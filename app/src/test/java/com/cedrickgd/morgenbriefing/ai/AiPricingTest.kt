package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiPricingTest {
    private val today = LocalDate.of(2026, 10, 9)
    private fun assertUsd(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    private fun cost(provider: AiProviderId, model: String, usage: TokenUsage, on: LocalDate = today) =
        AiPricing.costUsd(provider, model, usage, on)

    private fun usd(provider: AiProviderId, model: String, usage: TokenUsage, on: LocalDate = today) = cost(provider, model, usage, on).usd

    private val million = 1_000_000L

    @Test
    fun `sonnet 5_5 prices per million tokens, cache hits at 0_10`() {
        val a = AiProviderId.ANTHROPIC
        assertUsd(2.0, usd(a, "claude-sonnet-5-5", TokenUsage(inputTokens = million)))
        assertUsd(10.0, usd(a, "claude-sonnet-5-5", TokenUsage(outputTokens = million)))
        assertUsd(2.5, usd(a, "claude-sonnet-5-5", TokenUsage(cacheWriteTokens = million)))
        // 0.05× input (platform.claude.com pricing); v1.2 charged 0.20 here.
        assertUsd(0.10, usd(a, "claude-sonnet-5-5", TokenUsage(cacheReadTokens = million)))
    }

    @Test
    fun `opus 5_5 and haiku 5_5 prices per million tokens`() {
        val a = AiProviderId.ANTHROPIC
        assertUsd(4.0, usd(a, "claude-opus-5-5", TokenUsage(inputTokens = million)))
        assertUsd(20.0, usd(a, "claude-opus-5-5", TokenUsage(outputTokens = million)))
        assertUsd(5.0, usd(a, "claude-opus-5-5", TokenUsage(cacheWriteTokens = million)))
        assertUsd(0.2, usd(a, "claude-opus-5-5", TokenUsage(cacheReadTokens = million)))
        assertUsd(0.01, usd(a, "claude-haiku-5-5", TokenUsage(inputTokens = 100_000)))
        assertUsd(0.05, usd(a, "claude-haiku-5-5", TokenUsage(outputTokens = 100_000)))
    }

    @Test
    fun `a typical chat answer with one web search`() {
        // 3,000 fresh input, 12,000 cache read, 1,500 cache write, 800 output, 1 search.
        val usage = TokenUsage(inputTokens = 3_000, outputTokens = 800, cacheWriteTokens = 1_500, cacheReadTokens = 12_000, webSearches = 1)
        val expected = (3_000 * 2.0 + 800 * 10.0 + 1_500 * 2.5 + 12_000 * 0.10) / 1_000_000 + 0.01
        assertUsd(expected, usd(AiProviderId.ANTHROPIC, "claude-sonnet-5-5", usage))
        // Web fetches cost nothing extra.
        assertUsd(expected, usd(AiProviderId.ANTHROPIC, "claude-sonnet-5-5", usage.copy(webFetches = 5)))
    }

    @Test
    fun `every curated model has its own price`() {
        ProviderCatalog.all.filter { it.id != AiProviderId.CUSTOM }.forEach { spec ->
            spec.models.forEach { model ->
                val (_, estimated) = AiPricing.ratesFor(spec.id, model.id, today)
                assertFalse("${spec.id.key}/${model.id}", estimated)
            }
        }
    }

    @Test
    fun `the longest prefix wins`() {
        val a = AiProviderId.ANTHROPIC
        assertEquals(AiPricing.SONNET_5_5, AiPricing.ratesFor(a, "claude-sonnet-5-5-20260901", today).first)
        assertEquals(AiPricing.SONNET_5, AiPricing.ratesFor(a, "claude-sonnet-5", today).first)
        assertEquals(AiPricing.OPUS_5, AiPricing.ratesFor(a, "claude-opus-5", today).first)
        val g = AiProviderId.GEMINI
        assertUsd(0.30, usd(g, "gemini-3.5-flash-lite", TokenUsage(inputTokens = million)))
        assertUsd(1.50, usd(g, "gemini-3.5-flash", TokenUsage(inputTokens = million)))
        assertUsd(0.50, usd(g, "gemini-3-flash-preview", TokenUsage(inputTokens = million)))
        val o = AiProviderId.OPENAI
        assertUsd(0.2, usd(o, "gpt-5.6-terra", TokenUsage(inputTokens = 100_000)))
        assertUsd(0.4, usd(o, "gpt-5.6", TokenUsage(inputTokens = 100_000)))
        // Ids are matched case-insensitively and trimmed.
        assertUsd(2.0, usd(a, " Claude-Sonnet-5-5 ", TokenUsage(inputTokens = million)))
        // Vendor prefixes count: OpenRouter writes Claude with dots, Perplexity with dashes.
        assertUsd(0.10, usd(AiProviderId.OPENROUTER, "anthropic/claude-sonnet-5.5", TokenUsage(cacheReadTokens = million)))
        assertUsd(0.20, usd(AiProviderId.PERPLEXITY, "anthropic/claude-sonnet-5-5", TokenUsage(cacheReadTokens = million)))
    }

    @Test
    fun `gemini flash doubles on 2027-01-01`() {
        val g = AiProviderId.GEMINI
        val usage = TokenUsage(inputTokens = million, outputTokens = million, cacheReadTokens = million)
        assertUsd(0.75 + 3.75 + 0.075, usd(g, "gemini-3.8-flash", usage, LocalDate.of(2026, 12, 31)))
        assertUsd(1.50 + 7.50 + 0.15, usd(g, "gemini-3.8-flash", usage, LocalDate.of(2027, 1, 1)))
        assertUsd(1.50, usd(g, "gemini-3.6-flash", TokenUsage(inputTokens = million), LocalDate.of(2027, 3, 1)))
        // Not every model changes.
        assertUsd(0.30, usd(g, "gemini-3.5-flash-lite", TokenUsage(inputTokens = million), LocalDate.of(2027, 3, 1)))
        // 14 $ per 1,000 search queries.
        assertUsd(0.028, usd(g, "gemini-3.8-flash", TokenUsage(webSearches = 2)))
    }

    @Test
    fun `long context switches the whole request to the higher rates`() {
        val o = AiProviderId.OPENAI
        // OpenAI above 272k: input ×2, output ×1.5 — counted with cache reads.
        assertUsd(272_000 * 2.0 / million, usd(o, "gpt-6.1-sol", TokenUsage(inputTokens = 272_000)))
        assertUsd(
            (200_000 * 4.0 + 100_000 * 0.20 + 10_000 * 15.0) / million,
            usd(o, "gpt-6.1-sol", TokenUsage(inputTokens = 200_000, cacheReadTokens = 100_000, outputTokens = 10_000)),
        )
        // Haiku above 100k.
        val a = AiProviderId.ANTHROPIC
        assertUsd(100_000 * 0.10 / million, usd(a, "claude-haiku-5-5", TokenUsage(inputTokens = 100_000)))
        assertUsd(100_001 * 0.50 / million, usd(a, "claude-haiku-5-5", TokenUsage(inputTokens = 100_001)))
        // Gemini Pro above 200k.
        val g = AiProviderId.GEMINI
        assertUsd(200_000 * 2.0 / million, usd(g, "gemini-3.1-pro-preview", TokenUsage(inputTokens = 200_000)))
        assertUsd(
            (250_000 * 4.0 + 1_000 * 18.0) / million,
            usd(g, "gemini-3.1-pro-preview", TokenUsage(inputTokens = 250_000, outputTokens = 1_000)),
        )
        // xAI from 200k (inclusive): everything ×2.
        val x = AiProviderId.XAI
        assertUsd(199_999 * 1.25 / million, usd(x, "grok-4.3", TokenUsage(inputTokens = 199_999)))
        assertUsd((200_000 * 2.5 + 1_000 * 5.0) / million, usd(x, "grok-4.3", TokenUsage(inputTokens = 200_000, outputTokens = 1_000)))
        // Searches keep their price.
        assertUsd(300_000 * 2.0 * 2 / million + 0.01, usd(o, "gpt-6.1-sol", TokenUsage(inputTokens = 300_000, webSearches = 1)))
    }

    @Test
    fun `a reported cost wins, the own server is free`() {
        val reported = cost(AiProviderId.OPENROUTER, "unknown/model", TokenUsage(inputTokens = million, webSearches = 3, reportedCostUsd = 0.0421))
        assertUsd(0.0421, reported.usd)
        assertFalse(reported.estimated)
        // A free Gemini key reports 0 $.
        assertUsd(0.0, usd(AiProviderId.GEMINI, "gemini-3.8-flash", TokenUsage(inputTokens = million, webSearches = 4, reportedCostUsd = 0.0)))
        val custom = cost(AiProviderId.CUSTOM, "llama4:8b", TokenUsage(inputTokens = million, outputTokens = million))
        assertUsd(0.0, custom.usd)
        assertFalse(custom.estimated)
    }

    @Test
    fun `unknown models use conservative fallback rates and are estimated`() {
        val claude = cost(AiProviderId.ANTHROPIC, "claude-something-new", TokenUsage(outputTokens = million))
        assertUsd(25.0, claude.usd)
        assertTrue(claude.estimated)
        val openRouter = cost(AiProviderId.OPENROUTER, "meta/llama-9", TokenUsage(inputTokens = million, webSearches = 1))
        assertUsd(10.0 + 0.014, openRouter.usd)
        assertTrue(openRouter.estimated)
        assertEquals(AiPricing.GLOBAL_FALLBACK, AiPricing.ratesFor(AiProviderId.PERPLEXITY, "openai/gpt-9", today).first)
        assertTrue(cost(AiProviderId.GEMINI, "gemini-4-ultra", TokenUsage(inputTokens = million)).estimated)
        // The fallback is never cheaper than a curated model of the same provider.
        ProviderCatalog.all.filter { it.id != AiProviderId.CUSTOM }.forEach { spec ->
            val fallback = AiPricing.ratesFor(spec.id, "zz-unknown", today).first
            spec.models.forEach { model ->
                val rates = AiPricing.ratesFor(spec.id, model.id, today).first
                assertTrue("${spec.id.key}/${model.id}", fallback.input >= rates.input && fallback.output >= rates.output)
            }
        }
    }

    @Test
    fun `reported costs add up, null stays null`() {
        assertEquals(null, (TokenUsage(inputTokens = 1) + TokenUsage(outputTokens = 1)).reportedCostUsd)
        assertUsd(0.3, (TokenUsage(reportedCostUsd = 0.1) + TokenUsage(reportedCostUsd = 0.2)).reportedCostUsd!!)
        assertUsd(0.1, (TokenUsage(reportedCostUsd = 0.1) + TokenUsage(outputTokens = 5)).reportedCostUsd!!)
        assertTrue(TokenUsage(reportedCostUsd = 0.0).isEmpty)
        assertFalse(TokenUsage(reportedCostUsd = 0.01).isEmpty)
    }
}
