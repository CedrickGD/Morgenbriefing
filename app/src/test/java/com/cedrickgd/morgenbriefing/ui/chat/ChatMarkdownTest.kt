package com.cedrickgd.morgenbriefing.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {

    private fun plain(spans: List<MdSpan>) = spans.joinToString("") { it.text }

    @Test
    fun `paragraphs, bullets and numbered lists`() {
        val blocks = parseChatMarkdown(
            """
            Die **EZB** hat den Leitzins gesenkt.
            Das kam erwartet.

            - erster Punkt
            - zweiter **Punkt**
              mit Fortsetzung

            1. eins
            2) zwei
            """.trimIndent(),
        )

        assertEquals(3, blocks.size)
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("Die EZB hat den Leitzins gesenkt. Das kam erwartet.", plain(paragraph.spans))
        assertEquals(MdSpan("EZB", bold = true), paragraph.spans[1])

        val bullets = blocks[1] as MdBlock.Bullets
        assertEquals(listOf("erster Punkt", "zweiter Punkt mit Fortsetzung"), bullets.items.map(::plain))
        assertTrue(bullets.items[1].any { it.bold && it.text == "Punkt" })

        val numbered = blocks[2] as MdBlock.Numbered
        assertEquals(listOf("1", "2"), numbered.items.map { it.first })
        assertEquals("zwei", plain(numbered.items[1].second))
    }

    @Test
    fun `headings, links and code are reduced to text`() {
        val blocks = parseChatMarkdown("## Hintergrund\n\nLaut [tagesschau.de](https://www.tagesschau.de/x) stieg der `DAX`.")
        assertEquals("Hintergrund", plain((blocks[0] as MdBlock.Heading).spans))
        assertEquals("Laut tagesschau.de stieg der DAX.", plain((blocks[1] as MdBlock.Paragraph).spans))
    }

    @Test
    fun `italic needs a word boundary`() {
        val spans = parseInline("Das ist *wichtig* und 5 * 3 bleibt Text.")
        assertEquals(MdSpan("wichtig", italic = true), spans[1])
        assertEquals("Das ist wichtig und 5 * 3 bleibt Text.", plain(spans))
    }

    @Test
    fun `an unclosed marker while streaming keeps its style to the end`() {
        val spans = parseInline("Die Antwort ist **wich")
        assertEquals(listOf(MdSpan("Die Antwort ist "), MdSpan("wich", bold = true)), spans)
    }

    @Test
    fun `empty and blank text`() {
        assertTrue(parseChatMarkdown("").isEmpty())
        assertTrue(parseChatMarkdown("\n\n  \n").isEmpty())
        assertTrue(parseChatMarkdown("---").isEmpty())
    }

    @Test
    fun `plain text for copy and share`() {
        val text = "Kurz gesagt: **ja**.\n\n- eins\n- zwei\n\n1. a\n2. b"
        assertEquals("Kurz gesagt: ja.\n\n• eins\n• zwei\n\n1. a\n2. b", chatPlainText(text))
    }
}
