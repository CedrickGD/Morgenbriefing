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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            onValueChangeFinished = { onCommit(range.first, range.second) },
            valueRange = DayWindow.MIN_HOUR.toFloat()..DayWindow.MAX_HOUR.toFloat(),
            steps = DayWindow.SLIDER_STEPS,
            startThumb = { SunThumb(Icons.Rounded.WbSunny) },
            endThumb = { SunThumb(Icons.Rounded.WbTwilight) },
            track = { state -> DaylightTrack(state) },
            modifier = Modifier.semantics { contentDescription = "Zeitfenster für den Tagesverlauf" },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🌅 ${DayWindow.MIN_HOUR} Uhr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${DayWindow.MAX_HOUR} Uhr 🌇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
