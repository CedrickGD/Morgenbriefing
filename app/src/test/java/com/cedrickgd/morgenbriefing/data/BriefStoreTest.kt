package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class BriefStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: BriefStore

    @Before
    fun setUp() {
        root = tmp.newFolder("store")
        store = BriefStore(root, AppJson)
    }

    @Test
    fun `brief round trip`() = runTest {
        val brief = brief("2026-10-06", teaser = "Ein ruhiger Morgen.")
        store.saveBrief(brief)

        assertEquals(brief, store.loadBrief(LocalDate.of(2026, 10, 6)))
        assertTrue(File(root, "briefs/2026-10-06.json").isFile)
        assertNull(store.loadBrief(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `saving a brief again replaces the older copy`() = runTest {
        store.saveBrief(brief("2026-10-06", teaser = "Erste Fassung"))
        store.saveBrief(brief("2026-10-06", teaser = "Korrigiert"))

        assertEquals("Korrigiert", store.loadBrief(LocalDate.of(2026, 10, 6))?.teaser)
        assertEquals(listOf("2026-10-06.json"), File(root, "briefs").list()!!.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `brief with invalid date is rejected`() = runTest {
        store.saveBrief(brief("06.10.2026"))
    }

    @Test
    fun `day weather keeps the first snapshot unless overwrite`() = runTest {
        val date = LocalDate.of(2026, 10, 6)
        val first = snapshot("2026-10-06T05:30:00+02:00", temp = 6.0)
        val second = snapshot("2026-10-06T09:10:00+02:00", temp = 11.0)

        store.saveDayWeather(date, first)
        store.saveDayWeather(date, second)
        assertEquals(first, store.loadDayWeather(date))

        store.saveDayWeather(date, second, overwrite = true)
        assertEquals(second, store.loadDayWeather(date))
    }

    @Test
    fun `latest weather round trip and replace`() = runTest {
        assertNull(store.loadLatestWeather())
        store.saveLatestWeather(snapshot("2026-10-06T05:30:00+02:00", temp = 6.0))
        val newer = snapshot("2026-10-06T12:00:00+02:00", temp = 15.0)
        store.saveLatestWeather(newer)

        assertEquals(newer, store.loadLatestWeather())
        assertTrue(File(root, "weather/latest.json").isFile)
    }

    @Test
    fun `listDays merges briefs and weather, newest first, ignoring other files`() = runTest {
        store.saveBrief(brief("2026-10-04"))
        store.saveBrief(brief("2026-10-06"))
        store.saveDayWeather(LocalDate.of(2026, 10, 6), snapshot("2026-10-06T05:30:00+02:00"))
        store.saveDayWeather(LocalDate.of(2026, 10, 5), snapshot("2026-10-05T05:30:00+02:00"))
        store.saveLatestWeather(snapshot("2026-10-06T12:00:00+02:00"))
        File(root, "briefs/notes.json").writeText("{}")
        File(root, "briefs/2026-13-45.json").writeText("{}")
        File(root, "weather/2026-10-03.json.bak").writeText("{}")

        assertEquals(
            listOf(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 4)),
            store.listDays(),
        )
    }

    @Test
    fun `listDays on an empty store is empty`() = runTest {
        assertEquals(emptyList<LocalDate>(), BriefStore(File(root, "missing"), AppJson).listDays())
    }

    @Test
    fun `oldest days beyond maxDays are pruned on write`() = runTest {
        val small = BriefStore(root, AppJson, maxDays = 3)
        small.saveDayWeather(LocalDate.of(2026, 10, 1), snapshot("2026-10-01T05:30:00+02:00"))
        small.saveBrief(brief("2026-10-01"))
        small.saveBrief(brief("2026-10-02"))
        small.saveBrief(brief("2026-10-03"))
        small.saveDayWeather(LocalDate.of(2026, 10, 4), snapshot("2026-10-04T05:30:00+02:00"))
        small.saveBrief(brief("2026-10-05"))

        assertEquals(
            listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 3)),
            small.listDays(),
        )
        // the whole day is gone: brief and weather
        assertFalse(File(root, "briefs/2026-10-01.json").exists())
        assertFalse(File(root, "weather/2026-10-01.json").exists())
        assertFalse(File(root, "briefs/2026-10-02.json").exists())
        assertNull(small.loadBrief(LocalDate.of(2026, 10, 2)))
        assertEquals("2026-10-03", small.loadBrief(LocalDate.of(2026, 10, 3))?.date)
    }

    @Test
    fun `latest weather does not count as a day`() = runTest {
        val small = BriefStore(root, AppJson, maxDays = 1)
        small.saveLatestWeather(snapshot("2026-10-06T12:00:00+02:00"))
        small.saveBrief(brief("2026-10-06"))

        assertEquals(listOf(LocalDate.of(2026, 10, 6)), small.listDays())
        assertTrue(File(root, "weather/latest.json").isFile)
    }

    @Test
    fun `corrupt files read as null and are deleted`() = runTest {
        File(root, "briefs").mkdirs()
        File(root, "weather").mkdirs()
        val corruptBrief = File(root, "briefs/2026-10-06.json").apply { writeText("{\"date\": \"2026-10-") }
        val corruptWeather = File(root, "weather/latest.json").apply { writeText("[1, 2, 3]") }

        assertNull(store.loadBrief(LocalDate.of(2026, 10, 6)))
        assertNull(store.loadLatestWeather())
        assertFalse(corruptBrief.exists())
        assertFalse(corruptWeather.exists())
    }

    @Test
    fun `corrupt day weather is replaced even without overwrite`() = runTest {
        val date = LocalDate.of(2026, 10, 6)
        File(root, "weather").mkdirs()
        File(root, "weather/2026-10-06.json").writeText("garbage")

        val fresh = snapshot("2026-10-06T05:30:00+02:00")
        store.saveDayWeather(date, fresh)
        assertEquals(fresh, store.loadDayWeather(date))
    }

    @Test
    fun `parallel writes leave valid files and no temp files`() = runTest {
        (1..20).map { i ->
            async { store.saveBrief(brief("2026-10-06", teaser = "Fassung $i")) }
        }.awaitAll()

        assertTrue(store.loadBrief(LocalDate.of(2026, 10, 6))!!.teaser.startsWith("Fassung "))
        assertEquals(listOf("2026-10-06.json"), File(root, "briefs").list()!!.toList())
    }

    private fun brief(date: String, teaser: String = "Teaser $date") = Brief(
        date = date,
        generatedAt = "${date}T05:07:12+02:00",
        teaser = teaser,
        sections = listOf(
            NewsSection(
                id = "ki",
                title = "KI & Tech",
                emoji = "🤖",
                items = listOf(
                    NewsItem(
                        headline = "Schlagzeile",
                        summary = "Zusammenfassung.",
                        source = "heise",
                        url = "https://www.heise.de/",
                        publishedAt = "2026-10-05T14:55:00+02:00",
                        importance = 6,
                    ),
                ),
            ),
        ),
    )

    private fun snapshot(fetchedAt: String, temp: Double = 8.0): WeatherSnapshot {
        val time = fetchedAt.substring(0, 13) + ":00"
        val hour = HourPoint(
            time = time, hour = time.substring(11, 13).toInt(), temp = temp, feels = temp - 2,
            pop = 10, precipMm = 0.0, code = 2, isDay = true, windKmh = 8.0, gustKmh = 15.0,
        )
        return WeatherSnapshot(
            fetchedAt = fetchedAt,
            home = HomeWeather(
                place = Place.BAD_DUEBEN,
                current = CurrentConditions(
                    time = time, temp = temp, feels = temp - 2, code = 2, isDay = true,
                    windKmh = 8.0, gustKmh = 15.0, precipMm = 0.0, humidity = 80, visibilityM = 20_000.0,
                ),
                nextHours = listOf(hour),
                sunrise = "07:14",
                sunset = "18:32",
            ),
            day = DayWeather(
                place = Place.LEIPZIG, date = fetchedAt.substring(0, 10), isTomorrow = false,
                startHour = 10, endHour = 18, hours = listOf(hour), minTemp = temp, maxTemp = temp + 8,
                maxPop = 10, precipSum = 0.0, maxGustKmh = 15.0, uvMax = 2.5, sunset = "18:32",
            ),
        )
    }
}
