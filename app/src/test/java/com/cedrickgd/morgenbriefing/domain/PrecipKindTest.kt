package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.hour
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecipKindTest {

    private fun home(current: Int, vararg codes: Int) = HomeWeather(
        place = Place.BAD_DUEBEN,
        current = CurrentConditions(
            time = "2026-12-03T07:00", temp = -1.0, feels = -4.0, code = current, isDay = true,
            windKmh = 10.0, gustKmh = 20.0, precipMm = 0.4, humidity = 92,
        ),
        nextHours = codes.mapIndexed { i, code -> hour("2026-12-03T%02d:00".format(7 + i), -1.0, pop = 80, code = code) },
        sunrise = "07:52",
        sunset = "16:00",
    )

    @Test
    fun `the precipitation chip names snow and sleet by the weather codes`() {
        // Snow now and later: "Schnee", never "Regen".
        assertEquals(PrecipKind.SNOW, precipKind(home(73, 73, 75, 85, 71)))
        assertEquals("Schnee", precipKind(home(73, 73, 75, 85, 71)).word)
        // Snow next to rain or drizzle: "Schneeregen".
        assertEquals(PrecipKind.SLEET, precipKind(home(71, 61, 71, 3, 3)))
        assertEquals(PrecipKind.SLEET, precipKind(home(51, 85, 3, 3, 3)))
        assertEquals("Schneeregen", PrecipKind.SLEET.word)
        // Rain, freezing rain, showers and thunder are rain; dry hours do not count.
        assertEquals(PrecipKind.RAIN, precipKind(home(61, 63, 80, 95, 66)))
        assertEquals(PrecipKind.RAIN, precipKind(home(0, 0, 1, 2, 3)))
        assertEquals(PrecipKind.SNOW, precipKind(home(3, 3, 71, 3, 0)))
        // Every kind has its own emoji: a snowflake for snow, the water drop stays rain's.
        assertEquals("💧", PrecipKind.RAIN.emoji)
        assertEquals(3, PrecipKind.entries.map { it.emoji }.toSet().size)
    }

    @Test
    fun `conditions alone decide`() {
        assertEquals(PrecipKind.RAIN, precipKind(emptyList()))
        assertEquals(PrecipKind.SNOW, precipKind(listOf(Condition.SNOW_SHOWERS, Condition.OVERCAST)))
        assertEquals(PrecipKind.SLEET, precipKind(listOf(Condition.SNOW, Condition.FREEZING_RAIN)))
        assertEquals(PrecipKind.RAIN, precipKind(listOf(Condition.THUNDER, Condition.FOG)))
    }
}
