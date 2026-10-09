package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.LiveResearchListener
import com.cedrickgd.morgenbriefing.ai.PublishReply
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * Responses-API streams for MockWebServer, shaped like the documented examples (OA §5.2 event
 * table and the function-call stream from S11, OC §4.4–4.6 for xAI, OC §6.4–6.6 for
 * Perplexity). Every event repeats its type in `data` (OA §5.1). Fake keys only.
 */
internal object ResponsesSse {
    const val OPENAI_KEY = "sk-test-invalid"
    const val XAI_KEY = "xai-test-invalid"
    const val PPLX_KEY = "pplx-test-invalid"

    /** xAI's real answer (HTTP 400) to a wrong key, on `/api-key` and `/responses` alike (observed 2026-10-09, OC §4.8). */
    const val XAI_INCORRECT_KEY =
        """{"code":"invalid-argument","error":"Incorrect API key provided. You can obtain an API key from https://console.x.ai."}"""

    private var sequence = 0

    fun response(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(events.joinToString(""))

    /** An SSE event whose name is the `type` of [json]. */
    fun event(json: String): String {
        val type = Json.parseToJsonElement(json).jsonObject["type"]!!.jsonPrimitive.content
        return "event: $type\ndata: $json\n\n"
    }

    fun done(): String = "data: [DONE]\n\n"

    fun created(model: String = "gpt-6.1-sol"): String = event(
        """{"type":"response.created","sequence_number":${seq()},"response":{"id":"resp_TEST","object":"response","status":"in_progress",""" +
            """"model":"$model","output":[],"usage":null}}""",
    )

    /** A reasoning item (store: false brings its encrypted content, OA §7.3). */
    fun reasoning(index: Int, id: String = "rs_$index"): String {
        val item = """{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"gAAAAABoENCRYPTED$index"}"""
        return added(index, """{"type":"reasoning","id":"$id","summary":[]}""") + itemDone(index, item)
    }

    /** A finished web search with the URLs it consulted (`include: web_search_call.action.sources`). */
    fun webSearch(index: Int, query: String?, vararg sources: String, id: String = "ws_$index", action: String? = null): String {
        val sourceList = sources.joinToString(",") { """{"type":"url","url":${str(it)}}""" }
        val actionJson = action ?: query?.let { """{"type":"search","query":${str(it)},"sources":[$sourceList]}""" }
        val item = """{"type":"web_search_call","id":"$id","status":"completed"${actionJson?.let { ""","action":$it""" }.orEmpty()}}"""
        return added(index, """{"type":"web_search_call","id":"$id","status":"in_progress"}""") +
            event("""{"type":"response.web_search_call.in_progress","sequence_number":${seq()},"output_index":$index,"item_id":"$id"}""") +
            event("""{"type":"response.web_search_call.searching","sequence_number":${seq()},"output_index":$index,"item_id":"$id"}""") +
            event("""{"type":"response.web_search_call.completed","sequence_number":${seq()},"output_index":$index,"item_id":"$id"}""") +
            itemDone(index, item)
    }

    /**
     * An assistant message streamed in [chunks]; [citations] (url to title) arrive as
     * annotation events and again in the finished item.
     */
    fun message(
        index: Int,
        vararg chunks: String,
        id: String = "msg_$index",
        phase: String? = "final_answer",
        citations: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        val phaseJson = phase?.let { ""","phase":"$it"""" }.orEmpty()
        append(added(index, """{"type":"message","id":"$id","status":"in_progress","role":"assistant"$phaseJson,"content":[]}"""))
        append(event("""{"type":"response.content_part.added","sequence_number":${seq()},"item_id":"$id","output_index":$index,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}"""))
        chunks.forEach { chunk ->
            append(event("""{"type":"response.output_text.delta","sequence_number":${seq()},"item_id":"$id","output_index":$index,"content_index":0,"delta":${str(chunk)},"logprobs":[]}"""))
        }
        val annotations = citations.mapIndexed { i, (url, title) ->
            """{"type":"url_citation","start_index":0,"end_index":${10 + i},"url":${str(url)},"title":${str(title)}}"""
        }
        annotations.forEachIndexed { i, annotation ->
            append(event("""{"type":"response.output_text.annotation.added","sequence_number":${seq()},"item_id":"$id","output_index":$index,"content_index":0,"annotation_index":$i,"annotation":$annotation}"""))
        }
        val full = chunks.joinToString("")
        append(event("""{"type":"response.output_text.done","sequence_number":${seq()},"item_id":"$id","output_index":$index,"content_index":0,"text":${str(full)}}"""))
        append(
            itemDone(
                index,
                """{"type":"message","id":"$id","status":"completed","role":"assistant"$phaseJson,""" +
                    """"content":[{"type":"output_text","text":${str(full)},"annotations":[${annotations.joinToString(",")}]}]}""",
            ),
        )
    }

    /**
     * A function call. With [deltas] it streams like OpenAI (S11: added → argument deltas →
     * arguments.done → item done); without, it arrives whole in `output_item.done` (xAI, Perplexity).
     */
    fun functionCall(index: Int, callId: String, arguments: String, name: String = "publish_articles", id: String = "fc_$index", deltas: Boolean = true): String =
        buildString {
            val doneItem = """{"type":"function_call","id":"$id","call_id":"$callId","name":"$name","arguments":${str(arguments)},"status":"completed"}"""
            if (deltas) {
                append(added(index, """{"type":"function_call","id":"$id","call_id":"$callId","name":"$name","arguments":""}"""))
                val half = arguments.length / 2
                listOf(arguments.substring(0, half), arguments.substring(half)).forEach { part ->
                    append(event("""{"type":"response.function_call_arguments.delta","sequence_number":${seq()},"item_id":"$id","output_index":$index,"delta":${str(part)}}"""))
                }
                append(event("""{"type":"response.function_call_arguments.done","sequence_number":${seq()},"item_id":"$id","output_index":$index,"arguments":${str(arguments)}}"""))
            }
            append(itemDone(index, doneItem))
        }

    /** Perplexity's `search_results` output item (OC §6.5). */
    fun searchResults(index: Int, queries: List<String>, vararg results: Pair<String, String>): String {
        val items = results.mapIndexed { i, (title, url) ->
            """{"id":${i + 1},"title":${str(title)},"url":${str(url)},"snippet":"…","date":"2026-10-09","source":"web"}"""
        }.joinToString(",")
        val item = """{"type":"search_results","id":"sr_$index","queries":[${queries.joinToString(",") { str(it) }}],"results":[$items]}"""
        return added(index, """{"type":"search_results","id":"sr_$index"}""") + itemDone(index, item)
    }

    fun refusal(index: Int, text: String = "Dabei kann ich nicht helfen."): String =
        added(index, """{"type":"message","id":"msg_r","status":"in_progress","role":"assistant","content":[]}""") +
            event("""{"type":"response.refusal.delta","sequence_number":${seq()},"item_id":"msg_r","output_index":$index,"content_index":0,"delta":${str(text)}}""") +
            event("""{"type":"response.refusal.done","sequence_number":${seq()},"item_id":"msg_r","output_index":$index,"content_index":0,"refusal":${str(text)}}""")

    /** OpenAI-style usage (S9): cached and cache-write tokens are part of `input_tokens`. */
    fun usage(input: Long = 2_000, cached: Long = 0, cacheWrite: Long = 0, output: Long = 300, reasoning: Long = 120, extra: String = ""): String =
        """{"input_tokens":$input,"input_tokens_details":{"cached_tokens":$cached,"cache_write_tokens":$cacheWrite},""" +
            """"output_tokens":$output,"output_tokens_details":{"reasoning_tokens":$reasoning},"total_tokens":${input + output}$extra}"""

    fun completed(model: String = "gpt-6.1-sol", usage: String = usage()): String = event(
        """{"type":"response.completed","sequence_number":${seq()},"response":{"id":"resp_TEST","object":"response","status":"completed",""" +
            """"model":"$model","output":[],"usage":$usage}}""",
    )

    fun incomplete(reason: String, usage: String = usage()): String = event(
        """{"type":"response.incomplete","sequence_number":${seq()},"response":{"id":"resp_TEST","status":"incomplete",""" +
            """"incomplete_details":{"reason":"$reason"},"model":"gpt-6.1-sol","usage":$usage}}""",
    )

    fun failed(code: String, usage: String = "null"): String = event(
        """{"type":"response.failed","sequence_number":${seq()},"response":{"id":"resp_TEST","status":"failed",""" +
            """"error":{"code":"$code","message":"The server had an error."},"model":"gpt-6.1-sol","usage":$usage}}""",
    )

    fun error(code: String?): String =
        event("""{"type":"error","sequence_number":${seq()},"code":${code?.let { "\"$it\"" } ?: "null"},"message":"Something went wrong.","param":null}""")

    fun httpError(status: Int, code: String?, type: String = "invalid_request_error", retryAfter: String? = null): MockResponse {
        val response = MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"Test error.","type":"$type","param":null,"code":${code?.let { "\"$it\"" } ?: "null"}}}""")
        retryAfter?.let { response.setHeader("Retry-After", it) }
        return response
    }

    private fun added(index: Int, item: String): String =
        event("""{"type":"response.output_item.added","sequence_number":${seq()},"output_index":$index,"item":$item}""")

    private fun itemDone(index: Int, item: String): String =
        event("""{"type":"response.output_item.done","sequence_number":${seq()},"output_index":$index,"item":$item}""")

    private fun seq(): Int = ++sequence

    fun str(text: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
}

/** Collects what a chat engine streams. */
internal class ChatRecorder : ChatTurnListener {
    val text = StringBuilder()
    val searches = mutableListOf<String>()
    val fetches = mutableListOf<String>()
    val toolCalls = mutableListOf<String>()
    var sources: List<SourceRef> = emptyList()

    override fun onText(delta: String) {
        text.append(delta)
    }

    override fun onSearch(query: String) {
        searches += query
    }

    override fun onFetch(url: String) {
        fetches += url
    }

    override fun onToolCall(name: String) {
        toolCalls += name
    }

    override fun onSources(sources: List<SourceRef>) {
        this.sources = sources
    }
}

/** A Live listener that answers `publish_articles` calls with [replies] in order (then "ok"). */
internal class LiveRecorder(vararg replies: PublishReply) : LiveResearchListener {
    private val queue = ArrayDeque(replies.toList())
    val published = mutableListOf<String>()
    val searches = mutableListOf<String>()
    val toolCalls = mutableListOf<String>()

    override suspend fun onPublish(rawInput: String): PublishReply {
        published += rawInput
        return queue.removeFirstOrNull() ?: PublishReply(true, "ok")
    }

    override fun onSearch(query: String) {
        searches += query
    }

    override fun onToolCall(name: String) {
        toolCalls += name
    }
}

/** The next request MockWebServer received, with its JSON body (null for a GET). */
internal fun MockWebServer.takeJson(): Pair<RecordedRequest, JsonObject?> {
    val recorded = takeRequest(5, TimeUnit.SECONDS) ?: error("no request")
    val body = recorded.body.readUtf8()
    return recorded to body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
}

