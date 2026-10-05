package com.cedrickgd.morgenbriefing.domain

import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * German display formatting. Pure Kotlin + java.time (desugared on API 24/25).
 *
 * Month and weekday names are spelled out here instead of coming from the platform's locale
 * data, so the output is identical on every Android version and in JVM unit tests.
 */
object Fmt {
    /** The app's reference time zone: local ISO strings without offset are read as Berlin time. */
    private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    private val WEEKDAYS = arrayOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val WEEKDAYS_SHORT = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val MONTHS = arrayOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    private val MONTHS_SHORT = arrayOf(
        "Jan.", "Feb.", "März", "Apr.", "Mai", "Juni",
        "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez.",
    )

    /**
     * 9.4 -> "9°", -0.4 -> "0°" (never "-0°"). Rounds half away from zero ("kaufmännisch"),
     * so 2.5 -> "3°" and -2.5 -> "-3°". Non-finite values render as "–°".
     */
    fun temp(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "–°"
        return "${roundHalfUp(value)}°"
    }

    /** "DIENSTAG · 6. OKTOBER" */
    fun dateLine(date: LocalDate): String {
        val weekday = WEEKDAYS[date.dayOfWeek.value - 1].uppercase(Locale.GERMAN)
        val month = MONTHS[date.monthValue - 1].uppercase(Locale.GERMAN)
        return "$weekday · ${date.dayOfMonth}. $month"
    }

    /** "Di, 6. Okt." */
    fun shortDate(date: LocalDate): String =
        "${WEEKDAYS_SHORT[date.dayOfWeek.value - 1]}, ${date.dayOfMonth}. ${MONTHS_SHORT[date.monthValue - 1]}"

    /** "Oktober 2026" */
    fun monthHeader(date: LocalDate): String = "${MONTHS[date.monthValue - 1]} ${date.year}"

    /**
     * "Guten Morgen" (4–10), "Guten Tag" (11–17), "Guten Abend" (18–22), "Gute Nacht" otherwise.
     * Uses the wall-clock hour of [now] as given; callers pass Berlin time.
     */
    fun greeting(now: ZonedDateTime): String = when (now.hour) {
        in 4..10 -> "Guten Morgen"
        in 11..17 -> "Guten Tag"
        in 18..22 -> "Guten Abend"
        else -> "Gute Nacht"
    }

    /**
     * ISO with offset or local ISO -> "05:07". Returns "" if unparsable.
     *
     * Accepted: `2026-10-06T05:07:12+02:00` / `…Z` (converted to Berlin time),
     * `2026-10-06T05:00` (already Berlin time) and a bare `07:18` as Open-Meteo uses for sunrise.
     */
    fun clock(iso: String): String {
        val text = iso.trim()
        val time: LocalTime = parseBerlin(text)?.toLocalTime()
            ?: attempt { LocalTime.parse(text) }
            ?: return ""
        return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }

    /**
     * "gerade eben", "vor 12 Min.", "vor 3 Std.", "gestern", "vor 4 Tagen" relative to [now].
     * Null/unparsable -> null. Times slightly in the future (clock skew) count as "gerade eben".
     * Below 24 hours the distance is shown in minutes/hours; beyond that in Berlin calendar days.
     */
    fun relative(iso: String?, now: ZonedDateTime): String? {
        val then = iso?.trim()?.let(::parseBerlin) ?: return null
        val reference = now.withZoneSameInstant(ZONE)
        val minutes = Duration.between(then, reference).toMinutes()
        return when {
            minutes < 1 -> "gerade eben"
            minutes < 60 -> "vor $minutes Min."
            minutes < 24 * 60 -> "vor ${minutes / 60} Std."
            else -> {
                val days = ChronoUnit.DAYS.between(then.toLocalDate(), reference.toLocalDate())
                if (days <= 1) "gestern" else "vor $days Tagen"
            }
        }
    }

    /**
     * Parses an ISO date-time with offset/zone, a local ISO date-time (Berlin) or a plain ISO
     * date (start of day, Berlin) into Berlin time. Null when the text is none of these.
     */
    internal fun parseBerlin(text: String): ZonedDateTime? {
        if (text.isEmpty()) return null
        // Tolerate "2026-10-06 05:00" (space instead of 'T').
        val iso = if (text.length > 10 && text[10] == ' ') text.replaceRange(10, 11, "T") else text
        return attempt { ZonedDateTime.parse(iso).withZoneSameInstant(ZONE) }
            ?: attempt { LocalDateTime.parse(iso).atZone(ZONE) }
            ?: attempt { LocalDate.parse(iso).atStartOfDay(ZONE) }
    }

    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (_: DateTimeException) {
            null
        }
}
