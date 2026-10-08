package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlin.math.max

/**
 * Pure, deterministic German texts derived from a [WeatherSnapshot]. No Android APIs,
 * fully unit-tested in WeatherInsightsTest.
 *
 * Wording varies a little from day to day (chosen by a hash of the window's date), but the
 * same snapshot always produces the same texts.
 */
object WeatherInsights {

    private const val HEADLINE_MAX = 60
    private const val CLOTHING_MARKER = "👕"

    /** Friendly one-liner for the serif headline, e.g. "Nebel zum Start, dann goldener Oktober mit 21°." */
    fun headline(s: WeatherSnapshot): String {
        val day = DayFacts(s.day)
        val seed = s.day.date
        val prefix = if (s.day.isTomorrow) "Morgen: " else ""
        val start = if (s.day.isTomorrow) tomorrowStart(day, seed) else todayStart(HomeFacts(s.home), day, seed)

        val all = dayCandidates(day, start, seed).mapNotNull { candidate ->
            when (candidate) {
                is Candidate.Alone -> candidate.text
                is Candidate.After -> start?.let { "${it.text}, ${candidate.text}" }
            }
        }
        // "Morgen: Goldener Oktober: …" reads clumsy — avoid a second colon after the prefix.
        val texts = if (prefix.isEmpty()) all else all.filter { ':' !in it }.ifEmpty { all }
        val chosen = texts.firstOrNull { prefix.length + it.length + 1 <= HEADLINE_MAX }
            ?: texts.minByOrNull { it.length }
            ?: "Dein Wetter für heute"
        return "$prefix$chosen."
    }

    /** One or two sentences about stepping outside at home right now. */
    fun homeNote(s: WeatherSnapshot): String {
        val home = HomeFacts(s.home)
        val light = when {
            home.beforeSunrise ->
                if (home.sunrise != null) "Noch dunkel, Sonnenaufgang ${Fmt.clock(s.home.sunrise)}" else "Noch dunkel"
            home.afterSunset -> "Schon dunkel"
            else -> null
        }
        val weather = weatherClause(home)
        val first = (if (light != null) "$light – $weather" else weather.capitalizeFirst()) + "."
        return listOfNotNull(first, hazardSentence(home)).joinToString(" ")
    }

    /** One sentence about the day window, e.g. "Von 13° auf 21°, ab Mittag Sonne pur, trocken." */
    fun daySummary(s: WeatherSnapshot): String {
        val day = DayFacts(s.day)
        val parts = mutableListOf(temperatureTrend(day))
        skyPhrase(day)?.let(parts::add)
        parts += precipitationPhrase(day)
        windPhrase(day)?.let(parts::add)
        return parts.joinToString(", ").capitalizeFirst() + "."
    }

    fun clothing(s: WeatherSnapshot): ClothingAdvice = ClothingAdvisor.advise(s)

    /** Fog/visibility, ice, storm, thunder, heavy rain, heat, strong UV — empty on a calm day. */
    fun warnings(s: WeatherSnapshot): List<WeatherWarning> {
        val home = HomeFacts(s.home)
        val day = DayFacts(s.day)
        val out = mutableListOf<WeatherWarning>()

        iceWarning(home, day)?.let(out::add)
        fogWarning(home)?.let(out::add)
        gustWarning(home, day)?.let(out::add)
        thunderWarning(home, day)?.let(out::add)
        heavyRainWarning(home, day)?.let(out::add)
        heatWarning(day)?.let(out::add)
        uvWarning(s.day)?.let(out::add)

        // Stable sort: within a level the order above (most pressing for the morning) is kept.
        return out.distinctBy { it.title }.sortedByDescending { it.level.ordinal }
    }

