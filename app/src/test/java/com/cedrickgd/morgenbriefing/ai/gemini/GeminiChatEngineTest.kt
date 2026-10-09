package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiBlockedException
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.ApiMessage
import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.GeminiProblem
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.RecordingUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.openai.ChatRecorder
import com.cedrickgd.morgenbriefing.ai.openai.string
import com.cedrickgd.morgenbriefing.ai.openai.takeJson
import com.cedrickgd.morgenbriefing.ai.provider.GeminiModels
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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

class GeminiChatEngineTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val usage = RecordingUsage()
    private val slept = mutableListOf<Duration>()
    private val backend by lazy { GeminiBackend(http, RetryPolicy(sleep = { slept += it })) }

    /** Collects what the engine streams, including the Search Suggestions. */
    private class Recorder : ChatTurnListener {
        val text = StringBuilder()
        val searches = mutableListOf<String>()
        var sources: List<SourceRef> = emptyList()
        val suggestions = mutableListOf<String>()
        var grounded = 0

        override fun onText(delta: String) {
            text.append(delta)
        }

        override fun onGrounded() {
            grounded++
        }

        override fun onSearch(query: String) {
            searches += query
        }

        override fun onSources(sources: List<SourceRef>) {
            this.sources = sources
        }

        override fun onSearchSuggestions(html: String) {
            suggestions += html
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The debug base-URL override (G5) points every request at the mock server. */
    private fun auth(key: String = GeminiSse.KEY, freeTier: Boolean = false) =
        ProviderAuth(AiProviderId.GEMINI, key, baseUrl = server.url("/v1beta").toString(), freeTier = freeTier)

    private fun request(
        auth: ProviderAuth = auth(),
        model: String = "gemini-3.8-flash",
        webSearch: Boolean = true,
        history: List<ApiMessage> = listOf(ApiMessage(true, "Worum geht es?\n\n(Heute ist Freitag, 9. Oktober 2026, es ist 07:30 Uhr.)")),
    ) = ChatTurnRequest(
        auth = auth,
        model = model,
        systemPrompt = AiPrompts.chatSystem(webSearch = webSearch),
        context = "Kontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.",
        history = history,
        webSearch = webSearch,
    )

    private fun ok(text: String = "Ok.") = GeminiSse.response(GeminiSse.text(text, finish = "STOP", usage = GeminiSse.usage()))

    /** The system prompt a request body carries. */
    private fun JsonObject.systemText(): String? = this["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject.string("text")

    @Test
    fun `streams text without thoughts, appends grounding and counts distinct queries`() = runBlocking<Unit> {
        val tagesschau = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA-tagesschau"
        val spiegel = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/BBB-spiegel"
        val faz = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/CCC-faz"
        server.enqueue(
            GeminiSse.response(
                GeminiSse.candidate(null, grounding = GeminiSse.grounding(queries = listOf("EZB Zinsentscheid Oktober 2026", ""))),
                GeminiSse.text("Die EZB ", thought = "Erst die Lage prüfen …"),
                GeminiSse.text(
                    "senkt den Zins.",
                    grounding = GeminiSse.grounding(
                        queries = listOf("EZB Zinsentscheid Oktober 2026", "Leitzins Eurozone"),
                        chunks = listOf("tagesschau.de" to tagesschau, "spiegel.de" to spiegel),
                    ),
                ),
                GeminiSse.candidate(
                    """"content":{"role":"model","parts":[{"text":"","thoughtSignature":"CoMDAXLI"}]}""",
                    finish = "STOP",
                    grounding = GeminiSse.grounding(chunks = listOf("faz.net" to faz), suggestions = GeminiSse.SUGGESTIONS),
                    usage = GeminiSse.usage(prompt = 5_000, cached = 4_000, candidates = 300, thoughts = 100, toolUse = 50),
                ),
            ),
        )
        val recorder = Recorder()

        val result = backend.chat.answer(request(), recorder, usage)

        assertEquals(TurnEnd.COMPLETE, result.end)
        assertEquals("Die EZB senkt den Zins.", result.text)
        assertEquals("Die EZB senkt den Zins.", recorder.text.toString())
        // The status line only before the answer streams; every distinct non-empty query is billed.
        assertEquals(listOf("EZB Zinsentscheid Oktober 2026"), recorder.searches)
        // Grounding chunks are incremental: appended in order; the title is a domain, the uri opened as it is.
        val expected = listOf(SourceRef("tagesschau.de", tagesschau), SourceRef("spiegel.de", spiegel), SourceRef("faz.net", faz))
        assertEquals(expected, result.sources)
        assertEquals(expected, recorder.sources)
        assertEquals(GeminiSse.SUGGESTIONS, result.searchSuggestionsHtml)
        assertEquals(listOf(GeminiSse.SUGGESTIONS), recorder.suggestions)
        // Grounded from the first query on, said once (the chat then needs the suggestions).
        assertEquals(1, recorder.grounded)
        assertFalse(result.withoutWebSearch)

        assertEquals(1, usage.checks)
        val (model, tokens) = usage.records.single()
        assertEquals(AiProviderId.GEMINI, usage.providers.single())
        assertEquals("gemini-3.8-flash", model)
        // Uncached prompt plus tool-use prompt; thinking counts as output (GE §9.2).
        assertEquals(1_050L, tokens.inputTokens)
        assertEquals(4_000L, tokens.cacheReadTokens)
        assertEquals(0L, tokens.cacheWriteTokens)
        assertEquals(400L, tokens.outputTokens)
        assertEquals(2L, tokens.webSearches)
        assertNull(tokens.reportedCostUsd)
    }

    @Test
    fun `the request follows the Gemini 3 rules and keeps the key out of the URL`() = runBlocking<Unit> {
        server.enqueue(ok())
        val history = listOf(ApiMessage(true, "Frage 1"), ApiMessage(false, "Antwort 1"), ApiMessage(true, "Frage 2"))
        backend.chat.answer(request(history = history), ChatRecorder(), usage)

        val (recorded, body) = server.takeJson()
        body!!
        assertEquals("POST", recorded.method)
        val url = recorded.requestUrl!!
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent", url.encodedPath)
        assertEquals("alt=sse", url.encodedQuery)
        assertNull(url.queryParameter("key"))
        assertEquals(GeminiSse.KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("Morgenbriefing-Android/test", recorded.getHeader("User-Agent"))

        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals(AiPrompts.chatSystem(webSearch = true), system.string("text"))
        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it.string("role") })
        // The context leads the first user turn (byte-stable prefix for implicit caching).
        assertEquals(
            listOf("Kontext: ein Artikel aus dem Morgenbriefing vom Freitag, 9. Oktober 2026.", "Frage 1"),
            contents[0].texts(),
        )
        assertEquals(listOf("Antwort 1"), contents[1].texts())
        assertEquals(listOf("Frage 2"), contents[2].texts())
        assertEquals("""[{"googleSearch":{}}]""", body["tools"].toString())
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("low", config["thinkingConfig"]!!.jsonObject.string("thinkingLevel"))
        assertEquals("8192", config.string("maxOutputTokens"))
        // Deprecated or rejected on Gemini 3 (GE §15).
        listOf("temperature", "topP", "topK", "candidateCount", "thinkingBudget", "key").forEach {
            assertFalse(it, body.toString().contains("\"$it\""))
        }
    }

    private fun JsonObject.texts(): List<String?> = this["parts"]!!.jsonArray.map { it.jsonObject.string("text") }

    @Test
    fun `a free key never asks for Google Search`() = runBlocking<Unit> {
        server.enqueue(ok())
        // Even when the chat would offer search, the free tier ("Kostenlos") never sends it (G1).
        val result = backend.chat.answer(request(auth = auth(freeTier = true)), ChatRecorder(), usage)
        val free = server.takeJson().second!!
        assertNull(free["tools"])
        assertEquals(AiPrompts.chatSystem(webSearch = false), free.systemText())
        // Not a fallback: the chat explains the free tier on its own.
        assertFalse(result.withoutWebSearch)
        assertNull(result.searchSuggestionsHtml)

        server.enqueue(ok())
        backend.chat.answer(request(webSearch = false), ChatRecorder(), usage)
        val body = server.takeJson().second!!
        assertNull(body["tools"])
        assertEquals(AiPrompts.chatSystem(webSearch = false), body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject.string("text"))
    }

    @Test
    fun `a key that may not search falls back to an answer without it, once per key`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "FAILED_PRECONDITION", "User location is not supported for the API use without a billing account linked.")))
        server.enqueue(ok("Ohne Suche."))

        val first = backend.chat.answer(request(), ChatRecorder(), usage)

        assertEquals("Ohne Suche.", first.text)
        assertTrue(first.withoutWebSearch)
        assertNull(first.searchSuggestionsHtml)
        val searched = server.takeJson().second!!
        assertEquals("""[{"googleSearch":{}}]""", searched["tools"].toString())
        assertEquals(AiPrompts.chatSystem(webSearch = true), searched.systemText())
        // The retry has no search tool – and no prompt that tells the model to search and cite.
        val plain = server.takeJson().second!!
        assertNull(plain["tools"])
        assertEquals(AiPrompts.chatSystem(webSearch = false), plain.systemText())
        assertTrue(slept.isEmpty())
        // The monthly limit is checked before both requests; only the answered one is billed.
        assertEquals(2, usage.checks)
        assertEquals(1, usage.records.size)

        // The same key is not offered the search again (for the life of the process) …
        server.enqueue(ok())
        assertTrue(backend.chat.answer(request(), ChatRecorder(), usage).withoutWebSearch)
        val remembered = server.takeJson().second!!
        assertNull(remembered["tools"])
        assertEquals(AiPrompts.chatSystem(webSearch = false), remembered.systemText())
        // … another key is.
        server.enqueue(ok())
        assertFalse(backend.chat.answer(request(auth = auth(key = "AIza-other-test-invalid")), ChatRecorder(), usage).withoutWebSearch)
        assertEquals("""[{"googleSearch":{}}]""", server.takeJson().second!!["tools"].toString())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `saving the tariff or a key lets a key that could not search try again`() = runBlocking<Unit> {
        val memo = GeminiSearchMemo()
        val engine = GeminiBackend(http, RetryPolicy(sleep = { slept += it }), memo).chat
        val refused = GeminiSse.rpc(400, "FAILED_PRECONDITION", "User location is not supported for the API use without a billing account linked.")
        server.enqueue(GeminiSse.httpError(400, refused))
        server.enqueue(ok())
        assertTrue(engine.answer(request(), ChatRecorder(), usage).withoutWebSearch)
        server.takeJson()
        server.takeJson()
        assertTrue(memo.cannotSearch(GeminiSse.KEY))

        // "Kostenlos" saved in between, then "Mit Abrechnung" again: the next answer asks for the search.
        memo.forget()
        server.enqueue(ok())
        server.enqueue(ok("Mit Suche."))
        engine.answer(request(auth = auth(freeTier = true)), ChatRecorder(), usage)
        assertNull(server.takeJson().second!!["tools"])
        val searched = engine.answer(request(), ChatRecorder(), usage)
        assertEquals("Mit Suche.", searched.text)
        assertFalse(searched.withoutWebSearch)
        assertEquals("""[{"googleSearch":{}}]""", server.takeJson().second!!["tools"].toString())

        // Refused again, then the key saved again (e.g. after billing was enabled): searched again.
        server.enqueue(GeminiSse.httpError(400, refused))
        server.enqueue(ok())
        assertTrue(engine.answer(request(), ChatRecorder(), usage).withoutWebSearch)
        server.takeJson()
        server.takeJson()
        memo.forget()
        server.enqueue(ok())
        assertFalse(engine.answer(request(), ChatRecorder(), usage).withoutWebSearch)
        assertEquals("""[{"googleSearch":{}}]""", server.takeJson().second!!["tools"].toString())
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `a refusal of a request from before a save is not remembered`() {
        val memo = GeminiSearchMemo()
        val before = memo.generation()
        memo.forget()
        // The request ran with the old tariff or key: its refusal says nothing about the new setup.
        memo.remember(GeminiSse.KEY, before)
        assertFalse(memo.cannotSearch(GeminiSse.KEY))

        memo.remember(GeminiSse.KEY, memo.generation())
        assertTrue(memo.cannotSearch(GeminiSse.KEY))
        // Kept as a hash of the trimmed key; no key, nothing to remember.
        assertTrue(memo.cannotSearch(" ${GeminiSse.KEY} "))
        assertFalse(memo.cannotSearch("AIza-other-test-invalid"))
        assertFalse(memo.cannotSearch(null))
        memo.remember(null, memo.generation())
        assertFalse(memo.cannotSearch(null))
    }

    @Test
    fun `a search quota error falls back at once instead of being retried`() = runBlocking<Unit> {
        server.enqueue(
            GeminiSse.httpError(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Grounding with Google Search is not available on the free tier.", retryDelay = "2s")),
        )
        server.enqueue(ok())
        val result = backend.chat.answer(request(), ChatRecorder(), usage)
        assertTrue(result.withoutWebSearch)
        assertEquals(2, server.requestCount)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `an invalid key is never mistaken for a missing search`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", reason = "API_KEY_INVALID")))
        assertError(AiError.InvalidKey) { backend.chat.answer(request(), ChatRecorder(), usage) }
        assertEquals(1, server.requestCount)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `finish reasons map to cut-off, refused and aborted answers`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.response(GeminiSse.text("Die EZB hat", finish = "MAX_TOKENS", usage = GeminiSse.usage())))
        val truncated = backend.chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.TRUNCATED, truncated.end)
        assertEquals("Die EZB hat", truncated.text)

        server.enqueue(GeminiSse.response(GeminiSse.candidate(null, finish = "SAFETY", usage = GeminiSse.usage(candidates = 0))))
        val safety = backend.chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, safety.end)
        assertEquals("safety", safety.refusalCategory)
        assertEquals("", safety.text)

        // A blocked prompt has no candidates at all (GE §5.3).
        server.enqueue(GeminiSse.response(GeminiSse.chunk("""{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":${GeminiSse.usage(candidates = 0, thoughts = 0)}}""")))
        val blocked = backend.chat.answer(request(), ChatRecorder(), usage)
        assertEquals(TurnEnd.REFUSED, blocked.end)
        assertEquals("prohibited_content", blocked.refusalCategory)

        server.enqueue(GeminiSse.response(GeminiSse.text("Halb", finish = "OTHER", usage = GeminiSse.usage())))
        assertError(AiError.Unknown("Gemini hat die Antwort abgebrochen.")) { backend.chat.answer(request(), ChatRecorder(), usage) }
        // Every answered request is billed, also the aborted one; nothing is sent twice.
        assertEquals(4, usage.records.size)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `grounding is announced before the suggestions, also when the stream then fails`() = runBlocking<Unit> {
        server.enqueue(
            GeminiSse.response(
                GeminiSse.text("Die EZB ", grounding = GeminiSse.grounding(queries = listOf("EZB Zinsentscheid"))),
                GeminiSse.chunk("""{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}"""),
            ),
        )
        val recorder = Recorder()
        assertError(AiError.Overloaded) { backend.chat.answer(request(), recorder, usage) }
        assertEquals(1, recorder.grounded)
        assertTrue(recorder.suggestions.isEmpty())

        // Without search nothing is grounded.
        server.enqueue(ok())
        val plain = Recorder()
        backend.chat.answer(request(auth = auth(freeTier = true)), plain, usage)
        assertEquals(0, plain.grounded)
    }

    @Test
    fun `an error chunk in the stream is mapped and keeps the usage`() = runBlocking<Unit> {
        server.enqueue(
            GeminiSse.response(
                GeminiSse.text("Anfang"),
                GeminiSse.chunk("""{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}"""),
            ),
        )
        assertError(AiError.Overloaded) { backend.chat.answer(request(), ChatRecorder(), usage) }
        assertEquals(1, server.requestCount)
        assertEquals(1, usage.records.size)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `a stream that ends without a finish reason is a network error with estimated usage`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.response(GeminiSse.text("x".repeat(300))))
        assertError(AiError.Network) { backend.chat.answer(request(), ChatRecorder(), usage) }
        val tokens = usage.records.single().second
        assertEquals(100L, tokens.outputTokens)
        assertTrue(tokens.inputTokens > 0)
    }

    @Test
    fun `HTTP errors before the stream`() = runBlocking<Unit> {
        // Classic key: 400 API_KEY_INVALID, labelled text/event-stream (GE §5.1).
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", reason = "API_KEY_INVALID")))
        assertError(AiError.InvalidKey) { backend.chat.answer(request(webSearch = false), ChatRecorder(), usage) }
        // AQ. key: 401 UNAUTHENTICATED (GE §12.1, observed).
        server.enqueue(
            GeminiSse.httpError(
                401,
                GeminiSse.rpc(401, "UNAUTHENTICATED", "Request had invalid authentication credentials. Expected OAuth 2 access token.", reason = "ACCESS_TOKEN_TYPE_UNSUPPORTED"),
                eventStream = false,
            ),
        )
        assertError(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED)) {
            backend.chat.answer(request(auth = auth(key = GeminiSse.AQ_KEY), webSearch = false), ChatRecorder(), usage)
        }
        // The error body may be wrapped in an array.
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "API key not valid.", reason = "API_KEY_INVALID", array = true)))
        assertError(AiError.InvalidKey) { backend.chat.answer(request(webSearch = false), ChatRecorder(), usage) }
        server.enqueue(GeminiSse.httpError(403, GeminiSse.rpc(403, "PERMISSION_DENIED", "Method doesn't allow unregistered callers.")))
        assertError(AiError.Unknown("Der Key darf die Gemini API nicht nutzen.")) { backend.chat.answer(request(webSearch = false), ChatRecorder(), usage) }
        // An unknown model: every model of the fallback chain is tried once, then the requested one is named.
        repeat(1 + GeminiModels.CHAIN.size) { server.enqueue(GeminiSse.httpError(404, GeminiSse.rpc(404, "NOT_FOUND", "models/gemini-9 is not found."))) }
        assertError(AiError.ModelUnavailable("gemini-9")) { backend.chat.answer(request(model = "gemini-9", webSearch = false), ChatRecorder(), usage) }
        server.enqueue(GeminiSse.httpError(402, GeminiSse.rpc(402, "RESOURCE_EXHAUSTED", "Prepay credit balance depleted.")))
        assertError(AiError.NoCredit) { backend.chat.answer(request(webSearch = false), ChatRecorder(), usage) }

        assertEquals(10, server.requestCount)
        assertTrue(slept.isEmpty())
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `a model the key may not use falls back to the next one of the chain`() = runBlocking<Unit> {
        // 3.8 is not open to this project (404), 3.7 answers – with Google Search, as asked.
        server.enqueue(GeminiSse.httpError(404, GeminiErrors.modelNotFound("gemini-3.8-flash")))
        server.enqueue(ok("Von 3.7."))

        val result = backend.chat.answer(request(), ChatRecorder(), usage)

        assertEquals("Von 3.7.", result.text)
        assertEquals("gemini-3.7-flash", result.fallbackModel)
        assertFalse(result.withoutWebSearch)
        val (first, firstBody) = server.takeJson()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent", first.requestUrl!!.encodedPath)
        val (second, secondBody) = server.takeJson()
        assertEquals("/v1beta/models/gemini-3.7-flash:streamGenerateContent", second.requestUrl!!.encodedPath)
        // The same question, only another model; never retried with a wait.
        assertEquals(firstBody, secondBody)
        assertTrue(slept.isEmpty())
        // Only the answered request is billed; the limit is checked before both.
        assertEquals(2, usage.checks)
        assertEquals(1, usage.records.size)
    }

    @Test
    fun `a zero quota or a forbidden model falls back too, also after the search was refused`() = runBlocking<Unit> {
        val free = auth(freeTier = true)
        // The free tier offers 3.8 no quota at all (limit 0): 3.7 answers.
        server.enqueue(GeminiSse.httpError(429, GeminiErrors.zeroQuota("gemini-3.8-flash")))
        server.enqueue(ok())
        assertEquals("gemini-3.7-flash", backend.chat.answer(request(auth = free), ChatRecorder(), usage).fallbackModel)
        assertTrue(slept.isEmpty())

        // A Pro model the project may not use (403 about the model): the chain from its start.
        server.enqueue(GeminiSse.httpError(403, GeminiErrors.modelForbidden("gemini-3.1-pro-preview")))
        server.enqueue(ok())
        val pro = backend.chat.answer(request(model = "gemini-3.1-pro-preview", webSearch = false), ChatRecorder(), usage)
        assertEquals("gemini-3.8-flash", pro.fallbackModel)

        // Search refused on the fallback model: answered there without search, the fallback kept.
        server.enqueue(GeminiSse.httpError(404, GeminiErrors.modelNotFound("gemini-3.8-flash")))
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "FAILED_PRECONDITION", "Grounding with Google Search is not available. Please enable billing.")))
        server.enqueue(ok())
        val searched = backend.chat.answer(request(auth = auth(key = "AIza-other-test-invalid")), ChatRecorder(), usage)
        assertEquals("gemini-3.7-flash", searched.fallbackModel)
        assertTrue(searched.withoutWebSearch)
        repeat(4) { server.takeJson() }
        repeat(2) { server.takeJson() }
        assertEquals("/v1beta/models/gemini-3.7-flash:streamGenerateContent", server.takeJson().first.requestUrl!!.encodedPath)
    }

    @Test
    fun `a refused request that mentions the model is no model refusal and swaps nothing`() = runBlocking<Unit> {
        // A typed older model that cannot take the app's thinkingLevel: the request is the problem, not the model.
        server.enqueue(GeminiSse.httpError(400, GeminiSse.rpc(400, "INVALID_ARGUMENT", "thinking_level is not supported by this model.")))
        try {
            backend.chat.answer(request(model = "gemini-2.0-flash", webSearch = false), ChatRecorder(), usage)
            fail("expected the refused request")
        } catch (e: AiException) {
            assertEquals(AiError.Unknown("Gemini hat die Anfrage abgelehnt (HTTP 400)."), e.error)
            assertEquals("HTTP 400 · INVALID_ARGUMENT", e.diagnostic)
        }
        // One request: no model of the chain was tried, no fallback to keep.
        assertEquals(1, server.requestCount)
        assertEquals("/v1beta/models/gemini-2.0-flash:streamGenerateContent", server.takeRequest().requestUrl!!.encodedPath)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `when no model of the chain answers, the chosen one's refusal is reported with its details`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.httpError(404, GeminiErrors.modelNotFound("gemini-3.7-flash")))
        server.enqueue(GeminiSse.httpError(429, GeminiErrors.zeroQuota("gemini-3.5-flash-lite")))
        server.enqueue(GeminiSse.httpError(404, GeminiErrors.modelNotFound("gemini-3.1-flash-lite")))
        try {
            backend.chat.answer(request(model = "gemini-3.7-flash", webSearch = false), ChatRecorder(), usage)
            fail("expected ModelUnavailable")
        } catch (e: AiException) {
            assertEquals(AiError.ModelUnavailable("gemini-3.7-flash"), e.error)
            assertEquals("HTTP 404 · NOT_FOUND", e.diagnostic)
        }
        // 3.7 and what comes after it in the chain, each once.
        assertEquals(3, server.requestCount)
        assertTrue(usage.records.isEmpty())
    }

    @Test
    fun `a blocked API or a rejected auth key is no model problem and carries its diagnostic`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.httpError(403, GeminiErrors.API_KEY_SERVICE_BLOCKED))
        try {
            backend.chat.answer(request(), ChatRecorder(), usage)
            fail("expected the blocked API")
        } catch (e: AiException) {
            assertEquals(AiError.GeminiAccess(GeminiProblem.API_BLOCKED), e.error)
            assertEquals("HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED", e.diagnostic)
        }
        server.enqueue(GeminiSse.httpError(401, GeminiErrors.AUTH_KEY_REJECTED, eventStream = false))
        try {
            backend.chat.answer(request(auth = auth(key = GeminiSse.AQ_KEY)), ChatRecorder(), usage)
            fail("expected the rejected auth key")
        } catch (e: AiException) {
            assertEquals(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), e.error)
            assertEquals("HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED", e.diagnostic)
            assertFalse(e.diagnostic!!.contains(GeminiSse.AQ_KEY))
        }
        // One request each: no search fallback, no model fallback, no retry.
        assertEquals(2, server.requestCount)
        // A stream error chunk names itself, too.
        server.enqueue(
            GeminiSse.response(GeminiSse.text("Anfang"), GeminiSse.chunk("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""")),
        )
        try {
            backend.chat.answer(request(webSearch = false), ChatRecorder(), usage)
            fail("expected the stream error")
        } catch (e: AiException) {
            assertEquals("Stream · 503 · UNAVAILABLE", e.diagnostic)
        }
    }

    @Test
    fun `a key echoed into the error body stays out of the details, before and inside the stream`() = runBlocking<Unit> {
        listOf(GeminiSse.ECHO_KEY, GeminiSse.ECHO_AQ_KEY).forEach { key ->
            // Refused before the stream: the key is in the reason, the code, the quota id, the metadata, the links.
            server.enqueue(GeminiSse.httpError(400, GeminiErrors.keyEcho(key)))
            try {
                backend.chat.answer(request(auth = auth(key = key)), ChatRecorder(), usage)
                fail("expected the refused key")
            } catch (e: AiException) {
                assertEquals(AiError.InvalidKey, e.error)
                assertEquals("HTTP 400 · INVALID_ARGUMENT", e.diagnostic)
            }
            // The same body as an error chunk inside a 200 stream.
            server.enqueue(GeminiSse.response(GeminiSse.text("Anfang"), GeminiSse.chunk(GeminiErrors.keyEcho(key))))
            try {
                backend.chat.answer(request(auth = auth(key = key), webSearch = false), ChatRecorder(), usage)
                fail("expected the stream error")
            } catch (e: AiException) {
                assertEquals(AiError.InvalidKey, e.error)
                assertEquals("Stream · INVALID_ARGUMENT", e.diagnostic)
            }
        }
        // One request each: an invalid key is neither retried nor sent to another model or without search.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `rate limits and overload of a paid key are retried before the first event`() = runBlocking<Unit> {
        server.enqueue(GeminiSse.httpError(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted.", retryDelay = "2s")))
        server.enqueue(GeminiSse.httpError(503, GeminiSse.rpc(503, "UNAVAILABLE", "The model is overloaded.")))
        server.enqueue(ok())

        val result = backend.chat.answer(request(), ChatRecorder(), usage)

        assertEquals("Ok.", result.text)
        assertFalse(result.withoutWebSearch)
        assertEquals(3, server.requestCount)
        assertEquals(2, slept.size)
        assertEquals(2.seconds, slept[0])
        assertEquals(3, usage.checks)
        assertEquals(1, usage.records.size)
    }

    @Test
    fun `the free quota is reported kindly and only retried for a short named wait`() = runBlocking<Unit> {
        val free = auth(freeTier = true)
        // A daily (or long) quota: one request, no waiting, the friendly G3 text.
        server.enqueue(GeminiSse.httpError(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded for metric: generate_content_free_tier_requests", retryDelay = "37s")))
        val error = assertError(AiError.QuotaExhausted) { backend.chat.answer(request(auth = free), ChatRecorder(), usage) }
        assertEquals(
            "Gemini-Gratiskontingent für den Moment aufgebraucht – in ein paar Minuten bzw. morgen geht's weiter.",
            AiTexts.error(error, ProviderCatalog.GEMINI),
        )
        server.enqueue(GeminiSse.httpError(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded.")))
        assertError(AiError.QuotaExhausted) { backend.chat.answer(request(auth = free), ChatRecorder(), usage) }
        assertEquals(2, server.requestCount)
        assertTrue(slept.isEmpty())

        // A per-minute quota with a short wait named by Google: waited for once, then it works.
        server.enqueue(GeminiSse.httpError(429, GeminiSse.rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded.", retryDelay = "12s")))
        server.enqueue(ok())
        assertEquals("Ok.", backend.chat.answer(request(auth = free), ChatRecorder(), usage).text)
        assertEquals(listOf(12.seconds), slept)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `the monthly limit stops a paid request before it is sent`() = runBlocking<Unit> {
        val limited = RecordingUsage(limitReached = true)
        try {
            backend.chat.answer(request(), ChatRecorder(), limited)
            fail("expected the limit")
        } catch (e: AiBlockedException) {
            assertEquals(AiBlock.LIMIT_REACHED, e.block)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a pasted models prefix and odd model ids stay one path segment`() = runBlocking<Unit> {
        server.enqueue(ok())
        backend.chat.answer(request(model = "models/gemini-3.5-flash-lite", webSearch = false), ChatRecorder(), usage)
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent", server.takeJson().first.requestUrl!!.encodedPath)
        // Priced as the model that answered (`modelVersion` of the fixture).
        assertEquals("gemini-3.8-flash", usage.records.single().first)

        server.enqueue(ok())
        backend.chat.answer(request(model = "my model?x", webSearch = false), ChatRecorder(), usage)
        assertEquals("/v1beta/models/my%20model%3Fx:streamGenerateContent", server.takeJson().first.requestUrl!!.encodedPath)
    }

    @Test
    fun `the conversation always ends with the user and leads with the context`() {
        val turns = GeminiRequests.turns(
            "Kontext",
            listOf(
                ApiMessage(true, "Frage 1"),
                ApiMessage(true, "Nachtrag"),
                ApiMessage(false, ""),
                ApiMessage(false, "Antwort 1"),
                ApiMessage(true, "Frage 2"),
                ApiMessage(false, "Vorweggenommene Antwort"),
            ),
        )
        assertEquals(
            listOf(
                GeminiRequests.Turn("user", listOf("Kontext", "Frage 1", "Nachtrag")),
                GeminiRequests.Turn("model", listOf("Antwort 1")),
                GeminiRequests.Turn("user", listOf("Frage 2")),
            ),
            turns,
        )
    }

    @Test
    fun `Gemini has no Live`() {
        assertNull(backend.live)
    }

    private suspend fun assertError(expected: AiError, block: suspend () -> Unit): AiError {
        try {
            block()
            fail("expected $expected")
        } catch (e: AiException) {
            assertEquals(expected, e.error)
            return e.error
        }
        error("unreachable")
    }
}
