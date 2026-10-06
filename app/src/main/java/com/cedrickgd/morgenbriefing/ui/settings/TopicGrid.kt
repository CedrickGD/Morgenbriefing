package com.cedrickgd.morgenbriefing.ui.settings

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.EmojiSupport
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings section 4 "Themen": colorful glass tiles in two columns. Tap shows/hides a topic
 * (hidden ones are dimmed), a long press lifts a tile so it can be dragged to a new place — the
 * others glide out of the way. Screen readers get "nach vorne/hinten" actions instead.
 *
 * @param listState the page's list, scrolled automatically when a lifted tile nears its edges.
 * @param listItemKey this grid's key in that list.
 */
@Composable
internal fun TopicGrid(
    storedOrder: List<String>,
    hidden: Set<String>,
    reduceMotion: Boolean,
    listState: LazyListState,
    listItemKey: Any,
    topObstruction: Float,
    onToggle: (id: String, visible: Boolean) -> Unit,
    onOrder: (List<String>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Local copy so a drag can reorder tiles before anything is written; follows the store.
    var order by remember { mutableStateOf(storedOrder) }
    val latestOrder by rememberUpdatedState(order)
    val latestStored by rememberUpdatedState(storedOrder)
    val latestOnOrder by rememberUpdatedState(onOrder)
    val latestReduceMotion by rememberUpdatedState(reduceMotion)
    val drag = remember(scope, haptics) {
        TopicDragState(
            scope = scope,
            order = { latestOrder },
            onMove = { from, to ->
                order = TopicList.move(latestOrder, from, to)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            },
            onDrop = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                if (latestOrder != latestStored) latestOnOrder(latestOrder)
            },
            reduceMotion = { latestReduceMotion },
        )
    }
    LaunchedEffect(storedOrder) {
        if (drag.draggingId == null) order = storedOrder
    }
    TopicAutoScroll(drag, listState, listItemKey, topObstruction)

    val density = LocalDensity.current
    val gap = with(density) { 10.dp.toPx() }
    // Tiles grow with their text (intrinsic height below); this only keeps short ones generous.
    val minCell = with(density) { 96.dp.toPx() }

    Column(modifier.fillMaxWidth()) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                order.forEach { id ->
                    key(id) {
                        val topic = Topics.byId(id)
                        if (topic != null) {
                            val index = order.indexOf(id)
                            TopicTile(
                                topic = topic,
                                visible = id !in hidden,
                                lifted = drag.draggingId == id,
                                onToggle = { visible ->
                                    haptics.performHapticFeedback(if (visible) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    onToggle(id, visible)
                                },
                                onMoveBy = if (drag.draggingId == null) {
                                    { delta ->
                                        val moved = TopicList.moveBy(latestOrder, id, delta)
                                        if (moved != latestOrder) {
                                            order = moved
                                            onOrder(moved)
                                        }
                                    }
                                } else {
                                    null
                                },
                                canMoveBack = index > 0,
                                canMoveForward = index < order.lastIndex,
                                dragModifier = Modifier.pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            drag.start(id)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            drag.drag(amount)
                                        },
                                        onDragEnd = drag::end,
                                        onDragCancel = drag::end,
                                    )
                                },
                            )
                        } else {
                            Box(Modifier)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val columns = GRID_COLUMNS
            val cellWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0f)
            val cellWidthPx = cellWidth.roundToInt()
            val cellHeight = measurables
                .maxOfOrNull { it.maxIntrinsicHeight(cellWidthPx) }
                ?.toFloat()
                ?.coerceAtLeast(minCell)
                ?: minCell
            val slots = GridSlots(columns, measurables.size, cellWidth, cellHeight, gap)
            drag.slots = slots
            val placeables = measurables.map {
                it.measure(Constraints.fixed(cellWidthPx, cellHeight.roundToInt()))
            }
            val ids = latestOrder
            layout(width, slots.height.roundToInt()) {
                placeables.forEachIndexed { index, placeable ->
                    val id = ids.getOrNull(index) ?: return@forEachIndexed
                    val position = drag.positionOf(id, index)
                    val lifted = drag.draggingId == id
                    val z = when {
                        lifted -> 2f
                        drag.settlingId == id -> 1f
                        else -> 0f
                    }
                    if (lifted) {
                        placeable.placeWithLayer(position.x.roundToInt(), position.y.roundToInt(), zIndex = z) {
                            scaleX = LIFT_SCALE
                            scaleY = LIFT_SCALE
                            shadowElevation = 14.dp.toPx()
                            shape = GlassShapes.tile
                            clip = false
                            ambientShadowColor = Color.Black.copy(alpha = 0.25f)
                            spotShadowColor = Color.Black.copy(alpha = 0.25f)
                        }
                    } else {
                        placeable.place(IntOffset(position.x.roundToInt(), position.y.roundToInt()), zIndex = z)
                    }
                }
            }
        }
        TopicsFooterRow(
            visibleCount = TopicList.visibleCount(order, hidden),
            total = order.size,
            canReset = !(TopicList.isDefaultOrder(order) && hidden.isEmpty()),
            onReset = onReset,
        )
    }
}

