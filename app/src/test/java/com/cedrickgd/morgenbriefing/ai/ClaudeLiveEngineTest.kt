package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeClients
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClaudeLiveEngineTest {
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

    private val request = LiveResearchRequest(
        apiKey = Sse.TEST_KEY,
        model = "claude-sonnet-5-5",
        systemPrompt = AiPrompts.LIVE_SYSTEM,
        userPrompt = "Recherchiere brandaktuelle Meldungen.",
    )

    private class Listener(private val replies: ArrayDeque<PublishReply>) : LiveResearchListener {
        val published = mutableListOf<String>()
        val searches = mutableListOf<String>()
        val toolCalls = mutableListOf<String>()
        override fun onSearch(query: String) {
            searches += query
        }
        override fun onToolCall(name: String) {
            toolCalls += name
        }
        override suspend fun onPublish(rawInput: String): PublishReply {
            published += rawInput
            return replies.removeFirst()
        }
    }

    private fun body(): JsonObject {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    @Test
    fun `publish_articles round trip answers the tool call and finishes`() = runBlocking<Unit> {
        val payload = LiveFixtures.payload(LiveFixtures.article())
        server.enqueue(
            Sse.response(
                Sse.messageStart(input = 3_000),
                Sse.webSearchCall(0, "srvtoolu_1", "Leipzig Nachrichten heute"),
                Sse.webSearchResult(1, "srvtoolu_1", "LVB: Neue Linie" to "https://www.lvz.de/leipzig/lvb-100.html"),
                Sse.toolUse(2, "toolu_01", LivePublish.TOOL_NAME, payload),
                Sse.messageDelta("tool_use", output = 900, webSearches = 1),
                Sse.messageStop(),
            ),
        )
        server.enqueue(Sse.simpleAnswer("Veröffentlicht."))
        val listener = Listener(ArrayDeque(listOf(PublishReply(true, "ok"))))

        val result = backend.live.research(request, listener, usage)

        assertTrue(result.published)
        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(listOf("Leipzig Nachrichten heute"), listener.searches)
        assertEquals(listOf(LivePublish.TOOL_NAME), listener.toolCalls)
        assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(listener.published.single()))

        val first = body()
        assertEquals("medium", first["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", first["fallbacks"]!!.jsonPrimitive.content)
        val tools = first["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("web_search_20260209", "web_fetch_20260209"), tools.take(2).map { it["type"]!!.jsonPrimitive.content })
        assertEquals(10L, tools[0]["max_uses"]!!.jsonPrimitive.content.toLong())
        assertEquals(8L, tools[1]["max_uses"]!!.jsonPrimitive.content.toLong())
        val publish = tools[2]
        assertEquals(LivePublish.TOOL_NAME, publish["name"]!!.jsonPrimitive.content)
        assertTrue(publish["strict"]!!.jsonPrimitive.boolean)
        val schema = publish["input_schema"]!!.jsonObject
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val article = schema["properties"]!!.jsonObject["articles"]!!.jsonObject["items"]!!.jsonObject
        assertFalse(article["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals("null", article["properties"]!!.jsonObject["image"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(article["required"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("topicId", "body", "sources", "publishedAt", "image")))
        assertFalse(first.containsKey("tool_choice"))

        val second = body()
        val messages = second["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val assistantTypes = (messages[1].jsonObject["content"] as JsonArray).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("server_tool_use", "web_search_tool_result", "tool_use"), assistantTypes)
        val toolResult = (messages[2].jsonObject["content"] as JsonArray).single().jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_01", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("ok", toolResult["content"]!!.jsonPrimitive.content)
        assertFalse(toolResult.containsKey("is_error"))
        assertEquals(2, usage.records.size)
    }

    @Test
    fun `rejected articles are sent back as an error result and published again`() = runBlocking<Unit> {
        val payload = LiveFixtures.payload(LiveFixtures.article())
        repeat(2) { i ->
            server.enqueue(
                Sse.response(
                    Sse.messageStart(),
                    Sse.toolUse(0, "toolu_0$i", LivePublish.TOOL_NAME, payload),
                    Sse.messageDelta("tool_use"),
                    Sse.messageStop(),
                ),
            )
        }
        server.enqueue(Sse.simpleAnswer("Erledigt."))
        val listener = Listener(ArrayDeque(listOf(PublishReply(false, "Artikel 1: body ist zu kurz."), PublishReply(true, "ok"))))

        val result = backend.live.research(request, listener, usage)

        assertTrue(result.published)
        assertEquals(2, listener.published.size)
        body()
        val second = body()
        val toolResult = (second["messages"]!!.jsonArray.last().jsonObject["content"] as JsonArray).single().jsonObject
        assertTrue(toolResult["is_error"]!!.jsonPrimitive.boolean)
        assertEquals("Artikel 1: body ist zu kurz.", toolResult["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a run that ends without publishing is reminded once`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Hier sind die Meldungen …"))
        server.enqueue(Sse.simpleAnswer("Es gibt nichts Neues."))
        val listener = Listener(ArrayDeque())

        val result = backend.live.research(request, listener, usage)

        assertFalse(result.published)
        // Never published: the run did not finish its job.
        assertEquals(TurnEnd.INCOMPLETE, result.end)
        assertEquals(2, server.requestCount)
        body()
        val second = body()
        val last = second["messages"]!!.jsonArray.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals(AiPrompts.LIVE_NUDGE, last["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refused attempt's tool call before a fallback marker is neither run nor answered`() = runBlocking<Unit> {
        val refused = LiveFixtures.payload(LiveFixtures.article(id = "region-live-abgelehnter-versuch"))
        val fallback = LiveFixtures.payload(LiveFixtures.article())
        server.enqueue(
            Sse.response(
                Sse.messageStart(),
                Sse.textBlock(0, "Ich veröffentliche."),
                Sse.toolUse(1, "toolu_refused", LivePublish.TOOL_NAME, refused),
                Sse.fallbackMarker(2),
                Sse.textBlock(3, "Weiter."),
                Sse.toolUse(4, "toolu_fallback", LivePublish.TOOL_NAME, fallback),
                Sse.messageDelta("tool_use", output = 40),
                Sse.messageStop(),
            ),
        )
        server.enqueue(Sse.simpleAnswer("Erledigt."))
        val listener = Listener(ArrayDeque(listOf(PublishReply(true, "ok"), PublishReply(true, "ok"))))

        val result = backend.live.research(request, listener, usage)

        assertTrue(result.published)
        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(Json.parseToJsonElement(fallback), Json.parseToJsonElement(listener.published.single()))
        body()
        val messages = body()["messages"]!!.jsonArray
        val assistant = (messages[1].jsonObject["content"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("toolu_fallback"), assistant.filter { it["type"]!!.jsonPrimitive.content == "tool_use" }.map { it["id"]!!.jsonPrimitive.content })
        val results = (messages[2].jsonObject["content"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("toolu_fallback"), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a run that publishes an empty list after the reminder is complete`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Ich habe recherchiert."))
        server.enqueue(
            Sse.response(
                Sse.messageStart(),
                Sse.toolUse(0, "toolu_empty", LivePublish.TOOL_NAME, LiveFixtures.payload()),
                Sse.messageDelta("tool_use"),
                Sse.messageStop(),
            ),
        )
        server.enqueue(Sse.simpleAnswer("Nichts Neues."))
        val listener = Listener(ArrayDeque(listOf(PublishReply(true, "ok"))))

        val result = backend.live.research(request, listener, usage)

        assertTrue(result.published)
        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a run that uses up its request budget is incomplete`() = runBlocking<Unit> {
        repeat(10) {
            server.enqueue(
                Sse.response(
                    Sse.messageStart(),
                    Sse.textBlock(0, "Ich suche weiter."),
                    Sse.messageDelta("pause_turn"),
                    Sse.messageStop(),
                ),
            )
        }
        val listener = Listener(ArrayDeque())

        val result = backend.live.research(request, listener, usage)

        assertFalse(result.published)
        assertEquals(TurnEnd.INCOMPLETE, result.end)
        assertEquals(10, server.requestCount)
    }
}
