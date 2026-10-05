package com.cedrickgd.morgenbriefing.domain

import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlin.math.max

/**
 * "Was ziehe ich an?" — layers from the felt temperature at home now and the warmest felt
 * temperature in the day window, plus rain, sun and ice extras. Pure and deterministic.
 */
internal object ClothingAdvisor {

    private const val MAX_ITEMS = 5
    private const val MAX_SHORT_WORDS = 8

    private val WINTERMANTEL = ClothingItem("🧥", "Wintermantel")
    private val WINTERJACKE = ClothingItem("🧥", "Winterjacke")
    private val WARME_JACKE = ClothingItem("🧥", "warme Jacke")
    private val JACKE = ClothingItem("🧥", "Jacke")
    private val LEICHTE_JACKE = ClothingItem("🧥", "leichte Jacke")
    private val PULLI = ClothingItem("🧶", "Pulli")
    private val DUENNER_PULLI = ClothingItem("🧶", "dünner Pulli")
    private val T_SHIRT = ClothingItem("👕", "T-Shirt")
    private val KURZE_HOSE = ClothingItem("🩳", "kurze Hose")
    private val SCHAL = ClothingItem("🧣", "Schal")
    private val HANDSCHUHE = ClothingItem("🧤", "Handschuhe")
    private val SCHIRM = ClothingItem("☂️", "Schirm")
    private val REGENJACKE = ClothingItem("🧥", "Regenjacke")
    private val SONNENBRILLE = ClothingItem("🕶️", "Sonnenbrille")
    private val SONNENCREME = ClothingItem("🧴", "Sonnencreme")
    private val FESTE_SCHUHE = ClothingItem("🥾", "feste Schuhe")

    /** Clothing bands by felt temperature, coldest first (ordinal order matters). */
    internal enum class Band(
        /** Noun phrase for the card text ("Morgens Jacke oder Fleece"). */
        val wear: String,
        /** 1–2 words for the change form of the short line ("Morgens Jacke, ab Mittag T-Shirt"). */
        val compact: String,
        /** ≤ 3 words for the all-day form of the short line. */
        val short: String,
        /** "reichen" instead of "reicht". */
        val plural: Boolean,
        val items: List<ClothingItem>,
        /** Added only when the advice would otherwise show a single item. */
        val filler: ClothingItem?,
    ) {
        ARCTIC(
            "dicker Wintermantel mit Mütze, Handschuhen und Schal", "Wintermantel", "Wintermantel und Handschuhe",
            plural = false, items = listOf(WINTERMANTEL, SCHAL, HANDSCHUHE), filler = null,
        ),
        COLD(
            "warme Winterjacke, Mütze und Handschuhe", "Winterjacke", "Winterjacke und Handschuhe",
            plural = true, items = listOf(WINTERJACKE, HANDSCHUHE), filler = SCHAL,
        ),
        WARM_JACKET("warme Jacke", "warme Jacke", "warme Jacke", plural = false, items = listOf(WARME_JACKE), filler = SCHAL),
        JACKET("Jacke oder Fleece", "Jacke", "Jacke", plural = false, items = listOf(JACKE), filler = PULLI),
        LIGHT("leichte Jacke oder Pulli", "leichte Jacke", "leichte Jacke", plural = false, items = listOf(LEICHTE_JACKE), filler = PULLI),
        LONGSLEEVE("Langarm oder dünner Pulli", "Pulli", "dünner Pulli", plural = false, items = listOf(DUENNER_PULLI), filler = T_SHIRT),
        TSHIRT("T-Shirt", "T-Shirt", "T-Shirt", plural = false, items = listOf(T_SHIRT), filler = KURZE_HOSE),
        HOT("luftige Sachen und kurze Hose", "kurze Hose", "kurze Hose", plural = true, items = listOf(T_SHIRT, KURZE_HOSE), filler = null);

        val isWinter: Boolean get() = this <= COLD

        companion object {
            fun of(feels: Double): Band = when {
                feels < -5 -> ARCTIC
                feels < 2 -> COLD
                feels < 8 -> WARM_JACKET
                feels < 13 -> JACKET
                feels < 17 -> LIGHT
                feels < 21 -> LONGSLEEVE
                feels <= 26 -> TSHIRT
                else -> HOT
            }
        }
    }

    private enum class RainGear { NONE, UMBRELLA, RAINJACKET }

    private sealed interface Change {
        data object Same : Change
        data class Warmer(val band: Band, val hour: Int?) : Change
        data class Colder(val band: Band, val hour: Int?) : Change
    }

    private data class Pick(val item: ClothingItem, val priority: Int)

