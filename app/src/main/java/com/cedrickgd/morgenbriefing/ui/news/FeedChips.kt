package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass

/** Width of the soft fade where the chips run out before the "Themen" button. */
private val ChipFadeWidth: Dp = 32.dp

/** Clear space between the last visible chip and the "Themen" button. */
private val ChipButtonGap: Dp = 8.dp

/** Height of the chip row itself (chips are 40 dp, touch targets 48 dp). */
internal val ChipRowHeight: Dp = 56.dp

/**
 * The feed's chip row: "Für dich", "Neueste" and one chip per visible topic, as glass pills
 * scrolling horizontally (they win over the page swipe until their end), and a round glass
 * "Themen" button floating at the right end.
 *
 * Inner horizontal scrolling wins over the page swipe (docs/DESIGN.md §3): whatever the row
 * cannot use itself — the rest of a flick at its start or end — is swallowed instead of being
 * handed on to the page pager.
 *
 * @param rowState hoist it to keep the scroll position when the row moves (e.g. into the top bar).
 * @param onGlass the row sits on a glass bar: chips get a quiet fill and rim instead of their own glass.
 */
@Composable
fun FeedChipRow(
    chips: List<FeedChip>,
    selected: FeedFilter,
    onSelect: (FeedFilter) -> Unit,
    onOpenTopics: () -> Unit,
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    onGlass: Boolean = false,
) {
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
                .nestedScroll(KeepHorizontalScroll)
                // The chips end left of the "Themen" button and fade out softly towards it.
                .padding(end = FeedGutter + 48.dp + ChipButtonGap)
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
                    onGlass = onGlass,
                    onClick = { onSelect(chip.filter) },
                )
            }
        }
        TopicsButton(
            onClick = onOpenTopics,
            onGlass = onGlass,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = FeedGutter),
        )
    }
}

@Composable
private fun FeedChipPill(chip: FeedChip, selected: Boolean, onGlass: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val topicColors = chip.topic?.let { rememberTopicColors(it) }
    val selectedFill = topicColors?.strong ?: colors.primary
    val selectedText = if (chip.topic == null) colors.onPrimary else Color.White
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
                .then(
                    when {
                        selected -> Modifier
                        onGlass -> Modifier.onGlassChip()
                        else -> Modifier.glass(shape = GlassShapes.pill, level = GlassLevel.Thin)
                    },
                )
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
        }
    }
}

/** Round glass button with a heart and a plus — opens the topic sheet. */
@Composable
private fun TopicsButton(onClick: () -> Unit, onGlass: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .then(
                if (onGlass) {
                    Modifier.onGlassChip()
                } else {
                    Modifier
                        .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                        .glass(shape = GlassShapes.pill, level = GlassLevel.Regular)
                },
            )
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
 * Swallows the horizontal scroll and fling the chip row could not use itself (at its start or
 * end), so a flick back to "Für dich" never carries on into a page swipe.
 */
private val KeepHorizontalScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

/** Chip surface on a glass bar: a quiet fill and a fine rim (a second blur would vanish into the bar). */
@Composable
private fun Modifier.onGlassChip(): Modifier {
    val ink = MaterialTheme.colorScheme.onSurface
    return this
        .clip(CircleShape)
        .background(ink.copy(alpha = 0.06f))
        .border(1.dp, ink.copy(alpha = 0.14f), CircleShape)
}

/**
 * True when the chip row — the list item at [chipIndex] — has reached the lower edge of the top
 * bar ([barHeightPx] from the top of the list) or scrolled past it: from then on the bar carries
 * the chips. Reads list state only; call it inside `derivedStateOf`.
 */
internal fun chipsParked(listState: LazyListState, chipIndex: Int, barHeightPx: Int): Boolean {
    if (chipIndex < 0) return false
    val first = listState.firstVisibleItemIndex
    return when {
        first > chipIndex -> true
        first == chipIndex -> true // its top is at or above the list's top, far above the bar's edge
        else -> listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == chipIndex }
            ?.let { it.offset <= barHeightPx }
            ?: false
    }
}

private const val TOPICS_BUTTON_ROOM_PX = 80
private const val CHIP_SCROLL_MARGIN_PX = 40