    /**
     * Text for the 5:30 notification. [brief] may be null or older than today. [newsPending]:
     * a follow-up will bring today's news (there is a token); without one (demo) the news line
     * must not promise any.
     */
    fun notification(s: WeatherSnapshot?, brief: Brief?, today: String, newsPending: Boolean = true): NotificationText {
        val todaysBrief = brief?.takeIf { it.date.trim() == today.trim() }
        val news = newsLine(todaysBrief, newsPending)
        if (s == null) {
            val text = todaysBrief?.teaser?.takeIf { it.isNotBlank() }
                ?: "Wetter gerade nicht erreichbar – öffne die App für ein Update."
            val bigText = listOf("🌦️ Das Wetter ließ sich gerade nicht laden – in der App gibt's gleich ein Update.", news)
            return NotificationText(title = "☀️ Dein Morgenbriefing", text = text, bigText = bigText.joinToString("\n"))
        }

        val current = s.home.current
        val condition = Condition.fromCode(current.code)
        val title = "${condition.emoji(current.isDay)} ${Fmt.temp(current.temp)} in ${s.home.place.name} · " +
            "gefühlt ${Fmt.temp(current.feels)}"

        val day = DayFacts(s.day)
        val window = windowLabel(s.day)
        val clothing = clothing(s)
        val text = "$window: ${shortTrend(day)}, ${conditionLabel(day)} · ${clothing.short.removeSuffix(".")}"
        val bigText = listOf(
            homeNote(s),
            "$window: ${daySummary(s)}",
            "${clothingMarker(clothing)} ${clothing.short}",
            news,
        ).joinToString("\n")
        return NotificationText(title = title, text = text, bigText = bigText)
    }

    /**
     * Emoji in front of the clothing line of the notification: the first advised item (🧥 on a
     * jacket day, 👕 on a T-shirt day), 👕 without any. Notifications.kt swaps it like every
     * emoji of the text where the device's font cannot draw it (Android 7).
     */
    internal fun clothingMarker(advice: ClothingAdvice): String =
        advice.items.firstOrNull()?.emoji?.takeIf { it.isNotBlank() } ?: CLOTHING_MARKER

    // ---------------------------------------------------------------------------------------
    // Headline
    // ---------------------------------------------------------------------------------------

    /** How the morning starts; [connector] links it to the rest of the day ("dann", "später"). */
    private data class Start(val kind: StartKind, val text: String, val connector: String)

    private enum class StartKind { ICE, FOG, WET, FROST }

    /** A headline candidate without the final period. */
    private sealed interface Candidate {
        /** A complete sentence on its own. */
        data class Alone(val text: String) : Candidate

        /** Follows "<start>, " and already contains its connector. */
        data class After(val text: String) : Candidate
    }

    private fun todayStart(home: HomeFacts, day: DayFacts, seed: String): Start? = when {
        home.freezingRain -> Start(StartKind.ICE, "Glatteis am Morgen", "später")
        home.condition == Condition.FOG -> Start(StartKind.FOG, pick(seed, 1, listOf("Nebel zum Start", "Erst Nebel")), "dann")
        home.wetNow && !day.isWetDay && day.thunder == null ->
            Start(StartKind.WET, if (home.condition.isWintry) "Schnee zum Start" else "Nass zum Start", "danach")
        home.now.temp <= -6 -> Start(StartKind.FROST, "Eisiger Start", "später")
        home.now.temp <= 0 -> Start(StartKind.FROST, "Frostiger Start", "später")
        else -> null
    }

    /** For tomorrow's window the current evening says nothing; look at the window's first hour. */
    private fun tomorrowStart(day: DayFacts, seed: String): Start? {
        val first = day.hours.firstOrNull() ?: return null
        return when {
            first.condition == Condition.FOG ->
                Start(StartKind.FOG, pick(seed, 1, listOf("Nebel zum Start", "Erst Nebel")), "dann")
            first.temp <= 0 -> Start(StartKind.FROST, "Frostiger Start", "später")
            else -> null
        }
    }

