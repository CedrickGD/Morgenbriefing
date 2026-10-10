package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.AppJson
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.data.withAiKey
import com.cedrickgd.morgenbriefing.data.withProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

class ChatServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = MutableStateFlow(AppSettings().withAiKey())
    private val usageState = MutableStateFlow(UsageSnapshot(month = "2026-10", limitUsd = 5.0))
    private val usage = RecordingUsage()
    private val engine = FakeChatEngine()
    private val clock = Clock.fixed(Instant.parse("2026-10-05T12:20:00Z"), ZoneId.of("Europe/Berlin"))
    private val article = ChatContext(date = "2026-10-05", articleKey = "region-buslinie")

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeChatEngine : ChatEngine {
        val requests = mutableListOf<ChatTurnRequest>()
        val scripts = ArrayDeque<suspend (ChatTurnListener) -> ChatTurnResult>()

        override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
            synchronized(requests) { requests += request }
            val script = scripts.removeFirstOrNull() ?: { l ->
                l.onText("Die ")
                l.onSearch("Buslinie Bad Düben")
                l.onText("Antwort.")
                val sources = listOf(SourceRef("LVZ", "https://www.lvz.de/a"))
                l.onSources(sources)
                ChatTurnResult("Die Antwort.", sources, TurnEnd.COMPLETE)
            }
            return script(listener)
        }
    }

    private val articleContexts = ChatContextSource { ctx ->
        if (ctx.articleKey == null) LoadedChatContext(AiPrompts.BRIEF_TITLE, "Kontext: Briefing")
        else LoadedChatContext("Neue Buslinie nach Leipzig", "Kontext: Artikel ${ctx.articleKey}")
    }

    private fun access(engine: ChatEngine? = this.engine, apiKey: String? = "test-key") =
        FakeAiAccess(settings, usageState, usage, engine?.let { FakeBackend(it) }, apiKey)

    private fun service(
        engine: ChatEngine? = this.engine,
        apiKey: String? = "test-key",
        contexts: ChatContextSource = articleContexts,
        access: AiProviderAccess = access(engine, apiKey),
    ) = ChatServiceImpl(
        scope = scope,
        access = access,
        store = ChatStore(folder.root.resolve("chat"), AppJson),
        contexts = contexts,
        clock = clock,
    )

    private suspend fun ChatSession.await(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(10_000) { state.first(predicate) }

    private fun ChatUiState.settled(count: Int) = messages.size == count && !busy && messages.none { it.status == ChatMessage.Status.STREAMING }

    @Test
    fun `an answer streams into the state, is saved and restored`() = runBlocking<Unit> {
        val session = service().session(article)
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS, session.await { it.title.isNotEmpty() }.suggestions)
        assertEquals("Neue Buslinie nach Leipzig", session.state.value.title)

        session.send("  Worum geht es?  ")
        val done = session.await { it.settled(2) }

        val (question, answer) = done.messages
        assertEquals("Worum geht es?", question.text)
        assertEquals(ChatMessage.Role.ASSISTANT, answer.role)
        assertEquals("Die Antwort.", answer.text)
        assertEquals(ChatMessage.Status.DONE, answer.status)
        assertEquals("https://www.lvz.de/a", answer.sources.single().url)
        assertTrue(done.suggestions.isEmpty())
        assertEquals(null, done.activity)

        val request = engine.requests.single()
        assertEquals("Kontext: Artikel region-buslinie", request.context)
        assertEquals(AiPrompts.chatSystem(webSearch = true), request.systemPrompt)
        assertEquals("claude-sonnet-5-5", request.model)
        assertEquals(1, request.history.size)
        assertTrue(request.history.single().text.startsWith("Worum geht es?\n\n(Heute ist Montag, 5. Oktober 2026, es ist 14:20 Uhr.)"))

        // A fresh process restores the conversation from disk.
        val restored = service().session(article).await { it.messages.size == 2 }
        assertEquals(done.messages.map { it.text }, restored.messages.map { it.text })
        assertEquals("Neue Buslinie nach Leipzig", restored.title)
    }

    @Test
    fun `follow-ups replay the history byte-identically with the date only once`() = runBlocking<Unit> {
        val session = service().session(article)
        session.send("Erste Frage")
        session.await { it.settled(2) }
        session.send("Zweite Frage")
        session.await { it.settled(4) }

        val second = engine.requests[1].history
        assertEquals(listOf(true, false, true), second.map { it.fromUser })
        assertEquals(engine.requests[0].history[0], second[0])
        assertEquals("Die Antwort.", second[1].text)
        assertEquals("Zweite Frage", second[2].text)
        assertFalse(second[2].text.contains("Heute ist"))
    }

    @Test
    fun `refused answers are explained and left out of the history`() = runBlocking<Unit> {
        engine.scripts += { l ->
            l.onText("Teil")
            ChatTurnResult("", emptyList(), TurnEnd.REFUSED, refusalCategory = "cyber")
        }
        val session = service().session(article)
        session.send("Heikle Frage")
        val refused = session.await { it.settled(2) }.messages.last()
        assertEquals(ChatMessage.Status.FAILED, refused.status)
        assertEquals(AiError.Refusal("cyber"), refused.error)
        assertEquals("", refused.text)

        session.send("Andere Frage")
        session.await { it.settled(4) }
        val history = engine.requests[1].history
        assertEquals(1, history.size)
        assertTrue(history.single().text.startsWith("Andere Frage"))
        assertTrue(history.single().text.contains("Heute ist"))
    }

    @Test
    fun `truncated answers keep their text and a marker`() = runBlocking<Unit> {
        engine.scripts += { l ->
            l.onText("Lange Antwort")
            ChatTurnResult("Lange Antwort", emptyList(), TurnEnd.TRUNCATED)
        }
        val session = service().session(article)
        session.send("Erzähl alles")
        val answer = session.await { it.settled(2) }.messages.last()
        assertEquals(ChatMessage.Status.DONE, answer.status)
        assertEquals(AiError.Truncated, answer.error)
        assertEquals("Lange Antwort", answer.text)
    }

    @Test
    fun `errors can be retried`() = runBlocking<Unit> {
        engine.scripts += { throw AiException(AiError.Network) }
        val session = service().session(article) as RetryableChatSession
        session.send("Frage")
        val failed = session.await { it.settled(2) }.messages.last()
        assertEquals(AiError.Network, failed.error)
        assertEquals(ChatMessage.Status.FAILED, failed.status)

        session.retry()
        val retried = session.await { it.settled(2) && it.messages.last().status == ChatMessage.Status.DONE }
        assertEquals(listOf("Frage", "Die Antwort."), retried.messages.map { it.text })
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `a question refused for its gemini key is sent again with openrouter for free`() = runBlocking<Unit> {
        settings.value = AppSettings().withAiKey(AiProviderId.GEMINI)
        engine.scripts += { throw AiException(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED)) }
        val session = service().session(article) as RetryableChatSession
        session.send("Frage")
        val failed = session.await { it.settled(2) }.messages.last()
        assertEquals(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), failed.error)
        assertEquals(AiProviderId.GEMINI, failed.provider)

        // OpenRouter set up on its free tariff and chosen for the chat: "Nochmal senden" asks it.
        settings.value = AppSettings().withAiKey(AiProviderId.OPENROUTER)
        val switched = session.await { it.provider == AiProviderId.OPENROUTER }
        assertEquals("Ohne Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar", switched.note)
        session.retry()
        val answered = session.await { it.settled(2) && it.messages.last().status == ChatMessage.Status.DONE }
        assertEquals(listOf("Frage", "Die Antwort."), answered.messages.map { it.text })
        assertEquals(AiProviderId.OPENROUTER, answered.messages.last().provider)
        // The free tariff: Gemma 4, no web search, the free quota instead of costs.
        val request = engine.requests.last()
        assertEquals("google/gemma-4-31b-it:free", request.model)
        assertFalse(request.webSearch)
        assertTrue(request.auth.freeTier)
    }

    @Test
    fun `a refused key is reported with the key the turn used, other errors are not`() = runBlocking<Unit> {
        val access = access(apiKey = "sk-ant-old")
        engine.scripts += { throw AiException(AiError.Network) }
        engine.scripts += { throw AiException(AiError.InvalidKey) }
        val session = service(access = access).session(article)
        session.send("Frage")
        assertEquals(AiError.Network, session.await { it.settled(2) }.messages.last().error)
        assertTrue(access.rejected.isEmpty())

        session.send("Noch eine")
        assertEquals(AiError.InvalidKey, session.await { it.settled(4) }.messages.last().error)
        assertEquals(listOf(AiProviderId.ANTHROPIC to "sk-ant-old"), access.rejected)
    }

    @Test
    fun `stop keeps the partial answer`() = runBlocking<Unit> {
        val streaming = CompletableDeferred<Unit>()
        engine.scripts += { l ->
            l.onText("Bis hier")
            streaming.complete(Unit)
            awaitCancellation()
        }
        val session = service().session(article)
        session.send("Frage")
        withTimeout(10_000) { streaming.await() }
        assertTrue(session.state.value.busy)
        session.stop()
        val stopped = session.await { it.settled(2) }
        assertEquals("Bis hier", stopped.messages.last().text)
        assertEquals(ChatMessage.Status.DONE, stopped.messages.last().status)
    }

    @Test
    fun `stop while the context still loads ends the turn and the chat keeps working`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val loading = CompletableDeferred<Unit>()
        val contexts = ChatContextSource { ctx ->
            when (calls.getAndIncrement()) {
                // At restore the article is not available (offline) ...
                0 -> null
                // ... on send its download hangs until the user taps stop ...
                1 -> {
                    loading.complete(Unit)
                    awaitCancellation()
                }
                // ... and later it loads fine.
                else -> articleContexts.load(ctx)
            }
        }
        val session = service(contexts = contexts).session(article) as RetryableChatSession
        session.send("Frage")
        withTimeout(10_000) { loading.await() }
        assertTrue(session.state.value.busy)

        session.stop()
        val stopped = session.await { it.settled(1) }
        assertEquals(ChatMessage.Role.USER, stopped.messages.single().role)
        assertTrue(engine.requests.isEmpty())

        // Not stuck: the question can be asked again.
        session.retry()
        val answered = session.await { it.settled(2) }
        assertEquals(listOf("Frage", "Die Antwort."), answered.messages.map { it.text })
    }

    @Test
    fun `clear forgets the conversation`() = runBlocking<Unit> {
        val service = service()
        val session = service.session(article)
        session.send("Frage")
        session.await { it.settled(2) }
        val file = ChatStore(folder.root.resolve("chat"), AppJson).fileFor(article)
        assertTrue(file.isFile)

        session.clear()
        val cleared = session.await { it.messages.isEmpty() && it.suggestions.isNotEmpty() }
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS, cleared.suggestions)
        withTimeout(10_000) { while (file.exists()) kotlinx.coroutines.delay(20) }
    }

    @Test
    fun `a question left without answer is restored and can be retried`() = runBlocking<Unit> {
        val store = ChatStore(folder.root.resolve("chat"), AppJson)
        val schema1Key = "https://www.tagesschau.de/inland/artikel-100.html"
        val context = ChatContext("2026-10-05", schema1Key)
        store.save(
            StoredChat(
                date = context.date,
                articleKey = schema1Key,
                title = "Alte Meldung",
                context = "Kontext: gespeichert",
                messages = listOf(StoredChatMessage(id = "q1", role = StoredChatMessage.ROLE_USER, text = "Und dann?", apiText = "Und dann?\n\n(Heute ist …)")),
            ),
        )
        // URL keys (schema 1) become a hashed, file-safe name.
        assertTrue(store.fileFor(context).name.matches(Regex("""2026-10-05__h[0-9a-f]{24}\.json""")))

        val session = service().session(context) as RetryableChatSession
        val restored = session.await { it.messages.size == 1 }
        assertEquals("Alte Meldung", restored.title)

        session.retry()
        val answered = session.await { it.settled(2) }
        assertEquals(listOf("Und dann?", "Die Antwort."), answered.messages.map { it.text })
        // The stored context is reused, not reloaded — the prompt prefix stays identical.
        assertEquals("Kontext: gespeichert", engine.requests.single().context)
    }

    @Test
    fun `same context gives the same session, the brief has its own suggestions`() = runBlocking<Unit> {
        val service = service()
        assertTrue(service.session(article) === service.session(ChatContext("2026-10-05", "region-buslinie")))
        val brief = service.session(ChatContext("2026-10-05", null))
        assertEquals(AiPrompts.BRIEF_TITLE, brief.state.value.title)
        assertEquals(AiPrompts.BRIEF_SUGGESTIONS, brief.state.value.suggestions)
    }

    @Test
    fun `without web search the examples ask nothing that needs other sources`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true) }
        val session = service().session(article)
        session.await { it.provider == AiProviderId.GEMINI && it.suggestions == AiPrompts.ARTICLE_SUGGESTIONS_OFFLINE }

        // The tariff switched to billing: the shown examples follow …
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        session.await { it.suggestions == AiPrompts.ARTICLE_SUGGESTIONS }
        // … and a provider without any web search gets the context-only ones again.
        settings.value = AppSettings().withProvider(AiProviderId.MISTRAL) { it.copy(hasKey = true) }
        session.await { it.provider == AiProviderId.MISTRAL && it.suggestions == AiPrompts.ARTICLE_SUGGESTIONS_OFFLINE }

        // Once a question was asked no examples come back with a provider switch; a cleared chat gets the fitting ones.
        session.send("Frage")
        session.await { it.settled(2) }
        settings.value = AppSettings().withAiKey()
        assertTrue(session.await { it.provider == AiProviderId.ANTHROPIC }.suggestions.isEmpty())
        settings.value = AppSettings().withProvider(AiProviderId.DEEPSEEK) { it.copy(hasKey = true) }
        session.await { it.provider == AiProviderId.DEEPSEEK }
        session.clear()
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS_OFFLINE, session.await { it.messages.isEmpty() && it.suggestions.isNotEmpty() }.suggestions)
    }

    @Test
    fun `blocked states`() = runBlocking<Unit> {
        service(engine = null).session(article).await { it.blocked == AiBlock.UNSUPPORTED_DEVICE }

        settings.value = AppSettings()
        val noKey = service(apiKey = null).session(ChatContext("2026-10-04", "x"))
        noKey.await { it.blocked == AiBlock.NO_KEY }
        noKey.send("Hallo")
        kotlinx.coroutines.delay(200)
        assertTrue(noKey.state.value.messages.isEmpty())

        settings.value = AppSettings().withAiKey()
        // The sink and the usage snapshot read the same numbers in the app.
        usage.limitReached = true
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 5.0, limitUsd = 5.0)
        val limited = service().session(ChatContext("2026-10-03", "y"))
        limited.send("Hallo")
        limited.await { it.blocked == AiBlock.LIMIT_REACHED }
        assertTrue(limited.state.value.messages.isEmpty())
        assertTrue(engine.requests.isEmpty())

        usage.limitReached = false
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 5.5, limitUsd = 5.0)
        limited.await { it.blocked == AiBlock.LIMIT_REACHED }
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 5.5, limitUsd = 10.0)
        limited.await { it.blocked == null }
    }

    @Test
    fun `the session names its provider and the provider of each turn answers`() = runBlocking<Unit> {
        val session = service().session(article)
        val claude = session.await { it.provider != null }
        assertEquals(AiProviderId.ANTHROPIC, claude.provider)
        assertEquals("Claude (Anthropic)", claude.providerName)
        assertEquals(null, claude.note)

        settings.value = AppSettings().withProvider(AiProviderId.OPENAI) { it.copy(hasKey = true, model = "gpt-6-luna") }
        session.await { it.provider == AiProviderId.OPENAI && it.providerName == "OpenAI" }
        session.send("Frage")
        session.await { it.settled(2) }
        val request = engine.requests.single()
        assertEquals(AiProviderId.OPENAI, request.auth.provider)
        assertEquals("gpt-6-luna", request.model)
        assertTrue(request.webSearch)
    }

    @Test
    fun `every answer keeps the provider that wrote it, across a switch and a restart`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true) }
        engine.scripts += { throw AiException(AiError.QuotaExhausted) }
        val session = service().session(article)
        session.await { it.provider == AiProviderId.GEMINI }
        session.send("Erste")
        val gemini = session.await { it.settled(2) }.messages
        assertEquals(null, gemini[0].provider)
        assertEquals(AiProviderId.GEMINI, gemini[1].provider)
        assertEquals(AiError.QuotaExhausted, gemini[1].error)

        // D12: the history stays when the chat provider changes; the old answer stays Gemini's.
        settings.value = AppSettings().withProvider(AiProviderId.OPENAI) { it.copy(hasKey = true) }
        session.await { it.provider == AiProviderId.OPENAI }
        session.send("Zweite")
        val both = session.await { it.settled(4) }.messages
        assertEquals(listOf(null, AiProviderId.GEMINI, null, AiProviderId.OPENAI), both.map { it.provider })

        val restored = service().session(article).await { it.messages.size == 4 }.messages
        assertEquals(listOf(null, AiProviderId.GEMINI, null, AiProviderId.OPENAI), restored.map { it.provider })
    }

    @Test
    fun `answers saved before providers were recorded load without one`() = runBlocking<Unit> {
        val store = ChatStore(folder.root.resolve("chat"), AppJson)
        store.save(
            StoredChat(
                date = article.date,
                articleKey = article.articleKey,
                title = "Neue Buslinie nach Leipzig",
                context = "Kontext: Artikel region-buslinie",
                messages = listOf(
                    StoredChatMessage(id = "q1", role = StoredChatMessage.ROLE_USER, text = "Alt"),
                    StoredChatMessage(id = "a1", role = StoredChatMessage.ROLE_ASSISTANT, text = "Alte Antwort."),
                    StoredChatMessage(id = "q2", role = StoredChatMessage.ROLE_USER, text = "Neuer"),
                    // A provider a newer app version knows: shown under the chat's provider too.
                    StoredChatMessage(id = "a2", role = StoredChatMessage.ROLE_ASSISTANT, text = "Antwort.", provider = "futureai"),
                ),
            ),
        )
        val restored = service().session(article).await { it.messages.size == 4 }.messages
        assertEquals(listOf("Alte Antwort.", "Antwort."), restored.filter { it.role == ChatMessage.Role.ASSISTANT }.map { it.text })
        assertTrue(restored.all { it.provider == null })
    }

    @Test
    fun `a free Gemini key chats without web search, at no cost and past the monthly limit`() = runBlocking<Unit> {
        settings.value = AppSettings().withAiKey(AiProviderId.GEMINI)
        // Spent more than the cap: paid providers are blocked, the free tier is not.
        usage.limitReached = true
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 6.0, limitUsd = 5.0)
        val gemini = object : ChatEngine {
            val requests = mutableListOf<ChatTurnRequest>()
            override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
                synchronized(requests) { requests += request }
                usage.beforeRequest()
                usage.record(request.auth.provider, request.model, TokenUsage(inputTokens = 4_000, outputTokens = 300))
                listener.onText("Aus dem Artikel: …")
                return ChatTurnResult("Aus dem Artikel: …", emptyList(), TurnEnd.COMPLETE)
            }
        }
        val session = service(engine = gemini).session(article)
        val ready = session.await { it.provider == AiProviderId.GEMINI }
        assertEquals(null, ready.blocked)
        assertEquals(GeminiTiers.NO_WEB_SEARCH_NOTE, ready.note)
        assertEquals("Google Gemini", ready.providerName)

        session.send("Worum geht es?")
        val answer = session.await { it.settled(2) }.messages.last()
        assertEquals(ChatMessage.Status.DONE, answer.status)
        val request = gemini.requests.single()
        assertFalse(request.webSearch)
        assertEquals(AiPrompts.chatSystem(webSearch = false), request.systemPrompt)
        assertTrue(request.auth.freeTier)
        assertEquals("gemini-3.8-flash", request.model)
        // Counted as a request, priced at 0 $.
        assertEquals(0.0, usage.records.single().second.reportedCostUsd!!, 0.0)
        assertEquals(listOf(AiProviderId.GEMINI), usage.providers)
    }

    @Test
    fun `a paid Gemini key searches and the limit applies`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 6.0, limitUsd = 5.0)
        val session = service().session(article)
        val limited = session.await { it.provider == AiProviderId.GEMINI && it.blocked != null }
        assertEquals(AiBlock.LIMIT_REACHED, limited.blocked)
        assertEquals(null, limited.note)

        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 1.0, limitUsd = 5.0)
        session.await { it.blocked == null }
        session.send("Was gibt es Neues?")
        session.await { it.settled(2) }
        assertTrue(engine.requests.single().webSearch)
        assertFalse(engine.requests.single().auth.freeTier)
    }

    @Test
    fun `a refused Gemini key or project keeps its details, and only a refused key is marked`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true) }
        val access = access(apiKey = "AQ.test-invalid")
        engine.scripts += {
            throw AiException(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), diagnostic = "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED")
        }
        engine.scripts += {
            throw AiException(AiError.GeminiAccess(GeminiProblem.API_BLOCKED), diagnostic = "HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED")
        }
        // A diagnostic can never hold the key; if it did, it would be dropped.
        engine.scripts += { throw AiException(AiError.Unknown(null), diagnostic = "HTTP 400 · AQ.test-invalid") }
        val session = service(access = access).session(article)
        session.send("Erste")
        val first = session.await { it.settled(2) }.messages.last()
        assertEquals(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), first.error)
        assertEquals("HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED", first.errorDetail)
        assertEquals(listOf(AiProviderId.GEMINI to "AQ.test-invalid"), access.rejected)

        session.send("Zweite")
        val second = session.await { it.settled(4) }.messages.last()
        assertEquals("HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED", second.errorDetail)
        // A blocked API is the project's problem, not the key's.
        assertEquals(1, access.rejected.size)

        session.send("Dritte")
        assertEquals(null, session.await { it.settled(6) }.messages.last().errorDetail)

        // The details survive a restart with the history.
        val restored = service(access = access).session(article).await { it.messages.size == 6 }
        assertEquals(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), restored.messages[1].error)
        assertEquals("HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED", restored.messages[1].errorDetail)
        assertEquals("HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED", restored.messages[3].errorDetail)
    }

    @Test
    fun `a model fallback is kept for the key and said under the answer`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        val access = access(apiKey = "AIza-test-invalid")
        engine.scripts += { l ->
            l.onText("Von 3.7.")
            ChatTurnResult("Von 3.7.", emptyList(), TurnEnd.COMPLETE, fallbackModel = "gemini-3.7-flash")
        }
        engine.scripts += { l ->
            l.onText("Ohne Suche.")
            ChatTurnResult("Ohne Suche.", emptyList(), TurnEnd.COMPLETE, withoutWebSearch = true, fallbackModel = "gemini-3.7-flash")
        }
        val session = service(access = access).session(article)
        session.send("Erste")
        val first = session.await { it.settled(2) }.messages.last()
        val note = "Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.7 Flash."
        assertEquals(note, first.note)
        assertEquals(listOf(listOf(AiProviderId.GEMINI, "AIza-test-invalid", "gemini-3.8-flash", "gemini-3.7-flash")), access.fallbacks)
        // Stored only for the tariff the answer ran on, here with billing (a switch meanwhile dropped it for good).
        assertEquals(listOf(false), access.fallbackFreeTiers)

        session.send("Zweite")
        assertEquals("$note\n${AiTexts.WITHOUT_WEB_SEARCH_NOTE}", session.await { it.settled(4) }.messages.last().note)
    }

    @Test
    fun `a fallback of an active fallback is kept and named for the chosen model`() = runBlocking<Unit> {
        // The key already runs 3.7 instead of the chosen 3.8; now Google refuses 3.7 too and 3.5 Flash-Lite answers.
        val gemini = AiProviderId.GEMINI
        settings.value = AppSettings().withProvider(gemini) {
            it.copy(hasKey = true, geminiTier = GeminiTier.PAID, modelFallback = ModelFallback("gemini-3.8-flash", "gemini-3.7-flash"))
        }
        val access = access(apiKey = "AIza-test-invalid")
        engine.scripts += { l ->
            l.onText("Von 3.5.")
            ChatTurnResult("Von 3.5.", emptyList(), TurnEnd.COMPLETE, fallbackModel = "gemini-3.5-flash-lite")
        }
        val session = service(access = access).session(article)
        session.send("Erste")
        val first = session.await { it.settled(2) }.messages.last()
        // Asked for the active fallback; the new one is stored and named for the chosen model.
        assertEquals("gemini-3.7-flash", engine.requests.single().model)
        assertEquals(listOf(listOf(gemini, "AIza-test-invalid", "gemini-3.8-flash", "gemini-3.5-flash-lite")), access.fallbacks)
        assertEquals("Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.5 Flash-Lite.", first.note)

        // Stored as AiServices does: the next question goes to 3.5 directly, and Settings keeps its note.
        val (_, _, from, to) = access.fallbacks.single()
        settings.value = settings.value.withProvider(gemini) { it.copy(modelFallback = ModelFallback(from as String, to as String)) }
        assertEquals("gemini-3.5-flash-lite", ProviderCatalog.effectiveModel(settings.value.ai, gemini))
        assertEquals("gemini-3.8-flash" to "gemini-3.5-flash-lite", ProviderCatalog.activeFallback(settings.value.ai, gemini))

        // No model of the chain left: the error names the chosen model, not the fallback the user never picked.
        engine.scripts += { throw AiException(AiError.ModelUnavailable("gemini-3.5-flash-lite"), diagnostic = "HTTP 404 · NOT_FOUND") }
        session.send("Zweite")
        val second = session.await { it.settled(4) }.messages.last()
        assertEquals("gemini-3.5-flash-lite", engine.requests[1].model)
        assertEquals(AiError.ModelUnavailable("gemini-3.8-flash"), second.error)
        assertEquals("HTTP 404 · NOT_FOUND", second.errorDetail)
        assertEquals(1, access.fallbacks.size)

        // Without a fallback the refused model is the chosen one and keeps its name.
        settings.value = settings.value.withProvider(gemini) { it.copy(modelFallback = null) }
        engine.scripts += { throw AiException(AiError.ModelUnavailable("gemini-3.8-flash")) }
        session.send("Dritte")
        assertEquals(AiError.ModelUnavailable("gemini-3.8-flash"), session.await { it.settled(6) }.messages.last().error)
        assertEquals("gemini-3.8-flash", engine.requests[2].model)
    }

    @Test
    fun `search suggestions and the fallback note stay with the answer, also after a restart`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        val html = """<div class="container"><a class="chip" href="https://www.google.com/search?q=ezb">EZB Zins</a></div>"""
        engine.scripts += { l ->
            l.onText("Die EZB …")
            l.onSearchSuggestions(html)
            ChatTurnResult("Die EZB …", emptyList(), TurnEnd.COMPLETE, searchSuggestionsHtml = html)
        }
        engine.scripts += { l ->
            l.onText("Ohne Suche.")
            ChatTurnResult("Ohne Suche.", emptyList(), TurnEnd.COMPLETE, withoutWebSearch = true)
        }
        val session = service().session(article)
        session.send("Erste")
        session.await { it.settled(2) }
        session.send("Zweite")
        val done = session.await { it.settled(4) }
        assertEquals(html, done.messages[1].searchSuggestionsHtml)
        assertEquals(null, done.messages[1].note)
        assertEquals(null, done.messages[3].searchSuggestionsHtml)
        assertEquals(AiTexts.WITHOUT_WEB_SEARCH_NOTE, done.messages[3].note)

        val restored = service().session(article).await { it.messages.size == 4 }
        assertEquals(html, restored.messages[1].searchSuggestionsHtml)
        assertEquals(AiTexts.WITHOUT_WEB_SEARCH_NOTE, restored.messages[3].note)
    }

    @Test
    fun `grounded text that fails before its Search Suggestions is neither shown nor kept`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        engine.scripts += { l ->
            // Grounding queries and sources arrive first; the suggestions would come last.
            l.onSearch("EZB Zinsentscheid")
            l.onGrounded()
            l.onSources(listOf(SourceRef("tagesschau.de", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA")))
            l.onText("Die EZB hat heute ")
            throw AiException(AiError.Network)
        }
        val session = service().session(article) as RetryableChatSession
        session.send("Was hat die EZB entschieden?")
        val failed = session.await { it.settled(2) }.messages.last()
        assertEquals(ChatMessage.Status.FAILED, failed.status)
        assertEquals(AiError.Network, failed.error)
        assertEquals("", failed.text)
        assertTrue(failed.sources.isEmpty())
        assertEquals(null, failed.searchSuggestionsHtml)
        // Not on disk either.
        val restored = service().session(article).await { it.messages.size == 2 }.messages.last()
        assertEquals("", restored.text)
        assertTrue(restored.sources.isEmpty())

        // Ungrounded text (no search ran) keeps its words, as for every provider.
        engine.scripts += { l ->
            l.onText("Aus dem Artikel: ")
            throw AiException(AiError.Network)
        }
        session.retry()
        val kept = session.await { it.settled(2) && it.messages.last().text.isNotEmpty() }.messages.last()
        assertEquals("Aus dem Artikel:", kept.text)
        assertEquals(ChatMessage.Status.FAILED, kept.status)
    }

    @Test
    fun `stopping a grounded answer before its Search Suggestions drops it`() = runBlocking<Unit> {
        settings.value = AppSettings().withProvider(AiProviderId.GEMINI) { it.copy(hasKey = true, geminiTier = GeminiTier.PAID) }
        val streaming = CompletableDeferred<Unit>()
        engine.scripts += { l ->
            l.onGrounded()
            l.onText("Laut tagesschau.de ")
            streaming.complete(Unit)
            awaitCancellation()
        }
        val session = service().session(article)
        session.send("Was gibt es Neues?")
        withTimeout(10_000) { streaming.await() }
        session.stop()
        val stopped = session.await { it.settled(1) }
        assertEquals(ChatMessage.Role.USER, stopped.messages.single().role)

        // With its suggestions already there, a stopped grounded answer keeps its text.
        val html = """<div class="container"><a class="chip" href="https://www.google.com/search?q=x">x</a></div>"""
        val again = CompletableDeferred<Unit>()
        engine.scripts += { l ->
            l.onGrounded()
            l.onText("Laut tagesschau.de …")
            l.onSearchSuggestions(html)
            again.complete(Unit)
            awaitCancellation()
        }
        (session as RetryableChatSession).retry()
        withTimeout(10_000) { again.await() }
        session.stop()
        val kept = session.await { it.settled(2) }.messages.last()
        assertEquals("Laut tagesschau.de …", kept.text)
        assertEquals(html, kept.searchSuggestionsHtml)
    }

    @Test
    fun `a provider without backend is blocked as unsupported`() = runBlocking<Unit> {
        settings.value = AppSettings().withAiKey(AiProviderId.MISTRAL)
        val session = service(engine = null).session(article)
        val blocked = session.await { it.provider == AiProviderId.MISTRAL && it.blocked != null }
        assertEquals(AiBlock.UNSUPPORTED_DEVICE, blocked.blocked)
        // Mistral has no web search: the chat says so calmly.
        assertEquals("Ohne Websuche – mit Mistral nicht verfügbar", blocked.note)
    }

    @Test
    fun `api history keeps only usable pairs`() {
        fun user(id: String, text: String) = ChatMessage(id, ChatMessage.Role.USER, text)
        fun bot(id: String, text: String, status: ChatMessage.Status = ChatMessage.Status.DONE, error: AiError? = null) =
            ChatMessage(id, ChatMessage.Role.ASSISTANT, text, status = status, error = error)
        val messages = listOf(
            user("1", "a"), bot("2", "A"),
            user("3", "b"), bot("4", "", ChatMessage.Status.FAILED, AiError.Network),
            user("5", "c"), bot("6", "C…", ChatMessage.Status.DONE, AiError.Truncated),
            user("7", "d"),
            user("8", "e"), bot("9", "E"),
        )
        val history = buildApiHistory(messages, mapOf("1" to "a (Heute ist …)"))
        assertEquals(listOf("a (Heute ist …)", "A", "c", "C…", "e", "E"), history.map { it.text })
        assertEquals(listOf(true, false, true, false, true, false), history.map { it.fromUser })
    }
}
