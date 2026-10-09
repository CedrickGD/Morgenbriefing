package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.ApiMessage
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration

class ResponsesChatEngineTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()
    private val slept = mutableListOf<Duration>()
    private val retry = RetryPolicy(sleep = { slept += it })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun backend(dialect: ResponsesDialect) = ResponsesBackend(http, dialect, retry)

    /** The debug base-URL override (G5) points every request at the mock server. */
    private fun auth(provider: AiProviderId, key: String) = ProviderAuth(provider, key, baseUrl = server.url("/v1").toString())

    private fun request(
        provider: AiProviderId = AiProviderId.OPENAI,
        key: String = ResponsesSse.OPENAI_KEY,
        model: String = "gpt-6.1-sol",
        webSearch: Boolean = true,
        history: List<ApiMessage> = listOf(ApiMessage(true, "Worum geht es?\n\n(Heute ist Freitag, 9. Oktober 2026, es ist 07:30 Uhr.)")),
    ) = ChatTurnRequest(
        auth = auth(provider, key),
        model = model,
        systemPrompt = AiPrompts.chatSystem(webSearch = webSearch),
        context = "Kontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.",
        history = history,
        webSearch = webSearch,
    )

    @Test
    fun `OpenAI streams text, citations, searched sources and usage`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created(),
                ResponsesSse.reasoning(0),
                ResponsesSse.webSearch(1, "EZB Zinsentscheid Oktober 2026", "https://www.tagesschau.de/wirtschaft/ezb-100.html", "https://www.spiegel.de/ezb"),
                ResponsesSse.message(
                    2, "Die EZB ", "senkt den Zins.",
                    citations = listOf("https://www.tagesschau.de/wirtschaft/ezb-100.html?utm_source=chatgpt.com" to "EZB senkt den Leitzins"),
                ),
                ResponsesSse.completed(usage = ResponsesSse.usage(input = 2_000, cached = 1_500, cacheWrite = 100, output = 300)),
            ),
        )
        val recorder = ChatRecorder()

        val result = backend(ResponsesDialect.OPENAI).chat.answer(request(), recorder, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals("Die EZB senkt den Zins.", result.text)
        assertEquals("Die EZB senkt den Zins.", recorder.text.toString())
        assertEquals(listOf("EZB Zinsentscheid Oktober 2026"), recorder.searches)
        // Cited first (tracking parameter dropped, so it merges with the searched URL).
        assertEquals(listOf(SourceRef("EZB senkt den Leitzins", "https://www.tagesschau.de/wirtschaft/ezb-100.html")), result.sources)
        assertEquals(result.sources, recorder.sources)
        assertEquals(1, usage.checks)
        val (model, tokens) = usage.records.single()
        assertEquals(AiProviderId.OPENAI, usage.providers.single())
        assertEquals("gpt-6.1-sol", model)
        assertEquals(400L, tokens.inputTokens)
        assertEquals(1_500L, tokens.cacheReadTokens)
        assertEquals(100L, tokens.cacheWriteTokens)
        assertEquals(300L, tokens.outputTokens)
        assertEquals(1L, tokens.webSearches)
        assertNull(tokens.reportedCostUsd)
    }

    @Test
    fun `only searched URLs become sources when nothing is cited`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created(),
                ResponsesSse.webSearch(0, "Wetter Leipzig", "https://www.mdr.de/wetter"),
                ResponsesSse.webSearch(1, null, action = """{"type":"open_page","url":"https://www.dwd.de/leipzig"}"""),
                ResponsesSse.message(2, "Sonnig."),
                ResponsesSse.completed(),
            ),
        )
        val recorder = ChatRecorder()
        val result = backend(ResponsesDialect.OPENAI).chat.answer(request(), recorder, usage)
        assertEquals(listOf(SourceRef("mdr.de", "https://www.mdr.de/wetter")), result.sources)
        assertEquals(listOf("https://www.dwd.de/leipzig"), recorder.fetches)
        // Only `search` actions are billed searches (OA §9).
        assertEquals(1L, usage.records.single().second.webSearches)
    }

    @Test
    fun `commentary preambles are not part of the answer`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created(),
                ResponsesSse.message(0, "Ich schaue kurz nach …", phase = "commentary"),
                ResponsesSse.webSearch(1, "EZB"),
                ResponsesSse.message(2, "Die Antwort."),
                ResponsesSse.completed(),
            ),
        )
        val recorder = ChatRecorder()
        val result = backend(ResponsesDialect.OPENAI).chat.answer(request(), recorder, usage)
        assertEquals("Die Antwort.", result.text)
        assertEquals("Die Antwort.", recorder.text.toString())
    }

    @Test
    fun `OpenAI request follows the API rules`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Ok."), ResponsesSse.completed()))
        val history = listOf(ApiMessage(true, "Frage 1"), ApiMessage(false, "Antwort 1"), ApiMessage(true, "Frage 2"))
        backend(ResponsesDialect.OPENAI).chat.answer(request(history = history), ChatRecorder(), usage)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/responses", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${ResponsesSse.OPENAI_KEY}", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("Morgenbriefing-Android/test", recorded.getHeader("User-Agent"))
        assertEquals("gpt-6.1-sol", body.string("model"))
        assertEquals("true", body.string("stream"))
        assertEquals("false", body.string("store"))
        assertEquals("low", body["reasoning"]!!.jsonObject.string("effort"))
        assertEquals("12000", body.string("max_output_tokens"))
        assertEquals(AiPrompts.chatSystem(webSearch = true), body.string("instructions"))
        // The context is a developer message before the history (cache breakpoint, OA §4).
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("developer", input[0].string("role"))
        val part = input[0]["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_text", part.string("type"))
        assertTrue(part.string("text")!!.startsWith("Kontext:"))
        assertEquals(listOf("user", "assistant", "user"), input.drop(1).map { it.string("role") })
        assertEquals("final_answer", input[2].string("phase"))
        assertNull(input[1]["phase"])
        assertEquals("Frage 2", input[3].string("content"))
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("web_search", tool.string("type"))
        assertEquals("medium", tool.string("search_context_size"))
        val location = tool["user_location"]!!.jsonObject
        assertEquals("approximate", location.string("type"))
        assertEquals("DE", location.string("country"))
        assertEquals("Leipzig", location.string("city"))
        assertEquals("Europe/Berlin", location.string("timezone"))
        assertEquals("auto", body.string("tool_choice"))
        assertEquals("4", body.string("max_tool_calls"))
        assertEquals(listOf("web_search_call.action.sources"), body["include"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", body["stream_options"]!!.jsonObject.string("include_obfuscation"))
        // Never next to a reasoning effort (OA §15), and no cache options in the chat.
        listOf("temperature", "top_p", "previous_response_id", "prompt_cache_options", "max_turns", "max_steps").forEach { assertNull(it, body[it]) }
    }

    @Test
    fun `without web search no tools are sent`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Ok."), ResponsesSse.completed()))
        backend(ResponsesDialect.OPENAI).chat.answer(request(webSearch = false), ChatRecorder(), usage)
        val body = server.takeJson().second!!
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        assertNull(body["max_tool_calls"])
        assertEquals(AiPrompts.chatSystem(webSearch = false), body.string("instructions"))
    }

    @Test
    fun `incomplete responses end truncated or refused`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Die EZB hat"), ResponsesSse.incomplete("max_output_tokens")))
        val truncated = backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.TRUNCATED, truncated.end)
        assertEquals("Die EZB hat", truncated.text)

        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.incomplete("content_filter")))
        val filtered = backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, filtered.end)
        assertEquals("", filtered.text)

        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.refusal(0), ResponsesSse.completed()))
        val refused = backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, refused.end)
        assertNull(refused.refusalCategory)
        assertEquals(3, usage.records.size)
    }

    @Test
    fun `mid-stream failures are mapped and keep the usage`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Anfang"), ResponsesSse.failed("server_error", ResponsesSse.usage(output = 12))))
        assertError(AiError.Overloaded) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        assertEquals(12L, usage.records.single().second.outputTokens)

        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.error("rate_limit_exceeded")))
        assertError(AiError.RateLimited) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.failed("misalignment_policy_violation")))
        assertError(AiError.Refusal("misalignment_policy_violation")) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        // Nothing is replayed after the stream started.
        assertEquals(3, server.requestCount)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `a stream that breaks off is a network error with estimated usage`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "x".repeat(300))))
        assertError(AiError.Network) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        val tokens = usage.records.single().second
        assertEquals(100L, tokens.outputTokens)
        assertTrue(tokens.inputTokens > 0)
    }

    @Test
    fun `HTTP errors before the stream`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.httpError(401, "invalid_api_key"))
        assertError(AiError.InvalidKey) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        server.enqueue(ResponsesSse.httpError(429, "credit_balance_exhausted", type = "insufficient_quota"))
        assertError(AiError.NoCredit) { backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage) }
        // xAI refuses a wrong key with 400 (OC §4.8): "Key ändern", not "Nochmal versuchen".
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(ResponsesSse.XAI_INCORRECT_KEY))
        assertError(AiError.InvalidKey) {
            backend(ResponsesDialect.XAI).chat.answer(request(AiProviderId.XAI, ResponsesSse.XAI_KEY, "grok-4.3"), ChatRecorder(), usage)
        }
        assertEquals(3, server.requestCount)
        assertTrue(slept.isEmpty())
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `slow_down and overload are retried before the first event`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.httpError(429, "slow_down", type = "rate_limit_error", retryAfter = "2"))
        server.enqueue(ResponsesSse.httpError(503, "server_is_overloaded", type = "service_unavailable_error"))
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Ok."), ResponsesSse.completed()))

        val result = backend(ResponsesDialect.OPENAI).chat.answer(request(), ChatRecorder(), usage)

        assertEquals("Ok.", result.text)
        assertEquals(3, server.requestCount)
        assertEquals(2, slept.size)
        assertEquals(kotlin.time.Duration.parse("2s"), slept[0])
        // The monthly limit is checked before every try; only the answered try is billed.
        assertEquals(3, usage.checks)
        assertEquals(1, usage.records.size)
    }

    @Test
    fun `xAI sends context in the instructions and reports its own cost`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created("grok-4.3"),
                ResponsesSse.reasoning(0),
                ResponsesSse.webSearch(1, "EZB Zins", "https://www.ecb.europa.eu/press"),
                ResponsesSse.message(2, "Die EZB senkt.", phase = null, citations = listOf("https://www.ecb.europa.eu/press" to "1")),
                ResponsesSse.completed(
                    "grok-4.3",
                    ResponsesSse.usage(
                        input = 3_000, cached = 1_000, output = 200,
                        extra = ""","num_server_side_tools_used":2,"server_side_tool_usage_details":{"web_search_calls":2},"cost_in_usd_ticks":37500000""",
                    ),
                ),
                ResponsesSse.done(),
            ),
        )
        val history = listOf(ApiMessage(true, "Frage 1"), ApiMessage(false, "Antwort 1"), ApiMessage(true, "Frage 2"))

        val result = backend(ResponsesDialect.XAI).chat.answer(request(AiProviderId.XAI, ResponsesSse.XAI_KEY, "grok-4.3", history = history), ChatRecorder(), usage)

        assertEquals("Die EZB senkt.", result.text)
        // xAI's citation title is the citation number: the host is shown instead.
        assertEquals(listOf(SourceRef("ecb.europa.eu", "https://www.ecb.europa.eu/press")), result.sources)
        val tokens = usage.records.single().second
        assertEquals(AiProviderId.XAI, usage.providers.single())
        assertEquals(2_000L, tokens.inputTokens)
        assertEquals(1_000L, tokens.cacheReadTokens)
        assertEquals(2L, tokens.webSearches)
        assertEquals(0.00375, tokens.reportedCostUsd!!, 1e-12)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/v1/responses", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${ResponsesSse.XAI_KEY}", recorded.getHeader("Authorization"))
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), input.map { it.string("role") })
        assertNull(input[1]["phase"])
        assertTrue(body.string("instructions")!!.endsWith("\n\nKontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026."))
        assertEquals(listOf("""{"type":"web_search"}"""), body["tools"]!!.jsonArray.map { it.toString() })
        assertEquals("3", body.string("max_turns"))
        assertEquals("8000", body.string("max_output_tokens"))
        assertEquals("false", body.string("store"))
        assertTrue(body["include"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("no_inline_citations"))
        listOf("stream_options", "max_tool_calls", "tool_choice", "temperature").forEach { assertNull(it, body[it]) }
    }

    @Test
    fun `Perplexity uses the Agent API, strips citation markers and reports its cost`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created("google/gemini-3.8-flash"),
                ResponsesSse.searchResults(
                    0, listOf("EZB Zinsentscheid"),
                    "EZB senkt Leitzins" to "https://www.tagesschau.de/ezb",
                    "Zinsen sinken" to "https://www.faz.net/zinsen",
                ),
                ResponsesSse.message(1, "Die EZB senkt[", "1] den Zins[web:", "2].", phase = null),
                ResponsesSse.completed(
                    "google/gemini-3.8-flash",
                    """{"input_tokens":1800,"input_tokens_details":{"cache_creation_input_tokens":0,"cache_read_input_tokens":800},""" +
                        """"output_tokens":90,"total_tokens":1890,"tool_calls_details":{"web_search":{"invocation":1,"cost_usd":0.001}},""" +
                        """"cost":{"currency":"USD","input_cost":0.00075,"output_cost":0.0003375,"total_cost":0.0021}}""",
                ),
            ),
        )
        val recorder = ChatRecorder()

        val result = backend(ResponsesDialect.PERPLEXITY).chat.answer(
            request(AiProviderId.PERPLEXITY, ResponsesSse.PPLX_KEY, "google/gemini-3.8-flash"), recorder, usage,
        )

        assertEquals("Die EZB senkt den Zins.", result.text)
        assertEquals("Die EZB senkt den Zins.", recorder.text.toString())
        assertEquals(listOf("EZB Zinsentscheid"), recorder.searches)
        assertEquals(
            listOf(SourceRef("EZB senkt Leitzins", "https://www.tagesschau.de/ezb"), SourceRef("Zinsen sinken", "https://www.faz.net/zinsen")),
            result.sources,
        )
        val tokens = usage.records.single().second
        assertEquals("google/gemini-3.8-flash", usage.records.single().first)
        assertEquals(1_000L, tokens.inputTokens)
        assertEquals(800L, tokens.cacheReadTokens)
        assertEquals(1L, tokens.webSearches)
        assertEquals(0.0021, tokens.reportedCostUsd!!, 1e-12)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/v1/agent", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${ResponsesSse.PPLX_KEY}", recorded.getHeader("Authorization"))
        val message = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("message", message.string("type"))
        assertEquals("user", message.string("role"))
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("web_search", tool.string("type"))
        assertEquals("fast", tool.string("search_type"))
        assertEquals("5", tool.string("max_results"))
        assertEquals("DE", tool["user_location"]!!.jsonObject.string("country"))
        assertNull(tool["filters"])
        assertEquals("3", body.string("max_steps"))
        assertEquals("4000", body.string("max_output_tokens"))
        assertEquals("de", body.string("language_preference"))
        listOf("store", "reasoning", "include", "stream_options", "max_tool_calls", "max_turns").forEach { assertNull(it, body[it]) }
    }

    @Test
    fun `citation markers are stripped across chunk boundaries`() {
        val stripper = CitationMarkerStripper()
        val out = listOf("A[", "1]B[web:", "12]C [x] D[web", "site] E[", "3").joinToString("") { stripper.feed(it) } + stripper.flush()
        assertEquals("ABC [x] D[website] E[3", out)
        val long = CitationMarkerStripper()
        assertEquals("[1234567890123", long.feed("[1234567890123"))
        assertEquals("", long.flush())
        assertFalse(CitationMarkerStripper().feed("Text [2][3].").contains("["))
    }

    @Test
    fun `tracking parameters are dropped from source URLs`() {
        assertEquals("https://a.de/x", ResponsesEventParser.withoutTracking("https://a.de/x?utm_source=chatgpt.com"))
        assertEquals("https://a.de/x?id=2#top", ResponsesEventParser.withoutTracking("https://a.de/x?utm_source=chatgpt.com&id=2#top"))
        assertEquals("https://a.de/x", ResponsesEventParser.withoutTracking("https://a.de/x"))
        assertNull(ResponsesEventParser.titleOf("3"))
        assertEquals("Titel", ResponsesEventParser.titleOf(" Titel "))
    }

    private suspend fun assertError(expected: AiError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: AiException) {
            assertEquals(expected, e.error)
        }
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
