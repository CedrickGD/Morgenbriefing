package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AnswerText
import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TokenUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.HttpFailure
import com.cedrickgd.morgenbriefing.ai.http.ProviderErrors
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.SseEnd
import com.cedrickgd.morgenbriefing.ai.http.SseEvent
import com.cedrickgd.morgenbriefing.ai.http.double
import com.cedrickgd.morgenbriefing.ai.http.long
import com.cedrickgd.morgenbriefing.ai.http.obj
import com.cedrickgd.morgenbriefing.ai.http.objects
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.ai.http.streamSse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap

/** A client tool call of one response, complete (arguments as the JSON text the model wrote). */
internal data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * Outcome of one streamed Chat Completions request. Text and sources went to the [AnswerText]
 * and [SourceCollector] the request was run with.
 *
 * @property citedByProvider the provider sent `url_citation` annotations (else OpenRouter's
 *   Markdown links are the sources).
 * @property reasoningDetails OpenRouter: the turn's `reasoning_details` as received (merged per
 *   item), for the replayed assistant message of a Live correction; null when none came.
 */
internal class CompatTurn(
    val end: TurnEnd,
    val refusalCategory: String?,
    val toolCalls: List<ToolCall>,
    val citedByProvider: Boolean,
    val reasoningDetails: JsonArray? = null,
)

/**
 * OpenRouter refused the top-level `max_tool_calls` (it is in the docs, not in the schema,
 * OC §7.3): the request is sent once more without it. Never leaves the family.
 */
internal class MaxToolCallsRejected(cause: HttpFailure) : Exception("max_tool_calls rejected", cause)

/**
 * A usage object as [TokenUsage] (OC §2.5, §5.3, §7.7): `prompt_tokens` minus cache reads
 * (`prompt_tokens_details.cached_tokens`, DeepSeek `prompt_cache_hit_tokens`, Mistral
 * `num_cached_tokens`) and writes is the uncached input; OpenRouter also reports the charged
 * cost (`cost`) and its server-side searches under one of two names.
 */
internal fun compatUsageOf(u: JsonObject, streamedChars: Long = 0): TokenUsage {
    val details = u.obj("prompt_tokens_details")
    val cacheRead = details?.long("cached_tokens") ?: u.long("prompt_cache_hit_tokens") ?: u.long("num_cached_tokens") ?: 0L
    val cacheWrite = details?.long("cache_write_tokens") ?: 0L
    val prompt = u.long("prompt_tokens") ?: 0L
    val searches = u.obj("server_tool_use_details")?.long("web_search_requests")
        ?: u.obj("server_tool_use")?.long("web_search_requests")
        ?: 0L
    return TokenUsage(
        inputTokens = (prompt - cacheRead - cacheWrite).coerceAtLeast(0),
        outputTokens = u.long("completion_tokens") ?: (streamedChars / 3),
        cacheWriteTokens = cacheWrite,
        cacheReadTokens = cacheRead,
        webSearches = searches,
        reportedCostUsd = u.double("cost"),
    )
}

/**
 * Runs one streamed Chat Completions request: retries per [retry] before the first event, feeds
 * every chunk to a [CompatChunkParser], and records the request's usage with the [UsageSink] —
 * also when it fails or is cancelled after the stream started (estimated when no usage came).
 * Usage is priced for the requested model: it is the id the user picked and the price table
 * knows (OpenRouter reports its cost anyway, the own server is free).
 */
internal class CompatStreamer(
    private val http: AiHttp,
    private val dialect: CompatDialect,
    private val retry: RetryPolicy,
) {
    /** OpenRouter refused `max_tool_calls` once: not sent again for the life of the process. */
    @Volatile
    private var maxToolCallsRejected = false

    /**
     * Runs the request [body] builds; its argument says whether `max_tool_calls` may be sent. A
     * refusal of that field is answered by sending the request once more without it.
     */
    suspend fun run(
        auth: ProviderAuth,
        model: String,
        listener: ChatTurnListener,
        usage: UsageSink,
        sources: SourceCollector,
        text: AnswerText,
        body: (maxToolCalls: Boolean) -> JsonObject,
    ): CompatTurn {
        if (!maxToolCallsRejected) {
            try {
                return run(auth, model, body(true), listener, usage, sources, text)
            } catch (_: MaxToolCallsRejected) {
                maxToolCallsRejected = true
            }
        }
        return run(auth, model, body(false), listener, usage, sources, text)
    }

    private suspend fun run(
        auth: ProviderAuth,
        model: String,
        body: JsonObject,
        listener: ChatTurnListener,
        usage: UsageSink,
        sources: SourceCollector,
        text: AnswerText,
    ): CompatTurn {
        val request = http.post(CompatRequests.url(dialect, auth), body, CompatRequests.headers(dialect, auth), stream = true)
        val requestChars = body.toString().length
        val limitSent = body.containsKey(MAX_TOOL_CALLS)
        val mapper = ProviderErrors.mapper(auth, model)
        return retry.run(dialect.provider.key, { e -> if (e is MaxToolCallsRejected) null else mapper.map(e) }) { attempt ->
            usage.beforeRequest()
            val parser = CompatChunkParser(dialect, listener, sources, text, model)
            val end = try {
                http.streamSse(request) { event ->
                    attempt.delivered = true
                    parser.onEvent(event)
                }
            } catch (e: HttpFailure) {
                if (limitSent && e.code == 400 && e.body.contains(MAX_TOOL_CALLS)) throw MaxToolCallsRejected(e)
                throw e
            } catch (e: Throwable) {
                // Whatever streamed was billed: record it even when cancelled or failed.
                if (attempt.delivered) withContext(NonCancellable) { record(parser, usage, model, requestChars) }
                throw e
            }
            record(parser, usage, model, requestChars)
            parser.finish(end)
        }
    }

    private suspend fun record(parser: CompatChunkParser, usage: UsageSink, model: String, requestChars: Int) {
        usage.record(dialect.provider, model, parser.tokenUsage(requestChars))
    }

    private companion object {
        const val MAX_TOOL_CALLS = "max_tool_calls"
    }
}

