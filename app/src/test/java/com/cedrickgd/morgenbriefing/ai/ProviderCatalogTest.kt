package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.EngineKind
import com.cedrickgd.morgenbriefing.ai.provider.GeminiModels
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import com.cedrickgd.morgenbriefing.ai.provider.OpenRouterTiers
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

class ProviderCatalogTest {

    @Test
    fun `every provider is listed once, in picker order`() {
        assertEquals(AiProviderId.entries.toList(), ProviderCatalog.all.map { it.id })
        AiProviderId.entries.forEach { assertEquals(it, ProviderCatalog.spec(it).id) }
    }

    @Test
    fun `key guides and models are complete`() {
        ProviderCatalog.all.forEach { spec ->
            val what = spec.id.key
            assertTrue(what, spec.name.isNotBlank() && spec.brand.isNotBlank() && spec.tagline.isNotBlank())
            assertTrue(what, spec.keySteps.isNotEmpty() && spec.keySteps.all { it.isNotBlank() })
            assertTrue(what, spec.keyPlaceholder.isNotBlank())
            if (spec.id == AiProviderId.CUSTOM) {
                assertNull(spec.consoleUrl)
                assertEquals("", spec.defaultModel)
                assertTrue(spec.models.isEmpty())
            } else {
                assertTrue(what, spec.consoleUrl!!.startsWith("https://"))
                assertFalse(what, spec.consoleLabel.isNullOrBlank())
                // The default is a curated model and the only one with the badge.
                assertEquals(what, spec.defaultModel, spec.models.single { it.badge == "Standard" }.id)
                assertEquals(what, spec.models.size, spec.models.map { it.id }.distinct().size)
                assertTrue(what, spec.baseUrl == null || spec.baseUrl!!.startsWith("https://"))
            }
        }
    }

    @Test
    fun `only Claude needs the SDK and uses web fetch`() {
        ProviderCatalog.all.forEach { spec ->
            val claude = spec.id == AiProviderId.ANTHROPIC
            assertEquals(spec.id.key, claude, spec.needsSdk)
            assertEquals(spec.id.key, claude, spec.engine == EngineKind.CLAUDE_SDK)
            assertEquals(spec.id.key, if (claude) LiveToolNote.SEARCH_AND_FETCH else LiveToolNote.SEARCH_ONLY, spec.liveToolNote)
        }
    }

    @Test
    fun `Live runs where search may be stored, Gemini explains why not`() {
        val live = ProviderCatalog.all.filter { it.live == LiveSupport.YES }.map { it.id }
        assertEquals(
            listOf(AiProviderId.ANTHROPIC, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.PERPLEXITY, AiProviderId.OPENROUTER),
            live,
        )
        ProviderCatalog.all.forEach { spec ->
            assertEquals(spec.id.key, spec.live == LiveSupport.YES, spec.liveUnavailableText == null)
            // Live needs search; every Live provider has it in the chat too.
            if (spec.live == LiveSupport.YES) assertTrue(spec.chatWebSearch)
        }
        assertEquals(LiveSupport.NOT_ALLOWED, ProviderCatalog.GEMINI.live)
        assertTrue(ProviderCatalog.GEMINI.liveUnavailableText!!.contains("Googles Bedingungen"))
        assertEquals("Live braucht Websuche – mit Mistral nicht verfügbar.", ProviderCatalog.MISTRAL.liveUnavailableText)
    }

    @Test
    fun `Gemini accepts both key shapes and guides through AI Studio`() {
        val gemini = ProviderCatalog.GEMINI
        assertTrue(gemini.keyPrefixes.isEmpty())
        assertTrue(gemini.keyPlaceholder.contains("AIza") && gemini.keyPlaceholder.contains("AQ."))
        assertEquals("https://aistudio.google.com/apikey", gemini.consoleUrl)
        assertTrue(gemini.keySteps.any { it.contains("Get API key") && it.contains("Create API key") })
        assertEquals("aistudio.google.com → Get API key → Create API key", GeminiTiers.KEY_GUIDE)
        assertEquals("Im kostenlosen Tarif darf Google deine Anfragen zur Verbesserung seiner Produkte nutzen.", GeminiTiers.PRIVACY)
        assertEquals("Kostenlos (ohne Abrechnung)", GeminiTiers.label(GeminiTier.FREE))
        assertEquals("Mit Abrechnung (Paid Tier)", GeminiTiers.label(GeminiTier.PAID))
        assertTrue(GeminiTiers.SEARCH_ALLOWANCE.contains("5.000"))
        GeminiTier.entries.forEach { assertTrue(GeminiTiers.description(it).isNotBlank()) }
        // Only Pro has no free tier.
        assertEquals(listOf("gemini-3.1-pro-preview"), gemini.models.filter { it.requiresBilling }.map { it.id })
    }

