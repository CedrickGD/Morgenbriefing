package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.LivePublish
import com.cedrickgd.morgenbriefing.ai.LiveResearchRequest
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.http.toJsonElement
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Request bodies of the Responses family as JSON element trees (D3; docs/AI-PROVIDERS.md §7.1,
 * OA §4/§8, OC §4.3/§6.3). Never sends `temperature`/`top_p` (rejected next to a reasoning
 * effort, OA §15) and always replays history as plain text (D12).
 */
internal object ResponsesRequests {
    /** The approximate reader location for OpenAI's search (it would default to the US, OA §6.1). */
    private val OPENAI_LOCATION = buildJsonObject {
        put("type", "approximate")
        put("country", "DE")
        put("city", "Leipzig")
        put("region", "Sachsen")
        put("timezone", "Europe/Berlin")
    }

    private val PERPLEXITY_LOCATION = buildJsonObject {
        put("country", "DE")
        put("city", "Leipzig")
    }

    /** Base URL of [auth]: the debug override (QA, debug builds only) or the catalog's. */
    fun baseUrl(dialect: ResponsesDialect, auth: ProviderAuth): String =
        (auth.baseUrl ?: ProviderCatalog.spec(dialect.provider).baseUrl ?: error("no base URL")).trimEnd('/')

    fun url(dialect: ResponsesDialect, auth: ProviderAuth): String = baseUrl(dialect, auth) + dialect.path

    /** `Authorization: Bearer <key>`; never logged. */
    fun headers(auth: ProviderAuth): Map<String, String> {
        val key = auth.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiException(AiError.InvalidKey)
        return mapOf("Authorization" to "Bearer $key")
    }

    /** "Frag nach": system prompt, article context, history, web search (when [ChatTurnRequest.webSearch]). */
    fun chat(dialect: ResponsesDialect, request: ChatTurnRequest): JsonObject {
        val budget = dialect.chat
        val instructions = if (dialect.contextAsDeveloperMessage) request.systemPrompt else "${request.systemPrompt}\n\n${request.context}"
        val input = buildJsonArray {
            if (dialect.contextAsDeveloperMessage) {
                addJsonObject {
                    put("role", "developer")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", request.context)
                        }
                    }
                }
            }
            request.history.forEach { add(message(dialect, if (it.fromUser) "user" else "assistant", it.text)) }
        }
        return body(dialect, request.model, budget, instructions, input, tools = if (request.webSearch) listOf(webSearchTool(dialect, budget)) else emptyList(), live = false)
    }

    /**
     * One request of a Live run: the Live system prompt, the conversation so far ([input]: the
     * research request, then replayed output items and function outputs), web search and the
     * strict `publish_articles` function (PORTABLE schema, every topic).
     */
    fun live(dialect: ResponsesDialect, request: LiveResearchRequest, input: List<JsonObject>): JsonObject {
        val budget = dialect.live
        val tools = listOf(webSearchTool(dialect, budget), publishTool())
        return body(dialect, request.model, budget, request.systemPrompt, JsonArray(input), tools, live = true)
    }

    /** A user (or replayed assistant) message as an input item. */
    fun message(dialect: ResponsesDialect, role: String, text: String): JsonObject = buildJsonObject {
        if (dialect.typedMessages) put("type", "message")
        put("role", role)
        put("content", text)
        if (role == "assistant" && dialect.sendPhase) put("phase", "final_answer")
    }

    /** The app's answer to one function call. */
    fun functionOutput(callId: String, output: String): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }

    /** `publish_articles` as a flat Responses function (OA §7.1), strict, with the PORTABLE schema. */
    fun publishTool(): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", LivePublish.TOOL_NAME)
        put("description", LivePublish.TOOL_DESCRIPTION)
        put("strict", true)
        put("parameters", LivePublish.parametersSchema(Topics.ALL.map { it.id }, LivePublish.SchemaDialect.PORTABLE).toJsonElement())
    }

    /** Perplexity's key check: the smallest possible request, no tools, not streamed (OC §6.8). */
    fun perplexityKeyCheck(): JsonObject = buildJsonObject {
        put("model", PERPLEXITY_CHECK_MODEL)
        put("input", "ping")
        put("max_output_tokens", 16)
    }

    const val PERPLEXITY_CHECK_MODEL = "openai/gpt-6-luna"

    private fun webSearchTool(dialect: ResponsesDialect, budget: ResponsesBudget): JsonObject = buildJsonObject {
        put("type", "web_search")
        when (dialect) {
            ResponsesDialect.OPENAI -> {
                put("search_context_size", "medium")
                put("user_location", OPENAI_LOCATION)
            }
            ResponsesDialect.XAI -> Unit
            ResponsesDialect.PERPLEXITY -> {
                budget.searchType?.let { put("search_type", it) }
                put("max_results", 5)
                put("user_location", PERPLEXITY_LOCATION)
                budget.recencyFilter?.let { filter -> putJsonObject("filters") { put("search_recency_filter", filter) } }
            }
        }
    }

    private fun body(
        dialect: ResponsesDialect,
        model: String,
        budget: ResponsesBudget,
        instructions: String,
        input: JsonArray,
        tools: List<JsonObject>,
        live: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        if (dialect.storeOff) put("store", false)
        budget.effort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
        put("max_output_tokens", budget.maxOutputTokens)
        put("instructions", instructions)
        put("input", input)
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools))
            // Never "required": the model must be free to answer without another call (OA §8.5).
            if (live || dialect == ResponsesDialect.OPENAI) put("tool_choice", "auto")
            budget.maxToolCalls?.let { put("max_tool_calls", it) }
            budget.maxTurns?.let { put("max_turns", it) }
        }
        budget.maxSteps?.let { put("max_steps", it) }
        if (dialect.include.isNotEmpty()) putJsonArray("include") { dialect.include.forEach { add(it) } }
        if (live && dialect.liveCacheOff) putJsonObject("prompt_cache_options") { put("mode", "explicit") }
        if (dialect.obfuscationOff) putJsonObject("stream_options") { put("include_obfuscation", false) }
        dialect.languagePreference?.let { put("language_preference", it) }
    }
}
