package com.cedrickgd.morgenbriefing.ui.reader

import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.ui.news.TopicPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReaderModelsTest {

    private val now = ZonedDateTime.of(2026, 10, 6, 9, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun item(id: String, publishedAt: String? = null, body: String = "", sources: List<SourceRef> = emptyList()) = NewsItem(
        id = id,
        headline = "Headline $id",
        summary = "Summary $id",
        body = body,
        source = "Quelle $id",
        url = "https://example.org/$id",
        sources = sources,
        publishedAt = publishedAt,
    )

    private fun brief(vararg sections: NewsSection) =
        Brief(schema = 2, date = "2026-10-06", generatedAt = "2026-10-06T05:00:00+02:00", sections = sections.toList())

    private fun section(id: String, vararg items: NewsItem) = NewsSection(id = id, title = id, items = items.toList())

    private fun live(id: String, topic: String) = LiveArticle(item(id), topic, "2026-10-06T08:30:00+02:00")

    // --- live articles --------------------------------------------------------------------

    @Test
    fun `live articles follow the brief, in their own order`() {
        val entries = readerEntries(
            brief = brief(section("ki", item("k1")), section("politik", item("p1"))),
            live = listOf(live("l2", "welt"), live("l1", "ki")),
            prefs = TopicPrefs(),
            focusKey = "l1",
        )
        assertEquals(listOf("k1", "p1", "l2", "l1"), entries.map { it.item.id })
        assertEquals(listOf(false, false, true, true), entries.map { it.live })
        assertEquals("Welt", entries[2].topic.title)
    }

    @Test
    fun `a live article that is already in the brief appears once`() {
        val entries = readerEntries(brief(section("ki", item("k1"))), listOf(live("k1", "ki")), TopicPrefs(), focusKey = null)
        assertEquals(listOf("k1"), entries.map { it.item.id })
        assertFalse(entries.single().live)
    }

    @Test
    fun `live articles of hidden topics are left out unless opened`() {
        val prefs = TopicPrefs(hidden = setOf("sport"))
        val lives = listOf(live("s1", "sport"), live("s2", "sport"))
        assertEquals(listOf("s2"), readerEntries(null, lives, prefs, focusKey = "s2").map { it.item.id })
        assertTrue(readerEntries(null, lives, prefs, focusKey = null).isEmpty())
    }

    @Test
    fun `live only day and unknown topics still work`() {
        val entries = readerEntries(null, listOf(live("x1", "neu")), TopicPrefs(), focusKey = "x1")
        assertEquals("Live", entries.single().topic.title)
        assertEquals("⚡", entries.single().topic.emoji)
    }

    // --- text size ------------------------------------------------------------------------

    @Test
    fun `text size steps by 0_1 within 0_85 to 1_4`() {
        assertEquals(1.1f, stepTextScale(1.0f, larger = true), 0.0001f)
        assertEquals(0.9f, stepTextScale(1.0f, larger = false), 0.0001f)
        assertEquals(0.85f, stepTextScale(0.9f, larger = false), 0.0001f)
        assertEquals(0.85f, stepTextScale(0.85f, larger = false), 0.0001f)
        assertEquals(0.9f, stepTextScale(0.85f, larger = true), 0.0001f)
        assertEquals(1.4f, stepTextScale(1.3f, larger = true), 0.0001f)
        assertEquals(1.4f, stepTextScale(1.4f, larger = true), 0.0001f)
        assertEquals(1.2f, stepTextScale(1.15f, larger = true), 0.0001f)
        assertEquals(1.1f, stepTextScale(1.15f, larger = false), 0.0001f)
        assertEquals("110 %", textScaleLabel(1.1f))
    }

    @Test
    fun `stepping up from the smallest size reaches the largest`() {
        var scale = MIN_TEXT_SCALE
        val seen = mutableListOf(scale)
        repeat(10) {
            scale = stepTextScale(scale, larger = true)
            seen += scale
        }
        assertEquals(listOf(0.85f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f), seen.distinct())
    }

    // --- meta & footnote ------------------------------------------------------------------

    @Test
    fun `meta line names source, reading time and age`() {
        val body = List(400) { "wort" }.joinToString(" ")
        val entry = ReaderEntry(item("a", publishedAt = "2026-10-06T06:00:00+02:00", body = body), topic = liveTopic("ki"))
        assertEquals("Quelle a · 2 Min. Lesezeit · vor 3 Std.", readerMetaLine(entry, now))
        assertEquals("Quelle b", readerMetaLine(ReaderEntry(item("b"), liveTopic("ki"), live = true), now))
    }

    @Test
    fun `footnote counts reports`() {
        assertEquals("In eigenen Worten zusammengefasst aus diesem Bericht.", sourcesFootnote(1))
        assertEquals("In eigenen Worten zusammengefasst aus diesen 3 Berichten.", sourcesFootnote(3))
    }

    // --- reading progress -----------------------------------------------------------------

    @Test
    fun `reading progress grows with scrolling and ends at 1`() {
        assertEquals(0f, readingProgress(0, 0, totalItems = 20, averageItemSize = 100f, viewport = 800, canScrollForward = true), 0.0001f)
        assertEquals(0.5f, readingProgress(6, 0, totalItems = 20, averageItemSize = 100f, viewport = 800, canScrollForward = true), 0.0001f)
        assertEquals(1f, readingProgress(12, 50, totalItems = 20, averageItemSize = 100f, viewport = 800, canScrollForward = false), 0.0001f)
        assertEquals(0f, readingProgress(0, 0, totalItems = 0, averageItemSize = 0f, viewport = 800, canScrollForward = true), 0.0001f)
        assertEquals(1f, readingProgress(0, 0, totalItems = 2, averageItemSize = 100f, viewport = 800, canScrollForward = true), 0.0001f)
    }
}
