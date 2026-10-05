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
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.util.AppSignals
import com.cedrickgd.morgenbriefing.util.attempt
import com.cedrickgd.morgenbriefing.util.flatten
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings: every change is written straight to the DataStore (theme and topics apply live,
 * the alarm is re-synced by [MorgenbriefingApp]). Also runs the place search and the token
 * check. The in-app updater has its own [UpdateViewModel].
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    /** Null until the DataStore emitted. */
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .catch { e -> Log.w(TAG, "Settings unavailable", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _tokenCheck = MutableStateFlow<TokenCheck>(TokenCheck.Idle)
    val tokenCheck: StateFlow<TokenCheck> = _tokenCheck.asStateFlow()

    private val _placeSearch = MutableStateFlow(PlaceSearchState())
    val placeSearch: StateFlow<PlaceSearchState> = _placeSearch.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar texts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var searchJob: Job? = null
    private var tokenJob: Job? = null

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

    /** Article text size, rounded to 5 % steps within 85–140 %. */
    fun setReaderTextScale(scale: Float) = edit { it.copy(readerTextScale = ReaderScale.normalize(scale)) }

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
     * to the briefs repo and reports the outcome in [tokenCheck].
     */
    fun saveAndTestToken(raw: String) {
        val token = raw.trim()
        if (token.isEmpty()) return
        tokenJob?.cancel()
        tokenJob = viewModelScope.launch {
            _tokenCheck.value = TokenCheck.Checking
            val saved = attempt { container.settings.setToken(token) }
            if (saved.isFailure) {
                Log.w(TAG, "Token could not be stored", saved.exceptionOrNull())
                _tokenCheck.value = TokenCheck.SaveFailed
                return@launch
            }
            AppSignals.notifyBriefSourceChanged()
            val check = attempt { container.briefs.checkAccess(token) }
            ensureActive()
            _tokenCheck.value = check.fold(
                onSuccess = { TokenCheck.Success },
                onFailure = { TokenCheck.Failed(it.toBriefErrorKind()) },
            )
        }
    }

    fun resetTokenCheck() {
        tokenJob?.cancel()
        _tokenCheck.value = TokenCheck.Idle
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

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                SettingsViewModel(app.container)
            }
        }
    }
}
