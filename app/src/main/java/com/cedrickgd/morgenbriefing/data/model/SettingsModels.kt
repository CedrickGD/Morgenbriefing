package com.cedrickgd.morgenbriefing.data.model

import com.cedrickgd.morgenbriefing.data.news.Regions
import kotlinx.serialization.Serializable

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** e.g. "Sachsen, Deutschland" — only for display in the picker. */
    val region: String = "",
    /** ISO 3166-1 alpha-2 (`DE`), for the little flag; empty when unknown. */
    val countryCode: String = "",
) {
    companion object {
        val BERLIN = Place("Berlin", 52.5244, 13.4105, "Berlin, Deutschland", "DE")
        val LEIPZIG = Place("Leipzig", 51.3397, 12.3731, "Sachsen, Deutschland", "DE")
        val BAD_DUEBEN = Place("Bad Düben", 51.5928, 12.5853, "Sachsen, Deutschland", "DE")
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent palettes. [DYNAMIC] = Material You wallpaper colors (Android 12+); on older
 * devices it falls back to [MORGENROT]. Seeds are ARGB.
 */
enum class AccentId(val label: String, val seed: Long) {
    DYNAMIC("Material You", 0xFFC6613F),
    MORGENROT("Morgenrot", 0xFFC6613F),
    SONNE("Sonne", 0xFFE0A100),
    OZEAN("Ozean", 0xFF0E7C9B),
    WALD("Wald", 0xFF2E8B57),
    LAVENDEL("Lavendel", 0xFF7B61FF),
    BEERE("Beere", 0xFFC2185B),
    GRAPHIT("Graphit", 0xFF5F6B7A),
}

/** How far back the feed reaches. */
enum class NewsAge(val label: String, val hours: Int) {
    /** The last 24 hours — "tagesaktuell". */
    DAY("Heute", 24),
    TWO_DAYS("2 Tage", 48),
    WEEK("Eine Woche", 24 * 7),
    ALL("Alles, was die Quellen liefern", 0),
    ;

    companion object {
        fun fromName(name: String?): NewsAge? = entries.firstOrNull { it.name == name }
    }
}

/** The weather blocks of "Heute" the user can switch off (the hero stays). */
enum class WeatherCard(val label: String, val description: String) {
    WARNINGS("Warnungen", "Glätte, Sturm, Hitze – wenn etwas ansteht"),
    CURVE("Tagesverlauf", "Temperatur und Regen über den Tag"),
    CLOTHING("Anziehen", "Was du heute anziehen solltest"),
    ;

    companion object {
        val ALL: Set<WeatherCard> = entries.toSet()
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentId = AccentId.DYNAMIC,
    val notificationsEnabled: Boolean = true,
    val notifyHour: Int = 6,
    val notifyMinute: Int = 30,
    val home: Place = Place.BERLIN,
    val day: Place = Place.BERLIN,
    val dayStartHour: Int = 9,
    val dayEndHour: Int = 18,
    val onboardingDone: Boolean = false,
    /** `YYYY-MM-DD` of the last morning notification, to avoid duplicates. */
    val lastNotifiedDate: String? = null,
    /** Topic ids in the user's preferred order (see [Topics]); custom topics included. */
    val topicOrder: List<String> = Topics.DEFAULT_ORDER,
    /** Topic ids the user switched off. */
    val hiddenTopics: Set<String> = emptySet(),
    /** Topics the user created; their sources are in [customFeeds]. */
    val customTopics: List<Topic> = emptyList(),
    /** Feeds the user added (to built-in or custom topics). */
    val customFeeds: List<FeedSource> = emptyList(),
    /** Ids of feeds (built-in or custom) the user switched off. */
    val disabledFeeds: Set<String> = emptySet(),
    /** Key of the [Regions] preset behind the "Region" topic. */
    val region: String = Regions.DEFAULT,
    /** The user picked the region by hand (a new home place then no longer moves it). */
    val regionChosenByUser: Boolean = false,
    /** Articles a topic shows in "Für dich" before "Mehr zu …". */
    val sectionSize: Int = DEFAULT_SECTION_SIZE,
    /** Pictures in the feed (off: quiet typographic covers, less data). */
    val showImages: Boolean = true,
    /** Articles open in the app's own reader (full text, pictures, no ads); false: in the browser. */
    val readInApp: Boolean = true,
    val newsAge: NewsAge = NewsAge.DAY,
    /** Weather blocks shown on "Heute" besides the hero. */
    val weatherCards: Set<WeatherCard> = WeatherCard.ALL,
) {
    companion object {
        const val DEFAULT_SECTION_SIZE = 4
        const val MIN_SECTION_SIZE = 2
        const val MAX_SECTION_SIZE = 10
    }
}
