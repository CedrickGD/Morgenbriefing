package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.max

/*
 * Shared, pure analysis of a WeatherSnapshot. WeatherInsights and ClothingAdvisor read these
 * facts instead of re-deriving thresholds, so every text agrees with every other text.
 */

/** Precipitation probability (%) from which an hour counts as "wet". */
internal const val WET_POP = 50

/** Rounds half away from zero (2.5 -> 3, -2.5 -> -3); the single rounding rule for all texts. */
internal fun roundHalfUp(value: Double): Int {
    if (value.isNaN()) return 0
    if (value >= Int.MAX_VALUE) return Int.MAX_VALUE
    if (value <= Int.MIN_VALUE) return Int.MIN_VALUE
    return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toInt()
}

internal val Condition.isSunny: Boolean
    get() = this == Condition.CLEAR || this == Condition.MOSTLY_CLEAR

/** Snow or ice on the ground is likely: sturdy shoes, slippery paths. */
internal val Condition.isWintry: Boolean
    get() = this == Condition.SNOW || this == Condition.SNOW_SHOWERS || this == Condition.FREEZING_RAIN

/** Higher = more notable. Breaks frequency ties toward the weather people should hear about. */
internal val Condition.severity: Int
    get() = when (this) {
        Condition.CLEAR -> 0
        Condition.MOSTLY_CLEAR -> 1
        Condition.PARTLY_CLOUDY -> 2
        Condition.OVERCAST -> 3
        Condition.FOG -> 4
        Condition.DRIZZLE -> 5
        Condition.SHOWERS -> 6
        Condition.RAIN -> 7
        Condition.SNOW_SHOWERS -> 8
        Condition.SNOW -> 9
        Condition.HEAVY_RAIN -> 10
        Condition.FREEZING_RAIN -> 11
        Condition.THUNDER -> 12
    }

/** WMO 65 (heavy rain) and 82 (violent showers). */
internal fun isHeavyRainCode(code: Int): Boolean = code == 65 || code == 82

/** WMO 96/99: thunderstorm with hail. */
internal fun isHailCode(code: Int): Boolean = code == 96 || code == 99

internal val HourPoint.condition: Condition
    get() = Condition.fromCode(code)

/** An hour that will very likely be wet: high probability or measurable precipitation. */
internal fun HourPoint.isWetPoint(): Boolean = pop >= WET_POP || precipMm >= 0.3

/**
 * Most frequent condition; "clear" and "mostly clear" count together as sunny. Ties go to the
 * more notable condition (see [severity]). Null for an empty list.
 */
internal fun dominantCondition(conditions: List<Condition>): Condition? {
    if (conditions.isEmpty()) return null
    val counts = conditions.groupingBy { it }.eachCount().toMutableMap()
    val clear = counts.remove(Condition.CLEAR) ?: 0
    val mostlyClear = counts.remove(Condition.MOSTLY_CLEAR) ?: 0
    if (clear + mostlyClear > 0) {
        counts[if (clear >= mostlyClear) Condition.CLEAR else Condition.MOSTLY_CLEAR] = clear + mostlyClear
    }
    return counts.entries
        .maxWithOrNull(compareBy<Map.Entry<Condition, Int>> { it.value }.thenBy { it.key.severity })
        ?.key
}

/** German noun for a kind of precipitation, usable mid-sentence ("ab 14 Uhr Regen"). */
internal fun precipNoun(condition: Condition): String = when (condition) {
    Condition.DRIZZLE -> "Nieselregen"
    Condition.SHOWERS -> "Schauer"
    Condition.HEAVY_RAIN -> "kräftiger Regen"
    Condition.FREEZING_RAIN -> "Eisregen"
    Condition.SNOW -> "Schnee"
    Condition.SNOW_SHOWERS -> "Schneeschauer"
    Condition.THUNDER -> "Gewitter"
    else -> "Regen"
}

/** "ab Mittag" for 12/13 Uhr, otherwise "ab 15 Uhr"; "später" when the hour is unknown. */
internal fun fromHour(hour: Int?): String = when (hour) {
    null -> "später"
    12, 13 -> "ab Mittag"
    else -> "ab $hour Uhr"
}

/** Deterministic choice for a little wording variety: same date, same text. */
internal fun <T> pick(seed: String, salt: Int, options: List<T>): T =
    options[Math.floorMod(seed.hashCode() * 31 + salt, options.size)]

internal fun String.capitalizeFirst(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.GERMAN) else it.toString() }

internal fun String.wordCount(): Int = trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/** Parses "HH:mm", local ISO or offset ISO into a Berlin wall-clock time. */
internal fun parseLocalTime(text: String): LocalTime? {
    val clock = Fmt.clock(text)
    if (clock.isEmpty()) return null
    return try {
        LocalTime.parse(clock)
    } catch (_: DateTimeException) {
        null
    }
}

/** Facts about the day window at the day place (default Leipzig 10–18 Uhr). */
internal class DayFacts(val day: DayWeather) {
    val hours: List<HourPoint> = day.hours
    val conditions: List<Condition> = hours.map { it.condition }
    val hasHours: Boolean = hours.isNotEmpty()

    val startTemp: Double = hours.firstOrNull()?.temp ?: day.minTemp
    val endTemp: Double = hours.lastOrNull()?.temp ?: day.maxTemp
    val maxTemp: Double = hours.maxOfOrNull { it.temp } ?: day.maxTemp
    val minTemp: Double = hours.minOfOrNull { it.temp } ?: day.minTemp

    /** First hour with the window's highest temperature. */
    val peak: HourPoint? = hours.maxByOrNull { it.temp }
    val maxFeels: Double = hours.maxOfOrNull { it.feels } ?: maxTemp
    val minFeels: Double = hours.minOfOrNull { it.feels } ?: minTemp

