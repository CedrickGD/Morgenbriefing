package com.cedrickgd.morgenbriefing.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.util.Locale

/**
 * A realistic Open-Meteo forecast response for Bad Düben/Leipzig: 48 hourly entries for
 * day 1 (default 2026-10-06, foggy start, sunny afternoon) and day 2 (rainy), plus the
 * metadata keys the real API sends. Contains the nulls the real API can return:
 * - day 1, 12:00 has no precipitation probability (→ pop 0),
 * - day 2, 13:00 has no temperature (→ hour skipped),
 * - visibility is null for the whole second day.
 */
object OpenMeteoFixture {
    val tempDay1: List<Double?> = listOf(
        8.1, 7.6, 7.2, 6.9, 6.5, 6.2, 6.0, 6.4, 7.8, 9.9, 12.1, 14.0,
        15.6, 16.9, 17.8, 18.3, 18.1, 17.2, 15.4, 13.6, 12.2, 11.1, 10.3, 9.6,
    )
    val tempDay2: List<Double?> = listOf(
        9.2, 8.8, 8.5, 8.3, 8.1, 8.0, 7.9, 8.2, 9.0, 10.4, 11.8, 12.9,
        13.7, null, 14.6, 14.9, 14.2, 13.1, 12.0, 11.2, 10.5, 9.9, 9.4, 9.0,
    )
    private val popDay1: List<Double?> = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 5.0, 10.0, 10.0, 15.0,
        null, 20.0, 35.0, 40.0, 25.0, 10.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0,
    )
    private val popDay2: List<Double?> = listOf(
        20.0, 20.0, 25.0, 25.0, 30.0, 30.0, 35.0, 40.0, 45.0, 55.0, 60.0, 70.0,
        75.0, 80.0, 85.0, 80.0, 70.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 20.0,
    )
    private val precipDay1: List<Double?> = List(24) { h ->
        when (h) {
            14 -> 0.2
            15 -> 0.4
            16 -> 0.1
            else -> 0.0
        }
    }
    private val precipDay2: List<Double?> = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.1, 0.2, 0.4, 0.6, 0.9,
        1.2, 1.5, 1.8, 1.1, 0.7, 0.3, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0,
    )
    private val gustDay1: List<Double?> = listOf(
        12.0, 11.0, 10.0, 10.0, 9.0, 9.0, 10.0, 14.0, 18.0, 22.0, 26.0, 29.0,
        31.0, 33.0, 35.0, 38.0, 34.0, 28.0, 22.0, 19.0, 17.0, 15.0, 14.0, 13.0,
    )
    private val gustDay2: List<Double?> = listOf(
        30.0, 32.0, 34.0, 35.0, 36.0, 38.0, 40.0, 42.0, 45.0, 48.0, 52.0, 55.0,
        58.0, 62.0, 65.0, 60.0, 56.0, 50.0, 45.0, 40.0, 38.0, 35.0, 33.0, 30.0,
    )
    private val codeDay1: List<Double?> = listOf(
        3, 3, 3, 3, 3, 45, 45, 45, 45, 3, 2, 2, 1, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 3,
    ).map { it.toDouble() }
    private val codeDay2: List<Double?> = listOf(
        3, 3, 3, 3, 3, 3, 3, 61, 61, 61, 63, 63, 63, 63, 65, 63, 61, 61, 61, 3, 3, 3, 3, 3,
    ).map { it.toDouble() }
    private val isDay: List<Double?> = List(24) { h -> if (h in 8..18) 1.0 else 0.0 }
    private val visibilityDay1: List<Double?> = List(24) { h ->
        when (h) {
            5 -> 200.0
            6 -> 150.0
            7 -> 300.0
            8 -> 800.0
            else -> 24_000.0
        }
    }
    private val visibilityDay2: List<Double?> = List(24) { null }

    /** Default first day; tests that run against the real clock pass today instead. */
    val FIRST_DAY: LocalDate = LocalDate.of(2026, 10, 6)

    fun json(firstDay: LocalDate = FIRST_DAY): String {
        val d1 = firstDay.toString()
        val d2 = firstDay.plusDays(1).toString()
        val times = (0 until 48).map { i ->
            String.format(Locale.ROOT, "%sT%02d:00", if (i < 24) d1 else d2, i % 24)
        }
        val temps = tempDay1 + tempDay2
        val gusts = gustDay1 + gustDay2
        return buildJsonObject {
            put("latitude", 51.6)
            put("longitude", 12.58)
            put("generationtime_ms", 0.1)
            put("utc_offset_seconds", 7200)
            put("timezone", "Europe/Berlin")
            put("timezone_abbreviation", "GMT+2")
            put("elevation", 92.0)
            putJsonObject("current_units") {
                put("time", "iso8601")
                put("temperature_2m", "°C")
            }
            putJsonObject("current") {
                put("time", "${d1}T07:15")
                put("interval", 900)
                put("temperature_2m", 6.3)
                put("apparent_temperature", 4.1)
                put("precipitation", 0.0)
                put("weather_code", 45)
                put("is_day", 0)
                put("wind_speed_10m", 7.2)
                put("wind_gusts_10m", 14.4)
                put("relative_humidity_2m", 96)
                put("visibility", 300.0)
            }
            putJsonObject("hourly_units") { put("temperature_2m", "°C") }
            putJsonObject("hourly") {
                put("time", strings(times))
                put("temperature_2m", numbers(temps))
                put("apparent_temperature", numbers(temps.map { t -> t?.let { it - 1.5 } }))
                put("precipitation_probability", numbers(popDay1 + popDay2))
                put("precipitation", numbers(precipDay1 + precipDay2))
                put("weather_code", numbers(codeDay1 + codeDay2))
                put("is_day", numbers(isDay + isDay))
                put("wind_speed_10m", numbers(gusts.map { g -> g?.let { it / 2 } }))
                put("wind_gusts_10m", numbers(gusts))
                put("visibility", numbers(visibilityDay1 + visibilityDay2))
            }
            putJsonObject("daily_units") { put("sunrise", "iso8601") }
            putJsonObject("daily") {
                put("time", strings(listOf(d1, d2)))
                put("sunrise", strings(listOf("${d1}T07:14", "${d2}T07:16")))
                put("sunset", strings(listOf("${d1}T18:32", "${d2}T18:30")))
                put("uv_index_max", numbers(listOf(3.45, 1.2)))
                put("temperature_2m_max", numbers(listOf(18.3, 14.9)))
                put("temperature_2m_min", numbers(listOf(6.0, 7.9)))
            }
        }.toString()
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun numbers(values: List<Double?>) =
        JsonArray(values.map { v -> if (v == null) JsonNull else JsonPrimitive(v) })
}
