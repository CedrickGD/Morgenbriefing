package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import java.time.LocalDate

/**
 * Token counts of one billed API request (one HTTP call; a `pause_turn` continuation or a tool
 * round trip is a request of its own). Same convention for every engine: [inputTokens] is the
 * *uncached* input, cache reads and writes are counted separately (writes are 5-minute writes),
 * [outputTokens] includes reasoning/thinking tokens and [webSearches] counts billable searches.
 *
 * @property reportedCostUsd what the provider itself says the request cost (OpenRouter
 *   `usage.cost`, xAI `cost_in_usd_ticks`, Perplexity `cost.total_cost`); wins over the price
 *   table. 0.0 for requests that are free (a free Gemini key).
 */
internal data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val webSearches: Long = 0,
    val webFetches: Long = 0,
    val reportedCostUsd: Double? = null,
) {
    /** Sums every count; a reported cost on either side is summed with null counting as 0. */
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        webSearches = webSearches + other.webSearches,
        webFetches = webFetches + other.webFetches,
        reportedCostUsd = if (reportedCostUsd == null && other.reportedCostUsd == null) {
            null
        } else {
            (reportedCostUsd ?: 0.0) + (other.reportedCostUsd ?: 0.0)
        },
    )

    /** All input the request sent: uncached, cache reads and cache writes. */
    val totalInputTokens: Long
        get() = inputTokens.coerceAtLeast(0) + cacheReadTokens.coerceAtLeast(0) + cacheWriteTokens.coerceAtLeast(0)

    val isEmpty: Boolean
        get() = inputTokens == 0L && outputTokens == 0L && cacheWriteTokens == 0L &&
            cacheReadTokens == 0L && webSearches == 0L && webFetches == 0L && (reportedCostUsd ?: 0.0) == 0.0
}

/**
 * The one place with API prices of every provider (docs/AI-PROVIDERS.md §9), in US dollars per
 * million tokens, as published on [AS_OF]. Everything the app spends is computed from a
 * response's usage with this table (or the provider's own reported cost) and summed per month.
 *
 * Matching: model ids are lowercased, the longest matching [Entry.modelPrefix] of the provider
 * wins (`claude-sonnet-5-5` before `claude-sonnet-5`, `gemini-3.5-flash-lite` before
 * `gemini-3.5-flash`), and among its entries the one with the latest [Entry.validFrom] on the
 * request day (Gemini Flash doubles on 2027-01-01). OpenRouter and Perplexity ids carry their
 * `vendor/` prefix. A model the table does not know is priced with the provider's most
 * expensive rates and marked as estimated, so it is never priced too low against the limit.
 */
internal object AiPricing {
    const val AS_OF = "2026-10-09"

    data class Rates(
        val input: Double,
        val output: Double,
        val cacheWrite: Double,
        val cacheRead: Double,
        /** Per web search call (Gemini, Perplexity: per executed query). */
        val perSearchUsd: Double = 0.0,
        val longContext: LongContext? = null,
    ) {
        /** These rates with the token prices scaled (long-context surcharges); searches keep their price. */
        fun scaled(input: Double, output: Double, cache: Double = input): Rates = Rates(
            input = this.input * input,
            output = this.output * output,
            cacheWrite = cacheWrite * cache,
            cacheRead = cacheRead * cache,
            perSearchUsd = perSearchUsd,
        )
    }

    /** More than [thresholdInputTokens] input (uncached + cache read + cache write): the whole request uses [rates]. */
    data class LongContext(val thresholdInputTokens: Long, val rates: Rates)

    data class Entry(
        val provider: AiProviderId,
        val modelPrefix: String,
        val rates: Rates,
        val validFrom: LocalDate? = null,
        val source: String,
    )

    data class PricedCost(val usd: Double, val estimated: Boolean)

    private const val CLAUDE = "platform.claude.com/docs/en/about-claude/pricing, 2026-10-09"
    private const val OPENAI = "docs/providers/openai.md §3 (developers.openai.com pricing), 2026-10-09"
    private const val GEMINI = "docs/providers/gemini.md §3 (ai.google.dev pricing), 2026-10-09"
    private const val XAI = "docs/providers/openai-compatible.md §4.2 (docs.x.ai), 2026-10-09"
    private const val PERPLEXITY = "docs/providers/openai-compatible.md §6.2 (docs.perplexity.ai), 2026-10-09"
    private const val OPENROUTER = "docs/providers/openai-compatible.md §7.2 (openrouter.ai/models), 2026-10-09"
    private const val MISTRAL = "docs/providers/openai-compatible.md §3.2 (mistral.ai pricing), 2026-10-09"
    private const val DEEPSEEK = "docs/providers/openai-compatible.md §5.1 (api-docs.deepseek.com), 2026-10-09"

