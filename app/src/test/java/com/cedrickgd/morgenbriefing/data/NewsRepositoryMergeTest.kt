package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.TopicNews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NewsRepositoryMergeTest {

    private val now: Instant = Instant.parse("2026-10-29T12:00:00Z")
    private val a = FeedSource("a", "A", "https://a.de/feed", "ki")
    private val b = FeedSource("b", "B", "https://b.de/feed", "ki")

    private fun item(id: String, source: FeedSource, at: String?, headline: String = "H $id") =
        NewsItem(id = id, headline = headline, url = "https://x/$id", source = source.name, sourceId = source.id, topicId = "ki", publishedAt = at)

    private fun ok(source: FeedSource, vararg items: NewsItem) = NewsRepository.FetchResult(source, items.toList(), null)
    private fun failed(source: FeedSource) = NewsRepository.FetchResult(source, emptyList(), "Nicht erreichbar")

    @Test
    fun `newest first, undated last, statuses per source`() {
        val merged = NewsRepository.merge(
            "ki", null,
            listOf(
                ok(a, item("1", a, "2026-10-29T10:00:00+01:00"), item("2", a, null)),
                ok(b, item("3", b, "2026-10-29T12:30:00+01:00")),
            ),
            now,
        )
        assertEquals(listOf("3", "1", "2"), merged.items.map { it.id })
        assertEquals(listOf(true, true), merged.sources.map { it.ok })
        assertEquals("2026-10-29T13:00:00+01:00", merged.fetchedAt)
    }

    @Test
    fun `a failed source keeps its recent articles from last time`() {
        val previous = TopicNews("ki", "2026-10-29T08:00:00+01:00", listOf(item("old", b, "2026-10-29T07:00:00+01:00"), item("ancient", b, "2026-10-20T07:00:00+01:00"), item("fromA", a, "2026-10-29T07:30:00+01:00")))
        val merged = NewsRepository.merge("ki", previous, listOf(ok(a, item("new", a, "2026-10-29T11:00:00+01:00")), failed(b)), now)
        assertEquals(listOf("new", "old"), merged.items.map { it.id })
        val status = merged.sources.single { it.sourceId == "b" }
        assertFalse(status.ok)
        assertEquals("Nicht erreichbar", status.error)
        assertEquals(1, status.count)
        assertTrue(merged.failedSources.isNotEmpty())
    }

    @Test
    fun `duplicates by id or by headline within a source are dropped`() {
        val items = listOf(
            item("1", a, null, "Gleiche Schlagzeile"),
            item("1", a, null, "Andere"),
            item("2", a, null, "gleiche schlagzeile "),
            item("3", b, null, "Gleiche Schlagzeile"),
        )
        assertEquals(listOf("1", "3"), NewsRepository.dedupe(items).map { it.id })
    }

    @Test
    fun `at most max per topic survive`() {
        val many = (1..500).map { item("$it", a, "2026-10-29T10:00:00+01:00") }
        val merged = NewsRepository.merge("ki", null, listOf(ok(a, *many.toTypedArray())), now)
        assertEquals(NewsRepository.MAX_PER_TOPIC, merged.items.size)
    }

    @Test
    fun `a round in which every source failed keeps the previous fetch time`() {
        val previous = TopicNews("ki", "2026-10-29T10:00:00+01:00", listOf(item("1", a, "2026-10-29T09:00:00+01:00")))
        val merged = NewsRepository.merge("ki", previous, listOf(failed(a), failed(b)), now)
        assertEquals("2026-10-29T10:00:00+01:00", merged.fetchedAt)
        assertTrue(merged.allFailed)
        assertEquals(listOf("1"), merged.items.map { it.id })
        // Without anything to keep, the attempt is still stamped so the UI can say when it was.
        assertEquals("2026-10-29T13:00:00+01:00", NewsRepository.merge("ki", null, listOf(failed(a)), now).fetchedAt)
    }
}
