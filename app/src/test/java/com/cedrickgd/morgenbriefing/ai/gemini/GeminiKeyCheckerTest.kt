package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.openai.string
import com.cedrickgd.morgenbriefing.ai.openai.takeJson
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.util.concurrent.TimeUnit

/**
 * The Gemini key test (docs/providers/gemini.md §13): the model list proves the key without
 * tokens, a tiny generation proves that it may generate — and every refusal comes back with
 * Google's reason and a key-free diagnostic. Fake keys only.
 */
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

    private fun check(model: String = "gemini-3.8-flash", key: String = GeminiSse.KEY): KeyCheckOutcome =
        runBlocking { checker.check(auth(key), model, usage) }

    private fun takePath(): String = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath

    @Test
    fun `the list proves the key, a tiny generation proves it may generate`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.modelList("gemini-3.8-flash", "gemini-3.5-flash-lite"))
        server.enqueue(GeminiSse.generated())

        val outcome = checker.check(auth(), "gemini-3.8-flash", usage)

        assertEquals(KeyCheckOutcome(AiKeyCheck.OK), outcome)
        val list = server.takeRequest()
        assertEquals("GET", list.method)
        assertEquals("/v1beta/models", list.requestUrl!!.encodedPath)
        assertEquals("1000", list.requestUrl!!.queryParameter("pageSize"))
        assertNull(list.requestUrl!!.queryParameter("key"))
        assertEquals(GeminiSse.KEY, list.getHeader("x-goog-api-key"))
        val (generate, body) = server.takeJson()
        assertEquals("POST", generate.method)
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", generate.requestUrl!!.encodedPath)
        assertNull(generate.requestUrl!!.query)
        assertEquals(GeminiSse.KEY, generate.getHeader("x-goog-api-key"))
        assertNull(generate.getHeader("Authorization"))
        // "Antworte nur: OK", little thinking, a small cap, no tools (no search costs).
        body!!
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertEquals("Antworte nur: OK", parts.single().jsonObject.string("text"))
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("low", config["thinkingConfig"]!!.jsonObject.string("thinkingLevel"))
        assertEquals("32", config.string("maxOutputTokens"))
        assertNull(body["tools"])
        assertNull(body["systemInstruction"])
        // The few tokens are recorded like a request (priced at 0 $ on the free tier by the sink).
        val (model, tokens) = usage.records.single()
        assertEquals("gemini-3.8-flash", model)
        assertEquals(13L, tokens.outputTokens)
        assertEquals(0, usage.checks)
    }

    @Test
    fun `a refusal of the key ends the test at the list with its reason and details`() {
        val cases = listOf(
            json(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", reason = "API_KEY_INVALID")) to
                (AiKeyCheck.INVALID to "HTTP 400 · INVALID_ARGUMENT · API_KEY_INVALID"),
            json(401, GeminiErrors.AUTH_KEY_REJECTED) to
                (AiKeyCheck.AUTH_KEY_REJECTED to "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED"),
            json(403, GeminiErrors.API_KEY_SERVICE_BLOCKED) to
                (AiKeyCheck.API_BLOCKED to "HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED"),
            json(403, GeminiErrors.SERVICE_DISABLED) to (AiKeyCheck.API_BLOCKED to "HTTP 403 · PERMISSION_DENIED · SERVICE_DISABLED"),
            json(403, GeminiErrors.ANDROID_APP_BLOCKED) to
                (AiKeyCheck.KEY_RESTRICTED to "HTTP 403 · PERMISSION_DENIED · API_KEY_ANDROID_APP_BLOCKED"),
            json(403, GeminiErrors.KEY_LEAKED) to (AiKeyCheck.KEY_LEAKED to "HTTP 403 · PERMISSION_DENIED"),
            json(403, GeminiSse.rpc(403, "PERMISSION_DENIED", "Method doesn't allow unregistered callers.")) to
                (AiKeyCheck.INVALID to "HTTP 403 · PERMISSION_DENIED"),
            json(402, GeminiSse.rpc(402, "RESOURCE_EXHAUSTED", "Prepay credit balance depleted.")) to
                (AiKeyCheck.OK_NO_CREDIT to "HTTP 402 · RESOURCE_EXHAUSTED"),
            json(503, GeminiSse.rpc(503, "UNAVAILABLE", "The service is currently unavailable.")) to
                (AiKeyCheck.NETWORK to "HTTP 503 · UNAVAILABLE"),
        )
        cases.forEachIndexed { i, (response, expected) ->
            server.enqueue(response)
            val key = if (i % 2 == 0) GeminiSse.KEY else GeminiSse.AQ_KEY
            val outcome = check(key = key)
            assertEquals("case $i", expected.first, outcome.check)
            assertEquals("case $i", expected.second, outcome.diagnostic)
            assertFalse(outcome.diagnostic!!.contains(key))
            assertEquals("/v1beta/models", takePath())
        }
        // Nothing was generated.
        assertEquals(cases.size, server.requestCount)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `generation decides what the list cannot see`() {
        // Listed fine, but Google does not let the key generate: the real reason, not "OK".
        val cases = listOf(
            json(401, GeminiErrors.AUTH_KEY_REJECTED) to AiKeyCheck.AUTH_KEY_REJECTED,
            json(403, GeminiErrors.API_KEY_SERVICE_BLOCKED) to AiKeyCheck.API_BLOCKED,
            json(400, GeminiErrors.FREE_TIER_REGION) to AiKeyCheck.FREE_TIER_UNAVAILABLE,
            json(429, GeminiErrors.QUOTA_PER_MINUTE) to AiKeyCheck.OK_QUOTA_EXHAUSTED,
            json(402, GeminiSse.rpc(402, "RESOURCE_EXHAUSTED", "Prepay credit balance depleted.")) to AiKeyCheck.OK_NO_CREDIT,
            json(500, GeminiSse.rpc(500, "INTERNAL", "An internal error has occurred.")) to AiKeyCheck.NETWORK,
            // Another 400 is about the tiny request, not the key: it got through.
            json(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "Request contains an invalid argument.")) to AiKeyCheck.OK,
            // Even when it mentions the model: no model refusal, no fallback.
            json(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "thinking_level is not supported by this model.")) to AiKeyCheck.OK,
        )
        cases.forEach { (response, expected) ->
            server.enqueue(GeminiSse.modelList("gemini-3.8-flash"))
            server.enqueue(response)
            val outcome = check()
            assertEquals(expected.name, expected, outcome.check)
            assertTrue(expected.name, outcome.diagnostic!!.startsWith("HTTP "))
            assertEquals("/v1beta/models", takePath())
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", takePath())
        }
        assertEquals(
            "HTTP 400 · FAILED_PRECONDITION",
            run {
                server.enqueue(GeminiSse.modelList("gemini-3.8-flash"))
                server.enqueue(json(400, GeminiErrors.FREE_TIER_REGION))
                check().diagnostic
            },
        )
    }

    @Test
    fun `a model the key may not use falls back to the next listed one`() {
        // 3.7 is not in the list (a new project): skipped; 3.5 Flash-Lite generates.
        server.enqueue(GeminiSse.modelList("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"))
        server.enqueue(json(404, GeminiErrors.modelNotFound("gemini-3.8-flash")))
        server.enqueue(GeminiSse.generated("gemini-3.5-flash-lite"))

        val outcome = check()

        assertEquals(AiKeyCheck.OK, outcome.check)
        assertEquals(ModelFallback("gemini-3.8-flash", "gemini-3.5-flash-lite"), outcome.fallback)
        assertEquals("/v1beta/models", takePath())
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", takePath())
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", takePath())
        assertEquals("gemini-3.5-flash-lite", usage.records.single().first)
    }

    @Test
    fun `without a usable list every fallback is tried once, then the model problem is reported`() {
        // The list itself fails without saying anything about the key (404): the chain decides.
        server.enqueue(json(404, GeminiSse.rpc(404, "NOT_FOUND", "Not found.")))
        server.enqueue(json(429, GeminiErrors.zeroQuota("gemini-3.8-flash")))
        server.enqueue(json(404, GeminiErrors.modelNotFound("gemini-3.7-flash")))
        server.enqueue(json(403, GeminiErrors.modelForbidden("gemini-3.5-flash-lite")))
        server.enqueue(json(404, GeminiErrors.modelNotFound("gemini-3.1-flash-lite")))

        val outcome = check()

        assertEquals(AiKeyCheck.MODEL_UNAVAILABLE, outcome.check)
        // The chosen model's refusal explains it.
        assertEquals("HTTP 429 · RESOURCE_EXHAUSTED · GenerateRequestsPerDayPerProjectPerModel-FreeTier · limit 0 · retry 20s", outcome.diagnostic)
        assertNull(outcome.fallback)
        assertEquals(5, server.requestCount)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `the chosen model is tried first even when the list does not show it`() {
        // Aliases and typed ids may be missing from the list: the generation tells the truth.
        server.enqueue(GeminiSse.modelList("gemini-3.8-flash"))
        server.enqueue(GeminiSse.generated("gemini-flash-latest"))
        val outcome = check(model = "gemini-flash-latest")
        assertEquals(KeyCheckOutcome(AiKeyCheck.OK), outcome)
        assertEquals("/v1beta/models", takePath())
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", takePath())
    }

    @Test
    fun `the model list is read leniently`() {
        val listed = GeminiKeyChecker.listedModels(
            """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},""" +
                """{"name":"models/text-embedding-005","supportedGenerationMethods":["embedContent"]},""" +
                """{"name":"models/gemini-3.5-flash-lite"}],"nextPageToken":"x"}""",
        )
        assertEquals(setOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), listed)
        assertEquals(emptySet<String>(), GeminiKeyChecker.listedModels("{}"))
        assertNull(GeminiKeyChecker.listedModels("<html></html>"))
        assertNull(GeminiKeyChecker.listedModels("""{"error":{"code":500}}"""))
    }

    @Test
    fun `a missing key or an unreachable server needs no answer`() = runBlocking<Unit> {
        assertEquals(AiKeyCheck.INVALID, checker.check(auth(key = null), "gemini-3.8-flash", usage).check)
        assertEquals(AiKeyCheck.INVALID, checker.check(auth(key = "  "), "gemini-3.8-flash", usage).check)
        assertEquals(0, server.requestCount)
        // Port 1 on loopback: nothing listens there, the connection is refused.
        val gone = ProviderAuth(AiProviderId.GEMINI, GeminiSse.KEY, baseUrl = "http://127.0.0.1:1/v1beta")
        val outcome = checker.check(gone, "gemini-3.8-flash", usage)
        assertEquals(AiKeyCheck.NETWORK, outcome.check)
        assertEquals("Verbindung · ConnectException", outcome.diagnostic)
    }

    @Test
    fun `a key with an invisible or non-ASCII character is refused without sending or naming it`() = runBlocking<Unit> {
        // A zero-width space, a BOM, an umlaut: OkHttp would refuse the header with the value in
        // its exception message (and from there in a log). (AiServices cleans the first two off before.)
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
