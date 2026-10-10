package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiLogicTest {

    private val claude = ProviderCatalog.ANTHROPIC
    private val today = LocalDate.of(2026, 10, 9)

    /** Price lines keep amounts together with non-breaking spaces; compared as plain text. */
    private fun plain(text: String) = text.replace(NBSP, ' ')

    private companion object {
        const val NBSP = ' '
    }

    /** OpenRouter as the chat provider with a key on [tier]. */
    private fun openRouter(tier: OpenRouterTier, provider: AiProviderId = AiProviderId.OPENROUTER, live: AiProviderId? = null) = AiSettings(
        provider = provider,
        liveProvider = live,
        providers = mapOf(AiProviderId.OPENROUTER to ProviderSettings(hasKey = true, openRouterTier = tier)),
    )

    private fun gemini(tier: GeminiTier, model: String? = null, hasKey: Boolean = true) = AiSettings(
        provider = AiProviderId.GEMINI,
        providers = mapOf(AiProviderId.GEMINI to ProviderSettings(hasKey = hasKey, geminiTier = tier, model = model)),
    )

    @Test
    fun `dollar amounts the german way`() {
        assertEquals("0 $", usd(0.0))
        assertEquals("0 $", usd(-1.0))
        assertEquals("0 $", usd(Double.NaN))
        assertEquals("< 0,01 $", usd(0.003))
        assertEquals("0,84 $", usd(0.84))
        assertEquals("5 $", usd(5.0))
        assertEquals("12,50 $", usd(12.5))
        assertEquals("1 $", usd(0.999))
    }

    @Test
    fun `usage line with and without a cap`() {
        assertEquals(
            "Diesen Monat: 0,84 $ von 5 $ · 23 Anfragen · 7 Websuchen",
            usageLine(spent = 0.84, limit = 5.0, requests = 23, webSearches = 7),
        )
        assertEquals("Diesen Monat: 0 $ · 1 Anfrage · 1 Websuche", usageLine(0.0, 0.0, 1, 1))
        assertEquals("Diesen Monat: 2,10 $ · 0 Anfragen · 0 Websuchen", usageLine(2.1, 0.0, 0, -3))
    }

    @Test
    fun `usage fraction is clamped and absent without cap`() {
        assertNull(usageFraction(1.0, 0.0))
        assertEquals(0f, usageFraction(0.0, 5.0)!!, 0f)
        assertEquals(0.2f, usageFraction(1.0, 5.0)!!, 0.0001f)
        assertEquals(1f, usageFraction(9.0, 5.0)!!, 0f)
    }

    @Test
    fun `monthly limit slider`() {
        assertEquals(49, AiLimit.SLIDER_STEPS)
        assertEquals(5.0, AiLimit.normalize(4.6), 0.0)
        assertEquals(50.0, AiLimit.normalize(80.0), 0.0)
        assertEquals(0.0, AiLimit.normalize(-2.0), 0.0)
        assertEquals(0.0, AiLimit.normalize(Double.NaN), 0.0)
        assertEquals("Kein Limit", AiLimit.label(0.0))
        assertEquals("5 $ im Monat", AiLimit.label(5.0))
        assertEquals("50 $ im Monat", AiLimit.label(50.0))
    }

    @Test
    fun `limit hint does not talk about reaching a cap that is off`() {
        assertTrue(AiLimit.hint(0.0).startsWith("Ohne Limit"))
        assertTrue(AiLimit.hint(0.3).startsWith("Ohne Limit"))
        assertTrue(AiLimit.hint(5.0).startsWith("Ist es erreicht"))
    }

    @Test
    fun `limit hint covers every provider and names who keeps running`() {
        assertFalse(AiLimit.hint(0.0).contains("Anthropic"))
        val capped = AiLimit.hint(5.0)
        assertTrue(capped.contains("alle Anbieter zusammen"))
        assertTrue(capped.contains("kostenlosen Tarife von Gemini und OpenRouter"))
    }

    @Test
    fun `key check results`() {
        assertNull(AiKeyCheck.OK.toProblem())
        assertNull(AiKeyCheck.OK_NO_CREDIT.toProblem())
        assertEquals(AiKeyProblem.INVALID, AiKeyCheck.INVALID.toProblem())
        assertEquals(AiKeyProblem.NETWORK, AiKeyCheck.NETWORK.toProblem())
        assertEquals(AiKeyProblem.UNSUPPORTED, AiKeyCheck.UNSUPPORTED.toProblem())
        assertEquals(AiKeyProblem.WORKSPACE_REQUIRED, AiKeyCheck.WORKSPACE_REQUIRED.toProblem())
        assertEquals(AiKeyProblem.SERVER_UNREACHABLE, AiKeyCheck.SERVER_UNREACHABLE.toProblem())
        assertEquals(AiKeyProblem.SERVER_CERTIFICATE, AiKeyCheck.SERVER_CERTIFICATE.toProblem())
    }

    @Test
    fun `an untrusted own server certificate is no network problem`() {
        val text = aiKeyMessage(AiKeyState.Failed(AiKeyProblem.SERVER_CERTIFICATE), ProviderCatalog.CUSTOM)!!
        assertTrue(text.startsWith("✗ Dein Server antwortet, aber"))
        assertTrue(text.contains("Let's-Encrypt"))
        // Saving untested would not help: every request would fail the same way.
        assertFalse(canSaveUntested(AiKeyState.Failed(AiKeyProblem.SERVER_CERTIFICATE)))
    }

    @Test
    fun `key messages`() {
        assertNull(aiKeyMessage(AiKeyState.Idle, claude))
        assertEquals("✓ Der Key funktioniert und ist gespeichert.", aiKeyMessage(AiKeyState.Saved, claude))
        assertEquals("✗ Keine Verbindung zu Anthropic – bist du online?", aiKeyMessage(AiKeyState.Failed(AiKeyProblem.NETWORK), claude))
        ProviderCatalog.all.forEach { spec ->
            AiKeyProblem.entries.forEach { problem ->
                assertTrue("${spec.id} $problem", aiKeyMessage(AiKeyState.Failed(problem), spec)!!.startsWith("✗"))
            }
        }
    }

    @Test
    fun `key messages name the company that checks the key`() {
        assertEquals("Prüfe den Key bei Anthropic …", aiKeyMessage(AiKeyState.Checking, claude))
        assertEquals("Prüfe den Key bei Google …", aiKeyMessage(AiKeyState.Checking, ProviderCatalog.GEMINI))
        assertEquals("Prüfe den Key bei xAI …", aiKeyMessage(AiKeyState.Checking, ProviderCatalog.XAI))
        assertEquals("Verbinde mit deinem Server …", aiKeyMessage(AiKeyState.Checking, ProviderCatalog.CUSTOM))
        assertEquals(
            "✗ Google lehnt den Key ab – hast du ihn vollständig kopiert?",
            aiKeyMessage(AiKeyState.Failed(AiKeyProblem.INVALID), ProviderCatalog.GEMINI),
        )
        // Perplexity says "invalid" for an empty balance too: its own sentence says both.
        assertEquals(
            "✗ ${ProviderCatalog.PERPLEXITY.invalidKeyText}",
            aiKeyMessage(AiKeyState.Failed(AiKeyProblem.INVALID), ProviderCatalog.PERPLEXITY),
        )
        assertEquals("✗ Claude läuft erst ab Android 8.", aiKeyMessage(AiKeyState.Failed(AiKeyProblem.UNSUPPORTED), claude))
    }

    @Test
    fun `the own server reports how many models it found`() {
        assertEquals("✓ Verbunden – 3 Modelle gefunden.", aiKeyMessage(AiKeyState.ServerConnected(3), ProviderCatalog.CUSTOM))
        assertEquals("✓ Verbunden – 1 Modell gefunden.", aiKeyMessage(AiKeyState.ServerConnected(1), ProviderCatalog.CUSTOM))
        assertTrue(aiKeyMessage(AiKeyState.ServerConnected(0), ProviderCatalog.CUSTOM)!!.startsWith("✓ Verbunden"))
        assertTrue(AiKeyState.ServerConnected(2).isSaved)
    }

    @Test
    fun `a gemini key without billing where google wants one gets its own warning`() {
        // 402: the prepaid credit of the Google project is used up.
        val text = aiKeyMessage(AiKeyState.SavedNoCredit, ProviderCatalog.GEMINI)!!
        assertTrue(text.startsWith("Gespeichert ✓"))
        assertTrue(text.contains("Prepay-Guthaben"))
        assertTrue(text.contains("AI Studio"))
        assertEquals(
            "Gespeichert ✓ – aber dein Guthaben ist leer. Lade es auf, sonst schlagen Anfragen fehl.",
            aiKeyMessage(AiKeyState.SavedNoCredit, ProviderCatalog.OPENAI),
        )
    }

    @Test
    fun `save untested only after a connection problem`() {
        assertTrue(canSaveUntested(AiKeyState.Failed(AiKeyProblem.NETWORK)))
        assertTrue(canSaveUntested(AiKeyState.Failed(AiKeyProblem.SERVER_UNREACHABLE)))
        assertFalse(canSaveUntested(AiKeyState.Failed(AiKeyProblem.INVALID)))
        assertFalse(canSaveUntested(AiKeyState.Idle))
    }

    @Test
    fun `a Gemini key test that fails says Google's reason, shows the details and the page that fixes it`() {
        val gemini = ProviderCatalog.GEMINI
        val auth = AiKeyState.Failed(AiKeyProblem.AUTH_KEY_REJECTED, "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED")
        val text = aiKeyMessage(auth, gemini)!!
        assertEquals(
            "✗ Google nimmt deinen Key gerade nicht an – ein bekannter Fehler bei Google, der viele neue Keys („AQ.…“) trifft, " +
                "auch wenn AI Studio sie als gültig zeigt. Was hilft: ein älterer Key, der mit „AIza“ beginnt – " +
                "oder OpenRouter kostenlos mit Gemma 4, Googles offenem Modell.",
            text,
        )
        assertEquals("HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED", auth.diagnostic)
        assertEquals(KeyFixLink("Keys in AI Studio", "https://aistudio.google.com/apikey"), aiKeyFixLink(auth))
        assertFalse(canSaveUntested(auth))
        // The way out without a Google key: OpenRouter's free tariff.
        assertTrue(aiKeyOffersOpenRouter(auth))
        assertFalse(aiKeyOffersOpenRouter(AiKeyState.Failed(AiKeyProblem.KEY_LEAKED)))
        assertFalse(aiKeyOffersOpenRouter(AiKeyState.Failed(AiKeyProblem.INVALID)))
        assertFalse(aiKeyOffersOpenRouter(AiKeyState.Saved))

        val blocked = AiKeyState.Failed(AiKeyProblem.API_BLOCKED, "HTTP 403 · PERMISSION_DENIED · SERVICE_DISABLED")
        assertTrue(aiKeyMessage(blocked, gemini)!!.startsWith("✗ Die Gemini API ist für dein Google-Projekt gesperrt oder nicht aktiviert"))
        assertEquals("https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com", aiKeyFixLink(blocked)!!.url)
        // The key may be fine: once the API is on, it works without being entered again.
        assertTrue(canSaveUntested(blocked))

        val region = AiKeyState.Failed(AiKeyProblem.FREE_TIER_UNAVAILABLE)
        assertTrue(aiKeyMessage(region, gemini)!!.contains("Der kostenlose Gemini-Tarif ist für dein Land/Projekt nicht freigeschaltet"))
        assertEquals("https://aistudio.google.com/projects", aiKeyFixLink(region)!!.url)
        assertTrue(canSaveUntested(region))

        assertNotNull(aiKeyFixLink(AiKeyState.Failed(AiKeyProblem.KEY_RESTRICTED)))
        assertTrue(aiKeyMessage(AiKeyState.Failed(AiKeyProblem.MODEL_UNAVAILABLE), gemini)!!.startsWith("✗ Der Key funktioniert, aber keines der Gemini-Modelle"))
        assertNull(aiKeyFixLink(AiKeyState.Failed(AiKeyProblem.INVALID)))
        assertNull(aiKeyFixLink(AiKeyState.Saved))
        // A failure without details has none to show; equality only looks at the problem plus details.
        assertNull(AiKeyState.Failed(AiKeyProblem.NETWORK).diagnostic)
        assertTrue(AiKeyState.Failed(AiKeyProblem.WORKSPACE_REQUIRED, "HTTP 400").failedWith(AiKeyProblem.WORKSPACE_REQUIRED))
        assertTrue(canSaveUntested(AiKeyState.Failed(AiKeyProblem.NETWORK, "Verbindung · UnknownHostException")))

        // The facade's verdicts map to these problems; a used-up quota still stores the key.
        assertEquals(AiKeyProblem.AUTH_KEY_REJECTED, AiKeyCheck.AUTH_KEY_REJECTED.toProblem())
        assertEquals(AiKeyProblem.API_BLOCKED, AiKeyCheck.API_BLOCKED.toProblem())
        assertEquals(AiKeyProblem.KEY_RESTRICTED, AiKeyCheck.KEY_RESTRICTED.toProblem())
        assertEquals(AiKeyProblem.FREE_TIER_UNAVAILABLE, AiKeyCheck.FREE_TIER_UNAVAILABLE.toProblem())
        assertEquals(AiKeyProblem.MODEL_UNAVAILABLE, AiKeyCheck.MODEL_UNAVAILABLE.toProblem())
        assertNull(AiKeyCheck.OK_QUOTA_EXHAUSTED.toProblem())
    }

    @Test
    fun `a passed key test says what is special about the key`() {
        val gemini = ProviderCatalog.GEMINI
        assertEquals(AiKeyState.Saved, savedKeyState(KeyCheckOutcome(AiKeyCheck.OK)))
        assertEquals(AiKeyState.SavedUntested, savedKeyState(null))
        assertEquals(AiKeyState.SavedNoCredit, savedKeyState(KeyCheckOutcome(AiKeyCheck.OK_NO_CREDIT)))

        val fallback = savedKeyState(KeyCheckOutcome(AiKeyCheck.OK, fallback = ModelFallback("gemini-3.8-flash", "gemini-3.7-flash")))
        assertEquals(AiKeyState.SavedWithFallback("gemini-3.8-flash", "gemini-3.7-flash"), fallback)
        assertEquals(
            "✓ Der Key funktioniert und ist gespeichert. Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.7 Flash.",
            aiKeyMessage(fallback, gemini),
        )
        assertTrue(fallback.isSaved)

        val quota = savedKeyState(KeyCheckOutcome(AiKeyCheck.OK_QUOTA_EXHAUSTED, diagnostic = "HTTP 429 · RESOURCE_EXHAUSTED"))
        assertEquals(AiKeyState.SavedQuotaExhausted("HTTP 429 · RESOURCE_EXHAUSTED"), quota)
        assertTrue(aiKeyMessage(quota, gemini)!!.startsWith("Gespeichert ✓ – aber das Kontingent"))
        assertEquals("HTTP 429 · RESOURCE_EXHAUSTED", quota.diagnostic)
        assertTrue(quota.isSaved)
    }

    @Test
    fun `the model picker marks the chosen model and says which one runs instead`() {
        val ai = gemini(GeminiTier.FREE).let { a ->
            a.with(AiProviderId.GEMINI, a.of(AiProviderId.GEMINI).copy(modelFallback = ModelFallback("gemini-3.8-flash", "gemini-3.7-flash")))
        }
        assertEquals("gemini-3.8-flash", modelChoices(ai, AiProviderId.GEMINI, today).single { it.selected }.id)
        assertEquals(
            "Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.7 Flash.",
            modelFallbackNote(ai, AiProviderId.GEMINI),
        )
        assertNull(modelFallbackNote(gemini(GeminiTier.FREE), AiProviderId.GEMINI))
        assertNull(modelFallbackNote(ai.withModel(AiProviderId.GEMINI, "gemini-3.5-flash-lite"), AiProviderId.GEMINI))
    }

    @Test
    fun `a key saved without a passed test never claims to work`() {
        assertEquals(AiKeyState.Saved, savedKeyState(tested = true))
        assertEquals(AiKeyState.SavedUntested, savedKeyState(tested = false))
        val untested = aiKeyMessage(AiKeyState.SavedUntested, claude)!!
        assertEquals("Gespeichert – noch nicht getestet. Die erste Anfrage zeigt, ob er funktioniert.", untested)
        assertFalse(untested.startsWith("✓"))
        assertFalse(untested.contains("funktioniert und"))
    }

    @Test
    fun `key row label`() {
        assertEquals("Noch keiner eingetragen", aiKeyRowLabel(claude, hasKey = false, untested = false))
        assertEquals("Gespeichert ✓ · verschlüsselt", aiKeyRowLabel(claude, hasKey = true, untested = false))
        assertEquals("Gespeichert · noch nicht getestet", aiKeyRowLabel(claude, hasKey = true, untested = true))
        // The flag means nothing without a key.
        assertEquals("Noch keiner eingetragen", aiKeyRowLabel(claude, hasKey = false, untested = true))
    }

    @Test
    fun `a key anthropic refused says so, also when it was never tested`() {
        assertEquals("Gespeichert · von Anthropic abgelehnt", aiKeyRowLabel(claude, hasKey = true, untested = false, rejected = true))
        assertEquals("Gespeichert · von Anthropic abgelehnt", aiKeyRowLabel(claude, hasKey = true, untested = true, rejected = true))
        assertEquals("Gespeichert · noch nicht getestet", aiKeyRowLabel(claude, hasKey = true, untested = true, rejected = false))
        assertEquals("Noch keiner eingetragen", aiKeyRowLabel(claude, hasKey = false, untested = false, rejected = true))
        assertEquals("Gespeichert · von Google abgelehnt", aiKeyRowLabel(ProviderCatalog.GEMINI, hasKey = true, untested = false, rejected = true))
    }

    @Test
    fun `key format hint`() {
        fun looksRight(raw: String) = aiKeyFormatIssue(raw, claude) == null
        assertTrue(looksRight("  sk-ant-api03-abcdefghijklmnopqrstuvwxyz  "))
        assertFalse(looksRight("sk-ant-short"))
        assertFalse(looksRight("github_pat_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(looksRight("sk-ant-api03 abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `key format hint names the cause`() {
        // Spaces and line breaks around a pasted key are dropped silently.
        assertNull(aiKeyFormatIssue("\n  sk-ant-api03-abcdefghijklmnopqrstuvwxyz \n", claude))
        assertEquals(AiKeyFormatIssue.WRONG_PREFIX, aiKeyFormatIssue("github_pat_abcdefghijklmnopqrstuvwxyz", claude))
        assertEquals(AiKeyFormatIssue.WRONG_PREFIX, aiKeyFormatIssue("Bearer sk-ant-api03-abcdefghijklmnopqrstuvwxyz", claude))
        assertEquals(AiKeyFormatIssue.TOO_SHORT, aiKeyFormatIssue("sk-ant-short", claude))
        assertEquals(AiKeyFormatIssue.WHITESPACE, aiKeyFormatIssue("sk-ant-api03 abcdefghijklmnopqrstuvwxyz", claude))
        assertEquals(AiKeyFormatIssue.WHITESPACE, aiKeyFormatIssue("sk-ant-api03-abcdefghij\nklmnopqrstuvwxyz", claude))
        // A line break inside the prefix is still a whitespace problem, not a foreign key.
        assertEquals(AiKeyFormatIssue.WHITESPACE, aiKeyFormatIssue("sk-an\nt-api03-abcdefghijklmnopqrstuvwxyz", claude))

        assertEquals("Claude-Keys beginnen mit „sk-ant-“ – stimmt die Kopie?", aiKeyFormatHint(AiKeyFormatIssue.WRONG_PREFIX, claude))
        assertEquals("Der Key wirkt zu kurz – hast du ihn vollständig kopiert?", aiKeyFormatHint(AiKeyFormatIssue.TOO_SHORT, claude))
        assertEquals("Der Key enthält Leerzeichen – kopier ihn ohne Zeilenumbruch.", aiKeyFormatHint(AiKeyFormatIssue.WHITESPACE, claude))
    }

    @Test
    fun `gemini accepts both key shapes and only complains about spaces`() {
        val gemini = ProviderCatalog.GEMINI
        assertNull(aiKeyFormatIssue("AIza-test-invalid", gemini))
        assertNull(aiKeyFormatIssue("AQ.test-invalid", gemini))
        // The format is undocumented: no prefix, no length (GE §13).
        assertNull(aiKeyFormatIssue("x", gemini))
        assertEquals(AiKeyFormatIssue.WHITESPACE, aiKeyFormatIssue("AIza test", gemini))
    }

    @Test
    fun `an invisible or non-ASCII character in a key gets its own hint`() {
        val gemini = ProviderCatalog.GEMINI
        // A zero-width space or a BOM that a copy brought along is dropped when saving (cleanApiKey): no hint.
        assertNull(aiKeyFormatIssue("AIza-test-invalid​", gemini))
        assertNull(aiKeyFormatIssue("﻿AQ.test-invalid", gemini))
        assertNull(aiKeyFormatIssue("​sk-ant-api03-abcdefghijklmnopqrstuvwxyz", claude))
        // An umlaut stays: such a key cannot be sent.
        assertEquals(AiKeyFormatIssue.ODD_CHARACTER, aiKeyFormatIssue("AIza-test-invälid", gemini))
        // Not a wrong prefix: it starts right once the odd character is gone.
        assertEquals(AiKeyFormatIssue.ODD_CHARACTER, aiKeyFormatIssue("sk-ant-äpi03-abcdefghijklmnopqrstuvwxyz", claude))
        assertEquals(
            "Der Key enthält ein unsichtbares oder ungewöhnliches Zeichen – kopier ihn bitte noch einmal.",
            aiKeyFormatHint(AiKeyFormatIssue.ODD_CHARACTER, gemini),
        )
    }

    @Test
    fun `providers with a documented prefix get their own hint`() {
        val openRouter = ProviderCatalog.OPENROUTER
        assertEquals(AiKeyFormatIssue.WRONG_PREFIX, aiKeyFormatIssue("sk-test-invalid-but-long-enough", openRouter))
        assertNull(aiKeyFormatIssue("sk-or-v1-test-invalid-long-enough", openRouter))
        assertEquals("OpenRouter-Keys beginnen mit „sk-or-“ – stimmt die Kopie?", aiKeyFormatHint(AiKeyFormatIssue.WRONG_PREFIX, openRouter))
        // The own server's key is whatever the server wants.
        assertNull(aiKeyFormatIssue("token", ProviderCatalog.CUSTOM))
    }

    @Test
    fun `dialog titles and intros per provider`() {
        assertEquals("Claude-API-Key eintragen", aiKeyDialogTitle(claude, hasKey = false))
        assertEquals("Claude-API-Key ändern", aiKeyDialogTitle(claude, hasKey = true))
        assertEquals("Gemini-API-Key eintragen", aiKeyDialogTitle(ProviderCatalog.GEMINI, hasKey = false))
        assertEquals("xAI-API-Key eintragen", aiKeyDialogTitle(ProviderCatalog.XAI, hasKey = false))
        assertEquals("Eigenen Server einrichten", aiKeyDialogTitle(ProviderCatalog.CUSTOM, hasKey = false))
        assertTrue(aiKeyIntro(claude).contains("Anthropic-Konto"))
        assertTrue(aiKeyIntro(ProviderCatalog.GEMINI).contains("kostenlos oder mit Abrechnung"))
        assertTrue(aiKeyIntro(ProviderCatalog.MISTRAL).contains("Mistral-Konto"))
    }

    @Test
    fun `workspace id hint`() {
        assertNull(workspaceIdHint(""))
        assertNull(workspaceIdHint(" wrkspc_01ABC "))
        assertNotNull(workspaceIdHint("01ABC"))
    }

    @Test
    fun `server label shows host and port without the default path`() {
        assertEquals("nas.fritz.box:11434", serverLabel("http://nas.fritz.box:11434/v1"))
        assertEquals("ai.example.org/api/v1", serverLabel("https://ai.example.org/api/v1"))
        assertEquals("Noch keiner eingerichtet", serverLabel(null))
        assertEquals("Noch keiner eingerichtet", serverLabel(" "))
    }

    @Test
    fun `capabilities follow the catalog and the gemini tariff`() {
        fun available(spec: com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec, ai: AiSettings? = null) =
            capabilities(spec, ai).map { it.available }
        assertEquals(listOf(true, true, true, false), available(claude))
        assertEquals(listOf(true, true, true, true), available(ProviderCatalog.OPENAI))
        assertEquals(listOf(true, false, false, true), available(ProviderCatalog.MISTRAL))
        // Gemini: search with billing, never Live.
        assertEquals(listOf(true, true, false, true), available(ProviderCatalog.GEMINI, gemini(GeminiTier.PAID)))
        val free = capabilities(ProviderCatalog.GEMINI, gemini(GeminiTier.FREE))
        assertFalse(free[1].available)
        assertEquals("Websuche nur mit Abrechnung", free[1].label)
        // TalkBack hears the same as the pill shows: billing turns the search on.
        assertEquals("Websuche nur mit Abrechnung", free[1].spoken)
        assertEquals(
            "Chat verfügbar, Websuche nur mit Abrechnung, Live nicht verfügbar, läuft auf Android 7",
            capabilitySemantics(free),
        )
        assertEquals("Websuche nicht verfügbar", capabilities(ProviderCatalog.MISTRAL)[1].spoken)
        assertEquals("ab Android 8", capabilities(claude)[3].label)
        assertEquals(
            "Chat verfügbar, Websuche verfügbar, Live verfügbar, braucht Android 8",
            capabilitySemantics(capabilities(claude)),
        )
    }

    @Test
    fun `provider status line in the picker`() {
        val ai = AiSettings(
            providers = mapOf(
                AiProviderId.OPENAI to ProviderSettings(hasKey = true),
                AiProviderId.XAI to ProviderSettings(hasKey = true, keyRejected = true),
                AiProviderId.CUSTOM to ProviderSettings(baseUrl = "http://nas.fritz.box:11434/v1"),
                AiProviderId.GEMINI to ProviderSettings(hasKey = true, geminiTier = GeminiTier.FREE),
            ),
        )
        assertEquals("Key gespeichert ✓", providerStatusLine(ai, AiProviderId.OPENAI, supported = true))
        assertEquals("Key abgelehnt", providerStatusLine(ai, AiProviderId.XAI, supported = true))
        assertEquals("Kein Key", providerStatusLine(ai, AiProviderId.MISTRAL, supported = true))
        assertEquals("Server eingerichtet · nas.fritz.box:11434", providerStatusLine(ai, AiProviderId.CUSTOM, supported = true))
        assertEquals("Key gespeichert ✓ · kostenlos", providerStatusLine(ai, AiProviderId.GEMINI, supported = true))
        assertEquals("Braucht Android 8", providerStatusLine(ai, AiProviderId.ANTHROPIC, supported = false))
    }

    @Test
    fun `openrouter on the free tariff shows what it lacks and needs credits for live`() {
        val free = openRouter(OpenRouterTier.FREE)
        val pills = capabilities(ProviderCatalog.OPENROUTER, free)
        assertEquals(listOf(true, false, false, true), pills.map { it.available })
        assertEquals("Websuche nur mit Guthaben", pills[1].label)
        assertEquals("Live nur mit Guthaben", pills[2].label)
        assertEquals(
            "Chat verfügbar, Websuche nur mit Guthaben, Live nur mit Guthaben, läuft auf Android 7",
            capabilitySemantics(pills),
        )
        // With credits (and without settings) OpenRouter can do everything.
        assertEquals(listOf(true, true, true, true), capabilities(ProviderCatalog.OPENROUTER, openRouter(OpenRouterTier.CREDITS)).map { it.available })
        assertEquals("Live", capabilities(ProviderCatalog.OPENROUTER)[2].label)
        // The picker names the tariff.
        assertEquals("Key gespeichert ✓ · kostenlos", providerStatusLine(free, AiProviderId.OPENROUTER, supported = true))
        assertEquals("Key gespeichert ✓ · mit Guthaben", providerStatusLine(openRouter(OpenRouterTier.CREDITS), AiProviderId.OPENROUTER, supported = true))
        assertEquals("Kein Key", providerStatusLine(AiSettings(), AiProviderId.OPENROUTER, supported = true))
        // Live: no "wie Chat" on the free tariff; picking OpenRouter for Live asks for its credits.
        assertNull(liveProviderLine(free))
        assertEquals("Live über OpenRouter (wie Chat)", liveProviderLine(openRouter(OpenRouterTier.CREDITS)))
        assertEquals("Live über OpenAI", liveProviderLine(openRouter(OpenRouterTier.FREE, live = AiProviderId.OPENAI)))
        val choices = liveProviderChoices(free, sdkAvailable = true)
        assertFalse(null in choices)
        assertTrue(AiProviderId.OPENROUTER in choices)
        assertEquals(null, liveProviderChoices(openRouter(OpenRouterTier.CREDITS), sdkAvailable = true).first())
        assertTrue(liveNeedsSetup(free, AiProviderId.OPENROUTER))
        assertFalse(liveNeedsSetup(openRouter(OpenRouterTier.CREDITS), AiProviderId.OPENROUTER))
        assertTrue(liveNeedsSetup(free, AiProviderId.OPENAI))
        assertFalse(liveNeedsSetup(free.with(AiProviderId.OPENAI, ProviderSettings(hasKey = true)), AiProviderId.OPENAI))
    }

    @Test
    fun `live provider line - same as chat, separate, or none`() {
        assertEquals("Live über OpenAI (wie Chat)", liveProviderLine(AiSettings(provider = AiProviderId.OPENAI)))
        assertEquals(
            "Live über OpenAI",
            liveProviderLine(AiSettings(provider = AiProviderId.GEMINI, liveProvider = AiProviderId.OPENAI)),
        )
        // Gemini cannot run Live (Google's terms): the section explains and offers a Live provider.
        assertNull(liveProviderLine(AiSettings(provider = AiProviderId.GEMINI)))
        assertNull(liveProviderLine(AiSettings(provider = AiProviderId.CUSTOM)))
    }

    @Test
    fun `live labels name the brand without nested brackets`() {
        assertEquals("Live über Claude (wie Chat)", liveProviderLine(AiSettings(provider = AiProviderId.ANTHROPIC)))
        assertEquals("Wie Chat (Claude)", sameAsChatLabel(ProviderCatalog.ANTHROPIC))
        assertEquals("Live über Grok", liveProviderLine(AiSettings(provider = AiProviderId.OPENAI, liveProvider = AiProviderId.XAI)))
        ProviderCatalog.all.filter { it.live == LiveSupport.YES }.forEach { spec ->
            // OpenRouter researches with credits.
            val labels = listOfNotNull(
                liveProviderLine(openRouter(OpenRouterTier.CREDITS, provider = spec.id)),
                liveProviderLine(openRouter(OpenRouterTier.CREDITS, provider = AiProviderId.GEMINI, live = spec.id)),
                sameAsChatLabel(spec),
            )
            assertEquals(spec.id.key, 3, labels.size)
            labels.forEach { label ->
                assertTrue(label, label.count { it == '(' } <= 1)
                assertTrue(label, label.contains(spec.brand))
            }
        }
    }

    @Test
    fun `live picker offers only providers that can research here`() {
        val geminiChat = liveProviderChoices(AiSettings(provider = AiProviderId.GEMINI), sdkAvailable = true)
        // No "Wie Chat" for a chat provider without Live; no Gemini, Mistral, DeepSeek or own server.
        assertEquals(
            listOf(AiProviderId.ANTHROPIC, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.PERPLEXITY, AiProviderId.OPENROUTER),
            geminiChat,
        )
        val android7 = liveProviderChoices(AiSettings(provider = AiProviderId.OPENAI), sdkAvailable = false)
        assertEquals(null, android7.first())
        assertFalse(AiProviderId.ANTHROPIC in android7)
    }

    @Test
    fun `picking the chat provider for live stores same as chat`() {
        val ai = AiSettings(provider = AiProviderId.OPENAI)
        assertNull(liveProviderToStore(ai, null))
        assertNull(liveProviderToStore(ai, AiProviderId.OPENAI))
        assertEquals(AiProviderId.XAI, liveProviderToStore(ai, AiProviderId.XAI))
    }

    @Test
    fun `model choices carry prices and mark the default`() {
        val choices = modelChoices(AiSettings(provider = AiProviderId.ANTHROPIC), AiProviderId.ANTHROPIC, today)
        val sonnet = choices.first()
        assertEquals("claude-sonnet-5-5", sonnet.id)
        assertTrue(sonnet.selected)
        assertEquals("Standard", sonnet.badge)
        assertEquals("Schnell und gründlich · 2 $ / 10 $ je 1 Mio. Tokens", plain(sonnet.subtitle))
        // Amounts and the unit never break across lines.
        assertTrue(sonnet.subtitle.endsWith("2${NBSP}$ / 10${NBSP}$ je${NBSP}1${NBSP}Mio.${NBSP}Tokens"))
        assertTrue(choices.all { it.enabled })
    }

    @Test
    fun `free gemini - every model is free, pro needs billing and cannot be picked`() {
        val free = modelChoices(gemini(GeminiTier.FREE), AiProviderId.GEMINI, today)
        val pro = free.single { it.id == "gemini-3.1-pro-preview" }
        assertFalse(pro.enabled)
        assertTrue(pro.subtitle.endsWith(GeminiTiers.BILLING_ONLY))
        assertTrue(free.filter { it.enabled }.all { it.subtitle.endsWith("kostenlos") })
        // Free stays free: no note that the paid price doubles in 2027.
        assertEquals("Schnell und günstig · kostenlos", free.single { it.id == "gemini-3.8-flash" }.subtitle)
        assertTrue(free.none { "2027" in it.subtitle })
        // A Pro model stored before switching back to free: the default is what runs (and shows).
        val switchedBack = modelChoices(gemini(GeminiTier.FREE, model = "gemini-3.1-pro-preview"), AiProviderId.GEMINI, today)
        assertTrue(switchedBack.single { it.id == "gemini-3.8-flash" }.selected)

        val paid = modelChoices(gemini(GeminiTier.PAID), AiProviderId.GEMINI, today)
        assertTrue(paid.all { it.enabled })
        assertEquals("Stärkstes Gemini · 2 $ / 12 $ je 1 Mio. Tokens", plain(paid.single { it.id == "gemini-3.1-pro-preview" }.subtitle))
        assertEquals("Schnell und günstig (ab 2027 doppelter Preis) · 0,75 $ / 3,75 $ je 1 Mio. Tokens", plain(paid.first().subtitle))
    }

    @Test
    fun `free gemini - a typed pro id is marked and cannot be taken`() {
        val free = gemini(GeminiTier.FREE, model = "gemini-pro-latest")
        assertEquals("gemini-pro-latest · ${GeminiTiers.BILLING_ONLY}", customModelSubtitle(free, AiProviderId.GEMINI, today))
        // The default runs and shows as chosen.
        assertTrue(modelChoices(free, AiProviderId.GEMINI, today).single { it.id == "gemini-3.8-flash" }.selected)
        assertEquals(GeminiTiers.PRO_NEEDS_BILLING, customModelBlockedHint(free, AiProviderId.GEMINI, "gemini-3.1-pro-preview-customtools"))
        assertNull(customModelBlockedHint(free, AiProviderId.GEMINI, "gemini-3.7-flash"))
        assertNull(customModelBlockedHint(free, AiProviderId.GEMINI, " "))

        val paid = gemini(GeminiTier.PAID, model = "gemini-pro-latest")
        assertNull(customModelBlockedHint(paid, AiProviderId.GEMINI, "gemini-pro-latest"))
        assertEquals("gemini-pro-latest · Preis geschätzt", customModelSubtitle(paid, AiProviderId.GEMINI, today))
        assertEquals("z. B. ${customModelExample(AiProviderId.GEMINI)}", customModelSubtitle(gemini(GeminiTier.FREE), AiProviderId.GEMINI, today))
    }

    @Test
    fun `own server models keep the server's order when one is picked`() {
        val listed = listOf("gpt-6.1-sol", "gpt-6-luna", "mistral-small-2603", "llama3.3")
        // Picking llama3.3 must not move it to the top (the rows would shift under the finger).
        assertEquals(listed, serverModelOrder("llama3.3", listed))
        assertEquals(listed, serverModelOrder(null, listed))
        // Not on the server's list (not loaded yet, or typed): in front.
        assertEquals(listOf("qwen3:14b") + listed, serverModelOrder("qwen3:14b", listed))
        assertEquals(listOf("qwen3:14b"), serverModelOrder("qwen3:14b", emptyList()))
    }

    @Test
    fun `gemini flash prices double in 2027`() {
        val ai = gemini(GeminiTier.PAID)
        assertEquals("1,50 $ / 7,50 $ je 1 Mio. Tokens", plain(priceLine(ai, AiProviderId.GEMINI, "gemini-3.8-flash", LocalDate.of(2027, 1, 1))))
    }

    @Test
    fun `own model - stored id, example and estimated price`() {
        val ai = AiSettings(provider = AiProviderId.OPENAI).withModel(AiProviderId.OPENAI, "gpt-5.6-terra")
        assertEquals("gpt-5.6-terra", customModelOf(ai, AiProviderId.OPENAI))
        assertNull(customModelOf(ai.withModel(AiProviderId.OPENAI, "gpt-6-luna"), AiProviderId.OPENAI))
        assertNull(customModelPriceHint(ai, AiProviderId.OPENAI, "gpt-5.6-terra", today))
        assertEquals(
            "Preis geschätzt – dieses Modell kennt die App nicht.",
            customModelPriceHint(ai, AiProviderId.OPENAI, "o9-preview", today),
        )
        assertEquals("Preis geschätzt", priceLine(ai, AiProviderId.OPENAI, "o9-preview", today))
        // Free providers never estimate.
        assertNull(customModelPriceHint(AiSettings(), AiProviderId.CUSTOM, "llama3.3", today))
        assertEquals("kostenlos", priceLine(AiSettings(), AiProviderId.CUSTOM, "llama3.3", today))
        AiProviderId.entries.forEach { assertTrue(customModelExample(it).isNotBlank()) }
    }

    @Test
    fun `prices per million tokens the german way`() {
        assertEquals("2${NBSP}$", perMillionUsd(2.0))
        assertEquals("0,10 $", plain(perMillionUsd(0.1)))
        assertEquals("0,075 $", plain(perMillionUsd(0.075)))
        assertEquals("3,75 $", plain(perMillionUsd(3.75)))
        assertEquals("0 $", plain(perMillionUsd(0.0)))
    }

    @Test
    fun `debug base url examples point at the mock server on the host`() {
        assertEquals("http://10.0.2.2:8787", debugBaseUrlExample(AiProviderId.ANTHROPIC))
        assertEquals("http://10.0.2.2:8787/v1beta", debugBaseUrlExample(AiProviderId.GEMINI))
        assertEquals("http://10.0.2.2:8787/v1", debugBaseUrlExample(AiProviderId.OPENAI))
        assertEquals("http://10.0.2.2:8787/v1", debugBaseUrlExample(AiProviderId.PERPLEXITY))
        assertEquals("http://10.0.2.2:8787/v1", debugBaseUrlExample(AiProviderId.DEEPSEEK))
    }


    @Test
    fun `debug base url summary covers every provider but the own server`() {
        assertEquals(8, debugBaseUrlProviders.size)
        assertFalse(AiProviderId.CUSTOM in debugBaseUrlProviders)
        assertEquals("Echte APIs (keine Umleitung)", debugBaseUrlSummary(AiSettings()))
        val gemini = AiSettings(
            provider = AiProviderId.GEMINI,
            providers = mapOf(AiProviderId.GEMINI to ProviderSettings(debugBaseUrl = "http://10.0.2.2:8787/v1beta")),
        )
        assertEquals("http://10.0.2.2:8787/v1beta", debugBaseUrlSummary(gemini))
        val withLive = gemini.with(AiProviderId.OPENAI, ProviderSettings(debugBaseUrl = "http://10.0.2.2:8787/v1"))
        assertEquals("http://10.0.2.2:8787/v1beta · 2 von 8 Anbietern umgeleitet", debugBaseUrlSummary(withLive))
        assertEquals("1 von 8 Anbietern umgeleitet", debugBaseUrlSummary(withLive.copy(provider = AiProviderId.MISTRAL).with(AiProviderId.GEMINI, ProviderSettings())))
    }
    @Test
    fun `estimated note only when some requests were guessed`() {
        assertNull(estimatedNote(0))
        assertTrue(estimatedNote(1)!!.contains("1 Anfrage"))
        assertTrue(estimatedNote(4)!!.contains("4 Anfragen"))
    }

    @Test
    fun `the tariff subtitles do not repeat the tariff name`() {
        GeminiTier.entries.forEach { tier ->
            assertFalse(geminiTierSubtitle(tier).startsWith(GeminiTiers.label(tier).substringBefore(" ")))
        }
        assertTrue(geminiTierSubtitle(GeminiTier.FREE).contains("ohne Websuche"))
        assertTrue(geminiTierSubtitle(GeminiTier.PAID).contains("Google-Suche"))
    }

    @Test
    fun `the android pill is a device hint, not a cross`() {
        assertTrue(capabilities(claude).last().device)
        assertFalse(capabilities(claude).first().device)
    }

    @Test
    fun `unsupported provider hint`() {
        assertEquals("Claude braucht Android 8 – wähl einen anderen Anbieter.", unsupportedProviderHint(claude))
    }

    @Test
    fun `an openrouter key test tells about credits and today's free requests`() {
        val openRouter = ProviderCatalog.OPENROUTER
        val free = com.cedrickgd.morgenbriefing.ai.OpenRouterKeyInfo(freeTier = true, freeRequestsLeft = 37, freeRequestsPerDay = 50)
        val state = savedKeyState(KeyCheckOutcome(AiKeyCheck.OK, openRouter = free))
        assertEquals(AiKeyState.SavedWithKeyInfo(free), state)
        assertTrue(state.isSaved)
        assertEquals(
            "✓ Der Key funktioniert und ist gespeichert – Gratis-Konto ohne gekauftes Guthaben. Heute noch 37 von 50 Gratis-Anfragen.",
            aiKeyMessage(state, openRouter),
        )
        val credits = com.cedrickgd.morgenbriefing.ai.OpenRouterKeyInfo(freeTier = false, freeRequestsLeft = 1000, freeRequestsPerDay = 1000)
        assertEquals(
            "✓ Der Key funktioniert und ist gespeichert – Konto mit Guthaben. Heute noch 1.000 von 1.000 Gratis-Anfragen.",
            aiKeyMessage(AiKeyState.SavedWithKeyInfo(credits), openRouter),
        )
        assertEquals(
            "✓ Der Key funktioniert und ist gespeichert – Gratis-Konto ohne gekauftes Guthaben. Heute keine Gratis-Anfragen mehr (50 am Tag) – morgen geht's weiter.",
            aiKeyMessage(AiKeyState.SavedWithKeyInfo(free.copy(freeRequestsLeft = 0)), openRouter),
        )
        assertEquals(
            "✓ Der Key funktioniert und ist gespeichert.",
            aiKeyMessage(AiKeyState.SavedWithKeyInfo(com.cedrickgd.morgenbriefing.ai.OpenRouterKeyInfo()), openRouter),
        )
        // "Mit Guthaben" without any credits bought.
        assertEquals(AiKeyState.SavedNoCredit, savedKeyState(KeyCheckOutcome(AiKeyCheck.OK_NO_CREDIT, openRouter = free)))
        assertEquals(
            "Gespeichert ✓ – aber auf deinem OpenRouter-Konto ist noch kein Guthaben. Kauf unter „Credits“ welches – oder nimm den Tarif „Kostenlos“.",
            aiKeyMessage(AiKeyState.SavedNoCredit, openRouter),
        )
    }

    @Test
    fun `the free tariff picks among openrouter's free models`() {
        val models = com.cedrickgd.morgenbriefing.ai.compat.OpenRouterFree.FALLBACK +
            listOf("a", "b", "c").map { com.cedrickgd.morgenbriefing.ai.compat.FreeModel("vendor/$it:free", "Vendor: Model $it (free)") }
        val fresh = openRouter(OpenRouterTier.FREE)
        val choices = freeModelChoices(fresh, models)
        assertEquals(models.map { it.id }, choices.map { it.id })
        // Gemma 4 31B runs by default and carries the badge; every row is free and selectable.
        assertEquals(listOf("google/gemma-4-31b-it:free"), choices.filter { it.selected }.map { it.id })
        assertEquals("Standard", choices.first().badge)
        assertEquals("Gemma 4 31B", choices.first().title)
        assertEquals("Googles offenes Modell · kostenlos", choices.first().subtitle)
        assertTrue(choices.all { it.enabled && it.subtitle.endsWith("· kostenlos") })
        // A stored free model is selected; one that left the list gives way to the default.
        val picked = fresh.withModel(AiProviderId.OPENROUTER, "vendor/c:free")
        assertEquals(listOf("vendor/c:free"), freeModelChoices(picked, models).filter { it.selected }.map { it.id })
        val gone = fresh.withModel(AiProviderId.OPENROUTER, "vendor/gone:free")
        assertEquals(listOf("google/gemma-4-31b-it:free"), freeModelChoices(gone, models).filter { it.selected }.map { it.id })

        // Five rows, plus the selected one further down; all when expanded.
        val pickedChoices = freeModelChoices(picked, models)
        assertEquals(FREE_MODELS_SHOWN + 1, visibleFreeModels(pickedChoices, expanded = false).size)
        assertEquals("vendor/c:free", visibleFreeModels(pickedChoices, expanded = false).last().id)
        assertEquals(FREE_MODELS_SHOWN, visibleFreeModels(choices, expanded = false).size)
        assertEquals(choices, visibleFreeModels(choices, expanded = true))
        assertEquals(choices.take(3), visibleFreeModels(choices.take(3), expanded = false))
    }

    @Test
    fun `the free model list says where it stands`() {
        val list = com.cedrickgd.morgenbriefing.ai.compat.FreeModelList(
            com.cedrickgd.morgenbriefing.ai.compat.OpenRouterFree.FALLBACK,
            com.cedrickgd.morgenbriefing.ai.compat.FreeModelSource.LIVE,
        )
        assertEquals("5 Gratis-Modelle von OpenRouter, täglich aktualisiert. Bis 50 Anfragen am Tag.", freeModelsNote(list, loading = false))
        assertEquals(
            "Offline – eine kleine Auswahl. Die ganze Liste lädt, sobald du online bist.",
            freeModelsNote(list.copy(source = com.cedrickgd.morgenbriefing.ai.compat.FreeModelSource.FALLBACK), loading = false),
        )
        assertEquals("Lade die Gratis-Modelle von OpenRouter …", freeModelsNote(null, loading = true))
        assertNull(freeModelsNote(null, loading = false))
        assertEquals(
            "Der Key gehört dir: Anfragen laufen über dein eigenes OpenRouter-Konto – kostenlos mit Gratis-Modellen oder mit Guthaben, wie du unten wählst.",
            aiKeyIntro(ProviderCatalog.OPENROUTER),
        )
    }

    @Test
    fun `a short key that is refused is asked about its copy only once`() {
        val refused = AiKeyState.Failed(AiKeyProblem.INVALID)
        val short = aiKeyFormatIssue("sk-ant-api03-short", claude)
        assertEquals(AiKeyFormatIssue.TOO_SHORT, short)
        val hint = aiKeyFormatHint(short!!, claude)
        val message = aiKeyMessage(refused, claude, short)!!
        assertEquals("Der Key wirkt zu kurz – hast du ihn vollständig kopiert?", hint)
        assertEquals("✗ Anthropic lehnt den Key ab.", message)
        val phrase = "hast du ihn vollständig kopiert?"
        assertEquals(1, listOf(hint, message).sumOf { it.split(phrase).size - 1 })
        // A key without a hint keeps the question; the own server and Perplexity keep their sentences.
        assertEquals("✗ Anthropic lehnt den Key ab – hast du ihn vollständig kopiert?", aiKeyMessage(refused, claude))
        assertEquals("✗ Dein Server lehnt den Key ab – stimmt er?", aiKeyMessage(refused, ProviderCatalog.CUSTOM, AiKeyFormatIssue.WHITESPACE))
        assertEquals(
            "✗ Perplexity lehnt den Key ab – er ist ungültig oder dein Guthaben ist leer.",
            aiKeyMessage(refused, ProviderCatalog.PERPLEXITY, AiKeyFormatIssue.WHITESPACE),
        )
    }
}
