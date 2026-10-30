package com.cedrickgd.morgenbriefing.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.NewsAge
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.model.WeatherCard
import com.cedrickgd.morgenbriefing.data.news.Regions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException

/** The one DataStore instance per process (DataStore forbids two instances on the same file). */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences DataStore named "settings". Nothing secret lives here any more (no tokens, no
 * API keys), so the store takes part in Android backups. Places, custom topics and custom
 * feeds are stored as JSON strings.
 */
class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.settingsStore

    /** Always emits; unreadable storage falls back to the defaults instead of crashing. */
    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /**
     * Atomically maps the stored settings through [transform] and writes every field back.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    // ---------------------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------------------

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val home = placeOrDefault(this[Keys.HOME_PLACE], defaults.home)
        return AppSettings(
            themeMode = enumOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
            accent = enumOrDefault(this[Keys.ACCENT], defaults.accent),
            notificationsEnabled = this[Keys.NOTIFICATIONS_ENABLED] ?: defaults.notificationsEnabled,
            notifyHour = this[Keys.NOTIFY_HOUR]?.takeIf { it in 0..23 } ?: defaults.notifyHour,
            notifyMinute = this[Keys.NOTIFY_MINUTE]?.takeIf { it in 0..59 } ?: defaults.notifyMinute,
            home = home,
            day = placeOrDefault(this[Keys.DAY_PLACE], defaults.day),
            dayStartHour = this[Keys.DAY_START_HOUR]?.takeIf { it in 0..23 } ?: defaults.dayStartHour,
            dayEndHour = this[Keys.DAY_END_HOUR]?.takeIf { it in 0..23 } ?: defaults.dayEndHour,
            onboardingDone = this[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
            lastNotifiedDate = this[Keys.LAST_NOTIFIED_DATE]?.takeIf { it.isNotBlank() },
            topicOrder = topicOrderOrDefault(this[Keys.TOPIC_ORDER], defaults.topicOrder),
            hiddenTopics = idSet(this[Keys.HIDDEN_TOPICS]),
            customTopics = decodeList(this[Keys.CUSTOM_TOPICS], Topic.serializer()).filter { it.id.isNotBlank() && it.title.isNotBlank() },
            customFeeds = decodeList(this[Keys.CUSTOM_FEEDS], FeedSource.serializer()).filter { it.id.isNotBlank() && it.url.isNotBlank() },
            disabledFeeds = idSet(this[Keys.DISABLED_FEEDS]),
            // Installs from before the region preset existed: guess it from the home place.
            region = this[Keys.REGION]?.takeIf { it.isNotBlank() } ?: Regions.guess(home)?.key ?: defaults.region,
            regionChosenByUser = this[Keys.REGION_CHOSEN] ?: defaults.regionChosenByUser,
            sectionSize = this[Keys.SECTION_SIZE]?.coerceIn(AppSettings.MIN_SECTION_SIZE, AppSettings.MAX_SECTION_SIZE) ?: defaults.sectionSize,
            showImages = this[Keys.SHOW_IMAGES] ?: defaults.showImages,
            readInApp = this[Keys.READ_IN_APP] ?: defaults.readInApp,
            newsAge = NewsAge.fromName(this[Keys.NEWS_AGE]) ?: defaults.newsAge,
            weatherCards = this[Keys.WEATHER_CARDS]?.let { raw ->
                idSet(raw).mapNotNull { name -> WeatherCard.entries.firstOrNull { it.name == name } }.toSet()
            } ?: defaults.weatherCards,
        )
    }

    /** Stored order first; catalogue topics added in a newer app version are appended. */
    private fun topicOrderOrDefault(raw: String?, default: List<String>): List<String> {
        val stored = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (stored.isEmpty()) return default
        return stored + default.filterNot { it in stored }
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.ACCENT] = s.accent.name
        this[Keys.NOTIFICATIONS_ENABLED] = s.notificationsEnabled
        this[Keys.NOTIFY_HOUR] = s.notifyHour.coerceIn(0, 23)
        this[Keys.NOTIFY_MINUTE] = s.notifyMinute.coerceIn(0, 59)
        this[Keys.HOME_PLACE] = AppJson.encodeToString(Place.serializer(), s.home)
        this[Keys.DAY_PLACE] = AppJson.encodeToString(Place.serializer(), s.day)
        this[Keys.DAY_START_HOUR] = s.dayStartHour.coerceIn(0, 23)
        this[Keys.DAY_END_HOUR] = s.dayEndHour.coerceIn(0, 23)
        this[Keys.ONBOARDING_DONE] = s.onboardingDone
        val last = s.lastNotifiedDate
        if (last.isNullOrBlank()) remove(Keys.LAST_NOTIFIED_DATE) else this[Keys.LAST_NOTIFIED_DATE] = last
        this[Keys.TOPIC_ORDER] = s.topicOrder.filter { it.isNotBlank() && ',' !in it }.distinct().joinToString(",")
        this[Keys.HIDDEN_TOPICS] = s.hiddenTopics.filter { it.isNotBlank() && ',' !in it }.sorted().joinToString(",")
        this[Keys.CUSTOM_TOPICS] = AppJson.encodeToString(ListSerializer(Topic.serializer()), s.customTopics)
        this[Keys.CUSTOM_FEEDS] = AppJson.encodeToString(ListSerializer(FeedSource.serializer()), s.customFeeds)
        this[Keys.DISABLED_FEEDS] = s.disabledFeeds.filter { it.isNotBlank() && ',' !in it }.sorted().joinToString(",")
        this[Keys.REGION] = s.region
        this[Keys.REGION_CHOSEN] = s.regionChosenByUser
        this[Keys.SECTION_SIZE] = s.sectionSize.coerceIn(AppSettings.MIN_SECTION_SIZE, AppSettings.MAX_SECTION_SIZE)
        this[Keys.SHOW_IMAGES] = s.showImages
        this[Keys.READ_IN_APP] = s.readInApp
        this[Keys.NEWS_AGE] = s.newsAge.name
        this[Keys.WEATHER_CARDS] = s.weatherCards.map { it.name }.sorted().joinToString(",")
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun idSet(raw: String?): Set<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private fun placeOrDefault(raw: String?, default: Place): Place {
        if (raw.isNullOrBlank()) return default
        return try {
            AppJson.decodeFromString(Place.serializer(), raw)
        } catch (_: SerializationException) {
            default
        } catch (_: IllegalArgumentException) {
            default
        }
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            AppJson.decodeFromString(ListSerializer(serializer), raw)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_HOUR = intPreferencesKey("notify_hour")
        val NOTIFY_MINUTE = intPreferencesKey("notify_minute")
        val HOME_PLACE = stringPreferencesKey("home_place")
        val DAY_PLACE = stringPreferencesKey("day_place")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val DAY_END_HOUR = intPreferencesKey("day_end_hour")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LAST_NOTIFIED_DATE = stringPreferencesKey("last_notified_date")
        val TOPIC_ORDER = stringPreferencesKey("topic_order")
        val HIDDEN_TOPICS = stringPreferencesKey("hidden_topics")
        val CUSTOM_TOPICS = stringPreferencesKey("custom_topics")
        val CUSTOM_FEEDS = stringPreferencesKey("custom_feeds")
        val DISABLED_FEEDS = stringPreferencesKey("disabled_feeds")
        val REGION = stringPreferencesKey("region")
        val REGION_CHOSEN = booleanPreferencesKey("region_chosen")
        val SECTION_SIZE = intPreferencesKey("section_size")
        val SHOW_IMAGES = booleanPreferencesKey("show_images")
        val READ_IN_APP = booleanPreferencesKey("read_in_app")
        val NEWS_AGE = stringPreferencesKey("news_age")
        val WEATHER_CARDS = stringPreferencesKey("weather_cards")
    }
}
