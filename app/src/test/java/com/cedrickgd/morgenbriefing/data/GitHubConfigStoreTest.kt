package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.EmailConfigException
import com.cedrickgd.morgenbriefing.data.remote.GitHubConfigStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GitHubConfigStoreTest {
    private lateinit var server: MockWebServer
    private lateinit var store: GitHubConfigStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = storeFor(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun storeFor(apiUrl: String) = GitHubConfigStore(
        http = OkHttpClient(),
        json = AppJson,
        owner = "CedrickGD",
        repo = "Morgenbriefing",
        branch = "briefs",
        apiUrl = apiUrl,
    )

    private suspend fun expectKind(kind: EmailConfigError, block: suspend () -> Unit): EmailConfigException {
        try {
            block()
        } catch (e: EmailConfigException) {
            assertEquals(kind, e.kind)
            return e
        }
        fail("expected EmailConfigException($kind)")
        throw AssertionError()
    }

    // --- read ------------------------------------------------------------------------------

    @Test
    fun `read decodes the base64 content and keeps the sha`() = runTest {
        server.enqueue(fileResponse(STORED_JSON, sha = "sha-1"))

        val stored = store.read(TOKEN)

        assertEquals("sha-1", stored?.sha)
        assertEquals(
            EmailConfig(1, true, listOf("anna@example.de", "ben@example.de"), "2026-10-05T04:00:00+02:00"),
            stored?.config,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/CedrickGD/Morgenbriefing/contents/config/email.json?ref=briefs", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("Morgenbriefing-Android", request.getHeader("User-Agent"))
    }

    @Test
    fun `read returns null when the file does not exist`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertNull(store.read(TOKEN))
    }

    @Test
    fun `read maps 401 and 403 to UNAUTHORIZED and a rate limit to RATE_LIMITED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Resource not accessible"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded for user"}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val e = expectKind(EmailConfigError.UNAUTHORIZED) { store.read(TOKEN) }
        expectKind(EmailConfigError.UNAUTHORIZED) { store.read(TOKEN) }
        expectKind(EmailConfigError.RATE_LIMITED) { store.read(TOKEN) }
        expectKind(EmailConfigError.UNKNOWN) { store.read(TOKEN) }
        assertFalse(e.message.orEmpty().contains(TOKEN))
    }

    @Test
    fun `broken files map to INVALID`() = runTest {
        server.enqueue(MockResponse().setBody("<html>kein json</html>"))
        server.enqueue(fileResponse("{ das ist kaputt", sha = "sha-1"))
        server.enqueue(MockResponse().setBody("""{"type":"file","encoding":"base64","sha":"s","content":"%%%"}"""))
        server.enqueue(MockResponse().setBody("""{"type":"dir","sha":"s"}"""))

        repeat(4) { expectKind(EmailConfigError.INVALID) { store.read(TOKEN) } }
    }

    // --- write -----------------------------------------------------------------------------

    @Test
    fun `write puts pretty json as base64 with sha and branch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(writeResponse("sha-2")))
        val config = EmailConfig(1, true, listOf("anna@example.de", "ben@example.de"), "2026-10-05T18:52:07+02:00")

        val stored = store.write(TOKEN, config, sha = "sha-1")

        assertEquals("sha-2", stored.sha)
        assertEquals(config, stored.config)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/repos/CedrickGD/Morgenbriefing/contents/config/email.json", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val body = request.jsonBody()
        assertEquals("config: E-Mail-Briefing aktualisiert", body.string("message"))
        assertEquals("briefs", body.string("branch"))
        assertEquals("sha-1", body.string("sha"))
        val expected = """
            {
              "schema": 1,
              "enabled": true,
              "recipients": [
                "anna@example.de",
                "ben@example.de"
              ],
              "updatedAt": "2026-10-05T18:52:07+02:00"
            }
        """.trimIndent() + "\n"
        assertEquals(expected, body.string("content").decodeBase64()?.utf8())
    }

    @Test
    fun `write without a sha creates the file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(writeResponse("sha-new")))

        val stored = store.write(TOKEN, EmailConfig(enabled = false, recipients = listOf("a@b.de")), sha = null)

        assertEquals("sha-new", stored.sha)
        assertFalse(server.takeRequest().jsonBody().containsKey("sha"))
    }

    @Test
    fun `a sha mismatch re-reads the file and retries once with the fresh sha`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"is at abc but expected def"}"""))
        server.enqueue(fileResponse(STORED_JSON, sha = "sha-fresh"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(writeResponse("sha-3")))

        val stored = store.write(TOKEN, EmailConfig(enabled = false, recipients = listOf("a@b.de")), sha = "sha-old")

        assertEquals("sha-3", stored.sha)
        assertEquals("sha-old", server.takeRequest().jsonBody().string("sha"))
        assertEquals("GET", server.takeRequest().method)
        val retry = server.takeRequest()
        assertEquals("PUT", retry.method)
        assertEquals("sha-fresh", retry.jsonBody().string("sha"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a file that appeared meanwhile (422 without sha) is overwritten on the retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"\"sha\" wasn't supplied."}"""))
        server.enqueue(fileResponse(STORED_JSON, sha = "sha-seeded"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(writeResponse("sha-4")))

        store.write(TOKEN, EmailConfig(enabled = true, recipients = listOf("a@b.de")), sha = null)

        assertFalse(server.takeRequest().jsonBody().containsKey("sha"))
        server.takeRequest()
        assertEquals("sha-seeded", server.takeRequest().jsonBody().string("sha"))
    }

    @Test
    fun `a second mismatch gives up with CONFLICT`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(fileResponse(STORED_JSON, sha = "sha-fresh"))
        server.enqueue(MockResponse().setResponseCode(422))

        expectKind(EmailConfigError.CONFLICT) {
            store.write(TOKEN, EmailConfig(enabled = true, recipients = listOf("a@b.de")), sha = "sha-old")
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `403 and 404 on write mean the token may only read`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Resource not accessible by personal access token"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val config = EmailConfig(enabled = true, recipients = listOf("a@b.de"))

        expectKind(EmailConfigError.NO_WRITE_PERMISSION) { store.write(TOKEN, config, sha = "sha-1") }
        expectKind(EmailConfigError.NO_WRITE_PERMISSION) { store.write(TOKEN, config, sha = "sha-1") }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401 on write is UNAUTHORIZED and a rate limit is RATE_LIMITED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"You have exceeded a secondary rate limit"}"""))
        val config = EmailConfig(enabled = true, recipients = listOf("a@b.de"))

        val e = expectKind(EmailConfigError.UNAUTHORIZED) { store.write(TOKEN, config, sha = "sha-1") }
        expectKind(EmailConfigError.RATE_LIMITED) { store.write(TOKEN, config, sha = "sha-1") }
        assertFalse(e.message.orEmpty().contains(TOKEN))
    }

    @Test
    fun `a write response without sha still succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"commit":{"sha":"c1"}}"""))
        val stored = store.write(TOKEN, EmailConfig(enabled = true, recipients = listOf("a@b.de")), sha = "sha-1")
        assertNull(stored.sha)
    }

    // --- transport & token -----------------------------------------------------------------

    @Test
    fun `connection failure maps to NETWORK`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()
        expectKind(EmailConfigError.NETWORK) { storeFor(url).read(TOKEN) }
        expectKind(EmailConfigError.NETWORK) {
            storeFor(url).write(TOKEN, EmailConfig(enabled = true, recipients = listOf("a@b.de")), sha = null)
        }
    }

    @Test
    fun `blank token is rejected without a request`() = runTest {
        expectKind(EmailConfigError.NO_TOKEN) { store.read("  ") }
        expectKind(EmailConfigError.NO_TOKEN) { store.write("", EmailConfig(), sha = null) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `token is trimmed and control characters are rejected without echoing it`() = runTest {
        server.enqueue(fileResponse(STORED_JSON, sha = "sha-1"))
        store.read("  $TOKEN\n")
        assertEquals("Bearer $TOKEN", server.takeRequest().getHeader("Authorization"))

        val e = expectKind(EmailConfigError.UNAUTHORIZED) { store.read("github_pat_A\nB") }
        assertFalse(e.message.orEmpty().contains("github_pat_A"))
        assertNull(e.cause)
        assertEquals(1, server.requestCount)
    }

    // ---------------------------------------------------------------------------------------

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val TOKEN = "github_pat_TEST0123456789"

        val STORED_JSON = """
            {
              "schema": 1,
              "enabled": true,
              "recipients": [" Anna@Example.de", "ben@example.de"],
              "updatedAt": "2026-10-05T04:00:00+02:00",
              "note": "von der Routine angelegt"
            }
        """.trimIndent()

        /** Contents API file object; GitHub wraps the base64 every 60 characters. */
        fun fileResponse(text: String, sha: String): MockResponse {
            val base64 = text.encodeUtf8().base64().chunked(60).joinToString("\\n")
            return MockResponse().setBody(
                """{"type":"file","encoding":"base64","name":"email.json","path":"config/email.json",""" +
                    """"sha":"$sha","size":${text.length},"content":"$base64\n"}""",
            )
        }

        fun writeResponse(sha: String): String =
            """{"content":{"name":"email.json","path":"config/email.json","sha":"$sha"},"commit":{"sha":"c0ffee"}}"""
    }
}
