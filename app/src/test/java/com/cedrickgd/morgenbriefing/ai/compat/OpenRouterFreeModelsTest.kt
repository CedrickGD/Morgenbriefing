package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.provider.OpenRouterTiers
import com.cedrickgd.morgenbriefing.data.AppJson
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class OpenRouterFreeModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")
    private val today = LocalDate.of(2026, 10, 10)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A clock the test moves forward. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun model(
        id: String,
        name: String,
        prompt: String = "0",
        completion: String = "0",
        output: String = """["text"]""",
        input: String = """["text"]""",
        parameters: String = """["max_tokens","reasoning","tools","tool_choice"]""",
        extra: String = "",
    ) = """{"id":"$id","name":"$name","context_length":262144,"architecture":{"modality":"text->text","input_modalities":$input,"output_modalities":$output},""" +
        """"pricing":{"prompt":"$prompt","completion":"$completion"},"supported_parameters":$parameters$extra}"""

    /** Shaped like OpenRouter's live list of 2026-10-10: free, paid and look-alike models. */
    private val list = """{"data":[""" + listOf(
        model("stepfun/step-5-preview", "StepFun: Step 5 Preview", prompt = "0.0000003", completion = "0.0000012"),
        model("nvidia/nemotron-3-ultra-550b-a55b:free", "NVIDIA: Nemotron 3 Ultra (free)"),
        model("inclusionai/ling-3.1-flash", "inclusionAI: Ling 3.1 Flash"),
        model("google/gemma-4-31b-it:free", "Google: Gemma 4 31B (free)", input = """["image","text","video"]"""),
        model("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B A4B  (free)"),
        // Music generation: free, but it answers with audio.
        model("google/lyria-3-pro-preview", "Google: Lyria 3 Pro Preview", output = """["text","audio"]""", parameters = """["max_tokens"]"""),
        // A guardrail classifier: no tools, no chat.
        model("nvidia/nemotron-3.5-content-safety:free", "NVIDIA: Nemotron 3.5 Content Safety (free)", parameters = """["max_tokens","temperature"]"""),
        // Routers with variable prices.
        model("openrouter/auto", "Auto Router", prompt = "-1", completion = "-1"),
        model("openrouter/free", "Free Models Router", input = """["text","image"]"""),
        // Free only for the prompt.
        model("vendor/half-free", "Vendor: Half Free", completion = "0.000001"),
        // Expired yesterday, and one that runs out later.
        model("poolside/laguna-old:free", "Poolside: Laguna Old (free)", extra = ""","expiration_date":"2026-10-09""""),
        model("poolside/laguna-s-2.1:free", "Poolside: Laguna S 2.1 (free)", extra = ""","expiration_date":"2026-10-31""""),
        // A per-request price.
        """{"id":"vendor/per-request","name":"Vendor: Per Request","pricing":{"prompt":"0","completion":"0","request":"0.01"}}""",
    ).joinToString(",") + "]}"

    @Test
    fun `only zero-price text chat models count, in picker order`() {
        val models = OpenRouterFree.parse(list, today)!!
        assertEquals(
            listOf(
                "google/gemma-4-31b-it:free",
                "google/gemma-4-26b-a4b-it:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "openrouter/free",
                "poolside/laguna-s-2.1:free",
                "inclusionai/ling-3.1-flash",
            ),
            models.map { it.id },
        )
        assertEquals(262_144, models.first().contextLength)
        // Not a model list.
        assertNull(OpenRouterFree.parse("""{"error":{"message":"nope"}}""", today))
        assertNull(OpenRouterFree.parse("<html>", today))
        assertEquals(emptyList<FreeModel>(), OpenRouterFree.parse("""{"data":[]}""", today))
    }

    @Test
    fun `names, makers and the default`() {
        val gemma = FreeModel("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B A4B  (free)")
        assertEquals("Gemma 4 26B A4B", OpenRouterFree.title(gemma))
        assertEquals("Google", OpenRouterFree.maker(gemma))
        assertEquals("Googles offenes Modell, kleiner und flotter · kostenlos", OpenRouterFree.subtitle(gemma))
        val ling = FreeModel("inclusionai/ling-3.1-flash", "inclusionAI: Ling 3.1 Flash")
        assertEquals("Ling 3.1 Flash", OpenRouterFree.title(ling))
        assertEquals("inclusionAI · kostenlos", OpenRouterFree.subtitle(ling))
        assertEquals("Zufälliges Gratis-Modell", OpenRouterFree.title(FreeModel("openrouter/free", "Free Models Router")))
        assertEquals("plain/id", OpenRouterFree.title(FreeModel("plain/id", "")))

        // Gemma 4 31B when listed, else the first model.
        val models = OpenRouterFree.parse(list, today)!!
        assertEquals("google/gemma-4-31b-it:free", OpenRouterFree.defaultOf(models))
        assertEquals("google/gemma-4-26b-a4b-it:free", OpenRouterFree.defaultOf(models.drop(1)))
        assertEquals(OpenRouterTiers.DEFAULT_FREE_MODEL, OpenRouterFree.defaultOf(null))
        // A chosen model that left the list gives way to the default; without a list it stands.
        assertEquals("inclusionai/ling-3.1-flash", OpenRouterFree.effective("inclusionai/ling-3.1-flash", models))
        assertEquals("google/gemma-4-31b-it:free", OpenRouterFree.effective("vendor/gone:free", models))
        assertEquals("vendor/gone:free", OpenRouterFree.effective("vendor/gone:free", null))
        // The offline list starts with the default and is free by name.
        assertEquals(OpenRouterTiers.DEFAULT_FREE_MODEL, OpenRouterFree.FALLBACK.first().id)
        assertTrue(OpenRouterFree.FALLBACK.all { OpenRouterTiers.looksFree(it.id) })
        assertEquals(OpenRouterFree.PREFERRED, OpenRouterFree.FALLBACK.map { it.id })
    }

    @Test
    fun `the list is loaded without a key, kept for a day and survives a restart`() = runBlocking<Unit> {
        val clock = MovableClock(Instant.parse("2026-10-10T08:00:00Z"))
        val file = folder.root.resolve("openrouter-free-models.json")
        val base = server.local("/api/v1")
        val models = OpenRouterFreeModels(http, file, AppJson, clock)

        server.enqueue(CompatSse.json(200, list))
        val first = models.list(base)
        assertEquals(FreeModelSource.LIVE, first.source)
        assertEquals("google/gemma-4-31b-it:free", first.models.first().id)
        val request = server.takeRequest()
        assertEquals("/api/v1/models", request.requestUrl!!.encodedPath)
        assertNull(request.getHeader("Authorization"))
        assertEquals("Morgenbriefing", request.getHeader("X-OpenRouter-Title"))

        // Within the day: from the cache, no request.
        clock.now = clock.now.plusSeconds(23 * 3600)
        assertEquals(FreeModelSource.CACHE, models.list(base).source)
        assertEquals(1, server.requestCount)
        // Another process reads the file.
        val restarted = OpenRouterFreeModels(http, file, AppJson, clock)
        assertEquals(first.models, restarted.known(base))
        assertEquals(FreeModelSource.CACHE, restarted.list(base).source)
        assertEquals(1, server.requestCount)

        // A day later a new list is loaded …
        clock.now = clock.now.plusSeconds(2 * 3600)
        server.enqueue(CompatSse.json(200, """{"data":[${model("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B A4B (free)")}]}"""))
        val next = restarted.list(base)
        assertEquals(FreeModelSource.LIVE, next.source)
        assertEquals(listOf("google/gemma-4-26b-a4b-it:free"), next.models.map { it.id })
        // … and when OpenRouter fails, the last list stays (of any age).
        clock.now = clock.now.plusSeconds(3 * 24 * 3600)
        server.enqueue(CompatSse.httpError(503))
        val stale = restarted.list(base)
        assertEquals(FreeModelSource.CACHE, stale.source)
        assertEquals(next.models, stale.models)
        // force skips the day's cache.
        server.enqueue(CompatSse.json(200, list))
        assertEquals(FreeModelSource.LIVE, restarted.list(base, force = true).source)
    }

    @Test
    fun `offline without a list the built-in one is used, another base starts fresh`() = runBlocking<Unit> {
        val clock = MovableClock(Instant.parse("2026-10-10T08:00:00Z"))
        val file = folder.root.resolve("free.json")
        val models = OpenRouterFreeModels(http, file, AppJson, clock)
        server.enqueue(CompatSse.httpError(500))
        val offline = models.list(server.local("/api/v1"))
        assertEquals(FreeModelSource.FALLBACK, offline.source)
        assertEquals(OpenRouterFree.FALLBACK, offline.models)
        assertNull(models.known(server.local("/api/v1")))
        assertFalse(file.exists())
        // An empty or broken answer is no list either.
        server.enqueue(CompatSse.json(200, """{"data":[]}"""))
        assertEquals(FreeModelSource.FALLBACK, models.list(server.local("/api/v1")).source)
        server.enqueue(CompatSse.json(200, "not json"))
        assertEquals(FreeModelSource.FALLBACK, models.list(server.local("/api/v1")).source)

        // A list of one base (the mock server) is never used for another (the real API).
        server.enqueue(CompatSse.json(200, list))
        assertEquals(FreeModelSource.LIVE, models.list(server.local("/mock/v1")).source)
        assertNull(models.known(server.local("/api/v1")))
        // A base OkHttp cannot read is no crash.
        assertEquals(FreeModelSource.FALLBACK, models.list("not a url").source)
    }

    @Test
    fun `the chat's lookup never waits for a download under way`() = runBlocking<Unit> {
        val clock = MovableClock(Instant.parse("2026-10-10T08:00:00Z"))
        val base = server.local("/api/v1")
        val models = OpenRouterFreeModels(http, folder.root.resolve("slow.json"), AppJson, clock)
        server.enqueue(CompatSse.json(200, list))
        models.list(base)
        // A day later the next download hangs; the lookup answers from the last list meanwhile.
        clock.now = clock.now.plusSeconds(25 * 3600)
        server.enqueue(CompatSse.json(200, list).setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        val download = async(kotlinx.coroutines.Dispatchers.IO) { models.list(base) }
        kotlinx.coroutines.delay(300)
        val started = System.nanoTime()
        assertEquals("google/gemma-4-31b-it:free", models.known(base)!!.first().id)
        assertTrue("the lookup waited", System.nanoTime() - started < 1_000_000_000L)
        assertEquals(FreeModelSource.LIVE, download.await().source)
    }
}
