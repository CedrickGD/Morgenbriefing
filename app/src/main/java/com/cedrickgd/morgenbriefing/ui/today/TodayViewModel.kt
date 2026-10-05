package com.cedrickgd.morgenbriefing.ui.today

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
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.ai.LiveNewsService
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.ui.brief.WeatherUi
import com.cedrickgd.morgenbriefing.ui.brief.isBriefFor
import com.cedrickgd.morgenbriefing.ui.brief.toWeatherUi
import com.cedrickgd.morgenbriefing.ui.news.TopicPrefs
import com.cedrickgd.morgenbriefing.ui.settings.TopicList
import com.cedrickgd.morgenbriefing.ui.news.topicPrefs
import com.cedrickgd.morgenbriefing.ui.news.withTopicVisible
import com.cedrickgd.morgenbriefing.util.AppSignals
import com.cedrickgd.morgenbriefing.util.attempt
import com.cedrickgd.morgenbriefing.util.flatten
import com.cedrickgd.morgenbriefing.util.isOlderThan
import com.cedrickgd.morgenbriefing.util.isWithin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** Everything the Today screen renders. */
@Immutable
data class TodayUiState(
    /** Drives the date line, greeting and relative publish times; refreshed on every resume. */
    val now: ZonedDateTime,
    /** Live or cached weather; null until the first snapshot is known. */
    val weather: WeatherUi? = null,
    val weatherLoading: Boolean = true,
    /** The last weather refresh failed (the shown snapshot, if any, is older). */
    val weatherFailed: Boolean = false,
    /** Null until the first brief (cached, demo or live) is known. */
    val brief: BriefLoad? = null,
    val briefLoading: Boolean = true,
    /** User-initiated pull-to-refresh is running. */
    val refreshing: Boolean = false,
) {
    val today: LocalDate get() = now.toLocalDate()
}

/** Content of the onboarding sheet; null = sheet hidden. */
@Immutable
data class OnboardingUi(val hasToken: Boolean)

/**
 * Today: shows cached weather and the last brief instantly, then refreshes both in parallel.
 * Weather is refreshed again on resume once it is older than 10 minutes; the brief whenever
 * the one on screen is not today's (e.g. the app stayed open overnight or the routine was late).
 * Also drives "⚡ Live" (docs/AI.md §2) and showing/hiding topics from the feed.
 *
 * @param liveService the live research service; null below Android 8 (no AI there).
 */
