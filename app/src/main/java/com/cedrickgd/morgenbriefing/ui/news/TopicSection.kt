package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.ui.components.Emphasized
import com.cedrickgd.morgenbriefing.ui.components.Pill
import com.cedrickgd.morgenbriefing.ui.components.entrance
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.rememberEntrance
import java.time.ZonedDateTime

/** Extra space above a topic header, on top of the list's item spacing. */
private val SectionGap = 16.dp

/** Header of a topic: emoji in a topic-tinted circle, title and the number of articles. */
@Composable
fun TopicHeader(section: SectionUi, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopicBadge(section.topic)
        Spacer(Modifier.width(12.dp))
        Text(
            text = section.topic.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        val count = section.count
        Pill(
            text = count.toString(),
            container = colors.surfaceContainerHigh,
            content = colors.onSurfaceVariant,
            textStyle = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = if (count == 1) "1 Meldung" else "$count Meldungen"
            },
        )
    }
}

/**
 * The section's "more" articles behind a topic-tinted "+ Mehr zu … (n)" button. Expanding
 * reveals them as compact rows above the button, which then reads "Weniger anzeigen".
 */
@Composable
fun MoreArticles(
    section: SectionUi,
    expanded: Boolean,
    now: ZonedDateTime,
    onToggle: () -> Unit,
    onOpen: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = motionDisabled()
    val topicColors = rememberTopicColors(section.topic)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (still) snap() else tween(280, easing = Emphasized),
        label = "moreChevron",
    )

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = expanded,
            enter = if (still) {
                EnterTransition.None
            } else {
                expandVertically(tween(340, easing = Emphasized), expandFrom = Alignment.Top) +
                    fadeIn(tween(260, delayMillis = 60))
            },
            exit = if (still) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(280, easing = Emphasized), shrinkTowards = Alignment.Top) + fadeOut(tween(160))
            },
            label = "moreArticles",
        ) {
            ArticleRowGroup(
                items = section.more,
                topic = section.topic,
                now = now,
                onOpen = onOpen,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        FilledTonalButton(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { stateDescription = if (expanded) "ausgeklappt" else "eingeklappt" },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = topicColors.container,
                contentColor = topicColors.onContainer,
            ),
        ) {
            Text(
                text = moreButtonLabel(section.topic.title, section.more.size, expanded),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/**
 * One topic as a plain column (header, large cards, "more" block) — for previews and non-lazy
 * hosts. Screens use [newsFeedItems], which emits the same pieces as lazy items.
 */
@Composable
fun TopicSection(
    section: SectionUi,
    expanded: Boolean,
    now: ZonedDateTime,
    onToggle: () -> Unit,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopicHeader(section)
        section.top.forEachIndexed { index, item ->
            key(index, item.key) {
                ArticleCard(
                    item = item,
                    topic = section.topic,
                    now = now,
                    onOpen = { onOpen(item) },
                    onShare = { onShare(item) },
                )
            }
        }
        if (section.hasMore) {
            MoreArticles(section = section, expanded = expanded, now = now, onToggle = onToggle, onOpen = onOpen)
        }
    }
}

/**
 * The news feed of a brief as lazy items: "Heute wichtig" pager, then per topic a header,
 * one large card per top article and the expandable "more" block.
 *
 * @param expanded ids of the sections whose "more" articles are open.
 * @param keyPrefix keeps keys unique when several feeds share a list.
 */
internal fun LazyListScope.newsFeedItems(
    feed: NewsFeed,
    now: ZonedDateTime,
    expanded: Set<String>,
    topStoriesTitle: String,
    onToggleSection: (String) -> Unit,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    keyPrefix: String,
) {
    if (feed.topStories.isNotEmpty()) {
        item(key = "$keyPrefix-top-stories", contentType = "top-stories") {
            TopStories(
                stories = feed.topStories,
                title = topStoriesTitle,
                now = now,
                onOpen = onOpen,
                modifier = Modifier
                    .animateItem()
                    .padding(top = 8.dp),
            )
        }
    }

    feed.sections.forEach { section ->
        val sectionKey = "$keyPrefix-s${section.index}-${section.id}"
        item(key = "$sectionKey-header", contentType = "topic-header") {
            TopicHeader(
                section = section,
                modifier = Modifier
                    .animateItem()
                    .padding(top = SectionGap),
            )
        }
        section.top.forEachIndexed { index, item ->
            item(key = "$sectionKey-top-$index", contentType = "article-card") {
                val entrance = rememberEntrance(durationMillis = 380)
                ArticleCard(
                    item = item,
                    topic = section.topic,
                    now = now,
                    onOpen = { onOpen(item) },
                    onShare = { onShare(item) },
                    modifier = Modifier
                        .animateItem()
                        .entrance(entrance, distance = 14.dp),
                )
            }
        }
        if (section.hasMore) {
            item(key = "$sectionKey-more", contentType = "more-articles") {
                MoreArticles(
                    section = section,
                    expanded = section.isExpanded(expanded),
                    now = now,
                    onToggle = { onToggleSection(section.id) },
                    onOpen = onOpen,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
