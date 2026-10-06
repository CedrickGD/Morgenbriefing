package com.cedrickgd.morgenbriefing.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark

/** What the hero's seal shows. */
private enum class Seal { READY, ATTENTION, OFF }

/**
 * Settings section 1: "Alles bereit für morgen, 05:30" with a big animated check — or a friendly
 * hint — and one pill per requirement. Amber pills fix their problem on tap, green ones jump to
 * their section.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusHero(
    hero: HeroText,
    enabled: Boolean,
    items: List<StatusItem>,
    reduceMotion: Boolean,
    onPill: (StatusItem) -> Unit,
    onOpenAppDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seal = when {
        !enabled -> Seal.OFF
        hero.ready -> Seal.READY
        else -> Seal.ATTENTION
    }
    val glow = when (seal) {
        Seal.READY -> StatusPalette.okFill()
        Seal.ATTENTION -> StatusPalette.attentionFill()
        Seal.OFF -> MaterialTheme.colorScheme.outline
    }
    val glowAlpha = if (isGlassDark()) 0.22f else 0.26f
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Soft light of the seal's color spilling into the glass.
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(glow.copy(alpha = glowAlpha), Color.Transparent),
                            center = Offset(52.dp.toPx(), 52.dp.toPx()),
                            radius = size.width * 0.7f,
                        ),
                        radius = size.width * 0.7f,
                        center = Offset(52.dp.toPx(), 52.dp.toPx()),
                    )
                }
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusSeal(seal = seal, reduceMotion = reduceMotion)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Overline(
                        when (seal) {
                            Seal.READY -> "Status · alles gut"
                            Seal.ATTENTION -> "Status · kurz prüfen"
                            Seal.OFF -> "Status · pausiert"
                        },
                    )
                    AnimatedContent(
                        targetState = hero.title,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "heroTitle",
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Text(
                text = hero.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item -> StatusPill(item = item, onClick = { onPill(item) }) }
            }
            // Samsung keeps its own per-app battery limit next to Android's list – only there.
            val samsung = remember { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
            if (samsung && items.any { it.kind == StatusKind.BATTERY && it.level == StatusLevel.ATTENTION }) {
                SamsungHint(onOpenAppDetails)
            }
        }
    }
}

/** 60 dp seal: an animated check (ready), an exclamation (attention) or a muted bell (off). */
@Composable
private fun StatusSeal(seal: Seal, reduceMotion: Boolean) {
    val fill = when (seal) {
        Seal.READY -> StatusPalette.okFill()
        Seal.ATTENTION -> StatusPalette.attentionFill()
        Seal.OFF -> MaterialTheme.colorScheme.outline
    }
    val description = when (seal) {
        Seal.READY -> "Alles bereit"
        Seal.ATTENTION -> "Braucht deine Aufmerksamkeit"
        Seal.OFF -> "Ausgeschaltet"
    }
    val draw = remember(seal) { Animatable(if (reduceMotion || seal != Seal.READY) 1f else 0f) }
    LaunchedEffect(seal) {
        if (seal == Seal.READY && draw.value < 1f) draw.animateTo(1f, tween(620, delayMillis = 180, easing = FastOutSlowInEasing))
    }
    val pulse = if (reduceMotion || seal == Seal.OFF) {
        null
    } else {
        rememberInfiniteTransition(label = "sealPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart),
            label = "sealPulseValue",
        )
    }
    Box(
        modifier = Modifier
            .size(60.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(60.dp)) {
            val r = size.minDimension / 2f
            // A slow ring breathing outwards: calm, alive.
            pulse?.value?.let { p ->
                drawCircle(
                    color = fill.copy(alpha = 0.35f * (1f - p)),
                    radius = r * (0.82f + 0.18f * p),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(lerp(fill, Color.White, 0.28f), fill),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                radius = r * 0.8f,
            )
            if (seal == Seal.READY) {
                val path = Path().apply {
                    moveTo(size.width * 0.34f, size.height * 0.52f)
                    lineTo(size.width * 0.45f, size.height * 0.63f)
                    lineTo(size.width * 0.67f, size.height * 0.39f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val partial = Path()
                measure.getSegment(0f, measure.length * draw.value, partial, true)
                drawPath(
                    partial,
                    color = Color.White,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        when (seal) {
            Seal.ATTENTION -> Icon(Icons.Rounded.PriorityHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            Seal.OFF -> Icon(Icons.Rounded.NotificationsOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Seal.READY -> Unit
        }
    }
}

/** One requirement as a pill: colored state icon + label; tap fixes or explains it. */
@Composable
private fun StatusPill(item: StatusItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when (item.level) {
        StatusLevel.OK -> StatusPalette.ok()
        StatusLevel.ATTENTION -> StatusPalette.attention()
        StatusLevel.NEUTRAL -> colors.onSurfaceVariant
    }
    val checking = item.level == StatusLevel.NEUTRAL && item.kind == StatusKind.NEWS
    val action = when (item.level) {
        StatusLevel.ATTENTION -> "Beheben"
        StatusLevel.OK -> "Zum Abschnitt"
        StatusLevel.NEUTRAL -> if (item.kind == StatusKind.AI) "Einrichten" else "Zum Abschnitt"
    }
    val attentionWash = StatusPalette.attentionFill().copy(alpha = if (isGlassDark()) 0.16f else 0.14f)
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .lamina(CircleShape, strong = true)
            .then(if (item.level == StatusLevel.ATTENTION) Modifier.background(attentionWash) else Modifier)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${item.label}: ${item.state}" }
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(
                imageVector = when (item.level) {
                    StatusLevel.OK -> Icons.Rounded.CheckCircle
                    StatusLevel.ATTENTION -> Icons.Rounded.ErrorOutline
                    StatusLevel.NEUTRAL -> Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(item.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1)
    }
}

/** Samsung's own app battery setting is separate from Android's optimization list. */
@Composable
private fun SamsungHint(onOpenAppDetails: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            text = "Bei Samsung zusätzlich: App-Info → Akku → „Nicht eingeschränkt“.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        GlassPillButton(text = "App-Info öffnen", onClick = onOpenAppDetails, style = PillStyle.Quiet)
    }
}

/** Placeholder while the settings load: a calm glass block instead of a spinner. */
@Composable
internal fun HeroPlaceholder(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .lamina(CircleShape),
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(22.dp)
                .lamina(CircleShape),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth(0.45f)
                .height(14.dp)
                .lamina(CircleShape),
        )
    }
}
