package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiKeyCheckerTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()
    private val checker by lazy { GeminiBackend(http).keys }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun auth(key: String? = GeminiSse.KEY, freeTier: Boolean = true) =
        ProviderAuth(AiProviderId.GEMINI, key, baseUrl = server.url("/v1beta").toString(), freeTier = freeTier)

    private fun json(code: Int, body: String = "{}") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

    @Test
    fun `the model info proves the key without spending tokens`() = runBlocking<Unit> {
        server.enqueue(json(200, """{"name":"models/gemini-3.8-flash","inputTokenLimit":1048576,"outputTokenLimit":65536,"thinking":true}"""))
        assertEquals(AiKeyCheck.OK, checker.check(auth(), "gemini-3.8-flash", usage).check)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1beta/models/gemini-3.8-flash", recorded.requestUrl!!.encodedPath)
        assertNull(recorded.requestUrl!!.query)
        assertEquals(GeminiSse.KEY, recorded.getHeader("x-goog-api-key"))
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `both key shapes and every answer are judged`() = runBlocking<Unit> {
        val cases = listOf(
            json(404, GeminiSse.rpc(404, "NOT_FOUND", "models/gemini-9 is not found.")) to AiKeyCheck.OK,
            json(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", reason = "API_KEY_INVALID")) to AiKeyCheck.INVALID,
            json(401, GeminiSse.rpc(401, "UNAUTHENTICATED", "Request had invalid authentication credentials.", reason = "ACCESS_TOKEN_TYPE_UNSUPPORTED")) to AiKeyCheck.INVALID,
            json(403, GeminiSse.rpc(403, "PERMISSION_DENIED", "Method doesn't allow unregistered callers.")) to AiKeyCheck.INVALID,
            json(400, GeminiSse.rpc(400, "FAILED_PRECONDITION", "Gemini API free tier is not available in your country. Please enable billing.")) to AiKeyCheck.OK_NO_CREDIT,
            // Another 400 is about the request (a typed model id), not the key: like 404.
            json(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "* GenerateContentRequest.model: unexpected model name format")) to AiKeyCheck.OK,
            json(402, GeminiSse.rpc(402, "RESOURCE_EXHAUSTED", "Prepay credit balance depleted.")) to AiKeyCheck.OK_NO_CREDIT,
            json(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded.")) to AiKeyCheck.OK,
            json(503, GeminiSse.rpc(503, "UNAVAILABLE", "The service is currently unavailable.")) to AiKeyCheck.NETWORK,
        )
        cases.forEachIndexed { i, (response, expected) ->
            server.enqueue(response)
            val key = if (i % 2 == 0) GeminiSse.KEY else GeminiSse.AQ_KEY
            assertEquals("case $i", expected, checker.check(auth(key), "gemini-9", usage).check)
            assertEquals(key, server.takeRequest().getHeader("x-goog-api-key"))
        }
    }

    @Test
    fun `a missing key or an unreachable server needs no answer`() = runBlocking<Unit> {
        assertEquals(AiKeyCheck.INVALID, checker.check(auth(key = null), "gemini-3.8-flash", usage).check)
        assertEquals(AiKeyCheck.INVALID, checker.check(auth(key = "  "), "gemini-3.8-flash", usage).check)
        assertEquals(0, server.requestCount)
        // Port 1 on loopback: nothing listens there, the connection is refused.
        val gone = ProviderAuth(AiProviderId.GEMINI, GeminiSse.KEY, baseUrl = "http://127.0.0.1:1/v1beta")
        assertEquals(AiKeyCheck.NETWORK, checker.check(gone, "gemini-3.8-flash", usage).check)
    }

    @Test
    fun `a key with an invisible or non-ASCII character is refused without sending or naming it`() = runBlocking<Unit> {
        // A zero-width space, a BOM, an umlaut: OkHttp would refuse the header with the value in
        // its exception message (and from there in a log).
        listOf("${GeminiSse.KEY}​", "﻿${GeminiSse.KEY}", "AIza-test-invälid").forEach { key ->
            assertEquals(key, AiKeyCheck.INVALID, checker.check(auth(key), "gemini-3.8-flash", usage).check)
        }
        assertEquals(0, server.requestCount)
        val refused = runCatching { http.get(server.url("/v1beta/models/m").toString(), mapOf("x-goog-api-key" to "AIza-test​")) }
            .exceptionOrNull()
        assertEquals(AiError.InvalidKey, (refused as AiException).error)
        assertFalse(refused.toString().contains("AIza-test"))
        assertFalse(AiHttp.isPlainKey("AIza-test​"))
        assertTrue(AiHttp.isPlainKey(GeminiSse.AQ_KEY))
    }
}
