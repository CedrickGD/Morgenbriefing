package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.NewsAge
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.TopicNews
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** A topic keeps at least this many articles on screen even when the age filter leaves fewer. */
internal const val MIN_PER_TOPIC = 3

/** The user's feed settings that shape what the news screens show. */
@Immutable
data class FeedPrefs(
    /** Visible topics in the user's order. */
    val topics: List<Topic>,
    val sectionSize: Int = AppSettings.DEFAULT_SECTION_SIZE,
    val newsAge: NewsAge = NewsAge.DAY,
    val showImages: Boolean = true,
    /** Every topic the user has, hidden ones included (for the topic sheet). */
    val catalogue: List<Topic> = topics,
    val hidden: Set<String> = emptySet(),
    /** Sources the user switched off; their articles never show, even before the next fetch. */
    val disabledFeeds: Set<String> = emptySet(),
) {
    constructor(settings: AppSettings) : this(
        topics = Topics.visible(settings),
        sectionSize = settings.sectionSize,
        newsAge = settings.newsAge,
        showImages = settings.showImages,
        catalogue = Topics.catalogue(settings),
        hidden = settings.hiddenTopics,
        disabledFeeds = settings.disabledFeeds,
    )
}

/** One article as the feed shows it, with its topic. */
@Immutable
data class FeedArticle(val item: NewsItem, val topic: Topic) {
    val feedKey: String get() = item.key
}

/**
 * One topic of the feed, ready to render: [top] opens "Für dich", [all] is what the topic's
 * own chip scrolls through (endless, newest first).
 *
 * @property index position in the arranged list — keeps lazy-list keys unique.
 */
@Immutable
data class SectionUi(
    val index: Int,
    val topic: Topic,
    val all: List<NewsItem>,
    val top: List<NewsItem>,
    /** When these articles were fetched (ISO-8601) or null when the topic was never fetched. */
    val fetchedAt: String?,
    /** Sources that failed last time, by name. */
    val failedSources: List<String> = emptyList(),
) {
    val id: String get() = topic.id
    val count: Int get() = all.size
    val hasMore: Boolean get() = all.size > top.size
    val isEmpty: Boolean get() = all.isEmpty()
}

/** Everything the news part of "Heute" shows. */
@Immutable
data class NewsFeed(
    /** The visible topics in the user's order (also the ones without articles). */
    val sections: List<SectionUi>,
    /** Every article of every visible topic, newest first ("Neueste"). */
    val latest: List<FeedArticle>,
    /** The newest fetch time over all visible topics. */
    val fetchedAt: Instant?,
) {
    val isEmpty: Boolean get() = latest.isEmpty()
    val articleCount: Int get() = latest.size

    /** Topics that have something to show. */
    val filledSections: List<SectionUi> get() = sections.filter { !it.isEmpty }
}

/**
 * Builds the feed for the visible topics of [prefs] from the repository's [news]. Pure.
 *
 * @param now reference for the age filter.
 */
internal fun buildNewsFeed(news: Map<String, TopicNews>, prefs: FeedPrefs, now: ZonedDateTime): NewsFeed {
    val reference = now.toInstant()
    val sections = prefs.topics.mapIndexed { index, topic ->
        val topicNews = news[topic.id]
        val items = filterByAge(topicNews?.items.orEmpty().filter { it.sourceId !in prefs.disabledFeeds }, prefs.newsAge, reference)
        SectionUi(
            index = index,
            topic = topic,
            all = items,
            top = items.take(prefs.sectionSize.coerceAtLeast(1)),
            fetchedAt = topicNews?.fetchedAt,
            failedSources = topicNews?.failedSources?.map { it.name }.orEmpty(),
        )
    }
    val seen = HashSet<String>()
    val latest = sections
        .flatMap { section -> section.all.map { FeedArticle(it, section.topic) } }
        .filter { seen.add(it.item.key) }
        .sortedWith(compareByDescending<FeedArticle> { parseInstantOrNull(it.item.publishedAt) ?: Instant.MIN })
    return NewsFeed(
        sections = sections,
        latest = latest,
        fetchedAt = sections.mapNotNull { it.fetchedAt?.let(::parseInstantOrNull) }.maxOrNull(),
    )
}

/**
 * The articles within [age] (newest first, as stored). A topic never shows fewer than
 * [MIN_PER_TOPIC] articles when it has them: a quiet newsroom still gets its latest stories.
 * Undated articles count as fresh (the feed order is all there is).
 */
internal fun filterByAge(items: List<NewsItem>, age: NewsAge, now: Instant, minimum: Int = MIN_PER_TOPIC): List<NewsItem> {
    if (age.hours <= 0) return items
    val window = Duration.ofHours(age.hours.toLong())
    val within = items.filter { item ->
        val at = parseInstantOrNull(item.publishedAt) ?: return@filter true
        Duration.between(at, now) <= window
    }
    return if (within.size >= minimum) within else items.take(minimum)
}

/** "1 Meldung" / "4 Meldungen". */
internal fun articleCountLabel(count: Int): String = if (count == 1) "1 Meldung" else "$count Meldungen"

/** Label of a section's "all articles" button. */
internal fun moreButtonLabel(title: String, total: Int): String = "Alle $total Meldungen zu $title"

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

/** The two parts of a feed meta line; either may be missing. */
internal data class FeedMetaParts(val time: String?, val source: String?)

/** "vor 3 Std." and "tagesschau" separately, so the time never gets shortened. */
internal fun feedMetaParts(item: NewsItem, now: ZonedDateTime): FeedMetaParts {
    val time = Fmt.relative(item.publishedAt, now)
    val source = item.source.trim().ifEmpty { hostOf(item.url) }
    return FeedMetaParts(time = time, source = source.ifEmpty { null })
}

/** "vor 3 Std. · tagesschau" — unknown parts are left out. */
internal fun feedMeta(item: NewsItem, now: ZonedDateTime): String {
    val parts = feedMetaParts(item, now)
    return listOfNotNull(parts.time, parts.source).joinToString(" · ")
}

/** "Stand 07:12" for the feed's title line; empty when nothing was fetched yet. */
internal fun fetchedLabel(fetchedAt: Instant?): String {
    val at = fetchedAt ?: return ""
    return Fmt.clock(at.toString())
}
