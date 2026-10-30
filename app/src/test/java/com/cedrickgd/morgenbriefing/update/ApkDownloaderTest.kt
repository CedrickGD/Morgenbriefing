package com.cedrickgd.morgenbriefing.update

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var api: MockWebServer
    private lateinit var storage: MockWebServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        api = MockWebServer().apply { start() }
        storage = MockWebServer().apply { start() }
        directory = File(temp.root, "updates")
    }

    @After
    fun tearDown() {
        api.shutdown()
        storage.shutdown()
    }

    private fun downloader() = ApkDownloader(
        http = OkHttpClient(),
        owner = "CedrickGD",
        repo = "Morgenbriefing",
        directory = directory,
        apiUrl = api.url("/"),
    )

    private fun release(size: Long, tag: String = "v1.2.0") = ReleaseInfo(
        tag = tag,
        version = tag.removePrefix("v"),
        title = "Morgenbriefing $tag",
        apk = ApkAsset(id = 42, name = "Morgenbriefing-$tag.apk", size = size),
    )

    /** The storage server under a different host name than the API (localhost ↔ 127.0.0.1). */
    private fun storageUrlOnOtherHost(path: String): HttpUrl {
        val url = storage.url(path)
        val other = if (api.url("/").host == "127.0.0.1") "localhost" else "127.0.0.1"
        return url.newBuilder().host(other).build()
    }

    private suspend fun expectError(expected: UpdateError, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UpdateException) {
            assertEquals(expected, e.error)
            return
        }
        fail("expected UpdateException($expected)")
    }

    @Test
    fun `follows the redirect to storage`() = runTest {
        val apk = ByteArray(300_000) { (it % 251).toByte() }
        api.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", storageUrlOnOtherHost("/github-production-release-asset/42?sig=abc")),
        )
        storage.enqueue(MockResponse().setBody(Buffer().write(apk)))

        val progress = mutableListOf<DownloadProgress>()
        val file = downloader().download(release(apk.size.toLong())) { progress += it }

        assertEquals("Morgenbriefing-v1.2.0.apk", file.name)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf(file), directory.listFiles()!!.toList())

        val apiRequest = api.takeRequest()
        assertEquals("/repos/CedrickGD/Morgenbriefing/releases/assets/42", apiRequest.path)
        assertEquals("application/octet-stream", apiRequest.getHeader("Accept"))

        val storageRequest = storage.takeRequest()
        assertEquals("/github-production-release-asset/42?sig=abc", storageRequest.path)

        assertEquals(1f, progress.last().fraction)
        assertEquals(apk.size.toLong(), progress.last().received)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.received >= a.received })
    }

    @Test
    fun `plain okhttp drops the token on a cross-host redirect`() {
        // OkHttp's own behaviour, without our guard: the storage host never sees the token.
        val target = storageUrlOnOtherHost("/blob")
        assertNotEquals(api.url("/").host, target.host)
        api.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target))
        storage.enqueue(MockResponse().setBody("apk"))
        val request = Request.Builder().url(api.url("/asset")).header("Authorization", "Bearer secret").build()

        OkHttpClient().newCall(request).execute().use { assertEquals("apk", it.body.string()) }

        assertEquals("Bearer secret", api.takeRequest().getHeader("Authorization"))
        assertNull(storage.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `token host guard strips authorization for every other host`() {
        storage.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addNetworkInterceptor(TokenHostGuard("api.github.com")).build()
        val request = Request.Builder().url(storage.url("/x")).header("Authorization", "Bearer secret").build()

        client.newCall(request).execute().use { assertEquals(200, it.code) }

        assertNull(storage.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `token host guard keeps authorization for the api host`() {
        api.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addNetworkInterceptor(TokenHostGuard(api.url("/").host)).build()
        val request = Request.Builder().url(api.url("/x")).header("Authorization", "Bearer secret").build()

        client.newCall(request).execute().use { assertEquals(200, it.code) }

        assertEquals("Bearer secret", api.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `content length that differs from the asset size is refused`() = runTest {
        api.enqueue(MockResponse().setBody("short"))

        expectError(UpdateError.INCOMPLETE_DOWNLOAD) {
            downloader().download(release(size = 1_000)) { }
        }
        assertTrue("no partial file stays behind", directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `http errors map to update errors`() = runTest {
        val cases = listOf(
            404 to UpdateError.NOT_FOUND,
            401 to UpdateError.UNAUTHORIZED,
            403 to UpdateError.UNAUTHORIZED,
            502 to UpdateError.SERVER,
        )
        cases.forEach { (code, expected) ->
            api.enqueue(MockResponse().setResponseCode(code))
            expectError(expected) { downloader().download(release(10)) { } }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `old downloads are deleted and a complete file is reused`() = runTest {
        directory.mkdirs()
        val stale = File(directory, "Morgenbriefing-v1.1.0.apk").apply { writeText("old") }
        val broken = File(directory, "Morgenbriefing-v1.2.0.apk.part").apply { writeText("half") }
        val complete = File(directory, "Morgenbriefing-v1.2.0.apk").apply { writeBytes(ByteArray(5) { 1 }) }

        val file = downloader().download(release(size = 5)) { }

        assertEquals(complete, file)
        assertFalse(stale.exists())
        assertFalse(broken.exists())
        assertEquals(0, api.requestCount)
    }

    @Test
    fun `unknown size still downloads`() = runTest {
        api.enqueue(MockResponse().setChunkedBody("chunked-apk-bytes", 4))

        val progress = mutableListOf<DownloadProgress>()
        val file = downloader().download(release(size = 0)) { progress += it }

        assertEquals("chunked-apk-bytes", file.readText())
        assertNull(progress.first().fraction)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun `file names are safe`() {
        assertEquals("v1.2.0", ApkDownloader.safeName("v1.2.0"))
        assertEquals("v1.2.0-beta.1", ApkDownloader.safeName(" v1.2.0-beta.1 "))
        assertEquals(".._.._evil", ApkDownloader.safeName("../../evil"))
        assertEquals("update", ApkDownloader.safeName(""))
    }
}
