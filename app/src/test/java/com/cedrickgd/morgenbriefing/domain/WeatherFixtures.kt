package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import java.util.Locale

/** Hand-built snapshots that mirror what OpenMeteoClient produces for typical mornings. */
object WeatherFixtures {

    fun hour(
        time: String,
        temp: Double,
        feels: Double = temp,
        pop: Int = 0,
        precip: Double = 0.0,
        code: Int = 0,
        isDay: Boolean = true,
        wind: Double = 8.0,
        gust: Double = 15.0,
        visibility: Double? = 20_000.0,
    ) = HourPoint(
        time = time,
        hour = time.substring(11, 13).toInt(),
        temp = temp,
        feels = feels,
        pop = pop,
        precipMm = precip,
        code = code,
        isDay = isDay,
        windKmh = wind,
        gustKmh = gust,
        visibilityM = visibility,
    )

    fun day(
        date: String,
        hours: List<HourPoint>,
        isTomorrow: Boolean = false,
        uvMax: Double = 3.0,
        place: Place = Place.LEIPZIG,
        sunset: String = "18:42",
    ) = DayWeather(
        place = place,
        date = date,
        isTomorrow = isTomorrow,
        startHour = hours.first().hour,
        endHour = hours.last().hour,
        hours = hours,
        minTemp = hours.minOf { it.temp },
        maxTemp = hours.maxOf { it.temp },
        maxPop = hours.maxOf { it.pop },
        precipSum = hours.sumOf { it.precipMm },
        maxGustKmh = hours.maxOf { it.gustKmh },
        uvMax = uvMax,
        sunset = sunset,
    )

    /** Builds an hourly window from parallel lists, one entry per hour starting at [startHour]. */
    fun window(
        date: String,
        startHour: Int,
        temps: List<Double>,
        feels: List<Double> = temps,
        codes: List<Int> = temps.map { 0 },
        pops: List<Int> = temps.map { 0 },
        precip: List<Double> = temps.map { 0.0 },
        gusts: List<Double> = temps.map { 15.0 },
        wind: Double = 8.0,
    ): List<HourPoint> = temps.indices.map { i ->
        val h = startHour + i
        hour(
            time = String.format(Locale.ROOT, "%sT%02d:00", date, h),
            temp = temps[i],
            feels = feels[i],
            pop = pops[i],
            precip = precip[i],
            code = codes[i],
            gust = gusts[i],
            wind = wind,
        )
    }

