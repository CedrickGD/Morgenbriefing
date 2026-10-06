package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import kotlin.math.roundToInt

/**
 * Settings section 5 "Orte & Zeitfenster": two place cards with live mini weather (tap → place
 * picker) and the day window as a range slider with sun thumbs.
 */
@Composable
internal fun PlacesSection(
    settings: AppSettings,
    homeWeather: PlaceWeather?,
    dayWeather: PlaceWeather?,
    weatherLoading: Boolean,
    onEditPlace: (PlaceTarget) -> Unit,
    onDayWindow: (start: Int, end: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaceCard(
                overline = "Vor der Tür",
                icon = Icons.Rounded.Home,
                name = settings.home.name,
                weather = homeWeather,
                loading = weatherLoading,
                onClick = { onEditPlace(PlaceTarget.HOME) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            PlaceCard(
                overline = "Tagsüber",
                icon = Icons.Rounded.LocationCity,
                name = settings.day.name,
                weather = dayWeather,
                loading = weatherLoading,
                onClick = { onEditPlace(PlaceTarget.DAY) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        DayWindowCard(
            start = settings.dayStartHour,
            end = settings.dayEndHour,
            placeName = settings.day.name,
            onCommit = onDayWindow,
        )
    }
}

@Composable
private fun PlaceCard(
    overline: String,
    icon: ImageVector,
    name: String,
    weather: PlaceWeather?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = buildString {
                append("$overline: $name")
                if (weather != null) append(", ${weather.temp}, ${weather.caption}")
            }
        },
        onClick = onClick,
        onClickLabel = "Ort ändern",
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Overline(overline)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(14.dp))
        if (weather != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Emoji(weather.emoji, size = 26.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = weather.temp,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = weather.caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (loading) {
            // Same rhythm as the loaded state, so nothing jumps when the weather arrives.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).lamina(CircleShape))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(width = 54.dp, height = 30.dp).lamina(GlassShapes.chip))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(width = 72.dp, height = 12.dp).lamina(CircleShape))
        } else {
            Text(
                text = "Wetter gerade nicht erreichbar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The day window: "10–18 Uhr" in serif and a range slider whose thumbs are a rising and a setting sun. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayWindowCard(start: Int, end: Int, placeName: String, onCommit: (Int, Int) -> Unit) {
    // Local while dragging, written once the finger lifts; resets when the stored value changes.
    var range by remember(start, end) { mutableStateOf(DayWindow.normalize(start, end)) }
    val haptics = LocalHapticFeedback.current
    // material3's RangeSlider moves the nearest thumb to any touch and reports every gesture as
    // finished, also a page scroll that starts on the track and a Home swipe the system takes
    // over when the slider sits in the gesture strip (it cancels the touch). Only a horizontal
    // drag or a tap that ends normally while the app is in front is saved (SliderTouchGuard).
    val guard = remember { SliderTouchGuard() }
    val windowInfo = LocalWindowInfo.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val finishTouch by rememberUpdatedState { how: TouchEnd ->
        val inFront = windowInfo.isWindowFocused && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        when (guard.onTouchEnd(how, inFront)) {
            SliderVerdict.SAVE -> onCommit(range.first, range.second)
            SliderVerdict.REVERT -> range = DayWindow.normalize(start, end)
            null -> Unit
        }
    }
    val onTouchEnd = remember { { how: TouchEnd -> finishTouch(how) } }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Overline("Zeitfenster")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${range.first}–${range.second}",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Uhr",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        Text(
            text = "Für den Tagesverlauf in $placeName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        RangeSlider(
            value = range.first.toFloat()..range.second.toFloat(),
            onValueChange = { value ->
                val next = DayWindow.constrain(
                    previousStart = range.first,
                    previousEnd = range.second,
                    start = value.start.roundToInt(),
                    end = value.endInclusive.roundToInt(),
                )
                if (next != range) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                range = next
            },
            onValueChangeFinished = {
                // Keyboard and TalkBack save at once; during a touch its end decides.
                if (guard.onSliderFinished()) onCommit(range.first, range.second)
            },
            valueRange = DayWindow.MIN_HOUR.toFloat()..DayWindow.MAX_HOUR.toFloat(),
            steps = DayWindow.SLIDER_STEPS,
            startThumb = { SunThumb(Icons.Rounded.WbSunny) },
            endThumb = { SunThumb(Icons.Rounded.WbTwilight) },
            track = { state -> DaylightTrack(state) },
            modifier = Modifier
                .followSliderTouch(guard, onTouchEnd)
                .semantics { contentDescription = "Zeitfenster für den Tagesverlauf" },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🌅 ${DayWindow.MIN_HOUR} Uhr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${DayWindow.MAX_HOUR} Uhr 🌇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Feeds [guard] with each touch on this element and reports its end to [onTouchEnd]. Nothing is
 * consumed, so the slider and the page behave as before. The first contact and the movement are
 * read in the Initial pass (before the slider moves a thumb), the release in the Final pass
 * (after the slider reported the gesture finished). A cancel (the system taking the touch over)
 * arrives through [PointerInputModifierNode.onCancelPointerInput], which Compose calls on this
 * outer node only after the slider has handled the cancel.
 */
private fun Modifier.followSliderTouch(guard: SliderTouchGuard, onTouchEnd: (TouchEnd) -> Unit): Modifier =
    this then SliderTouchElement(guard, onTouchEnd)

private data class SliderTouchElement(
    val guard: SliderTouchGuard,
    val onTouchEnd: (TouchEnd) -> Unit,
) : ModifierNodeElement<SliderTouchNode>() {
    override fun create() = SliderTouchNode(guard, onTouchEnd)

    override fun update(node: SliderTouchNode) {
        node.guard = guard
        node.onTouchEnd = onTouchEnd
    }
}

private class SliderTouchNode(
    var guard: SliderTouchGuard,
    var onTouchEnd: (TouchEnd) -> Unit,
) : Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    /** The finger that started the touch; null between touches. */
    private var pointer: PointerId? = null

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val tracked = pointer
        when (pass) {
            PointerEventPass.Initial -> if (tracked == null) {
                val down = pointerEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return
                pointer = down.id
                guard.onDown()
            } else {
                val change = pointerEvent.changes.firstOrNull { it.id == tracked } ?: return
                val delta = change.positionChangeIgnoreConsumed()
                guard.onMove(delta.x, delta.y, currentValueOf(LocalViewConfiguration).touchSlop)
            }
            PointerEventPass.Final -> if (tracked != null) {
                val change = pointerEvent.changes.firstOrNull { it.id == tracked }
                if (change == null || !change.pressed) end(TouchEnd.RELEASED)
            }
            else -> Unit
        }
    }

    override fun onCancelPointerInput() {
        if (pointer != null) end(TouchEnd.CANCELLED)
    }

    override fun onDetach() {
        pointer = null
        guard.reset()
    }

    private fun end(how: TouchEnd) {
        pointer = null
        onTouchEnd(how)
    }
}

/** A sun on a warm disc as slider thumb. */
@Composable
private fun SunThumb(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(THUMB_SIZE)
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .background(Brush.linearGradient(listOf(Color(0xFFFFC861), Color(0xFFF08A3C))), CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Track from dawn to dusk; the chosen window glows in daylight colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaylightTrack(state: RangeSliderState) {
    val dark = isGlassDark()
    val inactive = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val dawn = Color(0xFFF6B04B)
    val noon = Color(0xFFFFD86B)
    val dusk = Color(0xFFE8744A)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT),
    ) {
        // Material lays the track out between the thumb centers, so 0 and width are the ends.
        val usable = size.width.coerceAtLeast(1f)
        val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(1f)
        val startX = usable * ((state.activeRangeStart - state.valueRange.start) / span)
        val endX = usable * ((state.activeRangeEnd - state.valueRange.start) / span)
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(inactive, Offset.Zero, Size(usable, size.height), radius)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(dawn, noon, dusk), startX = startX, endX = endX),
            topLeft = Offset(startX, 0f),
            size = Size((endX - startX).coerceAtLeast(0f), size.height),
            cornerRadius = radius,
        )
    }
}

private val THUMB_SIZE = SliderKnobSize
private val TRACK_HEIGHT = 8.dp
