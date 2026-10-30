package com.cedrickgd.morgenbriefing.ui.today

import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceStatus
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.news.FeedArticle
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.SectionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsNoticesTest {

    private val topic = Topic("ki", "KI", "🤖", 0xFF000000)
    private val item = NewsItem("1", "H", url = "https://x/1", source = "S", sourceId = "s", topicId = "ki")

    private fun feed(items: List<NewsItem>, failed: List<String> = emptyList(), sections: Int = 1): NewsFeed {
        val section = SectionUi(0, topic, items, items.take(4), fetchedAt = "2026-10-29T13:00:00+01:00", failedSources = failed)
        return NewsFeed(sections = List(sections) { section }, latest = items.map { FeedArticle(it, topic) }, fetchedAt = null)
    }

    @Test
    fun `nothing while loading or before the cache was read`() {
        assertNull(newsNotice(null, offline = false, loading = false, warmed = true, attempted = true))
        assertNull(newsNotice(feed(emptyList()), offline = false, loading = true, warmed = true, attempted = true))
        assertNull(newsNotice(feed(emptyList()), offline = false, loading = false, warmed = false, attempted = false))
        assertNull(newsNotice(feed(listOf(item)), offline = false, loading = false, warmed = true, attempted = true))
    }

    @Test
    fun `offline with and without cache`() {
        assertEquals(NoticeAction.RETRY, newsNotice(feed(listOf(item)), offline = true, loading = false, warmed = true, attempted = true)?.action)
        assertEquals("Offline – noch keine News gespeichert", newsNotice(feed(emptyList()), offline = true, loading = false, warmed = true, attempted = true)?.title)
    }

    @Test
    fun `all topics hidden and all sources off`() {
        assertEquals(NoticeAction.OPEN_TOPICS, newsNotice(feed(emptyList(), sections = 0), offline = false, loading = false, warmed = true, attempted = true)?.action)
        assertEquals(NoticeAction.OPEN_SOURCES, newsNotice(feed(emptyList()), offline = false, loading = false, warmed = true, attempted = true)?.action)
        assertEquals(NoticeAction.RETRY, newsNotice(feed(emptyList(), failed = listOf("A")), offline = false, loading = false, warmed = true, attempted = true)?.action)
    }

    @Test
    fun `several failed sources are mentioned, one is not`() {
        assertNull(newsNotice(feed(listOf(item), failed = listOf("A")), offline = false, loading = false, warmed = true, attempted = true))
        val notice = newsNotice(feed(listOf(item), failed = listOf("A", "B", "C")), offline = false, loading = false, warmed = true, attempted = true)
        assertEquals("3 Quellen antworten gerade nicht", notice?.title)
        assertEquals("A, B, C", notice?.text)
    }

    @Test
    fun `footer line`() {
        assertEquals("Wetter: Open-Meteo", footerLine(null, hasWeather = true, weatherOffline = false))
        assertEquals("", footerLine(null, hasWeather = false, weatherOffline = false))
        val f = feed(listOf(item)).copy(fetchedAt = java.time.Instant.parse("2026-10-29T06:12:00Z"))
        assertEquals("Stand 07:12 · 1 Meldung · Wetter: Open-Meteo (offline)", footerLine(f, hasWeather = true, weatherOffline = true))
    }
}
