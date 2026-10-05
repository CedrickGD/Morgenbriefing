package com.cedrickgd.morgenbriefing.domain

/** Coarse weather condition derived from a WMO code; drives icons, backdrop and texts. */
enum class Condition(val label: String, val emojiDay: String, val emojiNight: String) {
    CLEAR("klar", "☀️", "🌙"),
    MOSTLY_CLEAR("überwiegend sonnig", "🌤️", "🌙"),
    PARTLY_CLOUDY("teils bewölkt", "⛅", "☁️"),
    OVERCAST("bedeckt", "☁️", "☁️"),
    FOG("Nebel", "🌫️", "🌫️"),
    DRIZZLE("Nieselregen", "🌦️", "🌧️"),
    RAIN("Regen", "🌧️", "🌧️"),
    HEAVY_RAIN("Starkregen", "🌧️", "🌧️"),
    FREEZING_RAIN("gefrierender Regen", "🧊", "🧊"),
    SNOW("Schnee", "🌨️", "🌨️"),
    SHOWERS("Schauer", "🌦️", "🌧️"),
    SNOW_SHOWERS("Schneeschauer", "🌨️", "🌨️"),
    THUNDER("Gewitter", "⛈️", "⛈️");

    fun emoji(isDay: Boolean): String = if (isDay) emojiDay else emojiNight

    /** "sonnig" reads better than "klar" during the day. */
    fun labelFor(isDay: Boolean): String = if (this == CLEAR && isDay) "sonnig" else label

    val isWet: Boolean
        get() = this in setOf(DRIZZLE, RAIN, HEAVY_RAIN, FREEZING_RAIN, SHOWERS, SNOW, SNOW_SHOWERS, THUNDER)

    companion object {
        /** WMO 4677 subset used by Open-Meteo. */
        fun fromCode(code: Int): Condition = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57, 66, 67 -> FREEZING_RAIN
            61, 63 -> RAIN
            65 -> HEAVY_RAIN
            71, 73, 75, 77 -> SNOW
            80, 81, 82 -> SHOWERS
            85, 86 -> SNOW_SHOWERS
            95, 96, 99 -> THUNDER
            else -> OVERCAST
        }
    }
}
