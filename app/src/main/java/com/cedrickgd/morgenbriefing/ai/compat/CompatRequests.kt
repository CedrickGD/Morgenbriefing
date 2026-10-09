package com.cedrickgd.morgenbriefing.ai.compat

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Request bodies of the Chat Completions family as JSON element trees (D3; docs/AI-PROVIDERS.md
 * §7.3, OC §2.1, §3.3, §5.2, §7.3). Every dialect gets only the fields it knows (Mistral
 * rejects foreign ones), history is replayed as plain text (D12).
 */
internal object CompatRequests {
    /** The approximate reader location for OpenRouter's native search (OC §7.3). */
    private val OPENROUTER_LOCATION = buildJsonObject {
        put("type", "approximate")
        put("country", "DE")
        put("city", "Leipzig")
        put("timezone", "Europe/Berlin")
    }

    /**
     * Base URL of [auth]: the own server's address (with the `/v1` rule) or, for the others, the
     * debug override (QA, debug builds only) or the catalog's.
     */
    fun baseUrl(dialect: CompatDialect, auth: ProviderAuth): String {
        val base = if (dialect == CompatDialect.CUSTOM) {
            CustomServer.normalize(auth.baseUrl) ?: throw AiException(AiError.ServerUnreachable)
        } else {
            auth.baseUrl ?: ProviderCatalog.spec(dialect.provider).baseUrl ?: error("no base URL")
        }
        return base.trimEnd('/')
    }

    fun url(dialect: CompatDialect, auth: ProviderAuth): String = baseUrl(dialect, auth) + "/chat/completions"

    /** `Authorization: Bearer <key>` (the own server only with a key) plus OpenRouter's attribution; never logged. */
    fun headers(dialect: CompatDialect, auth: ProviderAuth): Map<String, String> {
        val key = auth.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && !dialect.keyOptional) throw AiException(AiError.InvalidKey)
        return buildMap {
            key?.let { put("Authorization", "Bearer $it") }
            putAll(dialect.extraHeaders)
        }
    }

    /**
     * "Frag nach": the system prompt with the context after it as the system message (OC §2.1),
     * the history, and OpenRouter's web search when [ChatTurnRequest.webSearch].
     * [maxToolCalls]: send `max_tool_calls` (dropped after OpenRouter refused it once).
     */
    fun chat(dialect: CompatDialect, request: ChatTurnRequest, maxToolCalls: Boolean): JsonObject {
        val messages = buildJsonArray {
            add(message("system", "${request.systemPrompt}\n\n${request.context}"))
            request.history.forEach { add(message(if (it.fromUser) "user" else "assistant", it.text)) }
        }
        val search = request.webSearch && dialect.webSearch
        return body(dialect, request.model, dialect.chat, messages, if (search) listOf(webSearchTool()) else emptyList(), maxToolCalls)
    }

    /**
     * One request of a Live run (OpenRouter, OC §7.8): the Live system prompt, the conversation so
     * far ([messages]: the research request, then replayed assistant tool calls and tool
     * results), and on every request (OC §7.3) the web search server tool plus `publish_articles`.
     */
    fun live(dialect: CompatDialect, request: LiveResearchRequest, messages: List<JsonObject>, maxToolCalls: Boolean): JsonObject {
        val budget = dialect.liveBudget ?: throw IllegalArgumentException("${dialect.provider.key} has no Live")
        val all = buildJsonArray {
            add(message("system", request.systemPrompt))
            messages.forEach { add(it) }
        }
        return body(dialect, request.model, budget, all, listOf(webSearchTool(), publishTool()), maxToolCalls)
    }

    /** A plain text message. */
    fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    /** The assistant turn of a Live request with its tool calls, replayed for the correction (OC §2.4). */
    fun assistantToolCalls(text: String, calls: List<ToolCall>): JsonObject = buildJsonObject {
        put("role", "assistant")
        if (text.isBlank()) put("content", JsonNull) else put("content", text)
        putJsonArray("tool_calls") {
            calls.forEach { call ->
                addJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", call.name)
                        put("arguments", call.arguments)
                    }
                }
            }
        }
    }

    /** The app's answer to one tool call. */
    fun toolResult(callId: String, text: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", callId)
        put("content", text)
    }

    /**
     * `publish_articles` as a nested Chat Completions function (OC §2.4) with the PORTABLE schema
     * and every topic; no `strict` — OpenRouter's upstreams differ in what they accept (§5.2).
     */
    fun publishTool(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", LivePublish.TOOL_NAME)
            put("description", LivePublish.TOOL_DESCRIPTION)
            put("parameters", LivePublish.parametersSchema(Topics.ALL.map { it.id }, LivePublish.SchemaDialect.PORTABLE).toJsonElement())
        }
    }

    /** OpenRouter's search server tool (OC §7.3); `engine: auto` = the model's native search, else Exa. */
    private fun webSearchTool(): JsonObject = buildJsonObject {
        put("type", "openrouter:web_search")
        putJsonObject("parameters") {
            put("engine", "auto")
            put("max_results", 5)
            put("search_context_size", "medium")
            put("user_location", OPENROUTER_LOCATION)
        }
    }

    private fun body(
        dialect: CompatDialect,
        model: String,
        budget: CompatBudget,
        messages: JsonArray,
        tools: List<JsonObject>,
        maxToolCalls: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("messages", messages)
        put("max_tokens", budget.maxTokens)
        budget.temperature?.let { put("temperature", it) }
        budget.effort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
        if (budget.thinkingOff) putJsonObject("thinking") { put("type", "disabled") }
        if (dialect.streamOptionsUsage) putJsonObject("stream_options") { put("include_usage", true) }
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools))
            if (maxToolCalls) budget.maxToolCalls?.let { put("max_tool_calls", it) }
        }
    }
}
