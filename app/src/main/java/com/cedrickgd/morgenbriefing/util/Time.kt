package com.cedrickgd.morgenbriefing.util

import com.cedrickgd.morgenbriefing.data.BERLIN
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Parses an ISO-8601 timestamp with offset (`2026-10-06T05:07:12+02:00`), with zone id, or a
 * local date-time without offset (`2026-10-06T05:07`, interpreted in [zone]).
 * Returns null for null, blank or unparsable input.
 */
internal fun parseInstantOrNull(iso: String?, zone: ZoneId = BERLIN): Instant? {
    val text = iso?.trim().orEmpty()
    if (text.isEmpty()) return null
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (notOffset: DateTimeParseException) {
        try {
            ZonedDateTime.parse(text).toInstant()
        } catch (notZoned: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).atZone(zone).toInstant()
            } catch (notLocal: DateTimeParseException) {
                null
            }
        }
    }
}

/** True when [iso] is missing, unparsable or more than [maxAge] before [now]. */
internal fun isOlderThan(iso: String?, now: Instant, maxAge: Duration): Boolean {
    val at = parseInstantOrNull(iso) ?: return true
    return Duration.between(at, now) > maxAge
}

/** True when [then] is set and less than [window] before [now]. */
internal fun isWithin(then: Instant?, now: Instant, window: Duration): Boolean =
    then != null && Duration.between(then, now) < window

/** `YYYY-MM-DD` -> [LocalDate], null if malformed. */
internal fun parseDateOrNull(text: String?): LocalDate? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed)
    } catch (e: DateTimeParseException) {
        null
    }
}
