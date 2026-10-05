package com.cedrickgd.morgenbriefing.ui.news

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.ui.brief.isBriefFor
import com.cedrickgd.morgenbriefing.util.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the reader shows. */
@Immutable
data class ArticleUiState(
    val loading: Boolean = true,
    /** Every article of the brief in reading order (arranged like on the Today screen). */
    val articles: List<ReaderArticle> = emptyList(),
    /** Page of the article the reader was opened with; 0 when it is no longer in the brief. */
    val startIndex: Int = 0,
    /** Reader text size factor from the settings (0.85–1.4). */
    val textScale: Float = 1f,
    /** No brief for this date could be loaded (offline, deleted, malformed link). */
    val failed: Boolean = false,
    /** Reference for relative publish times ("vor 3 Std."). */
    val now: ZonedDateTime = ZonedDateTime.now(BERLIN),
)

/**
 * Loads the brief of [date] for the reader and exposes its articles as one flat list.
 *
 * Where the brief comes from, first match wins (a brief only counts if it contains [key];
 * otherwise the first brief of that date is used and the reader starts at the top):
 * 1. without a token: the bundled demo brief (that is what the Today screen shows then),
 * 2. the offline store,
 * 3. with a token and [date] = today: `latest.json`,
 * 4. with a token: `briefs/DATE.json` via [com.cedrickgd.morgenbriefing.data.BriefRepository.day].
 *
 * @param date null when the route carried a malformed date.
 */
class ArticleViewModel(
    private val container: AppContainer,
    val date: LocalDate?,
    private val key: String,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
) : ViewModel() {

    private sealed interface Source {
        data object Loading : Source
        data class Ready(val brief: Brief, val now: ZonedDateTime) : Source
        data object Missing : Source
    }

    private val source = MutableStateFlow<Source>(if (date == null) Source.Missing else Source.Loading)

    private val settings: Flow<AppSettings> = container.settings.settings
        .catch { e ->
            Log.w(TAG, "Settings unavailable in the reader", e)
            emit(AppSettings())
        }

    private val articles: Flow<List<ReaderArticle>> = combine(
        source,
        settings.map { TopicPrefs(it) }.distinctUntilChanged(),
    ) { src, prefs ->
        if (src is Source.Ready) readerArticles(src.brief, prefs, focusKey = key) else emptyList()
    }.distinctUntilChanged()

    val state: StateFlow<ArticleUiState> = combine(
        source,
        articles,
        settings.map { it.readerTextScale }.distinctUntilChanged(),
    ) { src, list, scale ->
        ArticleUiState(
            loading = src is Source.Loading,
            articles = list,
            startIndex = list.indexOfFirst { it.item.key == key }.coerceAtLeast(0),
            textScale = scale,
            failed = src is Source.Missing,
            now = (src as? Source.Ready)?.now ?: clock(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ArticleUiState(loading = date != null, failed = date == null, now = clock()),
    )

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        val day = date ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            source.value = Source.Loading
            val brief = attempt { findBrief(day) }
                .onFailure { Log.w(TAG, "Could not load the brief of $day", it) }
                .getOrNull()
            ensureActive()
            source.value = if (brief != null) Source.Ready(brief, clock()) else Source.Missing
        }
    }

    /** A− / A+: one step smaller or larger, stored in the settings. */
    fun changeTextScale(larger: Boolean) {
        viewModelScope.launch {
            attempt { container.settings.update { it.copy(readerTextScale = nextTextScale(it.readerTextScale, larger)) } }
                .onFailure { Log.w(TAG, "Could not store the reader text size", it) }
        }
    }

    private suspend fun findBrief(day: LocalDate): Brief? {
        var firstOfDay: Brief? = null

        /** The candidate if it is the right day and has the article; remembers the first of the day. */
        fun pick(candidate: Brief?): Brief? {
            if (candidate == null || !isBriefFor(candidate, day)) return null
            if (firstOfDay == null) firstOfDay = candidate
            return candidate.takeIf { it.containsArticle(key) }
        }

        val hasToken = attempt { container.settings.current().hasToken }.getOrDefault(false)
        if (!hasToken) {
            val sample = attempt { withContext(Dispatchers.IO) { container.briefs.sampleBrief() } }.getOrNull()
            pick(sample)?.let { return it }
        }
        pick(attempt { container.store.loadBrief(day) }.getOrNull())?.let { return it }
        if (hasToken) {
            if (day == clock().toLocalDate()) {
                val latest = when (val load = attempt { container.briefs.latest() }.getOrNull()) {
                    is BriefLoad.Loaded -> load.brief
                    is BriefLoad.Demo -> load.brief
                    is BriefLoad.Failed -> load.cached
                    null -> null
                }
                pick(latest)?.let { return it }
            }
            pick(attempt { container.briefs.day(day) }.getOrNull()?.brief)?.let { return it }
        }
        return firstOfDay
    }

    companion object {
        private const val TAG = "ArticleViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(date: LocalDate?, key: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                ArticleViewModel(app.container, date, key)
            }
        }
    }
}
