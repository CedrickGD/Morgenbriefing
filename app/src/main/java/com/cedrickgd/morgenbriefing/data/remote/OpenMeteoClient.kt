package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.ZonedDateTime

/**
 * Open-Meteo (no API key, CC BY 4.0). One forecast call per place with
 * `timezone=Europe/Berlin&forecast_days=2`, plus the geocoding API for the place picker.
 */
class OpenMeteoClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://api.open-meteo.com",
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com",
) {
    /**
     * Builds the snapshot: home = current + next 4 hours; day = [dayStart]..[dayEnd] at [day].
     * If `now` is already past [dayEnd], the day window is tomorrow's (`isTomorrow = true`).
     * Throws IOException on network/HTTP errors.
     */
    suspend fun snapshot(
        home: Place,
        day: Place,
        dayStart: Int,
        dayEnd: Int,
        now: ZonedDateTime = ZonedDateTime.now(BERLIN),
    ): WeatherSnapshot = TODO("agent A")

    /** German place search (`language=de`, `count=8`). */
    suspend fun searchPlaces(query: String): List<Place> = TODO("agent A")
}
