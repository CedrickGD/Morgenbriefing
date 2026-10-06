package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class ChatBriefTest {
    private val sample = Brief(
        schema = 2,
        date = "2026-10-05",
        generatedAt = "2026-10-05T05:00:00+02:00",
        sections = listOf(
            NewsSection(
                id = "ki",
                title = "KI",
                emoji = "",
                items = listOf(
                    NewsItem(
                        id = "ki-trump-super-intelligence-force",
                        headline = "Trump gründet „Super Intelligence Force“ für KI-Fragen",
                        summary = "Demo",
                        source = "Quelle",
                        url = "https://example.org/a",
                    ),
                ),
            ),
        ),
    )
    private val day = LocalDate.of(2026, 10, 5)

    @Test
    fun `the demo brief of that day is what the chat talks about`() {
        assertSame(sample, demoBriefForChat(sample, day, "ki-trump-super-intelligence-force"))
        // "Frag dein Briefing": the whole demo brief.
        assertSame(sample, demoBriefForChat(sample, day, null))
    }

    @Test
    fun `other days and unknown articles fall through to the stored briefs`() {
        assertNull(demoBriefForChat(sample, day.plusDays(1), null))
        assertNull(demoBriefForChat(sample, day, "nicht-im-demo"))
    }
}
