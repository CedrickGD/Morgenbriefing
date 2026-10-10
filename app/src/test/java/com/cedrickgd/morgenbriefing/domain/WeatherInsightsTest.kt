package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.foggyAutumn
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.frostyWinter
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.rainyWindy
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.summerThunder
import com.cedrickgd.morgenbriefing.domain.WeatherFixtures.tomorrowEvening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherInsightsTest {

    private fun ClothingAdvice.labels() = items.map { it.label }

    private fun assertContains(haystack: String, needle: String) =
        assertTrue("Expected \"$needle\" in \"$haystack\"", haystack.contains(needle))

    private fun assertNotContains(haystack: String, needle: String) =
        assertFalse("Did not expect \"$needle\" in \"$haystack\"", haystack.contains(needle))

    // --- Foggy autumn morning ------------------------------------------------------------

    @Test
    fun `foggy autumn headline mentions fog and the golden October`() {
        val headline = WeatherInsights.headline(foggyAutumn)
        assertContains(headline, "Nebel")
        assertContains(headline, "goldener Oktober mit 21°")
        assertTrue(headline.endsWith("."))
        assertTrue(headline.length <= 60)
    }

    @Test
    fun `foggy autumn home note covers darkness, dryness and visibility`() {
        val note = WeatherInsights.homeNote(foggyAutumn)
        assertEquals(
            "Noch dunkel, Sonnenaufgang 07:18 – neblig, aber die nächsten Stunden trocken. " +
                "Sicht stellenweise nur gut 1 km – beim Fahren Licht an.",
            note,
        )
    }

    @Test
    fun `foggy autumn day summary`() {
        assertEquals(
            "Von 13° auf 21°, ab Mittag Sonne pur, kein Regen, kaum Wind.",
            WeatherInsights.daySummary(foggyAutumn),
        )
    }

    @Test
    fun `foggy autumn clothing - jacket to t-shirt, sunglasses, no umbrella`() {
        val advice = WeatherInsights.clothing(foggyAutumn)
        assertEquals(listOf("Jacke", "T-Shirt", "Sonnenbrille"), advice.labels())
        assertFalse(advice.labels().contains("Schirm"))
        assertEquals(
            "Morgens Jacke oder Fleece übers Shirt – im Nebel fühlen sich 8° klamm an. " +
                "Ab Mittag reicht T-Shirt, Sonnenbrille lohnt sich. Kein Schirm nötig.",
            advice.text,
        )
        assertEquals("Morgens Jacke, ab Mittag T-Shirt, kein Schirm.", advice.short)
    }

    @Test
    fun `foggy autumn warns about fog only`() {
        val warnings = WeatherInsights.warnings(foggyAutumn)
        assertEquals(1, warnings.size)
        val fog = warnings.single()
        assertEquals(WarningLevel.CAUTION, fog.level)
        assertEquals("🌫️", fog.emoji)
        assertEquals("Nebel", fog.title)
        assertEquals("Sicht teils nur ~1 km – Abblendlicht an und Abstand halten.", fog.text)
    }

    @Test
    fun `foggy autumn notification with today's brief`() {
        val n = WeatherInsights.notification(foggyAutumn, WeatherFixtures.brief("2026-10-06"), "2026-10-06")
        assertEquals("🌫️ 9° in Bad Düben · gefühlt 8°", n.title)
        assertEquals("Leipzig 10–18 Uhr: 13→21°, sonnig · Morgens Jacke, ab Mittag T-Shirt, kein Schirm", n.text)
        val lines = n.bigText.lines()
        assertEquals(4, lines.size)
        assertEquals(WeatherInsights.homeNote(foggyAutumn), lines[0])
        assertEquals("Leipzig 10–18 Uhr: " + WeatherInsights.daySummary(foggyAutumn), lines[1])
        // The first advised item (the jacket) leads the clothing line.
        assertEquals("🧥 Morgens Jacke, ab Mittag T-Shirt, kein Schirm.", lines[2])
        // Top 3 by importance, ties keep the brief's order.
        assertEquals("📰 Euro fällt auf Jahrestief · KI-Taskforce startet · Bundestag beschließt Haushalt", lines[3])
    }

    @Test
    fun `notification with yesterday's brief says the news are coming`() {
        val n = WeatherInsights.notification(foggyAutumn, WeatherFixtures.brief("2026-10-05"), "2026-10-06")
        assertEquals("📰 Die News kommen gleich.", n.bigText.lines().last())
        val none = WeatherInsights.notification(foggyAutumn, null, "2026-10-06")
        assertEquals("📰 Die News kommen gleich.", none.bigText.lines().last())
    }

    @Test
    fun `without a token the notification does not promise news`() {
        // Demo mode: no follow-up will ever bring today's news.
        val demo = WeatherInsights.notification(foggyAutumn, WeatherFixtures.brief("2026-10-05"), "2026-10-06", newsPending = false)
        assertEquals("📰 Demo-Modus – mit deinem GitHub-Token kommen hier deine News.", demo.bigText.lines().last())
        val noWeather = WeatherInsights.notification(null, null, "2026-10-06", newsPending = false)
        assertEquals("📰 Demo-Modus – mit deinem GitHub-Token kommen hier deine News.", noWeather.bigText.lines().last())
        // Today's brief at hand: its headlines, whatever the follow-up does.
        val today = WeatherInsights.notification(foggyAutumn, WeatherFixtures.brief("2026-10-06"), "2026-10-06", newsPending = false)
        assertTrue(today.bigText.lines().last().startsWith("📰 Euro fällt auf Jahrestief"))
    }

    @Test
    fun `notification without weather still works`() {
        val n = WeatherInsights.notification(null, WeatherFixtures.brief("2026-10-06"), "2026-10-06")
        assertEquals("☀️ Dein Morgenbriefing", n.title)
        assertEquals("Ein ruhiger Nachrichtentag.", n.text)
        assertTrue(n.bigText.lines().last().startsWith("📰 Euro fällt auf Jahrestief"))

        val empty = WeatherInsights.notification(null, null, "2026-10-06")
        assertEquals("☀️ Dein Morgenbriefing", empty.title)
        assertTrue(empty.text.isNotBlank())
        assertEquals("📰 Die News kommen gleich.", empty.bigText.lines().last())
    }

    // --- Frosty winter morning with freezing rain -------------------------------------------

    @Test
    fun `freezing rain is a severe ice warning listed first`() {
        val warnings = WeatherInsights.warnings(frostyWinter)
        val first = warnings.first()
        assertEquals(WarningLevel.SEVERE, first.level)
        assertEquals("Glatteis", first.title)
        assertEquals("🧊", first.emoji)
        assertEquals(1, warnings.count { it.emoji == "🧊" })
    }

    @Test
    fun `frosty winter clothing - full winter gear with gloves and sturdy shoes`() {
        val advice = WeatherInsights.clothing(frostyWinter)
        val labels = advice.labels()
        assertTrue(labels.toString(), "Handschuhe" in labels)
        assertTrue(labels.toString(), "Wintermantel" in labels)
        assertTrue(labels.toString(), "feste Schuhe" in labels)
        assertTrue(labels.toString(), "Schirm" in labels)
        assertTrue(labels.size in 2..5)
        assertContains(advice.text, "Handschuhen")
        assertContains(advice.text, "feste Schuhe")
        assertContains(advice.short, "Handschuhe")
    }

    @Test
    fun `frosty winter home note warns about black ice`() {
        val note = WeatherInsights.homeNote(frostyWinter)
        assertContains(note, "Noch dunkel, Sonnenaufgang 08:08")
        assertContains(note, "gefrierender Regen")
        assertContains(note, "spiegelglatt")
    }

    @Test
    fun `frosty winter headline mentions ice and never shows minus zero`() {
        val headline = WeatherInsights.headline(frostyWinter)
        assertContains(headline, "Glatteis")
        assertNotContains(headline, "-0°")
        assertEquals("Von 0° auf 2°, Regen bis 12 Uhr, danach trocken.", WeatherInsights.daySummary(frostyWinter))
    }

    // --- Summer heat with thunder ---------------------------------------------------------------

    @Test
    fun `summer heat warns about heat, thunder and UV`() {
        val warnings = WeatherInsights.warnings(summerThunder)
        val titles = warnings.map { it.title }
        assertTrue(titles.toString(), "Hitze" in titles)
        assertTrue(titles.toString(), "Gewitter" in titles)
        assertTrue(titles.toString(), "Hohe UV-Belastung" in titles)
        assertEquals(WarningLevel.CAUTION, warnings.first { it.title == "Hitze" }.level)
        assertEquals("Ab 16 Uhr Gewitter möglich – bei Blitz und Donner rein ins Haus.", warnings.first { it.title == "Gewitter" }.text)
        assertEquals(WarningLevel.INFO, warnings.last().level)
    }

    @Test
    fun `summer heat clothing recommends sunscreen`() {
        val advice = WeatherInsights.clothing(summerThunder)
        val labels = advice.labels()
        assertTrue(labels.toString(), "Sonnencreme" in labels)
        assertTrue(labels.toString(), "kurze Hose" in labels)
        assertTrue(labels.toString(), "Schirm" in labels)
        assertContains(advice.text, "Sonnencreme")
    }

    @Test
    fun `the notification's clothing line starts with the first advised item`() {
        listOf(foggyAutumn, frostyWinter, summerThunder, rainyWindy, tomorrowEvening).forEach { snapshot ->
            val advice = WeatherInsights.clothing(snapshot)
            val line = WeatherInsights.notification(snapshot, null, "2026-10-06").bigText.lines()
                .single { it.endsWith(advice.short) }
            assertEquals("${advice.items.first().emoji} ${advice.short}", line)
        }
        // A T-shirt day keeps the T-shirt.
        assertEquals("👕", WeatherInsights.clothingMarker(WeatherInsights.clothing(summerThunder)))
        // Without any item the T-shirt stands in.
        assertEquals("👕", WeatherInsights.clothingMarker(ClothingAdvice(items = emptyList(), text = "", short = "")))
    }

    @Test
    fun `summer heat headline and summary name the thunderstorm hour`() {
        assertEquals("Heiß bis 31°, ab 16 Uhr Gewitter.", WeatherInsights.headline(summerThunder))
        assertEquals(
            "Von 26° auf 31°, erst viel Sonne, ab 16 Uhr Gewitter möglich.",
            WeatherInsights.daySummary(summerThunder),
        )
        val n = WeatherInsights.notification(summerThunder, null, "2026-07-15")
        assertTrue(n.text, n.text.startsWith("Leipzig 10–18 Uhr: 26→31°, Gewitter ab 16 Uhr · "))
    }

    // --- Rainy and windy ------------------------------------------------------------------------

    @Test
    fun `rainy windy day - rain jacket instead of umbrella`() {
        val advice = WeatherInsights.clothing(rainyWindy)
        val labels = advice.labels()
        assertTrue(labels.toString(), "Regenjacke" in labels)
        assertFalse(labels.toString(), "Schirm" in labels)
        assertContains(advice.text, "Regenjacke statt Schirm – zu windig")
        assertTrue(advice.short, advice.short.wordCount() <= 8)
        // The rain jacket has its own pictogram, never the coat of the warm jacket next to it.
        val rainJacket = advice.items.single { it.label == "Regenjacke" }
        advice.items.filter { it.label != "Regenjacke" }.forEach { assertNotEquals(it.label, rainJacket.emoji, it.emoji) }
    }

    @Test
    fun `rainy windy day headline and summary`() {
        assertEquals("Windig und nass – heute lieber Kapuze.", WeatherInsights.headline(rainyWindy))
        assertEquals(
            "Von 12° auf 14°, Regen fast den ganzen Tag, bis 80 %, Böen bis 50 km/h.",
            WeatherInsights.daySummary(rainyWindy),
        )
        val note = WeatherInsights.homeNote(rainyWindy)
        assertContains(note, "es regnet")
    }

    // --- Tomorrow --------------------------------------------------------------------------------

    @Test
    fun `tomorrow's window gets the Morgen prefix`() {
        val headline = WeatherInsights.headline(tomorrowEvening)
        assertTrue(headline, headline.startsWith("Morgen: "))
        assertEquals("Morgen: Grauer Tag bei 15°, ab 14 Uhr Regen.", headline)
        val n = WeatherInsights.notification(tomorrowEvening, null, "2026-10-06")
        assertTrue(n.text, n.text.startsWith("Leipzig morgen 10–18 Uhr: 12→15°, Regen ab 14 Uhr · "))
    }

    @Test
    fun `tomorrow's clothing ignores tonight's dry conditions but sees the rain`() {
        val advice = WeatherInsights.clothing(tomorrowEvening)
        // Jacket in the morning, lighter jacket later: nothing extra to carry, but the umbrella.
        assertEquals(listOf("Jacke", "Schirm"), advice.labels())
        assertContains(advice.text, "ab 14 Uhr wird's nass")
    }

    // --- Edge cases ------------------------------------------------------------------------------

    @Test
    fun `calm mild day has no warnings`() {
        val calm = foggyAutumn.copy(
            home = foggyAutumn.home.copy(
                current = foggyAutumn.home.current.copy(code = 1, visibilityM = 30_000.0, humidity = 70, isDay = true, time = "2026-10-06T08:30"),
                nextHours = foggyAutumn.home.nextHours.map { it.copy(code = 1, visibilityM = 30_000.0) },
            ),
        )
        assertTrue(WeatherInsights.warnings(calm).isEmpty())
        assertEquals("Sonnig, die nächsten Stunden bleibt's trocken.", WeatherInsights.homeNote(calm))
    }

    @Test
    fun `storm gusts are severe and sorted before cautions`() {
        val stormy = rainyWindy.copy(
            day = rainyWindy.day.copy(
                hours = rainyWindy.day.hours.map { it.copy(gustKmh = 85.0) },
                maxGustKmh = 85.0,
            ),
        )
        val warnings = WeatherInsights.warnings(stormy)
        assertEquals("Sturm", warnings.first().title)
        assertEquals(WarningLevel.SEVERE, warnings.first().level)
        assertEquals(warnings.map { it.title }.distinct(), warnings.map { it.title })
    }

    @Test
    fun `heavy rain and extreme heat`() {
        val heavy = rainyWindy.copy(
            day = rainyWindy.day.copy(hours = rainyWindy.day.hours.mapIndexed { i, h -> if (i == 4) h.copy(code = 82, precipMm = 7.4) else h }),
        )
        val rain = WeatherInsights.warnings(heavy).first { it.title == "Starkregen" }
        assertEquals("Ab 14 Uhr bis 7 l/m² pro Stunde – Unterführungen und Senken meiden.", rain.text)

        val scorcher = summerThunder.copy(
            day = summerThunder.day.copy(hours = summerThunder.day.hours.map { it.copy(temp = it.temp + 5) }),
        )
        val heat = WeatherInsights.warnings(scorcher).first { it.title.contains("Hitze") }
        assertEquals(WarningLevel.SEVERE, heat.level)
        assertEquals("Starke Hitze", heat.title)
    }

    @Test
    fun `reif glaette on a dry humid frosty morning`() {
        val frosty = foggyAutumn.copy(
            home = foggyAutumn.home.copy(
                current = CurrentConditions(
                    time = "2026-12-02T06:00", temp = -1.0, feels = -4.0, code = 0, isDay = false,
                    windKmh = 5.0, gustKmh = 10.0, precipMm = 0.0, humidity = 93, visibilityM = 20_000.0,
                ),
                nextHours = foggyAutumn.home.nextHours.map { it.copy(code = 0, temp = -1.0, visibilityM = 20_000.0) },
                sunrise = "07:55",
            ),
        )
        val ice = WeatherInsights.warnings(frosty).first { it.emoji == "🧊" }
        assertEquals("Glätte", ice.title)
        assertEquals(WarningLevel.CAUTION, ice.level)
        assertContains(ice.text, "Reifglätte")
        assertContains(WeatherInsights.homeNote(frosty), "Glättegefahr bei -1°")
        assertTrue(WeatherInsights.headline(frosty), WeatherInsights.headline(frosty).startsWith("Frostiger Start, später "))
    }

    @Test
    fun `falling temperatures suggest taking a jacket along`() {
        val hours = WeatherFixtures.window(
            "2026-09-01", 10,
            temps = listOf(22.0, 21.0, 20.0, 18.0, 16.0, 14.0, 12.0, 11.0, 10.0),
            codes = List(9) { 3 },
        )
        val s = summerThunder.copy(day = WeatherFixtures.day("2026-09-01", hours, uvMax = 2.0))
        val advice = WeatherInsights.clothing(s)
        assertEquals(listOf("T-Shirt", "Jacke"), advice.labels())
        assertContains(advice.text, "Ab 16 Uhr wird's kühler – dann Jacke oder Fleece.")
        assertEquals("Erst T-Shirt, ab 16 Uhr Jacke, kein Schirm.", advice.short)
        assertTrue(WeatherInsights.daySummary(s).startsWith("Erst 22°, später nur noch 10°"))
    }

    @Test
    fun `flat temperatures read as um`() {
        val hours = WeatherFixtures.window("2026-11-21", 10, temps = List(9) { 8.0 }, codes = List(9) { 3 })
        val s = foggyAutumn.copy(day = WeatherFixtures.day("2026-11-21", hours))
        assertTrue(WeatherInsights.daySummary(s).startsWith("Um die 8°, meist grau"))
        val text = WeatherInsights.notification(s, null, "2026-11-21").text
        assertTrue(text, text.startsWith("Leipzig 10–18 Uhr: um 8°, bedeckt · "))
    }

    @Test
    fun `empty day window does not crash`() {
        val empty = foggyAutumn.copy(day = foggyAutumn.day.copy(hours = emptyList()))
        assertTrue(WeatherInsights.headline(empty).isNotBlank())
        assertTrue(WeatherInsights.daySummary(empty).startsWith("Zwischen "))
        assertTrue(WeatherInsights.clothing(empty).items.size in 2..5)
        assertNotNull(WeatherInsights.notification(empty, null, "2026-10-06"))
    }

    // --- Invariants over many synthetic days --------------------------------------------------

    @Test
    fun `invariants hold across a sweep of synthetic weather`() {
        val codes = listOf(0, 1, 2, 3, 45, 51, 61, 65, 66, 71, 80, 85, 95)
        val all = WeatherFixtures.allNamed.values.toMutableList()
        var seed = 0
        for (base in listOf(-14.0, -7.0, -2.0, 1.0, 5.0, 9.0, 14.0, 18.0, 23.0, 28.0, 33.0)) {
            for (pop in listOf(0, 30, 60, 90)) {
                for (gust in listOf(10.0, 45.0, 85.0)) {
                    seed++
                    val code = codes[seed % codes.size]
                    val month = 1 + seed % 12
                    val date = String.format(java.util.Locale.ROOT, "2026-%02d-15", month)
                    val temps = (0..8).map { base + it * 0.9 - (if (it > 6) 2.0 else 0.0) }
                    val hours = WeatherFixtures.window(
                        date = date, startHour = 10, temps = temps,
                        feels = temps.map { it - gust / 25 },
                        codes = (0..8).map { if (it % 3 == 0) code else codes[(seed + it) % codes.size] },
                        pops = (0..8).map { (pop + it * 5).coerceAtMost(100) },
                        precip = (0..8).map { if (pop >= 60) 0.8 else 0.0 },
                        gusts = (0..8).map { gust },
                    )
                    all += WeatherSnapshot(
                        fetchedAt = "${date}T05:30:00+02:00",
                        home = HomeWeather(
                            place = Place.BAD_DUEBEN,
                            current = CurrentConditions(
                                time = "${date}T05:30", temp = base - 3, feels = base - 5, code = code,
                                isDay = false, windKmh = gust / 2, gustKmh = gust, precipMm = if (pop >= 60) 0.5 else 0.0,
                                humidity = 70 + seed % 30, visibilityM = 400.0 + seed * 97 % 20_000,
                            ),
                            nextHours = WeatherFixtures.window(date, 5, temps.take(4).map { it - 4 }, codes = List(4) { code }, pops = List(4) { pop }),
                            sunrise = "06:40",
                            sunset = "19:10",
                        ),
                        day = WeatherFixtures.day(date, hours, isTomorrow = seed % 7 == 0, uvMax = (seed % 10).toDouble()),
                    )
                }
            }
        }

        for (s in all) {
            val ctx = "${s.day.date} ${s.home.current} ${s.day.hours.map { it.code }}"
            val headline = WeatherInsights.headline(s)
            assertTrue("headline too long: $headline | $ctx", headline.length <= 60)
            assertTrue(headline, headline.endsWith("."))
            assertTrue(headline, headline.first().isUpperCase())
            if (s.day.isTomorrow) assertTrue(headline, headline.startsWith("Morgen: "))

            val clothing = WeatherInsights.clothing(s)
            assertTrue("short too long: ${clothing.short} | $ctx", clothing.short.wordCount() <= 8)
            assertTrue("items: ${clothing.items} | $ctx", clothing.items.size in 2..5)
            assertEquals(clothing.items.map { it.label }.distinct(), clothing.items.map { it.label })
            val sentences = Regex("[.!?](\\s|$)").findAll(clothing.text).count()
            assertTrue("sentences: ${clothing.text} | $ctx", sentences in 1..3)
            assertFalse(
                "umbrella and rain jacket together: ${clothing.items}",
                clothing.items.any { it.label == "Schirm" } && clothing.items.any { it.label == "Regenjacke" },
            )

            val note = WeatherInsights.homeNote(s)
            val noteSentences = Regex("[.!?](\\s|$)").findAll(note).count()
            assertTrue("home note: $note", noteSentences in 1..2)

            val warnings = WeatherInsights.warnings(s)
            assertEquals(warnings.map { it.title }.distinct(), warnings.map { it.title })
            assertEquals(warnings.sortedByDescending { it.level.ordinal }, warnings)

            val n = WeatherInsights.notification(s, null, s.day.date)
            val texts = listOf(headline, note, WeatherInsights.daySummary(s), clothing.text, clothing.short, n.title, n.text, n.bigText) +
                warnings.map { it.text }
            for (text in texts) {
                assertNotContains(text, "-0°")
                assertNotContains(text, "null")
                assertNotContains(text, "  ")
                assertNotContains(text, "..")
            }
        }
    }
}
