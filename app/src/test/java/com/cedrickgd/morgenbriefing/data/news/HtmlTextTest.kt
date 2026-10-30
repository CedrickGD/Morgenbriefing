package com.cedrickgd.morgenbriefing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlTextTest {

    @Test
    fun `strips tags, decodes entities and collapses whitespace`() {
        val html = "<p> Die <b>Telekom</b> setzt auf&nbsp;KI &amp; Automatisierung.&#8230; </p>\n\n<p>Zweiter   Absatz.</p>"
        assertEquals("Die Telekom setzt auf KI & Automatisierung.…\nZweiter Absatz.", HtmlText.toPlain(html))
    }

    @Test
    fun `decodes named german entities and numeric ones`() {
        assertEquals("Ärger über „Zölle“ – 5 € ≠ 6", HtmlText.decodeEntities("&Auml;rger &uuml;ber &bdquo;Z&ouml;lle&ldquo; &ndash; 5 &euro; &#8800; 6"))
        assertEquals("a<b", HtmlText.decodeEntities("a&lt;b"))
        assertEquals("&unknown;", HtmlText.decodeEntities("&unknown;"))
    }

    @Test
    fun `drops the trailing mehr link golem and tagesschau append`() {
        assertEquals("Ein Teaser.", HtmlText.toPlain("Ein Teaser.[<a href=\"https://x\">mehr</a>]"))
        assertEquals("Ein Teaser.", HtmlText.toPlain("Ein Teaser.\n<a href='https://t3n.de/x'>weiterlesen auf t3n.de</a> "))
    }

    @Test
    fun `handles doubly escaped html like golem`() {
        val text = "Lenovo Legion: Curved Monitor (&lt;a href=&quot;https://www.golem.de/x&quot;&gt;Technik/Hardware&lt;/a&gt;) &lt;img src=&quot;https://cpx.golem.de/cpx.php?class=17&quot; width=&quot;1&quot; /&gt;"
        assertEquals("Lenovo Legion: Curved Monitor (Technik/Hardware)", HtmlText.toPlain(text))
    }

    @Test
    fun `finds the first real picture and skips tracking pixels`() {
        val html = "<img src=\"https://cpx.golem.de/cpx.php?class=17\" width=\"1\" height=\"1\" /><a href=\"x\"><img src=\"https://images.tagesschau.de/a.jpg?width=1920\" alt=\"Demo\" /></a>"
        assertEquals("https://images.tagesschau.de/a.jpg?width=1920", HtmlText.firstImageSrc(html))
        assertNull(HtmlText.firstImageSrc("<p>kein Bild</p>"))
        assertNull(HtmlText.firstImageSrc("<img src='/relativ.jpg'>"))
        assertNull(HtmlText.firstImageSrc(null))
    }

    @Test
    fun `blank input is empty`() {
        assertEquals("", HtmlText.toPlain(null))
        assertEquals("", HtmlText.toPlain("   "))
        assertEquals("", HtmlText.toPlain("<p></p>"))
    }

    @Test
    fun `keeps a last word that only looks like a read-more link`() {
        assertEquals("Bahnstreik geht weiter", HtmlText.toPlain("Bahnstreik geht weiter"))
        assertEquals("Verstappen wird Zweiter", HtmlText.toPlain("Verstappen wird Zweiter"))
        assertEquals("Neue Features: Kamera, Akku und mehr.", HtmlText.toPlain("<p>Neue Features: Kamera, Akku und mehr.</p>"))
        assertEquals("Die Bahn fährt nicht mehr", HtmlText.toPlain("Die Bahn fährt nicht mehr"))
    }

    @Test
    fun `drops the plain read-more forms and leaves headlines alone`() {
        assertEquals("Ein Teaser.", HtmlText.toPlain("Ein Teaser. [mehr]"))
        assertEquals("Ein Teaser.", HtmlText.toPlain("Ein Teaser. weiterlesen auf t3n.de"))
        assertEquals("Ein Teaser.", HtmlText.toPlain("<p>Ein Teaser.</p><p><a href=\"x\">weiterlesen</a></p>"))
        assertEquals("Es geht weiter", HtmlText.toPlain("Es geht <a href='x'>weiter</a>", dropReadMore = false))
    }

    @Test
    fun `a picture named after the pixel phone is not a tracking pixel`() {
        assertEquals(
            "https://heise.cloudimg.io/v7/x/pixel-10-test.jpg",
            HtmlText.firstImageSrc("<img src=\"https://heise.cloudimg.io/v7/x/pixel-10-test.jpg\">"),
        )
        assertNull(HtmlText.firstImageSrc("<img src=\"https://ssl-vg03.met.vgwort.de/na/0123456789abcdef\" width=\"1\" height=\"1\">"))
        assertNull(HtmlText.firstImageSrc("<img src=\"https://x.de/img/pixel.gif?id=1\">"))
    }
}
