package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.data.model.AiProviderId

/**
 * Effort and budget of one purpose (chat or Live) of a dialect (docs/AI-PROVIDERS.md §7, effort
 * table). Fields a dialect does not know stay null and are not sent — Mistral rejects unknown
 * fields (`additionalProperties: false`, OC §2.1).
 *
 * @property maxTokens `max_tokens`.
 * @property effort OpenRouter `reasoning.effort`.
 * @property maxToolCalls OpenRouter `max_tool_calls` (server tool calls per request: the search cost guard).
 * @property temperature Mistral and the own server (no reasoning control there).
 * @property thinkingOff DeepSeek `thinking: {type: disabled}` (thinking is on by default, OC §5.2).
 */
internal data class CompatBudget(
    val maxTokens: Int,
    val effort: String? = null,
    val maxToolCalls: Int? = null,
    val temperature: Double? = null,
    val thinkingOff: Boolean = false,
)

/** How a dialect checks a key (docs/AI-PROVIDERS.md §7.3). */
internal enum class KeyCheckKind {
    /** `GET {base}/models` (Mistral; the own server also collects the model ids). */
    MODELS,

    /** `GET {base}/key`: the key's own status (OpenRouter, OC §7.9). */
    OPENROUTER_KEY,

    /** `GET {base}/user/balance` (DeepSeek, OC §5.6). */
    DEEPSEEK_BALANCE,
}

/**
 * The providers on the OpenAI-compatible Chat Completions shape (D2) and what each one does
 * differently (OC §2.5, §3, §5, §7, §8).
 *
 * @property webSearch OpenRouter: the `openrouter:web_search` server tool (OC §7.3).
 * @property live OpenRouter: web search and `publish_articles` in one request (OC §7.8).
 * @property streamOptionsUsage DeepSeek: `stream_options.include_usage` (Mistral must not get it, OC §2.5).
 * @property extraHeaders OpenRouter's attribution headers (OC §7.1).
 * @property markdownLinkSources OpenRouter: Markdown links in an answer become sources and plain
 *   labels (its search cites as Markdown links; streamed annotations are undocumented, OC §7.5).
 * @property keyOptional the own server: `Authorization` only when a key is set.
 * @property replaysReasoningDetails OpenRouter: the streamed `delta.reasoning_details` go back
 *   on the replayed assistant tool-call message of a Live correction (OC §2.4) — Gemini 3
 *   upstreams reject a function call without its thought signature, Claude upstreams with
 *   thinking a tool use without its thinking block. Mistral rejects unknown fields, so only here.
 */
internal enum class CompatDialect(
    val provider: AiProviderId,
    val webSearch: Boolean,
    val live: Boolean,
    val streamOptionsUsage: Boolean,
    val extraHeaders: Map<String, String>,
    val keyCheck: KeyCheckKind,
    val markdownLinkSources: Boolean,
    val keyOptional: Boolean,
    val replaysReasoningDetails: Boolean,
    val chat: CompatBudget,
    val liveBudget: CompatBudget?,
) {
    OPENROUTER(
        provider = AiProviderId.OPENROUTER,
        webSearch = true,
        live = true,
        streamOptionsUsage = false,
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/CedrickGD/Morgenbriefing",
            "X-OpenRouter-Title" to "Morgenbriefing",
        ),
        keyCheck = KeyCheckKind.OPENROUTER_KEY,
        markdownLinkSources = true,
        keyOptional = false,
        replaysReasoningDetails = true,
        chat = CompatBudget(maxTokens = 8_000, effort = "low", maxToolCalls = 3),
        liveBudget = CompatBudget(maxTokens = 32_000, effort = "medium", maxToolCalls = 10),
    ),
    MISTRAL(
        provider = AiProviderId.MISTRAL,
        webSearch = false,
        live = false,
        streamOptionsUsage = false,
        extraHeaders = emptyMap(),
        keyCheck = KeyCheckKind.MODELS,
        markdownLinkSources = false,
        keyOptional = false,
        replaysReasoningDetails = false,
        chat = CompatBudget(maxTokens = 4_000, temperature = 0.3),
        liveBudget = null,
    ),
    DEEPSEEK(
        provider = AiProviderId.DEEPSEEK,
        webSearch = false,
        live = false,
        streamOptionsUsage = true,
        extraHeaders = emptyMap(),
        keyCheck = KeyCheckKind.DEEPSEEK_BALANCE,
        markdownLinkSources = false,
        keyOptional = false,
        replaysReasoningDetails = false,
        chat = CompatBudget(maxTokens = 4_000, thinkingOff = true),
        liveBudget = null,
    ),
    CUSTOM(
        provider = AiProviderId.CUSTOM,
        webSearch = false,
        live = false,
        streamOptionsUsage = false,
        extraHeaders = emptyMap(),
        keyCheck = KeyCheckKind.MODELS,
        markdownLinkSources = false,
        keyOptional = true,
        replaysReasoningDetails = false,
        chat = CompatBudget(maxTokens = 4_000, temperature = 0.3),
        liveBudget = null,
    ),
    ;

    companion object {
        /** The dialect of [id]; only OpenRouter, Mistral, DeepSeek and the own server speak Chat Completions here. */
        fun of(id: AiProviderId): CompatDialect =
            entries.firstOrNull { it.provider == id } ?: throw IllegalArgumentException("${id.key} is no Chat Completions provider")
    }
}
