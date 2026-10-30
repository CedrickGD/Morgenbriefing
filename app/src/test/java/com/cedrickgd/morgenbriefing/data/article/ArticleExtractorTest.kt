package com.cedrickgd.morgenbriefing.data.article

import com.cedrickgd.morgenbriefing.data.model.ArticleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ArticleExtractorTest {

    private val now: ZonedDateTime = ZonedDateTime.parse("2026-10-30T18:00:00+01:00")

    private fun paragraph(n: Int) =
        "<p>Absatz $n erzählt in aller Ruhe, was an diesem Tag in der Stadt passiert ist, wer dabei war und warum das " +
            "für die Menschen vor Ort eine Rolle spielt. Die Redaktion hat mit mehreren Beteiligten gesprochen und " +
            "ihre Einschätzungen hier zusammengetragen, damit sich ein vollständiges Bild ergibt.</p>"

    private val page = """
        <html><head>
          <title>Testartikel – Beispielzeitung</title>
          <meta property="og:title" content="Testartikel">
          <meta property="og:image" content="/bilder/og.jpg">
          <meta property="og:site_name" content="Beispielzeitung">
        </head><body>
          <nav><a href="/">Startseite</a><a href="/politik">Politik</a><a href="/sport">Sport</a></nav>
          <div class="ad">Anzeige</div>
          <article>
            <h1>Testartikel</h1>
            <figure><img data-src="/bilder/foto.jpg" alt="Ein Foto"><figcaption>Bildunterschrift zum Foto</figcaption></figure>
            ${paragraph(1)}
            ${paragraph(2)}
            <p>Anzeige</p>
            <h2>Zwischentitel</h2>
            ${paragraph(3)}
            <blockquote>Das ist ein Zitat aus dem Gespräch.</blockquote>
            <ul><li>Erster Punkt der Liste</li><li>Zweiter Punkt der Liste</li></ul>
            ${paragraph(4)}
            <p><img src="https://cdn.example.org/pixel.gif" width="1" height="1"></p>
            ${paragraph(5)}
            ${paragraph(6)}
          </article>
          <aside>Mehr zum Thema: andere Artikel</aside>
          <footer>Alle Rechte vorbehalten</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `finds the article, its pictures and structure, drops chrome and ads`() {
        val content = ArticleExtractor.extract(page, "https://www.example.org/news/test", now)
        assertNotNull(content)
        content!!
        assertEquals("Testartikel", content.title)
        assertEquals("Beispielzeitung", content.site)
        assertEquals("https://www.example.org/bilder/og.jpg", content.image)
        assertEquals("2026-10-30T18:00:00+01:00", content.fetchedAt)

        val types = content.blocks.map { it.type }
        assertTrue(types.contains(ArticleBlock.HEADING))
        assertTrue(types.contains(ArticleBlock.QUOTE))
        assertTrue(types.contains(ArticleBlock.LIST))
        val image = content.blocks.first { it.type == ArticleBlock.IMAGE }
        assertEquals("https://www.example.org/bilder/foto.jpg", image.url)
        assertEquals("Ein Foto", image.alt)
        assertTrue(content.blocks.any { it.type == ArticleBlock.CAPTION && it.text == "Bildunterschrift zum Foto" })
        assertEquals(listOf("Erster Punkt der Liste", "Zweiter Punkt der Liste"), content.blocks.first { it.type == ArticleBlock.LIST }.items)

        val texts = content.blocks.map { it.text }
        assertTrue(texts.none { it.contains("Startseite") })
        assertTrue(texts.none { it == "Anzeige" })
        assertTrue(texts.none { it == "Testartikel" })
        assertFalse(content.blocks.any { it.url.contains("pixel.gif") })
        assertTrue(content.wordCount > 120)
        assertFalse(content.looksTruncated)
        assertTrue(content.text.startsWith("Absatz 1"))
    }

    @Test
    fun `a page without an article is null`() {
        assertNull(ArticleExtractor.extract("<html><body><nav>Menü</nav></body></html>", "https://www.example.org/", now))
        assertNull(ArticleExtractor.extract("", "https://www.example.org/", now))
    }

    @Test
    fun `a consent page is recognised, a real article is not`() {
        val wall = "<html><body><article><h1>Cookies zustimmen</h1><p>Besuchen Sie Golem.de wie gewohnt mit Werbung und Tracking, " +
            "indem Sie der Nutzung aller Cookies zustimmen. Details zum Tracking finden Sie im Privacy Center und in der Datenschutzerklärung.</p>" +
            "<p>Oder lesen Sie Golem.de ohne Werbung und Tracking: mit dem Pur-Abo für wenige Euro im Monat, jederzeit kündbar.</p>" +
            "<p>Sie haben bereits ein Abo? Dann melden Sie sich bitte an, damit wir Ihre Einstellungen übernehmen können.</p></article></body></html>"
        val content = ArticleExtractor.extract(wall, "https://www.golem.de/news/x", now)
        assertNotNull(content)
        assertTrue(ArticleExtractor.looksLikeConsentWall(content!!))
        val real = ArticleExtractor.extract(page, "https://www.example.org/news/test", now)!!
        assertFalse(ArticleExtractor.looksLikeConsentWall(real))
    }

    @Test
    fun `http pictures are upgraded and data uris dropped`() {
        val html = "<html><body><article><h1>T</h1>" + paragraph(1) + paragraph(2) + paragraph(3) +
            "<p><img src=\"http://www.example.org/a.jpg\" alt=\"A\"></p>" +
            "<p><img src=\"data:image/png;base64,AAAA\"></p>" + paragraph(4) + paragraph(5) + "</article></body></html>"
        val content = ArticleExtractor.extract(html, "https://www.example.org/x", now)!!
        val images = content.blocks.filter { it.type == ArticleBlock.IMAGE }
        assertEquals(listOf("https://www.example.org/a.jpg"), images.map { it.url })
    }
}