    private fun gemini(tier: GeminiTier, model: String? = null) =
        AiSettings(provider = AiProviderId.GEMINI).with(AiProviderId.GEMINI, ProviderSettings(hasKey = true, geminiTier = tier, model = model))

    @Test
    fun `a free Gemini key neither searches nor runs Pro`() {
        val free = gemini(GeminiTier.FREE, model = "gemini-3.1-pro-preview")
        assertFalse(ProviderCatalog.chatWebSearch(free, AiProviderId.GEMINI))
        assertFalse(ProviderCatalog.isModelSelectable(free, AiProviderId.GEMINI, "gemini-3.1-pro-preview"))
        assertTrue(ProviderCatalog.isModelSelectable(free, AiProviderId.GEMINI, "gemini-3.5-flash-lite"))
        // Free text models are the user's call.
        assertTrue(ProviderCatalog.isModelSelectable(free, AiProviderId.GEMINI, "gemini-3.7-flash"))
        assertEquals("gemini-3.8-flash", ProviderCatalog.effectiveModel(free, AiProviderId.GEMINI))

        val paid = gemini(GeminiTier.PAID, model = "gemini-3.1-pro-preview")
        assertTrue(ProviderCatalog.chatWebSearch(paid, AiProviderId.GEMINI))
        assertEquals("gemini-3.1-pro-preview", ProviderCatalog.effectiveModel(paid, AiProviderId.GEMINI))
    }

    @Test
    fun `a typed Pro id is no way around the free tier`() {
        // Pro has no free tier: a free key would only collect 429s the quota text explains wrongly.
        listOf("gemini-3.1-pro-preview-customtools", "gemini-pro-latest", "models/gemini-2.5-pro", " Gemini-3.1-Pro-Preview ").forEach { id ->
            assertTrue(id, ProviderCatalog.requiresBilling(AiProviderId.GEMINI, id))
            assertFalse(id, ProviderCatalog.isModelSelectable(gemini(GeminiTier.FREE), AiProviderId.GEMINI, id))
            assertEquals(id, "gemini-3.8-flash", ProviderCatalog.effectiveModel(gemini(GeminiTier.FREE, model = id), AiProviderId.GEMINI))
            assertTrue(id, ProviderCatalog.isModelSelectable(gemini(GeminiTier.PAID), AiProviderId.GEMINI, id))
            assertEquals(id, id.trim(), ProviderCatalog.effectiveModel(gemini(GeminiTier.PAID, model = id), AiProviderId.GEMINI))
        }
        listOf("gemini-3.7-flash", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-3-flash-preview").forEach { id ->
            assertFalse(id, ProviderCatalog.requiresBilling(AiProviderId.GEMINI, id))
        }
        // Only Gemini has a free tier to protect; other providers' "pro" models are theirs.
        assertFalse(ProviderCatalog.requiresBilling(AiProviderId.DEEPSEEK, "deepseek-v4-pro"))
        assertFalse(ProviderCatalog.requiresBilling(AiProviderId.OPENROUTER, "google/gemini-3.1-pro-preview"))
    }

