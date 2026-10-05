package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.remote.OpenMeteoClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDate

class WeatherRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: BriefStore
    private lateinit var repo: WeatherRepository

    /** Same place twice → exactly one request per attempt. */
    private val settings = AppSettings(home = Place.LEIPZIG, day = Place.LEIPZIG)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        store = BriefStore(tmp.newFolder("store"), AppJson)
        repo = WeatherRepository(OpenMeteoClient(OkHttpClient(), AppJson, baseUrl = base, geocodingUrl = base), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `retries once and stores the snapshot as latest`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        // The repository uses the real clock, so the fixture must cover today and tomorrow.
        server.enqueue(MockResponse().setBody(OpenMeteoFixture.json(LocalDate.now(BERLIN))))

        val result = repo.load(settings)

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
        assertEquals(result.getOrThrow(), repo.cached())
    }

    @Test
    fun `two failures give a failed result and keep the cache untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.load(settings)

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, server.requestCount)
        assertNull(repo.cached())
    }

    @Test
    fun `search wraps results and errors`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"name":"Leipzig","latitude":51.34,"longitude":12.37,"admin1":"Sachsen","country":"Deutschland"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(listOf("Leipzig"), repo.search("Leipzig").getOrThrow().map { it.name })
        assertTrue(repo.search("Leipzig").isFailure)
    }
}