/**
 * Drag state of the grid. Every tile owns an animated position (grid pixels) that glides to its
 * slot whenever the order changes; the lifted tile follows the finger instead and, when
 * dropped, glides from where it was let go into its new slot. Positions are read in the
 * placement phase only, so dragging never recomposes the tiles.
 */
@Stable
internal class TopicDragState(
    private val scope: CoroutineScope,
    private val order: () -> List<String>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
    private val reduceMotion: () -> Boolean,
) {
    /** Set by the layout on every measure. */
    var slots: GridSlots? = null

    var draggingId by mutableStateOf<String?>(null)
        private set

    var settlingId by mutableStateOf<String?>(null)
        private set

    private var origin = Offset.Zero
    private var delta by mutableStateOf(Offset.Zero)
    private val positions = HashMap<String, Animatable<Offset, *>>()
    private val targets = HashMap<String, Offset>()

    /** Where tile [id] at slot [index] is drawn right now (grid pixels). */
    fun positionOf(id: String, index: Int): Offset {
        val grid = slots ?: return Offset.Zero
        val slot = Offset(grid.x(index), grid.y(index))
        if (id == draggingId) return origin + delta
        val anim = positions[id]
        if (anim == null) {
            positions[id] = Animatable(slot, Offset.VectorConverter)
            targets[id] = slot
            return slot
        }
        if (targets[id] != slot) {
            targets[id] = slot
            scope.launch {
                if (reduceMotion()) {
                    anim.snapTo(slot)
                } else {
                    anim.animateTo(slot, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
        return anim.value
    }

    /** The lifted tile's top/bottom in grid pixels, or null. */
    fun liftedSpan(): ClosedFloatingPointRange<Float>? {
        val grid = slots ?: return null
        if (draggingId == null) return null
        val top = origin.y + delta.y
        return top..(top + grid.cellHeight)
    }

    val movingDown: Boolean get() = delta.y > 0f

    fun start(id: String) {
        val grid = slots ?: return
        val index = order().indexOf(id)
        if (index < 0) return
        val current = positions[id]?.value ?: Offset(grid.x(index), grid.y(index))
        origin = current
        delta = Offset.Zero
        settlingId = null
        draggingId = id
    }

    fun drag(amount: Offset) {
        if (draggingId == null) return
        delta += amount
        reorderIfNeeded()
    }

    /** The list scrolled by [dy] under the finger: the finger now points further down the grid. */
    fun compensateScroll(dy: Float) {
        if (draggingId == null) return
        delta += Offset(0f, dy)
        reorderIfNeeded()
    }

    fun end() {
        val id = draggingId ?: return
        val grid = slots
        val released = origin + delta
        draggingId = null
        delta = Offset.Zero
        onDrop()
        val index = order().indexOf(id)
        if (grid == null || index < 0) return
        val slot = Offset(grid.x(index), grid.y(index))
        targets[id] = slot
        val anim = positions[id] ?: Animatable(slot, Offset.VectorConverter).also { positions[id] = it }
        settlingId = id
        scope.launch {
            anim.snapTo(released)
            if (reduceMotion()) {
                anim.snapTo(slot)
            } else {
                anim.animateTo(slot, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
            }
            if (settlingId == id) settlingId = null
        }
    }

    private fun reorderIfNeeded() {
        val id = draggingId ?: return
        val grid = slots ?: return
        val current = order().indexOf(id)
        if (current < 0) return
        val center = origin + delta + Offset(grid.cellWidth / 2f, grid.cellHeight / 2f)
        val target = grid.indexAt(center.x, center.y)
        if (target >= 0 && target != current) onMove(current, target)
    }
}

/** While a tile is lifted near the visible top/bottom of the page, scroll the page along. */
@Composable
private fun TopicAutoScroll(drag: TopicDragState, listState: LazyListState, listItemKey: Any, topObstruction: Float) {
    val dragging = drag.draggingId != null
    val density = LocalDensity.current
    val edge = with(density) { 88.dp.toPx() }
    val maxStep = with(density) { 12.dp.toPx() }
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val span = drag.liftedSpan() ?: continue
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == listItemKey } ?: continue
            val top = item.offset + span.start
            val bottom = item.offset + span.endInclusive
            val viewportTop = info.viewportStartOffset + topObstruction
            val viewportBottom = (info.viewportEndOffset - info.afterContentPadding).toFloat()
            val step = when {
                drag.movingDown && bottom > viewportBottom - edge ->
                    maxStep * ((bottom - (viewportBottom - edge)) / edge).coerceIn(0.15f, 1f)
                !drag.movingDown && top < viewportTop + edge ->
                    -maxStep * (((viewportTop + edge) - top) / edge).coerceIn(0.15f, 1f)
                else -> 0f
            }
            if (step != 0f) {
                val consumed = listState.scrollBy(step)
                if (consumed != 0f) drag.compensateScroll(consumed)
            }
        }
    }
}

/**
 * One topic tile: glass tinted in the topic's color, emoji badge, title and a state mark.
 * Hidden topics are dimmed with an eye-off mark.
 */
@Composable
private fun TopicTile(
    topic: Topic,
    visible: Boolean,
    lifted: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveBy: ((Int) -> Unit)?,
    canMoveBack: Boolean,
    canMoveForward: Boolean,
    dragModifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = isGlassDark()
    val seed = Color(topic.seed)
    val tint = lerp(colors.surface, seed, if (dark) 0.30f else 0.22f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !lifted) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "tilePress",
    )
    val dim by animateFloatAsState(if (visible) 1f else 0.5f, label = "tileDim")
    val moveBack = "${topic.title} nach vorne verschieben"
    val moveForward = "${topic.title} nach hinten verschieben"
    val emojiRenders = remember(topic.emoji) { topic.emoji.isNotBlank() && Paint().hasGlyph(EmojiSupport.display(topic.emoji)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glass(shape = GlassShapes.tile, level = GlassLevel.Thin, tint = tint)
            .toggleable(
                value = visible,
                interactionSource = interaction,
                indication = ripple(color = seed),
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .then(dragModifier)
            .semantics {
                stateDescription = if (visible) "Sichtbar" else "Ausgeblendet"
                if (onMoveBy != null) {
                    customActions = buildList {
                        if (canMoveBack) add(CustomAccessibilityAction(moveBack) { onMoveBy(-1); true })
                        if (canMoveForward) add(CustomAccessibilityAction(moveForward) { onMoveBy(1); true })
                    }
                }
            }
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dim },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(seed.copy(alpha = if (dark) 0.34f else 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                if (emojiRenders) {
                    Emoji(topic.emoji, size = 21.sp)
                } else {
                    Text(
                        text = topic.title.take(1).uppercase(Locale.GERMAN),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (dark) lerp(seed, Color.White, 0.5f) else lerp(seed, Color.Black, 0.3f),
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (visible) Icons.Rounded.CheckCircle else Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = if (visible) {
                if (dark) lerp(seed, Color.White, 0.45f) else lerp(seed, Color.Black, 0.15f)
            } else {
                colors.onSurfaceVariant
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp),
        )
    }
}

/** "8 von 10 sichtbar · Lange drücken zum Sortieren" + "Zurücksetzen". */
@Composable
private fun TopicsFooterRow(visibleCount: Int, total: Int, canReset: Boolean, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (visibleCount == total) "Alle $total Themen sichtbar" else "$visibleCount von $total Themen sichtbar",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Antippen blendet aus · lange drücken sortiert",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only offered once there is something to undo: no faded, broken-looking button.
        AnimatedVisibility(
            visible = canReset,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            GlassPillButton(
                text = "Zurücksetzen",
                onClick = onReset,
                icon = Icons.Rounded.RestartAlt,
                onGlass = false,
                style = PillStyle.Tonal,
            )
        }
    }
}

private const val GRID_COLUMNS = 2
private const val LIFT_SCALE = 1.05f
