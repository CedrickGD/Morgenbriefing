package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.AppJson
import com.cedrickgd.morgenbriefing.data.withAiKey
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.SourceRef
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

    private fun service(
        engine: ChatEngine? = this.engine,
        apiKey: String? = "test-key",
        contexts: ChatContextSource = articleContexts,
        keyRejected: suspend (String) -> Unit = {},
    ) = ChatServiceImpl(
        scope = scope,
        settings = settings,
        apiKey = { apiKey },
        usageSink = usage,
        usageState = usageState,
        engine = engine,
        store = ChatStore(folder.root.resolve("chat"), AppJson),
        contexts = contexts,
        clock = clock,
        keyRejected = keyRejected,
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
        assertEquals(AiPrompts.CHAT_SYSTEM, request.systemPrompt)
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
    fun `a refused key is reported with the key the turn used, other errors are not`() = runBlocking<Unit> {
        val rejected = mutableListOf<String>()
        engine.scripts += { throw AiException(AiError.Network) }
        engine.scripts += { throw AiException(AiError.InvalidKey) }
        val session = service(apiKey = "sk-ant-old", keyRejected = { synchronized(rejected) { rejected += it } }).session(article)
        session.send("Frage")
        assertEquals(AiError.Network, session.await { it.settled(2) }.messages.last().error)
        assertTrue(rejected.isEmpty())

        session.send("Noch eine")
        assertEquals(AiError.InvalidKey, session.await { it.settled(4) }.messages.last().error)
        assertEquals(listOf("sk-ant-old"), rejected)
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
    fun `blocked states`() = runBlocking<Unit> {
        assertEquals(AiBlock.UNSUPPORTED_DEVICE, service(engine = null).session(article).state.value.blocked)

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
