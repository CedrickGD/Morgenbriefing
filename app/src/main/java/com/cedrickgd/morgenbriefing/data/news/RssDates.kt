package com.cedrickgd.morgenbriefing.data.news

import com.cedrickgd.morgenbriefing.data.BERLIN
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Publish dates as feeds write them: RFC 1123 ("Thu, 29 Oct 2026 13:02:19 +0100", also
 * without the weekday or with "GMT"), ISO-8601 with offset or `Z`, with milliseconds, or a
 * local date-time (read as Berlin time). Pure, unit-tested.
 */
object RssDates {

    private val RFC_NO_WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    private val RFC_NO_WEEKDAY_ZONE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
    private val RFC_NO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("[EEE, ]d MMM yyyy HH:mm Z", Locale.ENGLISH)

    /** The instant [raw] names, or null when it is blank or not a date the app understands. */
    fun parse(raw: String?): Instant? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.isEmpty()) return null
        val normalized = text.replace(" UT", " UTC").replace("Z", "+0000").let { if (it.endsWith("+0000") && text.endsWith("Z")) text else it }
        return attempt { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            ?: attempt { OffsetDateTime.parse(text).toInstant() }
            ?: attempt { ZonedDateTime.parse(text).toInstant() }
            ?: attempt { Instant.parse(text) }
            ?: attempt { ZonedDateTime.parse(withoutWeekday(text), RFC_NO_WEEKDAY).toInstant() }
            ?: attempt { ZonedDateTime.parse(withoutWeekday(text), RFC_NO_WEEKDAY_ZONE).toInstant() }
            ?: attempt { ZonedDateTime.parse(text, RFC_NO_SECONDS).toInstant() }
            ?: attempt { ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            ?: attempt { LocalDateTime.parse(text).atZone(BERLIN).toInstant() }
            ?: attempt { OffsetDateTime.parse(text.replace(' ', 'T')).toInstant() }
            ?: attempt { LocalDateTime.parse(text.replace(' ', 'T')).atZone(BERLIN).toInstant() }
    }

    /** [instant] as the app stores it: ISO-8601 with the Berlin offset, whole seconds. */
    fun format(instant: Instant): String =
        instant.atZone(BERLIN).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Parses and normalizes in one go; null when [raw] is no date. */
    fun normalize(raw: String?): String? = parse(raw)?.let(::format)

    private fun withoutWeekday(text: String): String =
        if (text.length > 5 && text[3] == ',') text.substring(4).trim() else text

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
