package com.cedrickgd.morgenbriefing.data

import android.util.Log
import com.cedrickgd.morgenbriefing.data.local.NewsCache
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceStatus
import com.cedrickgd.morgenbriefing.data.model.TopicNews
import com.cedrickgd.morgenbriefing.data.news.FeedCatalog
import com.cedrickgd.morgenbriefing.data.news.RssDates
import com.cedrickgd.morgenbriefing.data.remote.FeedException
import com.cedrickgd.morgenbriefing.data.remote.RssClient
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** What a refresh of several topics ended in. */
data class RefreshOutcome(
    /** Topics for which at least one source answered. */
    val refreshed: Set<String>,
    /** Topics for which every source failed (offline, or all feeds down). */
    val failed: Set<String>,
) {
    val allFailed: Boolean get() = refreshed.isEmpty() && failed.isNotEmpty()
}

/**
 * The news of the app: every topic's articles, merged from its RSS/Atom sources, kept in memory
 * ([state]) and on disk ([NewsCache]). A refresh fetches the sources of the wanted topics in
 * parallel (at most [PARALLEL_FETCHES] at a time) and publishes each topic as soon as its
 * sources are in. A source that fails keeps the articles it delivered last time, so a newsroom
 * that is down for a moment never empties a topic.
 */
