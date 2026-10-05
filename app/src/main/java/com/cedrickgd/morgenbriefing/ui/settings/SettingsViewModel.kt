package com.cedrickgd.morgenbriefing.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.ai.AiServices
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.ai.UsageSnapshot
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AiModel
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.util.AppSignals
import com.cedrickgd.morgenbriefing.util.attempt
import com.cedrickgd.morgenbriefing.util.flatten
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Settings: every change is written straight to the DataStore (theme and topics apply live,
 * the alarm is re-synced by [MorgenbriefingApp]). Also runs the place search, the GitHub token
 * and Claude key checks, the news connection status and the AI usage. The in-app updater has
 * its own [UpdateViewModel].
 *
 * @param ai the AI facade, or null where AI cannot run (Android 7) — never touched then.
 */
class SettingsViewModel(
    private val container: AppContainer,
    private val ai: (() -> AiServices)?,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
) : ViewModel() {

    /** Null until the DataStore emitted. */
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .catch { e -> Log.w(TAG, "Settings unavailable", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _tokenCheck = MutableStateFlow<TokenCheck>(TokenCheck.Idle)
    val tokenCheck: StateFlow<TokenCheck> = _tokenCheck.asStateFlow()

    private val _placeSearch = MutableStateFlow(PlaceSearchState())
    val placeSearch: StateFlow<PlaceSearchState> = _placeSearch.asStateFlow()

    private val _source = MutableStateFlow<SourceStatus>(SourceStatus.Unknown)

    /** Connection to the briefs repo (status hero + "News-Quelle"). */
    val source: StateFlow<SourceStatus> = _source.asStateFlow()

    private val _aiKey = MutableStateFlow<AiKeyState>(AiKeyState.Idle)
    val aiKey: StateFlow<AiKeyState> = _aiKey.asStateFlow()

    /** This month's AI spending; zeros where AI is unavailable. */
    val usage: StateFlow<UsageSnapshot> = aiUsageOrNull() ?: MutableStateFlow(UsageSnapshot()).asStateFlow()

    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)

    /** Last stored weather, for the mini weather on the place cards. */
    val weather: StateFlow<WeatherSnapshot?> = _weather.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar texts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var searchJob: Job? = null
    private var tokenJob: Job? = null
    private var sourceJob: Job? = null
    private var aiKeyJob: Job? = null
    private var lastSourceCheck: ZonedDateTime? = null

    init {
        loadWeather()
        // Token added or removed (here or in onboarding): update the connection status.
        viewModelScope.launch {
            container.settings.settings
                .map { it.hasToken }
                .distinctUntilChanged()
                .catch { e -> Log.w(TAG, "Settings unavailable", e) }
                .collect { hasToken ->
                    if (!hasToken) {
                        sourceJob?.cancel()
                        _source.value = SourceStatus.NoToken
                    } else if (tokenJob?.isActive != true) {
                        checkSource(force = true)
                    }
                }
        }
    }

    /** Screen resumed: fresh weather, and re-check the connection when the last check is old. */
    fun onResume() {
        loadWeather()
        checkSource(force = false)
    }

    fun setThemeMode(mode: ThemeMode) = edit { it.copy(themeMode = mode) }

    fun setAccent(accent: AccentId) = edit { it.copy(accent = accent) }

    fun setNotificationsEnabled(enabled: Boolean) = edit { it.copy(notificationsEnabled = enabled) }

    fun setNotifyTime(hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        edit(successMessage = "Dein Briefing kommt jetzt um ${clockLabel(h, m)} Uhr.") {
            it.copy(notifyHour = h, notifyMinute = m)
        }
    }

    fun setPlace(target: PlaceTarget, place: Place) = edit {
        when (target) {
            PlaceTarget.HOME -> it.copy(home = place)
            PlaceTarget.DAY -> it.copy(day = place)
        }
    }

    fun setDayWindow(start: Int, end: Int) {
        val (s, e) = DayWindow.normalize(start, end)
        edit { it.copy(dayStartHour = s, dayEndHour = e) }
    }

    /**
     * Shows or hides a topic. Hiding the last visible topic is refused with a hint — the brief
     * would otherwise be empty.
     */
    fun setTopicVisible(id: String, visible: Boolean) {
        viewModelScope.launch {
            var refused = false
            val saved = persist { current ->
                val order = TopicList.normalize(current.topicOrder)
                val hidden = TopicList.setVisible(order, current.hiddenTopics, id, visible)
                refused = !visible && id in order && id !in hidden
                current.copy(hiddenTopics = hidden)
            }
            if (saved && refused) _messages.send("Mindestens ein Thema bleibt sichtbar.")
        }
    }

    /** Stores a new topic order (after a drag or a move action). */
    fun setTopicOrder(order: List<String>) = edit { it.copy(topicOrder = TopicList.normalize(order)) }

    /** "Reihenfolge zurücksetzen": catalogue order; hidden topics stay hidden. */
    fun resetTopicOrder() = edit(successMessage = "Reihenfolge zurückgesetzt.") {
        it.copy(topicOrder = Topics.DEFAULT_ORDER)
    }

    /** "Zurücksetzen" of the topic grid: catalogue order and every topic visible again. */
    fun resetTopics() = edit(successMessage = "Alle Themen sind wieder da – in der ursprünglichen Reihenfolge.") {
        it.copy(topicOrder = Topics.DEFAULT_ORDER, hiddenTopics = emptySet())
    }

    /** Article text size, rounded to 5 % steps within 85–140 %. */
    fun setReaderTextScale(scale: Float) = edit { it.copy(readerTextScale = ReaderScale.normalize(scale)) }

    fun setAiModel(model: AiModel) = edit { it.copy(aiModel = model) }

    /** Monthly AI cap in whole dollars, 0 = none. */
    fun setAiMonthlyLimit(usd: Double) = edit { it.copy(aiMonthlyLimitUsd = AiLimit.normalize(usd)) }

    fun setLiveAutoRefresh(enabled: Boolean) = edit { it.copy(liveAutoRefresh = enabled) }

    /** Debounced (350 ms) geocoding search; queries shorter than 2 characters clear the list. */
    fun onPlaceQueryChange(query: String) {
        _placeSearch.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < PLACE_QUERY_MIN_LENGTH) {
            _placeSearch.update { it.copy(loading = false, failed = false, results = emptyList(), searchedFor = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _placeSearch.update { it.copy(loading = true, failed = false) }
            val result = attempt { container.weather.search(trimmed) }.flatten()
            ensureActive()
            val places = result.getOrNull()
            if (places == null) Log.w(TAG, "Place search failed", result.exceptionOrNull())
            _placeSearch.update {
                if (places != null) {
                    it.copy(loading = false, failed = false, results = places, searchedFor = trimmed)
                } else {
                    it.copy(loading = false, failed = true)
                }
            }
        }
    }

    /** Fresh, empty search whenever the picker opens. */
    fun resetPlaceSearch() {
        searchJob?.cancel()
        _placeSearch.value = PlaceSearchState()
    }

    /**
     * "Speichern & testen": stores the token (encrypted by the repository), then checks access
     * to the briefs repo and reports the outcome in [tokenCheck] and [source].
     */
    fun saveAndTestToken(raw: String) {
        val token = raw.trim()
        if (token.isEmpty()) return
        tokenJob?.cancel()
        sourceJob?.cancel()
        tokenJob = viewModelScope.launch {
            _tokenCheck.value = TokenCheck.Checking
            _source.value = SourceStatus.Checking
            val saved = attempt { container.settings.setToken(token) }
            if (saved.isFailure) {
                Log.w(TAG, "Token could not be stored", saved.exceptionOrNull())
                _tokenCheck.value = TokenCheck.SaveFailed
                _source.value = SourceStatus.Unknown
                return@launch
            }
            AppSignals.notifyBriefSourceChanged()
            val check = attempt { container.briefs.checkAccess(token) }
            ensureActive()
            val now = clock()
            lastSourceCheck = now
            _tokenCheck.value = check.fold(
                onSuccess = { TokenCheck.Success },
                onFailure = { TokenCheck.Failed(it.toBriefErrorKind()) },
            )
            _source.value = check.fold(
                onSuccess = { SourceStatus.Connected(now.toInstant().toEpochMilli()) },
                onFailure = { SourceStatus.Failed(it.toBriefErrorKind(), now.toInstant().toEpochMilli()) },
            )
        }
    }

    fun resetTokenCheck() {
        tokenJob?.cancel()
        _tokenCheck.value = TokenCheck.Idle
        if (_source.value == SourceStatus.Checking) checkSource(force = true)
    }

    fun removeToken() {
        viewModelScope.launch {
            val removed = attempt { container.settings.setToken(null) }
            if (removed.isSuccess) {
                AppSignals.notifyBriefSourceChanged()
                _messages.send("Token entfernt – du siehst wieder das Demo-Briefing.")
            } else {
                Log.w(TAG, "Token could not be removed", removed.exceptionOrNull())
                _messages.send("Der Token konnte nicht entfernt werden.")
            }
        }
    }

    /**
     * Checks the stored token against the briefs repo. Without [force] it skips when the last
     * check is recent or one is running.
     */
    fun checkSource(force: Boolean) {
        if (sourceJob?.isActive == true || tokenJob?.isActive == true) return
        val last = lastSourceCheck
        if (!force && last != null && last.plusMinutes(SOURCE_RECHECK_MINUTES).isAfter(clock())) return
        sourceJob = viewModelScope.launch {
            val token = attempt { container.settings.token() }.getOrNull()
            if (token.isNullOrBlank()) {
                _source.value = SourceStatus.NoToken
                return@launch
            }
            _source.value = SourceStatus.Checking
            val check = attempt { container.briefs.checkAccess(token) }
            ensureActive()
            val now = clock()
            lastSourceCheck = now
            val at = now.toInstant().toEpochMilli()
            _source.value = check.fold(
                onSuccess = { SourceStatus.Connected(at) },
                onFailure = {
                    Log.w(TAG, "Source check failed", it)
                    SourceStatus.Failed(it.toBriefErrorKind(), at)
                },
            )
        }
    }

    /**
     * "Speichern & testen" of the Claude key: one tiny request through the AI facade; only a key
     * that works is stored. [skipTest] stores without the request (offline).
     */
    fun saveAndTestAiKey(raw: String, skipTest: Boolean = false) {
        val key = raw.trim()
        if (key.isEmpty()) return
        val services = ai
        if (services == null) {
            _aiKey.value = AiKeyState.Failed(AiKeyProblem.UNSUPPORTED)
            return
        }
        aiKeyJob?.cancel()
        aiKeyJob = viewModelScope.launch {
            _aiKey.value = AiKeyState.Checking
            if (!skipTest) {
                val problem = try {
                    services().testKey(key).toProblem()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Anything unexpected from the facade (incl. a missing implementation).
                    Log.w(TAG, "Key test failed", e)
                    AiKeyProblem.UNKNOWN
                }
                ensureActive()
                if (problem != null) {
                    _aiKey.value = AiKeyState.Failed(problem)
                    return@launch
                }
            }
            val saved = attempt { container.settings.setAiKey(key) }
            _aiKey.value = if (saved.isSuccess) {
                AiKeyState.Saved
            } else {
                Log.w(TAG, "Key could not be stored", saved.exceptionOrNull())
                AiKeyState.Failed(AiKeyProblem.SAVE_FAILED)
            }
        }
    }

    fun resetAiKeyState() {
        aiKeyJob?.cancel()
        _aiKey.value = AiKeyState.Idle
    }

    fun removeAiKey() {
        viewModelScope.launch {
            val removed = attempt { container.settings.setAiKey(null) }
            _messages.send(
                if (removed.isSuccess) "Key entfernt – die KI-Funktionen sind jetzt aus." else "Der Key konnte nicht entfernt werden.",
            )
            if (removed.isFailure) Log.w(TAG, "Key could not be removed", removed.exceptionOrNull())
        }
    }

    private fun loadWeather() {
        viewModelScope.launch {
            val cached = attempt { container.weather.cached() }
                .onFailure { Log.w(TAG, "Cached weather unreadable", it) }
                .getOrNull()
            if (cached != null) _weather.value = cached
        }
    }

    /** The facade's usage flow; null on Android 7 or when the facade is not available. */
    private fun aiUsageOrNull(): StateFlow<UsageSnapshot>? {
        val services = ai ?: return null
        return try {
            services().usage.usage
        } catch (e: Throwable) {
            Log.w(TAG, "AI usage unavailable", e)
            null
        }
    }

    private fun edit(successMessage: String? = null, transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            if (persist(transform) && successMessage != null) _messages.send(successMessage)
        }
    }

    /** Writes [transform] atomically; a failure becomes a snackbar. Returns whether it was saved. */
    private suspend fun persist(transform: (AppSettings) -> AppSettings): Boolean {
        val result = attempt { container.settings.update(transform) }
        if (result.isFailure) {
            Log.w(TAG, "Settings update failed", result.exceptionOrNull())
            _messages.send("Das konnte gerade nicht gespeichert werden.")
        }
        return result.isSuccess
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val SOURCE_RECHECK_MINUTES = 10L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                SettingsViewModel(
                    container = app.container,
                    ai = if (AiSupport.isSupported) ({ app.ai }) else null,
                )
            }
        }
    }
}
