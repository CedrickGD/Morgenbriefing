package com.cedrickgd.morgenbriefing.ui.settings

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
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.NewsAge
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.model.WeatherCard
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.data.news.FeedCatalog
import com.cedrickgd.morgenbriefing.data.news.Regions
import com.cedrickgd.morgenbriefing.data.remote.FeedException
import com.cedrickgd.morgenbriefing.location.LocationException
import com.cedrickgd.morgenbriefing.location.LocationIssue
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** "Mein Standort" in the place picker. */
@Immutable
data class LocationState(
    val searching: Boolean = false,
    /** Why the last attempt failed; null when it worked or never ran. */
    val issue: LocationIssue? = null,
)

/** "Quelle hinzufügen": the feed behind the typed address is looked up before it is saved. */
@Immutable
sealed interface AddFeedState {
    data object Idle : AddFeedState
    data object Checking : AddFeedState
    data class Found(val url: String, val title: String, val itemCount: Int) : AddFeedState
    data class Failed(val reason: String) : AddFeedState
    data object Saved : AddFeedState
}

/**
 * Settings: every change is written straight to the DataStore (theme and topics apply live,
 * the alarm is re-synced by [MorgenbriefingApp]). Also runs the place search and "Mein
 * Standort", the feed lookup for own sources, and the mini weather of the place cards. The
 * in-app updater has its own [UpdateViewModel].
 */
