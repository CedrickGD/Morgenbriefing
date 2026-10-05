package com.cedrickgd.morgenbriefing.notify

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CatchUpAndFollowUpTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 5)
    private val settings = AppSettings(notifyHour = 5, notifyMinute = 30)

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, 5, hour, minute, 0, 0, berlin)

    private fun brief(date: String) = Brief(date = date, generatedAt = "${date}T05:07:12+02:00")

    // --- SystemEventsReceiver.shouldCatchUp ------------------------------------------------------

    @Test
    fun `missed briefing shortly after the scheduled time is caught up`() {
        assertTrue(SystemEventsReceiver.shouldCatchUp(settings, at(6, 0), today))
        assertTrue(SystemEventsReceiver.shouldCatchUp(settings, at(5, 30), today))
        assertTrue(SystemEventsReceiver.shouldCatchUp(settings, at(8, 29), today))
    }

    @Test
    fun `no catch up before the scheduled time`() {
        assertFalse(SystemEventsReceiver.shouldCatchUp(settings, at(5, 29), today))
    }

    @Test
    fun `no catch up three hours or more after the scheduled time`() {
        assertFalse(SystemEventsReceiver.shouldCatchUp(settings, at(8, 30), today))
        assertFalse(SystemEventsReceiver.shouldCatchUp(settings, at(14, 0), today))
    }

    @Test
    fun `no catch up when today was already notified`() {
        val notified = settings.copy(lastNotifiedDate = "2026-10-05")
        assertFalse(SystemEventsReceiver.shouldCatchUp(notified, at(6, 0), today))
        val yesterday = settings.copy(lastNotifiedDate = "2026-10-04")
        assertTrue(SystemEventsReceiver.shouldCatchUp(yesterday, at(6, 0), today))
    }

    @Test
    fun `no catch up when notifications are switched off`() {
        assertFalse(SystemEventsReceiver.shouldCatchUp(settings.copy(notificationsEnabled = false), at(6, 0), today))
    }

    // --- brief freshness ---------------------------------------------------------------------

    @Test
    fun `brief dates are compared as calendar days`() {
        assertTrue(brief("2026-10-05").isCurrentFor(today))
        assertTrue(brief(" 2026-10-05 ").isCurrentFor(today))
        assertTrue("clock lagging behind", brief("2026-10-06").isCurrentFor(today))
        assertFalse(brief("2026-10-04").isCurrentFor(today))
        assertFalse(brief("gestern").isCurrentFor(today))
    }

    @Test
    fun `shown brief prefers real news, then demo, then cache`() {
        val fresh = brief("2026-10-05")
        val old = brief("2026-10-04")
        assertEquals(fresh, BriefLoad.Loaded(fresh, fromCache = false).shownBrief())
        assertEquals(old, BriefLoad.Demo(old).shownBrief())
        assertEquals(old, BriefLoad.Failed(BriefErrorKind.NETWORK, old).shownBrief())
        assertNull(BriefLoad.Failed(BriefErrorKind.NETWORK, null).shownBrief())
    }

    // --- needsNewsFollowUp -------------------------------------------------------------------

    @Test
    fun `no follow up once today's brief is there`() {
        assertFalse(needsNewsFollowUp(BriefLoad.Loaded(brief("2026-10-05"), false), hasToken = true, today))
        assertFalse(
            "today's brief from the cache is good enough",
            needsNewsFollowUp(BriefLoad.Failed(BriefErrorKind.NETWORK, brief("2026-10-05")), true, today),
        )
    }

    @Test
    fun `follow up while today's brief is missing`() {
        assertTrue(needsNewsFollowUp(BriefLoad.Loaded(brief("2026-10-04"), false), hasToken = true, today))
        assertTrue(needsNewsFollowUp(BriefLoad.Failed(BriefErrorKind.NOT_FOUND, null), true, today))
        assertTrue(needsNewsFollowUp(BriefLoad.Failed(BriefErrorKind.NETWORK, brief("2026-10-04")), true, today))
        assertTrue("token saved meanwhile", needsNewsFollowUp(BriefLoad.Demo(brief("2026-10-01")), true, today))
    }

    @Test
    fun `no follow up without a token`() {
        assertFalse(needsNewsFollowUp(BriefLoad.Demo(brief("2026-10-01")), hasToken = false, today))
        assertFalse(needsNewsFollowUp(BriefLoad.Failed(BriefErrorKind.NO_TOKEN, null), hasToken = false, today))
        assertFalse(needsNewsFollowUp(BriefLoad.Failed(BriefErrorKind.NO_TOKEN, null), hasToken = true, today))
    }
}
