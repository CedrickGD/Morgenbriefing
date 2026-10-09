package com.cedrickgd.morgenbriefing.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.ai.compat.CompatBackend
import com.cedrickgd.morgenbriefing.ai.compat.CompatDialect
import com.cedrickgd.morgenbriefing.ai.compat.CompatModels
import com.cedrickgd.morgenbriefing.ai.gemini.GeminiBackend
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.openai.ResponsesBackend
import com.cedrickgd.morgenbriefing.ai.openai.ResponsesDialect
import com.cedrickgd.morgenbriefing.ai.provider.EngineKind
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The one DataStore for this month's AI spending (DataStore forbids two instances per file). */
private val Context.aiUsageStore: DataStore<Preferences> by preferencesDataStore(name = "ai_usage")

/**
 * Entry point for all AI features, owned by MorgenbriefingApp (`context.ai`). Safe to construct
 * and use on every API level (D9): the provider backends are created on first use, and the
 * Claude backend ([ClaudeBackend], the only code that touches `com.anthropic.*`) only behind
 * [AiSupport.sdkAvailable] — below Android 8 a Claude chat reports [AiBlock.UNSUPPORTED_DEVICE]
 * while every other provider runs.
 */
class AiServices(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeDir = File(context.applicationContext.filesDir, "store")

    private val claude: AiBackend? by lazy {
        if (!AiSupport.sdkAvailable) return@lazy null
        try {
            ClaudeBackend()
        } catch (e: LinkageError) {
            // Defensive: a vendor ROM without the Java APIs the SDK needs behaves like API < 26.
            Log.w(TAG, "Claude SDK not usable on this device", e)
            null
        }
    }

    /** Plain OkHttp + SSE for every provider without an SDK (shares the app's connection pool). */
    private val http: AiHttp by lazy { AiHttp(container.http) }

    /** One backend per provider of the families without an SDK, created on first use. */
    private val backends = ConcurrentHashMap<AiProviderId, AiBackend>()

    /**
     * The backend of the engine family of [id] (docs/AI-PROVIDERS.md §4.2), or null where it
     * cannot run (Claude below Android 8). Every family without an SDK is plain OkHttp + SSE and
     * exists on every API level.
     */
    internal fun backendFor(id: AiProviderId): AiBackend? = when (ProviderCatalog.spec(id).engine) {
        EngineKind.CLAUDE_SDK -> claude
        EngineKind.RESPONSES, EngineKind.GEMINI, EngineKind.CHAT_COMPLETIONS -> backends.getOrPut(id) { httpBackend(id, http) }
    }

    /** Whether [id] has a backend on this device, without creating it (no SDK class loading). */
    private fun isWired(id: AiProviderId): Boolean = when (ProviderCatalog.spec(id).engine) {
        EngineKind.CLAUDE_SDK -> AiSupport.sdkAvailable
        EngineKind.RESPONSES, EngineKind.GEMINI, EngineKind.CHAT_COMPLETIONS -> true
    }

    private val usageStore: UsageStore by lazy {
        UsageStore(
            dataStore = context.applicationContext.aiUsageStore,
            limitUsd = container.settings.settings.map { it.aiMonthlyLimitUsd },
            scope = scope,
        )
    }

    /** The usage sink of chat and Live requests with [id]; see [ProviderUsageSink]. */
    private fun requestUsage(ai: AiSettings, id: AiProviderId): UsageSink =
        ProviderUsageSink(usageStore, free = ai.costsNothing(id), onRecorded = ::markKeyWorks)

    private val access: AiProviderAccess = object : AiProviderAccess {
        override suspend fun resolve(purpose: AiPurpose): Resolution {
            val ai = settingsOrDefault().ai
            val id = ai.providerFor(purpose)
            val spec = ProviderCatalog.spec(id)
            val backend = backendFor(id)
            // The monthly limit is checked fresh by the sink right before the request.
            aiBlock(ai, purpose, AiSupport.sdkAvailable, UsageSnapshot(), hasBackend = backend != null)
                ?.let { return Resolution.Blocked(spec, it) }
            if (backend == null) return Resolution.Blocked(spec, AiBlock.UNSUPPORTED_DEVICE)
            if (purpose == AiPurpose.LIVE && backend.live == null) return Resolution.Blocked(spec, AiBlock.LIVE_UNAVAILABLE)
            val key = keyOrNull(id)
            if (key == null && !id.keyOptional) return Resolution.Blocked(spec, AiBlock.NO_KEY)
            return Resolution.Ready(
                spec = spec,
                auth = authFor(ai, id, key),
                model = ProviderCatalog.effectiveModel(ai, id),
                backend = backend,
                webSearch = ProviderCatalog.chatWebSearch(ai, id),
                usage = requestUsage(ai, id),
            )
        }

        override fun status(purpose: AiPurpose): Flow<AiStatus> =
            combine(container.settings.settings.map { it.ai }.distinctUntilChanged(), usageStore.usage) { ai, usage ->
                aiStatus(ai, purpose, AiSupport.sdkAvailable, usage, hasBackend = isWired(ai.providerFor(purpose)))
            }.distinctUntilChanged()

        override suspend fun keyRejected(provider: AiProviderId, key: String?) = markKeyRejected(provider, key)
    }

    private val liveService: LiveNewsServiceImpl by lazy {
        LiveNewsServiceImpl(
            scope = scope,
            settings = container.settings.settings,
            access = access,
            store = LiveStore(File(storeDir, "live"), container.json),
            briefOf = { date -> container.store.loadBrief(date) },
        )
    }

    private val chatService: ChatServiceImpl by lazy {
        ChatServiceImpl(
            scope = scope,
            access = access,
            store = ChatStore(File(storeDir, "chat"), container.json),
            contexts = ChatContextSource(::loadChatContext),
        )
    }

    val chat: ChatService get() = chatService
    val live: LiveNewsService get() = liveService
    val usage: UsageTracker get() = usageStore

    /**
     * Key or connection test for the settings (as few tokens as possible) with the candidate
     * configuration: [key] null = the stored key; a null field of [extras] = the stored
     * workspace id or server address, "" = none.
     */
    suspend fun testKey(provider: AiProviderId, key: String?, extras: KeyExtras = KeyExtras()): KeyCheckOutcome {
        if (!AiSupport.supports(provider)) return KeyCheckOutcome(AiKeyCheck.UNSUPPORTED)
        val backend = backendFor(provider) ?: return KeyCheckOutcome(AiKeyCheck.UNSUPPORTED)
        val candidate = key?.trim()?.takeIf { it.isNotEmpty() } ?: keyOrNull(provider)
        if (candidate == null && !provider.keyOptional) return KeyCheckOutcome(AiKeyCheck.INVALID)
        // Inner spaces, or an invisible / non-ASCII character from a copy (a zero-width space, a
        // BOM, an umlaut): such a key cannot travel in a header, so it is refused before any request.
        if (candidate != null && !AiHttp.isPlainKey(candidate)) return KeyCheckOutcome(AiKeyCheck.INVALID)
        val ai = settingsOrDefault().ai
        val stored = ai.of(provider)
        val tested = ai.with(
            provider,
            stored.copy(
                workspaceId = extras.workspaceId?.let { it.trim().ifEmpty { null } } ?: stored.workspaceId.takeIf { extras.workspaceId == null },
                baseUrl = extras.baseUrl?.let { it.trim().ifEmpty { null } } ?: stored.baseUrl.takeIf { extras.baseUrl == null },
            ),
        )
        // Priced like a request, but it does not confirm the stored key: the candidate may be another one.
        val usage = ProviderUsageSink(usageStore, free = tested.costsNothing(provider))
        return backend.keys.check(authFor(tested, provider, candidate), ProviderCatalog.effectiveModel(tested, provider), usage)
    }

    /**
     * The model ids of the own server at [baseUrl] ("Vom Server laden" in the model picker);
     * [key] null = the stored key. Empty when the server cannot be reached or lists nothing.
     */
    suspend fun listServerModels(baseUrl: String, key: String? = null): List<String> {
        val candidate = key?.trim()?.takeIf { it.isNotEmpty() } ?: keyOrNull(AiProviderId.CUSTOM)
        return CompatModels.list(http, baseUrl, candidate)
    }

    /**
     * Credentials and endpoint of [id] as configured in [ai]: the own server's address, else the
     * debug base URL (always null in release builds), else the catalog default.
     */
    private fun authFor(ai: AiSettings, id: AiProviderId, key: String?): ProviderAuth {
        val p = ai.of(id)
        return ProviderAuth(
            provider = id,
            apiKey = key,
            baseUrl = if (id == AiProviderId.CUSTOM) p.baseUrl else p.debugBaseUrl,
            workspaceId = p.workspaceId.takeIf { id == AiProviderId.ANTHROPIC },
            freeTier = ai.isGeminiFree(id),
        )
    }

    private suspend fun markKeyWorks(provider: AiProviderId) {
        try {
            container.settings.markAiKeyWorks(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${provider.key} key could not be marked as working", e)
        }
    }

    /** A chat or Live request with [key] ended in [AiError.InvalidKey]: Settings → KI says so. */
    private suspend fun markKeyRejected(provider: AiProviderId, key: String?) {
        try {
            container.settings.markAiKeyRejected(provider, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${provider.key} key could not be marked as rejected", e)
        }
    }

    private suspend fun keyOrNull(provider: AiProviderId): String? = try {
        container.settings.aiKey(provider)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${provider.key} key could not be read", e)
        null
    }

    private suspend fun settingsOrDefault(): AppSettings = try {
        container.settings.current()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Settings could not be read", e)
        AppSettings()
    }

    /**
     * Article or whole-day context for a chat: the brief of that day as the screens show it
     * ([briefFor]: the demo brief without a token, else the stored one, downloaded if missing),
     * plus that day's live articles.
     */
    private suspend fun loadChatContext(chat: ChatContext): LoadedChatContext? {
        val date = parseDateOrNull(chat.date) ?: return null
        val brief = briefFor(date, chat.articleKey)
        val live = liveService.articles(date)
        val key = chat.articleKey
        if (key == null) {
            if (brief == null && live.isEmpty()) return null
            return LoadedChatContext(AiPrompts.BRIEF_TITLE, AiPrompts.briefContext(date, brief, live))
        }
        brief?.findItem(key)?.let { (section, item) ->
            return LoadedChatContext(item.headline, AiPrompts.articleContext(item, date, Topics.forSection(section)))
        }
        live.firstOrNull { it.item.key == key }?.let { article ->
            return LoadedChatContext(
                article.item.headline,
                AiPrompts.articleContext(article.item, date, Topics.byId(article.topicId), liveFetchedAt = article.fetchedAt),
            )
        }
        // Demo mode: the bundled brief may carry another date than the one the screen passed.
        container.briefs.sampleBrief().findItem(key)?.let { (section, item) ->
            return LoadedChatContext(item.headline, AiPrompts.articleContext(item, date, Topics.forSection(section)))
        }
        return null
    }

    /**
     * The brief of [date] the screens show. Without a token "Heute" and the reader show the
     * bundled demo brief (TodayViewModel, ArticleViewModel.findBrief), so the chat takes it first
     * too — even when an older real brief of that day is stored with the same article ids.
     */
    private suspend fun briefFor(date: LocalDate, articleKey: String?): Brief? {
        if (!hasToken()) demoBriefForChat(container.briefs.sampleBrief(), date, articleKey)?.let { return it }
        container.store.loadBrief(date)?.let { return it }
        val fetched = try {
            container.briefs.day(date).brief
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Brief $date could not be loaded for the chat", e)
            null
        }
        return fetched ?: container.briefs.sampleBrief().takeIf { it.date == date.toString() }
    }

    private suspend fun hasToken(): Boolean = try {
        container.settings.current().hasToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Settings could not be read", e)
        false
    }

    private fun Brief.findItem(key: String): Pair<NewsSection, NewsItem>? {
        for (section in sections) {
            section.items.firstOrNull { it.key == key }?.let { return section to it }
        }
        return null
    }

    private companion object {
        const val TAG = "AiServices"
    }
}

/**
 * The backend of [id] for every engine family without an SDK (docs/AI-PROVIDERS.md §4.2): the
 * Responses dialects, Gemini and the Chat Completions dialects, all over [http].
 * @throws IllegalArgumentException for Claude, which needs the SDK ([AiServices] creates it).
 */
internal fun httpBackend(id: AiProviderId, http: AiHttp): AiBackend = when (ProviderCatalog.spec(id).engine) {
    EngineKind.RESPONSES -> ResponsesBackend(http, ResponsesDialect.of(id))
    EngineKind.GEMINI -> GeminiBackend(http)
    EngineKind.CHAT_COMPLETIONS -> CompatBackend(http, CompatDialect.of(id))
    EngineKind.CLAUDE_SDK -> throw IllegalArgumentException("${id.key} needs the Anthropic SDK")
}

/**
 * Without a token "Heute" and the reader show the bundled demo brief [sample]; a chat about
 * [date] (and [articleKey], when given) talks about that same text when it is that day's.
 */
internal fun demoBriefForChat(sample: Brief, date: LocalDate, articleKey: String?): Brief? {
    if (sample.date != date.toString()) return null
    if (articleKey != null && sample.sections.none { section -> section.items.any { it.key == articleKey } }) return null
    return sample
}
