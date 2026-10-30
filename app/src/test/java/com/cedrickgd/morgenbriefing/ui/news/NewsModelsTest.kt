package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.NewsAge
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceStatus
import com.cedrickgd.morgenbriefing.data.model.TopicNews
import com.cedrickgd.morgenbriefing.data.model.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class NewsModelsTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 29, 13, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun item(id: String, topic: String, hoursAgo: Long?, source: String = "S") = NewsItem(
        id = id, headline = "H$id", url = "https://x/$id", source = source, sourceId = source.lowercase(), topicId = topic,
        publishedAt = hoursAgo?.let { now.minusHours(it).toOffsetDateTime().toString() },
    )

    private fun news(topic: String, vararg items: NewsItem) =
        TopicNews(topic, now.toOffsetDateTime().toString(), items.toList(), listOf(SourceStatus("s", "S", ok = true, count = items.size)))

    @Test
    fun `age filter keeps the window but never fewer than the minimum`() {
        val items = listOf(item("1", "ki", 1), item("2", "ki", 30), item("3", "ki", 40), item("4", "ki", 50))
        assertEquals(listOf("1", "2", "3"), filterByAge(items, NewsAge.DAY, now.toInstant()).map { it.id })
        assertEquals(4, filterByAge(items, NewsAge.ALL, now.toInstant()).size)
        assertEquals(4, filterByAge(items, NewsAge.WEEK, now.toInstant()).size)
        val fresh = listOf(item("1", "ki", 1), item("2", "ki", 2), item("3", "ki", 3), item("4", "ki", 30))
        assertEquals(listOf("1", "2", "3"), filterByAge(fresh, NewsAge.DAY, now.toInstant()).map { it.id })
        assertTrue(filterByAge(listOf(item("u", "ki", null)), NewsAge.DAY, now.toInstant()).isNotEmpty())
    }

    @Test
    fun `feed follows the user's topic order, sizes the sections and merges the latest`() {
        val settings = AppSettings(topicOrder = listOf("sport", "ki"), hiddenTopics = setOf("politik"), sectionSize = 2)
        val prefs = FeedPrefs(settings)
        val repo = mapOf(
            "ki" to news("ki", item("k1", "ki", 1), item("k2", "ki", 5), item("k3", "ki", 9)),
            "sport" to news("sport", item("s1", "sport", 3)),
            "politik" to news("politik", item("p1", "politik", 0)),
        )
        val feed = buildNewsFeed(repo, prefs, now)
        assertEquals(listOf("sport", "ki"), feed.sections.take(2).map { it.id })
        assertTrue(feed.sections.none { it.id == "politik" })
        val ki = feed.sectionFor("ki")!!
        assertEquals(2, ki.top.size)
        assertEquals(3, ki.count)
        assertTrue(ki.hasMore)
        assertEquals(listOf("k1", "s1", "k2", "k3"), feed.latest.map { it.item.id })
        assertEquals(4, feed.articleCount)
        assertTrue(feed.sections.any { it.isEmpty })
        assertEquals(Instant.from(now), feed.fetchedAt)
        assertEquals("13:00", fetchedLabel(feed.fetchedAt))
    }

    @Test
    fun `chips list for you, latest and every visible topic`() {
        val feed = buildNewsFeed(emptyMap(), FeedPrefs(AppSettings()), now)
        val chips = feedChips(feed)
        assertEquals(FeedFilter.ForYou, chips[0].filter)
        assertEquals(FeedFilter.Latest, chips[1].filter)
        assertEquals(Topics.DEFAULT_ORDER, chips.drop(2).map { (it.filter as FeedFilter.OneTopic).topicId })
        assertEquals(FeedFilter.ForYou, resolveFilter(FeedFilter.OneTopic("weg"), chips))
        assertEquals(FeedFilter.OneTopic("ki"), FeedFilter.fromKey("topic:ki"))
        assertEquals(FeedFilter.Latest, FeedFilter.fromKey("latest"))
        assertEquals(FeedFilter.ForYou, FeedFilter.fromKey(null))
    }

    @Test
    fun `endless lists reveal page by page`() {
        assertEquals(20, revealedCount(1, 100))
        assertEquals(40, revealedCount(2, 100))
        assertEquals(7, revealedCount(3, 7))
        assertEquals(20, revealedCount(0, 100))
    }

    @Test
    fun `meta parts and labels`() {
        val parts = feedMetaParts(item("1", "ki", 3, source = "tagesschau"), now)
        assertEquals("vor 3 Std.", parts.time)
        assertEquals("tagesschau", parts.source)
        assertEquals("vor 3 Std. · tagesschau", feedMeta(item("1", "ki", 3, source = "tagesschau"), now))
        assertNull(feedMetaParts(item("1", "ki", null, source = ""), now).time)
        assertEquals("x", feedMetaParts(item("1", "ki", null, source = ""), now).source)
        assertEquals("1 Meldung", articleCountLabel(1))
        assertEquals("Alle 37 Meldungen zu Sport", moreButtonLabel("Sport", 37))
        assertEquals("tagesschau.de", hostOf("https://www.tagesschau.de/inland/x.html"))
        assertFalse(fetchedLabel(null).isNotEmpty())
    }

    @Test
    fun `a switched-off source disappears from the feed at once`() {
        val prefs = FeedPrefs(AppSettings(disabledFeeds = setOf("b")))
        val repo = mapOf("ki" to news("ki", item("a1", "ki", 1, source = "A"), item("b1", "ki", 2, source = "B")))
        val feed = buildNewsFeed(repo, prefs, now)
        assertEquals(listOf("a1"), feed.sectionFor("ki")!!.all.map { it.id })
        assertEquals(listOf("a1"), feed.latest.map { it.item.id })
    }
}
