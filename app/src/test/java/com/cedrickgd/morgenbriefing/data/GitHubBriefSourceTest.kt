package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefSourceException
import com.cedrickgd.morgenbriefing.data.remote.GitHubBriefSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GitHubBriefSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: GitHubBriefSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = sourceFor(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sourceFor(apiUrl: String) = GitHubBriefSource(
        http = OkHttpClient(),
        json = AppJson,
        owner = "CedrickGD",
        repo = "Morgenbriefing",
        branch = "briefs",
        apiUrl = apiUrl,
    )

    private suspend fun expectKind(kind: BriefErrorKind, block: suspend () -> Unit): BriefSourceException {
        try {
            block()
        } catch (e: BriefSourceException) {
            assertEquals(kind, e.kind)
            return e
        }
        fail("expected BriefSourceException($kind)")
        throw AssertionError()
    }

    @Test
    fun `latest sends auth and raw accept headers and parses the brief`() = runTest {
        server.enqueue(MockResponse().setBody(BRIEF_JSON))
        val brief = source.latest(TOKEN)

        assertEquals("2026-10-06", brief.date)
        assertEquals("Ein ruhiger Morgen.", brief.teaser)
        assertEquals(1, brief.sections.single().items.size)
        assertEquals(7, brief.sections.single().items.single().importance)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/CedrickGD/Morgenbriefing/contents/latest.json?ref=briefs", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("application/vnd.github.raw+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertEquals("Morgenbriefing-Android", request.getHeader("User-Agent"))
    }

    @Test
    fun `day and index use their paths`() = runTest {
        server.enqueue(MockResponse().setBody(BRIEF_JSON))
        server.enqueue(
            MockResponse().setBody(
                """{"updatedAt":"2026-10-06T05:07:12+02:00","days":[
                   {"date":"2026-10-06","teaser":"Ein ruhiger Morgen.","itemCount":10},
                   {"date":"2026-10-05","teaser":"Gestern.","itemCount":9}]}""",
            ),
        )

        source.day(TOKEN, LocalDate.of(2026, 10, 6))
        val index = source.index(TOKEN)

        assertEquals("/repos/CedrickGD/Morgenbriefing/contents/briefs/2026-10-06.json?ref=briefs", server.takeRequest().path)
        assertEquals("/repos/CedrickGD/Morgenbriefing/contents/index.json?ref=briefs", server.takeRequest().path)
        assertEquals(listOf("2026-10-06", "2026-10-05"), index.days.map { it.date })
        assertEquals(9, index.days[1].itemCount)
    }

    @Test
    fun `token is trimmed before use`() = runTest {
        server.enqueue(MockResponse().setBody(BRIEF_JSON))
        source.latest("  $TOKEN\n")
        assertEquals("Bearer $TOKEN", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `401 and 403 map to UNAUTHORIZED without leaking the token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        server.enqueue(MockResponse().setResponseCode(403))

        val e401 = expectKind(BriefErrorKind.UNAUTHORIZED) { source.latest(TOKEN) }
        expectKind(BriefErrorKind.UNAUTHORIZED) { source.index(TOKEN) }
        assertFalse(e401.message.orEmpty().contains(TOKEN))
    }

    @Test
    fun `404 maps to NOT_FOUND`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        expectKind(BriefErrorKind.NOT_FOUND) { source.day(TOKEN, LocalDate.of(2026, 10, 7)) }
    }

    @Test
    fun `other status codes map to UNKNOWN`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(302))
        expectKind(BriefErrorKind.UNKNOWN) { source.latest(TOKEN) }
        expectKind(BriefErrorKind.UNKNOWN) { source.latest(TOKEN) }
    }

    @Test
    fun `malformed or invalid json maps to PARSE`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        server.enqueue(MockResponse().setBody("""{"generatedAt":"2026-10-06T05:00:00+02:00"}"""))
        server.enqueue(MockResponse().setBody("""{"date":"6. Oktober","generatedAt":"x"}"""))
        expectKind(BriefErrorKind.PARSE) { source.latest(TOKEN) }
        expectKind(BriefErrorKind.PARSE) { source.latest(TOKEN) } // required "date" missing
        expectKind(BriefErrorKind.PARSE) { source.latest(TOKEN) } // date not YYYY-MM-DD
    }

    @Test
    fun `connection failure maps to NETWORK`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()
        expectKind(BriefErrorKind.NETWORK) { sourceFor(url).latest(TOKEN) }
    }

    @Test
    fun `blank token is rejected without a request`() = runTest {
        expectKind(BriefErrorKind.NO_TOKEN) { source.latest("   ") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `token with control characters is rejected without echoing it`() = runTest {
        val e = expectKind(BriefErrorKind.UNAUTHORIZED) { source.latest("github_pat_A\nB") }
        assertFalse(e.message.orEmpty().contains("github_pat_A"))
        assertNull(e.cause)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `checkAccess gets the repository`() = runTest {
        server.enqueue(MockResponse().setBody("""{"full_name":"CedrickGD/Morgenbriefing","private":true}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        source.checkAccess(TOKEN)
        expectKind(BriefErrorKind.UNAUTHORIZED) { source.checkAccess(TOKEN) }

        val request = server.takeRequest()
        assertEquals("/repos/CedrickGD/Morgenbriefing", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("Morgenbriefing-Android", request.getHeader("User-Agent"))
    }

    private companion object {
        const val TOKEN = "github_pat_TEST0123456789"

        val BRIEF_JSON = """
            {
              "schema": 1,
              "date": "2026-10-06",
              "generatedAt": "2026-10-06T05:07:12+02:00",
              "teaser": "Ein ruhiger Morgen.",
              "futureField": {"ignored": true},
              "sections": [
                {"id": "ki", "title": "KI & Tech", "emoji": "🤖", "items": [
                  {"headline": "Neue Modelle", "summary": "Kurz erklärt.", "source": "heise",
                   "url": "https://www.heise.de/", "publishedAt": "2026-10-05T14:55:00+02:00", "importance": 7}
                ]}
              ]
            }
        """.trimIndent()
    }
}