    private const val CLAUDE_SEARCH = 0.01
    private const val OPENAI_SEARCH = 0.01
    private const val GEMINI_SEARCH = 0.014
    private const val XAI_SEARCH = 0.005
    private const val PERPLEXITY_SEARCH = 0.0025
    private const val EXA_SEARCH = 0.007

    /** Gemini Flash prices from 2027-01-01 (introductory prices end on 2026-12-31). */
    private val GEMINI_FLASH_2027: LocalDate = LocalDate.of(2027, 1, 1)

    // --- Anthropic ---------------------------------------------------------------------------
    val SONNET_5_5 = Rates(input = 2.0, output = 10.0, cacheWrite = 2.50, cacheRead = 0.10, perSearchUsd = CLAUDE_SEARCH)
    val OPUS_5_5 = Rates(input = 4.0, output = 20.0, cacheWrite = 5.0, cacheRead = 0.20, perSearchUsd = CLAUDE_SEARCH)
    val HAIKU_5_5 = Rates(
        input = 0.10, output = 0.50, cacheWrite = 0.125, cacheRead = 0.01, perSearchUsd = CLAUDE_SEARCH,
        longContext = LongContext(100_000, Rates(0.50, 2.50, 0.625, 0.05, CLAUDE_SEARCH)),
    )

    /** Target of the server-side refusal fallback of Sonnet 5.5. */
    val SONNET_5 = Rates(input = 2.0, output = 10.0, cacheWrite = 2.50, cacheRead = 0.20, perSearchUsd = CLAUDE_SEARCH)

    /** Target of the refusal fallback of Opus 5.5; also Anthropic's rate for unknown models. */
    val OPUS_5 = Rates(input = 5.0, output = 25.0, cacheWrite = 6.25, cacheRead = 0.50, perSearchUsd = CLAUDE_SEARCH)

    /** Unknown OpenRouter/Perplexity models without a reported cost: priced like a top model. */
    val GLOBAL_FALLBACK = Rates(input = 10.0, output = 50.0, cacheWrite = 12.50, cacheRead = 1.00, perSearchUsd = GEMINI_SEARCH)

    /** OpenAI: above 272k input tokens input and cache ×2, output ×1.5. */
    private fun openAi(input: Double, output: Double, cacheWrite: Double, cacheRead: Double): Rates {
        val base = Rates(input, output, cacheWrite, cacheRead, OPENAI_SEARCH)
        return base.copy(longContext = LongContext(272_000, base.scaled(input = 2.0, output = 1.5)))
    }

    /** xAI: from 200k input tokens every token price doubles. */
    private fun xai(input: Double, output: Double, cacheWrite: Double, cacheRead: Double): Rates {
        val base = Rates(input, output, cacheWrite, cacheRead, XAI_SEARCH)
        return base.copy(longContext = LongContext(199_999, base.scaled(input = 2.0, output = 2.0)))
    }

    private fun gemini(input: Double, output: Double, cacheRead: Double) = Rates(input, output, input, cacheRead, GEMINI_SEARCH)

    private val GEMINI_FLASH_NOW = gemini(0.75, 3.75, 0.075)
    private val GEMINI_FLASH_LATER = gemini(1.50, 7.50, 0.15)
    private val GEMINI_PRO = gemini(2.00, 12.00, 0.20).copy(longContext = LongContext(200_000, gemini(4.00, 18.00, 0.40)))

    private fun entries(provider: AiProviderId, source: String, rates: Rates, vararg prefixes: String, validFrom: LocalDate? = null) =
        prefixes.map { Entry(provider, it, rates, validFrom, source) }

