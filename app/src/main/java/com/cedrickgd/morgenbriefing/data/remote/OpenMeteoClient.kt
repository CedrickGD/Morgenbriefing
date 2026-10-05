package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

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
    /** Open-Meteo adds metadata (units, elevation …) we do not model, so unknown keys are fine. */
    private val parser = Json(from = json) { ignoreUnknownKeys = true }

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
    ): WeatherSnapshot {
        // Open-Meteo answers in Berlin local time, so all matching happens in that zone.
        val local = now.withZoneSameInstant(BERLIN)
        val (forHome, forDay) = coroutineScope {
            val homeCall = async { forecast(home) }
            val dayCall = if (home.sameSpotAs(day)) homeCall else async { forecast(day) }
            homeCall.await() to dayCall.await()
        }
        val start = minOf(dayStart, dayEnd).coerceIn(0, 23)
        val end = maxOf(dayStart, dayEnd).coerceIn(0, 23)
        return WeatherSnapshot(
            fetchedAt = local.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            home = buildHome(home, forHome, local.toLocalDateTime()),
            day = buildDay(day, forDay, start, end, local.toLocalDateTime()),
        )
    }

    /** German place search (`language=de`, `count=8`). */
    suspend fun searchPlaces(query: String): List<Place> {
        val name = query.trim()
        if (name.isEmpty()) return emptyList()
        val url = geocodingUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/search")
            .addQueryParameter("name", name)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "de")
            .addQueryParameter("format", "json")
            .build()
        val dto = get(url, GeocodingDto.serializer(), what = "Ortssuche")
        return dto.results.orEmpty().mapNotNull { r ->
            val placeName = r.name?.trim().orEmpty()
            val lat = r.latitude
            val lon = r.longitude
            if (placeName.isEmpty() || lat == null || lon == null) return@mapNotNull null
            Place(
                name = placeName,
                lat = lat,
                lon = lon,
                region = listOf(r.admin1, r.country)
                    .mapNotNull { it?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(", "),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fetching
    // ---------------------------------------------------------------------------------------

    private suspend fun forecast(place: Place): Forecast {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", place.lat.coordinate())
            .addQueryParameter("longitude", place.lon.coordinate())
            .addEncodedQueryParameter("current", CURRENT_FIELDS)
            .addEncodedQueryParameter("hourly", HOURLY_FIELDS)
            .addEncodedQueryParameter("daily", DAILY_FIELDS)
            .addEncodedQueryParameter("timezone", "Europe%2FBerlin")
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("wind_speed_unit", "kmh")
            .build()
        return Forecast.from(get(url, ForecastDto.serializer(), what = "Wetter für ${place.name}"))
    }

    private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>, what: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = http.fetchText(request)
        if (!response.isSuccessful) {
            throw IOException("Open-Meteo ($what): HTTP ${response.code}")
        }
        return try {
            parser.decodeFromString(serializer, response.body)
        } catch (e: SerializationException) {
            throw IOException("Open-Meteo ($what): Antwort nicht lesbar", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Open-Meteo ($what): Antwort nicht lesbar", e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------------------

    private fun buildHome(place: Place, f: Forecast, now: LocalDateTime): HomeWeather {
        val hourKey = now.truncatedTo(ChronoUnit.HOURS).format(HOUR_KEY)
        // Consecutive entries in API order (DST-safe), starting at the current hour.
        val firstIndex = f.hours.indexOfFirst { it.time >= hourKey }
        val nextHours = if (firstIndex < 0) emptyList() else f.hours.drop(firstIndex).take(NEXT_HOURS)
        val today = now.toLocalDate()
        val daily = f.daily[today] ?: f.daily.values.firstOrNull()
        return HomeWeather(
            place = place,
            current = buildCurrent(f, hourKey, now),
            nextHours = nextHours,
            sunrise = daily?.sunrise.orEmpty(),
            sunset = daily?.sunset.orEmpty(),
            sunriseTomorrow = f.daily[today.plusDays(1)]?.sunrise.orEmpty(),
        )
    }

    private fun buildCurrent(f: Forecast, hourKey: String, now: LocalDateTime): CurrentConditions {
        val c = f.current
        val hour = f.hourByTime[hourKey]
        val temp = c?.temperature ?: hour?.temp
            ?: throw IOException("Open-Meteo: keine aktuellen Werte in der Antwort")
        val wind = c?.windSpeed ?: hour?.windKmh ?: 0.0
        return CurrentConditions(
            time = c?.time?.takeIf { it.isNotBlank() } ?: now.truncatedTo(ChronoUnit.MINUTES).format(MINUTE_KEY),
            temp = temp,
            feels = c?.apparent ?: hour?.feels ?: temp,
            code = c?.weatherCode?.toInt() ?: hour?.code ?: FALLBACK_CODE,
            isDay = c?.isDay?.let { it >= 0.5 } ?: hour?.isDay ?: (now.hour in DAYLIGHT_FALLBACK),
            windKmh = wind,
            gustKmh = c?.windGusts ?: hour?.gustKmh ?: wind,
            precipMm = c?.precipitation ?: hour?.precipMm ?: 0.0,
            humidity = c?.humidity?.roundToInt()?.coerceIn(0, 100) ?: 0,
            visibilityM = c?.visibility ?: hour?.visibilityM,
        )
    }

    private fun buildDay(place: Place, f: Forecast, start: Int, end: Int, now: LocalDateTime): DayWeather {
        val isTomorrow = now.hour >= end
        val date = now.toLocalDate().let { if (isTomorrow) it.plusDays(1) else it }
        val hours = (start..end).mapNotNull { h -> f.hourByTime[date.atTime(h, 0).format(HOUR_KEY)] }
        val daily = f.daily[date]
        val minTemp = hours.minOfOrNull { it.temp } ?: daily?.tempMin
        val maxTemp = hours.maxOfOrNull { it.temp } ?: daily?.tempMax
        if (minTemp == null || maxTemp == null) {
            throw IOException("Open-Meteo: keine Vorhersage für den $date in der Antwort")
        }
        return DayWeather(
            place = place,
            date = date.toString(),
            isTomorrow = isTomorrow,
            startHour = start,
            endHour = end,
            hours = hours,
            minTemp = minTemp,
            maxTemp = maxTemp,
            maxPop = hours.maxOfOrNull { it.pop } ?: 0,
            precipSum = hours.sumOf { it.precipMm }.roundTo(1),
            maxGustKmh = hours.maxOfOrNull { it.gustKmh } ?: 0.0,
            uvMax = daily?.uvMax ?: 0.0,
            sunset = daily?.sunset.orEmpty(),
        )
    }

    /** Parsed, null-cleaned forecast of one place. */
    private class Forecast(
        val current: CurrentDto?,
        /** Valid hourly points in API order. */
        val hours: List<HourPoint>,
        val daily: Map<LocalDate, DailyEntry>,
    ) {
        /** First point per local `YYYY-MM-DDTHH:00` key. */
        val hourByTime: Map<String, HourPoint> = buildMap { hours.forEach { putIfAbsent(it.time, it) } }

        companion object {
            fun from(dto: ForecastDto): Forecast =
                Forecast(dto.current, dto.hourly?.points().orEmpty(), dto.daily?.entries().orEmpty())
        }
    }

    private class DailyEntry(
        val sunrise: String?,
        val sunset: String?,
        val uvMax: Double?,
        val tempMax: Double?,
        val tempMin: Double?,
    )

    // ---------------------------------------------------------------------------------------
    // DTOs (null-tolerant: Open-Meteo may return null inside arrays, e.g. for missing models)
    // ---------------------------------------------------------------------------------------

    @Serializable
    private class ForecastDto(
        val current: CurrentDto? = null,
        val hourly: HourlyDto? = null,
        val daily: DailyDto? = null,
    )

    @Serializable
    private class CurrentDto(
        val time: String? = null,
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("apparent_temperature") val apparent: Double? = null,
        val precipitation: Double? = null,
        @SerialName("weather_code") val weatherCode: Double? = null,
        @SerialName("is_day") val isDay: Double? = null,
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
        @SerialName("wind_gusts_10m") val windGusts: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Double? = null,
        val visibility: Double? = null,
    )

    @Serializable
    private class HourlyDto(
        val time: List<String?> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("apparent_temperature") val apparent: List<Double?> = emptyList(),
        @SerialName("precipitation_probability") val pop: List<Double?> = emptyList(),
        val precipitation: List<Double?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Double?> = emptyList(),
        @SerialName("is_day") val isDay: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
        @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
        val visibility: List<Double?> = emptyList(),
    ) {
        /** Hours without a parseable time or temperature are skipped. */
        fun points(): List<HourPoint> = time.indices.mapNotNull { i ->
            val t = time[i] ?: return@mapNotNull null
            val dateTime = parseLocal(t) ?: return@mapNotNull null
            val temp = temperature.getOrNull(i) ?: return@mapNotNull null
            val wind = windSpeed.getOrNull(i) ?: 0.0
            HourPoint(
                time = dateTime.truncatedTo(ChronoUnit.HOURS).format(HOUR_KEY),
                hour = dateTime.hour,
                temp = temp,
                feels = apparent.getOrNull(i) ?: temp,
                pop = pop.getOrNull(i)?.roundToInt()?.coerceIn(0, 100) ?: 0,
                precipMm = precipitation.getOrNull(i)?.coerceAtLeast(0.0) ?: 0.0,
                code = weatherCode.getOrNull(i)?.toInt() ?: FALLBACK_CODE,
                isDay = isDay.getOrNull(i)?.let { it >= 0.5 } ?: (dateTime.hour in DAYLIGHT_FALLBACK),
                windKmh = wind,
                gustKmh = windGusts.getOrNull(i) ?: wind,
                visibilityM = visibility.getOrNull(i),
            )
        }
    }

    @Serializable
    private class DailyDto(
        val time: List<String?> = emptyList(),
        val sunrise: List<String?> = emptyList(),
        val sunset: List<String?> = emptyList(),
        @SerialName("uv_index_max") val uvMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
    ) {
        fun entries(): Map<LocalDate, DailyEntry> = buildMap {
            time.forEachIndexed { i, raw ->
                val date = raw?.let(::parseDate) ?: return@forEachIndexed
                putIfAbsent(
                    date,
                    DailyEntry(
                        sunrise = sunrise.getOrNull(i)?.let(::clockTime),
                        sunset = sunset.getOrNull(i)?.let(::clockTime),
                        uvMax = uvMax.getOrNull(i),
                        tempMax = tempMax.getOrNull(i),
                        tempMin = tempMin.getOrNull(i),
                    ),
                )
            }
        }
    }

    @Serializable
    private class GeocodingDto(val results: List<GeoResultDto>? = null)

    @Serializable
    private class GeoResultDto(
        val name: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val admin1: String? = null,
        val country: String? = null,
    )

    private companion object {
        const val NEXT_HOURS = 4

        /** "bedeckt": neutral when the API leaves the weather code empty. */
        const val FALLBACK_CODE = 3

        /** Rough daylight guess when `is_day` is missing. */
        val DAYLIGHT_FALLBACK = 7..19

        const val CURRENT_FIELDS = "temperature_2m,apparent_temperature,precipitation,weather_code,is_day," +
            "wind_speed_10m,wind_gusts_10m,relative_humidity_2m,visibility"
        const val HOURLY_FIELDS = "temperature_2m,apparent_temperature,precipitation_probability,precipitation," +
            "weather_code,is_day,wind_speed_10m,wind_gusts_10m,visibility"
        const val DAILY_FIELDS = "sunrise,sunset,uv_index_max,temperature_2m_max,temperature_2m_min"

        val HOUR_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH':00'", Locale.ROOT)
        val MINUTE_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        fun Double.coordinate(): String = String.format(Locale.US, "%.4f", this)

        fun Double.roundTo(decimals: Int): Double {
            var factor = 1.0
            repeat(decimals) { factor *= 10 }
            return Math.round(this * factor) / factor
        }

        fun Place.sameSpotAs(other: Place): Boolean =
            lat.coordinate() == other.lat.coordinate() && lon.coordinate() == other.lon.coordinate()

        fun parseLocal(value: String): LocalDateTime? = try {
            LocalDateTime.parse(value.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        fun parseDate(value: String): LocalDate? = try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        /** `2026-10-06T07:14` → `07:14`; null if unparseable. */
        fun clockTime(value: String): String? = parseLocal(value)?.format(CLOCK)
    }
}
