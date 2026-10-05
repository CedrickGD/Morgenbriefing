package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeClients
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClaudeChatEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: ClaudeBackend
    private val usage = RecordingUsage()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        backend = ClaudeBackend(ClaudeClients(baseUrl = server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(history: List<ApiMessage> = listOf(ApiMessage(true, "Worum geht es?\n\n(Heute ist Montag, 5. Oktober 2026, es ist 14:20 Uhr.)"))) =
        ChatTurnRequest(
            apiKey = Sse.TEST_KEY,
            model = "claude-sonnet-5-5",
            systemPrompt = AiPrompts.CHAT_SYSTEM,
            context = "Kontext: ein Artikel aus dem Morgenbriefing vom Montag, 5. Oktober 2026.",
            history = history,
        )

    private class Recorder : ChatTurnListener {
        val text = StringBuilder()
        val searches = mutableListOf<String>()
        val fetches = mutableListOf<String>()
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
        override fun onSources(sources: List<SourceRef>) {
            this.sources = sources
        }
    }

    private fun takeBody(): Pair<RecordedRequest, JsonObject> {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")
        return recorded to Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    @Test
    fun `streams text deltas and returns the complete answer`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Die EZB ", "hat den Zins ", "gesenkt."))
        val recorder = Recorder()

        val result = backend.chat.answer(request(), recorder, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals("Die EZB hat den Zins gesenkt.", result.text)
        assertEquals("Die EZB hat den Zins gesenkt.", recorder.text.toString())
        assertTrue(result.sources.isEmpty())
        assertEquals(1, usage.checks)
        assertEquals(1, usage.records.size)
        val (model, tokens) = usage.records.single()
        assertEquals("claude-sonnet-5-5", model)
        assertEquals(1_200L, tokens.inputTokens)
        assertEquals(200L, tokens.outputTokens)
    }

    @Test
    fun `request opts into the refusal fallback and follows the API rules`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Ok."))
        backend.chat.answer(request(), Recorder(), usage)

        val (recorded, body) = takeBody()
        assertEquals("/v1/messages", recorded.requestUrl!!.encodedPath)
        assertEquals(Sse.TEST_KEY, recorded.getHeader("x-api-key"))
        assertTrue(recorded.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        // Thinking is never sent (cannot be disabled on Sonnet/Opus 5.5); effort is the control.
        assertNull(body["thinking"])
        assertNull(body["tool_choice"])
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Stable prefix: system prompt, then the cached context block; automatic caching on top.
        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertNull(system[0].jsonObject["cache_control"])
        assertEquals("ephemeral", system[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("web_search_20260209", "web_fetch_20260209"), tools)
        assertFalse(tools.any { it.startsWith("code_execution") })
        // The date travels in the user message, never in the cached prefix.
        assertFalse(system.toString().contains("Heute ist"))
        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages.last().toString().contains("Heute ist"))
    }

    @Test
    fun `web search shows activity and collects cited sources`() = runBlocking<Unit> {
        val url = "https://www.tagesschau.de/wirtschaft/ezb-zinsentscheid-100.html"
        server.enqueue(
            Sse.response(
                Sse.messageStart(),
                Sse.thinkingBlock(0),
                Sse.textBlock(1, "Ich schaue kurz nach."),
                Sse.webSearchCall(2, "srvtoolu_01", "EZB Zinsentscheid Oktober 2026"),
                Sse.webSearchResult(3, "srvtoolu_01", "EZB senkt Leitzins" to url, "Zinsen: Was jetzt gilt" to "https://www.zdfheute.de/wirtschaft/zinsen-100.html"),
                Sse.webFetchCall(4, "srvtoolu_02", url),
                Sse.webFetchResult(5, "srvtoolu_02", url, "EZB senkt Leitzins – tagesschau.de"),
                Sse.citedTextBlock(6, url, "EZB senkt Leitzins", "Laut tagesschau.de senkt die EZB den Leitzins"),
                Sse.textBlock(7, " auf 1,75 Prozent."),
                Sse.messageDelta("end_turn", output = 340, webSearches = 1),
                Sse.messageStop(),
            ),
        )
        val recorder = Recorder()

        val result = backend.chat.answer(request(), recorder, usage)

        assertEquals(listOf("EZB Zinsentscheid Oktober 2026"), recorder.searches)
        assertEquals(listOf(url), recorder.fetches)
        // Text after the tool calls starts a new paragraph; citation-split blocks stay glued.
        assertEquals("Ich schaue kurz nach.\n\nLaut tagesschau.de senkt die EZB den Leitzins auf 1,75 Prozent.", result.text)
        // Cited/fetched pages win over plain search hits.
        assertEquals(1, result.sources.size)
        assertEquals(url, result.sources.single().url)
        // The cited title wins over the fetched page title.
        assertEquals("EZB senkt Leitzins", result.sources.single().name)
        assertEquals(result.sources, recorder.sources)
        assertEquals(1L, usage.records.single().second.webSearches)
    }

    @Test
    fun `pause_turn is continued with the assistant content appended`() = runBlocking<Unit> {
        server.enqueue(
            Sse.response(
                Sse.messageStart(input = 900),
                Sse.textBlock(0, "Moment, ich suche."),
                Sse.webSearchCall(1, "srvtoolu_77", "Landtag Sachsen Haushalt"),
                Sse.messageDelta("pause_turn", output = 40, webSearches = 1),
                Sse.messageStop(),
            ),
        )
        server.enqueue(
            Sse.response(
                Sse.messageStart(input = 1_400, cacheRead = 900),
                Sse.webSearchResult(0, "srvtoolu_77", "Landtag beschließt Haushalt" to "https://www.mdr.de/nachrichten/sachsen/haushalt-100.html"),
                Sse.textBlock(1, "Der Landtag hat den Haushalt beschlossen."),
                Sse.messageDelta("end_turn", output = 60),
                Sse.messageStop(),
            ),
        )
        val recorder = Recorder()

        val result = backend.chat.answer(request(), recorder, usage)

        assertEquals("Moment, ich suche.\n\nDer Landtag hat den Haushalt beschlossen.", result.text)
        assertEquals(2, server.requestCount)
        takeBody()
        val (_, second) = takeBody()
        val messages = second["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val last = messages.last().jsonObject
        assertEquals("assistant", last["role"]!!.jsonPrimitive.content)
        val types = (last["content"] as JsonArray).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "server_tool_use"), types)
        assertEquals("Landtag Sachsen Haushalt", (last["content"] as JsonArray)[1].jsonObject["input"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        // Both requests are billed, the second with its cache read.
        assertEquals(2, usage.records.size)
        assertEquals(900L, usage.records[1].second.cacheReadTokens)
        assertEquals(2, usage.checks)
    }

    @Test
    fun `refusal returns the category and drops partial text`() = runBlocking<Unit> {
        server.enqueue(
            Sse.response(
                Sse.messageStart(),
                Sse.textBlock(0, "Hier ist"),
                Sse.messageDelta("refusal", output = 3, stopDetails = """{"type":"refusal","category":"cyber","explanation":"Policy"}"""),
                Sse.messageStop(),
            ),
        )

        val result = backend.chat.answer(request(), Recorder(), usage)

        assertEquals(TurnEnd.REFUSED, result.end)
        assertEquals("cyber", result.refusalCategory)
        assertEquals("", result.text)
    }

    @Test
    fun `max_tokens marks the answer as truncated`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Erster Teil", stopReason = "max_tokens"))
        val result = backend.chat.answer(request(), Recorder(), usage)
        assertEquals(TurnEnd.TRUNCATED, result.end)
        assertEquals("Erster Teil", result.text)
    }

    @Test
    fun `a fallback marker in the stream does not break the answer`() = runBlocking<Unit> {
        server.enqueue(
            Sse.response(
                Sse.messageStart(model = "claude-sonnet-5"),
                Sse.fallbackMarker(0),
                Sse.textBlock(1, "Antwort vom Ersatzmodell."),
                Sse.messageDelta(
                    "end_turn",
                    output = 20,
                    extraUsage = ""","iterations":[{"type":"message","model":"claude-sonnet-5-5","input_tokens":500,"output_tokens":0},""" +
                        """{"type":"fallback_message","model":"claude-sonnet-5","input_tokens":500,"output_tokens":20}]""",
                ),
                Sse.messageStop(),
            ),
        )

        val result = backend.chat.answer(request(), Recorder(), usage)

        assertEquals("Antwort vom Ersatzmodell.", result.text)
        // Every attempt is billed with its own model.
        assertEquals(listOf("claude-sonnet-5-5", "claude-sonnet-5"), usage.records.map { it.first })
        assertEquals(20L, usage.records[1].second.outputTokens)
    }

    @Test
    fun `401 maps to an invalid key`() = runBlocking<Unit> {
        server.enqueue(Sse.error(401, "authentication_error", "invalid x-api-key"))
        try {
            backend.chat.answer(request(), Recorder(), usage)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiError.InvalidKey, e.error)
        }
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `overloaded stream error maps to overloaded`() = runBlocking<Unit> {
        server.enqueue(
            Sse.response(
                Sse.messageStart(),
                Sse.textBlock(0, "Teil"),
                Sse.event("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""),
            ),
        )
        try {
            backend.chat.answer(request(), Recorder(), usage)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiError.Overloaded, e.error)
        }
        // The input of the broken-off request is still counted.
        assertEquals(1_200L, usage.records.single().second.inputTokens)
    }

    @Test
    fun `the monthly limit stops the request before it is sent`() = runBlocking<Unit> {
        usage.limitReached = true
        try {
            backend.chat.answer(request(), Recorder(), usage)
            fail("expected AiBlockedException")
        } catch (e: AiBlockedException) {
            assertEquals(AiBlock.LIMIT_REACHED, e.block)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling closes the stream and keeps the usage`() = runBlocking<Unit> {
        val firstText = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(Sse.messageStart() + Sse.textBlock(0, "Anfang") + Sse.ping().repeat(2_000), 64)
                .throttleBody(256, 50, TimeUnit.MILLISECONDS),
        )
        val recorder = object : ChatTurnListener {
            override fun onText(delta: String) {
                firstText.complete(Unit)
            }
        }
        val job = async(Dispatchers.Default) { backend.chat.answer(request(), recorder, usage) }
        withTimeout(10_000) { firstText.await() }
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(1, usage.records.size)
        assertEquals(1_200L, usage.records.single().second.inputTokens)
    }

    @Test
    fun `key check maps status codes`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"OK"}],""" +
                    """"stop_reason":"end_turn","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":12,"output_tokens":3}}""",
            ),
        )
        assertEquals(AiKeyCheck.OK, backend.keys.check("sk-test", usage))
        server.enqueue(Sse.error(401, "authentication_error", "invalid x-api-key"))
        assertEquals(AiKeyCheck.INVALID, backend.keys.check("sk-wrong", usage))
        val (_, body) = takeBody()
        assertEquals(16L, body["max_tokens"]!!.jsonPrimitive.content.toLong())
        assertNull(body["tools"])
        assertEquals(1, usage.records.size)
    }
}
