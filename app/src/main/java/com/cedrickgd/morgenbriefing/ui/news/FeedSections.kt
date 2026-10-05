package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.entrance
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.components.rememberEntrance
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import java.time.ZonedDateTime

/** Side margin of feed content (docs/DESIGN.md §5: 16 dp). */
internal val FeedGutter: Dp = 16.dp

/** Extra space below a feed entry on top of the list spacing (16 + 14 = 30 dp between entries). */
private val EntryGap = 14.dp

/**
 * Glass header of a topic: its emoji in a circle tinted with the topic color, the title and
 * the number of articles.
 */
@Composable
fun FeedSectionHeader(topic: Topic, count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val countLabel = articleCountLabel(count)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = GlassShapes.pill, level = GlassLevel.Thin)
            .heightIn(min = 56.dp)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopicBadge(topic, diameter = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = topic.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .background(colors.onSurface.copy(alpha = 0.07f), GlassShapes.pill)
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .clearAndSetSemantics { contentDescription = countLabel },
        )
    }
}

/**
 * A topic's "more" articles behind a glass "+ Mehr zu … (n)" button. Expanding springs them
 * open above the button (compact rows), which then reads "Weniger anzeigen".
 */
@Composable
fun MoreArticles(
    section: SectionUi,
    date: String,
    expanded: Boolean,
    now: ZonedDateTime,
    onToggle: () -> Unit,
    onOpen: (FeedArticle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = motionDisabled()
    val topicColors = rememberTopicColors(section.topic)
    val dark = isDarkTheme()
    val labelColor = if (dark) topicColors.onContainer else topicColors.strong
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (still) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "moreChevron",
    )
    val articles = remember(section, date) { section.more.map { FeedArticle(it, section.topic, date) } }
    val interaction = remember { MutableInteractionSource() }

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = expanded,
            enter = if (still) {
                EnterTransition.None
            } else {
                expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Top) +
                    fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            exit = if (still) {
                ExitTransition.None
            } else {
                shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium), shrinkTowards = Alignment.Top) +
                    fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
            label = "moreArticles",
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                articles.forEachIndexed { index, article ->
                    key(index, article.item.key) {
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)),
                            )
                        }
                        val entrance = rememberEntrance(delayMillis = 50 * index, durationMillis = 420)
                        FeedRow(article = article, now = now, onOpen = onOpen, modifier = Modifier.entrance(entrance, distance = 10.dp))
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .pressScale(interaction, pressedScale = 0.98f)
                .glass(shape = GlassShapes.pill, level = GlassLevel.Thin)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics { stateDescription = if (expanded) "ausgeklappt" else "eingeklappt" }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = moreButtonLabel(section.topic.title, section.more.size, expanded),
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/** A small overline + serif title introducing a feed view ("Top", a topic). */
@Composable
fun FeedViewIntro(overline: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = overline.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * "Für dich": every visible topic with a glass header, its top articles as editorial entries
 * and the expandable "more" block.
 *
 * @param date the brief's date (opens the reader).
 * @param listeningKey `NewsItem.key` of the article being read aloud, if any.
 * @param keyPrefix keeps keys unique when several feeds share a list.
 */
internal fun LazyListScope.forYouItems(
    sections: List<SectionUi>,
    date: String,
    now: ZonedDateTime,
    expanded: Set<String>,
    listeningKey: String?,
    actions: FeedActions,
    onToggleSection: (String) -> Unit,
    keyPrefix: String,
) {
    sections.forEach { section ->
        val sectionKey = "$keyPrefix-s${section.index}-${section.id}"
        item(key = "$sectionKey-header", contentType = "feed-section-header") {
            FeedSectionHeader(
                topic = section.topic,
                count = section.count,
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = FeedGutter)
                    .padding(top = 8.dp, bottom = 4.dp),
            )
        }
        section.top.forEachIndexed { index, item ->
            item(key = "$sectionKey-top-$index-${item.key}", contentType = "feed-entry") {
                val article = remember(item, section.topic, date) { FeedArticle(item, section.topic, date) }
                AnimatedFeedEntry(article, now, listeningKey == item.key, actions)
            }
        }
        if (section.hasMore) {
            item(key = "$sectionKey-more", contentType = "feed-more") {
                MoreArticles(
                    section = section,
                    date = date,
                    expanded = section.isExpanded(expanded),
                    now = now,
                    onToggle = { onToggleSection(section.id) },
                    onOpen = actions.onOpen,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = FeedGutter)
                        .padding(bottom = EntryGap),
                )
            }
        }
    }
}

/**
 * A plain list of editorial entries ("Top", a topic, "⚡ Live").
 *
 * @param showTopic topic overline above each headline (mixed views).
 * @param badge per-article meta badge, e.g. "⚡ neu · vor 12 Min.".
 */
internal fun LazyListScope.articleItems(
    articles: List<FeedArticle>,
    now: ZonedDateTime,
    listeningKey: String?,
    actions: FeedActions,
    keyPrefix: String,
    showTopic: Boolean,
    badge: (FeedArticle) -> String? = { null },
) {
    articles.forEachIndexed { index, article ->
        item(key = "$keyPrefix-$index-${article.feedKey}", contentType = "feed-entry") {
            AnimatedFeedEntry(article, now, listeningKey == article.item.key, actions, showTopic, badge(article))
        }
    }
}

/** A [FeedEntry] that rises in once when it first scrolls into view. */
@Composable
private fun LazyItemScope.AnimatedFeedEntry(
    article: FeedArticle,
    now: ZonedDateTime,
    listening: Boolean,
    actions: FeedActions,
    showTopic: Boolean = false,
    badge: String? = null,
) {
    val entrance = rememberEntrance(durationMillis = 460)
    FeedEntry(
        article = article,
        now = now,
        listening = listening,
        actions = actions,
        showTopic = showTopic,
        badge = badge,
        modifier = Modifier
            .animateItem()
            .padding(horizontal = FeedGutter)
            .padding(bottom = EntryGap)
            .entrance(entrance, distance = 18.dp),
    )
}