    /** Candidates ordered by preference; the first one that fits [HEADLINE_MAX] wins. */
    private fun dayCandidates(d: DayFacts, start: Start?, seed: String): List<Candidate> {
        val t = Fmt.temp(d.maxTemp)
        val conn = start?.connector ?: "dann"
        val out = mutableListOf<Candidate>()
        val thunder = d.thunder
        when {
            thunder != null -> {
                val (base, baseLower) = when {
                    d.maxTemp >= 30 -> "Heiß bis $t" to "heiß bis $t"
                    d.maxTemp >= 25 -> "Schwül bis $t" to "schwül bis $t"
                    else -> "Wechselhaft bei $t" to "wechselhaft bei $t"
                }
                if (thunder !== d.hours.first()) {
                    val h = thunder.hour
                    out += Candidate.After("$conn $baseLower, ab $h Uhr Gewitter")
                    out += Candidate.After("ab $h Uhr Gewitter")
                    out += Candidate.Alone("$base, ab $h Uhr Gewitter")
                    out += Candidate.Alone("Ab $h Uhr Gewitter")
                } else {
                    out += Candidate.After("$conn Gewitter bei bis zu $t")
                    out += Candidate.Alone("Gewittriger Tag bei bis zu $t")
                    out += Candidate.Alone("Gewittrig bei $t")
                }
            }
            d.freezing != null -> {
                out += Candidate.After("$conn gefrierender Regen")
                out += Candidate.Alone("Gefrierender Regen – heute wird's glatt")
                out += Candidate.Alone("Achtung, Eisregen")
            }
            d.isWetDay && d.maxGust >= 50 -> {
                if (d.wetKind.isWintry) {
                    out += Candidate.After("$conn Schneetreiben")
                    out += Candidate.Alone("Schneetreiben – heute lieber Kapuze")
                    out += Candidate.Alone("Schneetreiben bei $t")
                } else {
                    out += Candidate.After("$conn windig und nass")
                    out += Candidate.Alone("Windig und nass – heute lieber Kapuze")
                    out += Candidate.Alone("Windig und nass bei $t")
                }
            }
            d.isWetDay -> wetCandidates(d, conn, t, seed, out)
            else -> dryCandidates(d, start, conn, t, seed, out)
        }
        return out
    }

    private fun wetCandidates(d: DayFacts, conn: String, t: String, seed: String, out: MutableList<Candidate>) {
        val noun = precipNoun(d.wetKind)
        val firstWet = d.firstWet
        val dryFrom = d.dryFrom
        when {
            d.rainStartsLater && firstWet != null -> {
                val h = firstWet.hour
                val before = dominantCondition(d.hours.takeWhile { it !== firstWet }.map { it.condition })
                val base = when {
                    before?.isSunny == true -> "Erst freundlich"
                    before == Condition.PARTLY_CLOUDY -> "Erst trocken"
                    else -> "Grauer Tag"
                }
                out += Candidate.After("ab $h Uhr $noun")
                out += Candidate.Alone("$base bei $t, ab $h Uhr $noun")
                out += Candidate.Alone("$base, ab $h Uhr $noun")
                out += Candidate.Alone("Ab $h Uhr $noun")
            }
            dryFrom != null -> {
                out += Candidate.After("$conn $noun, ab $dryFrom Uhr trocken")
                out += Candidate.After("ab $dryFrom Uhr trocken")
                out += Candidate.Alone("Erst $noun, ab $dryFrom Uhr trocken bei $t")
                out += Candidate.Alone("Erst $noun, ab $dryFrom Uhr trocken")
            }
            else -> {
                out += Candidate.After("$conn $noun bei $t")
                val alone = when (d.wetKind) {
                    Condition.DRIZZLE -> listOf("Nieselgrau bei höchstens $t", "Trüb mit Niesel bei $t")
                    Condition.SHOWERS -> listOf("Schauerwetter bei bis zu $t", "Immer wieder Schauer bei $t")
                    Condition.HEAVY_RAIN -> listOf("Kräftiger Regen bei höchstens $t", "Sehr nass bei $t")
                    Condition.SNOW -> listOf("Schneetag bei höchstens $t", "Winterlich mit Schnee bei $t")
                    Condition.SNOW_SHOWERS -> listOf("Immer wieder Schneeschauer bei $t", "Schneeschauer bei $t")
                    else -> listOf("Regnerischer Tag bei höchstens $t", "Nass und grau bei höchstens $t")
                }
                val first = pick(seed, 2, alone)
                out += Candidate.Alone(first)
                alone.filter { it != first }.forEach { out += Candidate.Alone(it) }
                out += Candidate.Alone(noun.capitalizeFirst())
            }
        }
    }

