package com.cedrickgd.morgenbriefing.domain

import java.time.LocalDate
import java.time.ZonedDateTime

/** German display formatting. Pure Kotlin + java.time (desugared on API 24/25). */
object Fmt {
    /** 9.4 -> "9°", -0.4 -> "0°" (never "-0°"). */
    fun temp(value: Double): String = TODO("agent B")

    /** "DIENSTAG · 6. OKTOBER" */
    fun dateLine(date: LocalDate): String = TODO("agent B")

    /** "Di, 6. Okt." */
    fun shortDate(date: LocalDate): String = TODO("agent B")

    /** "Oktober 2026" */
    fun monthHeader(date: LocalDate): String = TODO("agent B")

    /** "Guten Morgen" (4–10), "Guten Tag" (11–17), "Guten Abend" (18–22), "Gute Nacht" otherwise. */
    fun greeting(now: ZonedDateTime): String = TODO("agent B")

    /** ISO with offset or local ISO -> "05:07". Returns "" if unparsable. */
    fun clock(iso: String): String = TODO("agent B")

    /** "gerade eben", "vor 12 Min.", "vor 3 Std.", "gestern", "vor 4 Tagen" relative to [now]. Null/unparsable -> null. */
    fun relative(iso: String?, now: ZonedDateTime): String? = TODO("agent B")
}