class NewsRepository(
    private val client: RssClient,
    private val cache: NewsCache,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
) {
    data class State(
        val topics: Map<String, TopicNews> = emptyMap(),
        /** Topic ids whose refresh is running. */
        val loading: Set<String> = emptySet(),
        /** The cache was read once (so "nothing yet" means nothing, not "not looked"). */
        val warmed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val fetchLimit = Semaphore(PARALLEL_FETCHES)
    private val warmLock = Mutex()

    /** Loads the cached topics once; later calls are no-ops. */
    suspend fun warm() {
        if (_state.value.warmed) return
        warmLock.withLock {
            if (_state.value.warmed) return
            val cached = try {
                cache.loadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "News cache unreadable", e)
                emptyMap()
            }
            _state.update { s -> s.copy(topics = cached + s.topics, warmed = true) }
        }
    }

    /** The newest fetch time over [topicIds], or null when none of them was ever fetched. */
    fun fetchedAt(topicIds: Collection<String>): Instant? =
        topicIds.mapNotNull { _state.value.topics[it]?.fetchedAt?.let(::parseInstantOrNull) }.maxOrNull()

    /**
     * True when [topicId] was never fetched, every source failed the last time (so the next
     * chance to fetch should be taken), or its last fetch is older than [maxAge].
     */
    fun isStale(topicId: String, maxAge: Duration): Boolean {
        val news = _state.value.topics[topicId] ?: return true
        if (news.allFailed) return true
        val at = parseInstantOrNull(news.fetchedAt) ?: return true
        return Duration.between(at, clock().toInstant()) > maxAge
    }

    /**
     * Fetches every enabled source of [topicIds] (per [settings]) and merges the results into
     * [state] and the cache. Topics that are already being refreshed are skipped.
     */
    suspend fun refresh(topicIds: Collection<String>, settings: AppSettings): RefreshOutcome {
        warm()
        val wanted = topicIds.distinct().filter { it !in _state.value.loading }
        if (wanted.isEmpty()) return RefreshOutcome(emptySet(), emptySet())
        _state.update { it.copy(loading = it.loading + wanted) }
        val refreshed = HashSet<String>()
        val failed = HashSet<String>()
        try {
            coroutineScope {
                wanted.map { topicId ->
                    async {
                        val result = refreshTopic(topicId, settings)
                        synchronized(refreshed) { if (result) refreshed += topicId else failed += topicId }
                    }
                }.awaitAll()
            }
        } finally {
            _state.update { it.copy(loading = it.loading - wanted.toSet()) }
        }
        return RefreshOutcome(refreshed, failed)
    }

    /** Forgets a topic (a custom topic was deleted). */
    suspend fun forget(topicId: String) {
        _state.update { it.copy(topics = it.topics - topicId) }
        try {
            cache.delete(topicId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete cached news of $topicId", e)
        }
    }

    /** Refreshes one topic; returns whether at least one source answered. */
    private suspend fun refreshTopic(topicId: String, settings: AppSettings): Boolean = coroutineScope {
        val sources = FeedCatalog.sourcesFor(topicId, settings)
        val previous = _state.value.topics[topicId]
        val results = sources.map { source ->
            async {
                fetchLimit.withPermit {
                    try {
                        FetchResult(source, client.fetch(source), null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FeedException) {
                        Log.i(TAG, "${source.name}: ${e.reason}")
                        FetchResult(source, emptyList(), e.reason)
                    } catch (e: IOException) {
                        Log.w(TAG, "${source.name} failed", e)
                        FetchResult(source, emptyList(), "Nicht erreichbar")
                    } catch (e: Exception) {
                        Log.w(TAG, "${source.name} failed unexpectedly", e)
                        FetchResult(source, emptyList(), "Fehler beim Lesen")
                    }
                }
            }
        }.awaitAll()
        val merged = merge(topicId, previous, results, now = clock().toInstant())
        _state.update { it.copy(topics = it.topics + (topicId to merged)) }
        try {
            cache.save(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache news of $topicId", e)
        }
        results.any { it.error == null } || sources.isEmpty()
    }

    internal class FetchResult(val source: FeedSource, val items: List<NewsItem>, val error: String?)

    companion object {
        private const val TAG = "NewsRepository"
        private const val PARALLEL_FETCHES = 6

        /** Articles kept per topic (the newest). */
        const val MAX_PER_TOPIC = 400

        /** Articles of a failed source are kept from the last fetch for this long. */
        private val KEEP_FAILED_FOR: Duration = Duration.ofDays(3)

        /**
         * Merges fetch results into the topic's articles: failed sources keep what they delivered
         * before ([previous]), duplicates (same link, or same headline from the same source) are
         * dropped, the newest article comes first and undated ones follow the dated ones. Pure.
         */
        internal fun merge(topicId: String, previous: TopicNews?, results: List<FetchResult>, now: Instant): TopicNews {
            val items = ArrayList<NewsItem>()
            val statuses = ArrayList<SourceStatus>()
            results.forEach { result ->
                if (result.error == null) {
                    items += result.items
                    statuses += SourceStatus(result.source.id, result.source.name, ok = true, count = result.items.size)
                } else {
                    val kept = previous?.items.orEmpty().filter { item ->
                        item.sourceId == result.source.id && !isOlderThan(item.publishedAt, now, KEEP_FAILED_FOR)
                    }
                    items += kept
                    statuses += SourceStatus(result.source.id, result.source.name, ok = false, count = kept.size, error = result.error)
                }
            }
            // A round in which no source answered is not a fetch: the topic stays as old as it was,
            // so the next refresh does not skip it as "fresh".
            val reached = results.isEmpty() || results.any { it.error == null }
            return TopicNews(
                topicId = topicId,
                fetchedAt = if (reached || previous == null) RssDates.format(now) else previous.fetchedAt,
                items = dedupe(items).sortedWith(NEWEST_FIRST).take(MAX_PER_TOPIC),
                sources = statuses,
            )
        }

        /** Same id, or the same headline from the same source (a feed listing a story twice). */
        internal fun dedupe(items: List<NewsItem>): List<NewsItem> {
            val seenIds = HashSet<String>()
            val seenTitles = HashSet<String>()
            return items.filter { item ->
                val title = item.sourceId + "|" + item.headline.trim().lowercase()
                seenIds.add(item.id) && seenTitles.add(title)
            }
        }

        private fun isOlderThan(iso: String?, now: Instant, maxAge: Duration): Boolean {
            val at = parseInstantOrNull(iso) ?: return false
            return Duration.between(at, now) > maxAge
        }

        private val NEWEST_FIRST: Comparator<NewsItem> = Comparator { a, b ->
            val ta = parseInstantOrNull(a.publishedAt)
            val tb = parseInstantOrNull(b.publishedAt)
            when {
                ta == null && tb == null -> 0
                ta == null -> 1
                tb == null -> -1
                else -> tb.compareTo(ta)
            }
        }
    }
}