    private fun dryCandidates(
        d: DayFacts,
        start: Start?,
        conn: String,
        t: String,
        seed: String,
        out: MutableList<Candidate>,
    ) {
        val month = d.month
        val after: List<String>
        val alone: List<String>
        when {
            d.maxTemp >= 30 -> {
                after = listOf("$conn heiß bis $t")
                alone = listOf("Hitze bis $t – ab in den Schatten", "Heiß bei bis zu $t")
            }
            d.mostlySunny && d.maxTemp >= 25 -> {
                after = listOf("$conn Sommer pur mit $t", "$conn sommerlich bei $t")
                alone = if (d.allSunny && !d.showersPossible) {
                    listOf("Sommer pur: bis $t und kaum eine Wolke", "Sommerlich bei bis zu $t")
                } else {
                    listOf("Sommerlich mit viel Sonne bei $t", "Sommerlich bei bis zu $t")
                }
            }
            month != null && month in 9..11 && d.mostlySunny && d.maxTemp >= 15 -> {
                val name = if (month == 10) "goldener Oktober" else "goldener Herbst"
                after = listOf("$conn $name mit $t")
                alone = listOf("${name.capitalizeFirst()}: Sonne satt bei $t", "${name.capitalizeFirst()} mit $t")
            }
            month != null && month in 3..5 && d.mostlySunny && d.maxTemp >= 15 -> {
                after = listOf("$conn frühlingshaft bei $t")
                alone = listOf("Frühling pur: Sonne bei bis zu $t", "Frühlingshaft bei $t")
            }
            d.mostlySunny && d.maxTemp <= 2 -> {
                after = listOf("$conn sonnig, aber kalt bei $t")
                alone = listOf("Kalt, aber sonnig bei höchstens $t", "Sonnig, aber kalt bei $t")
            }
            d.mostlySunny && d.maxTemp < 15 -> {
                after = listOf("$conn freundlich bei $t")
                alone = listOf(
                    pick(seed, 3, listOf("Freundlich und sonnig bei bis zu $t", "Viel Sonne bei kühlen $t")),
                    "Freundlich bei $t",
                )
            }
            d.mostlySunny -> {
                after = listOf("$conn sonnig bei $t", "$conn viel Sonne bei $t")
                alone = listOf(
                    pick(seed, 4, listOf("Viel Sonne bei bis zu $t", "Sonnig und angenehm bei $t")),
                    "Sonnig bei $t",
                )
            }
            d.maxTemp < 0 -> {
                after = if (start?.kind == StartKind.FROST) emptyList() else listOf("$conn Dauerfrost bei $t")
                alone = listOf("Dauerfrost bei höchstens $t", "Eisig bei $t")
            }
            d.main == Condition.FOG -> {
                if (start?.kind == StartKind.FOG) {
                    after = emptyList()
                    alone = listOf("Zäher Nebel, kaum mehr als $t", "Neblig-trüb bei $t")
                } else {
                    after = listOf("$conn trüb bei $t")
                    alone = listOf("Trüber Nebeltag bei höchstens $t", "Neblig-trüb bei $t")
                }
            }
            d.main == Condition.OVERCAST -> {
                after = listOf("$conn grau bei $t")
                alone = listOf(
                    pick(seed, 5, listOf("Grauer Tag bei höchstens $t", "Grau in grau bei bis zu $t")),
                    "Grau bei $t",
                )
            }
            d.main.isWet -> {
                after = listOf("$conn wechselhaft bei $t")
                alone = listOf("Wechselhaft bei bis zu $t", "Wechselhaft bei $t")
            }
            else -> {
                after = listOf("$conn locker bewölkt bei $t")
                alone = listOf(
                    pick(seed, 6, listOf("Mal Sonne, mal Wolken bei bis zu $t", "Sonne und Wolken im Wechsel, bis $t")),
                    "Wolkig mit Sonne bei $t",
                )
            }
        }

        val tail = when {
            d.showersPossible || d.main.isWet -> if (d.maxTemp <= 2) "vereinzelt Flocken" else "vereinzelt Schauer"
            else -> null
        }
        if (tail != null) {
            // Keeping the shower hint beats keeping the start of the morning.
            after.forEach { out += Candidate.After("$it, $tail") }
            out += Candidate.Alone("${alone.last()}, $tail")
        }
        after.forEach { out += Candidate.After(it) }
        alone.forEach { out += Candidate.Alone(it) }
    }

