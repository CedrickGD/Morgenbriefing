package com.cedrickgd.morgenbriefing.ai.openai

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
import com.cedrickgd.morgenbriefing.ai.http.ProviderErrors
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.SseEnd
import com.cedrickgd.morgenbriefing.ai.http.SseEvent
import com.cedrickgd.morgenbriefing.ai.http.array
import com.cedrickgd.morgenbriefing.ai.http.double
import com.cedrickgd.morgenbriefing.ai.http.long
import com.cedrickgd.morgenbriefing.ai.http.obj
import com.cedrickgd.morgenbriefing.ai.http.objects
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.ai.http.streamSse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap

/**
 * A response's `usage` object as [TokenUsage] (§7.1): `input_tokens` minus cache reads and
 * writes is the uncached input; OpenAI's search count comes from the stream ([searchItems]),
 * xAI's and Perplexity's from their usage details, which also carry the charged cost (xAI
 * `cost_in_usd_ticks`, 10,000,000,000 ticks = 1 $, OC §4.6; Perplexity `cost.total_cost`).
 */
internal fun ResponsesDialect.usageOf(u: JsonObject, searchItems: Long = 0, streamedChars: Long = 0): TokenUsage {
    val details = u.obj("input_tokens_details")
    val cacheRead = details?.long("cache_read_input_tokens") ?: details?.long("cached_tokens") ?: 0L
    val cacheWrite = details?.long("cache_write_tokens") ?: details?.long("cache_creation_input_tokens") ?: 0L
    val input = u.long("input_tokens") ?: 0L
    val searches = when (this) {
        ResponsesDialect.XAI -> u.obj("server_side_tool_usage_details")?.long("web_search_calls")
        ResponsesDialect.PERPLEXITY -> u.obj("tool_calls_details")?.obj("web_search")?.long("invocation")
        ResponsesDialect.OPENAI -> null
    } ?: searchItems
    val reported = when (this) {
        ResponsesDialect.XAI -> u.double("cost_in_usd_ticks")?.div(1e10) ?: u.double("cost_in_nano_usd")?.div(1e9)
        ResponsesDialect.PERPLEXITY -> u.obj("cost")?.double("total_cost")
        ResponsesDialect.OPENAI -> null
    }
    return TokenUsage(
        inputTokens = (input - cacheRead - cacheWrite).coerceAtLeast(0),
        outputTokens = u.long("output_tokens") ?: (streamedChars / 3),
        cacheWriteTokens = cacheWrite,
        cacheReadTokens = cacheRead,
        webSearches = searches,
        reportedCostUsd = reported,
    )
}

/** A client function call of one response, complete. */
internal data class FunctionCall(val callId: String, val name: String, val arguments: String)

/**
 * Outcome of one streamed Responses request. Text and sources went to the [AnswerText] and
 * [SourceCollector] the request was run with.
 *
 * @property replayItems the response's output items as received (filtered per dialect), for
 *   the next request of a tool loop (`store: false`, OA §7.3).
 */
internal class ResponsesTurn(
    val end: TurnEnd,
    val refusalCategory: String?,
    val functionCalls: List<FunctionCall>,
    val replayItems: List<JsonObject>,
)

/**
 * Runs one streamed Responses request: retries per [retry] before the first event, feeds every
 * event to a [ResponsesEventParser], and records the request's usage with the [UsageSink] —
 * also when it fails or is cancelled after the stream started (estimated when the provider sent
 * no usage).
 */
internal class ResponsesStreamer(
    private val http: AiHttp,
    private val dialect: ResponsesDialect,
    private val retry: RetryPolicy,
) {
    suspend fun run(
        auth: ProviderAuth,
        body: JsonObject,
        requestModel: String,
        listener: ChatTurnListener,
        usage: UsageSink,
        sources: SourceCollector,
        text: AnswerText,
    ): ResponsesTurn {
        val request = http.post(ResponsesRequests.url(dialect, auth), body, ResponsesRequests.headers(auth), stream = true)
        val requestChars = body.toString().length
        val mapper = ProviderErrors.mapper(auth, requestModel)
        return retry.run(dialect.provider.key, mapper::map) { attempt ->
            usage.beforeRequest()
            val parser = ResponsesEventParser(dialect, listener, sources, text)
            val end = try {
                http.streamSse(request) { event ->
                    attempt.delivered = true
                    parser.onEvent(event)
                }
            } catch (e: Throwable) {
                // Whatever streamed was billed: record it even when cancelled or failed.
                if (attempt.delivered) withContext(NonCancellable) { record(parser, usage, requestModel, requestChars) }
                throw e
            }
            record(parser, usage, requestModel, requestChars)
            parser.finish(end)
        }
    }

    private suspend fun record(parser: ResponsesEventParser, usage: UsageSink, requestModel: String, requestChars: Int) {
        usage.record(dialect.provider, parser.model ?: requestModel, parser.tokenUsage(requestChars))
    }
}

