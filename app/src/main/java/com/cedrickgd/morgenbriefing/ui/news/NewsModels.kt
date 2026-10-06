package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.SettingsRepository
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.domain.Fmt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZonedDateTime
import kotlin.math.ceil

/** How many stories the "Top" chip of the feed shows. */
internal const val TOP_STORY_COUNT = 10

/** Reading speed used to estimate the reading time of articles without `readingMinutes`. */
private const val WORDS_PER_MINUTE = 200.0

/** The user's topic settings that shape how a brief is shown: order and switched-off topics. */
@Immutable
data class TopicPrefs(
    val order: List<String> = Topics.DEFAULT_ORDER,
    val hidden: Set<String> = emptySet(),
) {
    constructor(settings: AppSettings) : this(settings.topicOrder, settings.hiddenTopics)

    /** [Topics.arrange] with these preferences (drops hidden and empty sections). */
    fun arrange(sections: List<NewsSection>): List<NewsSection> = Topics.arrange(sections, order, hidden)
}

/** Topic preferences as a flow; only emits when order or hidden topics actually change. */
fun SettingsRepository.topicPrefs(): Flow<TopicPrefs> =
    settings.map { TopicPrefs(it) }.distinctUntilChanged()

/**
 * One topic of a brief, ready to render: [top] items are always shown as large cards, [more]
 * items sit behind the "+ Mehr" button.
 *
 * @property index position in the arranged list — keeps lazy-list keys unique even when a
 *   (malformed) brief repeats a section id.
 */
@Immutable
data class SectionUi(
    val index: Int,
    val id: String,
    val topic: Topic,
    val top: List<NewsItem>,
    val more: List<NewsItem>,
) {
    val count: Int get() = top.size + more.size
    val hasMore: Boolean get() = more.isNotEmpty()

    /** True when the user opened the "more" items of this section ([expandedIds] = section ids). */
    fun isExpanded(expandedIds: Set<String>): Boolean = hasMore && id in expandedIds
}

/** One of the most important stories of a brief ("Top" chip), with the topic it belongs to. */
@Immutable
data class TopStory(val item: NewsItem, val topic: Topic)

/** Everything the news part of a screen shows for one brief. */
@Immutable
data class NewsFeed(
    val sections: List<SectionUi>,
    /** The most important stories across the visible topics, importance descending. */
    val topStories: List<TopStory>,
    /** The brief has news, but the user switched off every topic in it. */
    val allHidden: Boolean,
) {
    val isEmpty: Boolean get() = sections.isEmpty()
}

/** An article in the reader's pager. */
@Immutable
data class ReaderArticle(val item: NewsItem, val topic: Topic)

/** Arranges [brief] with the user's [prefs] and splits each section into top/more. Pure. */
internal fun buildNewsFeed(brief: Brief, prefs: TopicPrefs): NewsFeed {
    val sections = prefs.arrange(brief.sections).mapIndexed { index, section -> sectionUi(index, section, brief.schema) }
    return NewsFeed(
        sections = sections,
        topStories = topStories(sections),
        allHidden = sections.isEmpty() && brief.sections.any { it.items.isNotEmpty() },
    )
}

internal fun sectionUi(index: Int, section: NewsSection, schema: Int): SectionUi {
    val (top, more) = splitTiers(section.items, schema)
    return SectionUi(index = index, id = section.id, topic = Topics.forSection(section), top = top, more = more)
}

/**
 * Splits items into (top, more). Schema-1 briefs know no tiers: everything is top. A section
 * that only has `more` items promotes the first one, so it never starts with a bare button.
 */
internal fun splitTiers(items: List<NewsItem>, schema: Int): Pair<List<NewsItem>, List<NewsItem>> {
    if (schema < 2) return items to emptyList()
    val (more, top) = items.partition { it.isMore }
    if (top.isEmpty() && more.isNotEmpty()) return listOf(more.first()) to more.drop(1)
    return top to more
}

/**
 * The [count] most important stories across the visible [sections]: importance descending,
 * ties in the user's topic order (and brief order within a topic).
 */
internal fun topStories(sections: List<SectionUi>, count: Int = TOP_STORY_COUNT): List<TopStory> {
    val all = sections.flatMap { section -> (section.top + section.more).map { TopStory(it, section.topic) } }
    return all.withIndex()
        .sortedWith(compareByDescending<IndexedValue<TopStory>> { it.value.item.importance }.thenBy { it.index })
        .take(count)
        .map { it.value }
}

/** Label of a section's expand button. */
internal fun moreButtonLabel(title: String, moreCount: Int, expanded: Boolean): String =
    if (expanded) "Weniger anzeigen" else "+ Mehr zu $title ($moreCount)"

/** Adds [id] when missing, removes it when present. */
internal fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/**
 * All articles of [brief] in reading order: arranged topics, each with its top then its more
 * items. The topic of [focusKey] is kept even if the user hid it meanwhile, so an opened
 * article never vanishes from under the reader.
 */
internal fun readerArticles(brief: Brief, prefs: TopicPrefs, focusKey: String?): List<ReaderArticle> {
    val focusSection = focusKey?.let { key -> brief.sections.firstOrNull { s -> s.items.any { it.key == key } }?.id }
    val effective = if (focusSection != null && focusSection in prefs.hidden) {
        prefs.copy(hidden = prefs.hidden - focusSection)
    } else {
        prefs
    }
    return effective.arrange(brief.sections).flatMap { section ->
        val (top, more) = splitTiers(section.items, brief.schema)
        val topic = Topics.forSection(section)
        (top + more).map { ReaderArticle(it, topic) }
    }
}

