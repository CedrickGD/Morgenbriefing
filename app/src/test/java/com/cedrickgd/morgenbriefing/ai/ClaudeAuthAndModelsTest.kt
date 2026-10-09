package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeClients
import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Claude with identity-linked keys (workspace id), the base URL override, Haiku 5.5 and the new error kinds. */
class ClaudeAuthAndModelsTest {
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

    private fun auth(key: String = Sse.TEST_KEY, workspaceId: String? = null, baseUrl: String? = null) =
        ProviderAuth(AiProviderId.ANTHROPIC, key, baseUrl = baseUrl, workspaceId = workspaceId)

    private fun chat(model: String = "claude-sonnet-5-5", auth: ProviderAuth = auth(), webSearch: Boolean = true) = ChatTurnRequest(
        auth = auth,
        model = model,
        systemPrompt = AiPrompts.chatSystem(webSearch),
        context = "Kontext: ein Artikel.",
        history = listOf(ApiMessage(true, "Worum geht es?")),
        webSearch = webSearch,
    )

    private fun take(): Pair<RecordedRequest, JsonObject> {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")
        return recorded to Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private fun keyCheckOk(model: String = "claude-sonnet-5-5"): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[{"type":"text","text":"OK"}],""" +
                """"stop_reason":"end_turn","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":12,"output_tokens":3}}""",
        )

    private suspend fun errorOf(response: MockResponse, model: String = "claude-sonnet-5-5"): AiError {
        server.enqueue(response)
        try {
            backend.chat.answer(chat(model), object : ChatTurnListener {}, usage)
        } catch (e: AiException) {
            return e.error
        }
        fail("expected AiException")
        error("unreachable")
    }

    @Test
    fun `the workspace id travels as a header, and only when set`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Mit Workspace."))
        backend.chat.answer(chat(auth = auth(workspaceId = " wrkspc_01ABC ")), object : ChatTurnListener {}, usage)
        assertEquals("wrkspc_01ABC", take().first.getHeader("anthropic-workspace-id"))

        server.enqueue(Sse.simpleAnswer("Ohne."))
        backend.chat.answer(chat(auth = auth(workspaceId = null)), object : ChatTurnListener {}, usage)
        assertNull(take().first.getHeader("anthropic-workspace-id"))

        server.enqueue(Sse.simpleAnswer("Leer."))
        backend.chat.answer(chat(auth = auth(workspaceId = "  ")), object : ChatTurnListener {}, usage)
        assertNull(take().first.getHeader("anthropic-workspace-id"))
    }

    @Test
    fun `a base URL in the auth wins over the client default`() = runBlocking<Unit> {
        // The default points nowhere; the per-request URL (debug override) reaches the server.
        val elsewhere = ClaudeBackend(ClaudeClients(baseUrl = "http://127.0.0.1:9"))
        server.enqueue(Sse.simpleAnswer("Vom Mock-Server."))
        val result = elsewhere.chat.answer(chat(auth = auth(baseUrl = server.url("/").toString())), object : ChatTurnListener {}, usage)
        assertEquals("Vom Mock-Server.", result.text)
        assertEquals("/v1/messages", take().first.requestUrl!!.encodedPath)
    }

    @Test
    fun `workspace problems, unknown models and an empty balance get their own errors`() = runBlocking<Unit> {
        assertEquals(
            AiError.WorkspaceRequired,
            errorOf(
                Sse.error(
                    400,
                    "invalid_request_error",
                    "anthropic-workspace-id is required when authenticating with an identity-linked API key; " +
                        "send the id of the workspace this request acts in.",
                ),
            ),
        )
        assertEquals(
            AiError.WorkspaceNotFound,
            errorOf(Sse.error(400, "invalid_request_error", "anthropic-workspace-id: must be a valid workspace ID")),
        )
        assertEquals(AiError.WorkspaceNotFound, errorOf(Sse.error(404, "not_found_error", "Workspace `wrkspc_01XYZ` not found")))
        assertEquals(AiError.ModelUnavailable("claude-sonnet-9"), errorOf(Sse.error(404, "not_found_error", "model: claude-sonnet-9"), "claude-sonnet-9"))
        assertEquals(AiError.NoCredit, errorOf(Sse.error(402, "billing_error", "Your account has no credit.")))
        assertEquals(
            AiError.NoCredit,
            errorOf(Sse.error(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API.")),
        )
        assertEquals(AiError.InvalidKey, errorOf(Sse.error(401, "authentication_error", "invalid x-api-key")))
        val other = errorOf(Sse.error(400, "invalid_request_error", "messages: roles must alternate"))
        assertEquals(AiError.Unknown("Claude hat die Anfrage abgelehnt (HTTP 400)."), other)
        // Nothing was billed for refused requests.
        assertEquals(0, usage.records.size)
    }

    @Test
    fun `the key check no longer takes every 400 for a working key`() = runBlocking<Unit> {
        suspend fun check(response: MockResponse, workspaceId: String? = null): AiKeyCheck {
            server.enqueue(response)
            return backend.keys.check(auth(workspaceId = workspaceId), "claude-sonnet-5-5", usage).check
        }
        assertEquals(
            AiKeyCheck.WORKSPACE_REQUIRED,
            check(Sse.error(400, "invalid_request_error", "anthropic-workspace-id is required when authenticating with an identity-linked API key")),
        )
        assertEquals(
            AiKeyCheck.WORKSPACE_INVALID,
            check(Sse.error(400, "invalid_request_error", "anthropic-workspace-id: must be a valid workspace ID"), "wrkspc_x"),
        )
        assertEquals(AiKeyCheck.WORKSPACE_INVALID, check(Sse.error(404, "not_found_error", "Workspace `wrkspc_01XYZ` not found"), "wrkspc_01XYZ"))
        assertEquals(AiKeyCheck.OK_NO_CREDIT, check(Sse.error(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API.")))
        assertEquals(AiKeyCheck.OK_NO_CREDIT, check(Sse.error(402, "billing_error", "No credit.")))
        assertEquals(AiKeyCheck.OK, check(Sse.error(400, "invalid_request_error", "something else about the request")))
        assertEquals(AiKeyCheck.INVALID, check(Sse.error(401, "authentication_error", "invalid x-api-key")))
        assertEquals(AiKeyCheck.OK, check(keyCheckOk(), "wrkspc_01ABC"))
        // The last check sent the workspace header; the request uses the chosen model.
        repeat(7) { take() }
        val (recorded, body) = take()
        assertEquals("wrkspc_01ABC", recorded.getHeader("anthropic-workspace-id"))
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(listOf(AiProviderId.ANTHROPIC), usage.providers)
    }

    @Test
    fun `the key check uses the chosen model`() = runBlocking<Unit> {
        server.enqueue(keyCheckOk("claude-haiku-5-5"))
        assertEquals(AiKeyCheck.OK, backend.keys.check(auth(), "claude-haiku-5-5", usage).check)
        assertEquals("claude-haiku-5-5", take().second["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Haiku gets the basic server tools and no fallback`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Kurz und günstig."))
        backend.chat.answer(chat("claude-haiku-5-5"), object : ChatTurnListener {}, usage)
        val (recorded, body) = take()
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("web_search_20250305", "web_fetch_20250910"), tools)
        assertNull(body["fallbacks"])
        assertFalse(recorded.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        // No publish call: the run is reminded once, then ends.
        server.enqueue(Sse.simpleAnswer("Nichts Neues."))
        server.enqueue(Sse.simpleAnswer("Immer noch nichts."))
        val live = LiveResearchRequest(auth(), "claude-haiku-5-5", AiPrompts.liveSystem(LiveToolNote.SEARCH_AND_FETCH), "Recherchiere.")
        backend.live.research(live, object : LiveResearchListener {
            override suspend fun onPublish(rawInput: String) = PublishReply(true, "ok")
        }, usage)
        val liveBody = take().second
        val liveTools = liveBody["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("web_search_20250305", "web_fetch_20250910"), liveTools.take(2).map { it["type"]!!.jsonPrimitive.content })
        assertEquals(10L, liveTools[0]["max_uses"]!!.jsonPrimitive.content.toLong())
        assertEquals(LivePublish.TOOL_NAME, liveTools[2]["name"]!!.jsonPrimitive.content)
        assertNull(liveBody["fallbacks"])
    }

    @Test
    fun `a chat without web search sends no tools`() = runBlocking<Unit> {
        server.enqueue(Sse.simpleAnswer("Aus dem Kontext."))
        backend.chat.answer(chat(webSearch = false), object : ChatTurnListener {}, usage)
        val body = take().second
        assertNull(body["tools"])
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
    }
}
