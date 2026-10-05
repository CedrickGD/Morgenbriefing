package com.cedrickgd.morgenbriefing.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TimeTest {

    @Test
    fun `parses offset, zoned and local timestamps`() {
        val expected = Instant.parse("2026-10-06T03:07:12Z")
        assertEquals(expected, parseInstantOrNull("2026-10-06T05:07:12+02:00"))
        assertEquals(expected, parseInstantOrNull("2026-10-06T05:07:12+02:00[Europe/Berlin]"))
        // Local Open-Meteo style time is interpreted in Europe/Berlin (CEST = UTC+2 in October).
        assertEquals(Instant.parse("2026-10-06T03:00:00Z"), parseInstantOrNull("2026-10-06T05:00"))
        assertEquals(expected, parseInstantOrNull("  2026-10-06T05:07:12+02:00  "))
    }

    @Test
    fun `unparsable or blank timestamps are null`() {
        assertNull(parseInstantOrNull(null))
        assertNull(parseInstantOrNull(""))
        assertNull(parseInstantOrNull("gestern früh"))
    }

    @Test
    fun `isOlderThan treats missing timestamps as stale`() {
        val now = Instant.parse("2026-10-06T08:00:00Z")
        val maxAge = Duration.ofMinutes(10)
        assertTrue(isOlderThan(null, now, maxAge))
        assertTrue(isOlderThan("kaputt", now, maxAge))
        assertTrue(isOlderThan("2026-10-06T07:49:00Z", now, maxAge))
        assertFalse(isOlderThan("2026-10-06T07:51:00Z", now, maxAge))
        assertFalse(isOlderThan("2026-10-06T07:50:00Z", now, maxAge))
        // A snapshot from the future (clock skew) is not stale.
        assertFalse(isOlderThan("2026-10-06T09:00:00Z", now, maxAge))
    }

    @Test
    fun `isWithin needs a previous instant`() {
        val now = Instant.parse("2026-10-06T08:00:00Z")
        assertFalse(isWithin(null, now, Duration.ofMinutes(10)))
        assertTrue(isWithin(now.minusSeconds(60), now, Duration.ofMinutes(10)))
        assertFalse(isWithin(now.minusSeconds(600), now, Duration.ofMinutes(10)))
    }

    @Test
    fun `parses brief dates`() {
        assertEquals(LocalDate.of(2026, 10, 6), parseDateOrNull("2026-10-06"))
        assertEquals(LocalDate.of(2026, 10, 6), parseDateOrNull(" 2026-10-06 "))
        assertNull(parseDateOrNull("06.10.2026"))
        assertNull(parseDateOrNull(null))
        assertNull(parseDateOrNull(""))
    }
}

class LinksTest {

    @Test
    fun `accepts http and https urls with a host`() {
        assertTrue(Links.isWebUrl("https://www.handelsblatt.com/technik/artikel.html"))
        assertTrue(Links.isWebUrl("HTTPS://example.org"))
        assertTrue(Links.isWebUrl("http://example.org:8080/a?b=c#d"))
        assertTrue(Links.isWebUrl("  https://example.org/pfad  "))
    }

    @Test
    fun `rejects other schemes, missing hosts and whitespace`() {
        assertFalse(Links.isWebUrl(""))
        assertFalse(Links.isWebUrl("javascript:alert(1)"))
        assertFalse(Links.isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(Links.isWebUrl("file:///sdcard/secret.txt"))
        assertFalse(Links.isWebUrl("https://"))
        assertFalse(Links.isWebUrl("https:///pfad"))
        assertFalse(Links.isWebUrl("example.org"))
        assertFalse(Links.isWebUrl("https://exa mple.org"))
    }
}

class CoroutinesTest {

    @Test
    fun `attempt wraps ordinary exceptions`() {
        val error = IOException("offline")
        val result = attempt { throw error }
        assertSame(error, result.exceptionOrNull())
        assertEquals(42, attempt { 42 }.getOrNull())
    }

    @Test
    fun `attempt never swallows cancellation`() {
        try {
            attempt { throw CancellationException("cancelled") }
            fail("CancellationException must be rethrown")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun `flatten unwraps nested results`() {
        val inner = IOException("nope")
        assertEquals("ok", Result.success(Result.success("ok")).flatten().getOrNull())
        assertSame(inner, Result.success(Result.failure<String>(inner)).flatten().exceptionOrNull())
        val outer = IllegalStateException("boom")
        assertSame(outer, Result.failure<Result<String>>(outer).flatten().exceptionOrNull())
    }
}
