package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.ui.news.BodyBlock.Bullets
import com.cedrickgd.morgenbriefing.ui.news.BodyBlock.Paragraph
import com.cedrickgd.morgenbriefing.ui.news.BodyBlock.Subheading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleBodyTest {

    @Test
    fun `blank lines separate paragraphs and single breaks are soft wraps`() {
        val body = "Erster Absatz,\nder weitergeht.\n\nZweiter Absatz."
        assertEquals(
            listOf(Paragraph("Erster Absatz, der weitergeht."), Paragraph("Zweiter Absatz.")),
            parseArticleBody(body),
        )
    }

    @Test
    fun `subheadings and bullet lists become their own blocks`() {
        val body = """
            Einleitung.

            ## Was sich ändert

            - Erstens
            - Zweitens
            * Drittens

            Schluss.
        """.trimIndent()
        assertEquals(
            listOf(
                Paragraph("Einleitung."),
                Subheading("Was sich ändert"),
                Bullets(listOf("Erstens", "Zweitens", "Drittens")),
                Paragraph("Schluss."),
            ),
            parseArticleBody(body),
        )
    }

    @Test
    fun `blocks without blank lines between them are still split`() {
        val body = "Absatz\n## Zwischentitel\n- Punkt\nNeuer Absatz"
        assertEquals(
            listOf(
                Paragraph("Absatz"),
                Subheading("Zwischentitel"),
                Bullets(listOf("Punkt")),
                Paragraph("Neuer Absatz"),
            ),
            parseArticleBody(body),
        )
    }

    @Test
    fun `an indented line continues the bullet above`() {
        val body = "- Ein langer Punkt,\n  der umbricht\n- Kurz"
        assertEquals(listOf(Bullets(listOf("Ein langer Punkt, der umbricht", "Kurz"))), parseArticleBody(body))
    }

    @Test
    fun `windows and old mac line endings work`() {
        assertEquals(
            listOf(Paragraph("A"), Paragraph("B"), Paragraph("C")),
            parseArticleBody("A\r\n\r\nB\r\rC"),
        )
    }

    @Test
    fun `markup and links are reduced to plain text`() {
        val body = "Laut **Bericht** [der Agentur](https://example.org/a) ist <b>alles</b> &amp; mehr ok."
        assertEquals(listOf(Paragraph("Laut Bericht der Agentur ist alles & mehr ok.")), parseArticleBody(body))
    }

    @Test
    fun `other heading levels count as subheadings, a bare hash does not`() {
        assertEquals(listOf(Subheading("Titel")), parseArticleBody("### Titel ##"))
        assertEquals(listOf(Paragraph("#hashtag bleibt Text")), parseArticleBody("#hashtag bleibt Text"))
    }

    @Test
    fun `horizontal rules separate blocks and are not shown`() {
        assertEquals(listOf(Paragraph("A"), Paragraph("B")), parseArticleBody("A\n---\nB\n\n* * *"))
    }

    @Test
    fun `a dash without space is no bullet`() {
        assertEquals(listOf(Paragraph("-5 Grad am Morgen")), parseArticleBody("-5 Grad am Morgen"))
    }

    @Test
    fun `empty and whitespace-only bodies have no blocks`() {
        assertTrue(parseArticleBody("").isEmpty())
        assertTrue(parseArticleBody(" \n\n \t \n").isEmpty())
        assertTrue(parseArticleBody("## \n- ").isEmpty())
    }

    @Test
    fun `plain text keeps literal angle brackets that are no tags`() {
        assertEquals("a < b und c > d", plainText("a < b und c > d"))
        assertEquals("&lt;", plainText("&amp;lt;"))
    }
}
