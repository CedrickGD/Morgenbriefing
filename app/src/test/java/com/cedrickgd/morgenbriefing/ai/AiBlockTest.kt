package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import com.cedrickgd.morgenbriefing.data.withAiKey
import com.cedrickgd.morgenbriefing.data.withProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBlockTest {
    private val spent = UsageSnapshot(month = "2026-10", spentUsd = 6.0, limitUsd = 5.0)
    private val fine = UsageSnapshot(month = "2026-10", spentUsd = 1.0, limitUsd = 5.0)

    private fun ai(id: AiProviderId) = AppSettings().withAiKey(id).ai

    /** OpenRouter with a key on [tier]. */
    private fun openRouter(tier: OpenRouterTier) =
        AppSettings().withProvider(AiProviderId.OPENROUTER) { it.copy(hasKey = true, openRouterTier = tier) }.ai

    @Test
    fun `checks come in order - device, Live, key, limit`() {
        val claude = ai(AiProviderId.ANTHROPIC)
        assertEquals(AiBlock.UNSUPPORTED_DEVICE, aiBlock(AiSettings(), AiPurpose.CHAT, sdkAvailable = false, usage = spent))
        assertEquals(AiBlock.NO_KEY, aiBlock(AiSettings(), AiPurpose.CHAT, true, spent))
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(claude, AiPurpose.CHAT, true, spent))
        assertNull(aiBlock(claude, AiPurpose.CHAT, true, fine))
        // Limit 0 = no cap.
        assertNull(aiBlock(claude, AiPurpose.CHAT, true, spent.copy(limitUsd = 0.0)))
        // No backend in this build: like an unsupported device.
        assertEquals(AiBlock.UNSUPPORTED_DEVICE, aiBlock(claude, AiPurpose.CHAT, true, fine, hasBackend = false))
        val gemini = ai(AiProviderId.GEMINI)
        assertEquals(AiBlock.LIVE_UNAVAILABLE, aiBlock(gemini.copy(providers = emptyMap()), AiPurpose.LIVE, true, spent))
    }

    @Test
    fun `only Claude needs Android 8`() {
        AiProviderId.entries.forEach { id ->
            val ai = AppSettings().withProvider(id) { it.copy(hasKey = true, baseUrl = "http://nas.local:11434/v1") }.ai
            val block = aiBlock(ai, AiPurpose.CHAT, sdkAvailable = false, usage = fine)
            assertEquals(id.key, if (id == AiProviderId.ANTHROPIC) AiBlock.UNSUPPORTED_DEVICE else null, block)
        }
        // Unit tests run with Build.VERSION.SDK_INT = 0, like a pre-Oreo device.
        assertFalse(AiSupport.sdkAvailable)
        assertFalse(AiSupport.supports(AiProviderId.ANTHROPIC))
        assertTrue(AiSupport.supports(AiProviderId.GEMINI))
    }

    @Test
    fun `Live needs a provider whose search results may be stored`() {
        listOf(AiProviderId.GEMINI, AiProviderId.MISTRAL, AiProviderId.DEEPSEEK, AiProviderId.CUSTOM).forEach { id ->
            val ai = AppSettings().withProvider(id) { it.copy(hasKey = true, baseUrl = "http://nas.local:11434/v1") }.ai
            assertEquals(id.key, AiBlock.LIVE_UNAVAILABLE, aiBlock(ai, AiPurpose.LIVE, true, fine))
            assertNull(id.key, aiBlock(ai, AiPurpose.CHAT, true, fine))
        }
        listOf(AiProviderId.ANTHROPIC, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.PERPLEXITY).forEach { id ->
            assertNull(id.key, aiBlock(ai(id), AiPurpose.LIVE, true, fine))
        }
        // OpenRouter researches with credits; its free tariff has no web search.
        assertNull(aiBlock(openRouter(OpenRouterTier.CREDITS), AiPurpose.LIVE, true, fine))
        assertEquals(AiBlock.LIVE_UNAVAILABLE, aiBlock(openRouter(OpenRouterTier.FREE), AiPurpose.LIVE, true, fine))
        assertNull(aiBlock(openRouter(OpenRouterTier.FREE), AiPurpose.CHAT, true, fine))
    }

    @Test
    fun `openrouter on the free tariff chats without search, costs and limit`() {
        val free = openRouter(OpenRouterTier.FREE)
        assertTrue(free.isOpenRouterFree(AiProviderId.OPENROUTER))
        assertTrue(free.isFreeTier(AiProviderId.OPENROUTER))
        assertTrue(free.costsNothing(AiProviderId.OPENROUTER))
        // The monthly limit never stops the free tariff, but it stops credits.
        assertNull(aiBlock(free, AiPurpose.CHAT, true, spent))
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(openRouter(OpenRouterTier.CREDITS), AiPurpose.CHAT, true, spent))
        val status = aiStatus(free, AiPurpose.CHAT, true, fine)
        assertFalse(status.webSearch)
        assertTrue(status.freeTier)
        assertEquals("Ohne Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar", AiTexts.chatNote(status.spec, status.webSearch, status.freeTier))
        val credits = aiStatus(openRouter(OpenRouterTier.CREDITS), AiPurpose.CHAT, true, fine)
        assertTrue(credits.webSearch)
        assertFalse(credits.freeTier)
        assertNull(AiTexts.chatNote(credits.spec, credits.webSearch, credits.freeTier))
        // Live says why in the existing words and offers "Live-Anbieter wählen".
        val live = aiStatus(free, AiPurpose.LIVE, true, fine)
        assertEquals(AiBlock.LIVE_UNAVAILABLE, live.block)
        assertEquals("Live braucht Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar.", AiTexts.blockBody(AiBlock.LIVE_UNAVAILABLE, live.spec))
        assertEquals("Live-Anbieter wählen", AiTexts.blockAction(AiBlock.LIVE_UNAVAILABLE, live.spec))
    }

    @Test
    fun `a separate Live provider is checked on its own`() {
        // Chat on Mistral, Live on OpenAI without a key yet.
        val split = ai(AiProviderId.MISTRAL).copy(liveProvider = AiProviderId.OPENAI)
        assertNull(aiBlock(split, AiPurpose.CHAT, true, fine))
        assertEquals(AiBlock.NO_KEY, aiBlock(split, AiPurpose.LIVE, true, fine))
        val ready = split.with(AiProviderId.OPENAI, ProviderSettings(hasKey = true))
        assertNull(aiBlock(ready, AiPurpose.LIVE, true, fine))
        assertEquals(AiProviderId.OPENAI, ready.providerFor(AiPurpose.LIVE))
        assertEquals(AiProviderId.MISTRAL, ready.providerFor(AiPurpose.CHAT))
    }

    @Test
    fun `the own server needs an address, not a key`() {
        val noAddress = AppSettings().withProvider(AiProviderId.CUSTOM).ai
        assertEquals(AiBlock.NO_KEY, aiBlock(noAddress, AiPurpose.CHAT, true, fine))
        val withAddress = AppSettings().withProvider(AiProviderId.CUSTOM) { it.copy(baseUrl = "http://nas.fritz.box:11434/v1") }.ai
        assertNull(aiBlock(withAddress, AiPurpose.CHAT, true, fine))
    }

    @Test
    fun `free providers are never stopped by the monthly limit`() {
        val freeGemini = ai(AiProviderId.GEMINI)
        assertNull(aiBlock(freeGemini, AiPurpose.CHAT, true, spent))
        val paidGemini = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }.ai
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(paidGemini, AiPurpose.CHAT, true, spent))
        val server = AppSettings().withProvider(AiProviderId.CUSTOM) { it.copy(baseUrl = "http://nas.local:11434/v1") }.ai
        assertNull(aiBlock(server, AiPurpose.CHAT, true, spent))
    }

    @Test
    fun `status tells whether the chat searches the web`() {
        val free = aiStatus(ai(AiProviderId.GEMINI), AiPurpose.CHAT, true, fine)
        assertEquals(ProviderCatalog.GEMINI, free.spec)
        assertFalse(free.webSearch)
        assertTrue(free.freeTier)
        assertEquals("Ohne Websuche – mit dem kostenlosen Gemini-Tarif nicht verfügbar", AiTexts.chatNote(free.spec, free.webSearch, free.freeTier))
        val claude = aiStatus(ai(AiProviderId.ANTHROPIC), AiPurpose.CHAT, true, fine)
        assertTrue(claude.webSearch)
        assertNull(AiTexts.chatNote(claude.spec, claude.webSearch, claude.freeTier))
        val deepSeek = aiStatus(ai(AiProviderId.DEEPSEEK), AiPurpose.CHAT, true, fine)
        assertEquals("Ohne Websuche – mit DeepSeek nicht verfügbar", AiTexts.chatNote(deepSeek.spec, deepSeek.webSearch, deepSeek.freeTier))
    }

    @Test
    fun `the provider sink prices free requests at 0 and skips the limit`() = runBlocking<Unit> {
        val store = RecordingUsage(limitReached = true)
        val confirmed = mutableListOf<AiProviderId>()
        val free = ProviderUsageSink(store, free = true) { confirmed += it }
        free.beforeRequest()
        free.record(AiProviderId.GEMINI, "gemini-3.8-flash", TokenUsage(inputTokens = 100, webSearches = 1))
        assertEquals(0.0, store.records.single().second.reportedCostUsd!!, 0.0)
        assertEquals(1L, store.records.single().second.webSearches)
        assertEquals(listOf(AiProviderId.GEMINI), confirmed)

        val paid = ProviderUsageSink(store, free = false)
        try {
            paid.beforeRequest()
            org.junit.Assert.fail("expected AiBlockedException")
        } catch (e: AiBlockedException) {
            assertEquals(AiBlock.LIMIT_REACHED, e.block)
        }
        paid.record(AiProviderId.OPENAI, "gpt-6.1-sol", TokenUsage(inputTokens = 100))
        assertNull(store.records.last().second.reportedCostUsd)
    }

    @Test
    fun `a charge OpenRouter reports on the free tariff stays visible`() = runBlocking<Unit> {
        val store = RecordingUsage()
        val free = ProviderUsageSink(store, free = true)
        free.record(AiProviderId.OPENROUTER, "google/gemma-4-31b-it:free", TokenUsage(inputTokens = 100, reportedCostUsd = 0.0))
        assertEquals(0.0, store.records.last().second.reportedCostUsd!!, 0.0)
        // A model whose free promotion ended billed the credits after all.
        free.record(AiProviderId.OPENROUTER, "inclusionai/ling-3.1-flash", TokenUsage(inputTokens = 100, reportedCostUsd = 0.0042))
        assertEquals(0.0042, store.records.last().second.reportedCostUsd!!, 1e-12)
        // The own server's "cost" is no bill.
        free.record(AiProviderId.CUSTOM, "llama3.3", TokenUsage(inputTokens = 100, reportedCostUsd = 0.5))
        assertEquals(0.0, store.records.last().second.reportedCostUsd!!, 0.0)
    }

    @Test
    fun `a key test sets up the candidate workspace id and server address`() {
        val claude = AppSettings().withProvider(AiProviderId.ANTHROPIC) { it.copy(hasKey = true, workspaceId = "wrkspc_alt") }.ai
        val anthropic = AiProviderId.ANTHROPIC
        // Null keeps what is stored, "" clears it, anything else is trimmed.
        assertEquals(claude, keyTestSettings(claude, anthropic, KeyExtras()))
        assertEquals("wrkspc_alt", keyTestSettings(claude, anthropic, KeyExtras(workspaceId = " wrkspc_alt ")).of(anthropic).workspaceId)
        assertNull(keyTestSettings(claude, anthropic, KeyExtras(workspaceId = "")).of(anthropic).workspaceId)
        val server = AppSettings().withProvider(AiProviderId.CUSTOM) { it.copy(baseUrl = "http://nas.local:11434/v1") }.ai
        assertEquals("http://nas.local:8080/v1", keyTestSettings(server, AiProviderId.CUSTOM, KeyExtras(baseUrl = "http://nas.local:8080/v1 ")).of(AiProviderId.CUSTOM).baseUrl)
    }

    @Test
    fun `a key test refusal marks the stored key only for the stored setup`() {
        val gemini = AiProviderId.GEMINI
        val stored = AppSettings().withProvider(gemini) { it.copy(hasKey = true, keyUntested = true) }.ai
        val same = keyTestSettings(stored, gemini, KeyExtras())
        // An outright refusal of the stored setup: marked (the key store then checks it is the stored key).
        assertTrue(keyTestRejectsStoredKey(AiKeyCheck.INVALID, stored, same, gemini))
        // A Gemini key Google does not take, or blocked as leaked, is refused as well (GE §12.4).
        assertTrue(keyTestRejectsStoredKey(AiKeyCheck.AUTH_KEY_REJECTED, stored, same, gemini))
        assertTrue(keyTestRejectsStoredKey(AiKeyCheck.KEY_LEAKED, stored, same, gemini))
        // Anything but a refusal of the key leaves it as it is — a blocked API or billing is the project's.
        val refusals = setOf(AiKeyCheck.INVALID, AiKeyCheck.AUTH_KEY_REJECTED, AiKeyCheck.KEY_LEAKED)
        AiKeyCheck.entries.filter { it !in refusals }.forEach {
            assertFalse(it.name, keyTestRejectsStoredKey(it, stored, same, gemini))
        }
        // No key stored: nothing to mark.
        val none = AppSettings().ai
        assertFalse(keyTestRejectsStoredKey(AiKeyCheck.INVALID, none, keyTestSettings(none, gemini, KeyExtras()), gemini))

        // Another workspace id or server address may be what failed: the stored key stays unmarked.
        val anthropic = AiProviderId.ANTHROPIC
        val claude = AppSettings().withProvider(anthropic) { it.copy(hasKey = true, workspaceId = "wrkspc_alt") }.ai
        assertTrue(keyTestRejectsStoredKey(AiKeyCheck.INVALID, claude, keyTestSettings(claude, anthropic, KeyExtras(workspaceId = "wrkspc_alt")), anthropic))
        assertFalse(keyTestRejectsStoredKey(AiKeyCheck.INVALID, claude, keyTestSettings(claude, anthropic, KeyExtras(workspaceId = "wrkspc_neu")), anthropic))
        assertFalse(keyTestRejectsStoredKey(AiKeyCheck.INVALID, claude, keyTestSettings(claude, anthropic, KeyExtras(workspaceId = "")), anthropic))
        val custom = AiProviderId.CUSTOM
        val server = AppSettings().withProvider(custom) { it.copy(hasKey = true, baseUrl = "http://nas.local:11434/v1") }.ai
        assertTrue(keyTestRejectsStoredKey(AiKeyCheck.INVALID, server, keyTestSettings(server, custom, KeyExtras(baseUrl = "http://nas.local:11434/v1")), custom))
        assertFalse(keyTestRejectsStoredKey(AiKeyCheck.INVALID, server, keyTestSettings(server, custom, KeyExtras(baseUrl = "http://pi.local:11434/v1")), custom))
    }

    @Test
    fun `a key test runs for the openrouter tariff the dialog is about to store`() {
        val id = AiProviderId.OPENROUTER
        val stored = openRouter(OpenRouterTier.CREDITS)
        assertEquals(OpenRouterTier.FREE, keyTestSettings(stored, id, KeyExtras(openRouterTier = OpenRouterTier.FREE)).of(id).openRouterTier)
        assertEquals(OpenRouterTier.CREDITS, keyTestSettings(stored, id, KeyExtras()).of(id).openRouterTier)
        // Other providers ignore it.
        val gemini = ai(AiProviderId.GEMINI)
        assertEquals(gemini, keyTestSettings(gemini, AiProviderId.GEMINI, KeyExtras(openRouterTier = OpenRouterTier.CREDITS)))
    }
}
