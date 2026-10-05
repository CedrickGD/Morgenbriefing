package com.cedrickgd.morgenbriefing.ui.today

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NewsBlockTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun brief(date: String) = Brief(
        date = date,
        generatedAt = "${date}T05:07:12+02:00",
        teaser = "Teaser $date",
        sections = listOf(
            NewsSection(
                id = "ki",
                title = "KI & Tech",
                emoji = "🤖",
                items = listOf(NewsItem("Headline", "Summary", "Quelle", "https://example.org")),
            ),
        ),
    )

    @Test
    fun `nothing loaded yet shows the loader`() {
        assertEquals(NewsBlock.Loading, newsBlockFor(null, today))
    }

    @Test
    fun `demo brief comes with the token notice`() {
        val sample = brief("2026-10-05")
        val block = newsBlockFor(BriefLoad.Demo(sample), today)
        assertTrue(block is NewsBlock.Demo)
        block as NewsBlock.Demo
        assertSame(sample, block.brief)
        assertEquals("Demo-Briefing", block.notice.title)
        assertEquals(NoticeAction.OPEN_SETTINGS, block.notice.action)
    }

    @Test
    fun `loaded brief is stale unless it is today's`() {
        val fresh = newsBlockFor(BriefLoad.Loaded(brief("2026-10-06"), fromCache = false), today)
        assertEquals(NewsBlock.Ready(brief("2026-10-06"), stale = false), fresh)

        val yesterday = newsBlockFor(BriefLoad.Loaded(brief("2026-10-05"), fromCache = true), today)
        assertEquals(NewsBlock.Ready(brief("2026-10-05"), stale = true), yesterday)

        val malformed = newsBlockFor(BriefLoad.Loaded(brief("6.10.2026"), fromCache = false), today)
        assertTrue((malformed as NewsBlock.Ready).stale)
    }

    @Test
    fun `failures keep the cached brief and explain what happened`() {
        val cached = brief("2026-10-05")
        val block = newsBlockFor(BriefLoad.Failed(BriefErrorKind.NOT_FOUND, cached), today)
        block as NewsBlock.Problem
        assertSame(cached, block.brief)
        assertTrue(block.stale)
        assertEquals("Heute noch kein Briefing veröffentlicht", block.notice.title)
        assertEquals(NoticeAction.RETRY, block.notice.action)
    }

    @Test
    fun `failure without cache has no brief`() {
        val block = newsBlockFor(BriefLoad.Failed(BriefErrorKind.NETWORK, null), today) as NewsBlock.Problem
        assertNull(block.brief)
        assertFalse(block.stale)
        assertEquals("Offline – noch keine News gespeichert", block.notice.title)
    }

    @Test
    fun `failure copy per kind`() {
        assertEquals("Token ungültig oder abgelaufen", failureNotice(BriefErrorKind.UNAUTHORIZED, true).title)
        assertEquals(NoticeAction.OPEN_SETTINGS, failureNotice(BriefErrorKind.UNAUTHORIZED, false).action)
        assertEquals(
            "Offline – du siehst die zuletzt gespeicherten News",
            failureNotice(BriefErrorKind.NETWORK, hasCache = true).title,
        )
        assertEquals(failureNotice(BriefErrorKind.PARSE, false).title, failureNotice(BriefErrorKind.UNKNOWN, false).title)
        assertEquals(DemoNotice, failureNotice(BriefErrorKind.NO_TOKEN, false))
        // Every kind has an action so the user is never stuck.
        BriefErrorKind.entries.forEach { kind ->
            assertTrue(kind.name, failureNotice(kind, false).action != null)
        }
    }

    @Test
    fun `no-token failure without cache behaves like demo notice`() {
        val block = newsBlockFor(BriefLoad.Failed(BriefErrorKind.NO_TOKEN, null), today) as NewsBlock.Problem
        assertEquals(DemoNotice, block.notice)
    }

    @Test
    fun `merge keeps what is on screen when a refresh fails without cache`() {
        val shown = brief("2026-10-06")
        val failed = BriefLoad.Failed(BriefErrorKind.NETWORK, cached = null)

        val merged = mergeBriefLoad(BriefLoad.Loaded(shown, fromCache = true), failed)
        assertEquals(BriefLoad.Failed(BriefErrorKind.NETWORK, shown), merged)

        val fromFailed = mergeBriefLoad(BriefLoad.Failed(BriefErrorKind.NOT_FOUND, shown), failed)
        assertEquals(BriefLoad.Failed(BriefErrorKind.NETWORK, shown), fromFailed)
    }

    @Test
    fun `merge never passes the demo brief off as cached news`() {
        val failed = BriefLoad.Failed(BriefErrorKind.UNAUTHORIZED, cached = null)
        assertEquals(failed, mergeBriefLoad(BriefLoad.Demo(brief("2026-10-05")), failed))
        assertEquals(failed, mergeBriefLoad(null, failed))
    }

    @Test
    fun `merge prefers new results`() {
        val next = BriefLoad.Loaded(brief("2026-10-06"), fromCache = false)
        assertSame(next, mergeBriefLoad(BriefLoad.Loaded(brief("2026-10-05"), true), next))
        val failedWithCache = BriefLoad.Failed(BriefErrorKind.NETWORK, brief("2026-10-04"))
        assertSame(failedWithCache, mergeBriefLoad(BriefLoad.Loaded(brief("2026-10-05"), true), failedWithCache))
    }
}
