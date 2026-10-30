package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.data.model.ArticleImage
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.news.HtmlText
import com.cedrickgd.morgenbriefing.data.news.ParsedFeed
import com.cedrickgd.morgenbriefing.data.news.RssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A feed that could not be read, with a short German reason for the settings. */
class FeedException(val reason: String, cause: Throwable? = null) : IOException(reason, cause)

/** What [RssClient.discover] found behind a URL the user typed. */
data class DiscoveredFeed(
    /** The feed's own URL (may differ from the typed page URL). */
    val url: String,
    /** The feed's title, for the source name ("heise online News"). */
    val title: String,
    val itemCount: Int,
)

/**
 * Fetches and parses RSS/Atom feeds. Runs on the shared OkHttp client with a 20 s read timeout
 * per feed; a feed that fails never throws anything but [FeedException] (besides cancellation),
 * so one broken newsroom cannot take a topic down.
 */
class RssClient(
    http: OkHttpClient,
    private val parser: RssParser,
) {
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * The articles of [source], in feed order.
     * @throws FeedException when the feed is unreachable, not a feed, or empty.
     */
    suspend fun fetch(source: FeedSource): List<NewsItem> {
        val feed = fetchFeed(source.url)
        val items = feed.entries.mapNotNull { entry ->
            val link = entry.link.trim()
            val headline = entry.title.trim()
            if (headline.isEmpty() || !link.startsWith("http", ignoreCase = true)) return@mapNotNull null
            NewsItem(
                id = stableId(link),
                headline = headline,
                summary = entry.summary.take(MAX_SUMMARY),
                url = link,
                source = source.name,
                sourceId = source.id,
                topicId = source.topicId,
                publishedAt = entry.publishedAt,
                image = entry.imageUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let { ArticleImage(it, entry.imageAlt) },
            )
        }
        if (items.isEmpty()) throw FeedException("Der Feed enthält keine Meldungen")
        return items
    }

    /**
     * Finds the feed behind [input]: the URL itself when it is a feed, else the RSS/Atom link
     * the page announces in its `<head>`, else common paths (`/feed`, `/rss`, `/rss.xml`).
     * @throws FeedException when nothing usable is found.
     */
    suspend fun discover(input: String): DiscoveredFeed {
        val typed = normalizeUrl(input) ?: throw FeedException("Das ist keine Web-Adresse")
        val candidates = mutableListOf(typed)
        var lastError: FeedException? = null
        var pageHtml: String? = null
        for (round in 0..1) {
            val url = candidates.removeFirstOrNull() ?: break
            try {
                val feed = fetchFeed(url)
                if (feed.entries.isNotEmpty()) {
                    return DiscoveredFeed(url = url, title = feed.title.ifBlank { hostOf(url) }, itemCount = feed.entries.size)
                }
                lastError = FeedException("Der Feed enthält keine Meldungen")
            } catch (e: FeedException) {
                lastError = e
                if (round == 0) {
                    pageHtml = fetchHtmlOrNull(url)
                    val announced = pageHtml?.let { discoverFeedLinks(it, url) }.orEmpty()
                    candidates += announced
                    if (announced.isEmpty()) candidates += commonFeedPaths(url)
                }
            }
        }
        // Try the remaining announced/common paths (at most a few).
        for (url in candidates.take(4)) {
            try {
                val feed = fetchFeed(url)
                if (feed.entries.isNotEmpty()) {
                    return DiscoveredFeed(url = url, title = feed.title.ifBlank { hostOf(url) }, itemCount = feed.entries.size)
                }
            } catch (e: FeedException) {
                lastError = e
            }
        }
        throw lastError ?: FeedException("Hier ist kein Feed zu finden")
    }

    private suspend fun fetchFeed(url: String): ParsedFeed {
        val request = Request.Builder()
            .url(url.toHttpUrlOrNull() ?: throw FeedException("Ungültige Adresse"))
            .header("Accept", ACCEPT)
            .header("User-Agent", FEED_USER_AGENT)
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw FeedException("Nicht erreichbar", e)
        }
        return response.use { r ->
            if (!r.isSuccessful) {
                throw FeedException(
                    when (r.code) {
                        403 -> "Die Seite blockt den Zugriff (403)"
                        404 -> "Feed nicht gefunden (404)"
                        429 -> "Zu viele Anfragen (429)"
                        in 500..599 -> "Server antwortet nicht (${r.code})"
                        else -> "HTTP ${r.code}"
                    },
                )
            }
            withContext(Dispatchers.IO) {
                try {
                    r.body.byteStream().use { stream -> parser.parse(stream) }
                } catch (e: IOException) {
                    throw FeedException(e.message ?: "Feed kann nicht gelesen werden", e)
                }
            }
        }
    }

    private suspend fun fetchHtmlOrNull(url: String): String? {
        val request = Request.Builder()
            .url(url.toHttpUrlOrNull() ?: return null)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", PAGE_USER_AGENT)
            .get()
            .build()
        return try {
            client.newCall(request).await().use { r ->
                if (!r.isSuccessful) return null
                withContext(Dispatchers.IO) {
                    // Only the head matters; a few hundred KB are plenty.
                    val bytes = r.body.byteStream().use { it.readNBytesCompat(MAX_HTML_BYTES) }
                    String(bytes, Charsets.UTF_8)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_SUMMARY = 600
        private const val MAX_HTML_BYTES = 400 * 1024
        private const val ACCEPT = "application/rss+xml, application/atom+xml, application/rdf+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"

        /** Honest, descriptive, with a contact — what newsroom servers expect from a reader. */
        const val FEED_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) Morgenbriefing/2.0 (RSS reader; +https://github.com/CedrickGD/Morgenbriefing)"
        private const val PAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Morgenbriefing/2.0"

        private val FEED_LINK = Regex("(?is)<link\\b[^>]*>")
        private val ATTR = Regex("(?is)\\b([a-z-]+)\\s*=\\s*([\"'])(.*?)\\2")

        /** The first 16 hex digits of SHA-1 over the normalized link: short, stable, unique enough. */
        fun stableId(link: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(normalizeLinkForId(link).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Lower-cased host, no scheme, no tracking query, no fragment, no trailing slash. */
        fun normalizeLinkForId(link: String): String {
            val trimmed = link.trim()
            val noFragment = trimmed.substringBefore('#')
            val query = noFragment.substringAfter('?', "")
            val base = noFragment.substringBefore('?')
            val keptQuery = query.split('&').filter { part ->
                val key = part.substringBefore('=').lowercase()
                part.isNotEmpty() && !key.startsWith("utm_") && key !in setOf("ref", "wt_mc", "maca", "cid", "fbclid", "gclid", "s", "source")
            }.joinToString("&")
            val withoutScheme = base.replaceFirst(Regex("(?i)^https?://"), "").replaceFirst(Regex("(?i)^www\\."), "")
            val path = withoutScheme.trimEnd('/').lowercase()
            return if (keptQuery.isEmpty()) path else "$path?$keptQuery"
        }

        /** Adds `https://` when the scheme is missing; null when the result is no URL. */
        fun normalizeUrl(input: String): String? {
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val withScheme = if (Regex("(?i)^https?://").containsMatchIn(text)) text else "https://$text"
            return withScheme.toHttpUrlOrNull()?.toString()
        }

        /** The feed URLs a page announces (`<link rel="alternate" type="application/rss+xml">`), resolved against [pageUrl]. */
        fun discoverFeedLinks(html: String, pageUrl: String): List<String> {
            val head = html.substringBefore("</head>", html).take(MAX_HTML_BYTES)
            return FEED_LINK.findAll(head).mapNotNull { match ->
                val attrs = ATTR.findAll(match.value).associate { it.groupValues[1].lowercase() to HtmlText.decodeEntities(it.groupValues[3]) }
                val type = attrs["type"].orEmpty().lowercase()
                if (!type.contains("rss") && !type.contains("atom")) return@mapNotNull null
                val href = attrs["href"]?.trim().orEmpty()
                if (href.isEmpty()) return@mapNotNull null
                resolve(pageUrl, href)
            }.distinct().toList()
        }

        private fun commonFeedPaths(url: String): List<String> {
            val base = url.toHttpUrlOrNull() ?: return emptyList()
            val root = "${base.scheme}://${base.host}"
            return listOf("$root/feed/", "$root/rss", "$root/rss.xml", "$root/feed.xml", "$root/atom.xml", "$root/index.rss")
        }

        private fun resolve(pageUrl: String, href: String): String? {
            val base = pageUrl.toHttpUrlOrNull() ?: return null
            return base.resolve(href)?.toString()
        }

        private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
    }
}
