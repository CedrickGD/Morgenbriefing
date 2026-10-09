package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.data.model.AiProviderId

/**
 * Effort and budget of one purpose (chat or Live) of a dialect (docs/AI-PROVIDERS.md §7, effort
 * table). Fields a dialect does not know stay null and are not sent.
 *
 * @property effort `reasoning.effort`.
 * @property maxToolCalls OpenAI `max_tool_calls` (built-in tool calls per response: the search cost guard).
 * @property maxTurns xAI `max_turns` (server-side tool turns per request).
 * @property maxSteps Perplexity `max_steps` (≥ 2 with search, or the model cannot read the results, OC §6.3).
 * @property searchType Perplexity `web_search.search_type`: "fast" or "web".
 * @property recencyFilter Perplexity `web_search.filters.search_recency_filter`.
 */
internal data class ResponsesBudget(
    val effort: String? = null,
    val maxOutputTokens: Int,
    val maxToolCalls: Int? = null,
    val maxTurns: Int? = null,
    val maxSteps: Int? = null,
    val searchType: String? = null,
    val recencyFilter: String? = null,
)

/**
 * The three providers on the OpenAI Responses API shape (D2) and what each one does
 * differently (OA §4–§9, OC §4 xAI, OC §6 Perplexity Agent API).
 *
 * @property path request path under the base URL.
 * @property contextAsDeveloperMessage OpenAI: the article context is a developer message in
 *   `input` (a cache breakpoint after it, OA §4); the others append it to `instructions`.
 * @property sendPhase OpenAI: replayed assistant messages carry `"phase": "final_answer"` (OA §4).
 * @property include `include` values.
 * @property obfuscationOff OpenAI: `stream_options.include_obfuscation = false` (saves bandwidth).
 * @property liveCacheOff OpenAI: a Live run is one-shot, so `prompt_cache_options {mode: explicit}`
 *   without breakpoints avoids paying 1.25× for cache writes it never reads (OA §9).
 * @property functionCallsOnlyInItemDone xAI and Perplexity send a function call whole in
 *   `response.output_item.done` and no argument deltas (OC §4.4, §6.4).
 * @property stripCitationMarkers Perplexity: remove `[1]` / `[web:1]` from the answer text.
 * @property storeOff send `store: false` (OpenAI and xAI keep responses 30 days otherwise).
 * @property typedMessages Perplexity: input messages carry `"type": "message"` (OC §6.3).
 * @property replayTypes output item types sent back with a correction; null = every item as
 *   received (OA §7.3). Perplexity: the function calls next to their outputs (OC §6.7).
 * @property languagePreference Perplexity `language_preference`.
 */
internal enum class ResponsesDialect(
    val provider: AiProviderId,
    val path: String,
    val contextAsDeveloperMessage: Boolean,
    val sendPhase: Boolean,
    val include: List<String>,
    val obfuscationOff: Boolean,
    val liveCacheOff: Boolean,
    val functionCallsOnlyInItemDone: Boolean,
    val stripCitationMarkers: Boolean,
    val storeOff: Boolean,
    val typedMessages: Boolean,
    val replayTypes: Set<String>?,
    val languagePreference: String?,
    val chat: ResponsesBudget,
    val live: ResponsesBudget,
) {
    OPENAI(
        provider = AiProviderId.OPENAI,
        path = "/responses",
        contextAsDeveloperMessage = true,
        sendPhase = true,
        include = listOf("web_search_call.action.sources"),
        obfuscationOff = true,
        liveCacheOff = true,
        functionCallsOnlyInItemDone = false,
        stripCitationMarkers = false,
        storeOff = true,
        typedMessages = false,
        replayTypes = null,
        languagePreference = null,
        chat = ResponsesBudget(effort = "low", maxOutputTokens = 12_000, maxToolCalls = 4),
        live = ResponsesBudget(effort = "medium", maxOutputTokens = 32_000, maxToolCalls = 10),
    ),
    XAI(
        provider = AiProviderId.XAI,
        path = "/responses",
        contextAsDeveloperMessage = false,
        sendPhase = false,
        include = listOf("web_search_call.action.sources", "no_inline_citations", "reasoning.encrypted_content"),
        obfuscationOff = false,
        liveCacheOff = false,
        functionCallsOnlyInItemDone = true,
        stripCitationMarkers = false,
        storeOff = true,
        typedMessages = false,
        replayTypes = null,
        languagePreference = null,
        chat = ResponsesBudget(effort = "low", maxOutputTokens = 8_000, maxTurns = 3),
        live = ResponsesBudget(effort = "medium", maxOutputTokens = 32_000, maxTurns = 10),
    ),
    PERPLEXITY(
        provider = AiProviderId.PERPLEXITY,
        path = "/agent",
        contextAsDeveloperMessage = false,
        sendPhase = false,
        include = emptyList(),
        obfuscationOff = false,
        liveCacheOff = false,
        functionCallsOnlyInItemDone = true,
        stripCitationMarkers = true,
        storeOff = false,
        typedMessages = true,
        replayTypes = setOf("function_call"),
        languagePreference = "de",
        chat = ResponsesBudget(maxOutputTokens = 4_000, maxSteps = 3, searchType = "fast"),
        live = ResponsesBudget(maxOutputTokens = 16_000, maxSteps = 10, searchType = "web", recencyFilter = "day"),
    ),
    ;

    companion object {
        /** The dialect of [id]; only OpenAI, xAI and Perplexity speak the Responses API. */
        fun of(id: AiProviderId): ResponsesDialect =
            entries.firstOrNull { it.provider == id } ?: throw IllegalArgumentException("${id.key} is no Responses provider")
    }
}
