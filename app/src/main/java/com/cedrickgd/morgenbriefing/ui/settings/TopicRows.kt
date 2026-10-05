package com.cedrickgd.morgenbriefing.ui.settings

import android.graphics.Paint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import java.util.Locale

/** Outer corners of the topic group (like a settings card), inner corners between tiles. */
private val OuterCorner = 24.dp
private val InnerCorner = 6.dp
private val LiftedCorner = 18.dp

/** Where a tile sits in the topic group: decides which corners are round. */
internal enum class TilePosition { FIRST, MIDDLE, LAST, SINGLE }

internal fun tilePosition(index: Int, count: Int): TilePosition = when {
    count <= 1 -> TilePosition.SINGLE
    index == 0 -> TilePosition.FIRST
    index == count - 1 -> TilePosition.LAST
    else -> TilePosition.MIDDLE
}

private fun tileShape(position: TilePosition, lifted: Boolean): RoundedCornerShape {
    if (lifted) return RoundedCornerShape(LiftedCorner)
    val top: Dp = if (position == TilePosition.FIRST || position == TilePosition.SINGLE) OuterCorner else InnerCorner
    val bottom: Dp = if (position == TilePosition.LAST || position == TilePosition.SINGLE) OuterCorner else InnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Callbacks of one [TopicRow]; the drag ones come from [ReorderState]. */
internal class TopicRowActions(
    val onVisibleChange: (Boolean) -> Unit,
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val onDragStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
)

/**
 * One topic: emoji, title, visibility switch and a drag handle. Tapping the row toggles the
 * topic; the handle reorders by dragging. Screen readers get "Nach oben/unten verschieben" as
 * custom actions instead of the drag gesture.
 */
@Composable
internal fun TopicRow(
    topic: Topic,
    visible: Boolean,
    canHide: Boolean,
    position: TilePosition,
    lifted: Boolean,
    actions: TopicRowActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val elevation by animateDpAsState(if (lifted) 8.dp else 0.dp, label = "topicLift")
    val toggleEnabled = !visible || canHide
    val moveUpLabel = "${topic.title} nach oben verschieben"
    val moveDownLabel = "${topic.title} nach unten verschieben"
    val latestActions by rememberUpdatedState(actions)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = tileShape(position, lifted),
        color = if (lifted) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        shadowElevation = elevation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .toggleable(
                        value = visible,
                        enabled = toggleEnabled,
                        role = Role.Switch,
                        onValueChange = actions.onVisibleChange,
                    )
                    .semantics {
                        stateDescription = when {
                            !visible -> "Ausgeblendet"
                            !canHide -> "Sichtbar, letztes sichtbares Thema"
                            else -> "Sichtbar"
                        }
                        customActions = buildList {
                            actions.onMoveUp?.let { move -> add(CustomAccessibilityAction(moveUpLabel) { move(); true }) }
                            actions.onMoveDown?.let { move -> add(CustomAccessibilityAction(moveDownLabel) { move(); true }) }
                        }
                    }
                    .heightIn(min = 64.dp)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TopicBadge(topic = topic, visible = visible)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (visible) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val hint = when {
                        !visible -> "Ausgeblendet"
                        !canHide -> "Mindestens ein Thema bleibt an"
                        else -> null
                    }
                    if (hint != null) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = visible, onCheckedChange = null, enabled = toggleEnabled)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { latestActions.onDragStart() },
                            onDragEnd = { latestActions.onDragEnd() },
                            onDragCancel = { latestActions.onDragEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                latestActions.onDrag(amount.y)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = null,
                    tint = if (lifted) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The topic's emoji on a soft circle in the topic's own color (dimmed when hidden). Older
 * Android versions lack newer emoji (🩺 needs Android 10) — then the title's initial is shown
 * instead of an empty box.
 */
@Composable
private fun TopicBadge(topic: Topic, visible: Boolean) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val seed = Color(topic.seed)
    val tint = seed.copy(alpha = if (dark) 0.28f else 0.16f)
    val emojiRenders = remember(topic.emoji) { topic.emoji.isNotBlank() && Paint().hasGlyph(topic.emoji) }
    Surface(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (visible) 1f else 0.5f),
        shape = CircleShape,
        color = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (emojiRenders) {
                Emoji(topic.emoji, size = 20.sp)
            } else {
                Text(
                    text = topic.title.take(1).uppercase(Locale.GERMAN),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (dark) lerp(seed, Color.White, 0.45f) else lerp(seed, Color.Black, 0.25f),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

/** Line above the topic tiles. */
@Composable
internal fun TopicsIntro(modifier: Modifier = Modifier) {
    Text(
        text = "Tippe ein Thema an, um es ein- oder auszublenden. Am Griff ziehst du es an eine andere Stelle.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
    )
}

/** "8 von 10 Themen sichtbar" + "Reihenfolge zurücksetzen" below the tiles. */
@Composable
internal fun TopicsFooter(
    visibleCount: Int,
    total: Int,
    isDefaultOrder: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (visibleCount == total) "Alle $total Themen sichtbar" else "$visibleCount von $total Themen sichtbar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReset,
            enabled = !isDefaultOrder,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Reihenfolge zurücksetzen", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
