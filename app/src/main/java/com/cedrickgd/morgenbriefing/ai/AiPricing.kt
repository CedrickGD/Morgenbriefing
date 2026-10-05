package com.cedrickgd.morgenbriefing.ai

/**
 * Token counts of one billed API request (one HTTP call; a `pause_turn` continuation or a tool
 * round trip is a request of its own). Cache writes are 5-minute writes (the app never asks for
 * the 1-hour TTL).
 */
internal data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val webSearches: Long = 0,
    val webFetches: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        webSearches = webSearches + other.webSearches,
        webFetches = webFetches + other.webFetches,
    )

    val isEmpty: Boolean
        get() = inputTokens == 0L && outputTokens == 0L && cacheWriteTokens == 0L &&
            cacheReadTokens == 0L && webSearches == 0L && webFetches == 0L
}

/**
 * The one place with Claude API prices (docs/AI.md "Modelle & Kosten"). Everything the app
 * spends is computed from a response's `usage` with this table and summed per month.
 *
 * Source: Anthropic API pricing as published in the Claude API reference (models,
 * migration guide: Claude Sonnet 5.5 / Claude Opus 5.5 sections), checked on [AS_OF].
 * Web search costs 10 $ per 1,000 searches (`usage.server_tool_use.web_search_requests`);
 * web fetch has no surcharge beyond the tokens of the fetched page.
 */
internal object AiPricing {
    const val AS_OF = "2026-10-05"
    const val SOURCE = "https://platform.claude.com/docs/en/about-claude/pricing"

    /** US dollars per million tokens. */
    data class Rates(
        val input: Double,
        val output: Double,
        val cacheWrite5m: Double,
        val cacheRead: Double,
    )

    /** `claude-sonnet-5-5`: 2 $ in, 10 $ out, 2.50 $ cache write (5 min), 0.20 $ cache read. */
    val SONNET_5_5 = Rates(input = 2.0, output = 10.0, cacheWrite5m = 2.50, cacheRead = 0.20)

    /** `claude-opus-5-5`: 4 $ in, 20 $ out, 5 $ cache write (5 min), 0.20 $ cache read. */
    val OPUS_5_5 = Rates(input = 4.0, output = 20.0, cacheWrite5m = 5.0, cacheRead = 0.20)

    /**
     * `claude-sonnet-5` can serve a request through the server-side refusal fallback
     * (`fallbacks: "default"`); same prices as Sonnet 5.5 per the migration guide.
     */
    val SONNET_5 = Rates(input = 2.0, output = 10.0, cacheWrite5m = 2.50, cacheRead = 0.20)

    /**
     * `claude-opus-5` (fallback target of Opus 5.5): 5 $ / 25 $, cache write 1.25×, cache
     * read 0.1× of the input price. Also used for any model id the table does not know, so an
     * unexpected model is never priced too low against the user's monthly limit.
     */
    val OPUS_5 = Rates(input = 5.0, output = 25.0, cacheWrite5m = 6.25, cacheRead = 0.50)

    /** 10 $ per 1,000 web searches. */
    const val WEB_SEARCH_USD = 10.0 / 1_000

    /**
     * Rates for a model id as the API reports it (an alias or a dated id). Longest prefix
     * first: `claude-sonnet-5-5` also starts with `claude-sonnet-5`.
     */
    fun ratesFor(modelId: String): Rates {
        val id = modelId.trim().lowercase()
        return when {
            id.startsWith("claude-sonnet-5-5") -> SONNET_5_5
            id.startsWith("claude-opus-5-5") -> OPUS_5_5
            id.startsWith("claude-sonnet-5") -> SONNET_5
            id.startsWith("claude-opus-5") -> OPUS_5
            else -> OPUS_5
        }
    }

    /** Cost in US dollars of one request with [usage] on [modelId]. */
    fun costUsd(modelId: String, usage: TokenUsage): Double {
        val r = ratesFor(modelId)
        val tokens = usage.inputTokens.coerceAtLeast(0) * r.input +
            usage.outputTokens.coerceAtLeast(0) * r.output +
            usage.cacheWriteTokens.coerceAtLeast(0) * r.cacheWrite5m +
            usage.cacheReadTokens.coerceAtLeast(0) * r.cacheRead
        return tokens / 1_000_000.0 + usage.webSearches.coerceAtLeast(0) * WEB_SEARCH_USD
    }
}
