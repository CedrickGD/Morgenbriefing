package com.cedrickgd.morgenbriefing.data.news

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.Topics

/**
 * The built-in RSS/Atom sources per topic: German newsrooms with public feeds, checked by hand.
 * Every source can be switched off in the settings; the user adds their own with
 * [AppSettings.customFeeds]. The "Region" topic takes its sources from [Regions].
 */
object FeedCatalog {

    private fun src(id: String, name: String, topic: String, url: String) = FeedSource(id, name, url, topic)

    val builtIn: List<FeedSource> = listOf(
        // 🤖 KI & Tech
        src("heise-top", "heise online", "ki", "https://www.heise.de/rss/heise-top-atom.xml"),
        src("golem", "Golem.de", "ki", "https://rss.golem.de/rss.php?feed=RSS2.0"),
        src("t3n", "t3n", "ki", "https://t3n.de/rss.xml"),
        src("the-decoder", "The Decoder", "ki", "https://the-decoder.de/feed/"),
        src("netzpolitik", "netzpolitik.org", "ki", "https://netzpolitik.org/feed/"),
        src("spiegel-netzwelt", "SPIEGEL Netzwelt", "ki", "https://www.spiegel.de/netzwelt/index.rss"),
        src("computerbase", "ComputerBase", "ki", "https://www.computerbase.de/rss/news.xml"),
        src("zeit-digital", "ZEIT Digital", "ki", "https://newsfeed.zeit.de/digital/index"),
        src("sz-digital", "SZ Digital", "ki", "https://rss.sueddeutsche.de/rss/Digital"),

        // 📈 Wirtschaft & Märkte
        src("tagesschau-wirtschaft", "tagesschau", "wirtschaft", "https://www.tagesschau.de/wirtschaft/index~rss2.xml"),
        src("tagesschau-finanzen", "tagesschau Finanzen", "wirtschaft", "https://www.tagesschau.de/wirtschaft/finanzen/index~rss2.xml"),
        src("handelsblatt", "Handelsblatt", "wirtschaft", "https://www.handelsblatt.com/contentexport/feed/schlagzeilen"),
        src("spiegel-wirtschaft", "SPIEGEL Wirtschaft", "wirtschaft", "https://www.spiegel.de/wirtschaft/index.rss"),
        src("zeit-wirtschaft", "ZEIT Wirtschaft", "wirtschaft", "https://newsfeed.zeit.de/wirtschaft/index"),
        src("faz-wirtschaft", "FAZ Wirtschaft", "wirtschaft", "https://www.faz.net/rss/aktuell/wirtschaft/"),
        src("sz-wirtschaft", "SZ Wirtschaft", "wirtschaft", "https://rss.sueddeutsche.de/rss/Wirtschaft"),
        src("wiwo", "WirtschaftsWoche", "wirtschaft", "https://www.wiwo.de/contentexport/feed/rss/schlagzeilen"),

        // 🏛️ Politik
        src("tagesschau-inland", "tagesschau", "politik", "https://www.tagesschau.de/inland/index~rss2.xml"),
        src("spiegel-politik", "SPIEGEL Politik", "politik", "https://www.spiegel.de/politik/index.rss"),
        src("zeit-politik", "ZEIT Politik", "politik", "https://newsfeed.zeit.de/politik/index"),
        src("faz-politik", "FAZ Politik", "politik", "https://www.faz.net/rss/aktuell/politik/"),
        src("sz-politik", "SZ Politik", "politik", "https://rss.sueddeutsche.de/rss/Politik"),
        src("dlf-politik", "Deutschlandfunk", "politik", "https://www.deutschlandfunk.de/politikportal-100.rss"),
        src("welt-politik", "WELT Politik", "politik", "https://www.welt.de/feeds/section/politik.rss"),
        src("taz-politik", "taz Politik", "politik", "https://taz.de/!p4615;rss/"),

        // 🌍 Welt
        src("tagesschau-ausland", "tagesschau", "welt", "https://www.tagesschau.de/ausland/index~rss2.xml"),
        src("spiegel-ausland", "SPIEGEL Ausland", "welt", "https://www.spiegel.de/ausland/index.rss"),
        src("dw", "Deutsche Welle", "welt", "https://rss.dw.com/rdf/rss-de-all"),
        src("dlf-nachrichten", "Deutschlandfunk", "welt", "https://www.deutschlandfunk.de/nachrichten-100.rss"),
        src("zdfheute", "ZDFheute", "welt", "https://www.zdf.de/rss/zdf/nachrichten"),

        // ⚽ Sport
        src("kicker", "kicker", "sport", "https://newsfeed.kicker.de/news/aktuell"),
        src("sportschau", "Sportschau", "sport", "https://www.sportschau.de/index~rss2.xml"),
        src("spiegel-sport", "SPIEGEL Sport", "sport", "https://www.spiegel.de/sport/index.rss"),
        src("zeit-sport", "ZEIT Sport", "sport", "https://newsfeed.zeit.de/sport/index"),
        src("sz-sport", "SZ Sport", "sport", "https://rss.sueddeutsche.de/rss/Sport"),

        // 🔭 Wissenschaft & Raumfahrt
        src("spektrum", "Spektrum.de", "wissenschaft", "https://www.spektrum.de/alias/rss/spektrum-de-rss-feed/996406"),
        src("scinexx", "scinexx", "wissenschaft", "https://www.scinexx.de/feed/"),
        src("tagesschau-wissen", "tagesschau Wissen", "wissenschaft", "https://www.tagesschau.de/wissen/index~rss2.xml"),
        src("spiegel-wissenschaft", "SPIEGEL Wissenschaft", "wissenschaft", "https://www.spiegel.de/wissenschaft/index.rss"),
        src("wissenschaft-de", "wissenschaft.de", "wissenschaft", "https://www.wissenschaft.de/feed/"),
        src("esa", "ESA", "wissenschaft", "https://www.esa.int/rssfeed/Germany"),
        src("raumfahrer", "Raumfahrer.net", "wissenschaft", "https://www.raumfahrer.net/feed/"),
        src("zdf-wissen", "ZDF Wissen", "wissenschaft", "https://www.zdf.de/rss/zdf/nachrichten/wissen"),
        src("quarks", "Quarks", "wissenschaft", "https://www.quarks.de/feed/"),

        // 🩺 Gesundheit
        src("spiegel-gesundheit", "SPIEGEL Gesundheit", "gesundheit", "https://www.spiegel.de/gesundheit/index.rss"),
        src("zeit-gesundheit", "ZEIT Gesundheit", "gesundheit", "https://newsfeed.zeit.de/gesundheit/index"),
        src("sz-gesundheit", "SZ Gesundheit", "gesundheit", "https://rss.sueddeutsche.de/rss/Gesundheit"),
        src("aerzteblatt", "Ärzteblatt", "gesundheit", "https://www.aerzteblatt.de/rss/news.asp"),

        // 🌱 Klima & Umwelt
        src("tagesschau-klima", "tagesschau Klima", "klima", "https://www.tagesschau.de/wissen/klima/index~rss2.xml"),
        src("dlf-umwelt", "Deutschlandfunk Umwelt", "klima", "https://www.deutschlandfunk.de/umwelt-und-verbraucher-100.rss"),
        src("spiegel-natur", "SPIEGEL Natur", "klima", "https://www.spiegel.de/wissenschaft/natur/index.rss"),
        src("taz-oeko", "taz Öko", "klima", "https://taz.de/!p4610;rss/"),
        src("klimareporter", "klimareporter°", "klima", "https://www.klimareporter.de/?format=feed&type=rss"),
        src("energiezukunft", "energiezukunft", "klima", "https://www.energiezukunft.eu/rss"),
        src("utopia", "Utopia", "klima", "https://www.utopia.de/feed/"),
        src("riffreporter", "RiffReporter", "klima", "https://www.riffreporter.de/de/rss"),

        // 🎮 Games & Entertainment
        src("gamestar", "GameStar", "entertainment", "https://www.gamestar.de/rss/gamestar.rss"),
        src("gamepro", "GamePro", "entertainment", "https://www.gamepro.de/rss/gamepro.rss"),
        src("golem-games", "Golem.de Games", "entertainment", "https://rss.golem.de/rss.php?feed=RSS2.0&tp=games"),
        src("spiegel-kultur", "SPIEGEL Kultur", "entertainment", "https://www.spiegel.de/kultur/index.rss"),
        src("tagesschau-kultur", "tagesschau Kultur", "entertainment", "https://www.tagesschau.de/kultur/index~rss2.xml"),
        src("sz-kultur", "SZ Kultur", "entertainment", "https://rss.sueddeutsche.de/rss/Kultur"),
        src("moviepilot", "Moviepilot", "entertainment", "https://www.moviepilot.de/rss/moviepilot-standard"),
        src("serienjunkies", "Serienjunkies", "entertainment", "https://www.serienjunkies.de/rss/news.xml"),
    )

