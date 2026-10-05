package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import java.time.ZonedDateTime

/** Corner radius of article cards (design rule: 24 dp). */
internal val CardShape = RoundedCornerShape(24.dp)

/** Large cards show pictures in this ratio. */
private const val CARD_IMAGE_RATIO = 16f / 10f

/** Bottom scrim under headlines on pictures: clear at the top, dark where the text sits. */
internal val HeadlineScrim: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.10f),
    0.3f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.20f),
    0.75f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.82f),
)

/** White text with a soft shadow, readable on any picture behind [HeadlineScrim]. */
internal fun TextStyle.onPicture(): TextStyle = copy(
    color = Color.White,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.45f), offset = Offset(0f, 2f), blurRadius = 10f),
)

/** Small rounded chip with the topic's emoji and title, tinted with the topic color. */
@Composable
fun TopicChip(topic: Topic, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = rememberTopicColors(topic)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (compact) 8.dp else 10.dp,
                end = if (compact) 10.dp else 12.dp,
                top = if (compact) 3.dp else 5.dp,
                bottom = if (compact) 3.dp else 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (topic.emoji.isNotBlank()) {
                Emoji(topic.emoji, size = if (compact) 12.sp else 14.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = topic.title,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Large article card: picture (or cover) at 16:10 with the headline big on top of it, the lead
 * and a meta line below. The whole card opens the reader; the share button shares the article.
 */
@Composable
fun ArticleCard(
    item: NewsItem,
    topic: Topic,
    now: ZonedDateTime,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    showTopicChip: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val meta = remember(item, now) { articleMeta(item, now) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.clickable(onClickLabel = "Artikel lesen", role = Role.Button, onClick = onOpen)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CARD_IMAGE_RATIO),
            ) {
                ArticleVisual(item = item, topic = topic, modifier = Modifier.matchParentSize())
                Box(
                    Modifier
                        .matchParentSize()
                        .background(HeadlineScrim),
                )
                if (showTopicChip) {
                    TopicChip(
                        topic = topic,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp),
                    )
                }
                Text(
                    text = item.headline,
                    style = typography.headlineSmall.onPicture(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                )
            }
            Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 4.dp)) {
                if (item.summary.isNotBlank()) {
                    Text(
                        text = item.summary,
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                MetaRow(meta = meta, onShare = onShare)
            }
        }
    }
}

@Composable
private fun MetaRow(meta: String, onShare: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = meta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = "Teilen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact row for the "more" articles: 88 dp thumbnail, two-line headline, meta line. */
@Composable
fun ArticleRow(
    item: NewsItem,
    topic: Topic,
    now: ZonedDateTime,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = remember(item, now) { articleMeta(item, now) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Artikel lesen", role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArticleVisual(
            item = item,
            topic = topic,
            modifier = Modifier
                .size(ThumbnailSize)
                .clip(RoundedCornerShape(16.dp)),
            emojiAlignment = Alignment.Center,
            emojiFraction = 0.42f,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.headline,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The "more" articles of a section as one rounded card with dividers between the rows. */
@Composable
fun ArticleRowGroup(
    items: List<NewsItem>,
    topic: Topic,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp + ThumbnailSize + 14.dp, end = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
                ArticleRow(item = item, topic = topic, now = now, onOpen = { onOpen(item) })
            }
        }
    }
}

/** Big emoji in a circle tinted with the topic color (section headers). */
@Composable
internal fun TopicBadge(topic: Topic, modifier: Modifier = Modifier, size: TextUnit = 20.sp) {
    val colors = rememberTopicColors(topic)
    Box(
        modifier = modifier
            .size(40.dp)
            .background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Emoji(topic.emoji.ifBlank { "📰" }, size = size)
    }
}

private val ThumbnailSize = 88.dp
