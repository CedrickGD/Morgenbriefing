package com.cedrickgd.morgenbriefing.ui.components

import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.ClothingAdvice
import com.cedrickgd.morgenbriefing.domain.ClothingItem
import com.cedrickgd.morgenbriefing.domain.WarningLevel
import com.cedrickgd.morgenbriefing.domain.WeatherWarning
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Fixture data for `@Preview`s: a foggy October morning in Bad Düben, sunny afternoon in Leipzig. */
internal object PreviewData {

    val date: LocalDate = LocalDate.of(2026, 10, 6)
    val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 6, 6, 40, 0, 0, ZoneId.of("Europe/Berlin"))

    private val leipzigTemps = listOf(12.6, 14.1, 16.0, 17.8, 19.2, 20.4, 20.9, 20.1, 18.6)
    private val leipzigPops = listOf(5, 0, 0, 10, 20, 35, 40, 15, 0)
    private val leipzigCodes = listOf(45, 2, 1, 1, 0, 2, 80, 2, 1)

    val snapshot = WeatherSnapshot(
        fetchedAt = "2026-10-06T05:30:12+02:00",
        home = HomeWeather(
            place = Place.BAD_DUEBEN,
            current = CurrentConditions(
                time = "2026-10-06T05:30",
                temp = 8.6,
                feels = 6.9,
                code = 45,
                isDay = false,
                windKmh = 7.0,
                gustKmh = 14.0,
                precipMm = 0.0,
                humidity = 96,
                visibilityM = 800.0,
            ),
            nextHours = listOf(5, 6, 7, 8).map { h ->
                HourPoint(
                    time = "2026-10-06T${h.toString().padStart(2, '0')}:00",
                    hour = h,
                    temp = 8.6 + (h - 5) * 0.4,
                    feels = 6.9 + (h - 5) * 0.4,
                    pop = if (h == 8) 10 else 0,
                    precipMm = 0.0,
                    code = 45,
                    isDay = h >= 7,
                    windKmh = 7.0,
                    gustKmh = 14.0,
                    visibilityM = 800.0,
                )
            },
            sunrise = "07:12",
            sunset = "18:41",
        ),
        day = DayWeather(
            place = Place.LEIPZIG,
            date = "2026-10-06",
            isTomorrow = false,
            startHour = 10,
            endHour = 18,
            hours = (10..18).mapIndexed { i, h ->
                HourPoint(
                    time = "2026-10-06T$h:00",
                    hour = h,
                    temp = leipzigTemps[i],
                    feels = leipzigTemps[i] - 1.0,
                    pop = leipzigPops[i],
                    precipMm = if (leipzigPops[i] >= 35) 0.3 else 0.0,
                    code = leipzigCodes[i],
                    isDay = true,
                    windKmh = 11.0,
                    gustKmh = 24.0,
                )
            },
            minTemp = 12.6,
            maxTemp = 20.9,
            maxPop = 40,
            precipSum = 0.6,
            maxGustKmh = 24.0,
            uvMax = 3.1,
            sunset = "18:41",
        ),
    )

    val clothing = ClothingAdvice(
        items = listOf(
            ClothingItem("🧥", "Jacke"),
            ClothingItem("👕", "T-Shirt"),
            ClothingItem("☂️", "Knirps"),
            ClothingItem("🕶️", "Sonnenbrille"),
        ),
        text = "Morgens ist es neblig und frisch – nimm die Jacke mit. Ab Mittag reicht ein T-Shirt, " +
            "gegen 16 Uhr kann ein kurzer Schauer kommen.",
        short = "Morgens Jacke, ab Mittag T-Shirt, Knirps.",
    )

    val warnings = listOf(
        WeatherWarning(WarningLevel.CAUTION, "🌫️", "Dichter Nebel", "Sicht unter 1 km – fahr heute früh besonders vorsichtig."),
        WeatherWarning(WarningLevel.INFO, "🌦️", "Schauer am Nachmittag", "Zwischen 15 und 17 Uhr kann es kurz regnen."),
    )

    val section = NewsSection(
        id = "ki",
        title = "KI & Tech",
        emoji = "🤖",
        items = listOf(
            NewsItem(
                headline = "EU einigt sich auf Regeln für KI-Rechenzentren",
                summary = "Betreiber großer Rechenzentren müssen künftig Strom- und Wasserverbrauch offenlegen. " +
                    "Das betrifft auch neue Standorte in Sachsen.",
                source = "Handelsblatt",
                url = "https://example.com/ki-1",
                publishedAt = "2026-10-05T18:20:00+02:00",
                importance = 8,
            ),
            NewsItem(
                headline = "Neues Smartphone-Update bringt Offline-Übersetzung",
                summary = "Die Funktion läuft komplett auf dem Gerät und unterstützt zum Start zwölf Sprachen.",
                source = "heise online",
                url = "https://example.com/ki-2",
                publishedAt = "2026-10-06T04:55:00+02:00",
                importance = 6,
            ),
        ),
    )

    const val TEASER = "EU zieht bei KI-Rechenzentren die Zügel an, der DAX startet ruhig, " +
        "und in Berlin wird über den Haushalt gestritten."
    const val GENERATED_AT = "2026-10-06T05:07:12+02:00"
}
