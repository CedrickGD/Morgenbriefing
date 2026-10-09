package com.cedrickgd.morgenbriefing.ai

import android.util.Log
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * "⚡ Live" (docs/AI.md §2): one research run at a time with the Live provider [access] resolves
 * for it (its own one or the chat provider, D1), results stored per run in [store]. The state
 * shows the provider and why it is blocked, live ([AiProviderAccess.status]); a key the provider
 * refuses ([AiError.InvalidKey]) is reported to [AiProviderAccess.keyRejected].
 *
 * @param settings for the topics of a run (order, hidden ones).
 * @param briefOf today's (or any day's) stored morning brief, for "since when" and for the
 *   headlines the reader already knows.
 */
internal class LiveNewsServiceImpl(
    private val scope: CoroutineScope,
    private val settings: Flow<AppSettings>,
    private val access: AiProviderAccess,
    private val store: LiveStore,
    private val briefOf: suspend (LocalDate) -> Brief?,
    private val clock: Clock = Clock.system(BERLIN),
) : LiveNewsService {

    private val _state = MutableStateFlow(LiveState())
    override val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Bumped whenever articles were stored; drives [todayArticles]. */
    private val changes = MutableStateFlow(0)
    private var job: Job? = null
    private val publishLock = Mutex()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val todayArticles: Flow<List<LiveArticle>> = changes
        .mapLatest { store.day(today()) }
        .distinctUntilChanged()

    init {
        scope.launch {
            val last = store.lastRunAt(today())
            if (last != null) _state.update { it.copy(lastRunAt = it.lastRunAt ?: last) }
        }
        scope.launch {
            access.status(AiPurpose.LIVE).collect { status ->
                _state.update { it.copy(blocked = status.block, provider = status.spec.id, providerName = status.spec.name) }
            }
        }
    }

    override fun refresh(topicIds: List<String>) {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch { run(topicIds) }
        }
    }

    override fun cancel() {
        synchronized(this) { job?.cancel() }
    }

    override suspend fun articles(date: LocalDate): List<LiveArticle> = store.day(date)

    // -------------------------------------------------------------------------------------

    private suspend fun run(requestedTopics: List<String>) {
        val resolved = when (val resolution = access.resolve(AiPurpose.LIVE)) {
            is Resolution.Blocked -> {
                _state.update { it.copy(blocked = resolution.block) }
                return
            }
            is Resolution.Ready -> resolution
        }
        val engine = resolved.backend.live ?: run {
            _state.update { it.copy(blocked = AiBlock.LIVE_UNAVAILABLE) }
            return
        }
        val usage = resolved.usage
        val provider = resolved.auth.provider
        try {
            usage.beforeRequest()
        } catch (e: AiBlockedException) {
            _state.update { it.copy(blocked = e.block) }
            return
        }

        val current = settings.first()
        val topics = runTopics(requestedTopics, current)
        val now = ZonedDateTime.now(clock).withZoneSameInstant(BERLIN)
        val today = now.toLocalDate()
        val brief = try {
            briefOf(today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Brief for live run could not be read", e)
            null
        }
        val earlier = store.day(today)
        val since = sinceFor(now.toInstant(), brief, store.lastRunAt(today))
        val known = buildList {
            brief?.sections?.forEach { section -> section.items.forEach { add(AiPrompts.KnownHeadline(section.id, it.headline)) } }
            earlier.forEach { add(AiPrompts.KnownHeadline(it.topicId, it.item.headline)) }
        }
        val takenKeys = buildSet {
            brief?.sections?.forEach { section -> section.items.forEach { add(it.key) } }
            earlier.forEach { add(it.item.key) }
        }.toMutableSet()

        _state.update { it.copy(running = true, progress = listOf("Starte die Recherche …"), newCount = 0, error = null) }
        val model = resolved.model
        val topicIds = topics.map { it.id }
        var newCount = 0
        var attempts = 0

        val listener = object : LiveResearchListener {
            override fun onSearch(query: String) {
                val what = query.trim().take(80)
                progress(if (what.isEmpty()) "Suche im Netz …" else "Suche: $what")
            }
            override fun onFetch(url: String) = progress("Lese ${hostOf(url)}")
            override fun onToolCall(name: String) {
                if (name == LivePublish.TOOL_NAME) progress("Schreibe die Artikel …")
            }

            override suspend fun onPublish(rawInput: String): PublishReply = publishLock.withLock {
                attempts++
                val outcome = LivePublish.validate(rawInput, topicIds.toSet(), takenKeys, clock.instant())
                if (outcome.problems.isNotEmpty() && attempts <= MAX_CORRECTIONS) {
                    progress("Prüfe und korrigiere …")
                    return@withLock PublishReply(accepted = false, message = LivePublish.replyText(outcome))
                }
                // After the corrections budget: keep what is valid instead of looping on.
                if (outcome.accepted.isNotEmpty()) {
                    try {
                        store.append(clock.instant(), provider.key, model, topicIds, outcome.accepted)
                    } catch (e: IOException) {
                        Log.w(TAG, "Live articles could not be stored", e)
                        return@withLock PublishReply(accepted = false, message = "Speichern fehlgeschlagen. Beende den Lauf.")
                    }
                    outcome.accepted.forEach { takenKeys += it.item.key }
                    newCount += outcome.accepted.size
                    _state.update { it.copy(newCount = newCount) }
                    changes.update { it + 1 }
                }
                progress(if (newCount == 1) "1 neue Meldung" else "$newCount neue Meldungen")
                PublishReply(accepted = true, message = "ok")
            }
        }

        try {
            val result = engine.research(
                LiveResearchRequest(
                    auth = resolved.auth,
                    model = model,
                    systemPrompt = AiPrompts.liveSystem(resolved.spec.liveToolNote),
                    userPrompt = AiPrompts.liveRequest(now, since, topics, known),
                ),
                listener,
                usage,
            )
            val finishedAt = clock.instant()
            // Only a run that really finished counts as "checked up to now": it becomes the next
            // run's "since" and, after a restart, the status line. Refused, cut-off or
            // unfinished runs without articles leave no trace (runs with articles are stored anyway).
            if (result.end == TurnEnd.COMPLETE && newCount == 0) markChecked(finishedAt, provider.key, model, topicIds)
            val error = when (result.end) {
                TurnEnd.REFUSED -> AiError.Refusal(result.refusalCategory)
                TurnEnd.TRUNCATED -> if (newCount == 0) AiError.Truncated else null
                TurnEnd.INCOMPLETE -> if (newCount == 0) AiError.Unknown(INCOMPLETE_TEXT) else null
                TurnEnd.COMPLETE -> null
            }
            val lastLine = when {
                newCount > 0 -> "Fertig"
                error == null -> "Nichts Neues seit dem letzten Stand"
                else -> "Ohne Ergebnis beendet"
            }
            _state.update {
                it.copy(
                    running = false,
                    lastRunAt = if (error == null || newCount > 0) iso(finishedAt) else it.lastRunAt,
                    newCount = newCount,
                    error = error,
                    progress = it.progress.withLine(lastLine),
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                _state.update {
                    it.copy(
                        running = false,
                        newCount = newCount,
                        lastRunAt = if (newCount > 0) iso(clock.instant()) else it.lastRunAt,
                        progress = it.progress.withLine("Abgebrochen"),
                    )
                }
            }
            throw e
        } catch (e: AiBlockedException) {
            _state.update { it.copy(running = false, blocked = e.block, newCount = newCount) }
        } catch (e: AiException) {
            // Settings → KI says so before the panel shows the error.
            if (e.error == AiError.InvalidKey) access.keyRejected(provider, resolved.auth.apiKey)
            _state.update { it.copy(running = false, error = e.error, newCount = newCount) }
        }
    }

    private suspend fun markChecked(at: Instant, provider: String, model: String, topicIds: List<String>) {
        try {
            store.append(at, provider, model, topicIds, emptyList())
        } catch (e: IOException) {
            Log.w(TAG, "Live run could not be recorded", e)
        }
    }

    private fun progress(line: String) {
        _state.update { it.copy(progress = it.progress.withLine(line)) }
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(BERLIN))

    private fun iso(instant: Instant): String = instant.atZone(BERLIN).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private companion object {
        const val TAG = "LiveNews"
        const val MAX_PROGRESS_LINES = 8
        const val MAX_CORRECTIONS = 2

        /** A run that stopped before the model published anything (request budget, no publish call). */
        const val INCOMPLETE_TEXT = "Die Recherche ist nicht fertig geworden. Versuch's gleich nochmal."

        fun List<String>.withLine(line: String): List<String> =
            if (lastOrNull() == line) this else (this + line).takeLast(MAX_PROGRESS_LINES)
    }
}

/** Topics of a run: the requested known ids, or the user's visible topics when none were given. */
internal fun runTopics(requested: List<String>, settings: AppSettings): List<Topic> {
    val ids = requested.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val chosen = if (ids.isNotEmpty()) ids else settings.topicOrder.filter { it !in settings.hiddenTopics }
    return chosen.mapNotNull { Topics.byId(it) }.ifEmpty { Topics.ALL }
}

/**
 * Since when news counts as new: the later of the morning brief's `generatedAt` and the last
 * live run — but never further back than 24 h; without either, the last 12 h.
 */
internal fun sinceFor(now: Instant, brief: Brief?, lastRunAt: String?): Instant {
    val candidates = listOfNotNull(parseInstantOrNull(brief?.generatedAt), parseInstantOrNull(lastRunAt))
        .filter { !it.isAfter(now) }
    val floor = now.minus(Duration.ofHours(24))
    val since = candidates.maxOrNull() ?: now.minus(Duration.ofHours(12))
    return if (since.isBefore(floor)) floor else since
}
