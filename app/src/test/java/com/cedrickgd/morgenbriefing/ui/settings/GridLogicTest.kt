package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GridLogicTest {

    /** Two columns of 100 × 80 px with 10 px gaps, ten tiles = five rows. */
    private val grid = GridSlots(columns = 2, count = 10, cellWidth = 100f, cellHeight = 80f, gap = 10f)

    @Test
    fun `slot geometry`() {
        assertEquals(5, grid.rows)
        assertEquals(5 * 80f + 4 * 10f, grid.height, 0f)
        assertEquals(0f, grid.x(0), 0f)
        assertEquals(110f, grid.x(1), 0f)
        assertEquals(90f, grid.y(2), 0f)
        assertEquals(160f, grid.centerX(3), 0f)
        assertEquals(130f, grid.centerY(3), 0f)
    }

    @Test
    fun `points map to the slot under them`() {
        assertEquals(0, grid.indexAt(50f, 40f))
        assertEquals(1, grid.indexAt(150f, 40f))
        assertEquals(3, grid.indexAt(150f, 130f))
        assertEquals(8, grid.indexAt(10f, 400f))
    }

    @Test
    fun `gaps belong to the nearer slot and outside points to the edge`() {
        // 104 is in the gap but closer to column 0 (gap center is 105).
        assertEquals(0, grid.indexAt(104f, 10f))
        assertEquals(1, grid.indexAt(106f, 10f))
        assertEquals(0, grid.indexAt(-500f, -500f))
        assertEquals(9, grid.indexAt(9999f, 9999f))
    }

    @Test
    fun `an incomplete last row maps to the last tile`() {
        val odd = GridSlots(columns = 2, count = 9, cellWidth = 100f, cellHeight = 80f, gap = 10f)
        assertEquals(5, odd.rows)
        assertEquals(8, odd.indexAt(150f, 400f))
        assertEquals(-1, GridSlots(2, 0, 100f, 80f, 10f).indexAt(0f, 0f))
    }

    @Test
    fun `place weather comes from the snapshot of the same places`() {
        val snapshot = WeatherFixtures.foggyAutumn
        val now = ZonedDateTime.of(2026, 10, 6, 5, 42, 0, 0, ZoneId.of("Europe/Berlin"))
        val (home, day) = placeWeather(snapshot, Place.BAD_DUEBEN, Place.LEIPZIG, now)
        assertEquals("9°", home?.temp)
        assertEquals("🌫️", home?.emoji)
        assertEquals("vor 12 Min.", home?.caption)
        assertEquals("21°", day?.temp)
        assertEquals("☀️", day?.emoji)
        assertEquals("max. heute", day?.caption)
    }

    @Test
    fun `changed places show no stale weather`() {
        val snapshot = WeatherFixtures.foggyAutumn
        val now = ZonedDateTime.of(2026, 10, 5, 22, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val berlin = Place("Berlin", 52.52, 13.405)
        val (home, day) = placeWeather(snapshot, berlin, Place.LEIPZIG, now)
        assertNull(home)
        assertEquals("max. morgen", day?.caption)
        assertEquals(null to null, placeWeather(null, Place.BAD_DUEBEN, Place.LEIPZIG, now))
    }

    @Test
    fun `nearby coordinates count as the same spot`() {
        val a = Place("Leipzig", 51.3397, 12.3731)
        assertEquals(true, a.sameSpotAs(Place("Leipzig", 51.34, 12.37)))
        assertEquals(false, a.sameSpotAs(Place("Halle", 51.48, 11.97)))
    }
}
