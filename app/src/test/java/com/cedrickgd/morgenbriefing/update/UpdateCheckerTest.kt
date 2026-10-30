package com.cedrickgd.morgenbriefing.update

import com.cedrickgd.morgenbriefing.data.AppJson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker(installed: String = "1.0.0") = UpdateChecker(
        http = OkHttpClient(),
        json = AppJson,
        owner = "CedrickGD",
        repo = "Morgenbriefing",
        installedVersionName = installed,
        apiUrl = server.url("/"),
    )

    private fun release(tag: String, assets: String = APK_ASSET, prerelease: Boolean = false) = """
        {
          "url": "https://api.github.com/repos/CedrickGD/Morgenbriefing/releases/1",
          "tag_name": "$tag",
          "name": "Morgenbriefing $tag",
          "body": "## Neu\r\n- Themen sortieren\r\n- Artikel in der App lesen\r\n\r\n**Full Changelog**: https://github.com/x/y/compare/v1.0.0...$tag",
          "draft": false,
          "prerelease": $prerelease,
          "published_at": "2026-10-05T17:12:00Z",
          "author": { "login": "CedrickGD" },
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun `newer release is available`() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.2.0")))

        val result = checker().check()

        val release = (result as UpdateCheckResult.Available).release
        assertEquals("v1.2.0", release.tag)
        assertEquals("1.2.0", release.version)
        assertEquals("Morgenbriefing v1.2.0", release.title)
        assertEquals("2026-10-05T17:12:00Z", release.publishedAt)
        assertEquals(ApkAsset(id = 42, name = "Morgenbriefing-v1.2.0.apk", size = 12_345_678), release.apk)
        assertTrue(release.notes.startsWith("## Neu"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/CedrickGD/Morgenbriefing/releases/latest", request.path)
        assertNull("public repo: no token is ever sent", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun `same or older release means up to date`() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.2.0")))
        assertEquals(UpdateCheckResult.UpToDate("1.2.0"), checker(installed = "1.2.0").check())

        server.enqueue(MockResponse().setBody(release("v1.1.0")))
        assertEquals(UpdateCheckResult.UpToDate("1.1.0"), checker(installed = "1.2.0-debug").check())
    }

    @Test
    fun `debug suffix of the installed version is ignored`() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.0.1")))
        assertTrue(checker(installed = "1.0.0-debug").check() is UpdateCheckResult.Available)
    }

    @Test
    fun `pre-release tags are skipped for release installs`() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.2.0-beta.1")))
        assertEquals(UpdateCheckResult.UpToDate("1.2.0-beta.1"), checker(installed = "1.1.0").check())

        server.enqueue(MockResponse().setBody(release("v1.2.0", prerelease = true)))
        assertEquals(UpdateCheckResult.UpToDate("1.2.0"), checker(installed = "1.1.0").check())

        server.enqueue(MockResponse().setBody(release("v1.2.0-beta.2")))
        assertTrue(checker(installed = "1.2.0-beta.1").check() is UpdateCheckResult.Available)
    }

    @Test
    fun `newer release without apk is reported`() = runTest {
        server.enqueue(MockResponse().setBody(release("v1.2.0", assets = "[]")))
        assertEquals(UpdateCheckResult.Failed(UpdateError.NO_APK), checker().check())
    }

    @Test
    fun `tag that is not a version is invalid`() = runTest {
        server.enqueue(MockResponse().setBody(release("nightly")))
        assertEquals(UpdateCheckResult.Failed(UpdateError.INVALID_RELEASE), checker().check())
    }

    @Test
    fun `http errors map to update errors`() = runTest {
        val cases = listOf(
            MockResponse().setResponseCode(401) to UpdateError.UNAUTHORIZED,
            MockResponse().setResponseCode(403) to UpdateError.UNAUTHORIZED,
            MockResponse().setResponseCode(403).addHeader("x-ratelimit-remaining", "0") to UpdateError.RATE_LIMITED,
            MockResponse().setResponseCode(429) to UpdateError.RATE_LIMITED,
            MockResponse().setResponseCode(404) to UpdateError.NOT_FOUND,
            MockResponse().setResponseCode(500) to UpdateError.SERVER,
            MockResponse().setBody("<html>not json</html>") to UpdateError.SERVER,
        )
        cases.forEach { (response, expected) ->
            server.enqueue(response)
            assertEquals(response.toString(), UpdateCheckResult.Failed(expected), checker().check())
        }
    }

    @Test
    fun `unreachable server is a network error`() = runTest {
        val gone = MockWebServer().apply { start() }
        val url = gone.url("/")
        gone.shutdown()
        val checker = UpdateChecker(OkHttpClient(), AppJson, "CedrickGD", "Morgenbriefing", "1.0.0", apiUrl = url)
        assertEquals(UpdateCheckResult.Failed(UpdateError.NETWORK), checker.check())
    }

    @Test
    fun `apk asset selection`() {
        fun asset(id: Long, name: String, state: String = "uploaded") = GitHubAsset(id = id, name = name, size = 1, state = state)

        assertNull(ReleaseSelection.selectApk(emptyList()))
        assertNull(ReleaseSelection.selectApk(listOf(asset(1, "notes.txt"), asset(2, "app.aab"))))
        assertEquals(3L, ReleaseSelection.selectApk(listOf(asset(1, "SHA256SUMS"), asset(3, "Morgenbriefing-v1.2.0.APK")))?.id)
        // A release APK wins over a debug one, whatever the order.
        assertEquals(
            5L,
            ReleaseSelection.selectApk(listOf(asset(4, "app-debug.apk"), asset(5, "Morgenbriefing-v1.2.0.apk")))?.id,
        )
        // Uploads still in progress and broken ids are skipped.
        assertEquals(
            7L,
            ReleaseSelection.selectApk(listOf(asset(6, "a.apk", state = "open"), asset(0, "b.apk"), asset(7, "c.apk")))?.id,
        )
        assertEquals(8L, ReleaseSelection.selectApk(listOf(asset(8, "app-debug.apk")))?.id)
    }

    @Test
    fun `release title and notes fall back sensibly`() {
        val result = ReleaseSelection.evaluate(
            GitHubRelease(
                tagName = "v2.0.0",
                name = "  ",
                body = null,
                publishedAt = "",
                assets = listOf(GitHubAsset(id = 9, name = "x.apk", size = -1)),
            ),
            installed = SemVer(1, 0, 0),
        )
        val release = (result as UpdateCheckResult.Available).release
        assertEquals("Morgenbriefing 2.0.0", release.title)
        assertEquals("", release.notes)
        assertNull(release.publishedAt)
        assertEquals(0L, release.apk.size)
    }

    @Test
    fun `stored release info round-trips through json`() {
        val info = ReleaseInfo(
            tag = "v1.2.0",
            version = "1.2.0",
            title = "Morgenbriefing 1.2.0",
            notes = "- Neu",
            publishedAt = "2026-10-05T17:12:00Z",
            apk = ApkAsset(42, "Morgenbriefing-v1.2.0.apk", 100),
        )
        val json = AppJson.encodeToString(ReleaseInfo.serializer(), info)
        assertEquals(info, AppJson.decodeFromString(ReleaseInfo.serializer(), json))
        assertEquals(SemVer(1, 2, 0), info.semVer)
    }

    private companion object {        const val APK_ASSET = """[
            { "id": 41, "name": "SHA256SUMS.txt", "size": 120, "state": "uploaded", "content_type": "text/plain" },
            { "id": 42, "name": "Morgenbriefing-v1.2.0.apk", "size": 12345678, "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/CedrickGD/Morgenbriefing/releases/download/v1.2.0/Morgenbriefing-v1.2.0.apk" }
        ]"""
    }
}