    @Test
    fun `models fall back to the catalog default`() {
        val ai = AiSettings()
        assertEquals("claude-sonnet-5-5", ProviderCatalog.effectiveModel(ai, AiProviderId.ANTHROPIC))
        assertEquals("gpt-6.1-sol", ProviderCatalog.effectiveModel(ai, AiProviderId.OPENAI))
        assertEquals("", ProviderCatalog.effectiveModel(ai, AiProviderId.CUSTOM))
        val chosen = ai.withModel(AiProviderId.ANTHROPIC, "claude-haiku-5-5").withModel(AiProviderId.CUSTOM, "llama4:8b")
        assertEquals("claude-haiku-5-5", ProviderCatalog.effectiveModel(chosen, AiProviderId.ANTHROPIC))
        assertEquals("llama4:8b", ProviderCatalog.effectiveModel(chosen, AiProviderId.CUSTOM))
        assertNotNull(ProviderCatalog.ANTHROPIC.model("claude-haiku-5-5"))
        assertTrue(ProviderCatalog.chatWebSearch(ai, AiProviderId.ANTHROPIC))
        assertFalse(ProviderCatalog.chatWebSearch(ai, AiProviderId.DEEPSEEK))
    }

    @Test
    fun `a model fallback of the key runs instead of the chosen model, as long as it is chosen`() {
        val gemini = AiProviderId.GEMINI
        val base = AiSettings().with(gemini, ProviderSettings(hasKey = true, modelFallback = ModelFallback("gemini-3.8-flash", "gemini-3.7-flash")))
        // The default 3.8 is chosen: 3.7 runs, the picker still marks 3.8.
        assertEquals("gemini-3.8-flash", ProviderCatalog.selectedModel(base, gemini))
        assertEquals("gemini-3.7-flash", ProviderCatalog.effectiveModel(base, gemini))
        assertEquals("gemini-3.8-flash" to "gemini-3.7-flash", ProviderCatalog.activeFallback(base, gemini))
        // Another model chosen: the fallback of 3.8 does not apply to it.
        val lite = base.withModel(gemini, "gemini-3.5-flash-lite")
        assertEquals("gemini-3.5-flash-lite", ProviderCatalog.effectiveModel(lite, gemini))
        assertNull(ProviderCatalog.activeFallback(lite, gemini))
        // A fallback to a model the free tier cannot run is never used.
        val pro = AiSettings().with(gemini, ProviderSettings(hasKey = true, modelFallback = ModelFallback("gemini-3.8-flash", "gemini-3.1-pro-preview")))
        assertEquals("gemini-3.8-flash", ProviderCatalog.effectiveModel(pro, gemini))
        assertEquals("gemini-3.1-pro-preview", ProviderCatalog.effectiveModel(pro.with(gemini, pro.of(gemini).copy(geminiTier = GeminiTier.PAID)), gemini))
    }

