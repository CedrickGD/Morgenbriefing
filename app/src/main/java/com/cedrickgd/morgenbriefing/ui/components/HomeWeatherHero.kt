package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.CurrentConditions
import com.cedrickgd.morgenbriefing.data.model.HomeWeather
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.domain.WeatherInsights
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Wind gusts from this speed on are worth mentioning. */
private const val GUST_NOTE_KMH = 40.0

/** Below this visibility the "Sicht" chip appears. */
private const val LOW_VISIBILITY_M = 5_000.0

/**
 * "Vor der Tür · Bad Düben": big serif temperature, feels-like, condition, chips, home note.
 *
 * A frosted glass card over the sky (docs/DESIGN.md §7) — the tint keeps text contrast
 * independent of the sky behind it, with real blur on Android 12+ and a denser frost below.
 */
@Composable
fun HomeWeatherHero(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val home = snapshot.home
    val current = home.current
    val condition = Condition.fromCode(current.code)
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 20.dp),
    ) {
        Column {
            Text(
                text = heroLabel(home),
                style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )

            val summary = heroDescription(home, condition)
            Column(Modifier.clearAndSetSemantics { contentDescription = summary }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountUpTemperature(
                        target = current.temp,
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize = 72.sp,
                            lineHeight = 80.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-1.5).sp,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    FloatingEmoji(condition.emoji(current.isDay))
                }
                Text(
                    text = "gefühlt ${Fmt.temp(current.feels)} · ${condition.labelFor(current.isDay)}",
                    style = typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chip in heroChips(home)) {
                    Pill(
                        text = chip.text,
                        emoji = chip.emoji,
                        container = colors.onSurface.copy(alpha = 0.07f),
                    )
                }
            }

            val note = WeatherInsights.homeNote(snapshot)
            if (note.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(text = note, style = typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

/** Temperature that counts up from 0° on first appearance and glides to new values on refresh. */
@Composable
private fun CountUpTemperature(target: Double, style: TextStyle, modifier: Modifier = Modifier) {
    val skip = motionDisabled()
    var played by rememberSaveable { mutableStateOf(false) }
    val value = remember { Animatable(if (played || skip) target.toFloat() else 0f) }
    LaunchedEffect(target, skip) {
        if (skip) {
            value.snapTo(target.toFloat())
        } else {
            value.animateTo(
                target.toFloat(),
                tween(durationMillis = if (played) 600 else 1_100, easing = FastOutSlowInEasing),
            )
        }
        played = true
    }
    Text(
        text = Fmt.temp(value.value.toDouble()),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The big condition emoji, bobbing gently (static with reduced motion). */
@Composable
private fun FloatingEmoji(emoji: String) {
    val skip = motionDisabled()
    val bob = if (skip) {
        null
    } else {
        rememberInfiniteTransition(label = "emoji").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Reverse),
            label = "emojiBob",
        )
    }
    Emoji(
        emoji = emoji,
        size = 56.sp,
        modifier = Modifier
            .padding(start = 12.dp)
            .graphicsLayer {
                val b = bob?.value ?: 0f
                // Smooth ease in/out of the linear ping-pong value.
                val eased = b * (1.5f - 0.5f * b * b)
                translationY = eased * 3.dp.toPx()
                rotationZ = eased * 2f
            },
    )
}

private data class HeroChip(val emoji: String, val text: String)

private fun heroLabel(home: HomeWeather): String {
    val place = home.place.name.uppercase(Locale.GERMAN)
    val time = Fmt.clock(home.current.time)
    return if (time.isBlank()) "VOR DER TÜR · $place" else "VOR DER TÜR · $place · $time"
}

private fun heroChips(home: HomeWeather): List<HeroChip> = buildList {
    val current = home.current
    val maxPop = home.nextHours.maxOfOrNull { it.pop } ?: 0
    add(HeroChip("💧", "Regen ${maxPop.coerceIn(0, 100)} %"))

    val wind = "Wind ${current.windKmh.roundToInt()} km/h"
    add(
        HeroChip(
            "💨",
            if (current.gustKmh >= GUST_NOTE_KMH) "$wind · Böen ${current.gustKmh.roundToInt()}" else wind,
        ),
    )

    sunChip(home)?.let(::add)

    val visibility = current.visibilityM
    if (visibility != null && visibility < LOW_VISIBILITY_M) {
        add(HeroChip("👁️", "Sicht ${String.format(Locale.GERMANY, "%.1f", visibility / 1000.0)} km"))
    }
}

/**
 * 🌅 sunrise while it is still dark before sunrise, 🌇 sunset during the day — and after sunset
 * tomorrow's 🌅 sunrise, never a sunset that is hours past.
 */
private fun sunChip(home: HomeWeather): HeroChip? {
    val now = localTimeOf(home.current.time)
    val sunrise = parseClock(home.sunrise)
    val sunset = parseClock(home.sunset)
    return when {
        now != null && sunrise != null && now.isBefore(sunrise) ->
            HeroChip("🌅", "Sonnenaufgang ${home.sunrise.trim()}")
        now != null && sunset != null && !now.isBefore(sunset) && home.sunriseTomorrow.isNotBlank() ->
            HeroChip("🌅", "Sonnenaufgang ${home.sunriseTomorrow.trim()}")
        home.sunset.isNotBlank() -> HeroChip("🌇", "Sonnenuntergang ${home.sunset.trim()}")
        else -> null
    }
}

private fun heroDescription(home: HomeWeather, condition: Condition): String {
    val current: CurrentConditions = home.current
    return "Vor der Tür in ${home.place.name}: ${Fmt.temp(current.temp)}, " +
        "gefühlt ${Fmt.temp(current.feels)}, ${condition.labelFor(current.isDay)}"
}

/** Local time of an ISO timestamp with or without offset; null when unparsable. */
internal fun localTimeOf(iso: String): LocalTime? =
    runCatching { LocalDateTime.parse(iso.trim()).toLocalTime() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso.trim()).toLocalTime() }.getOrNull()

/** `HH:mm` (or ISO date-time) → time; null when unparsable. */
internal fun parseClock(value: String): LocalTime? =
    runCatching { LocalTime.parse(value.trim()) }.getOrNull() ?: localTimeOf(value)
