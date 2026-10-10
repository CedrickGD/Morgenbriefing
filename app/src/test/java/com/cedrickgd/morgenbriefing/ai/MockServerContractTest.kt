package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeClients
import com.cedrickgd.morgenbriefing.ai.compat.CompatBackend
import com.cedrickgd.morgenbriefing.ai.compat.CompatDialect
import com.cedrickgd.morgenbriefing.ai.compat.FreeModelSource
import com.cedrickgd.morgenbriefing.ai.compat.OpenRouterFreeModels
import com.cedrickgd.morgenbriefing.ai.gemini.GeminiBackend
import com.cedrickgd.morgenbriefing.ai.gemini.GeminiSse
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.openai.ResponsesBackend
import com.cedrickgd.morgenbriefing.ai.openai.ResponsesDialect
import com.cedrickgd.morgenbriefing.data.AppJson
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Opt-in contract test (docs/AI-PROVIDERS.md §14): the real engines of every family against
 * `tools/mock-ai-server.mjs`, so the mock and the parsers keep speaking the same wire formats —
 * chat with search, Live with the correction loop, key checks and the error switches. Skipped
 * unless `MOCK_AI_SERVER` names a running mock:
 *
 * ```
 * node tools/mock-ai-server.mjs --delay 0
 * MOCK_AI_SERVER=http://127.0.0.1:8787 ./gradlew :app:testDebugUnitTest --tests "*MockServerContractTest"
 * ```
 *
 * Fake keys only.
 */
class MockServerContractTest {
    private val base = System.getenv("MOCK_AI_SERVER")?.trimEnd('/').orEmpty()

    /** The own server must use a name on the cleartext allowlist: localhost, not 127.0.0.1. */
    private val serverBase get() = base.replace("127.0.0.1", "localhost")
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val retry = RetryPolicy(sleep = {})

    @Before
    fun needsMockServer() {
        assumeTrue("MOCK_AI_SERVER not set: start tools/mock-ai-server.mjs to run this", base.isNotEmpty())
    }

    private class Recorder : ChatTurnListener {
        val text = StringBuilder()
        var sources: List<SourceRef> = emptyList()
        var suggestions: String? = null

        override fun onText(delta: String) {
            text.append(delta)
        }

        override fun onSources(sources: List<SourceRef>) {
            this.sources = sources
        }

        override fun onSearchSuggestions(html: String) {
            suggestions = html
        }
    }

    /** Answers every `publish_articles` call with the app's real validation. */
    private class Publisher : LiveResearchListener {
        val calls = mutableListOf<String>()

        override suspend fun onPublish(rawInput: String): PublishReply {
            calls += rawInput
            val outcome = LivePublish.validate(rawInput, Topics.ALL.map { it.id }.toSet(), emptySet(), Instant.now())
            return PublishReply(outcome.problems.isEmpty() && outcome.accepted.isNotEmpty(), LivePublish.replyText(outcome))
        }
    }

    private fun chat(backend: AiBackend, auth: ProviderAuth, model: String, search: Boolean = true): Pair<ChatTurnResult, Recorder> =
        runBlocking {
            val recorder = Recorder()
            val request = ChatTurnRequest(
                auth = auth,
                model = model,
                systemPrompt = AiPrompts.chatSystem(search),
                context = "KONTEXT",
                history = listOf(ApiMessage(fromUser = true, text = "Was bedeutet das?")),
                webSearch = search,
            )
            backend.chat.answer(request, recorder, RecordingUsage()) to recorder
        }

    private fun live(backend: AiBackend, auth: ProviderAuth, model: String): Pair<LiveResearchResult, Publisher> = runBlocking {
        val publisher = Publisher()
        backend.live!!.research(LiveResearchRequest(auth, model, "SYSTEM", "Recherchiere"), publisher, RecordingUsage()) to publisher
    }

    private fun check(backend: AiBackend, auth: ProviderAuth, model: String): AiKeyCheck =
        runBlocking { backend.keys.check(auth, model, RecordingUsage()).check }

