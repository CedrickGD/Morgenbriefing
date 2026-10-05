package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** "⚡ Live" shows what was published within this window (docs/AI.md §2). */
internal val LIVE_WINDOW: Duration = Duration.ofHours(6)

/** Publish times this far in the future still count (clock skew between servers and phone). */
private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(15)

/** Color of topics the app does not know (newer routine, live research). */
private const val NEUTRAL_TOPIC_SEED = 0xFF5F6B7A

/**
 * One article as the feed shows it: with its topic and the date of the brief it belongs to.
 *
 * @property date `YYYY-MM-DD` of the brief — or of today for live articles; opens the reader.
 * @property live found by a live research run (docs/AI.md §2), not part of the morning brief.
 * @property fetchedAt for live articles: when the run found it (ISO-8601).
 */
@Immutable
data class FeedArticle(
    val item: NewsItem,
    val topic: Topic,
    val date: String,
    val live: Boolean = false,
    val fetchedAt: String? = null,
) {
    /** Unique within one feed (a live article may share its key with nothing in the brief). */
    val feedKey: String get() = (if (live) "live:" else "brief:") + item.key
}

/** What the chip row above the feed filters by. */
@Immutable
sealed interface FeedFilter {
    /** Stable id for saving the selection (rememberSaveable). */
    val key: String

    /** Every visible topic as its own section, in the user's order. */
    data object ForYou : FeedFilter {
        override val key: String = "foryou"
    }

    /** The most important stories across all visible topics. */
    data object Top : FeedFilter {
        override val key: String = "top"
    }

    /** Only what is fresh: the last six hours of the brief plus live research. */
    data object Live : FeedFilter {
        override val key: String = "live"
    }

    /** All articles of one topic. */
    data class OneTopic(val topicId: String) : FeedFilter {
        override val key: String get() = TOPIC_PREFIX + topicId
    }

    companion object {
        private const val TOPIC_PREFIX = "topic:"

        fun fromKey(key: String?): FeedFilter = when {
            key == Top.key -> Top
            key == Live.key -> Live
            key != null && key.startsWith(TOPIC_PREFIX) && key.length > TOPIC_PREFIX.length ->
                OneTopic(key.removePrefix(TOPIC_PREFIX))
            else -> ForYou
        }
    }
}

/** One chip of the chip row. [topic] is set for topic chips (their color and emoji). */
@Immutable
data class FeedChip(val filter: FeedFilter, val label: String, val emoji: String? = null, val topic: Topic? = null)

/** "Für dich", "Top", "⚡ Live", then one chip per visible topic of [feed] (user order). Pure. */
internal fun feedChips(feed: NewsFeed?): List<FeedChip> = buildList {
    add(FeedChip(FeedFilter.ForYou, "Für dich"))
    add(FeedChip(FeedFilter.Top, "Top"))
    add(FeedChip(FeedFilter.Live, "Live", emoji = "⚡"))
    feed?.sections
        ?.distinctBy { it.id }
        ?.forEach { section -> add(FeedChip(FeedFilter.OneTopic(section.id), section.topic.title, section.topic.emoji, section.topic)) }
}

/** [selected] when it is still offered, else "Für dich" (e.g. after its topic was hidden). */
internal fun resolveFilter(selected: FeedFilter, chips: List<FeedChip>): FeedFilter =
    if (chips.any { it.filter == selected }) selected else FeedFilter.ForYou

/** The section a topic chip shows; null when it is not part of the feed (anymore). */
internal fun NewsFeed.sectionFor(topicId: String): SectionUi? = sections.firstOrNull { it.id == topicId }

/** The topic of a live article: the catalogue entry, or a neutral one for unknown ids. */
internal fun liveTopic(topicId: String): Topic =
    Topics.byId(topicId) ?: Topic(topicId, topicId.replaceFirstChar { it.uppercase() }, "📰", NEUTRAL_TOPIC_SEED)

/**
 * The "⚡ Live" view: articles published within [LIVE_WINDOW] before [now] — from the visible
 * topics of [brief] and from today's [live] research — newest first. The same story (same key
 * or link) appears once; the newer copy wins, a live copy on a tie. Pure.
 *
 * @param today `YYYY-MM-DD`, the date live articles are filed under.
 */
internal fun liveFeed(
    brief: Brief?,
    prefs: TopicPrefs,
    live: List<LiveArticle>,
    now: ZonedDateTime,
    today: String,
): List<FeedArticle> {
    val reference = now.toInstant()
    val candidates = mutableListOf<Pair<FeedArticle, Instant>>()

    live.forEach { article ->
        if (article.topicId in prefs.hidden) return@forEach
        // Publish time when known and readable, else when the run found it.
        val published = parseInstantOrNull(article.item.publishedAt) ?: parseInstantOrNull(article.fetchedAt)
        val at = published?.takeIf { isFresh(it, reference) } ?: return@forEach
        candidates += FeedArticle(article.item, liveTopic(article.topicId), today, live = true, fetchedAt = article.fetchedAt) to at
    }
    if (brief != null) {
        prefs.arrange(brief.sections).forEach { section ->
            val topic = Topics.forSection(section)
            section.items.forEach { item ->
                val at = parseInstantOrNull(item.publishedAt)?.takeIf { isFresh(it, reference) } ?: return@forEach
                candidates += FeedArticle(item, topic, brief.date) to at
            }
        }
    }

    // Newest first; on equal times live research before the brief (stable sort keeps live first).
    val sorted = candidates.sortedByDescending { it.second }
    val seenKeys = HashSet<String>()
    val seenUrls = HashSet<String>()
    return sorted.mapNotNull { (article, _) ->
        val key = article.item.key
        val url = article.item.url.trim().lowercase()
        if (key in seenKeys || (url.isNotEmpty() && url in seenUrls)) return@mapNotNull null
        seenKeys += key
        if (url.isNotEmpty()) seenUrls += url
        article
    }
}

/** True when [at] lies within the live window before [now] (or just slightly after it). */
private fun isFresh(at: Instant, now: Instant): Boolean {
    val age = Duration.between(at, now)
    return age <= LIVE_WINDOW && age >= FUTURE_TOLERANCE.negated()
}

/** "⚡ neu · vor 12 Min." for live articles (by publish time, else by when they were found). */
internal fun liveBadge(article: FeedArticle, now: ZonedDateTime): String {
    val relative = Fmt.relative(article.item.publishedAt, now) ?: Fmt.relative(article.fetchedAt, now)
    return if (relative == null) "⚡ neu" else "⚡ neu · $relative"
}

/**
 * The preferences after showing or hiding [id]. Null when hiding it would switch off the last
 * visible topic of the catalogue — at least one always stays on (same rule as the settings).
 */
internal fun TopicPrefs.withTopicVisible(id: String, visible: Boolean): TopicPrefs? {
    if (visible) return if (id in hidden) copy(hidden = hidden - id) else this
    if (id in hidden) return this
    val othersVisible = order.any { it != id && it !in hidden }
    if (id in order && !othersVisible) return null
    return copy(hidden = hidden + id)
}

/** Topics of the catalogue in the user's order (unknown ids dropped, missing ones appended). */
internal fun TopicPrefs.catalogueOrder(): List<Topic> {
    val known = Topics.ALL.associateBy { it.id }
    val ordered = order.mapNotNull { known[it] }.distinct()
    return ordered + Topics.ALL.filterNot { it in ordered }
}

/** "1 Meldung" / "4 Meldungen". */
internal fun articleCountLabel(count: Int): String = if (count == 1) "1 Meldung" else "$count Meldungen"
