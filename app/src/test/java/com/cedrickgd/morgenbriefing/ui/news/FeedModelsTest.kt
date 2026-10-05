package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FeedModelsTest {

    private val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun item(id: String, publishedAt: String? = null, url: String = "https://example.org/$id") = NewsItem(
        id = id,
        headline = "Headline $id",
        summary = "Summary $id",
        source = "Quelle",
        url = url,
        publishedAt = publishedAt,
    )

    private fun section(id: String, vararg items: NewsItem) =
        NewsSection(id = id, title = id.uppercase(), emoji = "", items = items.toList())

    private fun brief(vararg sections: NewsSection) =
        Brief(schema = 2, date = "2026-10-06", generatedAt = "2026-10-06T05:00:00+02:00", sections = sections.toList())

    // --- chips -----------------------------------------------------------------------------

    @Test
    fun `chips start with for you, top and live, then the visible topics in user order`() {
        val feed = buildNewsFeed(
            brief(section("ki", item("a")), section("politik", item("b")), section("sport", item("c"))),
            TopicPrefs(order = listOf("politik", "ki", "sport"), hidden = setOf("sport")),
        )
        val chips = feedChips(feed)
        assertEquals(listOf("foryou", "top", "live", "topic:politik", "topic:ki"), chips.map { it.filter.key })
        assertEquals("Politik", chips[3].label)
        assertEquals("🏛️", chips[3].emoji)
    }

    @Test
    fun `filters survive the round trip through their key`() {
        listOf(FeedFilter.ForYou, FeedFilter.Top, FeedFilter.Live, FeedFilter.OneTopic("ki")).forEach {
            assertEquals(it, FeedFilter.fromKey(it.key))
        }
        assertEquals(FeedFilter.ForYou, FeedFilter.fromKey(null))
        assertEquals(FeedFilter.ForYou, FeedFilter.fromKey("topic:"))
        assertEquals(FeedFilter.ForYou, FeedFilter.fromKey("quatsch"))
    }

    @Test
    fun `a hidden topic chip falls back to for you`() {
        val chips = feedChips(buildNewsFeed(brief(section("ki", item("a"))), TopicPrefs()))
        assertEquals(FeedFilter.ForYou, resolveFilter(FeedFilter.OneTopic("sport"), chips))
        assertEquals(FeedFilter.OneTopic("ki"), resolveFilter(FeedFilter.OneTopic("ki"), chips))
        assertEquals(FeedFilter.Live, resolveFilter(FeedFilter.Live, chips))
    }

    // --- live --------------------------------------------------------------------------------

    @Test
    fun `live shows only the last six hours, newest first, brief and live mixed`() {
        val b = brief(
            section(
                "ki",
                item("fresh", publishedAt = "2026-10-06T09:30:00+02:00"),
                item("old", publishedAt = "2026-10-06T05:30:00+02:00"),
                item("unknown"),
            ),
        )
        val live = listOf(
            LiveArticle(item("l1", publishedAt = "2026-10-06T11:40:00+02:00"), "politik", "2026-10-06T11:50:00+02:00"),
            LiveArticle(item("l2"), "ki", "2026-10-06T10:00:00+02:00"),
            LiveArticle(item("l3", publishedAt = "2026-10-05T20:00:00+02:00"), "ki", "2026-10-06T11:00:00+02:00"),
        )
        val feed = liveFeed(b, TopicPrefs(), live, now, "2026-10-06")
        assertEquals(listOf("l1", "l2", "fresh"), feed.map { it.item.id })
        assertTrue(feed[0].live)
        assertEquals("politik", feed[0].topic.id)
        assertEquals("2026-10-06", feed[2].date)
    }

    @Test
    fun `live skips hidden topics and duplicates`() {
        val b = brief(section("ki", item("same", publishedAt = "2026-10-06T10:00:00+02:00", url = "https://x.org/1")))
        val live = listOf(
            LiveArticle(item("other-id", publishedAt = "2026-10-06T11:00:00+02:00", url = "https://x.org/1"), "ki", "2026-10-06T11:05:00+02:00"),
            LiveArticle(item("hidden", publishedAt = "2026-10-06T11:30:00+02:00"), "sport", "2026-10-06T11:35:00+02:00"),
        )
        val feed = liveFeed(b, TopicPrefs(hidden = setOf("sport")), live, now, "2026-10-06")
        assertEquals(listOf("other-id"), feed.map { it.item.id })
    }

    @Test
    fun `live badge names when it was published`() {
        val article = FeedArticle(item("l", publishedAt = "2026-10-06T11:48:00+02:00"), Topics.ALL.first(), "2026-10-06", live = true)
        assertEquals("⚡ neu · vor 12 Min.", liveBadge(article, now))
        val noTime = article.copy(item = item("l"), fetchedAt = null)
        assertEquals("⚡ neu", liveBadge(noTime, now))
    }

    @Test
    fun `unknown live topics get a neutral topic`() {
        val topic = liveTopic("kultur")
        assertEquals("kultur", topic.id)
        assertEquals("Kultur", topic.title)
        assertSame(Topics.byId("ki"), liveTopic("ki"))
    }

    // --- topic visibility ----------------------------------------------------------------------

    @Test
    fun `hiding and showing a topic`() {
        val prefs = TopicPrefs(order = listOf("ki", "politik"))
        assertEquals(setOf("ki"), prefs.withTopicVisible("ki", visible = false)?.hidden)
        assertEquals(emptySet<String>(), TopicPrefs(hidden = setOf("ki")).withTopicVisible("ki", visible = true)?.hidden)
    }

    @Test
    fun `the last visible topic cannot be hidden`() {
        val prefs = TopicPrefs(order = listOf("ki", "politik"), hidden = setOf("politik"))
        assertNull(prefs.withTopicVisible("ki", visible = false))
    }

    @Test
    fun `catalogue order follows the user and appends missing topics`() {
        val order = TopicPrefs(order = listOf("sport", "ki", "unbekannt")).catalogueOrder().map { it.id }
        assertEquals(listOf("sport", "ki"), order.take(2))
        assertEquals(Topics.ALL.size, order.size)
    }

    @Test
    fun `article count label`() {
        assertEquals("1 Meldung", articleCountLabel(1))
        assertEquals("4 Meldungen", articleCountLabel(4))
    }
}
