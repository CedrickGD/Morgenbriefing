package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot

/**
 * Pure, deterministic German texts derived from a [WeatherSnapshot]. No Android APIs,
 * fully unit-tested in WeatherInsightsTest.
 */
object WeatherInsights {

    /** Friendly one-liner for the serif headline, e.g. "Nebel zum Start, dann goldener Oktober mit 21°." */
    fun headline(s: WeatherSnapshot): String = TODO("agent B")

    /** One or two sentences about stepping outside at home right now. */
    fun homeNote(s: WeatherSnapshot): String = TODO("agent B")

    /** One sentence about the day window, e.g. "Von 13° auf 21°, ab Mittag Sonne pur, trocken." */
    fun daySummary(s: WeatherSnapshot): String = TODO("agent B")

    fun clothing(s: WeatherSnapshot): ClothingAdvice = TODO("agent B")

    /** Fog/visibility, ice, storm, thunder, heavy rain, heat, strong UV — empty on a calm day. */
    fun warnings(s: WeatherSnapshot): List<WeatherWarning> = TODO("agent B")

    /** Text for the 5:30 notification. [brief] may be null or older than today. */
    fun notification(s: WeatherSnapshot?, brief: Brief?, today: String): NotificationText = TODO("agent B")
}