    val TABLE: List<Entry> = buildList {
        val a = AiProviderId.ANTHROPIC
        addAll(entries(a, CLAUDE, SONNET_5_5, "claude-sonnet-5-5"))
        addAll(entries(a, CLAUDE, OPUS_5_5, "claude-opus-5-5"))
        addAll(entries(a, CLAUDE, HAIKU_5_5, "claude-haiku-5-5"))
        addAll(entries(a, CLAUDE, SONNET_5, "claude-sonnet-5"))
        addAll(entries(a, CLAUDE, OPUS_5, "claude-opus-5"))

        val o = AiProviderId.OPENAI
        addAll(entries(o, OPENAI, openAi(2.00, 10.00, 2.50, 0.10), "gpt-6.1-sol"))
        addAll(entries(o, OPENAI, openAi(0.10, 0.50, 0.125, 0.01), "gpt-6-luna"))
        addAll(entries(o, OPENAI, openAi(10.00, 50.00, 12.50, 1.00), "gpt-6-astra"))
        addAll(entries(o, OPENAI, openAi(2.00, 10.00, 2.50, 0.20), "gpt-6-sol"))
        // Promo price until 2026-11-21; the list price is used (conservative).
        addAll(entries(o, OPENAI, openAi(4.00, 20.00, 5.00, 0.40), "gpt-5.6-sol", "gpt-5.6"))
        addAll(entries(o, OPENAI, openAi(2.00, 12.00, 2.50, 0.20), "gpt-5.6-terra"))
        addAll(entries(o, OPENAI, openAi(0.20, 1.20, 0.25, 0.02), "gpt-5.6-luna"))
        // No cache write price listed: the input price.
        addAll(entries(o, OPENAI, openAi(5.00, 30.00, 5.00, 0.50), "gpt-5.5"))

        val g = AiProviderId.GEMINI
        val flash = arrayOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
        addAll(entries(g, GEMINI, GEMINI_FLASH_NOW, *flash))
        addAll(entries(g, GEMINI, GEMINI_FLASH_LATER, *flash, validFrom = GEMINI_FLASH_2027))
        addAll(entries(g, GEMINI, gemini(1.50, 9.00, 0.15), "gemini-3.5-flash"))
        addAll(entries(g, GEMINI, gemini(0.30, 2.50, 0.03), "gemini-3.5-flash-lite"))
        addAll(entries(g, GEMINI, gemini(0.25, 1.50, 0.025), "gemini-3.1-flash-lite"))
        addAll(entries(g, GEMINI, GEMINI_PRO, "gemini-3.1-pro"))
        addAll(entries(g, GEMINI, gemini(0.50, 3.00, 0.05), "gemini-3-flash"))

        val x = AiProviderId.XAI
        addAll(entries(x, XAI, xai(2.00, 6.00, 2.00, 0.50), "grok-4.7", "grok-4.6", "grok-4.5"))
        addAll(entries(x, XAI, xai(1.25, 2.50, 1.25, 0.20), "grok-4.3", "grok-4.20"))

        // Only used when Perplexity sends no cost of its own.
        val p = AiProviderId.PERPLEXITY
        addAll(entries(p, PERPLEXITY, Rates(0.75, 3.75, 0.75, 0.075, PERPLEXITY_SEARCH), "google/gemini-3.8-flash"))
        addAll(entries(p, PERPLEXITY, Rates(0.25, 2.50, 0.25, 0.0625, PERPLEXITY_SEARCH), "perplexity/sonar"))
        addAll(entries(p, PERPLEXITY, Rates(2.00, 10.00, 2.50, 0.20, PERPLEXITY_SEARCH), "anthropic/claude-sonnet-5-5"))
        addAll(entries(p, PERPLEXITY, Rates(4.00, 20.00, 5.00, 0.20, PERPLEXITY_SEARCH), "anthropic/claude-opus-5-5"))
        addAll(entries(p, PERPLEXITY, Rates(0.10, 0.50, 0.10, 0.01, PERPLEXITY_SEARCH), "openai/gpt-6-luna"))

        // Only used when OpenRouter sends no usage.cost.
        val r = AiProviderId.OPENROUTER
        addAll(entries(r, OPENROUTER, Rates(0.75, 3.75, 0.75, 0.075, GEMINI_SEARCH), "google/gemini-3.8-flash"))
        addAll(entries(r, OPENROUTER, Rates(0.10, 0.50, 0.10, 0.01, OPENAI_SEARCH), "openai/gpt-6-luna"))
        addAll(entries(r, OPENROUTER, Rates(2.00, 10.00, 2.00, 0.10, OPENAI_SEARCH), "openai/gpt-6.1-sol"))
        addAll(entries(r, OPENROUTER, Rates(2.00, 10.00, 2.50, 0.10, CLAUDE_SEARCH), "anthropic/claude-sonnet-5.5"))
        addAll(entries(r, OPENROUTER, Rates(4.00, 20.00, 5.00, 0.20, CLAUDE_SEARCH), "anthropic/claude-opus-5.5"))
        addAll(entries(r, OPENROUTER, Rates(0.10, 0.50, 0.125, 0.01, CLAUDE_SEARCH), "anthropic/claude-haiku-5.5"))
        addAll(entries(r, OPENROUTER, Rates(2.00, 6.00, 2.00, 0.50, XAI_SEARCH), "x-ai/grok-4.7"))
        addAll(entries(r, OPENROUTER, Rates(0.30, 1.20, 0.30, 0.006, EXA_SEARCH), "deepseek/deepseek-v4.1-flash"))
        addAll(entries(r, OPENROUTER, Rates(0.15, 0.60, 0.15, 0.015, EXA_SEARCH), "mistralai/mistral-small-2603"))

        val m = AiProviderId.MISTRAL
        addAll(entries(m, MISTRAL, Rates(1.50, 7.50, 1.50, 0.15), "mistral-medium-3-5", "mistral-medium-3", "mistral-medium-latest"))
        addAll(entries(m, MISTRAL, Rates(0.15, 0.60, 0.15, 0.015), "mistral-small-2603", "mistral-small-latest"))
        addAll(entries(m, MISTRAL, Rates(0.50, 1.50, 0.50, 0.05), "mistral-large-2512", "mistral-large-latest"))
        // List price; the sale until about 2026-10-20 is ignored.
        addAll(entries(m, MISTRAL, Rates(1.36, 4.18, 1.36, 0.14), "mistral-large-4"))
        addAll(entries(m, MISTRAL, Rates(0.15, 0.15, 0.15, 0.015), "ministral-8b"))

        // Always the peak price (off-peak is up to 50 % cheaper).
        val d = AiProviderId.DEEPSEEK
        addAll(entries(d, DEEPSEEK, Rates(0.30, 1.20, 0.30, 0.006), "deepseek-flash", "deepseek-v4-flash"))
        addAll(entries(d, DEEPSEEK, Rates(1.32, 3.96, 1.32, 0.044), "deepseek-v4-pro"))
    }

