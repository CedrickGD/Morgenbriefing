package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass

/** Width of the soft fade where the chips run out before the "Themen" button. */
private val ChipFadeWidth: Dp = 28.dp

/** Height of the chip row itself (chips are 40 dp, touch targets 48 dp). */
internal val ChipRowHeight: Dp = 56.dp

/**
 * The feed's chip row: "Für dich", "Top", "⚡ Live" and one chip per visible topic, as glass
 * pills scrolling horizontally (they win over the page swipe until their end), and a round
 * glass "Themen" button floating at the right end.
 *
 * @param liveRunning shows a pulsing dot on the "⚡ Live" chip while a live research runs.
 */
@Composable
fun FeedChipRow(
    chips: List<FeedChip>,
    selected: FeedFilter,
    liveRunning: Boolean,
    onSelect: (FeedFilter) -> Unit,
    onOpenTopics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowState = rememberLazyListState()
    val selectedIndex = chips.indexOfFirst { it.filter == selected }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val info = rowState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = visible != null &&
            visible.offset >= info.viewportStartOffset &&
            visible.offset + visible.size <= info.viewportEndOffset - TOPICS_BUTTON_ROOM_PX
        if (!fullyVisible) rowState.animateScrollToItem(selectedIndex, scrollOffset = -CHIP_SCROLL_MARGIN_PX)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(ChipRowHeight),
    ) {
        LazyRow(
            state = rowState,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                // The chips end left of the "Themen" button and fade out softly towards it.
                .padding(end = FeedGutter + 48.dp + 2.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = ChipFadeWidth.toPx()
                    if (size.width > fade) {
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Black,
                                (size.width - fade) / size.width to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .semantics { contentDescription = "Nachrichten filtern" },
            contentPadding = PaddingValues(start = FeedGutter, end = ChipFadeWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(chips, key = { _, chip -> chip.filter.key }) { _, chip ->
                FeedChipPill(
                    chip = chip,
                    selected = chip.filter == selected,
                    pulsing = liveRunning && chip.filter == FeedFilter.Live,
                    onClick = { onSelect(chip.filter) },
                )
            }
        }
        TopicsButton(
            onClick = onOpenTopics,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = FeedGutter),
        )
    }
}

@Composable
private fun FeedChipPill(chip: FeedChip, selected: Boolean, pulsing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val topicColors = chip.topic?.let { rememberTopicColors(it) }
    val selectedFill = when {
        chip.filter == FeedFilter.Live -> LiveColors.fill
        topicColors != null -> topicColors.strong
        else -> colors.primary
    }
    val selectedText = if (chip.topic == null && chip.filter != FeedFilter.Live) colors.onPrimary else Color.White
    val fill by animateColorAsState(
        targetValue = if (selected) selectedFill else selectedFill.copy(alpha = 0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chipFill",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) selectedText else colors.onSurface,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chipText",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .height(48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .pressScale(interaction, pressedScale = 0.94f)
                .then(if (selected) Modifier else Modifier.glass(shape = GlassShapes.pill, level = GlassLevel.Thin))
                .background(fill, CircleShape)
                .indication(interaction, ripple())
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chip.emoji != null && chip.emoji.isNotBlank()) {
                Emoji(chip.emoji, size = 15.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = chip.label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pulsing) {
                Spacer(Modifier.width(8.dp))
                PulsingDot(color = if (selected) Color.White else LiveColors.fill)
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val still = motionDisabled()
    val pulse = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "livePulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "livePulseAlpha",
        )
    }
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .background(color, CircleShape),
    )
}

/** Round glass button with a heart and a plus — opens the topic sheet. */
@Composable
private fun TopicsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
            .glass(shape = GlassShapes.pill, level = GlassLevel.Regular)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Themen auswählen",
                onClick = onClick,
            )
            .semantics { contentDescription = "Themen auswählen" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp)) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.TopStart),
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .background(colors.onSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.surface, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Makes a LazyColumn `stickyHeader` park [barHeightPx] below the top of the list — under the
 * TopGlassBar — instead of at the very top. The header's content must begin with a spacer of
 * that height: in the flow this part overhangs the item above (it is transparent and does not
 * take touches); while stuck it fills the room behind the bar. Reads the list state only in the
 * placement phase, so scrolling never recomposes the header.
 */
internal fun Modifier.stickBelowBar(listState: LazyListState, key: Any, barHeightPx: Int): Modifier =
    zIndex(2f).layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val ownHeight = (placeable.height - barHeightPx).coerceAtLeast(0)
        layout(placeable.width, ownHeight) {
            placeable.place(0, -barHeightPx + stickyShift(listState, key, barHeightPx))
        }
    }

/**
 * 0 → 1 while the sticky header approaches its parked position below the bar (last [fadePx]);
 * 1 while it is parked. Drives the frosted strip behind the chips (draw phase).
 */
internal fun stuckFraction(listState: LazyListState, key: Any, barHeightPx: Int, fadePx: Float): Float {
    val natural = naturalOffset(listState, key) ?: return 0f
    return ((barHeightPx + fadePx - natural) / fadePx).coerceIn(0f, 1f)
}

/** How far the header must move down from where the list placed it (0 … [bar]). */
private fun stickyShift(listState: LazyListState, key: Any, bar: Int): Int {
    val natural = naturalOffset(listState, key) ?: return 0
    return if (natural <= 0) bar else (bar - natural).coerceAtLeast(0)
}

/**
 * Where the header would be without sticking (px from the top of the list); very negative once
 * it is scrolled past, null when it is not laid out at all.
 */
private fun naturalOffset(listState: LazyListState, key: Any): Int? {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val first = listState.firstVisibleItemIndex
    return when {
        info.index < first -> Int.MIN_VALUE / 2
        info.index == first -> -listState.firstVisibleItemScrollOffset
        else -> info.offset
    }
}

/** True when the sticky header [key] is parked below the bar (used before switching chips). */
internal fun isParked(listState: LazyListState, key: Any, barHeightPx: Int): Boolean {
    val natural = naturalOffset(listState, key) ?: return false
    return natural <= barHeightPx
}

/** The index of [key] in the list, if it is laid out. */
internal fun indexOfKey(listState: LazyListState, key: Any): Int? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index

private const val TOPICS_BUTTON_ROOM_PX = 80
private const val CHIP_SCROLL_MARGIN_PX = 40

/** Shape of the frosted strip behind the parked chips (continues the TopGlassBar). */
internal val ChipStripShape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
