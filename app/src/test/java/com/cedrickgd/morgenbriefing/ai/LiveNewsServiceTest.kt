package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.AppJson
import com.cedrickgd.morgenbriefing.data.withAiKey
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LiveNewsServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = MutableStateFlow(AppSettings().withAiKey())
    private val usageState = MutableStateFlow(UsageSnapshot(month = "2026-10"))
    private val usage = RecordingUsage()
    private val clock = Clock.fixed(LiveFixtures.NOW, ZoneId.of("Europe/Berlin"))
    private val today = LocalDate.of(2026, 10, 5)

    private val brief = Brief(
        date = "2026-10-05",
        generatedAt = "2026-10-05T04:41:07+02:00",
        sections = listOf(
            NewsSection(
                id = "region",
                title = "Leipzig & Region",
                items = listOf(NewsItem(id = "region-live-neue-buslinie", headline = "Alte Meldung", summary = "…", source = "LVZ", url = "https://www.lvz.de/alt")),
            ),
        ),
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeLiveEngine(val script: suspend (LiveResearchRequest, LiveResearchListener) -> LiveResearchResult) : LiveEngine {
        val requests = mutableListOf<LiveResearchRequest>()
        override suspend fun research(request: LiveResearchRequest, listener: LiveResearchListener, usage: UsageSink): LiveResearchResult {
            requests += request
            return script(request, listener)
        }
    }

    private val liveStore by lazy { LiveStore(folder.root.resolve("live"), AppJson) }

    private val noChat = object : ChatEngine {
        override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult = error("not used")
    }

    private fun access(engine: LiveEngine?) = FakeAiAccess(settings, usageState, usage, engine?.let { FakeBackend(noChat, it) })

    private fun service(
        engine: LiveEngine?,
        clock: Clock = this.clock,
        access: AiProviderAccess = access(engine),
    ) = LiveNewsServiceImpl(
        scope = scope,
        settings = settings,
        access = access,
        store = liveStore,
        briefOf = { date -> brief.takeIf { date.toString() == it.date } },
        clock = clock,
    )

    private suspend fun LiveNewsService.await(predicate: (LiveState) -> Boolean): LiveState =
        withTimeout(10_000) { state.first(predicate) }

    @Test
    fun `a run publishes validated articles and stores them`() = runBlocking<Unit> {
        val engine = FakeLiveEngine { _, listener ->
            listener.onSearch("Leipzig Buslinie")
            listener.onFetch("https://www.lvz.de/leipzig/buslinie-100.html")
            listener.onToolCall(LivePublish.TOOL_NAME)
            val bad = listener.onPublish(LiveFixtures.payload(LiveFixtures.article(body = "zu kurz")))
            assertFalse(bad.accepted)
            assertTrue(bad.message.contains("zu kurz"))
            val good = listener.onPublish(LiveFixtures.payload(LiveFixtures.article()))
            assertTrue(good.accepted)
            assertEquals("ok", good.message)
            LiveResearchResult(published = true, end = TurnEnd.COMPLETE)
        }
        val live = service(engine)
        live.refresh(listOf("region"))
        val done = live.await { !it.running && it.lastRunAt != null }

        assertEquals(1, done.newCount)
        assertNull(done.error)
        assertTrue(done.progress.toString(), done.progress.contains("Suche: Leipzig Buslinie"))
        assertTrue(done.progress.contains("Lese lvz.de"))
        assertEquals("Fertig", done.progress.last())

        val articles = withTimeout(10_000) { live.todayArticles.first { it.isNotEmpty() } }
        val article = articles.single()
        assertEquals("region", article.topicId)
        // The brief already has this id: the live copy gets its own key.
        assertEquals("region-live-neue-buslinie-2", article.item.id)
        assertEquals(article, live.articles(today).single())
        assertTrue(folder.root.resolve("live/2026-10-05-1430.json").isFile)

        val request = engine.requests.single()
        assertEquals(AiPrompts.LIVE_SYSTEM, request.systemPrompt)
        assertTrue(request.userPrompt.contains("- region (Leipzig & Region)"))
        assertTrue(request.userPrompt.contains("[region] Alte Meldung"))
        // News since the morning brief.
        assertTrue(request.userPrompt.contains("04:41 Uhr"))
    }

    @Test
    fun `a refusal and a cancelled run`() = runBlocking<Unit> {
        val refusing = service(FakeLiveEngine { _, _ -> LiveResearchResult(false, TurnEnd.REFUSED, "general_harms") })
        refusing.refresh(emptyList())
        val refused = refusing.await { !it.running && it.error != null }
        assertEquals(AiError.Refusal("general_harms"), refused.error)

        val started = CompletableDeferred<Unit>()
        val slow = service(FakeLiveEngine { _, listener ->
            listener.onSearch("x")
            started.complete(Unit)
            awaitCancellation()
        })
        slow.refresh(listOf("politik"))
        withTimeout(10_000) { started.await() }
        assertTrue(slow.state.value.running)
        slow.refresh(listOf("politik")) // no-op while running
        slow.cancel()
        val cancelled = slow.await { !it.running }
        assertEquals("Abgebrochen", cancelled.progress.last())
    }

    @Test
    fun `a refused key is reported with the key the run used, other errors are not`() = runBlocking<Unit> {
        val offlineEngine = FakeLiveEngine { _, _ -> throw AiException(AiError.Network) }
        val offlineAccess = access(offlineEngine)
        val offline = service(offlineEngine, access = offlineAccess)
        offline.refresh(listOf("region"))
        assertEquals(AiError.Network, offline.await { !it.running && it.error != null }.error)
        assertTrue(offlineAccess.rejected.isEmpty())

        val refusingEngine = FakeLiveEngine { _, _ -> throw AiException(AiError.InvalidKey) }
        val refusingAccess = access(refusingEngine)
        val refused = service(refusingEngine, access = refusingAccess)
        refused.refresh(listOf("region"))
        assertEquals(AiError.InvalidKey, refused.await { !it.running && it.error != null }.error)
        assertEquals(listOf(AiProviderId.ANTHROPIC to "test-key"), refusingAccess.rejected)
    }

    @Test
    fun `a clean run without news is remembered as checked`() = runBlocking<Unit> {
        val live = service(FakeLiveEngine { _, listener ->
            assertTrue(listener.onPublish(LiveFixtures.payload()).accepted)
            LiveResearchResult(published = true, end = TurnEnd.COMPLETE)
        })
        live.refresh(listOf("region"))
        val done = live.await { !it.running && it.lastRunAt != null }

        assertNull(done.error)
        assertEquals(0, done.newCount)
        assertEquals("Nichts Neues seit dem letzten Stand", done.progress.last())
        assertEquals("2026-10-05T14:30:00+02:00", liveStore.lastRunAt(today))
    }

    @Test
    fun `refused, cut off and unfinished runs do not count as checked`() = runBlocking<Unit> {
        val clock = MutableClock(LiveFixtures.NOW)
        val failures = listOf(
            LiveResearchResult(false, TurnEnd.REFUSED, "cyber"),
            LiveResearchResult(false, TurnEnd.TRUNCATED),
            // Ended without publishing even after the reminder.
            LiveResearchResult(false, TurnEnd.INCOMPLETE),
            // Published nothing new, then ran out of requests.
            LiveResearchResult(true, TurnEnd.INCOMPLETE),
        )
        val prompts = mutableListOf<String>()
        failures.forEachIndexed { i, end ->
            clock.now = LiveFixtures.NOW.plusSeconds(600L * i)
            // A fresh service each time, like after an app restart: it reads lastRunAt from the store.
            val live = service(FakeLiveEngine { request, _ -> prompts += request.userPrompt; end }, clock)
            live.refresh(listOf("region"))
            val failed = live.await { !it.running && it.error != null }
            when (end.end) {
                TurnEnd.REFUSED -> assertEquals(AiError.Refusal("cyber"), failed.error)
                TurnEnd.TRUNCATED -> assertEquals(AiError.Truncated, failed.error)
                // Stopped before publishing anything: an explained, retryable error.
                else -> {
                    val error = failed.error
                    assertTrue(error.toString(), error is AiError.Unknown && !error.message.isNullOrBlank())
                }
            }
            assertNull(failed.lastRunAt)
            assertEquals("Ohne Ergebnis beendet", failed.progress.last())
            assertNull(liveStore.lastRunAt(today))
        }

        // The next run still looks back to the morning brief (04:41), not to a failed run.
        clock.now = LiveFixtures.NOW.plusSeconds(3_600)
        val ok = service(FakeLiveEngine { request, _ -> prompts += request.userPrompt; LiveResearchResult(true, TurnEnd.COMPLETE) }, clock)
        ok.refresh(listOf("region"))
        ok.await { !it.running && it.lastRunAt != null }
        assertEquals("2026-10-05T15:30:00+02:00", liveStore.lastRunAt(today))
        assertEquals(5, prompts.size)
        prompts.forEach { assertTrue(it, it.contains("04:41 Uhr")) }
    }

    @Test
    fun `unsupported devices and the monthly limit block a run`() = runBlocking<Unit> {
        val unsupported = service(engine = null)
        unsupported.await { it.blocked == AiBlock.UNSUPPORTED_DEVICE }
        unsupported.refresh(listOf("ki"))

        // The sink and the usage snapshot read the same numbers in the app.
        usage.limitReached = true
        usageState.value = UsageSnapshot(month = "2026-10", spentUsd = 5.0, limitUsd = 5.0)
        val engine = FakeLiveEngine { _, _ -> error("must not run") }
        val limited = service(engine)
        limited.refresh(listOf("ki"))
        limited.await { it.blocked == AiBlock.LIMIT_REACHED }
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `Live runs with its own provider and stores it with the run`() = runBlocking<Unit> {
        // Chat on Mistral (no search), Live on OpenAI.
        settings.value = AppSettings().withAiKey(AiProviderId.MISTRAL).let { s ->
            s.copy(ai = s.ai.copy(liveProvider = AiProviderId.OPENAI).with(AiProviderId.OPENAI, ProviderSettings(hasKey = true, model = "gpt-6-luna")))
        }
        val engine = FakeLiveEngine { _, listener ->
            assertTrue(listener.onPublish(LiveFixtures.payload(LiveFixtures.article())).accepted)
            LiveResearchResult(published = true, end = TurnEnd.COMPLETE)
        }
        val live = service(engine)
        val ready = live.await { it.provider == AiProviderId.OPENAI }
        assertEquals("OpenAI", ready.providerName)
        assertNull(ready.blocked)

        live.refresh(listOf("region"))
        live.await { !it.running && it.lastRunAt != null }
        val request = engine.requests.single()
        assertEquals(AiProviderId.OPENAI, request.auth.provider)
        assertEquals("gpt-6-luna", request.model)
        val stored = AppJson.decodeFromString(StoredLiveRun.serializer(), folder.root.resolve("live/2026-10-05-1430.json").readText())
        assertEquals("openai", stored.provider)
        assertEquals("gpt-6-luna", stored.model)
    }

    @Test
    fun `Gemini and providers without search cannot run Live`() = runBlocking<Unit> {
        listOf(AiProviderId.GEMINI, AiProviderId.MISTRAL, AiProviderId.DEEPSEEK).forEach { id ->
            settings.value = AppSettings().withAiKey(id)
            val engine = FakeLiveEngine { _, _ -> error("must not run") }
            val live = service(engine)
            live.await { it.provider == id && it.blocked == AiBlock.LIVE_UNAVAILABLE }
            live.refresh(listOf("ki"))
            kotlinx.coroutines.delay(100)
            assertTrue(engine.requests.isEmpty())
            assertFalse(live.state.value.running)
        }
        // A backend without Live engine is caught at run time, too.
        settings.value = AppSettings().withAiKey(AiProviderId.OPENAI)
        val chatOnly = FakeAiAccess(settings, usageState, usage, FakeBackend(noChat, live = null))
        val live = service(engine = null, access = chatOnly)
        live.await { it.provider == AiProviderId.OPENAI }
        live.refresh(listOf("ki"))
        live.await { it.blocked == AiBlock.LIVE_UNAVAILABLE }
    }

    @Test
    fun `since is the later of brief and last run, at most a day back`() {
        val now = Instant.parse("2026-10-05T12:00:00Z")
        assertEquals(Instant.parse("2026-10-05T02:41:07Z"), sinceFor(now, brief, null))
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"), sinceFor(now, brief, "2026-10-05T12:00:00+02:00"))
        assertEquals(now.minusSeconds(12 * 3600), sinceFor(now, null, null))
        assertEquals(now.minusSeconds(24 * 3600), sinceFor(now, brief.copy(generatedAt = "2026-10-01T04:00:00+02:00"), null))
    }

    @Test
    fun `run topics default to the visible ones`() {
        val s = AppSettings(topicOrder = listOf("politik", "ki", "sport"), hiddenTopics = setOf("ki"))
        assertEquals(listOf("politik", "sport"), runTopics(emptyList(), s).map { it.id })
        assertEquals(listOf("region"), runTopics(listOf("region", "unbekannt"), s).map { it.id })
    }
}