    // ---------------------------------------------------------------------------------------
    // Home note
    // ---------------------------------------------------------------------------------------

    private fun weatherClause(home: HomeFacts): String {
        val soon = home.wetSoon
        return when {
            home.wetNow -> {
                val verb = when (home.condition) {
                    Condition.DRIZZLE -> "es nieselt"
                    Condition.SNOW, Condition.SNOW_SHOWERS -> "es schneit"
                    Condition.FREEZING_RAIN -> "es fällt gefrierender Regen"
                    Condition.THUNDER -> "es gewittert"
                    Condition.HEAVY_RAIN -> "es schüttet"
                    Condition.RAIN, Condition.SHOWERS -> "es regnet"
                    else -> "es regnet leicht"
                }
                val dry = home.dryAgain
                if (dry != null) "$verb, gegen ${dry.hour} Uhr wird's trocken" else "$verb und das hält erst mal an"
            }
            soon != null -> {
                val time = if (soon === home.upcoming.firstOrNull()) "gleich" else "ab ${soon.hour} Uhr"
                when (soon.condition) {
                    Condition.FREEZING_RAIN -> "noch trocken, $time droht Eisregen"
                    Condition.SNOW, Condition.SNOW_SHOWERS -> "noch trocken, $time kann's schneien"
                    Condition.THUNDER -> "noch trocken, $time kann's gewittern"
                    else -> "noch trocken, $time kann's regnen"
                }
            }
            home.condition == Condition.FOG -> "neblig, aber die nächsten Stunden trocken"
            home.condition == Condition.OVERCAST -> "grau, aber die nächsten Stunden trocken"
            home.condition.isSunny && home.now.isDay -> "sonnig, die nächsten Stunden bleibt's trocken"
            else -> "die nächsten Stunden bleibt's trocken"
        }
    }

    private fun hazardSentence(home: HomeFacts): String? {
        val temp = Fmt.temp(home.now.temp)
        val visibility = home.visibilityNow
        return when {
            // The first sentence already says that freezing rain is falling right now.
            home.freezingRain && home.condition == Condition.FREEZING_RAIN ->
                "Gehwege und Straßen werden spiegelglatt – langsam machen und Zeit einplanen."
            home.freezingRain -> "Gefrierender Regen in Sicht: Gehwege und Straßen werden spiegelglatt – langsam machen."
            home.slippery -> "Glättegefahr bei $temp – vorsichtig auf Gehwegen und Brücken."
            visibility != null && visibility < 2000 ->
                "Sicht stellenweise nur ${visibilityPhrase(visibility)} – beim Fahren Licht an."
            home.foggy -> "Nebel – beim Fahren Licht an und Abstand halten."
            home.maxGust >= 45 -> "Böen bis ${roundHalfUp(home.maxGust)} km/h – der Schirm hat's heute schwer."
            home.now.temp <= 0 -> "Frost bei $temp – Autoscheibe kratzen einplanen."
            else -> null
        }
    }

    /** "gut 1 km", "etwa 400 m" — for running text. */
    private fun visibilityPhrase(meters: Double): String = when {
        meters < 100 -> "unter 100 m"
        meters < 1000 -> "etwa ${roundHalfUp(meters / 100) * 100} m"
        meters < 1250 -> "gut 1 km"
        meters < 1750 -> "rund 1,5 km"
        else -> "knapp 2 km"
    }

    /** "~1 km", "~400 m", "~1,5 km" — for the compact warning text. */
    private fun approxDistance(meters: Double): String {
        if (meters < 950) return "~${max(100, roundHalfUp(meters / 100) * 100)} m"
        val halfKm = roundHalfUp(meters / 500)
        return if (halfKm % 2 == 0) "~${halfKm / 2} km" else "~${halfKm / 2},5 km"
    }

    // ---------------------------------------------------------------------------------------
    // Day summary
    // ---------------------------------------------------------------------------------------