    fun advise(s: WeatherSnapshot): ClothingAdvice {
        val home = HomeFacts(s.home)
        val day = DayFacts(s.day)
        val tomorrow = s.day.isTomorrow

        // For tomorrow's window the current evening temperature says nothing about the morning.
        val morningFeels = if (tomorrow) day.hours.firstOrNull()?.feels ?: home.now.feels else home.now.feels
        val morning = Band.of(morningFeels)
        val change = changeOf(morning, day)

        val wetNow = !tomorrow && (home.wetNow || home.wetSoon != null)
        val needsRainGear = day.maxPop >= 40 || day.precipSum >= 0.5 || wetNow
        val gust = if (tomorrow) day.maxGust else max(day.maxGust, home.maxGust)
        val gear = when {
            !needsRainGear -> RainGear.NONE
            gust >= 40 -> RainGear.RAINJACKET
            else -> RainGear.UMBRELLA
        }
        // A rain jacket replaces an ordinary jacket instead of being worn over it.
        val merged = gear == RainGear.RAINJACKET && (morning == Band.JACKET || morning == Band.LIGHT)

        val sunglasses = day.sunnyHours >= 3
        val sunscreen = s.day.uvMax >= 5
        val shoes = day.conditions.any { it.isWintry } ||
            (day.isWetDay && day.minTemp <= 1.0) ||
            (!tomorrow && (home.slippery || home.wintryPrecip))

        val items = items(morning, change, gear, merged, shoes, sunscreen, sunglasses)
        val reason = if (tomorrow) null else reasonFor(home, morning)
        val text = text(morning, change, gear, merged, shoes, sunscreen, sunglasses, reason, rainWhy(home, day, tomorrow))
        val short = short(morning, change, gear, merged)
        return ClothingAdvice(items = items, text = text, short = short)
    }

    private fun changeOf(morning: Band, day: DayFacts): Change {
        val warm = Band.of(day.maxFeels)
        val cold = Band.of(day.minFeels)
        return when {
            warm > morning && !(warm.isWinter && morning.isWinter) ->
                Change.Warmer(warm, day.hours.firstOrNull { Band.of(it.feels) >= warm }?.hour)
            cold < morning && !(cold.isWinter && morning.isWinter) ->
                Change.Colder(cold, day.hours.firstOrNull { Band.of(it.feels) <= cold }?.hour)
            else -> Change.Same
        }
    }

    private fun items(
        morning: Band,
        change: Change,
        gear: RainGear,
        merged: Boolean,
        shoes: Boolean,
        sunscreen: Boolean,
        sunglasses: Boolean,
    ): List<ClothingItem> {
        val picks = mutableListOf<Pick>()
        morning.items.forEach { picks += Pick(it, 3) }
        if (merged) {
            val jacketIndex = picks.indexOfFirst { it.item == morning.items.first() }
            picks[jacketIndex] = Pick(REGENJACKE, 5)
        }
        val target = when (change) {
            is Change.Warmer -> change.band
            is Change.Colder -> change.band
            Change.Same -> null
        }
        val additions = when (change) {
            // Going from one jacket to a lighter jacket adds nothing to carry.
            is Change.Warmer -> if (change.band >= Band.LONGSLEEVE) change.band.items else emptyList()
            // Already in a jacket: only the extras (gloves …); otherwise the jacket for later.
            is Change.Colder -> if (morning <= Band.LIGHT) change.band.items.drop(1) else change.band.items.take(1)
            Change.Same -> emptyList()
        }
        additions.filter { item -> picks.none { it.item == item } }.forEach { picks += Pick(it, 3) }
        when (gear) {
            RainGear.UMBRELLA -> picks += Pick(SCHIRM, 5)
            RainGear.RAINJACKET -> if (!merged) picks += Pick(REGENJACKE, 5)
            RainGear.NONE -> Unit
        }
        if (shoes) picks += Pick(FESTE_SCHUHE, 4)
        if (sunscreen) picks += Pick(SONNENCREME, 2)
        if (sunglasses) picks += Pick(SONNENBRILLE, 1)

        val unique = picks.distinctBy { it.item.label }.toMutableList()
        if (unique.size < 2) {
            listOfNotNull(morning.filler, target?.filler, PULLI)
                .firstOrNull { item -> unique.none { it.item.label == item.label } }
                ?.let { unique += Pick(it, 0) }
        }
        // Keep the most important items, but show them in their natural order.
        return unique.withIndex()
            .sortedByDescending { it.value.priority }
            .take(MAX_ITEMS)
            .sortedBy { it.index }
            .map { it.value.item }
    }

    private fun reasonFor(home: HomeFacts, morning: Band): String? {
        if (morning > Band.LIGHT) return null
        val feels = Fmt.temp(home.now.feels)
        return when {
            home.condition == Condition.FOG -> "im Nebel fühlen sich $feels klamm an"
            home.now.temp - home.now.feels >= 3 -> "gefühlt nur $feels"
            home.now.feels <= 0 -> "gefühlt $feels"
            home.now.humidity >= 90 && home.now.feels <= 12 -> "die feuchte Luft macht's klamm"
            else -> null
        }
    }