    private val builtInById = builtIn.associateBy { it.id }

    /** Every source the app knows for [settings]: built-in, the region's, and the user's own (switched-off ones included). */
    fun allSources(settings: AppSettings): List<FeedSource> =
        builtIn + Regions.byKey(settings.region).feeds + settings.customFeeds

    /** The sources of [topicId] that are switched on, in catalogue order (custom ones last). */
    fun sourcesFor(topicId: String, settings: AppSettings): List<FeedSource> =
        allSources(settings).filter { it.topicId == topicId && it.id !in settings.disabledFeeds }

    /** All sources of [topicId], switched-off ones included (for the settings). */
    fun allSourcesFor(topicId: String, settings: AppSettings): List<FeedSource> =
        allSources(settings).filter { it.topicId == topicId }

    fun byId(id: String, settings: AppSettings): FeedSource? =
        builtInById[id] ?: Regions.byKey(settings.region).feeds.firstOrNull { it.id == id } ?: settings.customFeeds.firstOrNull { it.id == id }

    /** The id of a new custom feed. */
    fun newCustomId(): String = "custom:" + java.util.UUID.randomUUID().toString().take(8)
}

/** A regional preset behind the "Region" topic: one or two public newsrooms of a Bundesland. */
data class Region(val key: String, val name: String, val feeds: List<FeedSource>)

