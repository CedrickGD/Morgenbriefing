package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.EngineKind
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
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
}
