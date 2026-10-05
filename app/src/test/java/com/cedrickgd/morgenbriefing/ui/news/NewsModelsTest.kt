package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NewsModelsTest {

    private val now = ZonedDateTime.of(2026, 10, 6, 7, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun item(
        id: String,
        importance: Int = 5,
        tier: String = NewsItem.TIER_TOP,
        source: String = "Quelle",
        url: String = "https://example.org/$id",
        sources: List<SourceRef> = emptyList(),
        body: String = "",
        readingMinutes: Int = 0,
        publishedAt: String? = null,
    ) = NewsItem(
        id = id,
        headline = "Headline $id",
        summary = "Summary $id",
        body = body,
        source = source,
        url = url,
        sources = sources,
        publishedAt = publishedAt,
        importance = importance,
        tier = tier,
        readingMinutes = readingMinutes,
    )

    private fun section(id: String, vararg items: NewsItem) =
        NewsSection(id = id, title = id.uppercase(), emoji = "", items = items.toList())

    private fun brief(schema: Int, vararg sections: NewsSection) =
        Brief(schema = schema, date = "2026-10-06", generatedAt = "2026-10-06T05:00:00+02:00", sections = sections.toList())

    // --- tiers -----------------------------------------------------------------------------

    @Test
    fun `schema 2 splits top and more in brief order`() {
        val items = listOf(item("a"), item("b", tier = "more"), item("c"), item("d", tier = "more"))
        val (top, more) = splitTiers(items, schema = 2)
        assertEquals(listOf("a", "c"), top.map { it.id })
        assertEquals(listOf("b", "d"), more.map { it.id })
    }

    @Test
    fun `schema 1 shows everything as top, whatever the tier says`() {
        val items = listOf(item("a"), item("b", tier = "more"))
        val (top, more) = splitTiers(items, schema = 1)
        assertEquals(listOf("a", "b"), top.map { it.id })
        assertTrue(more.isEmpty())
    }

    @Test
    fun `a section with only more items promotes the first one`() {
        val (top, more) = splitTiers(listOf(item("a", tier = "more"), item("b", tier = "more")), schema = 2)
        assertEquals(listOf("a"), top.map { it.id })
        assertEquals(listOf("b"), more.map { it.id })
    }

    @Test
    fun `unknown tiers count as top`() {
        val (top, more) = splitTiers(listOf(item("a", tier = "featured")), schema = 2)
        assertEquals(listOf("a"), top.map { it.id })
        assertTrue(more.isEmpty())
    }

    // --- top stories -----------------------------------------------------------------------

    @Test
    fun `top stories are the five most important, ties in brief order`() {
        val feed = buildNewsFeed(
            brief(
                2,
                section("ki", item("k1", 6), item("k2", 9), item("k3", 4, tier = "more")),
                section("politik", item("p1", 9), item("p2", 7), item("p3", 2)),
            ),
            TopicPrefs(order = listOf("ki", "politik")),
        )
        assertEquals(listOf("k2", "p1", "p2", "k1", "k3"), feed.topStories.map { it.item.id })
        assertEquals("ki", feed.topStories.first().topic.id)
    }

    @Test
    fun `top stories follow the user's topic order and skip hidden topics`() {
        val b = brief(
            2,
            section("ki", item("k1", 8), item("k2", 3), item("k3", 3)),
            section("politik", item("p1", 8), item("p2", 3), item("p3", 3)),
            section("sport", item("s1", 10)),
        )
        val feed = buildNewsFeed(b, TopicPrefs(order = listOf("politik", "ki", "sport"), hidden = setOf("sport")))
        assertEquals(listOf("politik", "ki"), feed.sections.map { it.id })
        assertEquals(listOf("p1", "k1", "p2", "p3", "k2"), feed.topStories.map { it.item.id })
    }

    @Test
    fun `no pager when the brief has no more stories than it would show`() {
        val feed = buildNewsFeed(
            brief(2, section("ki", item("a"), item("b"), item("c")), section("politik", item("d"), item("e"))),
            TopicPrefs(),
        )
        assertTrue(feed.topStories.isEmpty())
        assertEquals(2, feed.sections.size)
    }

    @Test
    fun `all hidden is reported separately from an empty brief`() {
        val b = brief(2, section("ki", item("a")))
        assertTrue(buildNewsFeed(b, TopicPrefs(hidden = setOf("ki"))).allHidden)
        assertFalse(buildNewsFeed(brief(2, section("ki")), TopicPrefs()).allHidden)
        assertTrue(buildNewsFeed(brief(2, section("ki")), TopicPrefs()).isEmpty)
    }

    // --- expand state ----------------------------------------------------------------------

    @Test
    fun `expanded ids open only sections that have more items`() {
        val feed = buildNewsFeed(
            brief(2, section("ki", item("a"), item("b", tier = "more")), section("politik", item("c"))),
            TopicPrefs(),
        )
        val (ki, politik) = feed.sections
        val expanded = setOf("ki", "politik", "unbekannt")
        assertTrue(ki.isExpanded(expanded))
        assertFalse(politik.isExpanded(expanded))
        assertFalse(ki.isExpanded(emptySet()))
    }

    @Test
    fun `toggling adds and removes a section id`() {
        val once = emptySet<String>().toggled("ki")
        assertEquals(setOf("ki"), once)
        assertEquals(setOf("ki", "politik"), once.toggled("politik"))
        assertEquals(emptySet<String>(), once.toggled("ki"))
    }

    @Test
    fun `button label reflects the expand state`() {
        assertEquals("+ Mehr zu KI & Tech (4)", moreButtonLabel("KI & Tech", 4, expanded = false))
        assertEquals("Weniger anzeigen", moreButtonLabel("KI & Tech", 4, expanded = true))
    }

    @Test
    fun `section count includes the more items`() {
        val ui = sectionUi(0, section("ki", item("a"), item("b", tier = "more"), item("c", tier = "more")), schema = 2)
        assertEquals(3, ui.count)
        assertEquals(1, ui.top.size)
        assertTrue(ui.hasMore)
    }

    // --- reader ----------------------------------------------------------------------------

    @Test
    fun `reader lists every article, top before more, in topic order`() {
        val b = brief(
            2,
            section("ki", item("k1"), item("k2", tier = "more"), item("k3")),
            section("politik", item("p1")),
        )
        val list = readerArticles(b, TopicPrefs(order = listOf("politik", "ki")), focusKey = "k1")
        assertEquals(listOf("p1", "k1", "k3", "k2"), list.map { it.item.id })
    }

    @Test
    fun `the opened article stays readable even if its topic is hidden`() {
        val b = brief(2, section("ki", item("k1")), section("politik", item("p1")))
        val prefs = TopicPrefs(hidden = setOf("ki", "politik"))
        assertEquals(listOf("k1"), readerArticles(b, prefs, focusKey = "k1").map { it.item.id })
        assertTrue(readerArticles(b, prefs, focusKey = null).isEmpty())
    }

    @Test
    fun `items without id are found by their url`() {
        val legacy = NewsItem(headline = "H", summary = "S", source = "Q", url = "https://example.org/x")
        val b = Brief(schema = 1, date = "2026-10-06", generatedAt = "", sections = listOf(NewsSection("ki", "KI", items = listOf(legacy))))
        assertTrue(b.containsArticle("https://example.org/x"))
        assertFalse(b.containsArticle("x"))
    }

    // --- meta ------------------------------------------------------------------------------

    @Test
    fun `sources are deduplicated with the primary first`() {
        val it = item(
            "a",
            source = "Handelsblatt",
            url = "https://example.org/hb",
            sources = listOf(
                SourceRef("tagesschau.de", "https://example.org/ts"),
                SourceRef("Handelsblatt (Print)", "https://EXAMPLE.org/hb"),
                SourceRef("", ""),
                SourceRef("tagesschau.de", "https://example.org/ts"),
            ),
        )
        assertEquals(
            listOf(SourceRef("Handelsblatt", "https://example.org/hb"), SourceRef("tagesschau.de", "https://example.org/ts")),
            sourcesOf(it),
        )
    }

    @Test
    fun `a primary without url takes the url of the same-named source`() {
        val it = item("a", source = "MDR", url = "", sources = listOf(SourceRef("mdr", "https://example.org/mdr")))
        assertEquals(listOf(SourceRef("MDR", "https://example.org/mdr")), sourcesOf(it))
    }

    @Test
    fun `card meta shows source, number of sources, minutes and age`() {
        val it = item(
            "a",
            source = "Handelsblatt",
            sources = listOf(SourceRef("A", "https://example.org/1"), SourceRef("B", "https://example.org/2")),
            readingMinutes = 4,
            publishedAt = "2026-10-06T04:00:00+02:00",
        )
        assertEquals("Handelsblatt · 3 Quellen · 4 Min. · vor 3 Std.", articleMeta(it, now))
        assertEquals("Quelle", articleMeta(item("b"), now))
    }

    @Test
    fun `reader meta names a single source`() {
        val it = item("a", source = "MDR", readingMinutes = 2, publishedAt = "2026-10-06T06:30:00+02:00")
        assertEquals("2 Min. Lesezeit · vor 30 Min. · MDR", readerMeta(it, now))
    }

    @Test
    fun `reading time is estimated from the body when missing`() {
        val words = List(450) { "wort" }.joinToString(" ")
        assertEquals(3, readingMinutesOf(item("a", body = words)))
        assertEquals(1, readingMinutesOf(item("b", body = "kurz")))
        assertEquals(0, readingMinutesOf(item("c")))
        assertEquals(7, readingMinutesOf(item("d", body = words, readingMinutes = 7)))
    }

    @Test
    fun `host drops scheme, www, port and path`() {
        assertEquals("tagesschau.de", hostOf("https://www.tagesschau.de/inland/x.html?a=1"))
        assertEquals("example.org", hostOf("http://user@example.org:8080/#top"))
        assertEquals("", hostOf("kein link"))
    }

    // --- text size -------------------------------------------------------------------------

    @Test
    fun `text size steps through the scale and stops at the ends`() {
        assertEquals(1.1f, nextTextScale(1.0f, larger = true), 0.0001f)
        assertEquals(0.9f, nextTextScale(1.0f, larger = false), 0.0001f)
        assertEquals(0.85f, nextTextScale(0.9f, larger = false), 0.0001f)
        assertEquals(0.85f, nextTextScale(0.85f, larger = false), 0.0001f)
        assertEquals(1.4f, nextTextScale(1.4f, larger = true), 0.0001f)
        // Values between the steps (older settings) snap to the next step.
        assertEquals(1.2f, nextTextScale(1.15f, larger = true), 0.0001f)
        assertEquals(1.1f, nextTextScale(1.15f, larger = false), 0.0001f)
    }
}