object Regions {
    const val DEFAULT = "berlin-brandenburg"

    private fun src(id: String, name: String, url: String) = FeedSource(id, name, url, Topics.REGION_ID)

    val ALL: List<Region> = listOf(
        Region("baden-wuerttemberg", "Baden-Württemberg", listOf(src("swr-bw", "SWR Aktuell", "https://www.swr.de/~rss/swraktuell/baden-wuerttemberg/index.xml"))),
        Region("bayern", "Bayern", listOf(src("br24", "BR24", "https://feeds.br24.de/rss/nachrichten"))),
        Region(
            "berlin-brandenburg", "Berlin & Brandenburg",
            listOf(
                src("rbb24", "rbb24", "https://www.rbb24.de/aktuell/index.xml/feed=rss.xml"),
                src("tagesspiegel-berlin", "Tagesspiegel", "https://www.tagesspiegel.de/contentexport/feed/berlin"),
            ),
        ),
        Region("bremen", "Bremen", listOf(src("butenunbinnen", "buten un binnen", "https://www.butenunbinnen.de/feed/rss/neuste-inhalte100.xml"))),
        Region("hamburg", "Hamburg", listOf(src("ndr-hamburg", "NDR Hamburg", "https://www.ndr.de/nachrichten/hamburg/index-rss.xml"))),
        Region("hessen", "Hessen", listOf(src("hessenschau", "hessenschau", "https://www.hessenschau.de/index.rss"))),
        Region("mecklenburg-vorpommern", "Mecklenburg-Vorpommern", listOf(src("ndr-mv", "NDR Mecklenburg-Vorpommern", "https://www.ndr.de/nachrichten/mecklenburg-vorpommern/index-rss.xml"))),
        Region("niedersachsen", "Niedersachsen", listOf(src("ndr-nds", "NDR Niedersachsen", "https://www.ndr.de/nachrichten/niedersachsen/index-rss.xml"))),
        Region("nordrhein-westfalen", "Nordrhein-Westfalen", listOf(src("wdr", "WDR", "https://www1.wdr.de/nachrichten/uebersicht-nachrichten-100.feed"))),
        Region("rheinland-pfalz", "Rheinland-Pfalz", listOf(src("swr-rlp", "SWR Aktuell", "https://www.swr.de/~rss/swraktuell/rheinland-pfalz/index.xml"))),
        // SR (Saarländischer Rundfunk) publishes no feed (checked 29.10.2026); the neighbouring SWR feed
        // stands in and is named for what it is.
        Region("saarland", "Saarland", listOf(src("swr-saar", "SWR Aktuell (Rheinland-Pfalz)", "https://www.swr.de/~rss/swraktuell/rheinland-pfalz/index.xml"))),
        Region(
            "sachsen", "Sachsen",
            listOf(
                src("mdr-sachsen", "MDR Sachsen", "https://www.mdr.de/nachrichten/sachsen/index-rss.xml"),
                src("lvz", "LVZ", "https://www.lvz.de/rss/feed/lvz_leipzig"),
                src("l-iz", "L-IZ", "https://www.l-iz.de/feed"),
            ),
        ),
        Region("sachsen-anhalt", "Sachsen-Anhalt", listOf(src("mdr-sachsen-anhalt", "MDR Sachsen-Anhalt", "https://www.mdr.de/nachrichten/sachsen-anhalt/index-rss.xml"))),
        Region("schleswig-holstein", "Schleswig-Holstein", listOf(src("ndr-sh", "NDR Schleswig-Holstein", "https://www.ndr.de/nachrichten/schleswig-holstein/index-rss.xml"))),
        Region("thueringen", "Thüringen", listOf(src("mdr-thueringen", "MDR Thüringen", "https://www.mdr.de/nachrichten/thueringen/index-rss.xml"))),
    )

