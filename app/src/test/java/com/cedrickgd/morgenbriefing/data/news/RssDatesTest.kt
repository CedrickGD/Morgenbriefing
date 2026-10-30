package com.cedrickgd.morgenbriefing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RssDatesTest {

    private fun instant(raw: String): Instant? = RssDates.parse(raw)

    @Test
    fun `parses rfc 1123 with numeric offset and gmt`() {
        assertEquals(Instant.parse("2026-10-29T12:02:19Z"), instant("Thu, 29 Oct 2026 13:02:19 +0100"))
        assertEquals(Instant.parse("2026-10-29T14:10:22Z"), instant("Thu, 29 Oct 2026 14:10:22 GMT"))
        assertEquals(Instant.parse("2026-10-29T12:50:24Z"), instant("Thu, 29 Oct 2026 12:50:24 -0000"))
    }

    @Test
    fun `parses taz style without a weekday`() {
        assertEquals(Instant.parse("2026-10-29T14:14:00Z"), instant("29 Oct 2026 15:14:00 +0100"))
    }

    @Test
    fun `parses iso with zulu, offset and millis`() {
        assertEquals(Instant.parse("2026-10-29T12:02:19Z"), instant("2026-10-29T12:02:19Z"))
        assertEquals(Instant.parse("2026-10-29T13:22:00Z"), instant("2026-10-29T14:22:00+01:00"))
        assertEquals(Instant.parse("2026-10-29T14:10:30.937Z"), instant("2026-10-29T15:10:30.937+01:00"))
    }

    @Test
    fun `local date-time is read as berlin time`() {
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), instant("2026-10-29T13:00:00"))
        assertEquals(Instant.parse("2026-07-15T10:00:00Z"), instant("2026-07-15 12:00:00"))
    }

    @Test
    fun `garbage and blanks are null`() {
        assertNull(RssDates.parse(null))
        assertNull(instant(""))
        assertNull(instant("gestern"))
    }

    @Test
    fun `normalize writes berlin offset whole seconds`() {
        assertEquals("2026-10-29T13:02:19+01:00", RssDates.normalize("Thu, 29 Oct 2026 12:02:19 GMT"))
        assertEquals("2026-07-15T12:00:00+02:00", RssDates.normalize("2026-07-15T10:00:00.5Z"))
    }
}