    /** Rates for a model the table does not know: the provider's most expensive known ones. */
    private val PROVIDER_FALLBACK: Map<AiProviderId, Rates> = mapOf(
        AiProviderId.ANTHROPIC to OPUS_5,
        AiProviderId.OPENAI to openAi(10.00, 50.00, 12.50, 1.00),
        AiProviderId.GEMINI to GEMINI_PRO,
        AiProviderId.XAI to xai(2.00, 6.00, 2.00, 0.50),
        AiProviderId.PERPLEXITY to GLOBAL_FALLBACK,
        AiProviderId.OPENROUTER to GLOBAL_FALLBACK,
        // Mistral Medium, not Large 4: Medium is the more expensive one.
        AiProviderId.MISTRAL to Rates(1.50, 7.50, 1.50, 0.15),
        AiProviderId.DEEPSEEK to Rates(1.32, 3.96, 1.32, 0.044),
    )

    private val FREE = Rates(0.0, 0.0, 0.0, 0.0)

    /**
     * Rates of [modelId] at [provider] on [on]; the second value is true when the model is
     * unknown and the provider's fallback rates are used (cost "estimated").
     */
    fun ratesFor(provider: AiProviderId, modelId: String, on: LocalDate): Pair<Rates, Boolean> {
        if (provider == AiProviderId.CUSTOM) return FREE to false
        val id = modelId.trim().lowercase()
        val matching = TABLE.filter { it.provider == provider && id.startsWith(it.modelPrefix) }
        val longest = matching.maxOfOrNull { it.modelPrefix.length }
            ?: return (PROVIDER_FALLBACK[provider] ?: GLOBAL_FALLBACK) to true
        val entry = matching
            .filter { it.modelPrefix.length == longest && (it.validFrom == null || !it.validFrom.isAfter(on)) }
            .maxByOrNull { it.validFrom ?: LocalDate.MIN }
            ?: return (PROVIDER_FALLBACK[provider] ?: GLOBAL_FALLBACK) to true
        return entry.rates to false
    }

    /**
     * Cost of one request with [usage] on [modelId] at [provider], on day [on]: the provider's
     * reported cost when there is one, 0 $ for the own server, else the price table (with the
     * long-context rates when the request's input is above their threshold).
     */
    fun costUsd(provider: AiProviderId, modelId: String, usage: TokenUsage, on: LocalDate): PricedCost {
        usage.reportedCostUsd?.let { return PricedCost(it.coerceAtLeast(0.0), estimated = false) }
        if (provider == AiProviderId.CUSTOM) return PricedCost(0.0, estimated = false)
        val (base, estimated) = ratesFor(provider, modelId, on)
        val long = base.longContext
        val r = if (long != null && usage.totalInputTokens > long.thresholdInputTokens) long.rates else base
        val tokens = usage.inputTokens.coerceAtLeast(0) * r.input +
            usage.outputTokens.coerceAtLeast(0) * r.output +
            usage.cacheWriteTokens.coerceAtLeast(0) * r.cacheWrite +
            usage.cacheReadTokens.coerceAtLeast(0) * r.cacheRead
        return PricedCost(tokens / 1_000_000.0 + usage.webSearches.coerceAtLeast(0) * r.perSearchUsd, estimated)
    }
}
