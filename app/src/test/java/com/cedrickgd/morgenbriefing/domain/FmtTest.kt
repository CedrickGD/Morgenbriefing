package com.cedrickgd.morgenbriefing.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FmtTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 6, 5, 30, 0, 0, berlin)

    @Test
    fun `temp rounds half up and never shows minus zero`() {
        assertEquals("9°", Fmt.temp(9.4))
        assertEquals("10°", Fmt.temp(9.5))
        assertEquals("21°", Fmt.temp(20.5))
        assertEquals("0°", Fmt.temp(-0.4))
        assertEquals("0°", Fmt.temp(-0.0))
        assertEquals("0°", Fmt.temp(0.4))
        assertEquals("-1°", Fmt.temp(-0.5))
        assertEquals("-3°", Fmt.temp(-2.5))
        assertEquals("-7°", Fmt.temp(-6.5))
        assertEquals("–°", Fmt.temp(Double.NaN))
    }

    @Test
    fun `date formats are German`() {
        val date = LocalDate.of(2026, 10, 6)
        assertEquals("DIENSTAG · 6. OKTOBER", Fmt.dateLine(date))
        assertEquals("Di, 6. Okt.", Fmt.shortDate(date))
        assertEquals("Oktober 2026", Fmt.monthHeader(date))

        val march = LocalDate.of(2027, 3, 1)
        assertEquals("MONTAG · 1. MÄRZ", Fmt.dateLine(march))
        assertEquals("Mo, 1. März", Fmt.shortDate(march))
        assertEquals("März 2027", Fmt.monthHeader(march))
        assertEquals("So, 31. Mai", Fmt.shortDate(LocalDate.of(2026, 5, 31)))
        assertEquals("SAMSTAG · 26. DEZEMBER", Fmt.dateLine(LocalDate.of(2026, 12, 26)))
    }

    @Test
    fun `greeting boundaries`() {
        fun at(h: Int, m: Int = 0) = Fmt.greeting(now.withHour(h).withMinute(m))
        assertEquals("Gute Nacht", at(0))
        assertEquals("Gute Nacht", at(3, 59))
        assertEquals("Guten Morgen", at(4))
        assertEquals("Guten Morgen", at(10, 59))
        assertEquals("Guten Tag", at(11))
        assertEquals("Guten Tag", at(17, 59))
        assertEquals("Guten Abend", at(18))
        assertEquals("Guten Abend", at(22, 59))
        assertEquals("Gute Nacht", at(23))
    }

    @Test
    fun `clock accepts offset, local and plain times`() {
        assertEquals("05:07", Fmt.clock("2026-10-06T05:07:12+02:00"))
        assertEquals("05:07", Fmt.clock("2026-10-06T03:07:12Z"))
        assertEquals("05:00", Fmt.clock("2026-10-06T05:00"))
        assertEquals("05:00", Fmt.clock("2026-10-06 05:00"))
        assertEquals("07:18", Fmt.clock("07:18"))
        assertEquals("", Fmt.clock("gestern früh"))
        assertEquals("", Fmt.clock(""))
    }

    @Test
    fun `relative times`() {
        assertEquals("gerade eben", Fmt.relative("2026-10-06T05:29:40+02:00", now))
        assertEquals("gerade eben", Fmt.relative("2026-10-06T05:35:00+02:00", now))
        assertEquals("vor 12 Min.", Fmt.relative("2026-10-06T05:18:00+02:00", now))
        assertEquals("vor 30 Min.", Fmt.relative("2026-10-06T03:00:00Z", now))
        assertEquals("vor 59 Min.", Fmt.relative("2026-10-06T04:31", now))
        assertEquals("vor 1 Std.", Fmt.relative("2026-10-06T04:30", now))
        assertEquals("vor 3 Std.", Fmt.relative("2026-10-06T02:30", now))
        assertEquals("vor 14 Std.", Fmt.relative("2026-10-05T14:55:00+02:00", now))
        assertEquals("gestern", Fmt.relative("2026-10-05T03:00:00+02:00", now))
        assertEquals("vor 2 Tagen", Fmt.relative("2026-10-04T03:00", now))
        assertEquals("vor 4 Tagen", Fmt.relative("2026-10-02T12:00", now))
        assertNull(Fmt.relative(null, now))
        assertNull(Fmt.relative("kaputt", now))
    }

    @Test
    fun `relative uses Berlin calendar days even when now is given in another zone`() {
        val utcNow = now.withZoneSameInstant(ZoneId.of("UTC"))
        assertEquals("gestern", Fmt.relative("2026-10-05T03:00", utcNow))
        assertEquals("vor 3 Std.", Fmt.relative("2026-10-06T02:30", utcNow))
    }
}