    @Test
    fun `the Gemini fallback chain goes down from the chosen model`() {
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), GeminiModels.CHAIN)
        assertEquals(listOf("gemini-3.7-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), GeminiModels.fallbacksAfter("gemini-3.8-flash", true))
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), GeminiModels.fallbacksAfter("models/gemini-3.7-flash", true))
        assertEquals(emptyList<String>(), GeminiModels.fallbacksAfter("gemini-3.1-flash-lite", false))
        // A model outside the chain (Pro, a typed id) falls back to the whole chain.
        assertEquals(GeminiModels.CHAIN, GeminiModels.fallbacksAfter("gemini-3.1-pro-preview", false))
        assertEquals(GeminiModels.CHAIN, GeminiModels.fallbacksAfter("gemini-2.5-flash", true))
        // Every model of the chain runs on the free tier and has a price.
        GeminiModels.CHAIN.forEach { id ->
            assertFalse(id, ProviderCatalog.requiresBilling(AiProviderId.GEMINI, id))
            assertFalse(id, AiPricing.ratesFor(AiProviderId.GEMINI, id, java.time.LocalDate.of(2026, 10, 9)).second)
        }
        assertEquals("Gemini 3.7 Flash", GeminiModels.label("gemini-3.7-flash"))
        assertEquals("Gemini 3.5 Flash-Lite", GeminiModels.label("gemini-3.5-flash-lite"))
        assertEquals("gemini-9", GeminiModels.label("gemini-9"))
        assertEquals(
            "Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.1 Flash-Lite.",
            GeminiModels.fallbackNote("gemini-3.8-flash", "gemini-3.1-flash-lite"),
        )
    }

    @Test
    fun `openrouter keeps one model per tariff and runs Gemma 4 for free by default`() {
        val id = AiProviderId.OPENROUTER
        val free = AiSettings().with(id, ProviderSettings(hasKey = true, model = "anthropic/claude-sonnet-5.5"))
        // A new setup is on the free tariff: the credits model is never sent there.
        assertEquals(OpenRouterTier.FREE, free.of(id).openRouterTier)
        assertEquals("google/gemma-4-31b-it:free", ProviderCatalog.selectedModel(free, id))
        assertEquals(OpenRouterTiers.DEFAULT_FREE_MODEL, ProviderCatalog.effectiveModel(free, id))
        // Picking a model on the free tariff stores the free model; the credits model stays.
        val picked = free.withModel(id, " nvidia/nemotron-3-ultra-550b-a55b:free ")
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", picked.of(id).freeModel)
        assertEquals("anthropic/claude-sonnet-5.5", picked.of(id).model)
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", ProviderCatalog.selectedModel(picked, id))
        // A stored id that is no free variant (a promotion that may start billing) never runs for free.
        val promo = free.with(id, free.of(id).copy(freeModel = "inclusionai/ling-3.1-flash"))
        assertEquals(OpenRouterTiers.DEFAULT_FREE_MODEL, ProviderCatalog.selectedModel(promo, id))
        assertEquals(OpenRouterTiers.DEFAULT_FREE_MODEL, ProviderCatalog.effectiveModel(promo, id))
        assertEquals("openrouter/free", ProviderCatalog.selectedModel(free.withModel(id, "openrouter/free"), id))
        // With credits the credits model runs again, and a pick there leaves the free one alone.
        val credits = picked.with(id, picked.of(id).copy(openRouterTier = OpenRouterTier.CREDITS))
        assertEquals("anthropic/claude-sonnet-5.5", ProviderCatalog.selectedModel(credits, id))
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", credits.withModel(id, "openai/gpt-6-luna").of(id).freeModel)
        assertEquals("google/gemini-3.8-flash", ProviderCatalog.selectedModel(credits.withModel(id, null), id))
    }

    @Test
    fun `openrouter on the free tariff has neither web search nor live`() {
        val id = AiProviderId.OPENROUTER
        val free = AiSettings(provider = id).with(id, ProviderSettings(hasKey = true))
        val credits = AiSettings(provider = id).with(id, ProviderSettings(hasKey = true, openRouterTier = OpenRouterTier.CREDITS))
        assertFalse(ProviderCatalog.chatWebSearch(free, id))
        assertTrue(ProviderCatalog.chatWebSearch(credits, id))
        assertEquals(LiveSupport.NO_WEB_SEARCH, ProviderCatalog.liveSupport(free, id))
        assertFalse(ProviderCatalog.canRunLive(free, id))
        assertEquals("Live braucht Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar.", ProviderCatalog.liveUnavailableText(free, id))
        assertEquals(LiveSupport.YES, ProviderCatalog.liveSupport(credits, id))
        assertTrue(ProviderCatalog.canRunLive(credits, id))
        assertNull(ProviderCatalog.liveUnavailableText(credits, id))
        // Other providers keep their own rules.
        assertEquals(LiveSupport.NOT_ALLOWED, ProviderCatalog.liveSupport(free, AiProviderId.GEMINI))
        assertTrue(ProviderCatalog.canRunLive(free, AiProviderId.OPENAI))
        // The key guide and the tariff wording.
        assertEquals("https://openrouter.ai/keys", ProviderCatalog.OPENROUTER.consoleUrl)
        assertEquals("openrouter.ai → Anmelden → Keys → Create Key (kein Zahlungsmittel nötig)", OpenRouterTiers.KEY_GUIDE)
        assertEquals("Kostenlos (Gratis-Modelle)", OpenRouterTiers.label(OpenRouterTier.FREE))
        assertEquals("Mit Guthaben", OpenRouterTiers.label(OpenRouterTier.CREDITS))
        assertTrue(OpenRouterTiers.looksFree("google/gemma-4-31b-it:free"))
        assertTrue(OpenRouterTiers.looksFree("openrouter/free"))
        assertFalse(OpenRouterTiers.looksFree("google/gemini-3.8-flash"))
        assertEquals("1.000", OpenRouterTiers.count(1000))
        assertEquals("37", OpenRouterTiers.count(37))
    }
}