    val maxPop: Int = max(day.maxPop, hours.maxOfOrNull { it.pop } ?: 0)
    val precipSum: Double = max(day.precipSum, hours.sumOf { it.precipMm })
    val maxGust: Double = max(day.maxGustKmh, hours.maxOfOrNull { it.gustKmh } ?: 0.0)
    val maxWind: Double = hours.maxOfOrNull { it.windKmh } ?: 0.0

    /** Hours with pop >= 50 %. */
    val wetHours: List<HourPoint> = hours.filter { it.pop >= WET_POP }

    /** Wet beats dry once at least two hours are likely wet. */
    val isWetDay: Boolean = wetHours.size >= 2
    val firstWet: HourPoint? = wetHours.firstOrNull()
    val lastWet: HourPoint? = wetHours.lastOrNull()

    /** Dominant kind of precipitation among the wet hours (rain when the codes don't say). */
    val wetKind: Condition =
        dominantCondition(wetHours.map { it.condition }.filter { it.isWet })
            ?: dominantCondition(conditions.filter { it.isWet })
            ?: if (maxTemp <= 1.0) Condition.SNOW else Condition.RAIN

    val thunder: HourPoint? = hours.firstOrNull { it.condition == Condition.THUNDER }
    val freezing: HourPoint? = hours.firstOrNull { it.condition == Condition.FREEZING_RAIN }

    val sunnyHours: Int = hours.count { it.condition.isSunny && it.isDay }
    val allSunny: Boolean = hasHours && conditions.all { it.isSunny }

    /** Condition that best describes the day: wet beats dry, otherwise the most frequent one. */
    val main: Condition = when {
        isWetDay -> wetKind
        else -> dominantCondition(conditions) ?: if (maxPop >= WET_POP) Condition.RAIN else Condition.PARTLY_CLOUDY
    }

    val mostlySunny: Boolean = !isWetDay && hasHours && conditions.count { it.isSunny } * 2 >= hours.size

    /** Not a wet day, but a shower can't be ruled out. */
    val showersPossible: Boolean = !isWetDay && (
        maxPop >= 25 || wetHours.isNotEmpty() || precipSum >= 0.3 ||
            (conditions.any { it.isWet } && maxPop >= 15)
        )

    /** Wet day whose first hour(s) are still dry. */
    val rainStartsLater: Boolean = isWetDay && firstWet != null && firstWet !== hours.firstOrNull()

    /**
     * Wet day that is wet from the start but dries up: the hour from which it stays dry
     * (at least two dry hours left in the window), else null.
     */
    val dryFrom: Int? = run {
        if (!isWetDay || rainStartsLater) return@run null
        val last = lastWet ?: return@run null
        val index = hours.indexOf(last)
        val rest = hours.drop(index + 1)
        if (rest.size >= 2 && rest.all { it.pop < 40 && it.precipMm < 0.3 }) rest.first().hour else null
    }

    val month: Int? = try {
        LocalDate.parse(day.date.trim()).monthValue
    } catch (_: DateTimeException) {
        null
    }
}

/** Facts about stepping outside at home right now (default Bad Düben). */
internal class HomeFacts(val home: HomeWeather) {
    val now = home.current
    val condition: Condition = Condition.fromCode(now.code)

    /** The current hour plus the next three. */
    val upcoming: List<HourPoint> = home.nextHours

    val wetNow: Boolean = condition.isWet || now.precipMm >= 0.1

    /** First upcoming hour that is likely wet while it's dry right now. */
    val wetSoon: HourPoint? = if (wetNow) null else upcoming.firstOrNull { it.isWetPoint() }

    /** While wet now: the hour from which the rest of the upcoming hours stay dry. */
    val dryAgain: HourPoint? = run {
        if (!wetNow) return@run null
        val later = upcoming.drop(1)
        val index = later.indices.firstOrNull { i ->
            later.drop(i).all { !it.isWetPoint() && !it.condition.isWet }
        }
        index?.let { later[it] }
    }

    /** Visibility right now (falls back to the current hour's forecast). */
    val visibilityNow: Double? = now.visibilityM ?: upcoming.firstOrNull()?.visibilityM

    /** Worst visibility now or in the next hours. */
    val minVisibility: Double? = (listOf(now.visibilityM) + upcoming.map { it.visibilityM }).filterNotNull().minOrNull()

    val foggy: Boolean = condition == Condition.FOG || upcoming.firstOrNull()?.condition == Condition.FOG

    val freezingRain: Boolean =
        condition == Condition.FREEZING_RAIN || upcoming.any { it.condition == Condition.FREEZING_RAIN }

    val wintryPrecip: Boolean = condition.isWintry || upcoming.any { it.condition.isWintry && it.pop >= 30 }

    /** Glättegefahr: freezing codes, or ≤ 1° with wet ground or very humid air. */
    val slippery: Boolean = freezingRain ||
        (now.temp <= 1.0 && (wetNow || now.humidity >= 90)) ||
        upcoming.any { it.temp <= 1.0 && it.isWetPoint() }

    val maxGust: Double = max(now.gustKmh, upcoming.maxOfOrNull { it.gustKmh } ?: 0.0)

    private val nowTime: LocalTime? = parseLocalTime(now.time)
    val sunrise: LocalTime? = parseLocalTime(home.sunrise)
    private val sunset: LocalTime? = parseLocalTime(home.sunset)

    val beforeSunrise: Boolean =
        if (nowTime != null && sunrise != null) nowTime < sunrise else !now.isDay && (nowTime?.hour ?: 0) < 12

    val afterSunset: Boolean =
        if (nowTime != null && sunset != null) nowTime >= sunset else !now.isDay && (nowTime?.hour ?: 0) >= 12
}
