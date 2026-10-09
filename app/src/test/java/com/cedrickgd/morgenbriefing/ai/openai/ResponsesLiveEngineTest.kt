package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.LivePublish
import com.cedrickgd.morgenbriefing.ai.LiveResearchRequest
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.PublishReply
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResponsesLiveEngineTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine(dialect: ResponsesDialect) = ResponsesBackend(http, dialect, RetryPolicy(sleep = {})).live

    private fun request(provider: AiProviderId = AiProviderId.OPENAI, key: String = ResponsesSse.OPENAI_KEY, model: String = "gpt-6.1-sol") =
        LiveResearchRequest(
            auth = ProviderAuth(provider, key, baseUrl = server.url("/v1").toString()),
            model = model,
            systemPrompt = AiPrompts.liveSystem(LiveToolNote.SEARCH_ONLY),
            userPrompt = "Recherchiere Live-Meldungen seit 06:00 Uhr.",
        )

    private val articles = """{"articles":[{"topicId":"politik","id":"politik-live-test"}]}"""

    private fun openAiTurn(callId: String, arguments: String = articles): okhttp3.mockwebserver.MockResponse = ResponsesSse.response(
        ResponsesSse.created(),
        ResponsesSse.reasoning(0),
        ResponsesSse.webSearch(1, "Bundestag heute", "https://www.bundestag.de/"),
        ResponsesSse.functionCall(2, callId, arguments),
        ResponsesSse.completed(),
    )

    private fun inputOf(body: JsonObject): List<JsonObject> = body["input"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `an accepted publish ends the run after one request`() = runBlocking<Unit> {
        server.enqueue(openAiTurn("call_1"))
        val listener = LiveRecorder()

        val result = engine(ResponsesDialect.OPENAI).research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        assertEquals(listOf(articles), listener.published)
        assertEquals(listOf("Bundestag heute"), listener.searches)
        assertEquals(listOf(LivePublish.TOOL_NAME), listener.toolCalls)
        assertEquals(1, server.requestCount)
        assertEquals(1, usage.records.size)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/v1/responses", recorded.requestUrl!!.encodedPath)
        assertEquals(AiPrompts.liveSystem(LiveToolNote.SEARCH_ONLY), body.string("instructions"))
        assertEquals(listOf("Recherchiere Live-Meldungen seit 06:00 Uhr."), inputOf(body).map { it.string("content") })
        assertEquals("medium", body["reasoning"]!!.jsonObject.string("effort"))
        assertEquals("32000", body.string("max_output_tokens"))
        assertEquals("10", body.string("max_tool_calls"))
        assertEquals("auto", body.string("tool_choice"))
        assertEquals("explicit", body["prompt_cache_options"]!!.jsonObject.string("mode"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("web_search", "function"), tools.map { it.string("type") })
        val function = tools[1]
        assertEquals(LivePublish.TOOL_NAME, function.string("name"))
        assertEquals("true", function.string("strict"))
        assertNull(function["function"])
        val parameters = function["parameters"]!!.jsonObject
        assertEquals(listOf("articles"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // PORTABLE schema: no "uri" format, no always-null image (OA §7.4).
        assertFalse(parameters.toString().contains("\"uri\""))
        assertFalse(parameters.toString().contains("\"image\""))
        val topicIds = parameters["properties"]!!.jsonObject["articles"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["topicId"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Topics.ALL.map { it.id }, topicIds)
    }

    @Test
    fun `a rejected publish is corrected with every output item and the function output`() = runBlocking<Unit> {
        server.enqueue(openAiTurn("call_1"))
        server.enqueue(openAiTurn("call_2"))
        val listener = LiveRecorder(PublishReply(false, "Artikel 1: headline fehlt."))

        val result = engine(ResponsesDialect.OPENAI).research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        assertEquals(2, listener.published.size)
        assertEquals(2, usage.records.size)
        server.takeJson()
        val second = inputOf(server.takeJson().second!!)
        // user prompt, then reasoning (with its encrypted content), search, call as received, then the output.
        assertEquals(listOf(null, "reasoning", "web_search_call", "function_call", "function_call_output"), second.map { it.string("type") })
        assertEquals("gAAAAABoENCRYPTED0", second[1].string("encrypted_content"))
        assertEquals("call_1", second[3].string("call_id"))
        assertEquals(articles, second[3].string("arguments"))
        assertEquals("call_1", second[4].string("call_id"))
        assertEquals("Artikel 1: headline fehlt.", second[4].string("output"))
    }

    @Test
    fun `a response without a call is reminded once, then the run is incomplete`() = runBlocking<Unit> {
        val noCall = ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Ich habe nichts gefunden."), ResponsesSse.completed())
        server.enqueue(noCall)
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Wirklich nichts."), ResponsesSse.completed()))

        val result = engine(ResponsesDialect.OPENAI).research(request(), LiveRecorder(), usage)

        assertEquals(TurnEnd.INCOMPLETE, result.end)
        assertFalse(result.published)
        assertEquals(2, server.requestCount)
        server.takeJson()
        val second = inputOf(server.takeJson().second!!)
        assertEquals("message", second[1].string("type"))
        assertEquals("user", second.last().string("role"))
        assertEquals(AiPrompts.LIVE_NUDGE, second.last().string("content"))
    }

    @Test
    fun `the reminder can lead to a publish`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Ich recherchiere weiter."), ResponsesSse.completed()))
        server.enqueue(openAiTurn("call_9"))
        val result = engine(ResponsesDialect.OPENAI).research(request(), LiveRecorder(), usage)
        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
    }

    @Test
    fun `a partly accepted publish ends complete when the model has nothing more to call`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created(),
                ResponsesSse.functionCall(0, "call_a", articles),
                ResponsesSse.functionCall(1, "call_b", articles),
                ResponsesSse.completed(),
            ),
        )
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.message(0, "Fertig."), ResponsesSse.completed()))
        val listener = LiveRecorder(PublishReply(true, "ok"), PublishReply(false, "Artikel 1: zu kurz."))

        val result = engine(ResponsesDialect.OPENAI).research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        server.takeJson()
        val outputs = inputOf(server.takeJson().second!!).filter { it.string("type") == "function_call_output" }
        assertEquals(listOf("call_a" to "ok", "call_b" to "Artikel 1: zu kurz."), outputs.map { it.string("call_id") to it.string("output") })
    }

    @Test
    fun `an unknown function is answered so the replay stays valid`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(ResponsesSse.created(), ResponsesSse.functionCall(0, "call_x", "{}", name = "get_weather"), ResponsesSse.completed()),
        )
        server.enqueue(openAiTurn("call_1"))
        val listener = LiveRecorder()

        val result = engine(ResponsesDialect.OPENAI).research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(1, listener.published.size)
        server.takeJson()
        val second = inputOf(server.takeJson().second!!)
        val output = second.single { it.string("type") == "function_call_output" }
        assertEquals("call_x", output.string("call_id"))
        assertEquals("Unbekanntes Werkzeug: get_weather", output.string("output"))
        assertEquals(AiPrompts.LIVE_NUDGE, second.last().string("content"))
    }

    @Test
    fun `a truncated or refused response ends the run`() = runBlocking<Unit> {
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.incomplete("max_output_tokens")))
        assertEquals(TurnEnd.TRUNCATED, engine(ResponsesDialect.OPENAI).research(request(), LiveRecorder(), usage).end)
        server.enqueue(ResponsesSse.response(ResponsesSse.created(), ResponsesSse.refusal(0), ResponsesSse.completed()))
        assertEquals(TurnEnd.REFUSED, engine(ResponsesDialect.OPENAI).research(request(), LiveRecorder(), usage).end)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the request budget ends a run that keeps failing validation`() = runBlocking<Unit> {
        repeat(6) { server.enqueue(openAiTurn("call_$it")) }
        val listener = LiveRecorder(*Array(6) { PublishReply(false, "Fehler $it") })
        val result = engine(ResponsesDialect.OPENAI).research(request(), listener, usage)
        assertEquals(TurnEnd.INCOMPLETE, result.end)
        assertFalse(result.published)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `xAI calls arrive whole in output_item_done`() = runBlocking<Unit> {
        server.enqueue(
            ResponsesSse.response(
                ResponsesSse.created("grok-4.3"),
                ResponsesSse.reasoning(0),
                ResponsesSse.functionCall(1, "call_x1", articles, deltas = false),
                ResponsesSse.completed("grok-4.3", ResponsesSse.usage(extra = ""","cost_in_usd_ticks":120000000""")),
                ResponsesSse.done(),
            ),
        )
        val listener = LiveRecorder()

        val result = engine(ResponsesDialect.XAI).research(request(AiProviderId.XAI, ResponsesSse.XAI_KEY, "grok-4.3"), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(listOf(articles), listener.published)
        assertEquals(listOf(LivePublish.TOOL_NAME), listener.toolCalls)
        assertEquals(0.012, usage.records.single().second.reportedCostUsd!!, 1e-12)
        val body = server.takeJson().second!!
        assertEquals("10", body.string("max_turns"))
        assertEquals("medium", body["reasoning"]!!.jsonObject.string("effort"))
        assertTrue(body["include"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("reasoning.encrypted_content"))
        assertNull(body["prompt_cache_options"])
        assertNull(body["max_tool_calls"])
        assertEquals("auto", body.string("tool_choice"))
    }

    @Test
    fun `Perplexity searches the last day and re-sends only its function calls`() = runBlocking<Unit> {
        val turn = {
            ResponsesSse.response(
                ResponsesSse.created("google/gemini-3.8-flash"),
                ResponsesSse.searchResults(0, listOf("Bundestag heute"), "Bundestag" to "https://www.bundestag.de/"),
                ResponsesSse.functionCall(1, "call_p", articles, deltas = false),
                ResponsesSse.completed("google/gemini-3.8-flash", """{"input_tokens":100,"output_tokens":50,"cost":{"total_cost":0.004}}"""),
            )
        }
        server.enqueue(turn())
        server.enqueue(turn())
        val listener = LiveRecorder(PublishReply(false, "Artikel 1: Quelle fehlt."))

        val result = engine(ResponsesDialect.PERPLEXITY).research(
            request(AiProviderId.PERPLEXITY, ResponsesSse.PPLX_KEY, "google/gemini-3.8-flash"), listener, usage,
        )

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(listOf("Bundestag heute", "Bundestag heute"), listener.searches)
        val (recorded, first) = server.takeJson()
        first!!
        assertEquals("/v1/agent", recorded.requestUrl!!.encodedPath)
        val search = first["tools"]!!.jsonArray[0].jsonObject
        assertEquals("web", search.string("search_type"))
        assertEquals("day", search["filters"]!!.jsonObject.string("search_recency_filter"))
        assertEquals("10", first.string("max_steps"))
        assertEquals("16000", first.string("max_output_tokens"))
        assertEquals("auto", first.string("tool_choice"))
        val second = inputOf(server.takeJson().second!!)
        assertEquals(listOf("message", "function_call", "function_call_output"), second.map { it.string("type") })
        assertEquals("call_p", second[1].string("call_id"))
        assertEquals("Artikel 1: Quelle fehlt.", second[2].string("output"))
        assertEquals(listOf(0.004, 0.004), usage.records.map { it.second.reportedCostUsd })
    }
}