    private fun temperatureTrend(d: DayFacts): String {
        if (!d.hasHours) return "zwischen ${Fmt.temp(d.minTemp)} und ${Fmt.temp(d.maxTemp)}"
        val start = roundHalfUp(d.startTemp)
        val peak = roundHalfUp(d.maxTemp)
        val end = roundHalfUp(d.endTemp)
        val peakIndex = d.hours.indexOf(d.peak)
        return when {
            peak - start >= 2 && peakIndex > 0 -> "von ${Fmt.temp(d.startTemp)} auf ${Fmt.temp(d.maxTemp)}"
            start - end >= 2 -> "erst ${Fmt.temp(d.startTemp)}, später nur noch ${Fmt.temp(d.endTemp)}"
            else -> "um die ${Fmt.temp((d.minTemp + d.maxTemp) / 2)}"
        }
    }

    /**
     * Sky development. On wet or thundery days only the dry stretch before the first event is
     * described, and only when it is sunny.
     */
    private fun skyPhrase(d: DayFacts): String? {
        if (!d.hasHours) return null
        val firstEvent = listOfNotNull(
            d.thunder?.let(d.hours::indexOf),
            if (d.isWetDay) d.firstWet?.let(d.hours::indexOf) else null,
        ).minOrNull()
        if (firstEvent != null) {
            val before = d.conditions.take(firstEvent)
            return when {
                before.size < 2 -> null
                before.all { it == Condition.CLEAR } -> "erst Sonne pur"
                before.count { it.isSunny } * 3 >= before.size * 2 -> "erst viel Sonne"
                else -> null
            }
        }

        val conditions = d.conditions
        if (conditions.all { it == Condition.CLEAR }) return "Sonne pur"
        if (conditions.all { it.isSunny }) return "viel Sonne"

        // Turning sunny: everything from index i on is sunny (at least two hours of it).
        val sunnyFrom = (1..conditions.size - 2).firstOrNull { i -> conditions.drop(i).all { it.isSunny } }
        if (sunnyFrom != null) {
            val what = if (conditions.drop(sunnyFrom).all { it == Condition.CLEAR }) "Sonne pur" else "viel Sonne"
            return "${fromHour(d.hours[sunnyFrom].hour)} $what"
        }
        // Clouding over: a sunny start of at least two hours, then at least two hours without sun.
        val cloudsFrom = (2..conditions.size - 2).firstOrNull { j ->
            conditions.take(j).all { it.isSunny } && conditions.drop(j).none { it.isSunny }
        }
        if (cloudsFrom != null) return "erst Sonne, ${fromHour(d.hours[cloudsFrom].hour)} Wolken"
        if (conditions.count { it.isSunny } * 2 >= conditions.size) return "viel Sonne"
        return when (dominantCondition(conditions)) {
            Condition.PARTLY_CLOUDY -> "mal Sonne, mal Wolken"
            Condition.OVERCAST -> "meist grau"
            Condition.FOG -> "trüb und neblig"
            else -> "wechselhaft"
        }
    }

    private fun precipitationPhrase(d: DayFacts): String {
        val thunder = d.thunder
        if (thunder != null) {
            val hail = d.hours.any { isHailCode(it.code) }
            val what = if (hail) "Gewitter mit Hagel möglich" else "Gewitter möglich"
            return if (thunder === d.hours.first()) what else "ab ${thunder.hour} Uhr $what"
        }
        val firstWet = d.firstWet
        val dryFrom = d.dryFrom
        if (d.isWetDay && firstWet != null) {
            val noun = precipNoun(d.wetKind)
            return when {
                d.rainStartsLater -> "$noun ab ${firstWet.hour} Uhr, bis ${d.maxPop} %"
                dryFrom != null -> "$noun bis $dryFrom Uhr, danach trocken"
                d.wetHours.size * 10 >= d.hours.size * 7 -> "$noun fast den ganzen Tag, bis ${d.maxPop} %"
                else -> "immer wieder $noun, bis ${d.maxPop} %"
            }
        }
        return when {
            d.showersPossible -> if (d.maxTemp <= 1) "vereinzelt Schneeflocken möglich" else "einzelne Schauer möglich"
            d.maxTemp <= 2 -> "trocken"
            else -> "kein Regen"
        }
    }

