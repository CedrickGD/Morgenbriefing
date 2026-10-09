package com.cedrickgd.morgenbriefing.ai.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse

/**
 * `streamGenerateContent?alt=sse` streams for MockWebServer, shaped like the documented
 * `GenerateContentResponse` (GE §5.2: every `data:` is one complete response, no event names,
 * no `[DONE]`, the stream ends at EOF) and the google.rpc error bodies observed with fake keys
 * (GE §12.1). Fake keys only.
 */
internal object GeminiSse {
    /** Classic key shape (`AIza…`). */
    const val KEY = "AIza-test-invalid"

    /** Newer AI Studio key shape (`AQ.…`, GE §13). */
    const val AQ_KEY = "AQ.test-invalid"

    const val SUGGESTIONS = """<style>.container{display:flex}</style><div class="container"><a class="chip" href="https://www.google.com/search?q=EZB+Zins">EZB Zins</a></div>"""

    fun response(vararg chunks: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString(""))

    /** One SSE event with a `GenerateContentResponse`. */
    fun chunk(json: String): String = "data: $json\n\n"

    /** A chunk with answer text parts; [thought] parts come first and are thought summaries. */
    fun text(vararg texts: String, thought: String? = null, finish: String? = null, grounding: String? = null, usage: String? = null): String {
        val parts = buildList {
            thought?.let { add("""{"text":${str(it)},"thought":true}""") }
            texts.forEach { add("""{"text":${str(it)}}""") }
        }.joinToString(",")
        return candidate(""""content":{"role":"model","parts":[$parts]}""", finish, grounding, usage)
    }

    /** A chunk that only carries grounding (or a finish reason) without new text. */
    fun candidate(content: String?, finish: String? = null, grounding: String? = null, usage: String? = null): String {
        val fields = listOfNotNull(
            content,
            finish?.let { """"finishReason":"$it"""" },
            grounding?.let { """"groundingMetadata":$it""" },
            """"index":0""",
        ).joinToString(",")
        val extra = usage?.let { ""","usageMetadata":$it""" }.orEmpty()
        return chunk("""{"candidates":[{$fields}]$extra,"modelVersion":"gemini-3.8-flash","responseId":"resp-TEST"}""")
    }

    /**
     * `groundingMetadata` (GE §6.2): [chunks] are (domain title, redirect uri) like the docs
     * example, [queries] the searches, [suggestions] the Search Suggestions HTML.
     */
    fun grounding(queries: List<String> = emptyList(), chunks: List<Pair<String, String>> = emptyList(), suggestions: String? = null): String {
        val fields = buildList {
            if (queries.isNotEmpty()) add(""""webSearchQueries":[${queries.joinToString(",") { str(it) }}]""")
            suggestions?.let { add(""""searchEntryPoint":{"renderedContent":${str(it)}}""") }
            if (chunks.isNotEmpty()) {
                add(""""groundingChunks":[${chunks.joinToString(",") { (title, uri) -> """{"web":{"uri":${str(uri)},"title":${str(title)}}}""" }}]""")
            }
        }
        return "{${fields.joinToString(",")}}"
    }

    fun usage(prompt: Long = 2_412, cached: Long = 0, candidates: Long = 380, thoughts: Long = 210, toolUse: Long = 0): String =
        """{"promptTokenCount":$prompt,"cachedContentTokenCount":$cached,"candidatesTokenCount":$candidates,""" +
            """"thoughtsTokenCount":$thoughts,"toolUsePromptTokenCount":$toolUse,"totalTokenCount":${prompt + candidates + thoughts}}"""

    /** google.rpc error body (GE §12.1), as an object or wrapped in an array. */
    fun rpc(code: Int, status: String, message: String, reason: String? = null, retryDelay: String? = null, array: Boolean = false): String {
        val details = buildList {
            reason?.let {
                add("""{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$it","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}}""")
            }
            retryDelay?.let { add("""{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$it"}""") }
        }.joinToString(",")
        val body = """{"error":{"code":$code,"message":${str(message)},"status":"$status","details":[$details]}}"""
        return if (array) "[$body]" else body
    }

    /**
     * An error answer. Gemini labels even a pre-stream error `text/event-stream` but sends a
     * plain JSON body (GE §5.1, observed).
     */
    fun httpError(code: Int, body: String, eventStream: Boolean = true): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", if (eventStream) "text/event-stream" else "application/json; charset=UTF-8")
        .setBody(body)

    fun str(text: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
}
