package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.GlassSheet
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading

/**
 * "Themen": glass sheet to show, hide and sort the topics of the feed (docs/DESIGN.md §7). Every
 * catalogue topic in the user's order, tinted with its color, with today's article count and a
 * switch; the handle (or a long press on the row) drags it to a new place. The order is stored
 * when the finger lifts.
 *
 * @param counts articles per topic id in the brief on screen.
 * @param onSetVisible shows/hides a topic; the last visible one cannot be switched off here.
 * @param onSetOrder stores a new order of topic ids.
 */
@Composable
fun TopicsSheet(
    prefs: TopicPrefs,
    counts: Map<String, Int>,
    onSetVisible: (topicId: String, visible: Boolean) -> Unit,
    onSetOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stepPx = with(density) { (TopicRowHeight + TopicRowGap).toPx() }
    var order by remember(prefs.order) { mutableStateOf(prefs.catalogueOrder()) }
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
            // Swap with the neighbour once the row has travelled half a row towards it.
            while (dragOffset > stepPx / 2f && order.indexOfFirst { it.id == id } < order.lastIndex) {
                moveBy(id, 1)
                dragOffset -= stepPx
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            while (dragOffset < -stepPx / 2f && order.indexOfFirst { it.id == id } > 0) {
                moveBy(id, -1)
                dragOffset += stepPx
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        },
        onEnd = {
            dragging = null
            dragOffset = 0f
            if (order.map { it.id } != prefs.catalogueOrder().map { it.id }) onSetOrder(order.map { it.id })
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
                    )
                }
            }
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
                    count > 0 -> "Heute ${articleCountLabel(count)}"
                    else -> "Heute keine Meldungen"
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

/**
 * "Quellen anzeigen": the sources an article is based on, each opening in the in-app browser.
 */
@Composable
fun SourcesSheet(article: FeedArticle, onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    val sources = remember(article.item) { sourcesOf(article.item) }
    val colors = MaterialTheme.colorScheme
    val topicColors = rememberTopicColors(article.topic)
    GlassSheet(onDismissRequest = onDismiss) {
        SheetTitle(overline = if (sources.size == 1) "1 Quelle" else "${sources.size} Quellen", title = article.item.headline, serif = true)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            if (sources.isEmpty()) {
                Text(
                    text = "Für diese Meldung sind keine Quellen hinterlegt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            sources.forEach { source ->
                val host = hostOf(source.url)
                val label = sourceLabel(source)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable(enabled = source.url.isNotBlank(), role = Role.Button, onClickLabel = "Quelle öffnen") {
                            onOpenLink(source.url)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(topicColors.container, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = topicColors.onContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.isNotEmpty() && !host.equals(label, ignoreCase = true)) {
                            Text(host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (source.url.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(
            text = "Der Artikel in der App ist in eigenen Worten aus diesen Quellen geschrieben.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SheetTitle(overline: String, title: String, serif: Boolean = false) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            text = overline.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = if (serif) Reading.feedHeadline().copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize) else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}
