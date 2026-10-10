package com.cedrickgd.morgenbriefing.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import com.cedrickgd.morgenbriefing.data.model.cleanApiKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsPrefsTest {

    @Test
    fun `key flags only count with a key`() {
        val prefs = preferencesOf(
            AiPrefKeys.keyEnc(AiProviderId.OPENAI) to "CT==",
            AiPrefKeys.untested(AiProviderId.OPENAI) to true,
            AiPrefKeys.untested(AiProviderId.MISTRAL) to true,
            AiPrefKeys.rejected(AiProviderId.MISTRAL) to true,
            AiPrefKeys.keyEnc(AiProviderId.XAI) to " ",
        )
        val ai = readAiSettings(prefs, debugOverrides = false)
        assertEquals(ProviderSettings(hasKey = true, keyUntested = true), ai.of(AiProviderId.OPENAI))
        assertEquals(ProviderSettings(), ai.of(AiProviderId.MISTRAL))
        assertFalse(ai.of(AiProviderId.XAI).hasKey)
        // One entry per provider.
        assertEquals(AiProviderId.entries.toSet(), ai.providers.keys)
    }

    @Test
    fun `unknown providers fall back to Anthropic, Live follows the chat unless set`() {
        val unknown = readAiSettings(preferencesOf(AiPrefKeys.PROVIDER to "skynet", AiPrefKeys.LIVE_PROVIDER to "nope"), false)
        assertEquals(AiProviderId.ANTHROPIC, unknown.provider)
        assertNull(unknown.liveProvider)
        assertEquals(AiProviderId.ANTHROPIC, unknown.effectiveLiveProvider)

        val split = readAiSettings(preferencesOf(AiPrefKeys.PROVIDER to "mistral", AiPrefKeys.LIVE_PROVIDER to "openai"), false)
        assertEquals(AiProviderId.MISTRAL, split.provider)
        assertEquals(AiProviderId.OPENAI, split.effectiveLiveProvider)
        assertEquals(AiProviderId.ANTHROPIC, readAiSettings(emptyPreferences(), false).provider)
    }

    @Test
    fun `the custom server is configured with an address, its key is optional`() {
        val noUrl = readAiSettings(preferencesOf(AiPrefKeys.PROVIDER to "custom"), false)
        assertFalse(noUrl.isConfigured(AiProviderId.CUSTOM))
        val withUrl = readAiSettings(preferencesOf(AiPrefKeys.CUSTOM_BASE_URL to "http://nas.fritz.box:11434/v1"), false)
        assertEquals("http://nas.fritz.box:11434/v1", withUrl.of(AiProviderId.CUSTOM).baseUrl)
        assertTrue(withUrl.isConfigured(AiProviderId.CUSTOM))
        // The address belongs to the custom server only.
        assertNull(withUrl.of(AiProviderId.OPENAI).baseUrl)
        assertTrue(withUrl.costsNothing(AiProviderId.CUSTOM))
    }

    @Test
    fun `workspace id and Gemini tier belong to their provider`() {
        val prefs = preferencesOf(
            AiPrefKeys.ANTHROPIC_WORKSPACE_ID to "  wrkspc_01ABC ",
            AiPrefKeys.GEMINI_TIER to "paid",
        )
        val ai = readAiSettings(prefs, false)
        assertEquals("wrkspc_01ABC", ai.of(AiProviderId.ANTHROPIC).workspaceId)
        assertNull(ai.of(AiProviderId.OPENAI).workspaceId)
        assertEquals(GeminiTier.PAID, ai.of(AiProviderId.GEMINI).geminiTier)
        assertFalse(ai.isGeminiFree(AiProviderId.GEMINI))
        assertFalse(ai.costsNothing(AiProviderId.GEMINI))
    }

    @Test
    fun `a new Gemini setup is on the free tier and costs nothing`() {
        val ai = readAiSettings(preferencesOf(AiPrefKeys.keyEnc(AiProviderId.GEMINI) to "CT=="), false)
        assertEquals(GeminiTier.FREE, ai.of(AiProviderId.GEMINI).geminiTier)
        assertTrue(ai.isGeminiFree(AiProviderId.GEMINI))
        assertTrue(ai.costsNothing(AiProviderId.GEMINI))
        // An unknown stored tier is the safe default, too.
        assertTrue(readAiSettings(preferencesOf(AiPrefKeys.GEMINI_TIER to "gold"), false).isGeminiFree(AiProviderId.GEMINI))
        // Other providers are never "free Gemini".
        assertFalse(ai.isGeminiFree(AiProviderId.OPENAI))
        assertFalse(ai.costsNothing(AiProviderId.ANTHROPIC))
    }

    @Test
    fun `a new OpenRouter setup is on the free tariff, its free model has a slot of its own`() {
        val id = AiProviderId.OPENROUTER
        val fresh = readAiSettings(preferencesOf(AiPrefKeys.keyEnc(id) to "CT=="), false)
        assertEquals(OpenRouterTier.FREE, fresh.of(id).openRouterTier)
        assertTrue(fresh.isOpenRouterFree(id))
        assertTrue(fresh.costsNothing(id))
        assertFalse(fresh.isOpenRouterFree(AiProviderId.GEMINI))
        val stored = readAiSettings(
            preferencesOf(
                AiPrefKeys.OPENROUTER_TIER to "credits",
                AiPrefKeys.OPENROUTER_FREE_MODEL to "  google/gemma-4-26b-a4b-it:free ",
                AiPrefKeys.model(id) to "openai/gpt-6-luna",
            ),
            false,
        )
        assertEquals(OpenRouterTier.CREDITS, stored.of(id).openRouterTier)
        assertFalse(stored.costsNothing(id))
        assertEquals("google/gemma-4-26b-a4b-it:free", stored.of(id).freeModel)
        assertEquals("openai/gpt-6-luna", stored.of(id).model)
        // An unknown tariff is the safe default; nobody else has a free model.
        assertTrue(readAiSettings(preferencesOf(AiPrefKeys.OPENROUTER_TIER to "gold"), false).isOpenRouterFree(id))
        assertNull(stored.of(AiProviderId.GEMINI).freeModel)

        // Writing keeps both models and leaves the tariff to its setter.
        val picked = fresh.withModel(id, "nvidia/nemotron-3-ultra-550b-a55b:free")
        val after = preferencesOf(AiPrefKeys.OPENROUTER_TIER to "free", AiPrefKeys.model(id) to "openai/gpt-6-luna")
            .toMutablePreferences().apply { writeAiSettings(picked.with(id, picked.of(id).copy(model = "openai/gpt-6-luna"))) }
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", after[AiPrefKeys.OPENROUTER_FREE_MODEL])
        assertEquals("openai/gpt-6-luna", after[AiPrefKeys.model(id)])
        assertEquals("free", after[AiPrefKeys.OPENROUTER_TIER])
        after.apply { writeAiSettings(picked.withModel(id, null)) }
        assertNull(after[AiPrefKeys.OPENROUTER_FREE_MODEL])
    }

    @Test
    fun `debug base URLs are only read when debug overrides are allowed`() {
        val prefs = preferencesOf(
            AiPrefKeys.debugBaseUrl(AiProviderId.GEMINI) to "http://10.0.2.2:8080/v1beta/",
            AiPrefKeys.debugBaseUrl(AiProviderId.OPENAI) to "not a url",
        )
        val debug = readAiSettings(prefs, debugOverrides = true)
        assertEquals("http://10.0.2.2:8080/v1beta", debug.of(AiProviderId.GEMINI).debugBaseUrl)
        assertNull(debug.of(AiProviderId.OPENAI).debugBaseUrl)
        val release = readAiSettings(prefs, debugOverrides = false)
        AiProviderId.entries.forEach { assertNull(release.of(it).debugBaseUrl) }
    }

    @Test
    fun `models are read trimmed and capped`() {
        val long = "m".repeat(300)
        val ai = readAiSettings(
            preferencesOf(AiPrefKeys.model(AiProviderId.OPENAI) to "  gpt-5.6-terra ", AiPrefKeys.model(AiProviderId.MISTRAL) to long),
            false,
        )
        assertEquals("gpt-5.6-terra", ai.of(AiProviderId.OPENAI).model)
        assertEquals(AiSettings.MAX_MODEL_LENGTH, ai.of(AiProviderId.MISTRAL).model!!.length)
        assertNull(ai.of(AiProviderId.GEMINI).model)
    }

    @Test
    fun `writing stores providers and models, keys and flags stay untouched`() {
        val before = preferencesOf(
            AiPrefKeys.keyEnc(AiProviderId.OPENAI) to "CT==",
            AiPrefKeys.untested(AiProviderId.OPENAI) to true,
            AiPrefKeys.model(AiProviderId.GEMINI) to "gemini-3.5-flash-lite",
            AiPrefKeys.model(AiProviderId.MISTRAL) to "mistral-large-2512",
            AiPrefKeys.LIVE_PROVIDER to "xai",
            AiPrefKeys.GEMINI_TIER to "paid",
        )
        val ai = AiSettings(provider = AiProviderId.OPENAI, liveProvider = null)
            .withModel(AiProviderId.OPENAI, "  gpt-6-luna ")
            .withModel(AiProviderId.GEMINI, null)
        val after = before.toMutablePreferences().apply { writeAiSettings(ai) }.toPreferences()

        assertEquals("openai", after[AiPrefKeys.PROVIDER])
        assertNull(after[AiPrefKeys.LIVE_PROVIDER])
        assertEquals("gpt-6-luna", after[AiPrefKeys.model(AiProviderId.OPENAI)])
        assertNull(after[AiPrefKeys.model(AiProviderId.GEMINI)])
        // Providers not in the map keep their model.
        assertEquals("mistral-large-2512", after[AiPrefKeys.model(AiProviderId.MISTRAL)])
        assertEquals("CT==", after[AiPrefKeys.keyEnc(AiProviderId.OPENAI)])
        assertEquals(true, after[AiPrefKeys.untested(AiProviderId.OPENAI)])
        assertEquals("paid", after[AiPrefKeys.GEMINI_TIER])

        val read = readAiSettings(after, false)
        assertEquals(AiProviderId.OPENAI, read.provider)
        assertEquals("gpt-6-luna", read.of(AiProviderId.OPENAI).model)

        val withLive = after.toMutablePreferences().apply { writeAiSettings(read.copy(liveProvider = AiProviderId.XAI)) }
        assertEquals("xai", withLive[AiPrefKeys.LIVE_PROVIDER])
    }

    @Test
    fun `a model fallback belongs to the stored key and goes with a new model choice`() {
        val gemini = AiProviderId.GEMINI
        val prefs = preferencesOf(
            AiPrefKeys.keyEnc(gemini) to "CT==",
            AiPrefKeys.fallbackFrom(gemini) to " gemini-3.8-flash ",
            AiPrefKeys.fallbackTo(gemini) to "gemini-3.7-flash",
            // Half a fallback, or one without a key, is none.
            AiPrefKeys.fallbackFrom(AiProviderId.OPENAI) to "gpt-6.1-sol",
            AiPrefKeys.fallbackFrom(AiProviderId.XAI) to "grok-4.3",
            AiPrefKeys.fallbackTo(AiProviderId.XAI) to "grok-4.7",
        )
        val read = readAiSettings(prefs, false)
        assertEquals(ModelFallback("gemini-3.8-flash", "gemini-3.7-flash"), read.of(gemini).modelFallback)
        assertNull(read.of(AiProviderId.OPENAI).modelFallback)
        assertNull(read.of(AiProviderId.XAI).modelFallback)

        // Writing the same model keeps it; another model drops it (the new choice is tried again).
        val same = prefs.toMutablePreferences().apply { writeAiSettings(read) }.toPreferences()
        assertEquals(ModelFallback("gemini-3.8-flash", "gemini-3.7-flash"), readAiSettings(same, false).of(gemini).modelFallback)
        val other = prefs.toMutablePreferences().apply { writeAiSettings(read.withModel(gemini, "gemini-3.5-flash-lite")) }.toPreferences()
        assertNull(readAiSettings(other, false).of(gemini).modelFallback)
        assertNull(other[AiPrefKeys.fallbackTo(gemini)])

        // The dedicated writer: set, and null or a "fallback" to the same model removes it.
        val written = emptyPreferences().toMutablePreferences().apply {
            writeModelFallback(gemini, ModelFallback("gemini-3.8-flash", "gemini-3.5-flash-lite"))
        }
        assertEquals("gemini-3.5-flash-lite", written[AiPrefKeys.fallbackTo(gemini)])
        written.writeModelFallback(gemini, ModelFallback("gemini-3.8-flash", "gemini-3.8-flash"))
        assertNull(written[AiPrefKeys.fallbackFrom(gemini)])
    }

    @Test
    fun `a pasted key loses spaces, quotes and invisible characters around it, nothing else`() {
        val aq = "AQ.Ab1-fake_test.key-0000"
        assertEquals(aq, cleanApiKey("  $aq \n"))
        assertEquals(aq, cleanApiKey("\"$aq\""))
        assertEquals(aq, cleanApiKey("„$aq“"))
        assertEquals(aq, cleanApiKey("'$aq'"))
        assertEquals(aq, cleanApiKey("`$aq`"))
        assertEquals(aq, cleanApiKey(" « \"$aq\" » "))
        assertEquals(aq, cleanApiKey("﻿$aq​"))
        assertEquals(aq, cleanApiKey("AQ.Ab1-‍fake_test.key­-0000"))
        assertEquals(aq, cleanApiKey(" $aq "))
        // Both Gemini shapes, any length: no format is imposed.
        assertEquals("AIza-test-invalid", cleanApiKey("AIza-test-invalid"))
        assertEquals("AQ.x", cleanApiKey("AQ.x"))
        // Inner spaces and umlauts stay (the dialog says so; such a key is never sent).
        assertEquals("AIza test", cleanApiKey(" AIza test "))
        assertEquals("AIza-ä", cleanApiKey("AIza-ä"))
        assertEquals("", cleanApiKey(null))
        assertEquals("", cleanApiKey(" \"\" "))
    }

    @Test
    fun `base URLs must be http or https addresses`() {
        assertEquals("http://10.0.2.2:8080/v1", normalizeBaseUrl(" http://10.0.2.2:8080/v1// "))
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl("https://api.openai.com/v1"))
        assertEquals("http://localhost:11434", normalizeBaseUrl("http://localhost:11434/"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("nas.fritz.box:11434"))
        assertNull(normalizeBaseUrl("ftp://nas.fritz.box"))
        assertNull(normalizeBaseUrl("http://"))
        assertNull(normalizeBaseUrl("http://nas .fritz.box"))
        assertNull(normalizeBaseUrl("http://nas.fritz.box/v1?key=abc"))
    }
}
