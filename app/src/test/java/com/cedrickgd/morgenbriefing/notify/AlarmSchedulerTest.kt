package com.cedrickgd.morgenbriefing.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AlarmSchedulerTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val cest: ZoneOffset = ZoneOffset.ofHours(2)
    private val cet: ZoneOffset = ZoneOffset.ofHours(1)

    /** Unambiguous Berlin wall-clock time with an explicit offset (needed inside overlaps). */
    private fun berlin(
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
        offset: ZoneOffset, second: Int = 0, nanos: Int = 0,
    ): ZonedDateTime =
        ZonedDateTime.ofStrict(LocalDateTime.of(year, month, day, hour, minute, second, nanos), offset, berlin)

    private fun next(now: ZonedDateTime, hour: Int = 5, minute: Int = 30) =
        AlarmScheduler.nextTrigger(now, hour, minute)

    // --- ordinary mornings -------------------------------------------------------------------

    @Test
    fun `before the scheduled time it fires the same morning`() {
        val now = berlin(2026, 10, 5, 4, 0, cest)
        assertEquals(berlin(2026, 10, 5, 5, 30, cest), next(now))
    }

    @Test
    fun `a moment before the scheduled time it still fires today`() {
        val now = berlin(2026, 10, 5, 5, 29, cest, second = 59, nanos = 999_000_000)
        assertEquals(berlin(2026, 10, 5, 5, 30, cest), next(now))
    }

    @Test
    fun `exactly at the scheduled time it moves to the next day`() {
        val now = berlin(2026, 10, 5, 5, 30, cest)
        assertEquals(berlin(2026, 10, 6, 5, 30, cest), next(now))
    }

    @Test
    fun `just after the alarm fired it moves to the next day`() {
        val now = berlin(2026, 10, 5, 5, 30, cest, second = 0, nanos = 40_000_000)
        assertEquals(berlin(2026, 10, 6, 5, 30, cest), next(now))
    }

    @Test
    fun `after the scheduled time it fires the next morning`() {
        val now = berlin(2026, 10, 5, 18, 45, cest)
        assertEquals(berlin(2026, 10, 6, 5, 30, cest), next(now))
    }

    @Test
    fun `result has no seconds and keeps the zone of now`() {
        val newYork = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 10, 5, 4, 12, 33, 123, newYork)
        val trigger = next(now)
        assertEquals(ZonedDateTime.of(2026, 10, 5, 5, 30, 0, 0, newYork), trigger)
        assertEquals(newYork, trigger.zone)
    }

    @Test
    fun `out of range values are clamped instead of crashing`() {
        val now = berlin(2026, 10, 5, 4, 0, cest)
        assertEquals(berlin(2026, 10, 5, 23, 59, cest), AlarmScheduler.nextTrigger(now, 25, 75))
        assertEquals(berlin(2026, 10, 6, 0, 0, cest), AlarmScheduler.nextTrigger(now, -1, -5))
    }

    // --- spring forward: 2026-03-29, 02:00 CET -> 03:00 CEST -------------------------------------

    @Test
    fun `spring forward night - 05_30 lands on summer time`() {
        val now = berlin(2026, 3, 29, 1, 0, cet)
        val trigger = next(now)
        assertEquals(berlin(2026, 3, 29, 5, 30, cest), trigger)
        // Only 3.5 real hours pass because the clock skips an hour.
        assertEquals(Duration.ofMinutes(210), Duration.between(now, trigger))
    }

    @Test
    fun `spring forward from the evening before`() {
        val now = berlin(2026, 3, 28, 6, 0, cet)
        val trigger = next(now)
        assertEquals(berlin(2026, 3, 29, 5, 30, cest), trigger)
        assertEquals(Duration.ofMinutes(22 * 60 + 30), Duration.between(now, trigger))
    }

    @Test
    fun `02_30 schedule inside the gap is moved to 03_30 summer time`() {
        val now = berlin(2026, 3, 29, 1, 0, cet)
        assertEquals(berlin(2026, 3, 29, 3, 30, cest), next(now, hour = 2, minute = 30))
    }

    @Test
    fun `02_30 schedule seen from the evening before also resolves the gap`() {
        val now = berlin(2026, 3, 28, 23, 0, cet)
        assertEquals(berlin(2026, 3, 29, 3, 30, cest), next(now, hour = 2, minute = 30))
    }

    @Test
    fun `02_30 schedule after the shifted alarm goes to the next regular day`() {
        val now = berlin(2026, 3, 29, 3, 30, cest)
        assertEquals(berlin(2026, 3, 30, 2, 30, cest), next(now, hour = 2, minute = 30))
    }

    // --- fall back: 2026-10-25, 03:00 CEST -> 02:00 CET ----------------------------------------

    @Test
    fun `fall back night - 05_30 lands on winter time`() {
        val now = berlin(2026, 10, 24, 6, 0, cest)
        val trigger = next(now)
        assertEquals(berlin(2026, 10, 25, 5, 30, cet), trigger)
        // 24.5 real hours because the clock repeats an hour.
        assertEquals(Duration.ofMinutes(23 * 60 + 30 + 60), Duration.between(now, trigger))
    }

    @Test
    fun `02_30 schedule in the overlap fires at the first occurrence`() {
        val now = berlin(2026, 10, 25, 1, 0, cest)
        assertEquals(berlin(2026, 10, 25, 2, 30, cest), next(now, hour = 2, minute = 30))
    }

    @Test
    fun `02_30 schedule does not fire a second time in the repeated hour`() {
        val firstOccurrence = berlin(2026, 10, 25, 2, 30, cest)
        assertEquals(berlin(2026, 10, 26, 2, 30, cet), next(firstOccurrence, hour = 2, minute = 30))

        val secondPass = berlin(2026, 10, 25, 2, 15, cet) // after the clocks went back
        assertEquals(berlin(2026, 10, 26, 2, 30, cet), next(secondPass, hour = 2, minute = 30))
    }

    @Test
    fun `daily alarms never fire twice on the same local day around DST`() {
        var now = berlin(2026, 3, 27, 12, 0, cet)
        val seen = mutableSetOf<java.time.LocalDate>()
        repeat(220) {
            val trigger = next(now, hour = 2, minute = 30)
            assertTrue("strictly after now", trigger.isAfter(now))
            assertTrue("one alarm per day: ${trigger.toLocalDate()}", seen.add(trigger.toLocalDate()))
            now = trigger
        }
    }

    // --- year boundary -----------------------------------------------------------------------

    @Test
    fun `new year's eve morning after the alarm rolls into the next year`() {
        val now = berlin(2026, 12, 31, 6, 0, cet)
        assertEquals(berlin(2027, 1, 1, 5, 30, cet), next(now))
    }

    @Test
    fun `new year's eve just before midnight`() {
        val now = berlin(2026, 12, 31, 23, 59, cet, second = 59)
        assertEquals(berlin(2027, 1, 1, 5, 30, cet), next(now))
    }
}
