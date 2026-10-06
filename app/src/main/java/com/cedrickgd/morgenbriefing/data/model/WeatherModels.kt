package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable

/**
 * Everything the app shows about the weather, built from one Open-Meteo call per place.
 * Serializable so the 5:30 snapshot can be stored with the day's history.
 * All times are local Europe/Berlin ISO strings without offset (`2026-10-06T05:00`),
 * exactly as Open-Meteo returns them with `timezone=Europe/Berlin`.
 */
@Serializable
data class WeatherSnapshot(
    /** ISO-8601 with offset of when this was fetched. */
    val fetchedAt: String,
    val home: HomeWeather,
    val day: DayWeather,
)

/** "Vor der Tür": the place you leave from in the morning (default Bad Düben). */
@Serializable
data class HomeWeather(
    val place: Place,
    val current: CurrentConditions,
    /** The next 4 full hours starting with the current hour. */
    val nextHours: List<HourPoint>,
    /** `HH:mm` local. */
    val sunrise: String,
    val sunset: String,
    /** Tomorrow's sunrise, `HH:mm` local; empty in snapshots stored before v1.2. */
    val sunriseTomorrow: String = "",
)

/** The day-time window at the day place (default Leipzig, 10–18 Uhr). */
@Serializable
data class DayWeather(
    val place: Place,
    /** `YYYY-MM-DD` of the window. */
    val date: String,
    /** True when the window is tomorrow's (opened after the window ended today). */
    val isTomorrow: Boolean,
    val startHour: Int,
    val endHour: Int,
    /** One point per full hour, startHour..endHour inclusive. */
    val hours: List<HourPoint>,
    val minTemp: Double,
    val maxTemp: Double,
    /** Max precipitation probability in the window, 0–100. */
    val maxPop: Int,
    val precipSum: Double,
    val maxGustKmh: Double,
    val uvMax: Double,
    val sunset: String,
)

@Serializable
data class CurrentConditions(
    val time: String,
    val temp: Double,
    val feels: Double,
    /** WMO weather code as returned by Open-Meteo. */
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double,
    val gustKmh: Double,
    val precipMm: Double,
    val humidity: Int,
    val visibilityM: Double? = null,
)

@Serializable
data class HourPoint(
    val time: String,
    val hour: Int,
    val temp: Double,
    val feels: Double,
    /** Precipitation probability 0–100. */
    val pop: Int,
    val precipMm: Double,
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double,
    val gustKmh: Double,
    val visibilityM: Double? = null,
)
