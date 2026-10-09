package com.cedrickgd.morgenbriefing.ai.compat

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
import com.cedrickgd.morgenbriefing.ai.openai.ChatRecorder
import com.cedrickgd.morgenbriefing.ai.openai.string
import com.cedrickgd.morgenbriefing.ai.openai.takeJson
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
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
import kotlin.time.Duration.Companion.seconds

class CompatChatEngineTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()
    private val slept = mutableListOf<Duration>()
    private val retry = RetryPolicy(sleep = { slept += it })
    private val backends = HashMap<CompatDialect, CompatBackend>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun backend(dialect: CompatDialect) = backends.getOrPut(dialect) { CompatBackend(http, dialect, retry) }

    /** The debug base-URL override (G5) — or the own server's address — points at the mock server. */
    private fun auth(provider: AiProviderId, key: String?, path: String) = ProviderAuth(provider, key, baseUrl = server.local(path))

    private fun request(
        auth: ProviderAuth,
        model: String,
        webSearch: Boolean,
        history: List<ApiMessage> = listOf(ApiMessage(true, "Worum geht es?")),
    ) = ChatTurnRequest(
        auth = auth,
        model = model,
        systemPrompt = AiPrompts.chatSystem(webSearch = webSearch),
        context = "Kontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.",
        history = history,
        webSearch = webSearch,
    )

    private fun openRouter(webSearch: Boolean = true, history: List<ApiMessage> = listOf(ApiMessage(true, "Worum geht es?"))) =
        request(auth(AiProviderId.OPENROUTER, CompatSse.OPENROUTER_KEY, "/api/v1"), "google/gemini-3.8-flash", webSearch, history)

    private fun mistral() = request(auth(AiProviderId.MISTRAL, CompatSse.MISTRAL_KEY, "/v1"), "mistral-medium-3-5", webSearch = false)

    private fun deepSeek() = request(auth(AiProviderId.DEEPSEEK, CompatSse.DEEPSEEK_KEY, "/"), "deepseek-flash", webSearch = false)

    private fun custom(key: String? = null, model: String = "llama4:8b") =
        request(auth(AiProviderId.CUSTOM, key, "/"), model, webSearch = false)

    private fun ok(text: String = "Ok.") = CompatSse.response(CompatSse.text(text, finish = "stop", usage = CompatSse.plainUsage()), CompatSse.done())

    @Test
    fun `OpenRouter streams text, citations and its own cost`() = runBlocking<Unit> {
        server.enqueue(
            CompatSse.response(
                CompatSse.comment(),
                CompatSse.text("Die EZB "),
                CompatSse.comment(),
                CompatSse.delta(
                    """"content":"senkt den Zins.","annotations":[{"type":"url_citation","url_citation":""" +
                        """{"url":"https://www.tagesschau.de/ezb","title":"EZB senkt Leitzins","start_index":0,"end_index":10}}]""",
                ),
                CompatSse.delta("", finish = "stop"),
                // The accounting chunk repeats the finish reason: no second end.
                CompatSse.usageChunk(CompatSse.openRouterUsage(prompt = 2_000, cached = 1_500, completion = 300, cost = 0.0042, searches = 2)),
                CompatSse.done(),
            ),
        )
        val recorder = ChatRecorder()

        val result = backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), recorder, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals("Die EZB senkt den Zins.", result.text)
        assertEquals("Die EZB senkt den Zins.", recorder.text.toString())
        assertEquals(listOf(SourceRef("EZB senkt Leitzins", "https://www.tagesschau.de/ezb")), result.sources)
        val (model, tokens) = usage.records.single()
        assertEquals(AiProviderId.OPENROUTER, usage.providers.single())
        assertEquals("google/gemini-3.8-flash", model)
        assertEquals(500L, tokens.inputTokens)
        assertEquals(1_500L, tokens.cacheReadTokens)
        assertEquals(300L, tokens.outputTokens)
        assertEquals(2L, tokens.webSearches)
        assertEquals(0.0042, tokens.reportedCostUsd!!, 1e-12)
    }

    @Test
    fun `OpenRouter request carries the search tool, attribution and budget`() = runBlocking<Unit> {
        server.enqueue(ok())
        val history = listOf(ApiMessage(true, "Frage 1"), ApiMessage(false, "Antwort 1"), ApiMessage(true, "Frage 2"))
        backend(CompatDialect.OPENROUTER).chat.answer(openRouter(history = history), ChatRecorder(), usage)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/api/v1/chat/completions", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${CompatSse.OPENROUTER_KEY}", recorded.getHeader("Authorization"))
        assertEquals("https://github.com/CedrickGD/Morgenbriefing", recorded.getHeader("HTTP-Referer"))
        assertEquals("Morgenbriefing", recorded.getHeader("X-OpenRouter-Title"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("google/gemini-3.8-flash", body.string("model"))
        assertEquals("true", body.string("stream"))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.string("role") })
        assertEquals("${AiPrompts.chatSystem(webSearch = true)}\n\nKontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.", messages[0].string("content"))
        assertEquals("Frage 2", messages[3].string("content"))
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("openrouter:web_search", tool.string("type"))
        val parameters = tool["parameters"]!!.jsonObject
        assertEquals("auto", parameters.string("engine"))
        assertEquals("5", parameters.string("max_results"))
        assertEquals("medium", parameters.string("search_context_size"))
        val location = parameters["user_location"]!!.jsonObject
        assertEquals("approximate", location.string("type"))
        assertEquals("DE", location.string("country"))
        assertEquals("Leipzig", location.string("city"))
        assertEquals("Europe/Berlin", location.string("timezone"))
        assertEquals("3", body.string("max_tool_calls"))
        assertEquals("low", body["reasoning"]!!.jsonObject.string("effort"))
        assertEquals("8000", body.string("max_tokens"))
        // Deprecated (usage always comes) or not for this dialect.
        listOf("stream_options", "temperature", "thinking", "plugins").forEach { assertNull(it, body[it]) }
    }

    @Test
    fun `OpenRouter Markdown links become sources and plain labels`() = runBlocking<Unit> {
        server.enqueue(
            CompatSse.response(
                CompatSse.text("Die EZB senkt den Zins ([tagesschau.de](https://www.tagesschau.de/ezb)) – "),
                CompatSse.text("laut [faz.net](https://www.faz.net/zins?x=1).", finish = "stop"),
                CompatSse.usageChunk(CompatSse.openRouterUsage(searches = 1, searchField = "server_tool_use")),
                CompatSse.done(),
            ),
        )
        val recorder = ChatRecorder()
        val result = backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), recorder, usage)

        assertEquals("Die EZB senkt den Zins (tagesschau.de) – laut faz.net.", result.text)
        val expected = listOf(SourceRef("tagesschau.de", "https://www.tagesschau.de/ezb"), SourceRef("faz.net", "https://www.faz.net/zins?x=1"))
        assertEquals(expected, result.sources)
        assertEquals(expected, recorder.sources)
        // Both documented names of the search count are read (OC §7.7).
        assertEquals(1L, usage.records.single().second.webSearches)
    }

    @Test
    fun `an OpenRouter error chunk fails the answer without a retry`() = runBlocking<Unit> {
        // "The error can be the only event" (OC §7.6).
        server.enqueue(
            CompatSse.response(
                CompatSse.chunk(
                    """{"id":"gen-TEST","object":"chat.completion.chunk","model":"google/gemini-3.8-flash","provider":"Google",""" +
                        """"error":{"code":502,"message":"Provider returned error","metadata":{"error_type":"provider_unavailable"}},""" +
                        """"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""",
                ),
                CompatSse.done(),
            ),
        )
        assertError(AiError.Overloaded) { backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage) }
        server.enqueue(
            CompatSse.response(
                CompatSse.text("Anfang"),
                CompatSse.chunk("""{"error":{"code":429,"message":"Rate limited upstream"},"choices":[{"index":0,"delta":{},"finish_reason":"error"}]}"""),
            ),
        )
        assertError(AiError.RateLimited) { backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage) }
        assertEquals(2, server.requestCount)
        assertTrue(slept.isEmpty())
        assertEquals(2, usage.records.size)
    }

    @Test
    fun `a refused max_tool_calls is dropped once and for good`() = runBlocking<Unit> {
        server.enqueue(CompatSse.httpError(400, """{"error":{"code":400,"message":"Invalid request: unknown field max_tool_calls"}}"""))
        server.enqueue(ok())
        server.enqueue(ok())

        assertEquals("Ok.", backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage).text)
        assertEquals("3", server.takeJson().second!!.string("max_tool_calls"))
        val retried = server.takeJson().second!!
        assertNull(retried["max_tool_calls"])
        assertEquals("openrouter:web_search", retried["tools"]!!.jsonArray.single().jsonObject.string("type"))

        backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage)
        assertNull(server.takeJson().second!!["max_tool_calls"])
        assertEquals(3, server.requestCount)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `OpenRouter without web search sends no tools`() = runBlocking<Unit> {
        server.enqueue(ok())
        backend(CompatDialect.OPENROUTER).chat.answer(openRouter(webSearch = false), ChatRecorder(), usage)
        val body = server.takeJson().second!!
        assertNull(body["tools"])
        assertNull(body["max_tool_calls"])
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject.string("content")!!.startsWith(AiPrompts.chatSystem(webSearch = false)))
    }

    @Test
    fun `Mistral gets only its own fields and its thinking chunks stay hidden`() = runBlocking<Unit> {
        server.enqueue(
            CompatSse.response(
                CompatSse.delta(""""role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"Erst überlegen …"}]}]"""),
                CompatSse.delta(""""content":[{"type":"text","text":"Die EZB "}]"""),
                CompatSse.delta(""""content":"senkt."""", finish = "stop", usage = CompatSse.plainUsage(prompt = 900, completion = 40)),
                CompatSse.done(),
            ),
        )
        val recorder = ChatRecorder()
        val result = backend(CompatDialect.MISTRAL).chat.answer(mistral(), recorder, usage)

        assertEquals("Die EZB senkt.", result.text)
        assertEquals("Die EZB senkt.", recorder.text.toString())
        assertTrue(result.sources.isEmpty())
        val tokens = usage.records.single().second
        assertEquals(900L, tokens.inputTokens)
        assertEquals(40L, tokens.outputTokens)
        assertNull(tokens.reportedCostUsd)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/v1/chat/completions", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${CompatSse.MISTRAL_KEY}", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("HTTP-Referer"))
        // Mistral's schema has additionalProperties: false (OC §2.1).
        assertEquals(setOf("model", "stream", "messages", "max_tokens", "temperature"), body.keys)
        assertEquals("0.3", body.string("temperature"))
        assertEquals("4000", body.string("max_tokens"))
        assertEquals(AiPrompts.chatSystem(webSearch = false) + "\n\nKontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.", body["messages"]!!.jsonArray[0].jsonObject.string("content"))
    }

    @Test
    fun `DeepSeek turns thinking off, asks for usage and reads its cache hits`() = runBlocking<Unit> {
        server.enqueue(
            CompatSse.response(
                CompatSse.comment("keep-alive"),
                CompatSse.delta(""""role":"assistant","content":null,"reasoning_content":"Hmm …""""),
                CompatSse.text("Antwort."),
                CompatSse.delta(
                    """"content":""""",
                    finish = "stop",
                    usage = """{"prompt_tokens":3000,"completion_tokens":120,"total_tokens":3120,"prompt_cache_hit_tokens":2000,"prompt_cache_miss_tokens":1000}""",
                ),
                CompatSse.done(),
            ),
        )
        val result = backend(CompatDialect.DEEPSEEK).chat.answer(deepSeek(), ChatRecorder(), usage)

        assertEquals("Antwort.", result.text)
        val (model, tokens) = usage.records.single()
        assertEquals("deepseek-flash", model)
        assertEquals(1_000L, tokens.inputTokens)
        assertEquals(2_000L, tokens.cacheReadTokens)
        assertEquals(120L, tokens.outputTokens)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/chat/completions", recorded.requestUrl!!.encodedPath)
        assertEquals("disabled", body["thinking"]!!.jsonObject.string("type"))
        assertEquals("true", body["stream_options"]!!.jsonObject.string("include_usage"))
        assertEquals("4000", body.string("max_tokens"))
        listOf("temperature", "tools", "reasoning").forEach { assertNull(it, body[it]) }
    }

    @Test
    fun `finish reasons map to cut-off, refused and given-up answers`() = runBlocking<Unit> {
        server.enqueue(CompatSse.response(CompatSse.text("Die EZB hat", finish = "length"), CompatSse.done()))
        val truncated = backend(CompatDialect.DEEPSEEK).chat.answer(deepSeek(), ChatRecorder(), usage)
        assertEquals(TurnEnd.TRUNCATED, truncated.end)
        assertEquals("Die EZB hat", truncated.text)

        server.enqueue(CompatSse.response(CompatSse.text("", finish = "content_filter"), CompatSse.done()))
        val filtered = backend(CompatDialect.DEEPSEEK).chat.answer(deepSeek(), ChatRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, filtered.end)
        assertEquals("content_filter", filtered.refusalCategory)

        server.enqueue(CompatSse.response(CompatSse.text("Halb", finish = "insufficient_system_resource"), CompatSse.done()))
        assertError(AiError.Overloaded) { backend(CompatDialect.DEEPSEEK).chat.answer(deepSeek(), ChatRecorder(), usage) }
        server.enqueue(CompatSse.response(CompatSse.text("Halb", finish = "error"), CompatSse.done()))
        assertError(AiError.Overloaded) { backend(CompatDialect.MISTRAL).chat.answer(mistral(), ChatRecorder(), usage) }

        // Without a finish reason, [DONE] still ends the answer properly; EOF does not.
        server.enqueue(CompatSse.response(CompatSse.text("Fertig."), CompatSse.done()))
        assertEquals(TurnEnd.COMPLETE, backend(CompatDialect.CUSTOM).chat.answer(custom(), ChatRecorder(), usage).end)
        server.enqueue(CompatSse.response(CompatSse.text("x".repeat(300))))
        assertError(AiError.Network) { backend(CompatDialect.CUSTOM).chat.answer(custom(), ChatRecorder(), usage) }
        val estimated = usage.records.last().second
        assertEquals(100L, estimated.outputTokens)
        assertTrue(estimated.inputTokens > 0)
        assertEquals(6, usage.records.size)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `HTTP errors before the stream`() = runBlocking<Unit> {
        server.enqueue(CompatSse.httpError(401))
        assertError(AiError.InvalidKey) { backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage) }
        server.enqueue(CompatSse.httpError(402, """{"error":{"message":"Insufficient Balance","type":"invalid_request_error","code":"invalid_request_error"}}"""))
        assertError(AiError.NoCredit) { backend(CompatDialect.DEEPSEEK).chat.answer(deepSeek(), ChatRecorder(), usage) }
        server.enqueue(CompatSse.httpError(402, """{"object":"error","message":"No payment method on account","type":"invalid_request_error","param":null,"code":"1700"}"""))
        assertError(AiError.NoCredit) { backend(CompatDialect.MISTRAL).chat.answer(mistral(), ChatRecorder(), usage) }
        server.enqueue(CompatSse.httpError(404, """{"error":"model 'llama9' not found"}"""))
        assertError(AiError.ModelUnavailable("llama9")) { backend(CompatDialect.CUSTOM).chat.answer(custom(model = "llama9"), ChatRecorder(), usage) }
        server.enqueue(CompatSse.httpError(403, """{"error":{"code":403,"message":"Flagged","metadata":{"reasons":["violence"]}}}"""))
        assertError(AiError.Unknown("OpenRouter hat die Anfrage abgelehnt (Moderation oder Rechte).")) {
            backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage)
        }
        assertEquals(5, server.requestCount)
        assertTrue(slept.isEmpty())
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `rate limits, a full OpenRouter budget and overload are retried before the first event`() = runBlocking<Unit> {
        server.enqueue(CompatSse.httpError(429, retryAfter = "2"))
        // 402 with Retry-After: an in-flight budget check, temporary (OC §7.9).
        server.enqueue(CompatSse.httpError(402, retryAfter = "1"))
        server.enqueue(ok())
        assertEquals("Ok.", backend(CompatDialect.OPENROUTER).chat.answer(openRouter(), ChatRecorder(), usage).text)
        assertEquals(listOf(2.seconds, 1.seconds), slept)

        server.enqueue(CompatSse.httpError(503))
        server.enqueue(ok())
        assertEquals("Ok.", backend(CompatDialect.MISTRAL).chat.answer(mistral(), ChatRecorder(), usage).text)
        assertEquals(5, server.requestCount)
        assertEquals(5, usage.checks)
        assertEquals(2, usage.records.size)
    }

    @Test
    fun `the own server needs no key, gets its v1 path and a model`() = runBlocking<Unit> {
        server.enqueue(ok("Hallo vom NAS."))
        val result = backend(CompatDialect.CUSTOM).chat.answer(custom(), ChatRecorder(), usage)
        assertEquals("Hallo vom NAS.", result.text)
        val (recorded, body) = server.takeJson()
        body!!
        // http://localhost:<port> without a path → /v1 (Ollama, LM Studio).
        assertEquals("/v1/chat/completions", recorded.requestUrl!!.encodedPath)
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("llama4:8b", body.string("model"))
        assertEquals("0.3", body.string("temperature"))
        assertEquals(setOf("model", "stream", "messages", "max_tokens", "temperature"), body.keys)
        assertEquals("llama4:8b", usage.records.single().first)

        server.enqueue(ok())
        backend(CompatDialect.CUSTOM).chat.answer(custom(key = CompatSse.SERVER_KEY), ChatRecorder(), usage)
        assertEquals("Bearer ${CompatSse.SERVER_KEY}", server.takeJson().first.getHeader("Authorization"))

        // No model chosen yet: nothing is sent.
        assertError(AiError.Unknown(CompatChatEngine.NO_MODEL_TEXT)) { backend(CompatDialect.CUSTOM).chat.answer(custom(model = " "), ChatRecorder(), usage) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an own server that does not answer is unreachable`() = runBlocking<Unit> {
        // Port 1 on loopback: nothing listens there, the connection is refused (twice retried).
        val gone = request(ProviderAuth(AiProviderId.CUSTOM, null, baseUrl = "http://localhost:1"), "llama4:8b", webSearch = false)
        assertError(AiError.ServerUnreachable) { backend(CompatDialect.CUSTOM).chat.answer(gone, ChatRecorder(), usage) }
        assertEquals(2, slept.size)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `only OpenRouter has Live`() {
        CompatDialect.entries.forEach { dialect ->
            assertEquals(dialect.name, dialect == CompatDialect.OPENROUTER, backend(dialect).live != null)
        }
        assertFalse(CompatDialect.MISTRAL.webSearch || CompatDialect.DEEPSEEK.webSearch || CompatDialect.CUSTOM.webSearch)
    }

    @Test
    fun `Markdown links are found only when complete`() {
        val (plain, links) = MarkdownLinks.extract("A [x](https://a.de/p) B [kein Link](ftp://b) C [y](http://c.de) [z](https://d.de/(1))")
        assertEquals("A x B [kein Link](ftp://b) C y [z](https://d.de/(1))", plain)
        assertEquals(listOf(MarkdownLinks.Link("x", "https://a.de/p"), MarkdownLinks.Link("y", "http://c.de")), links)
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