    private fun windPhrase(d: DayFacts): String? = when {
        d.maxGust >= 50 -> "Böen bis ${roundHalfUp(d.maxGust)} km/h"
        d.hasHours && d.maxGust < 20 && d.maxWind < 10 -> "kaum Wind"
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Warnings
    // ---------------------------------------------------------------------------------------

    private fun iceWarning(home: HomeFacts, day: DayFacts): WeatherWarning? {
        val dayFreezing = day.freezing
        val dayIcy = day.hours.firstOrNull { it.temp <= 1.0 && (it.isWetPoint() || (it.condition.isWet && it.pop >= 30)) }
        return when {
            home.freezingRain -> WeatherWarning(
                WarningLevel.SEVERE, "🧊", "Glatteis",
                "Gefrierender Regen – Wege und Straßen werden spiegelglatt. Langsam machen und Zeit einplanen.",
            )
            dayFreezing != null -> WeatherWarning(
                WarningLevel.SEVERE, "🧊", "Glatteis",
                "Ab ${dayFreezing.hour} Uhr gefrierender Regen – Wege und Straßen werden spiegelglatt.",
            )
            home.slippery -> {
                val temp = Fmt.temp(home.now.temp)
                val wet = home.wetNow || home.upcoming.any { it.temp <= 1.0 && it.isWetPoint() }
                val text = if (wet) {
                    val what = if (home.condition.isWintry || home.wintryPrecip) "Schnee" else "Nässe"
                    "Bei $temp und $what können Wege und Straßen glatt sein – langsam machen."
                } else {
                    "Bei $temp und feuchter Luft ist Reifglätte möglich, vor allem auf Brücken."
                }
                WeatherWarning(WarningLevel.CAUTION, "🧊", "Glätte", text)
            }
            dayIcy != null -> WeatherWarning(
                WarningLevel.CAUTION, "🧊", "Glätte",
                "Ab ${dayIcy.hour} Uhr ${precipNoun(dayIcy.condition.takeIf { it.isWet } ?: Condition.RAIN)} " +
                    "bei ${Fmt.temp(dayIcy.temp)} – es kann glatt werden.",
            )
            else -> null
        }
    }

    private fun fogWarning(home: HomeFacts): WeatherWarning? {
        val fogCode = home.condition == Condition.FOG || home.upcoming.any { it.condition == Condition.FOG }
        val visibility = home.minVisibility
        if (!fogCode && (visibility == null || visibility >= 1000)) return null
        val text = if (visibility != null && visibility < 2000) {
            "Sicht teils nur ${approxDistance(visibility)} – Abblendlicht an und Abstand halten."
        } else {
            "Nebel möglich – Abblendlicht an und Abstand halten."
        }
        return WeatherWarning(WarningLevel.CAUTION, "🌫️", "Nebel", text)
    }

    private fun gustWarning(home: HomeFacts, day: DayFacts): WeatherWarning? {
        val gust = max(home.maxGust, day.maxGust)
        val kmh = roundHalfUp(gust)
        return when {
            gust >= 80 -> WeatherWarning(
                WarningLevel.SEVERE, "💨", "Sturm",
                "Sturmböen bis $kmh km/h – Wald und Alleen meiden, lose Sachen sichern.",
            )
            gust >= 60 -> WeatherWarning(
                WarningLevel.CAUTION, "💨", "Starke Böen",
                "Böen bis $kmh km/h – lose Sachen sichern und auf herabfallende Äste achten.",
            )
            else -> null
        }
    }

    private fun thunderWarning(home: HomeFacts, day: DayFacts): WeatherWarning? {
        val homeHour = home.upcoming.firstOrNull { it.condition == Condition.THUNDER }
        val dayHour = day.thunder
        val hail = (home.upcoming + day.hours).any { it.condition == Condition.THUNDER && isHailCode(it.code) } ||
            isHailCode(home.now.code)
        val what = if (hail) "Gewitter mit Hagel möglich" else "Gewitter möglich"
        val text = when {
            home.condition == Condition.THUNDER -> "Gerade Gewitter – bei Blitz und Donner lieber drinnen bleiben."
            homeHour != null -> "Ab ${homeHour.hour} Uhr $what – bei Blitz und Donner rein ins Haus."
            dayHour != null -> "Ab ${dayHour.hour} Uhr $what – bei Blitz und Donner rein ins Haus."
            else -> return null
        }
        return WeatherWarning(WarningLevel.CAUTION, "⛈️", "Gewitter", text)
    }

    private fun heavyRainWarning(home: HomeFacts, day: DayFacts): WeatherWarning? {
        val heavy = (home.upcoming + day.hours).filter { isHeavyRainCode(it.code) || it.precipMm >= 5 }
        val first = heavy.firstOrNull() ?: return null
        val maxMm = heavy.maxOf { it.precipMm }
        val text = if (maxMm >= 5) {
            "Ab ${first.hour} Uhr bis ${roundHalfUp(maxMm)} l/m² pro Stunde – Unterführungen und Senken meiden."
        } else {
            "Ab ${first.hour} Uhr kräftige Güsse – Unterführungen und Senken meiden."
        }
        return WeatherWarning(WarningLevel.CAUTION, "🌧️", "Starkregen", text)
    }

    private fun heatWarning(day: DayFacts): WeatherWarning? {
        val temp = Fmt.temp(day.maxTemp)
        return when {
            day.maxTemp >= 35 -> WeatherWarning(
                WarningLevel.SEVERE, "🌡️", "Starke Hitze",
                "Bis $temp – viel trinken, Anstrengung meiden und im Schatten bleiben.",
            )
            day.maxTemp >= 30 -> WeatherWarning(
                WarningLevel.CAUTION, "🌡️", "Hitze",
                "Bis $temp – viel trinken und die Mittagssonne meiden.",
            )
            else -> null
        }
    }

    private fun uvWarning(day: DayWeather): WeatherWarning? {
        if (day.uvMax < 6) return null
        val index = roundHalfUp(day.uvMax)
        // WHO scale on the rounded index the text shows: 6–7 high, 8+ very high.
        val title = if (index >= 8) "Sehr hohe UV-Belastung" else "Hohe UV-Belastung"
        return WeatherWarning(
            WarningLevel.INFO, "🔆", title,
            "UV-Index bis $index – Sonnencreme und Kopfbedeckung nicht vergessen.",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    /** "Leipzig 10–18 Uhr" or "Leipzig morgen 10–18 Uhr". */
    private fun windowLabel(day: DayWeather): String {
        val tomorrow = if (day.isTomorrow) "morgen " else ""
        return "${day.place.name} $tomorrow${day.startHour}–${day.endHour} Uhr"
    }

    /** "13→21°" (start to peak), start to end when it only gets colder, "um 8°" when flat. */
    private fun shortTrend(d: DayFacts): String {
        val from = if (d.hasHours) d.startTemp else d.minTemp
        val to = when {
            !d.hasHours -> d.maxTemp
            roundHalfUp(d.maxTemp) > roundHalfUp(d.startTemp) -> d.maxTemp
            else -> d.endTemp
        }
        if (roundHalfUp(from) == roundHalfUp(to)) return "um ${Fmt.temp(to)}"
        return "${Fmt.temp(from).removeSuffix("°")}→${Fmt.temp(to)}"
    }

    private fun conditionLabel(d: DayFacts): String {
        val thunder = d.thunder
        val firstWet = d.firstWet
        val dryFrom = d.dryFrom
        return when {
            thunder != null && thunder !== d.hours.first() -> "Gewitter ab ${thunder.hour} Uhr"
            thunder != null -> "Gewitter"
            d.rainStartsLater && firstWet != null -> "${d.wetKind.labelFor(true)} ab ${firstWet.hour} Uhr"
            dryFrom != null -> "${d.wetKind.labelFor(true)} bis $dryFrom Uhr"
            else -> d.main.labelFor(true)
        }
    }

    private fun newsLine(todaysBrief: Brief?, newsPending: Boolean): String {
        if (todaysBrief == null) {
            return if (newsPending) "📰 Die News kommen gleich." else "📰 Demo-Modus – mit deinem GitHub-Token kommen hier deine News."
        }
        val top = todaysBrief.sections
            .flatMap { it.items }
            .filter { it.headline.isNotBlank() }
            .sortedByDescending { it.importance }
            .take(3)
            .map { it.headline.trim() }
        return when {
            top.isNotEmpty() -> "📰 " + top.joinToString(" · ")
            todaysBrief.teaser.isNotBlank() -> "📰 ${todaysBrief.teaser.trim()}"
            else -> "📰 Heute gibt's keine Meldungen."
        }
    }
}
