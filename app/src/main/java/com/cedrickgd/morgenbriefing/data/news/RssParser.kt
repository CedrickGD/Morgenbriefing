package com.cedrickgd.morgenbriefing.data.news

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/** What a feed says about itself. */
data class ParsedFeed(
    val title: String,
    val link: String,
    val entries: List<ParsedEntry>,
)

/** One `<item>` / `<entry>` before it becomes a [com.cedrickgd.morgenbriefing.data.model.NewsItem]. */
data class ParsedEntry(
    val title: String,
    val link: String,
    /** Plain text teaser. */
    val summary: String,
    /** ISO-8601 with offset ([RssDates.format]) or null. */
    val publishedAt: String?,
    val imageUrl: String?,
    val imageAlt: String,
    val guid: String,
)

/**
 * Reads RSS 2.0, RSS 1.0 (RDF) and Atom with a pull parser. Tolerant: every element the app does
 * not know is skipped, a missing field is simply empty. Which parser implementation is used is
 * injected ([newParser]) so the same code runs on Android (`Xml.newPullParser()`) and in plain
 * JVM unit tests.
 */
class RssParser(private val newParser: () -> XmlPullParser) {

    /**
     * Parses [stream] (encoding from the XML declaration, else UTF-8).
     * @throws IOException when the stream fails or the document is not a feed at all.
     */
    fun parse(stream: InputStream): ParsedFeed {
        val parser = newParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
        } catch (e: XmlPullParserException) {
            throw IOException("Feed kann nicht gelesen werden", e)
        }
        val feed = FeedBuilder()
        val entries = mutableListOf<ParsedEntry>()
        try {
            var event = parser.eventType
            var sawRoot = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.orEmpty()
                    val local = localName(name)
                    if (!sawRoot) {
                        sawRoot = true
                        if (local !in ROOTS) throw IOException("Das ist kein RSS- oder Atom-Feed (<$name>)")
                    }
                    when {
                        local == "item" || local == "entry" -> entries += readEntry(parser, name)
                        // Channel/feed metadata: only top-level title and link (depth 2/3), not an item's.
                        local == "title" && parser.depth <= 3 && feed.title.isEmpty() -> feed.title = HtmlText.toPlain(readText(parser), dropReadMore = false)
                        local == "link" && parser.depth <= 3 && feed.link.isEmpty() -> feed.link = readLink(parser)
                        local == "image" && parser.depth <= 3 -> skip(parser)
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            if (entries.isEmpty()) throw IOException("Feed ist kein gültiges XML", e)
            // A broken tail after some good entries: keep what was read.
        }
        return ParsedFeed(title = feed.title.trim(), link = feed.link.trim(), entries = entries)
    }

    private class FeedBuilder {
        var title = ""
        var link = ""
    }

    private class EntryBuilder {
        var title = ""
        var link = ""
        var guid = ""
        var guidIsLink = false
        var description = ""
        var summary = ""
        var abstract = ""
        var content = ""
        var date: String? = null
        var updated: String? = null
        val images = mutableListOf<ImageCandidate>()
        var imageAlt = ""
    }

    private data class ImageCandidate(val url: String, val width: Int, val priority: Int)