/**
 * The Chat Completions stream state machine (OC §2.2–§2.7, docs/AI-PROVIDERS.md §7.3). Comment
 * lines and `[DONE]` are handled by the SSE reader; every other event is one chunk:
 *
 * * top-level `error` (OpenRouter, HTTP stays 200) → mapped like its HTTP code, never retried;
 * * `choices[0].delta.content`: a string, or (Mistral) an array whose `text` parts are answer
 *   text and whose `thinking` parts are not; `reasoning_content` / `reasoning` are never shown;
 * * OpenRouter's `delta.reasoning_details` ([CompatDialect.replaysReasoningDetails]) are never
 *   shown but kept for the replay: items with the same `index` (else `id`) are merged — text
 *   pieces (`text`, `summary`, `data`, `signature`) appended, other fields as last received —
 *   and every other item is kept exactly as it came;
 * * `delta.tool_calls` accumulate by `index` (id and name first, argument fragments appended;
 *   a call that arrives whole works the same; Mistral's id `"null"` is ignored);
 * * `url_citation` annotations (OpenRouter, defensive: their streaming place is undocumented);
 * * `finish_reason` is remembered and reading goes on (OpenRouter repeats it in a final usage
 *   chunk); the last non-null `usage` wins.
 */
internal class CompatChunkParser(
    private val dialect: CompatDialect,
    private val listener: ChatTurnListener,
    private val sources: SourceCollector,
    private val text: AnswerText,
    private val requestModel: String,
) {
    private class Acc {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var announced = false
    }

    private val calls = TreeMap<Int, Acc>()

    /** `reasoning_details` items in arrival order, keyed by `index` / `id` (or their position). */
    private val reasoning = LinkedHashMap<String, MutableMap<String, JsonElement>>()
    private var finishReason: String? = null
    private var usage: JsonObject? = null
    private var refused = false
    private var cited = false
    private var textStarted = false
    private var streamedChars = 0L

    /** Handles one chunk; always true (the stream ends with `[DONE]` or EOF). */
    fun onEvent(event: SseEvent): Boolean {
        val chunk = try {
            JSON.parseToJsonElement(event.data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return true
        if (chunk["error"].let { it != null && it !is JsonNull }) {
            throw AiException(ProviderErrors.streamError(dialect.provider, chunk, requestModel))
        }
        chunk.obj("usage")?.let { usage = it }
        chunk.objects("annotations").forEach(::onAnnotation)
        val choice = chunk.objects("choices").firstOrNull() ?: return true
        val delta = choice.obj("delta") ?: choice.obj("message")
        if (delta != null) {
            onContent(delta["content"])
            delta.str("refusal")?.takeIf { it.isNotBlank() }?.let { refused = true }
            delta.objects("tool_calls").forEach(::onToolCall)
            delta.objects("annotations").forEach(::onAnnotation)
            if (dialect.replaysReasoningDetails) delta.objects("reasoning_details").forEach(::onReasoningDetail)
        }
        choice.str("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
        return true
    }

    /**
     * The turn after the stream ended with [end]: `stop` → complete, `length` → cut off,
     * `content_filter` → refused, `tool_calls` → complete with calls; `error`,
     * `insufficient_system_resource` and `aborted` mean the provider gave up
     * ([AiError.Overloaded]). Without a finish reason the stream must have ended with `[DONE]`,
     * else the connection broke ([AiError.Network]).
     */
    fun finish(end: SseEnd): CompatTurn {
        val reason = finishReason
        val turnEnd = when {
            refused -> TurnEnd.REFUSED
            reason == null -> if (end == SseEnd.DONE) TurnEnd.COMPLETE else throw AiException(AiError.Network)
            reason == "length" -> TurnEnd.TRUNCATED
            reason == "content_filter" -> TurnEnd.REFUSED
            reason in GAVE_UP -> throw AiException(AiError.Overloaded)
            else -> TurnEnd.COMPLETE
        }
        return CompatTurn(
            end = turnEnd,
            refusalCategory = if (turnEnd == TurnEnd.REFUSED && !refused) reason else null,
            toolCalls = toolCalls(),
            citedByProvider = cited,
            reasoningDetails = reasoning.takeIf { it.isNotEmpty() }?.values?.let { items -> JsonArray(items.map(::JsonObject)) },
        )
    }

    /**
     * Usage of this request. Without a usage object (the stream broke off, or a server that does
     * not report it) it is estimated: the request at ≈ 4 characters per input token, the
     * streamed text and arguments at ≈ 3 per output token.
     */
    fun tokenUsage(requestChars: Int): TokenUsage =
        usage?.let { compatUsageOf(it, streamedChars) } ?: TokenUsage(inputTokens = requestChars / 4L, outputTokens = streamedChars / 3)

    private fun onContent(content: JsonElement?) {
        when (content) {
            is JsonPrimitive -> if (content !is JsonNull) emit(content.content)
            is JsonArray -> content.forEach { part ->
                val o = part as? JsonObject ?: return@forEach
                if (o.str("type") == "text") o.str("text")?.let(::emit)
            }
            else -> Unit
        }
    }

    private fun emit(delta: String) {
        if (delta.isEmpty()) return
        if (!textStarted) {
            textStarted = true
            text.startTextBlock()?.let(listener::onText)
        }
        streamedChars += delta.length
        text.append(delta)
        listener.onText(delta)
    }

    private fun onToolCall(tc: JsonObject) {
        val index = tc.long("index")?.toInt() ?: 0
        val acc = calls.getOrPut(index) { Acc() }
        tc.str("id")?.takeIf { it.isNotEmpty() && it != "null" }?.let { acc.id = it }
        val function = tc.obj("function") ?: return
        function.str("name")?.takeIf { it.isNotEmpty() }?.let { acc.name = it }
        function.str("arguments")?.let {
            acc.arguments.append(it)
            streamedChars += it.length
        }
        val name = acc.name
        if (!acc.announced && name != null) {
            acc.announced = true
            listener.onToolCall(name)
        }
    }

    /**
     * One streamed `reasoning_details` item (OC §2.3): merged into the item with the same `index`
     * (else `id`), its text pieces appended; an item without either stays on its own.
     */
    private fun onReasoningDetail(detail: JsonObject) {
        val key = detail.long("index")?.let { "index:$it" } ?: detail.str("id")?.let { "id:$it" } ?: "item:${reasoning.size}"
        val merged = reasoning.getOrPut(key) { LinkedHashMap() }
        detail.forEach { (name, value) ->
            val before = merged[name]
            merged[name] = when {
                before == null -> value
                value is JsonNull -> before
                name in REASONING_PIECES && before.isText() && value.isText() ->
                    JsonPrimitive((before as JsonPrimitive).content + (value as JsonPrimitive).content)
                else -> value
            }
        }
    }

    private fun JsonElement.isText(): Boolean = this is JsonPrimitive && isString

    /** `{"type":"url_citation","url_citation":{url,title}}`, also the flat form. */
    private fun onAnnotation(annotation: JsonObject) {
        if (annotation.str("type") != "url_citation") return
        val citation = annotation.obj("url_citation") ?: annotation
        val url = citation.str("url") ?: return
        cited = true
        if (sources.addCited(url, citation.str("title"))) listener.onSources(sources.ranked())
    }

    private fun toolCalls(): List<ToolCall> = calls.entries.mapNotNull { (index, acc) ->
        val name = acc.name ?: return@mapNotNull null
        ToolCall(id = acc.id ?: "call_$index", name = name, arguments = acc.arguments.toString().ifBlank { "{}" })
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Finish reasons of a provider that gave up mid-answer (OC §2.5). */
        val GAVE_UP = setOf("error", "insufficient_system_resource", "aborted")

        /** Fields of a `reasoning_details` item that stream in pieces (text, summary, encrypted data, signature). */
        val REASONING_PIECES = setOf("text", "summary", "data", "signature")
    }
}

/**
 * OpenRouter's search cites as Markdown links (OC §7.5): `[label](https://…)`. The chat shows
 * plain text with sources as chips, so each link becomes its [label][MarkdownLinks.Link.label]
 * in the text and a source.
 */
internal object MarkdownLinks {
    data class Link(val label: String, val url: String)

    private val LINK = Regex("""\[([^\[\]\n]{1,200})]\((https?://[^\s()]+)\)""")

    /** [text] with every link replaced by its label, and the links in order. */
    fun extract(text: String): Pair<String, List<Link>> {
        val links = mutableListOf<Link>()
        val plain = LINK.replace(text) { match ->
            val label = match.groupValues[1].trim()
            links += Link(label, match.groupValues[2])
            label
        }
        return plain to links
    }
}
