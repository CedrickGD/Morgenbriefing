package com.cedrickgd.morgenbriefing.ui.history

import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class HistoryModelsTest {

    private fun entry(date: String, teaser: String = "") =
        HistoryEntry(LocalDate.parse(date), teaser, itemCount = 10, weather = null, cached = true)

    @Test
    fun `groups by month newest first`() {
        val groups = groupByMonth(
            listOf(entry("2026-10-06"), entry("2026-10-01"), entry("2026-09-30"), entry("2026-09-02")),
        )
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), groups.map { it.month })
        assertEquals(
            listOf(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-01")),
            groups[0].entries.map { it.date },
        )
        assertEquals(2, groups[1].entries.size)
    }

    @Test
    fun `grouping sorts and deduplicates defensively`() {
        val groups = groupByMonth(
            listOf(
                entry("2026-09-30"),
                entry("2026-10-06", teaser = "erster"),
                entry("2026-10-06", teaser = "doppelt"),
                entry("2025-12-31"),
            ),
        )
        assertEquals(
            listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2025, 12)),
            groups.map { it.month },
        )
        assertEquals(listOf("erster"), groups[0].entries.map { it.teaser })
    }

    @Test
    fun `empty history has no groups`() {
        assertEquals(emptyList<MonthGroup>(), groupByMonth(emptyList()))
    }

    @Test
    fun `mini weather uses early afternoon of the day window`() {
        val snapshot = snapshot(
            morningTemp = 8.6,
            morningCode = 45,
            morningIsDay = false,
            hours = listOf(hour(10, code = 45), hour(13, code = 2), hour(14, code = 0), hour(15, code = 3)),
            maxTemp = 21.2,
        )
        assertEquals(14, representativeHour(snapshot.day)?.hour)
        val mini = miniWeatherOf(snapshot)
        assertEquals("☀️", mini.emoji)
        assertEquals(8.6, mini.morningTemp, 0.0)
        assertEquals(21.2, mini.maxTemp, 0.0)
    }

    @Test
    fun `mini weather falls back to the morning condition without hours`() {
        val snapshot = snapshot(morningTemp = 3.0, morningCode = 61, morningIsDay = false, hours = emptyList(), maxTemp = 7.0)
        assertNull(representativeHour(snapshot.day))
        assertEquals("🌧️", miniWeatherOf(snapshot).emoji)
    }

    @Test
    fun `weekday abbreviations are german`() {
        assertEquals("Mo", weekdayShort(DayOfWeek.MONDAY))
        assertEquals("Di", weekdayShort(DayOfWeek.TUESDAY))
        assertEquals("So", weekdayShort(DayOfWeek.SUNDAY))
    }

    @Test
    fun `item count labels`() {
        assertNull(itemCountLabel(0))
        assertEquals("1 Meldung", itemCountLabel(1))
        assertEquals("10 Meldungen", itemCountLabel(10))
    }

    private fun hour(hour: Int, code: Int, temp: Double = 15.0) = HourPoint(
        time = "2026-10-06T%02d:00".format(hour),
        hour = hour,
        temp = temp,
        feels = temp,
        pop = 0,
        precipMm = 0.0,
        code = code,
        isDay = true,
        windKmh = 5.0,
        gustKmh = 10.0,
    )

    private fun snapshot(
        morningTemp: Double,
        morningCode: Int,
        morningIsDay: Boolean,
        hours: List<HourPoint>,
        maxTemp: Double,
    ) = WeatherSnapshot(
        fetchedAt = "2026-10-06T05:30:00+02:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-10-06T05:30",
                temp = morningTemp,
                feels = morningTemp,
                code = morningCode,
                isDay = morningIsDay,
                windKmh = 5.0,
                gustKmh = 10.0,
                precipMm = 0.0,
                humidity = 90,
            ),
            nextHours = emptyList(),
            sunrise = "07:12",
            sunset = "18:41",
        ),
        day = DayWeather(
            place = Place.LEIPZIG,
            date = "2026-10-06",
            isTomorrow = false,
            startHour = 10,
            endHour = 18,
            hours = hours,
            minTemp = morningTemp,
            maxTemp = maxTemp,
            maxPop = 0,
            precipSum = 0.0,
            maxGustKmh = 10.0,
            uvMax = 3.0,
            sunset = "18:41",
        ),
    )
}
