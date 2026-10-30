package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.remote.RssClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RssClientLogicTest {

    @Test
    fun `ids ignore scheme, www, tracking query and fragment`() {
        val a = RssClient.stableId("https://www.spiegel.de/politik/a-b72c#ref=rss")
        val b = RssClient.stableId("http://spiegel.de/politik/a-b72c/?utm_source=rss&utm_medium=feed")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertNotEquals(a, RssClient.stableId("https://www.spiegel.de/politik/a-b72d"))
    }

    @Test
    fun `meaningful query parameters stay in the id`() {
        assertNotEquals(
            RssClient.normalizeLinkForId("https://example.org/article?id=1"),
            RssClient.normalizeLinkForId("https://example.org/article?id=2"),
        )
        assertEquals("example.org/article?id=1", RssClient.normalizeLinkForId("https://example.org/article/?id=1&utm_campaign=x"))
    }

    @Test
    fun `typed addresses get https`() {
        assertEquals("https://tagesschau.de/", RssClient.normalizeUrl("tagesschau.de"))
        assertEquals("http://localhost:8080/feed", RssClient.normalizeUrl("http://localhost:8080/feed"))
        assertNull(RssClient.normalizeUrl(""))
        assertNull(RssClient.normalizeUrl("kein url mit leerzeichen"))
    }

    @Test
    fun `feed links in a page head are found and resolved`() {
        val html = """
            <html><head>
            <link rel="stylesheet" href="/x.css">
            <link href="/?format=feed&amp;type=rss" rel="alternate" type="application/rss+xml" title="klimareporter°">
            <link rel="alternate" type="application/atom+xml" title="Neuste" href="https://www.butenunbinnen.de/feed/rss/neuste-inhalte100.xml">
            </head><body><link rel="alternate" type="application/rss+xml" href="/body-feed"></body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://www.klimareporter.de/?format=feed&type=rss", "https://www.butenunbinnen.de/feed/rss/neuste-inhalte100.xml"),
            RssClient.discoverFeedLinks(html, "https://www.klimareporter.de/"),
        )
    }
}