/** True when the brief contains an item with [key]. */
internal fun Brief.containsArticle(key: String): Boolean = sections.any { s -> s.items.any { it.key == key } }

/** `readingMinutes` from the brief, else estimated from the body; 0 for summary-only items. */
internal fun readingMinutesOf(item: NewsItem): Int = when {
    item.readingMinutes > 0 -> item.readingMinutes
    item.body.isNotBlank() -> {
        val words = item.body.split(WHITESPACE).count { it.isNotBlank() }
        ceil(words / WORDS_PER_MINUTE).toInt().coerceAtLeast(1)
    }
    else -> 0
}

/**
 * The sources of an article, primary first, without duplicates (same URL, or same name when
 * one of them has no URL — then the entry with a URL wins).
 */
internal fun sourcesOf(item: NewsItem): List<SourceRef> {
    val result = mutableListOf<SourceRef>()
    fun addUnique(raw: SourceRef) {
        val ref = SourceRef(raw.name.trim(), raw.url.trim())
        if (ref.name.isEmpty() && ref.url.isEmpty()) return
        val index = result.indexOfFirst { known ->
            (ref.url.isNotEmpty() && known.url.equals(ref.url, ignoreCase = true)) ||
                ((ref.url.isEmpty() || known.url.isEmpty()) && ref.name.isNotEmpty() && known.name.equals(ref.name, ignoreCase = true))
        }
        when {
            index < 0 -> result += ref
            result[index].url.isEmpty() && ref.url.isNotEmpty() -> result[index] = SourceRef(result[index].name.ifEmpty { ref.name }, ref.url)
        }
    }
    addUnique(SourceRef(item.source, item.url))
    item.sources.forEach(::addUnique)
    return result
}

/** Name to show for a source: its name, else the host of its URL. */
internal fun sourceLabel(source: SourceRef): String = source.name.ifBlank { hostOf(source.url) }

/**
 * Meta line of a card: "Handelsblatt · 3 Quellen · 4 Min. · vor 3 Std." — parts that are
 * unknown are left out.
 */
internal fun articleMeta(item: NewsItem, now: ZonedDateTime): String {
    val sources = sourcesOf(item)
    return buildList {
        val primary = item.source.trim().ifEmpty { sources.firstOrNull()?.let(::sourceLabel).orEmpty() }
        if (primary.isNotEmpty()) add(primary)
        if (sources.size > 1) add("${sources.size} Quellen")
        readingMinutesOf(item).takeIf { it > 0 }?.let { add("$it Min.") }
        Fmt.relative(item.publishedAt, now)?.let(::add)
    }.joinToString(" · ")
}

/** Meta line under a feed entry: "vor 3 Std. · Handelsblatt" — unknown parts are left out. */
internal fun feedMeta(item: NewsItem, now: ZonedDateTime): String = buildList {
    Fmt.relative(item.publishedAt, now)?.let(::add)
    val primary = item.source.trim().ifEmpty { sourcesOf(item).firstOrNull()?.let(::sourceLabel).orEmpty() }
    if (primary.isNotEmpty()) add(primary)
}.joinToString(" · ")

/** Meta line in the reader: "4 Min. Lesezeit · vor 3 Std. · 3 Quellen". */
internal fun readerMeta(item: NewsItem, now: ZonedDateTime): String {
    val sources = sourcesOf(item)
    return buildList {
        readingMinutesOf(item).takeIf { it > 0 }?.let { add("$it Min. Lesezeit") }
        val time = Fmt.relative(item.publishedAt, now) ?: item.publishedAt?.let(Fmt::clock)?.takeIf { it.isNotEmpty() }
        time?.let(::add)
        when {
            sources.size > 1 -> add("${sources.size} Quellen")
            sources.size == 1 -> add(sourceLabel(sources.first()))
        }
    }.joinToString(" · ")
}

/** "www.tagesschau.de/…" → "tagesschau.de"; empty when [url] has no host. */
internal fun hostOf(url: String): String {
    val afterScheme = url.trim().substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return ""
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase()
        .removePrefix("www.")
}

/** Text size steps of the reader (A− / A+), within the 0.85–1.4 range of the settings. */
internal val READER_TEXT_SCALES: List<Float> = listOf(0.85f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f)

/** The next larger/smaller step from [current] (clamped to the ends). */
internal fun nextTextScale(current: Float, larger: Boolean): Float {
    val epsilon = 0.001f
    return if (larger) {
        READER_TEXT_SCALES.firstOrNull { it > current + epsilon } ?: READER_TEXT_SCALES.last()
    } else {
        READER_TEXT_SCALES.lastOrNull { it < current - epsilon } ?: READER_TEXT_SCALES.first()
    }
}

private val WHITESPACE = Regex("\\s+")

/** The two parts of a feed meta line; either may be missing. */
internal data class FeedMetaParts(val time: String?, val source: String?)

/** "vor 3 Std." and "Handelsblatt" separately, so the time never gets shortened. */
internal fun feedMetaParts(item: NewsItem, now: ZonedDateTime): FeedMetaParts {
    val time = Fmt.relative(item.publishedAt, now)
    val source = item.source.trim().ifEmpty { sourcesOf(item).firstOrNull()?.let(::sourceLabel).orEmpty() }
    return FeedMetaParts(time = time, source = source.ifEmpty { null })
}
