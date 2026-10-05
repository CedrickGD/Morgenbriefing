package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import java.time.ZonedDateTime

/** How much of the next story peeks in from the right. */
private val PeekWidth = 40.dp
private val PageSpacing = 16.dp

/**
 * "Heute wichtig": the most important stories as big picture cards in a horizontal pager
 * (the next page peeks in), with page dots below. Renders nothing for an empty list.
 *
 * @param bleed horizontal padding of the surrounding list; the pager extends over it so the
 *   cards can scroll in from the screen edge.
 */
@Composable
fun TopStories(
    stories: List<TopStory>,
    title: String,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
    bleed: Dp = 16.dp,
) {
    if (stories.isEmpty()) return
    val pagerState = rememberPagerState { stories.size }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Emoji("⭐", size = 19.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .bleedHorizontally(bleed),
            contentPadding = PaddingValues(start = bleed, end = bleed + PeekWidth),
            pageSize = TopStoryPageSize,
            pageSpacing = PageSpacing,
            beyondViewportPageCount = 1,
        ) { page ->
            val story = stories[page]
            TopStoryCard(story = story, now = now, onOpen = { onOpen(story.item) })
        }
        Spacer(Modifier.height(14.dp))
        PagerDots(
            state = pagerState,
            count = stories.size,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun TopStoryCard(story: TopStory, now: ZonedDateTime, onOpen: () -> Unit) {
    val item = story.item
    val meta = remember(item, now) { articleMeta(item, now) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.clickable(onClickLabel = "Artikel lesen", role = Role.Button, onClick = onOpen)) {
            ArticleVisual(item = item, topic = story.topic, modifier = Modifier.matchParentSize())
            Box(
                Modifier
                    .matchParentSize()
                    .background(HeadlineScrim),
            )
            TopicChip(
                topic = story.topic,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            ) {
                Text(
                    text = item.headline,
                    style = MaterialTheme.typography.headlineSmall.onPicture(),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium.onPicture().copy(color = Color.White.copy(alpha = 0.88f)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Dots under the pager; the current one is a wider pill. Decorative for screen readers. */
@Composable
private fun PagerDots(state: PagerState, count: Int, modifier: Modifier = Modifier) {
    val still = motionDisabled()
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = state.currentPage == index
            val width by animateDpAsState(
                targetValue = if (selected) 20.dp else 6.dp,
                animationSpec = if (still) snap() else tween(250),
                label = "dotWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                animationSpec = if (still) snap() else tween(250),
                label = "dotColor",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Full width on phones, capped on tablets/landscape so several stories fit side by side. */
private object TopStoryPageSize : PageSize {
    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
        minOf(availableSpace, 420.dp.roundToPx())
}

/**
 * Lets a child of a padded list extend [amount] into the list's horizontal padding on both
 * sides (the list clips at its own bounds, which include that padding).
 */
internal fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val wider = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
    )
    val placeable = measurable.measure(wider)
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-amount.roundToPx(), 0) }
}
