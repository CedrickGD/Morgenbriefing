package com.cedrickgd.morgenbriefing.ai.http

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SseReaderTest {
    private lateinit var server: MockWebServer
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun read(text: String): Pair<List<SseEvent>, Boolean> {
        val reader = SseReader(Buffer().writeUtf8(text))
        val events = generateSequence { reader.next() }.toList()
        return events to reader.done
    }

    @Test
    fun `reads event names and data`() {
        val (events, done) = read("event: response.created\ndata: {\"type\":\"response.created\"}\n\ndata: {\"a\":1}\n\n")
        assertEquals(listOf(SseEvent("response.created", """{"type":"response.created"}"""), SseEvent(null, """{"a":1}""")), events)
        assertFalse(done)
    }

    @Test
    fun `joins multi-line data with newlines`() {
        val (events, _) = read("data: erste Zeile\ndata: zweite Zeile\ndata:\n\n")
        assertEquals(listOf(SseEvent(null, "erste Zeile\nzweite Zeile\n")), events)
    }

    @Test
    fun `skips comments, ids, retries and empty events`() {
        val (events, _) = read(": OPENROUTER PROCESSING\n\n: keep-alive\nid: 7\nretry: 1000\n\nevent: ping\n\ndata: x\n\n")
        assertEquals(listOf(SseEvent(null, "x")), events)
    }

    @Test
    fun `handles CRLF line endings`() {
        val (events, _) = read("event: a\r\ndata: b\r\n\r\nevent: c\r\ndata: d\r\n\r\n")
        assertEquals(listOf(SseEvent("a", "b"), SseEvent("c", "d")), events)
    }

    @Test
    fun `drops exactly one space after the colon`() {
        val (events, _) = read("data:x\n\ndata:  y\n\n")
        assertEquals(listOf("x", " y"), events.map { it.data })
    }

    @Test
    fun `DONE ends the stream`() {
        val (events, done) = read("data: {\"n\":1}\n\ndata: [DONE]\n\ndata: {\"n\":2}\n\n")
        assertEquals(listOf("""{"n":1}"""), events.map { it.data })
        assertTrue(done)
    }

    @Test
    fun `end of input dispatches a pending event`() {
        val (events, done) = read("data: {\"n\":1}\n\ndata: {\"n\":2}")
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), events.map { it.data })
        assertFalse(done)
    }

    @Test
    fun `streams over HTTP and reports the end`() = runBlocking<Unit> {
        server.enqueue(sse("event: a\ndata: 1\n\n: comment\n\nevent: b\ndata: 2\n\ndata: [DONE]\n\n"))
        val seen = mutableListOf<SseEvent>()

        val end = http.streamSse(http.post(server.url("/v1/x").toString(), buildJsonObject { put("q", 1) }, stream = true)) {
            seen += it
            true
        }

        assertEquals(SseEnd.DONE, end)
        assertEquals(listOf(SseEvent("a", "1"), SseEvent("b", "2")), seen)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("Morgenbriefing-Android/test", recorded.getHeader("User-Agent"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"q":1}""", recorded.body.readUtf8())
    }

    @Test
    fun `stopping early reports STOPPED`() = runBlocking<Unit> {
        server.enqueue(sse("data: 1\n\ndata: 2\n\n"))
        val seen = mutableListOf<String>()
        val end = http.streamSse(http.get(server.url("/s").toString())) {
            seen += it.data
            false
        }
        assertEquals(SseEnd.STOPPED, end)
        assertEquals(listOf("1"), seen)
    }

    @Test
    fun `an error status throws HttpFailure with the body even for text-event-stream`() = runBlocking<Unit> {
        // Gemini answers a bad key with 400, an event-stream content type and a plain JSON body (GE §5.1).
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "text/event-stream").setBody(body))
        try {
            http.streamSse(http.get(server.url("/s").toString())) { fail("no events expected"); true }
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertEquals(400, e.code)
            assertEquals(body, e.body)
            assertNull(e.retryAfter)
        }
    }

    @Test
    fun `Retry-After is read as seconds or HTTP date`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("{}"))
        try {
            http.streamSse(http.get(server.url("/s").toString())) { true }
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertEquals(7.seconds, e.retryAfter)
        }
        val now = ZonedDateTime.of(2026, 10, 9, 7, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(30.seconds, parseRetryAfter("Fri, 09 Oct 2026 07:00:30 GMT", now))
        assertEquals(1.5.seconds, parseRetryAfter("1.5", now))
        assertNull(parseRetryAfter("bald", now))
        assertNull(parseRetryAfter(null, now))
        assertEquals(37.seconds, parseSecondsDuration("37s"))
    }

    @Test
    fun `cancelling the coroutine cancels the call mid-stream`() = runBlocking<Unit> {
        val first = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: 1\n\n" + ": keep-alive\n".repeat(5_000), 64)
                .throttleBody(64, 200, TimeUnit.MILLISECONDS),
        )
        val job = async(Dispatchers.Default) {
            http.streamSse(http.get(server.url("/s").toString())) {
                first.complete(Unit)
                true
            }
        }
        withTimeout(10_000) { first.await() }
        val started = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertTrue("cancel took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    }

    @Test
    fun `retries before the first event and honours Retry-After`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"error":{"message":"slow down","code":"slow_down"}}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"server_is_overloaded"}}"""))
        server.enqueue(sse("data: ok\n\n"))
        val slept = mutableListOf<Duration>()
        val policy = RetryPolicy(sleep = { slept += it })
        var tries = 0

        val result = policy.run("test", { ProviderErrors.map(AiProviderId.OPENAI, it, "gpt-6.1-sol") }) { attempt ->
            tries++
            val seen = mutableListOf<String>()
            http.streamSse(http.get(server.url("/s").toString())) {
                attempt.delivered = true
                seen += it.data
                true
            }
            seen
        }

        assertEquals(listOf("ok"), result)
        assertEquals(3, tries)
        assertEquals(3, server.requestCount)
        assertEquals(7.seconds, slept[0])
        // No Retry-After on the 503: the second backoff step, 3 s ± 20 %.
        assertTrue(slept[1] in 2.4.seconds..3.6.seconds)
    }

    @Test
    fun `Retry-After is capped at 20 seconds`() {
        assertEquals(20.seconds, RetryPolicy().delayBefore(1, 90.seconds))
        val first = RetryPolicy().delayBefore(1, null)
        assertTrue(first in 0.8.seconds..1.2.seconds)
    }

    @Test
    fun `gives up after two retries`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("{}")) }
        val slept = mutableListOf<Duration>()
        try {
            RetryPolicy(sleep = { slept += it }).run("test", { ProviderErrors.map(AiProviderId.XAI, it, "grok-4.3") }) {
                http.streamSse(http.get(server.url("/s").toString())) { true }
            }
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiError.Overloaded, e.error)
        }
        assertEquals(3, server.requestCount)
        assertEquals(2, slept.size)
    }

    @Test
    fun `never retries key or billing errors`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted"}}"""))
        val policy = RetryPolicy(sleep = { fail("no retry expected") })
        val map = { e: Throwable -> ProviderErrors.map(AiProviderId.OPENAI, e, "gpt-6.1-sol") }
        for (expected in listOf(AiError.InvalidKey, AiError.NoCredit)) {
            try {
                policy.run("test", map) { http.streamSse(http.get(server.url("/s").toString())) { true } }
                fail("expected AiException")
            } catch (e: AiException) {
                assertEquals(expected, e.error)
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `no retry once an event was delivered`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: erstes\n\n" + ": padding\n".repeat(400) + "data: letztes\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.enqueue(sse("data: nie\n\n"))
        val seen = mutableListOf<String>()
        try {
            RetryPolicy(sleep = { fail("no retry expected") }).run("test", { ProviderErrors.map(AiProviderId.OPENAI, it, null) }) { attempt ->
                http.streamSse(http.get(server.url("/s").toString())) {
                    attempt.delivered = true
                    seen += it.data
                    true
                }
            }
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiError.Network, e.error)
        }
        assertEquals(listOf("erstes"), seen)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a limit block passes through unchanged`() = runBlocking<Unit> {
        try {
            RetryPolicy().run("test", { ProviderErrors.map(AiProviderId.OPENAI, it, null) }) {
                throw com.cedrickgd.morgenbriefing.ai.AiBlockedException(com.cedrickgd.morgenbriefing.ai.AiBlock.LIMIT_REACHED)
            }
            fail("expected AiBlockedException")
        } catch (e: com.cedrickgd.morgenbriefing.ai.AiBlockedException) {
            assertEquals(com.cedrickgd.morgenbriefing.ai.AiBlock.LIMIT_REACHED, e.block)
        }
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}