class TodayViewModel(
    private val container: AppContainer,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
    liveService: () -> LiveNewsService? = { null },
) : ViewModel() {

    /** Resolved once; a service that cannot be created (should not happen) disables Live quietly. */
    private val live: LiveNewsService? = try {
        liveService()
    } catch (e: Exception) {
        Log.w(TAG, "Live news unavailable", e)
        null
    } catch (e: LinkageError) {
        // An SDK class that does not load on this device must never take the Today page down.
        Log.w(TAG, "Live news unavailable on this device", e)
        null
    }

    private val _state = MutableStateFlow(TodayUiState(now = clock()))
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** Onboarding sheet content while `onboardingDone` is false. Starts hidden: no flash. */
    val onboarding: StateFlow<OnboardingUi?> = container.settings.settings
        .map { settings -> if (settings.onboardingDone) null else OnboardingUi(settings.hasToken) }
        .distinctUntilChanged()
        .catch { e -> Log.w(TAG, "Settings unavailable for onboarding", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Topic order and switched-off topics; they arrange the brief's sections on screen. */
    val topicPrefs: StateFlow<TopicPrefs> = container.settings.topicPrefs()
        .catch { e -> Log.w(TAG, "Settings unavailable for topics", e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TopicPrefs())

    /** "⚡ Live": service state, today's live articles and the AI settings in one. */
    val liveUi: StateFlow<LiveUi> = liveUiFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), if (live == null) LiveUi.Unsupported else LiveUi())

    private var weatherJob: Job? = null
    private var briefJob: Job? = null
    private var lastWeatherAttempt: Instant? = null
    private var lastBriefAttempt: Instant? = null

    init {
        viewModelScope.launch { showCachedWeather() }
        viewModelScope.launch { showCachedBrief() }
        refreshWeather()
        refreshBrief()
        observeInputs()
    }

    /** Pull-to-refresh: weather and brief in parallel; joins refreshes that are already running. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, now = clock()) }
        viewModelScope.launch {
            try {
                joinAll(refreshWeather(), refreshBrief())
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Called on every resume of the screen (also after a notification tap). */
    fun onResume() {
        val now = clock()
        _state.update { it.copy(now = now) }
        val current = _state.value
        val instant = now.toInstant()

        val weatherStale = current.weather == null ||
            isOlderThan(current.weather.snapshot.fetchedAt, instant, WEATHER_MAX_AGE)
        if (weatherStale && !isWithin(lastWeatherAttempt, instant, WEATHER_MAX_AGE)) refreshWeather()

        val brief = current.brief
        val briefIsToday = when (brief) {
            is BriefLoad.Loaded -> isBriefFor(brief.brief, now.toLocalDate())
            is BriefLoad.Demo -> true // nothing newer to fetch without a token
            is BriefLoad.Failed, null -> false
        }
        if (!briefIsToday && !isWithin(lastBriefAttempt, instant, BRIEF_RETRY_AFTER)) refreshBrief()
    }

    fun retryWeather() {
        refreshWeather(force = true)
    }

    fun retryBrief() {
        refreshBrief(force = true)
    }

    /** "Jetzt aktualisieren": live research for [topicIds] (the visible topics). */
    fun refreshLive(topicIds: List<String>) {
        val service = live ?: return
        if (!liveUi.value.canRefresh || topicIds.isEmpty()) return
        try {
            service.refresh(topicIds)
        } catch (e: Exception) {
            Log.w(TAG, "Live refresh could not start", e)
        }
    }

    fun cancelLive() {
        try {
            live?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Live refresh could not be cancelled", e)
        }
    }

    /** "⚡ Live" was opened: refresh by itself when the user allowed it and the last run is old. */
    fun onLiveOpened(topicIds: List<String>) {
        if (shouldAutoRefresh(liveUi.value, clock().toInstant())) refreshLive(topicIds)
    }

    /** Shows or hides a topic in the feed (same setting as in Einstellungen → Themen). */
    fun setTopicVisible(topicId: String, visible: Boolean) {
        viewModelScope.launch {
            attempt {
                container.settings.update { settings ->
                    val next = TopicPrefs(settings).withTopicVisible(topicId, visible) ?: return@update settings
                    settings.copy(hiddenTopics = next.hidden)
                }
            }.onFailure { Log.w(TAG, "Could not store topic visibility", it) }
        }
    }

    /** Stores a new topic order (the "Themen" sheet; same setting as in Einstellungen → Themen). */
    fun setTopicOrder(order: List<String>) {
        viewModelScope.launch {
            attempt { container.settings.update { it.copy(topicOrder = TopicList.normalize(order)) } }
                .onFailure { Log.w(TAG, "Could not store topic order", it) }
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch {
            attempt { container.settings.update { it.copy(onboardingDone = true) } }
                .onFailure { Log.w(TAG, "Could not store onboarding state", it) }
        }
    }

    /**
     * Loads live weather for the current settings. A running refresh is reused unless [force]
     * (settings changed), in which case it is replaced so the result matches the new places.
     */
    private fun refreshWeather(force: Boolean = false): Job {
        weatherJob?.let { running ->
            if (running.isActive) {
                if (!force) return running
                running.cancel()
            }
        }
        lastWeatherAttempt = clock().toInstant()
        return viewModelScope.launch {
            _state.update { it.copy(weatherLoading = true) }
            val result = attempt { container.weather.load(container.settings.current()) }.flatten()
            ensureActive()
            val snapshot = result.getOrNull()
            if (snapshot != null) {
                val ui = withContext(Dispatchers.Default) { snapshot.toWeatherUi() }
                ensureActive()
                _state.update { it.copy(weather = ui, weatherLoading = false, weatherFailed = false) }
            } else {
                Log.w(TAG, "Weather refresh failed", result.exceptionOrNull())
                _state.update { it.copy(weatherLoading = false, weatherFailed = true) }
            }
        }.also { weatherJob = it }
    }

    /** Fetches `latest.json` (or the demo brief without a token). Same reuse rules as weather. */
    private fun refreshBrief(force: Boolean = false): Job {
        briefJob?.let { running ->
            if (running.isActive) {
                if (!force) return running
                running.cancel()
            }
        }
        lastBriefAttempt = clock().toInstant()
        return viewModelScope.launch {
            _state.update { it.copy(briefLoading = true) }
            val load = attempt { container.briefs.latest() }.getOrElse { e ->
                Log.w(TAG, "Brief refresh failed", e)
                BriefLoad.Failed(BriefErrorKind.UNKNOWN, cached = null)
            }
            ensureActive()
            _state.update { current ->
                current.copy(
                    brief = mergeBriefLoad(current.brief, load),
                    briefLoading = false,
                    now = clock(),
                )
            }
        }.also { briefJob = it }
    }

    private suspend fun showCachedWeather() {
        val cached = attempt { container.weather.cached() }
            .onFailure { Log.w(TAG, "Cached weather unavailable", it) }
            .getOrNull() ?: return
        val ui = withContext(Dispatchers.Default) { cached.toWeatherUi() }
        // Never overwrite a live result that arrived first.
        _state.update { if (it.weather == null) it.copy(weather = ui) else it }
    }

    private suspend fun showCachedBrief() {
        val hasToken = attempt { container.settings.current().hasToken }.getOrNull() ?: return
        val instant: BriefLoad? = if (hasToken) {
            newestCachedBrief()?.let { BriefLoad.Loaded(it, fromCache = true) }
        } else {
            attempt { withContext(Dispatchers.IO) { container.briefs.sampleBrief() } }
                .onFailure { Log.w(TAG, "Sample brief unavailable", it) }
                .getOrNull()
                ?.let { BriefLoad.Demo(it) }
        }
        if (instant != null) _state.update { if (it.brief == null) it.copy(brief = instant) else it }
    }

    private suspend fun newestCachedBrief(): Brief? {
        val days = attempt { container.store.listDays() }.getOrDefault(emptyList())
        for (day in days.take(CACHE_LOOKBACK_DAYS)) {
            attempt { container.store.loadBrief(day) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Reloads when the user changes places/time window or the token in the settings. */
    private fun observeInputs() {
        viewModelScope.launch {
            container.settings.settings
                .map { WeatherInputs(it) }
                .distinctUntilChanged()
                .drop(1)
                .catch { e -> Log.w(TAG, "Settings unavailable", e) }
                .collect { refreshWeather(force = true) }
        }
        viewModelScope.launch {
            container.settings.settings
                .map { it.hasToken }
                .distinctUntilChanged()
                .drop(1)
                .catch { e -> Log.w(TAG, "Settings unavailable", e) }
                .collect { refreshBrief(force = true) }
        }
        viewModelScope.launch {
            AppSignals.briefSourceChanged.collect { refreshBrief(force = true) }
        }
    }

    private fun liveUiFlow(): Flow<LiveUi> {
        val service = live ?: return flowOf(LiveUi.Unsupported)
        val aiSettings = container.settings.settings
            .map { it.hasAiKey to it.liveAutoRefresh }
            .distinctUntilChanged()
            .catch { e ->
                Log.w(TAG, "Settings unavailable for live news", e)
                emit(false to false)
            }
        val articles = service.todayArticles
            .onStart { emit(emptyList()) }
            .catch { e ->
                Log.w(TAG, "Live articles unavailable", e)
                emit(emptyList())
            }
        return combine(service.state, articles, aiSettings) { state, list, (hasKey, auto) ->
            liveUi(state, list, hasKey, auto)
        }.catch { e ->
            Log.w(TAG, "Live state unavailable", e)
            emit(LiveUi())
        }
    }

    /** The settings that change what the weather call returns. */
    private data class WeatherInputs(val home: Place, val day: Place, val start: Int, val end: Int) {
        constructor(settings: AppSettings) :
            this(settings.home, settings.day, settings.dayStartHour, settings.dayEndHour)
    }

    companion object {
        private const val TAG = "TodayViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val CACHE_LOOKBACK_DAYS = 14
        private val WEATHER_MAX_AGE: Duration = Duration.ofMinutes(10)
        private val BRIEF_RETRY_AFTER: Duration = Duration.ofMinutes(10)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                TodayViewModel(
                    container = app.container,
                    // Below Android 8 nothing of ai/ beyond the support check is touched.
                    liveService = { if (AiSupport.isSupported) app.ai.live else null },
                )
            }
        }
    }
}