    /** Foggy autumn morning: 9°/gefühlt 8° in fog, 1100 m sight, dark until 07:18; Leipzig 13°→21°, sunny, dry. */
    val foggyAutumn: WeatherSnapshot = WeatherSnapshot(
        fetchedAt = "2026-10-06T05:30:00+02:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-10-06T05:30", temp = 9.0, feels = 8.0, code = 45, isDay = false,
                windKmh = 4.0, gustKmh = 9.0, precipMm = 0.0, humidity = 97, visibilityM = 1100.0,
            ),
            nextHours = listOf(
                hour("2026-10-06T05:00", 9.0, 8.0, code = 45, isDay = false, visibility = 1100.0),
                hour("2026-10-06T06:00", 9.0, 8.0, code = 45, isDay = false, visibility = 1200.0),
                hour("2026-10-06T07:00", 9.5, 8.5, code = 45, isDay = false, visibility = 1500.0),
                hour("2026-10-06T08:00", 10.0, 9.0, code = 3, visibility = 2600.0),
            ),
            sunrise = "07:18",
            sunset = "18:42",
        ),
        day = day(
            date = "2026-10-06",
            hours = window(
                date = "2026-10-06", startHour = 10,
                temps = listOf(13.0, 16.0, 19.0, 21.0, 21.0, 21.0, 20.0, 18.0, 16.0),
                feels = listOf(12.5, 15.5, 19.0, 21.2, 21.5, 21.3, 20.0, 17.5, 15.0),
                codes = listOf(45, 2, 0, 0, 0, 0, 0, 0, 0),
            ),
            uvMax = 3.5,
        ),
    )

    /** Frosty winter morning with freezing rain at home; Leipzig around 0–2° with early rain. */
    val frostyWinter: WeatherSnapshot = WeatherSnapshot(
        fetchedAt = "2027-01-14T05:30:00+01:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2027-01-14T05:30", temp = -2.0, feels = -6.5, code = 66, isDay = false,
                windKmh = 12.0, gustKmh = 22.0, precipMm = 0.3, humidity = 96, visibilityM = 4000.0,
            ),
            nextHours = listOf(
                hour("2027-01-14T05:00", -2.0, -6.5, pop = 70, precip = 0.3, code = 66, isDay = false, gust = 22.0),
                hour("2027-01-14T06:00", -1.5, -6.0, pop = 65, precip = 0.4, code = 66, isDay = false, gust = 24.0),
                hour("2027-01-14T07:00", -1.0, -5.0, pop = 50, precip = 0.2, code = 61, isDay = false, gust = 24.0),
                hour("2027-01-14T08:00", 0.0, -4.0, pop = 40, precip = 0.1, code = 61, gust = 25.0),
            ),
            sunrise = "08:08",
            sunset = "16:31",
        ),
        day = day(
            date = "2027-01-14",
            hours = window(
                date = "2027-01-14", startHour = 10,
                temps = listOf(0.0, 1.0, 1.5, 2.0, 2.0, 2.0, 1.5, 1.0, 0.5),
                feels = listOf(-3.5, -2.5, -2.0, -1.5, -1.0, -1.5, -2.0, -2.5, -3.0),
                codes = listOf(61, 61, 3, 3, 3, 3, 3, 3, 3),
                pops = listOf(60, 55, 30, 20, 15, 10, 10, 10, 10),
                precip = listOf(0.6, 0.4, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                gusts = listOf(25.0, 25.0, 22.0, 20.0, 20.0, 18.0, 18.0, 16.0, 16.0),
            ),
            uvMax = 0.8,
        ),
    )

    /** Summer heat up to 31°, thunderstorms from 16 Uhr, UV 7. */
    val summerThunder: WeatherSnapshot = WeatherSnapshot(
        fetchedAt = "2026-07-15T05:30:00+02:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-07-15T05:30", temp = 22.0, feels = 23.0, code = 0, isDay = true,
                windKmh = 5.0, gustKmh = 10.0, precipMm = 0.0, humidity = 60, visibilityM = 30_000.0,
            ),
            nextHours = listOf(
                hour("2026-07-15T05:00", 21.5, 22.5),
                hour("2026-07-15T06:00", 22.0, 23.0),
                hour("2026-07-15T07:00", 23.0, 24.0),
                hour("2026-07-15T08:00", 24.5, 25.5),
            ),
            sunrise = "05:05",
            sunset = "21:18",
        ),
        day = day(
            date = "2026-07-15",
            hours = window(
                date = "2026-07-15", startHour = 10,
                temps = listOf(26.0, 27.5, 29.0, 30.0, 31.0, 31.0, 29.0, 26.0, 25.0),
                feels = listOf(27.0, 28.5, 30.0, 31.5, 32.0, 32.0, 29.5, 26.5, 25.5),
                codes = listOf(0, 0, 0, 1, 1, 2, 95, 95, 3),
                pops = listOf(0, 0, 5, 10, 20, 40, 70, 60, 30),
                precip = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 4.0, 2.0, 0.2),
                gusts = listOf(12.0, 14.0, 15.0, 18.0, 20.0, 25.0, 35.0, 30.0, 20.0),
            ),
            uvMax = 7.2,
        ),
    )

    /** Grey, rainy and windy November day: gusts up to 50 km/h, 80 % rain chance. */
    val rainyWindy: WeatherSnapshot = WeatherSnapshot(
        fetchedAt = "2026-11-03T05:30:00+01:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-11-03T05:30", temp = 11.0, feels = 8.5, code = 61, isDay = false,
                windKmh = 22.0, gustKmh = 44.0, precipMm = 0.8, humidity = 88, visibilityM = 9000.0,
            ),
            nextHours = listOf(
                hour("2026-11-03T05:00", 11.0, 8.5, pop = 80, precip = 0.8, code = 61, isDay = false, gust = 44.0),
                hour("2026-11-03T06:00", 11.0, 8.5, pop = 80, precip = 1.0, code = 63, isDay = false, gust = 46.0),
                hour("2026-11-03T07:00", 11.5, 9.0, pop = 75, precip = 0.7, code = 61, isDay = false, gust = 45.0),
                hour("2026-11-03T08:00", 12.0, 9.5, pop = 70, precip = 0.5, code = 61, gust = 44.0),
            ),
            sunrise = "07:05",
            sunset = "16:39",
        ),
        day = day(
            date = "2026-11-03",
            hours = window(
                date = "2026-11-03", startHour = 10,
                temps = listOf(12.0, 12.5, 13.0, 13.0, 13.5, 13.0, 12.5, 12.0, 11.5),
                feels = listOf(9.5, 10.0, 10.5, 10.5, 11.0, 10.5, 10.0, 9.5, 9.0),
                codes = listOf(63, 63, 61, 61, 80, 80, 61, 61, 61),
                pops = listOf(80, 80, 75, 70, 70, 75, 80, 80, 75),
                precip = listOf(1.2, 1.0, 0.8, 0.6, 0.9, 0.7, 0.5, 0.4, 0.4),
                gusts = listOf(42.0, 45.0, 48.0, 50.0, 50.0, 47.0, 44.0, 40.0, 38.0),
                wind = 24.0,
            ),
            uvMax = 1.0,
        ),
    )

    /** Opened in the evening: the day window is tomorrow's (grey start, rain from 14 Uhr). */
    val tomorrowEvening: WeatherSnapshot = WeatherSnapshot(
        fetchedAt = "2026-10-06T20:15:00+02:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-10-06T20:15", temp = 12.0, feels = 11.0, code = 3, isDay = false,
                windKmh = 8.0, gustKmh = 18.0, precipMm = 0.0, humidity = 80, visibilityM = 20_000.0,
            ),
            nextHours = listOf(
                hour("2026-10-06T20:00", 12.0, 11.0, code = 3, isDay = false),
                hour("2026-10-06T21:00", 11.5, 10.5, code = 3, isDay = false),
                hour("2026-10-06T22:00", 11.0, 10.0, code = 3, isDay = false),
                hour("2026-10-06T23:00", 11.0, 10.0, code = 3, isDay = false),
            ),
            sunrise = "07:18",
            sunset = "18:42",
        ),
        day = day(
            date = "2026-10-07",
            isTomorrow = true,
            hours = window(
                date = "2026-10-07", startHour = 10,
                temps = listOf(12.0, 13.0, 14.0, 15.0, 15.0, 14.5, 14.0, 13.0, 12.5),
                codes = listOf(3, 3, 3, 3, 61, 61, 63, 61, 61),
                pops = listOf(10, 10, 20, 35, 60, 70, 70, 65, 55),
                precip = listOf(0.0, 0.0, 0.0, 0.1, 0.6, 0.9, 1.2, 0.8, 0.5),
            ),
        ),
    )

    val allNamed: Map<String, WeatherSnapshot>
        get() = mapOf(
            "foggyAutumn" to foggyAutumn,
            "frostyWinter" to frostyWinter,
            "summerThunder" to summerThunder,
            "rainyWindy" to rainyWindy,
            "tomorrowEvening" to tomorrowEvening,
        )

}
