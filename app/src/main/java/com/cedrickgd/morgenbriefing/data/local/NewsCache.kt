package com.cedrickgd.morgenbriefing.data.local

import com.cedrickgd.morgenbriefing.data.model.TopicNews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last fetched articles per topic as JSON files (`<dir>/news/<topic>.json`), so the feed is
 * on screen at once — also offline. Reads never throw; a corrupt file is deleted.
 */
class NewsCache(dir: File, private val json: Json) {
    private val folder = File(dir, "news")
    private val lock = Mutex()

    suspend fun save(news: TopicNews) {
        val payload = json.encodeToString(TopicNews.serializer(), news)
        withContext(Dispatchers.IO) {
            lock.withLock {
                JsonFiles.writeAtomically(fileFor(news.topicId), payload)
                JsonFiles.pruneTemp(folder)
            }
        }
    }

    suspend fun load(topicId: String): TopicNews? = withContext(Dispatchers.IO) { read(fileFor(topicId)) }

    /** Every cached topic, keyed by topic id. */
    suspend fun loadAll(): Map<String, TopicNews> = withContext(Dispatchers.IO) {
        val files = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
        files.mapNotNull { read(it) }.associateBy { it.topicId }
    }

    suspend fun delete(topicId: String) = withContext(Dispatchers.IO) {
        lock.withLock { fileFor(topicId).delete() }
        Unit
    }

    private suspend fun read(file: File): TopicNews? {
        val text = JsonFiles.readOrNull(file) ?: return null
        return try {
            json.decodeFromString(TopicNews.serializer(), text)
        } catch (_: SerializationException) {
            lock.withLock { file.delete() }
            null
        } catch (_: IllegalArgumentException) {
            lock.withLock { file.delete() }
            null
        }
    }

    /** `custom:ab12` → `custom_ab12.json`: only safe file name characters. */
    private fun fileFor(topicId: String): File =
        File(folder, topicId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".json")
}
