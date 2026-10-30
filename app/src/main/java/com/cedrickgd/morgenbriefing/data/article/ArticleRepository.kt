package com.cedrickgd.morgenbriefing.data.article

import android.util.Log
import com.cedrickgd.morgenbriefing.data.local.JsonFiles
import com.cedrickgd.morgenbriefing.data.model.ArticleContent
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.remote.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The reader could not get the article; [reason] is a short German explanation. */
class ArticleException(val reason: String, cause: Throwable? = null) : IOException(reason, cause)

/**
 * Loads the readable text of an article: fetches the page once (browser-like request, no
 * cookies, no scripts), extracts the article with [ArticleExtractor] and keeps the result as
 * JSON (`<dir>/articles/<id>.json`) and in memory, so a second visit is instant and offline.
 */
class ArticleRepository(
    http: OkHttpClient,
    dir: File,
    private val json: Json,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val folder = File(dir, "articles")
    private val lock = Mutex()
    private val memory = object : LinkedHashMap<String, ArticleContent>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArticleContent>): Boolean = size > MEMORY_ENTRIES
    }
    private var pruned = false

    /**
     * The article behind [item], from the cache or freshly read from the page.
     * @throws ArticleException when the page is unreachable or holds no readable article.
     */
    suspend fun load(item: NewsItem, refresh: Boolean = false): ArticleContent {
        val id = item.id
        if (!refresh) {
            synchronized(memory) { memory[id] }?.takeUnless(ArticleExtractor::looksLikeConsentWall)?.let { return it }
            cached(id)?.let { content ->
                if (ArticleExtractor.looksLikeConsentWall(content)) {
                    // Cached before the wall check existed: forget it and read the page again.
                    withContext(Dispatchers.IO) { lock.withLock { fileFor(id).delete() } }
                } else {
                    synchronized(memory) { memory[id] = content }
                    return content
                }
            }
        }
        val html = fetch(item.url)
        val content = withContext(Dispatchers.Default) { ArticleExtractor.extract(html, item.url, clock()) }
            ?: throw ArticleException("Auf der Seite ist kein lesbarer Artikel zu finden")
        if (ArticleExtractor.looksLikeConsentWall(content)) {
            val site = item.source.ifBlank { content.site.ifBlank { "Die Redaktion" } }
            throw ArticleException("$site zeigt den Text nur nach Cookie-Zustimmung auf der eigenen Seite")
        }
        synchronized(memory) { memory[id] = content }
        store(id, content)
        return content
    }

    /** Removes cached articles older than [maxAge]; runs once per process. */
    suspend fun pruneOnce(maxAge: Duration = KEEP_FOR) {
        if (pruned) return
        pruned = true
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAge.toMillis()
            lock.withLock {
                folder.listFiles()?.forEach { file -> if (file.isFile && file.lastModified() < cutoff) file.delete() }
                JsonFiles.pruneTemp(folder)
            }
        }
    }

    private suspend fun fetch(url: String): String {
        val httpUrl = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: throw ArticleException("Ungültige Adresse")
        val request = Request.Builder()
            .url(httpUrl)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.6")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ArticleException("Die Seite ist gerade nicht erreichbar", e)
        }
        return response.use { r ->
            if (!r.isSuccessful) {
                throw ArticleException(
                    when (r.code) {
                        403 -> "Die Seite blockt den Zugriff (403)"
                        404, 410 -> "Den Artikel gibt es nicht mehr (404)"
                        429 -> "Zu viele Anfragen – gleich nochmal (429)"
                        in 500..599 -> "Der Server der Redaktion antwortet nicht (${r.code})"
                        else -> "HTTP ${r.code}"
                    },
                )
            }
            val type = r.body.contentType()
            if (type != null && type.type != "text" && type.subtype != "xhtml+xml" && type.subtype != "html") {
                throw ArticleException("Das ist keine Artikelseite (${type.type}/${type.subtype})")
            }
            withContext(Dispatchers.IO) {
                try {
                    val bytes = r.body.byteStream().use { it.readCapped(MAX_HTML_BYTES) }
                    // jsoup sniffs the charset (BOM, meta) when none is given; the header wins when it is.
                    val charset = type?.charset()?.name()
                    Jsoup.parse(ByteArrayInputStream(bytes), charset, url).outerHtml()
                } catch (e: IOException) {
                    throw ArticleException("Die Seite ließ sich nicht lesen", e)
                }
            }
        }
    }

    private suspend fun cached(id: String): ArticleContent? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        val text = JsonFiles.readOrNull(file) ?: return@withContext null
        try {
            json.decodeFromString(ArticleContent.serializer(), text)
        } catch (_: SerializationException) {
            lock.withLock { file.delete() }
            null
        } catch (_: IllegalArgumentException) {
            lock.withLock { file.delete() }
            null
        }
    }

    private suspend fun store(id: String, content: ArticleContent) {
        val payload = json.encodeToString(ArticleContent.serializer(), content)
        try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    JsonFiles.writeAtomically(fileFor(id), payload)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache article $id", e)
        }
    }

    private fun fileFor(id: String): File =
        File(folder, id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".json")

    private fun InputStream.readCapped(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
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
        private const val TAG = "ArticleRepository"
        private const val MEMORY_ENTRIES = 30
        private const val MAX_HTML_BYTES = 3 * 1024 * 1024

        /** Cached articles are kept this long (the feed itself forgets articles after a few days). */
        val KEEP_FOR: Duration = Duration.ofDays(7)

        /** What a mobile browser sends — newsrooms serve their normal article page to it. */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 Morgenbriefing/2.1"

        internal fun isFresh(content: ArticleContent, now: Instant, maxAge: Duration): Boolean =
            runCatching { Duration.between(ZonedDateTime.parse(content.fetchedAt).toInstant(), now) <= maxAge }.getOrDefault(false)
    }
}
