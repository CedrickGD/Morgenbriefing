package com.cedrickgd.morgenbriefing.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPricingTest {
    private fun assertUsd(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun `sonnet 5_5 prices per million tokens`() {
        assertUsd(2.0, AiPricing.costUsd("claude-sonnet-5-5", TokenUsage(inputTokens = 1_000_000)))
        assertUsd(10.0, AiPricing.costUsd("claude-sonnet-5-5", TokenUsage(outputTokens = 1_000_000)))
        assertUsd(2.5, AiPricing.costUsd("claude-sonnet-5-5", TokenUsage(cacheWriteTokens = 1_000_000)))
        assertUsd(0.2, AiPricing.costUsd("claude-sonnet-5-5", TokenUsage(cacheReadTokens = 1_000_000)))
    }

    @Test
    fun `opus 5_5 prices per million tokens`() {
        assertUsd(4.0, AiPricing.costUsd("claude-opus-5-5", TokenUsage(inputTokens = 1_000_000)))
        assertUsd(20.0, AiPricing.costUsd("claude-opus-5-5", TokenUsage(outputTokens = 1_000_000)))
        assertUsd(5.0, AiPricing.costUsd("claude-opus-5-5", TokenUsage(cacheWriteTokens = 1_000_000)))
        assertUsd(0.2, AiPricing.costUsd("claude-opus-5-5", TokenUsage(cacheReadTokens = 1_000_000)))
    }

    @Test
    fun `a typical chat answer with one web search`() {
        // 3,000 fresh input, 12,000 cache read, 1,500 cache write, 800 output, 1 search.
        val usage = TokenUsage(inputTokens = 3_000, outputTokens = 800, cacheWriteTokens = 1_500, cacheReadTokens = 12_000, webSearches = 1)
        val expected = (3_000 * 2.0 + 800 * 10.0 + 1_500 * 2.5 + 12_000 * 0.2) / 1_000_000 + 0.01
        assertUsd(expected, AiPricing.costUsd("claude-sonnet-5-5", usage))
        // Web fetches cost nothing extra.
        assertUsd(expected, AiPricing.costUsd("claude-sonnet-5-5", usage.copy(webFetches = 5)))
    }

    @Test
    fun `dated ids, fallback models and unknown models`() {
        assertEquals(AiPricing.SONNET_5_5, AiPricing.ratesFor("claude-sonnet-5-5-20260901"))
        assertEquals(AiPricing.SONNET_5, AiPricing.ratesFor("claude-sonnet-5"))
        assertEquals(AiPricing.OPUS_5_5, AiPricing.ratesFor("claude-opus-5-5"))
        assertEquals(AiPricing.OPUS_5, AiPricing.ratesFor("claude-opus-5"))
        // Unknown → the most expensive known rates, never too cheap against the limit.
        assertEquals(AiPricing.OPUS_5, AiPricing.ratesFor("claude-something-new"))
        assertUsd(10.0 / 1_000, AiPricing.WEB_SEARCH_USD)
    }
}
