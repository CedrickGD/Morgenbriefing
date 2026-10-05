package com.cedrickgd.morgenbriefing.ui.today

import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LiveModelsTest {

    private val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test
    fun `a missing key blocks before the service knows`() {
        assertEquals(AiBlock.NO_KEY, liveUi(LiveState(), emptyList(), hasKey = false, autoRefresh = false).blocked)
        assertNull(liveUi(LiveState(), emptyList(), hasKey = true, autoRefresh = false).blocked)
        val limit = LiveState(blocked = AiBlock.LIMIT_REACHED)
        assertEquals(AiBlock.LIMIT_REACHED, liveUi(limit, emptyList(), hasKey = false, autoRefresh = false).blocked)
    }

    @Test
    fun `auto refresh only when allowed, possible and due`() {
        val due = LiveUi(autoRefresh = true, lastRunAt = "2026-10-06T11:00:00+02:00")
        assertTrue(shouldAutoRefresh(due, now.toInstant()))
        assertTrue(shouldAutoRefresh(due.copy(lastRunAt = null), now.toInstant()))
        assertFalse(shouldAutoRefresh(due.copy(lastRunAt = "2026-10-06T11:45:00+02:00"), now.toInstant()))
        assertFalse(shouldAutoRefresh(due.copy(autoRefresh = false), now.toInstant()))
        assertFalse(shouldAutoRefresh(due.copy(running = true), now.toInstant()))
        assertFalse(shouldAutoRefresh(due.copy(blocked = AiBlock.NO_KEY), now.toInstant()))
    }

    @Test
    fun `status line tells what happened`() {
        assertEquals("Claude recherchiert gerade …", liveStatusLine(LiveUi(running = true), now))
        assertEquals(
            "Aktualisiert vor 12 Min. · 3 neu",
            liveStatusLine(LiveUi(lastRunAt = "2026-10-06T11:48:00+02:00", newCount = 3), now),
        )
        assertEquals(
            "Aktualisiert vor 12 Min. · keine neuen Meldungen",
            liveStatusLine(LiveUi(lastRunAt = "2026-10-06T11:48:00+02:00"), now),
        )
        assertEquals("Nur was in den letzten 6 Stunden passiert ist", liveStatusLine(LiveUi(), now))
    }

    @Test
    fun `blocks and errors have friendly German copy`() {
        assertTrue(liveBlockText(AiBlock.UNSUPPORTED_DEVICE).startsWith("Live-Recherche braucht Android 8 oder neuer"))
        assertTrue(liveBlockText(AiBlock.NO_KEY).startsWith("Trag einmal deinen Claude-API-Key ein"))
        assertEquals("Monatslimit erreicht – in den Einstellungen anpassen.", liveBlockText(AiBlock.LIMIT_REACHED))
        listOf(
            AiError.InvalidKey, AiError.RateLimited, AiError.Overloaded, AiError.Network,
            AiError.Refusal(null), AiError.Truncated, AiError.Unknown("x"),
        ).forEach { assertTrue(liveErrorText(it).isNotBlank()) }
    }
}
