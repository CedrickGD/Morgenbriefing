package com.cedrickgd.morgenbriefing.ui.today

import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.LiveState
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
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
    fun `a block from the settings shows before the service knows`() {
        assertEquals(AiBlock.NO_KEY, liveUi(LiveState(), emptyList(), blocked = AiBlock.NO_KEY, autoRefresh = false).blocked)
        assertNull(liveUi(LiveState(), emptyList(), blocked = null, autoRefresh = false).blocked)
        val limit = LiveState(blocked = AiBlock.LIMIT_REACHED)
        assertEquals(AiBlock.LIMIT_REACHED, liveUi(limit, emptyList(), blocked = AiBlock.NO_KEY, autoRefresh = false).blocked)
        val gemini = liveUi(LiveState(), emptyList(), blocked = AiBlock.LIVE_UNAVAILABLE, autoRefresh = false, provider = AiProviderId.GEMINI)
        assertEquals(AiBlock.LIVE_UNAVAILABLE, gemini.blocked)
        assertEquals(ProviderCatalog.GEMINI, gemini.spec)
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
        assertEquals("Grok recherchiert gerade …", liveStatusLine(LiveUi(running = true, provider = AiProviderId.XAI), now))
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
        val claude = ProviderCatalog.ANTHROPIC
        assertTrue(liveBlockText(AiBlock.UNSUPPORTED_DEVICE, claude).startsWith("Live-Recherche mit Claude braucht Android 8 oder neuer"))
        assertEquals(
            "Trag einmal deinen Claude-API-Key ein – dann sucht Claude hier auf Knopfdruck brandaktuelle Meldungen.",
            liveBlockText(AiBlock.NO_KEY, claude),
        )
        assertEquals("Monatslimit erreicht – in den Einstellungen anpassen.", liveBlockText(AiBlock.LIMIT_REACHED, claude))
        // Gemini: one sentence why (the panel offers "Live-Anbieter wählen").
        assertEquals(ProviderCatalog.GEMINI.liveUnavailableText, liveBlockText(AiBlock.LIVE_UNAVAILABLE, ProviderCatalog.GEMINI))
        assertEquals("Live-Anbieter wählen", AiTexts.LIVE_PROVIDER_ACTION)
        listOf(
            AiError.InvalidKey, AiError.RateLimited, AiError.Overloaded, AiError.Network,
            AiError.Refusal(null), AiError.Truncated, AiError.Unknown("x"), AiError.NoCredit, AiError.QuotaExhausted,
            AiError.ServerUnreachable, AiError.WorkspaceRequired, AiError.WorkspaceNotFound, AiError.ModelUnavailable("m"),
        ).forEach { assertTrue(liveErrorText(it, claude).isNotBlank()) }
        assertEquals(
            "Grok hat deinen API-Key abgelehnt. Schau in den Einstellungen nach, ob er noch stimmt.",
            liveErrorText(AiError.InvalidKey, ProviderCatalog.XAI),
        )
        assertEquals(ProviderCatalog.PERPLEXITY.invalidKeyText, liveErrorText(AiError.InvalidKey, ProviderCatalog.PERPLEXITY))
        // A known cause speaks for itself; without one the generic sentence stays.
        assertEquals("Das gewählte Claude-Modell ist für deinen Key nicht verfügbar.", liveErrorText(AiError.Unknown("Das gewählte Claude-Modell ist für deinen Key nicht verfügbar."), claude))
        assertEquals("Da ist etwas schiefgelaufen. Versuch's gleich nochmal.", liveErrorText(AiError.Unknown(null), claude))
        assertEquals("Da ist etwas schiefgelaufen. Versuch's gleich nochmal.", liveErrorText(AiError.Unknown(" "), claude))
    }

    @Test
    fun `the live texts name who researches`() {
        assertTrue(liveIntroText(ProviderCatalog.OPENAI, freshCount = 2).startsWith("OpenAI sucht, was seit dem Morgen-Briefing passiert ist"))
        assertTrue(liveIntroText(ProviderCatalog.XAI, freshCount = 0).contains("Grok kann für dich nachsehen"))
        assertEquals("Tipp: „Jetzt aktualisieren“ lässt Perplexity nach brandaktuellen Meldungen suchen.", liveEmptyTip(ProviderCatalog.PERPLEXITY))
        assertEquals("OpenRouter recherchiert gerade …", liveStatusLine(LiveUi(running = true, provider = AiProviderId.OPENROUTER), now))
    }
}
