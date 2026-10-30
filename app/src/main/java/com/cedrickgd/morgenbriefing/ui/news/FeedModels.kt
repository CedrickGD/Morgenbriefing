package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.Topic

/** What the chip row above the feed filters by. */
@Immutable
sealed interface FeedFilter {
    /** Stable id for saving the selection (rememberSaveable). */
    val key: String

    /** Every visible topic as its own section, in the user's order. */
    data object ForYou : FeedFilter {
        override val key: String = "foryou"
    }

    /** Everything, newest first. */
    data object Latest : FeedFilter {
        override val key: String = "latest"
    }

    /** All articles of one topic, endless. */
    data class OneTopic(val topicId: String) : FeedFilter {
        override val key: String get() = TOPIC_PREFIX + topicId
    }

    companion object {
        private const val TOPIC_PREFIX = "topic:"

        fun fromKey(key: String?): FeedFilter = when {
            key == Latest.key -> Latest
            key != null && key.startsWith(TOPIC_PREFIX) && key.length > TOPIC_PREFIX.length ->
                OneTopic(key.removePrefix(TOPIC_PREFIX))
            else -> ForYou
        }
    }
}

/** One chip of the chip row. [topic] is set for topic chips (their color and emoji). */
@Immutable
data class FeedChip(val filter: FeedFilter, val label: String, val emoji: String? = null, val topic: Topic? = null)

/** "Für dich", "Neueste", then one chip per visible topic of [feed] (user order). Pure. */
internal fun feedChips(feed: NewsFeed?): List<FeedChip> = buildList {
    add(FeedChip(FeedFilter.ForYou, "Für dich"))
    add(FeedChip(FeedFilter.Latest, "Neueste", emoji = "✨"))
    feed?.sections
        ?.distinctBy { it.id }
        ?.forEach { section -> add(FeedChip(FeedFilter.OneTopic(section.id), section.topic.title, section.topic.emoji, section.topic)) }
}

/** [selected] when it is still offered, else "Für dich" (e.g. after its topic was hidden). */
internal fun resolveFilter(selected: FeedFilter, chips: List<FeedChip>): FeedFilter =
    if (chips.any { it.filter == selected }) selected else FeedFilter.ForYou

/** The section a topic chip shows; null when it is not part of the feed (anymore). */
internal fun NewsFeed.sectionFor(topicId: String): SectionUi? = sections.firstOrNull { it.id == topicId }

/**
 * How many articles an endless list reveals after the user reached its end [pages] times:
 * [pageSize] per step, never more than [total].
 */
internal fun revealedCount(pages: Int, total: Int, pageSize: Int = PAGE_SIZE): Int =
    (pages.coerceAtLeast(1) * pageSize).coerceAtMost(total)

/** Articles revealed per step of an endless list. */
internal const val PAGE_SIZE = 20
