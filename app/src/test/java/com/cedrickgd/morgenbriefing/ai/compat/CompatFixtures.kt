package com.cedrickgd.morgenbriefing.ai.compat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The mock server's address under the name `localhost` (on the own server's cleartext
 * allowlist, whatever name the machine gives the loopback address), without a trailing slash.
 */
internal fun MockWebServer.local(path: String): String = "http://localhost:$port$path".trimEnd('/')

/**
 * Chat Completions streams for MockWebServer, shaped like the documented chunks (OC §2.2–§2.4,
 * OpenRouter §7.6–§7.7, Mistral §3.4, DeepSeek §5.3): `data: {chunk}` lines, `:` comments,
 * `data: [DONE]` at the end. Fake keys only.
 */
internal object CompatSse {
    const val OPENROUTER_KEY = "sk-or-v1-test-invalid"
    const val MISTRAL_KEY = "mistral-test-invalid"
    const val DEEPSEEK_KEY = "sk-test-invalid"
    const val SERVER_KEY = "lm-studio-test-token"

    fun response(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString(""))

    fun comment(text: String = "OPENROUTER PROCESSING"): String = ": $text\n\n"

    fun done(): String = "data: [DONE]\n\n"

    fun chunk(json: String): String = "data: $json\n\n"

    /** One chunk with a `delta` (raw JSON fields) and optional finish reason and usage. */
    fun delta(delta: String, finish: String? = null, usage: String? = null, model: String = "google/gemini-3.8-flash"): String {
        val finishJson = finish?.let { "\"$it\"" } ?: "null"
        val usageJson = usage?.let { ""","usage":$it""" }.orEmpty()
        return chunk(
            """{"id":"gen-TEST","object":"chat.completion.chunk","created":1760000000,"model":"$model",""" +
                """"choices":[{"index":0,"delta":{$delta},"finish_reason":$finishJson}]$usageJson}""",
        )
    }

    /** Answer text as a string delta. */
    fun text(text: String, finish: String? = null, usage: String? = null): String =
        delta(""""role":"assistant","content":${str(text)}""", finish, usage)

    /** A tool-call fragment keyed by [index] (id and name only on the first one, OC §2.4). */
    fun toolCall(index: Int, id: String? = null, name: String? = null, arguments: String? = null): String {
        val function = listOfNotNull(name?.let { """"name":"$it"""" }, arguments?.let { """"arguments":${str(it)}""" }).joinToString(",")
        val fields = listOfNotNull(""""index":$index""", id?.let { """"id":"$it"""" }, id?.let { """"type":"function"""" }, """"function":{$function}""")
        return delta(""""tool_calls":[{${fields.joinToString(",")}}]""")
    }

    /** OpenRouter's extra accounting chunk: one choice, empty delta, repeated finish reason (OC §7.6). */
    fun usageChunk(usage: String, finish: String = "stop"): String = delta("", finish, usage)

    /** OpenRouter usage (OC §7.7) with the charged [cost]; [searchField] is one of the two documented names. */
    fun openRouterUsage(prompt: Long = 2_000, cached: Long = 0, completion: Long = 300, cost: Double = 0.0042, searches: Long = 1, searchField: String = "server_tool_use_details"): String =
        """{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion},"cost":$cost,"is_byok":false,""" +
            """"prompt_tokens_details":{"cached_tokens":$cached,"cache_write_tokens":0},"completion_tokens_details":{"reasoning_tokens":40},""" +
            """"$searchField":{"web_search_requests":$searches}}"""

    fun plainUsage(prompt: Long = 1_200, completion: Long = 200): String =
        """{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion}}"""

    fun httpError(status: Int, body: String = """{"error":{"code":$status,"message":"Test error."}}""", retryAfter: String? = null): MockResponse {
        val response = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        retryAfter?.let { response.setHeader("Retry-After", it) }
        return response
    }

    fun json(code: Int, body: String): MockResponse = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    fun str(text: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
}
