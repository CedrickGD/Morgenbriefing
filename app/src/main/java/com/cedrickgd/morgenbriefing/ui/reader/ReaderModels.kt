package com.cedrickgd.morgenbriefing.ui.reader

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.news.TopicPrefs
import com.cedrickgd.morgenbriefing.ui.news.readerArticles
import com.cedrickgd.morgenbriefing.ui.news.readingMinutesOf
import com.cedrickgd.morgenbriefing.ui.news.sourceLabel
import com.cedrickgd.morgenbriefing.ui.news.sourcesOf
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * One page of the reader.
 *
 * @property live researched on demand ("⚡ Live", docs/AI.md §2) instead of coming from the
 *   morning brief.
 */
@Immutable
data class ReaderEntry(val item: NewsItem, val topic: Topic, val live: Boolean = false)

/** Seed of the neutral color for live articles of topics the app does not know. */
private const val LIVE_FALLBACK_SEED = 0xFF5F6B7AL

/** The topic of a live article: from the catalogue, or a neutral "Live" topic for unknown ids. */
internal fun liveTopic(topicId: String): Topic =
    Topics.byId(topicId) ?: Topic(topicId.ifBlank { "live" }, "Live", "⚡", LIVE_FALLBACK_SEED)

/**
 * Every page of the reader: the brief's articles in reading order (arranged like on Today),
 * then the live articles of that day that the brief does not contain already. Hidden topics
 * are left out — except the topic of [focusKey], so an opened article never vanishes.
 */
internal fun readerEntries(
    brief: Brief?,
    live: List<LiveArticle>,
    prefs: TopicPrefs,
    focusKey: String?,
): List<ReaderEntry> {
    val fromBrief = brief?.let { readerArticles(it, prefs, focusKey) }.orEmpty()
        .map { ReaderEntry(it.item, it.topic) }
    val seen = fromBrief.mapTo(HashSet()) { it.item.key }
    val fromLive = live.mapNotNull { article ->
        val key = article.item.key
        val visible = article.topicId !in prefs.hidden || key == focusKey
        if (key.isBlank() || !visible || !seen.add(key)) null else ReaderEntry(article.item, liveTopic(article.topicId), live = true)
    }
    return fromBrief + fromLive
}

/** Smallest / largest reader text size and the A− / A+ step (settings: 0.85–1.4). */
internal const val MIN_TEXT_SCALE = 0.85f
internal const val MAX_TEXT_SCALE = 1.4f
private const val TEXT_SCALE_STEP = 0.1f

/**
 * One A− / A+ step from [current] on the 0.1 grid (0.9, 1.0 … 1.4), clamped to 0.85–1.4:
 * 1.0 → 1.1, 0.9 → 0.85, 1.15 → 1.2 / 1.1. Never returns more than two decimals.
 */
internal fun stepTextScale(current: Float, larger: Boolean): Float {
    val epsilon = 0.001f
    val grid = current / TEXT_SCALE_STEP
    val next = if (larger) {
        (kotlin.math.floor(grid + epsilon) + 1) * TEXT_SCALE_STEP
    } else {
        (kotlin.math.ceil(grid - epsilon) - 1) * TEXT_SCALE_STEP
    }
    return (next.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) * 100f).roundToInt() / 100f
}

/** "110 %" */
internal fun textScaleLabel(scale: Float): String = "${(scale * 100f).roundToInt()} %"

/**
 * Meta line under the lead: "Handelsblatt · 4 Min. Lesezeit · vor 3 Std." — unknown parts are
 * left out (live articles carry their own "⚡ Live" badge above the headline).
 */
internal fun readerMetaLine(entry: ReaderEntry, now: ZonedDateTime): String {
    val item = entry.item
    val sources = sourcesOf(item)
    return buildList {
        val primary = item.source.trim().ifEmpty { sources.firstOrNull()?.let(::sourceLabel).orEmpty() }
        if (primary.isNotEmpty()) add(primary)
        readingMinutesOf(item).takeIf { it > 0 }?.let { add("$it Min. Lesezeit") }
        val time = Fmt.relative(item.publishedAt, now) ?: item.publishedAt?.let(Fmt::clock)?.takeIf { it.isNotEmpty() }
        time?.let(::add)
    }.joinToString(" · ")
}

/** "In eigenen Worten zusammengefasst aus diesen 3 Berichten." */
internal fun sourcesFootnote(count: Int): String = when {
    count <= 1 -> "In eigenen Worten zusammengefasst aus diesem Bericht."
    else -> "In eigenen Worten zusammengefasst aus diesen $count Berichten."
}

/**
 * How far the reader has read, 0..1, from what a lazy list shows: the scrolled distance over
 * the scrollable distance, with item sizes estimated from the visible ones ([averageItemSize]).
 * Pure and allocation-free, so the progress line can be computed in the draw phase.
 *
 * @param firstIndex index of the first visible item; [firstOffset] how many px of it are
 *   scrolled away (≥ 0).
 */
internal fun readingProgress(
    firstIndex: Int,
    firstOffset: Int,
    totalItems: Int,
    averageItemSize: Float,
    viewport: Int,
    canScrollForward: Boolean,
): Float {
    if (totalItems <= 0 || averageItemSize <= 0f || viewport <= 0) return 0f
    if (!canScrollForward) return 1f
    val average = averageItemSize.coerceAtLeast(1f)
    val scrolled = firstIndex * average + firstOffset
    val scrollable = totalItems * average - viewport
    if (scrollable <= 0f) return 1f
    return (scrolled / scrollable).coerceIn(0f, 1f)
}
