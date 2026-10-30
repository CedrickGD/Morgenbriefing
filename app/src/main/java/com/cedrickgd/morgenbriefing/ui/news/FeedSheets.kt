package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.GlassPillButton
import com.cedrickgd.morgenbriefing.ui.components.GlassSheet
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * "Themen": glass sheet to show, hide and sort the topics of the feed (docs/DESIGN.md §7). Every
 * topic in the user's order, tinted with its color, with today's article count and a switch;
 * the handle (or a long press on the row) drags it to a new place. The order is stored when
 * the finger lifts. "Quellen & eigene Themen" leads to the settings.
 *
 * @param counts articles per topic id on screen.
 * @param onSetVisible shows/hides a topic; the last visible one cannot be switched off here.
 * @param onSetOrder stores a new order of topic ids.
 */
@Composable
fun TopicsSheet(
    prefs: FeedPrefs,
    counts: Map<String, Int>,
    onSetVisible: (topicId: String, visible: Boolean) -> Unit,
    onSetOrder: (List<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stepPx = with(density) { (TopicRowHeight + TopicRowGap).toPx() }
    val gapPx = with(density) { TopicRowGap.toPx() }
    // Measured heights: a wrapped title or a large font makes a row taller than the minimum.
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    var order by remember(prefs.catalogue) { mutableStateOf(prefs.catalogue) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val visibleCount = order.count { it.id !in prefs.hidden }

    fun moveBy(id: String, delta: Int) {
        val from = order.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    val dragCallbacks = DragCallbacks(
        onStart = { id ->
            dragging = id
            dragOffset = 0f
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onDrag = { id, dy ->
            dragOffset += dy
            // The distance to the neighbour in [direction]: its measured height plus the gap.
            fun pitch(direction: Int): Float {
                val neighbour = order.getOrNull(order.indexOfFirst { it.id == id } + direction) ?: return stepPx
                return (rowHeights[neighbour.id]?.toFloat() ?: (stepPx - gapPx)) + gapPx
            }
            // Swap with the neighbour once the row has travelled half of its height towards it.
            while (order.indexOfFirst { it.id == id } < order.lastIndex && dragOffset > pitch(1) / 2f) {
                dragOffset -= pitch(1)
                moveBy(id, 1)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            while (order.indexOfFirst { it.id == id } > 0 && dragOffset < -pitch(-1) / 2f) {
                dragOffset += pitch(-1)
                moveBy(id, -1)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        },
        onEnd = {
            dragging = null
            dragOffset = 0f
            if (order.map { it.id } != prefs.catalogue.map { it.id }) onSetOrder(order.map { it.id })
        },
    )

    GlassSheet(onDismissRequest = onDismiss) {
        SheetTitle(overline = "Dein Feed", title = "Welche Themen willst du sehen?")
        Text(
            text = "Antippen blendet ein oder aus · am Griff ziehen sortiert",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState(), enabled = dragging == null)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(TopicRowGap),
        ) {
            order.forEachIndexed { index, topic ->
                key(topic.id) {
                    val visible = topic.id !in prefs.hidden
                    val lifted = dragging == topic.id
                    TopicToggleRow(
                        topic = topic,
                        count = counts[topic.id] ?: 0,
                        visible = visible,
                        enabled = !(visible && visibleCount <= 1),
                        lifted = lifted,
                        offset = { if (lifted) dragOffset else 0f },
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onToggle = { onSetVisible(topic.id, !visible) },
                        onMoveBy = { delta ->
                            moveBy(topic.id, delta)
                            onSetOrder(order.map { it.id })
                        },
                        drag = dragCallbacks,
                        onHeight = { rowHeights[topic.id] = it },
                    )
                }
            }
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            GlassPillButton(
                text = "Quellen & eigene Themen",
                onClick = onOpenSettings,
                icon = Icons.Rounded.Tune,
            )
        }
    }
}

/** Drag-to-sort events of the topic rows. */
private class DragCallbacks(
    val onStart: (id: String) -> Unit,
    val onDrag: (id: String, dy: Float) -> Unit,
    val onEnd: () -> Unit,
)

private val TopicRowHeight = 64.dp
private val TopicRowGap = 6.dp

@Composable
private fun TopicToggleRow(
    topic: Topic,
    count: Int,
    visible: Boolean,
    enabled: Boolean,
    lifted: Boolean,
    offset: () -> Float,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveBy: (Int) -> Unit,
    drag: DragCallbacks,
    /** Reports the row's measured height, so dragging knows how far the neighbours really are. */
    onHeight: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val topicColors = rememberTopicColors(topic)
    val dark = isDarkTheme()
    val tint = topicColors.strong.copy(alpha = if (dark) 0.16f else 0.08f)
    // The gesture coroutines outlive recompositions: always talk to the newest callbacks.
    val currentDrag by rememberUpdatedState(drag)
    val dragGesture = Modifier.pointerInput(topic.id) {
        detectDragGestures(
            onDragStart = { currentDrag.onStart(topic.id) },
            onDragEnd = { currentDrag.onEnd() },
            onDragCancel = { currentDrag.onEnd() },
            onDrag = { change, amount ->
                change.consume()
                currentDrag.onDrag(topic.id, amount.y)
            },
        )
    }
    val longPressDrag = Modifier.pointerInput(topic.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { currentDrag.onStart(topic.id) },
            onDragEnd = { currentDrag.onEnd() },
            onDragCancel = { currentDrag.onEnd() },
            onDrag = { change, amount ->
                change.consume()
                currentDrag.onDrag(topic.id, amount.y)
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = offset()
                val scale = if (lifted) 1.02f else 1f
                scaleX = scale
                scaleY = scale
                shadowElevation = if (lifted) 12.dp.toPx() else 0f
                shape = GlassShapes.chip
                clip = true
            }
            .heightIn(min = TopicRowHeight)
            .background(if (lifted) colors.surfaceContainerHigh else colors.surface.copy(alpha = 0f))
            .background(if (visible) tint else tint.copy(alpha = tint.alpha * 0.4f))
            .then(longPressDrag)
            .toggleable(value = visible, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics {
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("${topic.title} nach oben") { onMoveBy(-1); true })
                    if (canMoveDown) add(CustomAccessibilityAction("${topic.title} nach unten") { onMoveBy(1); true })
                }
            }
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopicBadge(topic, diameter = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = topic.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                text = when {
                    !enabled -> "Ein Thema bleibt immer an"
                    count > 0 -> articleCountLabel(count)
                    else -> "Gerade keine Meldungen"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The whole row toggles; the switch only shows the state.
        Switch(checked = visible, onCheckedChange = null, enabled = enabled)
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(dragGesture)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.DragIndicator,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetTitle(overline: String, title: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            text = overline.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}
