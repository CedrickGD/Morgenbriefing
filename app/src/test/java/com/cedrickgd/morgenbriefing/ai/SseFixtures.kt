package com.cedrickgd.morgenbriefing.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse

/**
 * Recorded-style Server-Sent-Event streams in the official Messages streaming format
 * (message_start → content_block_start/delta/stop … → message_delta → message_stop, with the
 * occasional ping), for MockWebServer. No real API traffic and no real keys anywhere.
 */
internal object Sse {
    const val TEST_KEY = "test-key"

    fun response(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(events.joinToString(""))

    fun event(type: String, json: String): String = "event: $type\ndata: $json\n\n"

    fun ping(): String = event("ping", """{"type": "ping"}""")

    fun messageStart(
        model: String = "claude-sonnet-5-5",
        input: Long = 1_200,
        cacheWrite: Long = 0,
        cacheRead: Long = 0,
    ): String = event(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_01TEST","type":"message","role":"assistant","model":"$model",""" +
            """"content":[],"stop_reason":null,"stop_sequence":null,"stop_details":null,""" +
            """"usage":{"input_tokens":$input,"cache_creation_input_tokens":$cacheWrite,"cache_read_input_tokens":$cacheRead,""" +
            """"output_tokens":1,"service_tier":"standard"}}}""",
    )

    /** A complete text block streamed in [chunks]. */
    fun textBlock(index: Int, vararg chunks: String): String = buildString {
        append(event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}"""))
        chunks.forEach { chunk ->
            append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${str(chunk)}}}"""))
        }
        append(blockStop(index))
    }

    /** A text block that cites a web search result first, then streams [text]. */
    fun citedTextBlock(index: Int, url: String, title: String, text: String): String = buildString {
        append(event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":"","citations":[]}}"""))
        append(
            event(
                "content_block_delta",
                """{"type":"content_block_delta","index":$index,"delta":{"type":"citations_delta","citation":""" +
                    """{"type":"web_search_result_location","cited_text":"Die EZB senkt den Leitzins.","url":${str(url)},""" +
                    """"title":${str(title)},"encrypted_index":"Eo8BCioIAhgBIiQ"}}}""",
            ),
        )
        append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${str(text)}}}"""))
        append(blockStop(index))
    }

    fun thinkingBlock(index: Int): String = buildString {
        append(event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":{"type":"thinking","thinking":"","signature":""}}"""))
        append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"thinking_delta","thinking":""}}"""))
        append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"signature_delta","signature":"EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds"}}"""))
        append(blockStop(index))
    }

    /** server_tool_use for web_search, its input streamed as JSON fragments. */
    fun webSearchCall(index: Int, id: String, query: String): String = serverToolCall(index, id, "web_search", """{"query": ${str(query)}}""")

    fun webFetchCall(index: Int, id: String, url: String): String = serverToolCall(index, id, "web_fetch", """{"url": ${str(url)}}""")

    private fun serverToolCall(index: Int, id: String, name: String, input: String): String = buildString {
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":$index,"content_block":{"type":"server_tool_use","id":"$id","name":"$name","input":{},"caller":{"type":"direct"}}}""",
            ),
        )
        val half = input.length / 2
        listOf(input.substring(0, half), input.substring(half)).forEach { part ->
            append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${str(part)}}}"""))
        }
        append(blockStop(index))
    }

    fun webSearchResult(index: Int, toolUseId: String, vararg results: Pair<String, String>): String = buildString {
        val items = results.joinToString(",") { (title, url) ->
            """{"type":"web_search_result","title":${str(title)},"url":${str(url)},"encrypted_content":"EqgfCioIARgBIiQ3YTAwMjY1Mi1mZjM5","page_age":"vor 2 Stunden"}"""
        }
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":$index,"content_block":{"type":"web_search_tool_result","tool_use_id":"$toolUseId","content":[$items],"caller":{"type":"direct"}}}""",
            ),
        )
        append(blockStop(index))
    }

    fun webFetchResult(index: Int, toolUseId: String, url: String, title: String): String = buildString {
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":$index,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"$toolUseId",""" +
                    """"content":{"type":"web_fetch_result","url":${str(url)},"retrieved_at":"2026-10-05T12:00:00Z",""" +
                    """"content":{"type":"document","source":{"type":"text","media_type":"text/plain","data":"Seiteninhalt"},"title":${str(title)}}},""" +
                    """"caller":{"type":"direct"}}}""",
            ),
        )
        append(blockStop(index))
    }

    /** A client tool call (`tool_use`) whose input JSON arrives in two fragments. */
    fun toolUse(index: Int, id: String, name: String, inputJson: String): String = buildString {
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{},"caller":{"type":"direct"}}}""",
            ),
        )
        val half = inputJson.length / 2
        listOf(inputJson.substring(0, half), inputJson.substring(half)).forEach { part ->
            append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${str(part)}}}"""))
        }
        append(blockStop(index))
    }

    /** The refusal-fallback switch marker (a block type the SDK does not model). */
    fun fallbackMarker(index: Int): String = buildString {
        append(
            event(
                "content_block_start",
                """{"type":"content_block_start","index":$index,"content_block":{"type":"fallback","from":{"model":"claude-sonnet-5-5"},"to":{"model":"claude-sonnet-5"}}}""",
            ),
        )
        append(blockStop(index))
    }

    fun blockStop(index: Int): String = event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    fun messageDelta(
        stopReason: String,
        output: Long = 200,
        webSearches: Long? = null,
        stopDetails: String = "null",
        extraUsage: String = "",
    ): String {
        val server = webSearches?.let { ""","server_tool_use":{"web_search_requests":$it,"web_fetch_requests":0}""" }.orEmpty()
        return event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null,"stop_details":$stopDetails},""" +
                """"usage":{"output_tokens":$output$server$extraUsage}}""",
        )
    }

    fun messageStop(): String = event("message_stop", """{"type":"message_stop"}""")

    /** A whole plain answer. */
    fun simpleAnswer(vararg chunks: String, stopReason: String = "end_turn"): MockResponse =
        response(messageStart(), ping(), textBlock(0, *chunks), messageDelta(stopReason), messageStop())

    fun error(status: Int, type: String, message: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":${str(message)}},"request_id":"req_TEST"}""")

    private fun str(text: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
}

/** Records every request's usage and can simulate the monthly limit. */
internal class RecordingUsage(var limitReached: Boolean = false) : UsageSink {
    val records = mutableListOf<Pair<String, TokenUsage>>()
    var checks = 0

    override suspend fun beforeRequest() {
        checks++
        if (limitReached) throw AiBlockedException(AiBlock.LIMIT_REACHED)
    }

    override suspend fun record(modelId: String, usage: TokenUsage) {
        synchronized(records) { records += modelId to usage }
    }
}
