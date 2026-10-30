package com.cedrickgd.morgenbriefing.ui.news

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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.entrance
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
 * Opens a topic in the feed like a newspaper section: a hairline, then "🤖 KI & TECH" in the
 * topic's color on the left and "37 Meldungen" on the right — straight on the sky.
 */
@Composable
fun FeedSectionHeader(topic: Topic, count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val topicColors = rememberTopicColors(topic)
    val tint = if (isDarkTheme()) topicColors.onContainer else topicColors.strong
    val countLabel = articleCountLabel(count)
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.onSurface.copy(alpha = 0.10f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Emoji(topic.emoji.ifBlank { "📰" }, size = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = topic.title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = OverlineLetterSpacing, fontWeight = FontWeight.SemiBold),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Glass pill "Alle 37 Meldungen zu KI & Tech →" that opens the topic's own, endless view. */
@Composable
fun AllArticlesButton(section: SectionUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val topicColors = rememberTopicColors(section.topic)
    val labelColor = if (isDarkTheme()) topicColors.onContainer else topicColors.strong
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressScale(interaction, pressedScale = 0.98f)
            .glass(shape = GlassShapes.pill, level = GlassLevel.Thin)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = moreButtonLabel(section.topic.title, section.count),
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = labelColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A small overline + serif title introducing a feed view ("Neueste", a topic). */
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

/** A quiet line inside a section that has nothing to show right now. */
@Composable
fun EmptySectionNote(text: String, loading: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The calm end of an endless list: "Das war's · 128 Meldungen aus 9 Quellen". */
@Composable
fun ListEndNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
    )
}

/**
 * "Für dich": every visible topic with its header, its first articles as editorial entries
 * and the "Alle … Meldungen" pill that opens the topic's own chip.
 *
 * @param loadingTopics topics whose refresh is running (empty sections say "lade …").
 * @param keyPrefix keeps keys unique when several feeds share a list.
 */
internal fun LazyListScope.forYouItems(
    sections: List<SectionUi>,
    loadingTopics: Set<String>,
    now: ZonedDateTime,
    listening: NowListening?,
    actions: FeedActions,
    showImages: Boolean,
    onShowAll: (SectionUi) -> Unit,
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
                    .padding(top = 12.dp),
            )
        }
        if (section.isEmpty) {
            item(key = "$sectionKey-empty", contentType = "feed-empty") {
                val loading = section.id in loadingTopics
                EmptySectionNote(
                    text = when {
                        loading -> "Hole die Meldungen …"
                        section.failedSources.isNotEmpty() -> "Gerade nicht erreichbar: ${section.failedSources.joinToString(", ")}"
                        section.fetchedAt == null -> "Noch nichts geladen – zieh nach unten zum Aktualisieren."
                        else -> "Heute noch nichts Neues."
                    },
                    loading = loading,
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                )
            }
            return@forEach
        }
        section.top.forEachIndexed { index, item ->
            item(key = "$sectionKey-top-$index-${item.key}", contentType = "feed-entry") {
                val article = remember(item, section.topic) { FeedArticle(item, section.topic) }
                AnimatedFeedEntry(article, now, listening.reading(item.key), listening.pausedOn(item.key), actions, showImages)
            }
        }
        if (section.hasMore) {
            item(key = "$sectionKey-more", contentType = "feed-more") {
                AllArticlesButton(
                    section = section,
                    onClick = { onShowAll(section) },
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
 * A plain list of editorial entries ("Neueste", a topic).
 *
 * @param showTopic topic overline above each headline (mixed views).
 */
internal fun LazyListScope.articleItems(
    articles: List<FeedArticle>,
    now: ZonedDateTime,
    listening: NowListening?,
    actions: FeedActions,
    showImages: Boolean,
    keyPrefix: String,
    showTopic: Boolean,
) {
    articles.forEachIndexed { index, article ->
        item(key = "$keyPrefix-$index-${article.feedKey}", contentType = "feed-entry") {
            AnimatedFeedEntry(article, now, listening.reading(article.item.key), listening.pausedOn(article.item.key), actions, showImages, showTopic)
        }
    }
}

/** A [FeedEntry] that rises in once when it first scrolls into view. */
@Composable
private fun LazyItemScope.AnimatedFeedEntry(
    article: FeedArticle,
    now: ZonedDateTime,
    listening: Boolean,
    paused: Boolean,
    actions: FeedActions,
    showImage: Boolean,
    showTopic: Boolean = false,
) {
    val entrance = rememberEntrance(durationMillis = 460)
    FeedEntry(
        article = article,
        now = now,
        listening = listening,
        paused = paused,
        actions = actions,
        showTopic = showTopic,
        showImage = showImage,
        modifier = Modifier
            .animateItem()
            .padding(horizontal = FeedGutter)
            .padding(bottom = EntryGap)
            .entrance(entrance, distance = 18.dp),
    )
}