    private val byKey = ALL.associateBy { it.key }

    /** The preset for [key]; unknown keys fall back to [DEFAULT]. */
    fun byKey(key: String?): Region = byKey[key] ?: byKey.getValue(DEFAULT)

    /**
     * The preset a place belongs to, judged by the Bundesland in its region label
     * ("Sachsen, Deutschland"); null when it names none (abroad, or unknown).
     */
    fun guess(place: Place): Region? {
        val label = place.region.lowercase()
        if (label.isEmpty()) return null
        // Longest alias first: "Sachsen-Anhalt" must not match "Sachsen".
        return ALL.flatMap { region -> region.aliases.map { alias -> alias to region } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> label.contains(alias) }
            ?.second
    }

    private val Region.aliases: List<String>
        get() = when (key) {
            "berlin-brandenburg" -> listOf("berlin", "brandenburg")
            "baden-wuerttemberg" -> listOf("baden-württemberg", "baden-wuerttemberg")
            "nordrhein-westfalen" -> listOf("nordrhein-westfalen", "north rhine")
            "rheinland-pfalz" -> listOf("rheinland-pfalz")
            "mecklenburg-vorpommern" -> listOf("mecklenburg")
            "sachsen-anhalt" -> listOf("sachsen-anhalt", "saxony-anhalt")
            "sachsen" -> listOf("sachsen", "saxony")
            "thueringen" -> listOf("thüringen", "thuringia")
            else -> listOf(name.lowercase())
        }
}
