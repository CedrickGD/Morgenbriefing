package com.cedrickgd.morgenbriefing.ui.brief

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.NewsTeaser
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.newsFeedItems
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import java.time.LocalDate
import java.time.ZonedDateTime

/** True when [brief] belongs to [day] (by its Berlin date). Malformed dates count as "not". */
internal fun isBriefFor(brief: Brief, day: LocalDate): Boolean = parseDateOrNull(brief.date) == day

/** Copy of the card shown when a brief has no news at all. */
internal data class EmptyNewsCopy(val emoji: String, val title: String, val text: String)

/** Teaser card for a brief; [stale] shows the "not today's" hint. */
internal fun LazyListScope.briefTeaserItem(brief: Brief, stale: Boolean, keyPrefix: String) {
    item(key = "$keyPrefix-teaser", contentType = "teaser") {
        NewsTeaser(
            teaser = brief.teaser,
            generatedAt = brief.generatedAt,
            stale = stale,
            modifier = Modifier.animateItem(),
        )
    }
}

/**
 * The news part of a screen for one brief: teaser, then the feed ("Heute wichtig" pager and the
 * topic sections) — or a friendly card when there is nothing to show.
 *
 * @param feed [brief] arranged with the user's topic settings (see `buildNewsFeed`).
 * @param onOpenSettings offered when the user switched off every topic of this brief.
 */
internal fun LazyListScope.briefNewsItems(
    brief: Brief,
    feed: NewsFeed,
    stale: Boolean,
    now: ZonedDateTime,
    expanded: Set<String>,
    topStoriesTitle: String,
    empty: EmptyNewsCopy,
    onToggleSection: (String) -> Unit,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    keyPrefix: String,
    onOpenSettings: (() -> Unit)? = null,
) {
    briefTeaserItem(brief = brief, stale = stale, keyPrefix = keyPrefix)
    when {
        feed.allHidden -> item(key = "$keyPrefix-all-hidden", contentType = "info") {
            InfoCard(
                emoji = "🙈",
                title = "Alle Themen ausgeblendet",
                text = "In diesem Briefing stehen nur Themen, die du ausgeblendet hast. " +
                    "In den Einstellungen kannst du sie wieder einschalten.",
                modifier = Modifier.animateItem(),
                actionLabel = onOpenSettings?.let { "Themen wählen" },
                onAction = onOpenSettings,
            )
        }

        feed.isEmpty -> item(key = "$keyPrefix-news-empty", contentType = "info") {
            InfoCard(
                emoji = empty.emoji,
                title = empty.title,
                text = empty.text,
                modifier = Modifier.animateItem(),
            )
        }

        else -> newsFeedItems(
            feed = feed,
            now = now,
            expanded = expanded,
            topStoriesTitle = topStoriesTitle,
            onToggleSection = onToggleSection,
            onOpen = onOpen,
            onShare = onShare,
            keyPrefix = keyPrefix,
        )
    }
}

/**
 * Ids of the sections whose "+ Mehr" block is open. Survives configuration changes and
 * navigating to the reader and back; starts closed again for a different brief ([briefKey]).
 */
@Composable
internal fun rememberExpandedSections(briefKey: String?): MutableState<Set<String>> =
    rememberSaveable(briefKey, saver = ExpandedSectionsSaver) { mutableStateOf(emptySet()) }

private val ExpandedSectionsSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)