class SettingsViewModel(
    private val container: AppContainer,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
) : ViewModel() {

    /** Null until the DataStore emitted. */
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .catch { e -> Log.w(TAG, "Settings unavailable", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _placeSearch = MutableStateFlow(PlaceSearchState())
    val placeSearch: StateFlow<PlaceSearchState> = _placeSearch.asStateFlow()

    private val _location = MutableStateFlow(LocationState())
    val location: StateFlow<LocationState> = _location.asStateFlow()

    private val _addFeed = MutableStateFlow<AddFeedState>(AddFeedState.Idle)
    val addFeed: StateFlow<AddFeedState> = _addFeed.asStateFlow()

    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)

    /**
     * Newest known weather (live from "Heute", else the stored copy) for the mini weather on the
     * place cards. Null until one is known.
     */
    val weather: StateFlow<WeatherSnapshot?> = _weather.asStateFlow()

    private val _weatherLoading = MutableStateFlow(true)

    /** True until the first weather (stored or live) arrived or the stored copy turned out missing. */
    val weatherLoading: StateFlow<Boolean> = _weatherLoading.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar texts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var searchJob: Job? = null
    private var locationJob: Job? = null
    private var addFeedJob: Job? = null

    init {
        loadWeather()
        // Live weather fetched by "Heute" (or anywhere else) updates the place cards right away.
        viewModelScope.launch {
            container.weather.latest
                .filterNotNull()
                .catch { e -> Log.w(TAG, "Weather updates unavailable", e) }
                .collect {
                    _weather.value = it
                    _weatherLoading.value = false
                }
        }
        // No stored copy and no live answer for a while: the cards say so instead of waiting forever.
        viewModelScope.launch {
            delay(WEATHER_WAIT_MS)
            _weatherLoading.value = false
        }
    }

    /** Screen resumed: fresh weather for the place cards. */
    fun onResume() {
        loadWeather()
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

    /**
     * Stores [place] for [target]. A new home place in Germany also moves the "Region" topic to
     * its Bundesland when the region was never chosen by hand (the first place of a new install).
     */
    fun setPlace(target: PlaceTarget, place: Place) = edit {
        when (target) {
            PlaceTarget.HOME -> {
                val guessed = Regions.guess(place)?.key
                it.copy(home = place, region = if (it.regionChosenByUser || guessed == null) it.region else guessed)
            }
            PlaceTarget.DAY -> it.copy(day = place)
        }
    }

    fun setDayWindow(start: Int, end: Int) {
        val (s, e) = DayWindow.normalize(start, end)
        edit { it.copy(dayStartHour = s, dayEndHour = e) }
    }

    /**
     * Shows or hides a topic. Hiding the last visible topic is refused with a hint — the feed
     * would otherwise be empty.
     */
    fun setTopicVisible(id: String, visible: Boolean) {
        viewModelScope.launch {
            var refused = false
            val saved = persist { current ->
                val catalogue = Topics.catalogue(current).map { it.id }
                val order = TopicList.normalize(current.topicOrder, catalogue)
                val hidden = TopicList.setVisible(order, current.hiddenTopics, id, visible)
                refused = !visible && id in order && id !in hidden
                current.copy(hiddenTopics = hidden)
            }
            if (saved && refused) _messages.send("Mindestens ein Thema bleibt sichtbar.")
        }
    }

    /** Stores a new topic order (after a drag or a move action). */
    fun setTopicOrder(order: List<String>) = edit {
        it.copy(topicOrder = TopicList.normalize(order, Topics.catalogue(it).map { t -> t.id }))
    }

    /** "Zurücksetzen" of the topic grid: catalogue order and every topic visible again (own topics stay). */
    fun resetTopics() = edit(successMessage = "Alle Themen sind wieder da – in der ursprünglichen Reihenfolge.") {
        it.copy(topicOrder = Topics.DEFAULT_ORDER + it.customTopics.map { t -> t.id }, hiddenTopics = emptySet())
    }

    /** Creates an own topic; it appears at the end of the order, visible. */
    fun addCustomTopic(title: String, emoji: String, seed: Long) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val topic = Topic(id = Topics.newCustomId(), title = clean.take(MAX_TITLE), emoji = emoji.trim().take(4), seed = seed, custom = true)
        edit(successMessage = "„${topic.title}“ angelegt – füge jetzt eine Quelle hinzu.") {
            it.copy(customTopics = it.customTopics + topic, topicOrder = it.topicOrder + topic.id)
        }
    }

    /** Renames / recolors an own topic. */
    fun updateCustomTopic(id: String, title: String, emoji: String, seed: Long) = edit {
        it.copy(customTopics = it.customTopics.map { t -> if (t.id == id) t.copy(title = title.trim().take(MAX_TITLE).ifEmpty { t.title }, emoji = emoji.trim().take(4), seed = seed) else t })
    }

    /** Deletes an own topic with its sources; its cached articles go too. */
    fun deleteCustomTopic(id: String) {
        viewModelScope.launch {
            val saved = persist {
                it.copy(
                    customTopics = it.customTopics.filterNot { t -> t.id == id },
                    customFeeds = it.customFeeds.filterNot { f -> f.topicId == id },
                    topicOrder = it.topicOrder - id,
                    hiddenTopics = it.hiddenTopics - id,
                )
            }
            if (saved) {
                attempt { container.news.forget(id) }.onFailure { Log.w(TAG, "Could not forget $id", it) }
                _messages.send("Thema gelöscht.")
            }
        }
    }

    /** Switches a built-in or own source on or off. */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) = edit {
        it.copy(disabledFeeds = if (enabled) it.disabledFeeds - sourceId else it.disabledFeeds + sourceId)
    }

    /** All sources of [topicId] back on. */
    fun enableAllSources(topicId: String) = edit { current ->
        val ids = FeedCatalog.allSourcesFor(topicId, current).map { it.id }.toSet()
        current.copy(disabledFeeds = current.disabledFeeds - ids)
    }

    /** The region preset of the "Region" topic; chosen by hand from now on. */
    fun setRegion(key: String) = edit { it.copy(region = Regions.byKey(key).key, regionChosenByUser = true) }

    fun setSectionSize(size: Int) = edit { it.copy(sectionSize = size.coerceIn(AppSettings.MIN_SECTION_SIZE, AppSettings.MAX_SECTION_SIZE)) }

    fun setShowImages(show: Boolean) = edit { it.copy(showImages = show) }

    fun setNewsAge(age: NewsAge) = edit { it.copy(newsAge = age) }

    fun setWeatherCard(card: WeatherCard, shown: Boolean) = edit {
        it.copy(weatherCards = if (shown) it.weatherCards + card else it.weatherCards - card)
    }

    /**
     * "Quelle hinzufügen": looks the feed up behind [input] (a feed URL or a website) and reports
     * what it found in [addFeed]; [saveFoundFeed] stores it.
     */
    fun lookUpFeed(input: String) {
        addFeedJob?.cancel()
        val text = input.trim()
        if (text.isEmpty()) {
            _addFeed.value = AddFeedState.Idle
            return
        }
        addFeedJob = viewModelScope.launch {
            _addFeed.value = AddFeedState.Checking
            val result = attempt { container.rss.discover(text) }
            ensureActive()
            _addFeed.value = result.fold(
                onSuccess = { AddFeedState.Found(it.url, it.title, it.itemCount) },
                onFailure = { e ->
                    Log.i(TAG, "Feed lookup failed: ${e.message}")
                    AddFeedState.Failed((e as? FeedException)?.reason ?: "Hier ist kein Feed zu finden")
                },
            )
        }
    }

    /** Stores the feed found by [lookUpFeed] for [topicId] under [name]. */
    fun saveFoundFeed(topicId: String, name: String) {
        val found = _addFeed.value as? AddFeedState.Found ?: return
        val clean = name.trim().ifEmpty { found.title }.take(MAX_TITLE)
        viewModelScope.launch {
            val saved = persist { current ->
                if (current.customFeeds.any { it.url == found.url && it.topicId == topicId }) return@persist current
                current.copy(customFeeds = current.customFeeds + FeedSource(FeedCatalog.newCustomId(), clean, found.url, topicId, builtIn = false))
            }
            if (saved) {
                _addFeed.value = AddFeedState.Saved
                _messages.send("„$clean“ hinzugefügt.")
            }
        }
    }

    fun resetAddFeed() {
        addFeedJob?.cancel()
        _addFeed.value = AddFeedState.Idle
    }

    /** Deletes an own source. */
    fun removeCustomFeed(sourceId: String) = edit(successMessage = "Quelle entfernt.") {
        it.copy(customFeeds = it.customFeeds.filterNot { f -> f.id == sourceId }, disabledFeeds = it.disabledFeeds - sourceId)
    }

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
        locationJob?.cancel()
        _placeSearch.value = PlaceSearchState()
        _location.value = LocationState()
    }

    /** "Mein Standort": asks the system for the place and stores it for [target] through [onFound]. */
    fun useCurrentLocation(onFound: (Place) -> Unit) {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            _location.value = LocationState(searching = true)
            val result = attempt { container.location.currentPlace() }
            ensureActive()
            result.fold(
                onSuccess = { place ->
                    _location.value = LocationState()
                    onFound(place)
                },
                onFailure = { e ->
                    Log.i(TAG, "Location unavailable: ${e.message}")
                    _location.value = LocationState(issue = (e as? LocationException)?.issue ?: LocationIssue.UNAVAILABLE)
                },
            )
        }
    }

    private fun loadWeather() {
        viewModelScope.launch {
            val cached = attempt { container.weather.cached() }
                .onFailure { Log.w(TAG, "Cached weather unreadable", it) }
                .getOrNull()
            if (cached != null) {
                _weather.value = cached
                _weatherLoading.value = false
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
        private const val WEATHER_WAIT_MS = 20_000L
        private const val MAX_TITLE = 40

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                SettingsViewModel(container = app.container)
            }
        }
    }
}
