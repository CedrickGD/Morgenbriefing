package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextsTest {
    private val claude = ProviderCatalog.ANTHROPIC
    private val gemini = ProviderCatalog.GEMINI
    private val server = ProviderCatalog.CUSTOM

    private val allErrors = listOf(
        AiError.InvalidKey, AiError.RateLimited, AiError.Overloaded, AiError.Network, AiError.Refusal("cyber"),
        AiError.Truncated, AiError.Unknown(null), AiError.NoCredit, AiError.WorkspaceRequired, AiError.WorkspaceNotFound,
        AiError.ModelUnavailable("gpt-9"), AiError.ServerUnreachable, AiError.QuotaExhausted,
    )

    @Test
    fun `a used-up free Gemini quota is explained calmly`() {
        assertEquals(
            "Gemini-Gratiskontingent für den Moment aufgebraucht – in ein paar Minuten bzw. morgen geht's weiter.",
            AiTexts.error(AiError.QuotaExhausted, gemini),
        )
        // The user may try again later by hand; nothing retries on its own.
        assertTrue(AiTexts.isRetryable(AiError.QuotaExhausted))
    }

    @Test
    fun `errors name the provider`() {
        assertEquals("Claude hat deinen API-Key abgelehnt. Prüf ihn bitte oder trag einen neuen ein.", AiTexts.error(AiError.InvalidKey, claude))
        assertEquals(ProviderCatalog.PERPLEXITY.invalidKeyText, AiTexts.error(AiError.InvalidKey, ProviderCatalog.PERPLEXITY))
        assertEquals("Gemini ist im Moment überlastet. Versuch es gleich noch einmal.", AiTexts.error(AiError.Overloaded, gemini))
        assertEquals("Dein Server ist im Moment überlastet. Versuch es gleich noch einmal.", AiTexts.error(AiError.Overloaded, server))
        assertEquals("Keine Verbindung zu Claude. Prüf dein Internet und versuch es noch mal.", AiTexts.error(AiError.Network, claude))
        assertTrue(AiTexts.error(AiError.Network, server).startsWith("Keine Verbindung zu deinem Server"))
        assertEquals(
            "Dein Guthaben bei Grok ist aufgebraucht. Lade es in der Konsole auf, dann geht es weiter.",
            AiTexts.error(AiError.NoCredit, ProviderCatalog.XAI),
        )
        assertEquals(
            "Das Modell „gpt-9“ gibt es bei OpenAI nicht (mehr), oder dein Key darf es nicht nutzen. Wähl in den Einstellungen ein anderes.",
            AiTexts.error(AiError.ModelUnavailable("gpt-9"), ProviderCatalog.OPENAI),
        )
        assertTrue(AiTexts.error(AiError.ModelUnavailable(null), claude).startsWith("Das gewählte Modell gibt es bei Claude"))
        // Ollama & Co. answer 404 for a model that is not pulled.
        assertEquals(
            "Das Modell „llama9“ läuft nicht auf deinem Server. Wähl in den Einstellungen eines, das er anbietet.",
            AiTexts.error(AiError.ModelUnavailable("llama9"), server),
        )
        assertTrue(AiTexts.error(AiError.WorkspaceRequired, claude).contains("Workspace-ID"))
        ProviderCatalog.all.forEach { spec -> allErrors.forEach { assertTrue(AiTexts.error(it, spec).isNotBlank()) } }
    }

    @Test
    fun `a new key or workspace id fixes key errors, retrying does not`() {
        listOf(AiError.InvalidKey, AiError.WorkspaceRequired, AiError.WorkspaceNotFound).forEach {
            assertTrue(AiTexts.needsKeyChange(it))
            assertFalse(AiTexts.isRetryable(it))
        }
        listOf(AiError.NoCredit, AiError.ModelUnavailable("x"), AiError.Truncated, AiError.Refusal(null)).forEach {
            assertFalse(it.toString(), AiTexts.isRetryable(it))
        }
        listOf(AiError.RateLimited, AiError.Overloaded, AiError.Network, AiError.ServerUnreachable).forEach {
            assertTrue(it.toString(), AiTexts.isRetryable(it))
        }
    }

    @Test
    fun `blocks name the provider`() {
        assertEquals("Noch kein Claude-Key hinterlegt", AiTexts.blockTitle(AiBlock.NO_KEY, claude))
        assertEquals("Noch kein Gemini-Key hinterlegt", AiTexts.blockTitle(AiBlock.NO_KEY, gemini))
        assertEquals("Noch kein Server eingerichtet", AiTexts.blockTitle(AiBlock.NO_KEY, server))
        assertEquals("Claude braucht Android 8", AiTexts.blockTitle(AiBlock.UNSUPPORTED_DEVICE, claude))
        assertTrue(AiTexts.blockBody(AiBlock.NO_KEY, claude).contains("brandaktuelle Meldungen"))
        // No Live with Gemini: the chat block does not promise it.
        assertFalse(AiTexts.blockBody(AiBlock.NO_KEY, gemini).contains("Meldungen"))
        assertEquals(gemini.liveUnavailableText, AiTexts.blockBody(AiBlock.LIVE_UNAVAILABLE, gemini))
        ProviderCatalog.all.forEach { spec ->
            AiBlock.entries.forEach { block ->
                assertTrue(AiTexts.blockTitle(block, spec).isNotBlank())
                assertTrue(AiTexts.blockBody(block, spec).isNotBlank())
            }
        }
    }

    @Test
    fun `stored errors survive a round trip, unknown kinds stay readable`() {
        allErrors.forEach { assertEquals(it, StoredAiError.of(it).toAiError()) }
        assertEquals(AiError.Unknown("x"), StoredAiError("from_the_future", "x").toAiError())
    }

    @Test
    fun `every block has its one way forward`() {
        assertEquals("Key eintragen", AiTexts.blockAction(AiBlock.NO_KEY, claude))
        assertEquals("Server einrichten", AiTexts.blockAction(AiBlock.NO_KEY, server))
        assertEquals("Limit anpassen", AiTexts.blockAction(AiBlock.LIMIT_REACHED, gemini))
        // Claude below Android 8: another provider; Gemini and Live: another Live provider (G4).
        assertEquals(AiTexts.PROVIDER_ACTION, AiTexts.blockAction(AiBlock.UNSUPPORTED_DEVICE, claude))
        assertEquals("Live-Anbieter wählen", AiTexts.blockAction(AiBlock.LIVE_UNAVAILABLE, gemini))
    }

    @Test
    fun `the own server reads as proper german after a preposition`() {
        assertEquals("deinem eigenen Server", server.dativeName)
        assertEquals("OpenAI", ProviderCatalog.OPENAI.dativeName)
        assertEquals("Live braucht Websuche – mit deinem eigenen Server nicht verfügbar.", server.liveUnavailableText)
        assertEquals("Live mit deinem Server nicht verfügbar", AiTexts.blockTitle(AiBlock.LIVE_UNAVAILABLE, server))
        assertEquals("Live mit Gemini nicht verfügbar", AiTexts.blockTitle(AiBlock.LIVE_UNAVAILABLE, gemini))
        assertEquals("Ohne Websuche – mit deinem eigenen Server nicht verfügbar", AiTexts.chatNote(server, webSearch = false, freeTier = false))
    }
}