    private fun rainWhy(home: HomeFacts, day: DayFacts, tomorrow: Boolean): String = when {
        !tomorrow && home.wetNow -> "draußen ist's gerade nass"
        !tomorrow && home.wetSoon != null -> "gleich wird's nass"
        day.rainStartsLater -> "ab ${day.firstWet?.hour} Uhr wird's nass"
        day.maxPop >= 40 -> "bis ${day.maxPop} % Regenrisiko"
        else -> "es kann nass werden"
    }

    private fun text(
        morning: Band,
        change: Change,
        gear: RainGear,
        merged: Boolean,
        shoes: Boolean,
        sunscreen: Boolean,
        sunglasses: Boolean,
        reason: String?,
        why: String,
    ): String {
        val sun = when {
            sunglasses && sunscreen -> "Sonnenbrille und Sonnencreme nicht vergessen"
            sunglasses -> "Sonnenbrille lohnt sich"
            sunscreen -> "Sonnencreme nicht vergessen"
            else -> null
        }
        val because = reason?.let { " – $it" }.orEmpty()
        val wear = if (merged) mergedWear(morning) else morning.wear
        val sentences = mutableListOf<String>()
        when (change) {
            is Change.Warmer -> {
                val layer = if (!merged && change.band >= Band.TSHIRT && morning in Band.WARM_JACKET..Band.LIGHT) {
                    " übers Shirt"
                } else {
                    ""
                }
                sentences += "Morgens $wear$layer$because."
                val verb = if (change.band.plural) "reichen" else "reicht"
                val tail = sun?.let { ", $it" }.orEmpty()
                sentences += "${fromHour(change.hour).capitalizeFirst()} $verb ${change.band.wear}$tail."
            }
            is Change.Colder -> {
                val tail = sun?.let { ", $it" }.orEmpty()
                sentences += "Morgens $wear$because$tail."
                sentences += "${fromHour(change.hour).capitalizeFirst()} wird's kühler – dann ${change.band.wear}."
            }
            Change.Same -> {
                sentences += if (morning >= Band.LONGSLEEVE && !merged) {
                    "Heute ${if (morning.plural) "reichen" else "reicht"} $wear$because."
                } else {
                    "Den ganzen Tag $wear$because."
                }
                sun?.let { sentences += "$it." }
            }
        }
        sentences += when (gear) {
            RainGear.NONE ->
                if (shoes) "Kein Schirm nötig, aber feste Schuhe – es kann glatt sein." else "Kein Schirm nötig."
            RainGear.UMBRELLA ->
                if (shoes) "Schirm einpacken und feste Schuhe an – es kann glatt werden." else "Schirm einpacken – $why."
            RainGear.RAINJACKET ->
                if (shoes) {
                    "Regenjacke statt Schirm – zu windig; dazu feste Schuhe, es kann glatt werden."
                } else {
                    "Regenjacke statt Schirm – zu windig."
                }
        }
        return sentences.joinToString(" ")
    }

    private fun mergedWear(morning: Band): String =
        if (morning == Band.LIGHT) "Shirt oder dünner Pulli unter der Regenjacke" else "Pulli oder Fleece unter der Regenjacke"

    private fun short(morning: Band, change: Change, gear: RainGear, merged: Boolean): String {
        val rain = when {
            merged -> "kein Schirm"
            gear == RainGear.UMBRELLA -> "Schirm einpacken"
            gear == RainGear.RAINJACKET -> "Regenjacke statt Schirm"
            else -> "kein Schirm"
        }
        val m = if (merged) "Regenjacke" else morning.compact
        val candidates = when (change) {
            is Change.Warmer -> {
                val a = change.band.compact
                listOf(
                    "Morgens $m, ${fromHour(change.hour)} $a, $rain",
                    "Morgens $m, später $a, $rain",
                    "$m, später $a, $rain",
                    "$m, $rain",
                )
            }
            is Change.Colder -> {
                val a = change.band.compact
                listOf(
                    "Erst $m, ${fromHour(change.hour)} $a, $rain",
                    "Erst $m, später $a, $rain",
                    "$m, später $a",
                    "$m, $rain",
                )
            }
            Change.Same ->
                if (merged) {
                    listOf("Regenjacke statt Schirm, drunter Pulli")
                } else {
                    listOf("Den ganzen Tag ${morning.short}, $rain", "${morning.short}, $rain", "${morning.compact}, $rain")
                }
        }
        val chosen = candidates.firstOrNull { it.wordCount() <= MAX_SHORT_WORDS } ?: candidates.last()
        return chosen.capitalizeFirst() + "."
    }
}
