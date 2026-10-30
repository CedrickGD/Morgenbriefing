package com.cedrickgd.morgenbriefing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.IOException

class RssParserTest {

    private val parser = RssParser { KXmlParser() }

    private fun parse(xml: String): ParsedFeed = parser.parse(xml.byteInputStream(Charsets.UTF_8))

    @Test
    fun `reads an rss 2 feed like tagesschau with content encoded picture`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>tagesschau.de - Die Nachrichten der ARD</title>
                <link>https://www.tagesschau.de</link>
                <item>
                  <title>Wer am Samstag auf die Straße geht</title>
                  <link>https://www.tagesschau.de/inland/proteste-100.html</link>
                  <description>Am Samstag soll es zu Protesten kommen.</description>
                  <pubDate>Thu, 29 Oct 2026 13:02:19 +0100</pubDate>
                  <guid>cf91b9af</guid>
                  <dc:date>2026-10-29T12:02:19Z</dc:date>
                  <content:encoded><![CDATA[<p> <a href="https://www.tagesschau.de/x"><img src="https://images.tagesschau.de/image/abc/16x9-big/demo.jpg?width=1920" alt="Schüler protestieren | dpa" /></a> <br/>Am Samstag soll es zu Protesten kommen.[<a href="https://www.tagesschau.de/x">mehr</a>]</p>]]></content:encoded>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("tagesschau.de - Die Nachrichten der ARD", feed.title)
        assertEquals("https://www.tagesschau.de", feed.link)
        val entry = feed.entries.single()
        assertEquals("Wer am Samstag auf die Straße geht", entry.title)
        assertEquals("https://www.tagesschau.de/inland/proteste-100.html", entry.link)
        assertEquals("Am Samstag soll es zu Protesten kommen.", entry.summary)
        assertEquals("2026-10-29T13:02:19+01:00", entry.publishedAt)
        assertEquals("https://images.tagesschau.de/image/abc/16x9-big/demo.jpg?width=1920", entry.imageUrl)
        assertEquals("Schüler protestieren | dpa", entry.imageAlt)
    }

    @Test
    fun `reads an atom feed like heise with html titles and link href`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>heise online News</title>
              <link rel="self" type="application/atom+xml" href="https://www.heise.de/rss/heise-top-atom.xml"/>
              <link rel="alternate" type="text/html" href="https://www.heise.de/"/>
              <entry>
                <title type="html"><![CDATA[Verbraucherzentrale mahnt Disney+ ab]]></title>
                <id>http://heise.de/-11504225</id>
                <updated>2026-10-29T14:22:00+01:00</updated>
                <published>2026-10-29T14:22:00+01:00</published>
                <link href="https://www.heise.de/news/Disney-11504225.html?wt_mc=rss"/>
                <summary type="html"><![CDATA[HDR und 4K funktionieren bestenfalls eingeschränkt.]]></summary>
                <content type="html"><![CDATA[<p><a href="https://www.heise.de/x"><img src="https://www.heise.de/scale/geometry/450/q80//imgs/18/5/demo.jpeg" class="webfeedsFeaturedVisual" alt="Ein Smartphone" /></a></p><p>HDR und 4K funktionieren bestenfalls eingeschränkt.</p>]]></content>
              </entry>
            </feed>
            """.trimIndent(),
        )
        assertEquals("heise online News", feed.title)
        assertEquals("https://www.heise.de/", feed.link)
        val entry = feed.entries.single()
        assertEquals("Verbraucherzentrale mahnt Disney+ ab", entry.title)
        assertEquals("https://www.heise.de/news/Disney-11504225.html?wt_mc=rss", entry.link)
        assertEquals("HDR und 4K funktionieren bestenfalls eingeschränkt.", entry.summary)
        assertEquals("2026-10-29T14:22:00+01:00", entry.publishedAt)
        assertEquals("https://www.heise.de/scale/geometry/450/q80//imgs/18/5/demo.jpeg", entry.imageUrl)
    }

    @Test
    fun `prefers media content over enclosure over embedded img and picks the largest`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel><title>FAZ</title>
                <item>
                  <title>Rechtsextremes Vorfeld</title>
                  <link>https://www.faz.net/aktuell/politik/afd-201632259.html</link>
                  <description>Ist die IB eine Gefahr?</description>
                  <enclosure type="image/jpeg" url="https://media0.faz.net/enclosure.jpg" length="0"/>
                  <media:thumbnail url="https://media0.faz.net/thumb.webp" width="400" height="225"/>
                  <media:content url="https://media0.faz.net/big.webp" type="image/webp" medium="image" width="1200" height="675"/>
                  <pubDate>Thu, 29 Oct 2026 13:30:35 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("https://media0.faz.net/enclosure.jpg", feed.entries.single().imageUrl)
    }

    @Test
    fun `media content wrapping a thumbnail like gamestar`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel><title>GameStar</title>
                <item>
                  <title><![CDATA[News: Pico11]]></title>
                  <link><![CDATA[https://www.gamestar.de/artikel/pico11,3462051.html]]></link>
                  <description><![CDATA[<a href="https://www.gamestar.de/x"><img src="https://images.cgames.de/images/gamestar/112/win.jpg"/></a>Windows 11 auf Diät.]]></description>
                  <pubDate>Thu, 29 Oct 2026 13:18:00 GMT</pubDate>
                  <media:content medium="image" url="https://images.cgames.de/images/gamestar/4/win.jpg">
                    <media:thumbnail url="https://images.cgames.de/images/gamestar/50/win.jpg"/>
                  </media:content>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        val entry = feed.entries.single()
        assertEquals("News: Pico11", entry.title)
        assertEquals("Windows 11 auf Diät.", entry.summary)
        assertEquals("https://images.cgames.de/images/gamestar/4/win.jpg", entry.imageUrl)
    }

    @Test
    fun `reads rdf feeds like dw with dc date and dwsyn image`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dwsyn="http://rss.dw.com/syndication/dwsyn/">
              <channel rdf:about="https://www.dw.com"><title>Deutsche Welle</title><link>https://www.dw.com/de</link></channel>
              <item rdf:about="https://www.dw.com/de/a-79795480">
                <title>EZB-Chefin Lagarde tritt vorzeitig ab</title>
                <link>https://www.dw.com/de/lagarde/a-79795480?maca=de-rss</link>
                <description>Schon länger wurde gemunkelt.</description>
                <dc:date>2026-10-29T12:36:00Z</dc:date>
                <dwsyn:image rdf:about="https://static.dw.com/image/78656142_302.jpg">
                  <dwsyn:imageURL>https://static.dw.com/image/78656142_302.jpg</dwsyn:imageURL>
                </dwsyn:image>
              </item>
            </rdf:RDF>
            """.trimIndent(),
        )
        assertEquals("Deutsche Welle", feed.title)
        val entry = feed.entries.single()
        assertEquals("https://www.dw.com/de/lagarde/a-79795480?maca=de-rss", entry.link)
        assertEquals("2026-10-29T13:36:00+01:00", entry.publishedAt)
        assertEquals("https://static.dw.com/image/78656142_302.jpg", entry.imageUrl)
    }

    @Test
    fun `sz puts the picture and the text inside description and the abstract separately`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:dcterms="http://purl.org/dc/terms/"><channel><title><![CDATA[Politik - SZ.de]]></title>
              <item><title><![CDATA[Krieg: 1200 Drohnen]]></title>
                <description><![CDATA[<img src="https://www.sueddeutsche.de/2026/10/29/a.jpg?rect=1&width=1000" data-portal-copyright="Foto: X" /><p>Moskau wird attackiert.</p>]]></description>
                <link>https://www.sueddeutsche.de/politik/ukraine-li.3563283</link>
                <guid isPermaLink="false">li.3563283</guid>
                <pubDate>Thu, 29 Oct 2026 14:03:15 GMT</pubDate>
                <dcterms:abstract>Moskau wird attackiert.</dcterms:abstract>
              </item></channel></rss>
            """.trimIndent(),
        )
        val entry = feed.entries.single()
        assertEquals("Moskau wird attackiert.", entry.summary)
        assertEquals("https://www.sueddeutsche.de/2026/10/29/a.jpg?rect=1&width=1000", entry.imageUrl)
        assertEquals("Politik - SZ.de", feed.title)
    }

    @Test
    fun `a permalink guid stands in for a missing link`() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>x</title>
              <item><title>Ohne Link</title><guid isPermaLink="true">https://example.org/a</guid></item>
              <item><title>Ohne alles</title><guid isPermaLink="false">abc</guid></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals("https://example.org/a", feed.entries[0].link)
        assertEquals("", feed.entries[1].link)
        assertNull(feed.entries[1].publishedAt)
    }

    @Test
    fun `html pages are not feeds`() {
        try {
            parse("<!DOCTYPE html><html><head><title>Seite</title></head><body></body></html>")
            throw AssertionError("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("kein RSS"))
        }
    }

    @Test
    fun `iso-8859-1 feeds like golem are read with their declared encoding`() {
        val xml = """<?xml version="1.0" encoding="ISO-8859-1"?><rss version="2.0"><channel><title>Golem</title><item><title>Günstig wie nie</title><link>https://www.golem.de/a</link></item></channel></rss>"""
        val feed = parser.parse(xml.toByteArray(Charsets.ISO_8859_1).inputStream())
        assertEquals("Günstig wie nie", feed.entries.single().title)
    }
}