    private fun expectError(expected: AiError, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: AiException) {
            assertEquals(expected, e.error)
        }
    }

    @Test
    fun `gemini - grounded chat, free key, free-tier fallback, quota and key check`() {
        val model = "gemini-3.8-flash"
        val (grounded, recorder) = chat(GeminiBackend(http, retry), ProviderAuth(AiProviderId.GEMINI, "AIza-test-invalid", "$base/v1beta"), model)
        assertEquals(TurnEnd.COMPLETE, grounded.end)
        assertTrue(grounded.searchSuggestionsHtml!!.contains("class=\"chip\""))
        assertEquals(grounded.searchSuggestionsHtml, recorder.suggestions)
        assertEquals(3, grounded.sources.size)
        assertTrue(recorder.text.contains("Kurz gesagt"))

        val free = ProviderAuth(AiProviderId.GEMINI, "AQ.test-invalid", "$base/v1beta", freeTier = true)
        val (answer, _) = chat(GeminiBackend(http, retry), free, model, search = false)
        assertNull(answer.searchSuggestionsHtml)
        assertFalse(answer.withoutWebSearch)

        // A key set to "Mit Abrechnung" that is a free one after all: answered again without search.
        for (mode in listOf("free-tier-search", "free-tier-search-400")) {
            val auth = ProviderAuth(AiProviderId.GEMINI, "AIza-test-$mode", "$base/mock/$mode/v1beta")
            assertTrue(mode, chat(GeminiBackend(http, retry), auth, model).first.withoutWebSearch)
        }

        val quota = ProviderAuth(AiProviderId.GEMINI, "AQ.test-invalid", "$base/mock/429/v1beta", freeTier = true)
        expectError(AiError.QuotaExhausted) { chat(GeminiBackend(http, retry), quota, model, search = false) }
        expectError(AiError.InvalidKey) {
            chat(GeminiBackend(http, retry), ProviderAuth(AiProviderId.GEMINI, "AIza-test-invalid", "$base/mock/400/v1beta"), model)
        }
        assertEquals(AiKeyCheck.OK, check(GeminiBackend(http, retry), free, model))
    }

    @Test
    fun `gemini - refused keys and projects, model fallback in chat and key test`() {
        val model = "gemini-3.8-flash"
        fun auth(mode: String, freeTier: Boolean = true) = ProviderAuth(AiProviderId.GEMINI, "AQ.test-$mode", "$base/mock/$mode/v1beta", freeTier = freeTier)

        // Every cause of GE §12.4 comes back as itself, in the key test and in the chat — with a key-free diagnostic.
        val causes = mapOf(
            "401-auth-key" to (AiKeyCheck.AUTH_KEY_REJECTED to AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED)),
            "403-blocked" to (AiKeyCheck.API_BLOCKED to AiError.GeminiAccess(GeminiProblem.API_BLOCKED)),
            "403-disabled" to (AiKeyCheck.API_BLOCKED to AiError.GeminiAccess(GeminiProblem.API_BLOCKED)),
            "403-restricted" to (AiKeyCheck.KEY_RESTRICTED to AiError.GeminiAccess(GeminiProblem.KEY_RESTRICTED)),
            "400-region" to (AiKeyCheck.FREE_TIER_UNAVAILABLE to AiError.GeminiAccess(GeminiProblem.FREE_TIER_UNAVAILABLE)),
        )
        causes.forEach { (mode, expected) ->
            val outcome = runBlocking { GeminiBackend(http, retry).keys.check(auth(mode), model, RecordingUsage()) }
            assertEquals(mode, expected.first, outcome.check)
            assertTrue(mode, outcome.diagnostic!!.startsWith("HTTP "))
            assertFalse(mode, outcome.diagnostic!!.contains("AQ.test"))
            try {
                chat(GeminiBackend(http, retry), auth(mode), model, search = false)
                fail("$mode: expected an error")
            } catch (e: AiException) {
                assertEquals(mode, expected.second, e.error)
                assertTrue(mode, e.diagnostic!!.startsWith("HTTP "))
            }
        }

        // A server that echoes the key it got into every field of the error body: the Details still hold no key.
        for (key in listOf(GeminiSse.ECHO_KEY, GeminiSse.ECHO_AQ_KEY)) {
            val echo = ProviderAuth(AiProviderId.GEMINI, key, "$base/mock/400-key-echo/v1beta", freeTier = true)
            val outcome = runBlocking { GeminiBackend(http, retry).keys.check(echo, model, RecordingUsage()) }
            assertEquals(AiKeyCheck.INVALID, outcome.check)
            assertEquals("HTTP 400 · INVALID_ARGUMENT", outcome.diagnostic)
            try {
                chat(GeminiBackend(http, retry), echo, model, search = false)
                fail("400-key-echo: expected an error")
            } catch (e: AiException) {
                assertEquals(AiError.InvalidKey, e.error)
                assertEquals("HTTP 400 · INVALID_ARGUMENT", e.diagnostic)
            }
        }

        // 3.8 gone for this project (404) or without free quota (limit 0): 3.7 answers, the key test says so.
        for (mode in listOf("model-gone", "model-no-quota")) {
            val (answer, _) = chat(GeminiBackend(http, retry), auth(mode), model, search = false)
            assertEquals(mode, "gemini-3.7-flash", answer.fallbackModel)
            assertEquals(mode, TurnEnd.COMPLETE, answer.end)
            val outcome = runBlocking { GeminiBackend(http, retry).keys.check(auth(mode), model, RecordingUsage()) }
            assertEquals(mode, AiKeyCheck.OK, outcome.check)
            assertEquals(mode, ModelFallback(model, "gemini-3.7-flash"), outcome.fallback)
        }
        // With billing and search, too.
        val (searched, _) = chat(GeminiBackend(http, retry), auth("model-gone", freeTier = false), model)
        assertEquals("gemini-3.7-flash", searched.fallbackModel)
        assertTrue(searched.searchSuggestionsHtml!!.contains("class=\"chip\""))
    }

    @Test
    fun `responses family - chat, live with correction, key checks`() {
        listOf(
            Triple(ResponsesDialect.OPENAI, "sk-test-invalid", "gpt-6.1-sol"),
            Triple(ResponsesDialect.XAI, "xai-test-invalid", "grok-4.3"),
            Triple(ResponsesDialect.PERPLEXITY, "pplx-test-invalid", "google/gemini-3.8-flash"),
        ).forEach { (dialect, key, model) ->
            val backend = ResponsesBackend(http, dialect, retry)
            val auth = ProviderAuth(dialect.provider, key, "$base/v1")
            val (answer, _) = chat(backend, auth, model)
            assertEquals(dialect.name, TurnEnd.COMPLETE, answer.end)
            assertTrue(dialect.name, answer.sources.isNotEmpty())
            // Perplexity's "[1]" markers (split across chunks) never reach the text.
            assertFalse(dialect.name, answer.text.contains("[1"))

            val (published, publisher) = live(backend, auth, model)
            assertTrue(dialect.name, published.published)
            assertEquals(1, publisher.calls.size)
            val corrected = live(backend, ProviderAuth(dialect.provider, key, "$base/mock/live-reject/v1"), model)
            assertTrue(dialect.name, corrected.first.published)
            assertEquals(dialect.name, 2, corrected.second.calls.size)

            assertEquals(dialect.name, AiKeyCheck.OK, check(backend, auth, model))
            assertEquals(dialect.name, AiKeyCheck.INVALID, check(backend, ProviderAuth(dialect.provider, "sk-reject", "$base/v1"), model))
        }
    }

    @Test
    fun `chat completions family - chat, openrouter live, key checks`() {
        listOf(
            Triple(CompatDialect.OPENROUTER, "sk-or-v1-test-invalid", "google/gemini-3.8-flash"),
            Triple(CompatDialect.MISTRAL, "mistral-test-invalid", "mistral-medium-3-5"),
            Triple(CompatDialect.DEEPSEEK, "sk-test-invalid", "deepseek-flash"),
            Triple(CompatDialect.CUSTOM, null, "llama3.3"),
        ).forEach { (dialect, key, model) ->
            val backend = CompatBackend(http, dialect, retry)
            val auth = ProviderAuth(dialect.provider, key, if (dialect == CompatDialect.CUSTOM) "$serverBase/v1" else "$base/v1")
            val search = dialect == CompatDialect.OPENROUTER
            val (answer, recorder) = chat(backend, auth, model, search)
            assertEquals(dialect.name, TurnEnd.COMPLETE, answer.end)
            assertTrue(dialect.name, recorder.text.contains("Kurz gesagt"))
            if (search) {
                assertTrue(answer.sources.isNotEmpty())
                val (published, publisher) = live(backend, auth, model)
                assertTrue(published.published)
                assertEquals(1, publisher.calls.size)
            }
            assertEquals(dialect.name, AiKeyCheck.OK, check(backend, auth, model))
        }
        val noCredit = ProviderAuth(AiProviderId.DEEPSEEK, "sk-test-invalid", "$base/mock/402/v1")
        assertEquals(AiKeyCheck.OK_NO_CREDIT, check(CompatBackend(http, CompatDialect.DEEPSEEK, retry), noCredit, "deepseek-flash"))
    }

    @Test
    fun `openrouter free tariff - free models, key status and the day's quota`() = runBlocking<Unit> {
        // The public model list: only the free text chat models, Gemma 4 31B first.
        val cache = File.createTempFile("openrouter-free", ".json").apply { delete() }
        val list = OpenRouterFreeModels(http, cache, AppJson).list("$base/v1")
        cache.delete()
        assertEquals(FreeModelSource.LIVE, list.source)
        assertEquals(
            listOf(
                "google/gemma-4-31b-it:free",
                "google/gemma-4-26b-a4b-it:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "nvidia/nemotron-3-super-120b-a12b:free",
                "openrouter/free",
                "inclusionai/ling-3.1-flash",
            ),
            list.models.map { it.id },
        )

        val backend = CompatBackend(http, CompatDialect.OPENROUTER, retry)
        val key = "sk-or-v1-test-invalid"
        val model = "google/gemma-4-31b-it:free"
        fun auth(mode: String, freeTier: Boolean = true) = ProviderAuth(AiProviderId.OPENROUTER, key, "$base/mock/$mode/v1", freeTier = freeTier)

        // GET /key: an account without credits, 37 of 50 free requests left today.
        val status = backend.keys.check(auth("free-tier"), model, RecordingUsage())
        assertEquals(KeyCheckOutcome(AiKeyCheck.OK, openRouter = OpenRouterKeyInfo(freeTier = true, freeRequestsLeft = 37, freeRequestsPerDay = 50)), status)
        assertEquals(AiKeyCheck.OK_NO_CREDIT, check(backend, auth("free-tier", freeTier = false), "google/gemini-3.8-flash"))
        assertEquals(0, backend.keys.check(auth("free-tier,429-free"), model, RecordingUsage()).openRouter!!.freeRequestsLeft)

        // A free answer without web search.
        val (answer, recorder) = chat(backend, ProviderAuth(AiProviderId.OPENROUTER, key, "$base/v1", freeTier = true), model, search = false)
        assertEquals(TurnEnd.COMPLETE, answer.end)
        assertTrue(recorder.text.contains("Ohne Websuche"))
        // The day's free requests used up: said at once; the minute's limit is a short wait.
        expectError(AiError.QuotaExhausted) { chat(backend, auth("429-free"), model, search = false) }
        expectError(AiError.RateLimited) { chat(backend, auth("429-free-min"), model, search = false) }
    }

    @Test
    fun `gemini - a precondition that is not about the free tier stays a refused request`() {
        val auth = ProviderAuth(AiProviderId.GEMINI, "AQ.test-invalid", "$base/mock/400-precondition/v1beta", freeTier = true)
        expectError(AiError.Unknown("Gemini hat die Anfrage abgelehnt (HTTP 400).")) {
            chat(GeminiBackend(http, retry), auth, "gemini-3.8-flash", search = false)
        }
    }

    @Test
    fun `claude - chat, live, key check and the workspace error`() {
        val backend = ClaudeBackend(ClaudeClients())
        val model = "claude-sonnet-5-5"
        val auth = ProviderAuth(AiProviderId.ANTHROPIC, "sk-ant-test-invalid", base)
        val (answer, recorder) = chat(backend, auth, model)
        assertEquals(TurnEnd.COMPLETE, answer.end)
        assertTrue(recorder.text.contains("Kurz gesagt"))
        assertTrue(answer.sources.isNotEmpty())
        val (published, publisher) = live(backend, auth, model)
        assertTrue(published.published)
        assertEquals(1, publisher.calls.size)
        assertEquals(AiKeyCheck.OK, check(backend, auth, model))
        val workspace = ProviderAuth(AiProviderId.ANTHROPIC, "sk-ant-test-invalid", "$base/mock/400-workspace")
        assertEquals(AiKeyCheck.WORKSPACE_REQUIRED, check(backend, workspace, model))
    }
}