    /** Reads one item/entry up to its end tag ([itemName]). */
    private fun readEntry(parser: XmlPullParser, itemName: String): ParsedEntry {
        val b = EntryBuilder()
        val startDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == itemName) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == startDepth + 1) {
                readEntryField(parser, b)
            } else if (event == XmlPullParser.START_TAG && parser.depth > startDepth + 1) {
                readNestedField(parser, b)
            }
            event = parser.next()
        }
        return b.build()
    }

    private fun readEntryField(parser: XmlPullParser, b: EntryBuilder) {
        val name = parser.name.orEmpty()
        val prefix = prefixOf(name)
        when (localName(name)) {
            "title" -> b.title = HtmlText.toPlain(readText(parser), dropReadMore = false)
            "link" -> {
                val link = readLink(parser)
                if (link.isNotEmpty() && (b.link.isEmpty() || prefix.isEmpty())) b.link = link
            }
            "guid", "id" -> {
                b.guidIsLink = parser.getAttributeValue(null, "isPermaLink")?.equals("true", ignoreCase = true) == true
                b.guid = readText(parser).trim()
            }
            "description" -> b.description = readText(parser)
            "summary" -> b.summary = readText(parser)
            "abstract" -> b.abstract = readText(parser)
            "encoded" -> b.content = readText(parser)
            "content" -> if (prefix == "media") b.addMediaImage(parser, priority = 2) else b.content = readText(parser)
            "thumbnail" -> if (prefix == "media") b.addMediaImage(parser, priority = 1)
            "enclosure" -> {
                val type = parser.getAttributeValue(null, "type").orEmpty()
                val url = parser.getAttributeValue(null, "url").orEmpty()
                if (type.startsWith("image/", ignoreCase = true) || url.looksLikeImage()) b.addImage(url, 0, priority = 3)
                skip(parser)
            }
            "pubDate", "date", "published", "issued", "created" -> b.date = b.date ?: readText(parser).trim()
            "updated", "modified" -> b.updated = readText(parser).trim()
            "imageURL" -> b.addImage(readText(parser).trim(), 0, priority = 2)
            "image" -> {
                // Atom/iTunes style `<image href="…">` or an RSS `<image><url>…</url></image>`.
                val href = parser.getAttributeValue(null, "href") ?: parser.getAttributeValue(null, "url")
                if (!href.isNullOrBlank()) {
                    b.addImage(href, 0, priority = 2)
                    skip(parser)
                }
                // Otherwise nested <url> is picked up by readNestedField.
            }
            "author", "creator", "category", "comments", "source" -> skip(parser)
        }
    }

    /** Elements nested deeper inside an item: GameStar puts media:thumbnail inside media:content, RSS images carry <url>. */
    private fun readNestedField(parser: XmlPullParser, b: EntryBuilder) {
        val name = parser.name.orEmpty()
        when (localName(name)) {
            "thumbnail" -> if (prefixOf(name) == "media") b.addMediaImage(parser, priority = 1)
            "content" -> if (prefixOf(name) == "media") b.addMediaImage(parser, priority = 2)
            "url" -> b.addImage(readText(parser).trim(), 0, priority = 1)
            "imageURL" -> b.addImage(readText(parser).trim(), 0, priority = 2)
        }
    }

    private fun EntryBuilder.addMediaImage(parser: XmlPullParser, priority: Int) {
        val url = parser.getAttributeValue(null, "url").orEmpty()
        val type = parser.getAttributeValue(null, "type").orEmpty()
        val medium = parser.getAttributeValue(null, "medium").orEmpty()
        val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
        val isImage = medium.equals("image", true) || type.startsWith("image/", true) || (medium.isEmpty() && type.isEmpty() && url.looksLikeImage()) || (medium.isEmpty() && type.isEmpty() && priority == 1)
        if (isImage) addImage(url, width, priority)
        // Do not skip: a media:content may wrap a media:thumbnail; the loop reads on.
    }

    private fun EntryBuilder.addImage(url: String, width: Int, priority: Int) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        val lower = clean.lowercase()
        if (lower.endsWith(".svg") || lower.endsWith(".ico")) return
        images += ImageCandidate(clean, width, priority)
    }

    private fun EntryBuilder.build(): ParsedEntry {
        val link = link.ifEmpty { if (guidIsLink || guid.startsWith("http")) guid else "" }
        val summaryHtml = listOf(description, summary, abstract, content).firstOrNull { HtmlText.toPlain(it).isNotEmpty() }.orEmpty()
        val plainSummary = HtmlText.toPlain(summaryHtml)
        // Prefer explicit media (largest first), then enclosures, then an <img> in the HTML.
        val explicit = images.maxWithOrNull(compareBy<ImageCandidate> { it.priority }.thenBy { it.width })
        val embedded = HtmlText.firstImageSrc(content) ?: HtmlText.firstImageSrc(description) ?: HtmlText.firstImageSrc(summary)
        val image = explicit?.url ?: embedded
        val alt = if (image != null) HtmlText.toPlain(Regex("(?is)<img\\b[^>]*\\balt\\s*=\\s*([\"'])(.*?)\\1").find(content.ifEmpty { description })?.groupValues?.getOrNull(2)).take(200) else ""
        return ParsedEntry(
            title = title.trim(),
            link = link.trim(),
            summary = plainSummary,
            publishedAt = RssDates.normalize(date) ?: RssDates.normalize(updated),
            imageUrl = image,
            imageAlt = alt,
            guid = guid,
        )
    }

    /** Text content of the current element (CDATA included); the parser ends on its END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val out = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> out.append(parser.text)
                XmlPullParser.START_TAG -> {
                    // Unexpected markup inside a text element (unescaped HTML): keep its text.
                    out.append(' ')
                }
            }
            event = parser.next()
        }
        return out.toString()
    }

    /** RSS `<link>text</link>` or Atom `<link href="…" rel="alternate"/>`; consumes the element. */
    private fun readLink(parser: XmlPullParser): String {
        val href = parser.getAttributeValue(null, "href")
        if (href != null) {
            val rel = parser.getAttributeValue(null, "rel").orEmpty()
            val type = parser.getAttributeValue(null, "type").orEmpty()
            val usable = (rel.isEmpty() || rel == "alternate") && !type.contains("xml", ignoreCase = true)
            val text = readText(parser) // consumes to the end tag
            return if (usable) href.trim() else text.trim().takeIf { it.startsWith("http") }.orEmpty()
        }
        return readText(parser).trim()
    }

    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
    }

    private fun localName(name: String): String = name.substringAfter(':')

    private fun prefixOf(name: String): String = if (':' in name) name.substringBefore(':') else ""

    private fun String.looksLikeImage(): Boolean {
        val path = lowercase().substringBefore('?')
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")
    }

    private companion object {
        val ROOTS = setOf("rss", "feed", "RDF")
    }
}
