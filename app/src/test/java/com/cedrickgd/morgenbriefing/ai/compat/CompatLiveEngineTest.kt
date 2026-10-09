package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.LivePublish
import com.cedrickgd.morgenbriefing.ai.LiveResearchRequest
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.PublishReply
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.openai.LiveRecorder
import com.cedrickgd.morgenbriefing.ai.openai.string
import com.cedrickgd.morgenbriefing.ai.openai.takeJson
import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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
import org.junit.Before
import org.junit.Test

class CompatLiveEngineTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()
    private val engine by lazy { CompatBackend(http, CompatDialect.OPENROUTER, RetryPolicy(sleep = {})).live!! }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = LiveResearchRequest(
        auth = ProviderAuth(AiProviderId.OPENROUTER, CompatSse.OPENROUTER_KEY, baseUrl = server.url("/api/v1").toString()),
        model = "google/gemini-3.8-flash",
        systemPrompt = AiPrompts.liveSystem(LiveToolNote.SEARCH_ONLY),
        userPrompt = "Recherchiere Live-Meldungen seit 06:00 Uhr.",
    )

    private val articles = """{"articles":[{"topicId":"politik","id":"politik-live-test"}]}"""

    /** A response whose `publish_articles` call streams in fragments by index (OC §2.4). */
    private fun publishTurn(callId: String, arguments: String = articles): MockResponse {
        val half = arguments.length / 2
        return CompatSse.response(
            CompatSse.comment(),
            CompatSse.toolCall(0, id = callId, name = LivePublish.TOOL_NAME, arguments = ""),
            CompatSse.toolCall(0, arguments = arguments.substring(0, half)),
            CompatSse.toolCall(0, arguments = arguments.substring(half)),
            CompatSse.delta("", finish = "tool_calls"),
            CompatSse.usageChunk(CompatSse.openRouterUsage(prompt = 9_000, completion = 1_200, cost = 0.03, searches = 3), finish = "tool_calls"),
            CompatSse.done(),
        )
    }

    private fun messagesOf(body: JsonObject): List<JsonObject> = body["messages"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `an accepted publish ends the run after one request`() = runBlocking<Unit> {
        server.enqueue(publishTurn("call_1"))
        val listener = LiveRecorder()

        val result = engine.research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        assertEquals(listOf(articles), listener.published)
        assertEquals(listOf(LivePublish.TOOL_NAME), listener.toolCalls)
        assertEquals(1, server.requestCount)
        val tokens = usage.records.single().second
        assertEquals(0.03, tokens.reportedCostUsd!!, 1e-12)
        assertEquals(3L, tokens.webSearches)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("/api/v1/chat/completions", recorded.requestUrl!!.encodedPath)
        val messages = messagesOf(body)
        assertEquals(listOf("system", "user"), messages.map { it.string("role") })
        assertEquals(AiPrompts.liveSystem(LiveToolNote.SEARCH_ONLY), messages[0].string("content"))
        assertEquals("Recherchiere Live-Meldungen seit 06:00 Uhr.", messages[1].string("content"))
        assertEquals("medium", body["reasoning"]!!.jsonObject.string("effort"))
        assertEquals("32000", body.string("max_tokens"))
        assertEquals("10", body.string("max_tool_calls"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("openrouter:web_search", "function"), tools.map { it.string("type") })
        val function = tools[1]["function"]!!.jsonObject
        assertEquals(LivePublish.TOOL_NAME, function.string("name"))
        assertEquals(LivePublish.TOOL_DESCRIPTION, function.string("description"))
        // Nested and non-strict: OpenRouter's upstreams differ (§5.2).
        assertNull(function["strict"])
        assertNull(tools[1]["strict"])
        val parameters = function["parameters"]!!.jsonObject
        assertFalse(parameters.toString().contains("\"uri\""))
        assertFalse(parameters.toString().contains("\"image\""))
        val topicIds = parameters["properties"]!!.jsonObject["articles"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["topicId"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Topics.ALL.map { it.id }, topicIds)
    }

    @Test
    fun `a rejected publish is corrected with the tool calls and one tool message each`() = runBlocking<Unit> {
        server.enqueue(publishTurn("call_1"))
        server.enqueue(publishTurn("call_2"))
        val listener = LiveRecorder(PublishReply(false, "Artikel 1: headline fehlt."))

        val result = engine.research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        assertEquals(2, listener.published.size)
        assertEquals(2, usage.records.size)
        server.takeJson()
        val body = server.takeJson().second!!
        val messages = messagesOf(body)
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.string("role") })
        val assistant = messages[2]
        assertEquals(JsonNull, assistant["content"])
        val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call.string("id"))
        assertEquals("function", call.string("type"))
        assertEquals(LivePublish.TOOL_NAME, call["function"]!!.jsonObject.string("name"))
        assertEquals(articles, call["function"]!!.jsonObject.string("arguments"))
        assertEquals("call_1", messages[3].string("tool_call_id"))
        assertEquals("Artikel 1: headline fehlt.", messages[3].string("content"))
        // The tools go with every request (OC §7.3).
        assertEquals(2, body["tools"]!!.jsonArray.size)
    }

    @Test
    fun `a correction replays the reasoning details with the tool call`() = runBlocking<Unit> {
        // Gemini 3 behind OpenRouter thinks (Live: effort medium) and signs its function call; the
        // signature streams in delta.reasoning_details and must go back with the call (GE §7.4).
        server.enqueue(
            CompatSse.response(
                CompatSse.delta(""""reasoning_details":[{"type":"reasoning.text","text":"Erst ","format":"google-gemini-v1","index":0}]"""),
                CompatSse.delta(""""reasoning_details":[{"type":"reasoning.text","text":"suchen.","index":0,"signature":null}]"""),
                CompatSse.delta(""""reasoning_details":[{"type":"reasoning.encrypted","data":"CoMDAXLI","id":"tool_call_1","format":"google-gemini-v1","index":1}]"""),
                CompatSse.toolCall(0, id = "call_1", name = LivePublish.TOOL_NAME, arguments = articles),
                CompatSse.delta(""""reasoning_details":[{"type":"reasoning.text","signature":"sig-A","index":0}]""", finish = "tool_calls"),
                CompatSse.done(),
            ),
        )
        server.enqueue(publishTurn("call_2"))
        val listener = LiveRecorder(PublishReply(false, "Artikel 1: body zu kurz."))

        assertEquals(TurnEnd.COMPLETE, engine.research(request(), listener, usage).end)

        server.takeJson()
        val assistant = messagesOf(server.takeJson().second!!)[2]
        assertEquals(
            """[{"type":"reasoning.text","text":"Erst suchen.","format":"google-gemini-v1","index":0,"signature":"sig-A"},""" +
                """{"type":"reasoning.encrypted","data":"CoMDAXLI","id":"tool_call_1","format":"google-gemini-v1","index":1}]""",
            assistant["reasoning_details"].toString(),
        )
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray.single().jsonObject.string("id"))
    }

    @Test
    fun `without reasoning details the replayed call has none`() = runBlocking<Unit> {
        server.enqueue(publishTurn("call_1"))
        server.enqueue(publishTurn("call_2"))
        engine.research(request(), LiveRecorder(PublishReply(false, "Artikel 1: headline fehlt.")), usage)
        server.takeJson()
        assertNull(messagesOf(server.takeJson().second!!)[2]["reasoning_details"])
        // Only OpenRouter replays them: Mistral rejects unknown fields.
        assertFalse(CompatDialect.entries.filter { it != CompatDialect.OPENROUTER }.any { it.replaysReasoningDetails })
    }

    @Test
    fun `whole calls work, other tools and Mistral's null ids are ignored`() = runBlocking<Unit> {
        server.enqueue(
            CompatSse.response(
                CompatSse.delta(
                    """"tool_calls":[{"index":0,"id":"null","type":"function","function":{"name":"openrouter_web_search","arguments":"{\"q\":\"x\"}"}},""" +
                        """{"index":1,"id":"call_9","type":"function","function":{"name":"${LivePublish.TOOL_NAME}","arguments":${CompatSse.str(articles)}}}]""",
                    finish = "tool_calls",
                ),
                CompatSse.done(),
            ),
        )
        val listener = LiveRecorder()
        val result = engine.research(request(), listener, usage)
        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(listOf(articles), listener.published)
        assertEquals(listOf(LivePublish.TOOL_NAME), listener.toolCalls)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a response without a call is reminded once`() = runBlocking<Unit> {
        server.enqueue(CompatSse.response(CompatSse.text("Ich habe recherchiert.", finish = "stop"), CompatSse.done()))
        server.enqueue(CompatSse.response(CompatSse.text("Immer noch kein Aufruf.", finish = "stop"), CompatSse.done()))

        val result = engine.research(request(), LiveRecorder(), usage)

        assertEquals(TurnEnd.INCOMPLETE, result.end)
        assertFalse(result.published)
        assertEquals(2, server.requestCount)
        server.takeJson()
        val messages = messagesOf(server.takeJson().second!!)
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.string("role") })
        assertEquals("Ich habe recherchiert.", messages[2].string("content"))
        assertEquals(AiPrompts.LIVE_NUDGE, messages[3].string("content"))
    }

    @Test
    fun `a partly accepted publish ends complete once the model stops calling`() = runBlocking<Unit> {
        // Two parallel calls: the first is accepted, the second needs a correction.
        server.enqueue(
            CompatSse.response(
                CompatSse.toolCall(0, id = "call_a", name = LivePublish.TOOL_NAME, arguments = articles),
                CompatSse.toolCall(1, id = "call_b", name = LivePublish.TOOL_NAME, arguments = articles),
                CompatSse.delta("", finish = "tool_calls"),
                CompatSse.done(),
            ),
        )
        server.enqueue(CompatSse.response(CompatSse.text("Mehr habe ich nicht.", finish = "stop"), CompatSse.done()))
        val listener = LiveRecorder(PublishReply(true, "ok"), PublishReply(false, "Artikel 1: url fehlt."))

        val result = engine.research(request(), listener, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertTrue(result.published)
        assertEquals(2, server.requestCount)
        server.takeJson()
        val messages = messagesOf(server.takeJson().second!!)
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.string("role") })
        assertEquals(listOf("call_a", "call_b"), messages[2]["tool_calls"]!!.jsonArray.map { it.jsonObject.string("id") })
        assertEquals(listOf("call_a", "call_b"), messages.drop(3).map { it.string("tool_call_id") })
        assertEquals("Artikel 1: url fehlt.", messages[4].string("content"))
    }

    @Test
    fun `a truncated or refused research ends the run`() = runBlocking<Unit> {
        server.enqueue(CompatSse.response(CompatSse.toolCall(0, id = "call_1", name = LivePublish.TOOL_NAME, arguments = "{\"art"), CompatSse.delta("", finish = "length"), CompatSse.done()))
        assertEquals(TurnEnd.TRUNCATED, engine.research(request(), LiveRecorder(), usage).end)
        server.enqueue(CompatSse.response(CompatSse.delta("", finish = "content_filter"), CompatSse.done()))
        val refused = engine.research(request(), LiveRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, refused.end)
        assertEquals("content_filter", refused.refusalCategory)
        assertEquals(2, server.requestCount)
    }
}
