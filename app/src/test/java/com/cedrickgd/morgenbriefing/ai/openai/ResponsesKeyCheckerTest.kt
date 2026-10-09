package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResponsesKeyCheckerTest {
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

    private fun checker(dialect: ResponsesDialect) = ResponsesBackend(http, dialect).keys

    private fun auth(provider: AiProviderId, key: String?) = ProviderAuth(provider, key, baseUrl = server.url("/v1").toString())

    private fun json(code: Int, body: String = "{}") = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `OpenAI lists the models without spending tokens`() = runBlocking<Unit> {
        val openai = checker(ResponsesDialect.OPENAI)
        server.enqueue(json(200, """{"object":"list","data":[{"id":"gpt-6.1-sol","object":"model","created":1,"owned_by":"openai"}]}"""))
        assertEquals(AiKeyCheck.OK, openai.check(auth(AiProviderId.OPENAI, ResponsesSse.OPENAI_KEY), "gpt-6.1-sol", usage).check)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer ${ResponsesSse.OPENAI_KEY}", recorded.getHeader("Authorization"))

        listOf(401 to AiKeyCheck.INVALID, 403 to AiKeyCheck.INVALID, 429 to AiKeyCheck.OK, 500 to AiKeyCheck.NETWORK).forEach { (code, expected) ->
            server.enqueue(json(code))
            assertEquals("HTTP $code", expected, openai.check(auth(AiProviderId.OPENAI, ResponsesSse.OPENAI_KEY), "gpt-6.1-sol", usage).check)
        }
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `a missing key or an unreachable server needs no answer`() = runBlocking<Unit> {
        val openai = checker(ResponsesDialect.OPENAI)
        assertEquals(AiKeyCheck.INVALID, openai.check(auth(AiProviderId.OPENAI, " "), "gpt-6.1-sol", usage).check)
        assertEquals(0, server.requestCount)
        // Port 1 on loopback: nothing listens there, the connection is refused.
        val gone = ProviderAuth(AiProviderId.OPENAI, ResponsesSse.OPENAI_KEY, baseUrl = "http://127.0.0.1:1/v1")
        assertEquals(AiKeyCheck.NETWORK, openai.check(gone, "gpt-6.1-sol", usage).check)
    }

    @Test
    fun `xAI reads the key's own status`() = runBlocking<Unit> {
        val xai = checker(ResponsesDialect.XAI)
        fun status(blocked: Boolean = false, disabled: Boolean = false, team: Boolean = false) = json(
            200,
            """{"redacted_api_key":"xai-…abcd","api_key_id":"k1","team_id":"t1","name":"Morgenbriefing","acls":["api-key:model:*"],""" +
                """"api_key_blocked":$blocked,"api_key_disabled":$disabled,"team_blocked":$team}""",
        )
        server.enqueue(status())
        assertEquals(AiKeyCheck.OK, xai.check(auth(AiProviderId.XAI, ResponsesSse.XAI_KEY), "grok-4.3", usage).check)
        assertEquals("/v1/api-key", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(status(team = true))
        assertEquals(AiKeyCheck.OK_NO_CREDIT, xai.check(auth(AiProviderId.XAI, ResponsesSse.XAI_KEY), "grok-4.3", usage).check)
        server.enqueue(status(disabled = true))
        assertEquals(AiKeyCheck.INVALID, xai.check(auth(AiProviderId.XAI, ResponsesSse.XAI_KEY), "grok-4.3", usage).check)
        server.enqueue(status(blocked = true))
        assertEquals(AiKeyCheck.INVALID, xai.check(auth(AiProviderId.XAI, ResponsesSse.XAI_KEY), "grok-4.3", usage).check)
        server.enqueue(json(401, """{"code":"Client specified an invalid argument","error":"Incorrect API key provided"}"""))
        assertEquals(AiKeyCheck.INVALID, xai.check(auth(AiProviderId.XAI, ResponsesSse.XAI_KEY), "grok-4.3", usage).check)
    }

    @Test
    fun `Perplexity answers one tiny request and its cost is recorded`() = runBlocking<Unit> {
        val pplx = checker(ResponsesDialect.PERPLEXITY)
        server.enqueue(
            json(
                200,
                """{"id":"resp_1","object":"response","status":"completed","model":"openai/gpt-6-luna","output":[],""" +
                    """"usage":{"input_tokens":12,"output_tokens":4,"total_tokens":16,"cost":{"currency":"USD","total_cost":0.00001}}}""",
            ),
        )
        assertEquals(AiKeyCheck.OK, pplx.check(auth(AiProviderId.PERPLEXITY, ResponsesSse.PPLX_KEY), "google/gemini-3.8-flash", usage).check)
        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/agent", recorded.requestUrl!!.encodedPath)
        assertEquals("openai/gpt-6-luna", body.string("model"))
        assertEquals("ping", body.string("input"))
        assertEquals("16", body.string("max_output_tokens"))
        assertNull(body["tools"])
        assertNull(body["stream"])
        val (model, tokens) = usage.records.single()
        assertEquals(AiProviderId.PERPLEXITY, usage.providers.single())
        assertEquals("openai/gpt-6-luna", model)
        assertEquals(0.00001, tokens.reportedCostUsd!!, 1e-12)

        // 401: invalid key or no credit (Perplexity does not tell them apart, OC §6.8).
        server.enqueue(json(401, """{"error":{"message":"Invalid API key","type":"invalid_api_key","code":401}}"""))
        assertEquals(AiKeyCheck.INVALID, pplx.check(auth(AiProviderId.PERPLEXITY, ResponsesSse.PPLX_KEY), "google/gemini-3.8-flash", usage).check)
        assertEquals(1, usage.records.size)
    }
}