/**
 * The Responses stream state machine (OA §5.2/§5.3, docs/AI-PROVIDERS.md §7.1): parses each
 * event's `data` and switches on its `type`; unknown types and fields are ignored.
 *
 * Text of messages in the `commentary` phase (a preamble before tool calls) is not part of the
 * answer. Citations come from `url_citation` annotations (xAI's titles are numbers and are
 * dropped), searched URLs from `web_search_call.action.sources`, Perplexity's results from its
 * `search_results` item. Function calls are taken from `response.output_item.done`
 * (authoritative; xAI and Perplexity send nothing else).
 */
internal class ResponsesEventParser(
    private val dialect: ResponsesDialect,
    private val listener: ChatTurnListener,
    private val sources: SourceCollector,
    private val text: AnswerText,
) {
    /** The model that answered (from `response.created` / the terminal event). */
    var model: String? = null
        private set

    private enum class Kind { MESSAGE, FUNCTION_CALL, WEB_SEARCH, OTHER }

    private class Item(val kind: Kind, val commentary: Boolean = false, val name: String? = null, val callId: String? = null) {
        val arguments = StringBuilder()
        var argumentsDone: String? = null
    }

    private enum class Terminal { COMPLETED, INCOMPLETE }

    private val items = HashMap<String, Item>()
    private val doneItems = TreeMap<Int, JsonObject>()
    private val doneCalls = LinkedHashMap<String, FunctionCall>()
    private val announcedCalls = HashSet<String>()
    private val announcedQueries = HashSet<String>()
    private val stripper = if (dialect.stripCitationMarkers) CitationMarkerStripper() else null
    private var textItem: String? = null
    private var terminal: Terminal? = null
    private var incompleteReason: String? = null
    private var refused = false
    private var usage: JsonObject? = null
    private var completedOutput: List<JsonObject> = emptyList()
    private var streamedChars = 0L
    private var searchItems = 0L
    private var nextIndex = 0

    /** Handles one event; false after a terminal event (the caller stops reading). */
    fun onEvent(event: SseEvent): Boolean {
        val data = try {
            JSON.parseToJsonElement(event.data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return true
        when (data.str("type") ?: event.event) {
            "response.created", "response.in_progress", "response.queued" -> data.obj("response")?.str("model")?.let { model = it }
            "response.output_item.added" -> data.obj("item")?.let(::onItemAdded)
            "response.output_text.delta" -> onTextDelta(data.str("item_id"), data.str("delta").orEmpty())
            "response.output_text.annotation.added" -> {
                if (data.str("item_id")?.let(items::get)?.commentary != true) data.obj("annotation")?.let(::onAnnotation)
            }
            "response.function_call_arguments.delta" -> data.str("delta")?.let { delta ->
                streamedChars += delta.length
                if (!dialect.functionCallsOnlyInItemDone) data.str("item_id")?.let(items::get)?.arguments?.append(delta)
            }
            "response.function_call_arguments.done" -> {
                if (!dialect.functionCallsOnlyInItemDone) data.str("item_id")?.let(items::get)?.argumentsDone = data.str("arguments")
            }
            "response.refusal.delta", "response.refusal.done" -> refused = true
            "response.reasoning.search_queries" -> queriesOf(data).forEach(::announceQuery)
            "response.output_item.done" -> data.obj("item")?.let { onItemDone(it, data.long("output_index")?.toInt()) }
            "response.completed" -> {
                onResponse(data.obj("response"))
                terminal = Terminal.COMPLETED
                return false
            }
            "response.incomplete" -> {
                val response = data.obj("response")
                onResponse(response)
                incompleteReason = response?.obj("incomplete_details")?.str("reason")
                terminal = Terminal.INCOMPLETE
                return false
            }
            "response.failed" -> {
                val response = data.obj("response")
                onResponse(response)
                throw AiException(ProviderErrors.responsesStreamError(dialect.provider, response?.obj("error")?.str("code")))
            }
            "error" -> throw AiException(ProviderErrors.responsesStreamError(dialect.provider, data.str("code") ?: data.obj("error")?.str("code")))
            else -> Unit
        }
        return true
    }

    /**
     * The turn after the stream ended with [end]. A stream that stopped without a terminal event
     * is a broken connection ([AiError.Network]) — unless the server closed it with `[DONE]`.
     */
    fun finish(end: SseEnd): ResponsesTurn {
        stripper?.flush()?.let(::emitText)
        val terminal = terminal ?: if (end == SseEnd.DONE) Terminal.COMPLETED else throw AiException(AiError.Network)
        val turnEnd = when {
            refused -> TurnEnd.REFUSED
            terminal == Terminal.COMPLETED -> TurnEnd.COMPLETE
            incompleteReason == "max_output_tokens" -> TurnEnd.TRUNCATED
            incompleteReason == "content_filter" -> TurnEnd.REFUSED
            else -> TurnEnd.INCOMPLETE
        }
        val replay = doneItems.values.toList().ifEmpty { completedOutput }
        return ResponsesTurn(
            end = turnEnd,
            refusalCategory = if (turnEnd == TurnEnd.REFUSED && !refused) incompleteReason else null,
            functionCalls = functionCalls(),
            replayItems = dialect.replayTypes?.let { types -> replay.filter { it.str("type") in types } } ?: replay,
        )
    }

    /**
     * Usage of this request (TokenUsage convention: uncached input). Without a usage object (the
     * stream broke off) it is estimated: the request at ≈ 4 characters per input token, the
     * streamed text and arguments at ≈ 3 per output token.
     */
    fun tokenUsage(requestChars: Int): TokenUsage =
        usage?.let { dialect.usageOf(it, searchItems, streamedChars) }
            ?: TokenUsage(inputTokens = requestChars / 4L, outputTokens = streamedChars / 3, webSearches = searchItems)

    private fun onResponse(response: JsonObject?) {
        if (response == null) return
        response.str("model")?.let { model = it }
        response.obj("usage")?.let { usage = it }
        response.objects("output").takeIf { it.isNotEmpty() }?.let { completedOutput = it }
    }

    private fun onItemAdded(item: JsonObject) {
        val id = item.str("id") ?: return
        val type = item.str("type")
        val kind = kindOf(type)
        items[id] = Item(kind, commentary = item.str("phase") == "commentary", name = item.str("name"), callId = item.str("call_id"))
        if (kind != Kind.MESSAGE) text.nonTextBlock()
        if (kind == Kind.FUNCTION_CALL) item.str("name")?.let { announceCall(id, it) }
    }

    private fun onTextDelta(itemId: String?, delta: String) {
        if (delta.isEmpty()) return
        val item = itemId?.let(items::get)
        if (item?.commentary == true) return
        if (itemId != textItem) {
            textItem = itemId
            text.startTextBlock()?.let(listener::onText)
        }
        streamedChars += delta.length
        emitText(stripper?.feed(delta) ?: delta)
    }

    private fun emitText(delta: String) {
        if (delta.isEmpty()) return
        text.append(delta)
        listener.onText(delta)
    }

    private fun onAnnotation(annotation: JsonObject) {
        if (annotation.str("type") != "url_citation") return
        val url = annotation.str("url") ?: return
        if (sources.addCited(withoutTracking(url), titleOf(annotation.str("title")))) listener.onSources(sources.ranked())
    }

    private fun onItemDone(item: JsonObject, outputIndex: Int?) {
        val index = outputIndex ?: nextIndex
        nextIndex = maxOf(nextIndex, index + 1)
        doneItems[index] = item
        val id = item.str("id")
        when (item.str("type")) {
            "function_call" -> {
                val name = item.str("name") ?: return
                val callId = item.str("call_id") ?: id ?: return
                announceCall(id ?: callId, name)
                val arguments = item.str("arguments").orEmpty()
                // Without argument deltas this is the only place the arguments stream (usage estimate).
                if (dialect.functionCallsOnlyInItemDone) streamedChars += arguments.length
                doneCalls[id ?: callId] = FunctionCall(callId, name, arguments.ifBlank { "{}" })
            }
            "web_search_call" -> onSearchDone(item.obj("action"))
            "message" -> {
                if (item.str("phase") == "commentary") return
                item.objects("content").forEach { part ->
                    if (part.str("type") == "refusal") refused = true
                    part.objects("annotations").forEach(::onAnnotation)
                }
            }
            "search_results" -> {
                queriesOf(item).forEach(::announceQuery)
                var changed = false
                item.objects("results").forEach { result ->
                    changed = sources.addFetched(result.str("url"), titleOf(result.str("title"))) || changed
                }
                if (changed) listener.onSources(sources.ranked())
            }
            else -> Unit
        }
    }

    /** A finished search item: count it (only `search` actions are billed, OA §9), say what it did, keep its URLs. */
    private fun onSearchDone(action: JsonObject?) {
        when (action?.str("type")) {
            null, "search" -> {
                searchItems++
                val query = action?.str("query") ?: action?.array("queries")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
                listener.onSearch(query?.trim().orEmpty())
                query?.trim()?.takeIf { it.isNotEmpty() }?.let(announcedQueries::add)
            }
            "open_page" -> action?.str("url")?.let(listener::onFetch)
            else -> Unit
        }
        var changed = false
        action?.objects("sources")?.forEach { source -> changed = sources.addSearched(source.str("url")?.let(::withoutTracking), null) || changed }
        if (changed) listener.onSources(sources.ranked())
    }

    private fun announceQuery(query: String) {
        val clean = query.trim()
        if (clean.isNotEmpty() && announcedQueries.add(clean)) listener.onSearch(clean)
    }

    private fun announceCall(id: String, name: String) {
        if (announcedCalls.add(id)) listener.onToolCall(name)
    }

    /**
     * The function calls in output order: finished items first; a call whose item never finished
     * but whose arguments streamed completely (OpenAI) counts too; else the terminal event's
     * output list.
     */
    private fun functionCalls(): List<FunctionCall> {
        val calls = LinkedHashMap(doneCalls)
        if (!dialect.functionCallsOnlyInItemDone && terminal == Terminal.COMPLETED) {
            items.forEach { (id, item) ->
                if (item.kind == Kind.FUNCTION_CALL && id !in calls && item.name != null && item.callId != null) {
                    val args = item.argumentsDone ?: item.arguments.toString()
                    if (args.isNotBlank()) calls[id] = FunctionCall(item.callId, item.name, args)
                }
            }
        }
        if (calls.isEmpty()) {
            completedOutput.filter { it.str("type") == "function_call" }.forEach { item ->
                val name = item.str("name") ?: return@forEach
                val callId = item.str("call_id") ?: return@forEach
                calls[item.str("id") ?: callId] = FunctionCall(callId, name, item.str("arguments").orEmpty().ifBlank { "{}" })
            }
        }
        return calls.values.toList()
    }

    private fun kindOf(type: String?): Kind = when (type) {
        "message" -> Kind.MESSAGE
        "function_call" -> Kind.FUNCTION_CALL
        "web_search_call" -> Kind.WEB_SEARCH
        else -> Kind.OTHER
    }

    /** Perplexity: `queries` of a `search_results` item or a `response.reasoning.search_queries` event. */
    private fun queriesOf(o: JsonObject): List<String> =
        (o.array("queries") ?: o.obj("data")?.array("queries")).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    internal companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** A citation title worth showing: xAI sends the citation number instead (OC §4.5). */
        fun titleOf(title: String?): String? = title?.trim()?.takeUnless { it.isEmpty() || it.all(Char::isDigit) }

        /** Drops `utm_*` parameters (OpenAI adds `utm_source=chatgpt.com`, OA §6.2) so the same page dedupes. */
        fun withoutTracking(url: String): String {
            val query = url.indexOf('?')
            if (query < 0) return url
            val hash = url.indexOf('#', query).takeIf { it >= 0 } ?: url.length
            val kept = url.substring(query + 1, hash).split('&').filter { it.isNotEmpty() && !it.startsWith("utm_", ignoreCase = true) }
            return url.substring(0, query) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
        }
    }
}
