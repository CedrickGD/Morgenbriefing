package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One live research run as stored in `store/live/YYYY-MM-DD-HHmm.json`. */
@Serializable
internal data class StoredLiveRun(
    val version: Int = 1,
    /** ISO-8601 with offset: when the articles were published to the app. */
    val fetchedAt: String,
    /** [com.cedrickgd.morgenbriefing.data.model.AiProviderId.key] of the run; "" = written by v1.2 (Claude). */
    val provider: String = "",
    val model: String = "",
    val topicIds: List<String> = emptyList(),
    val articles: List<StoredLiveArticle> = emptyList(),
)

@Serializable
internal data class StoredLiveArticle(val topicId: String, val item: NewsItem)

/**
 * Live news results (docs/AI.md §2), one file per run and minute. A run without new articles
 * is stored too (empty list) — it still marks "last checked". Reads never throw; corrupt files
 * are skipped. Days older than [maxAgeDays] are pruned on write.
 */
internal class LiveStore(
    private val dir: File,
    private val json: Json,
    private val maxAgeDays: Long = 400,
) {
    private val lock = Mutex()

    /**
     * Adds [articles] of a run with [provider] (its [com.cedrickgd.morgenbriefing.data.model.AiProviderId.key])
     * and [model] to the run file of [fetchedAt]'s minute (merging with an earlier write in the same
     * minute; an article with the same key replaces the older copy).
     * @throws IOException when the disk refuses the write.
     */
    suspend fun append(fetchedAt: Instant, provider: String, model: String, topicIds: List<String>, articles: List<StoredLiveArticle>) =
        withContext(Dispatchers.IO) {
            val local = fetchedAt.atZone(BERLIN)
            val file = File(dir, local.format(FILE_NAME) + ".json")
            val iso = local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            lock.withLock {
                val existing = decode(file)
                val merged = if (existing == null) {
                    StoredLiveRun(fetchedAt = iso, provider = provider, model = model, topicIds = topicIds, articles = articles)
                } else {
                    val keys = articles.map { it.item.key }.toSet()
                    existing.copy(
                        fetchedAt = iso,
                        topicIds = (existing.topicIds + topicIds).distinct(),
                        articles = existing.articles.filterNot { it.item.key in keys } + articles,
                    )
                }
                AiFiles.writeAtomically(file, json.encodeToString(StoredLiveRun.serializer(), merged))
                prune()
            }
        }

    /** All live articles of [date], newest run first; a key seen in a newer run wins. */
    suspend fun day(date: LocalDate): List<LiveArticle> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        runs(date).flatMap { run ->
            run.articles
                .sortedByDescending { parseInstantOrNull(it.item.publishedAt) ?: Instant.EPOCH }
                .filter { seen.add(it.item.key) }
                .map { LiveArticle(item = it.item, topicId = it.topicId, fetchedAt = run.fetchedAt) }
        }
    }

    /** `fetchedAt` of the newest run on [date] (with or without articles), or null. */
    suspend fun lastRunAt(date: LocalDate): String? = withContext(Dispatchers.IO) {
        runs(date).firstOrNull()?.fetchedAt
    }

    private fun runs(date: LocalDate): List<StoredLiveRun> {
        val prefix = "$date-"
        return dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && FILE.matches(f.name) }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { decode(it) }
            .sortedByDescending { parseInstantOrNull(it.fetchedAt) ?: Instant.EPOCH }
    }

    private fun decode(file: File): StoredLiveRun? {
        val text = AiFiles.readOrNull(file) ?: return null
        return try {
            json.decodeFromString(StoredLiveRun.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun prune() {
        val oldest = LocalDate.now(BERLIN).minusDays(maxAgeDays)
        dir.listFiles()?.forEach { file ->
            val match = FILE.matchEntire(file.name) ?: return@forEach
            val date = try {
                LocalDate.parse(match.groupValues[1])
            } catch (_: DateTimeParseException) {
                null
            }
            if (date != null && date.isBefore(oldest)) file.delete()
        }
    }

    private companion object {
        val FILE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
        val FILE = Regex("""(\d{4}-\d{2}-\d{2})-\d{4}\.json""")
    }
}
