package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The abstract motif of a generated cover. */
internal enum class CoverVariant { ARCS, BUBBLES, WAVES, RINGS }

/**
 * Everything that varies between covers, derived deterministically from an article key: the
 * same article always gets the same cover, neighbouring articles look different.
 */
@Immutable
internal data class CoverSpec(
    val variant: CoverVariant,
    /** Seeds the positions/sizes of the shapes. */
    val seed: Int,
    /** Direction of the base gradient in degrees. */
    val angle: Float,
    /** Center of the soft glow, as fractions of width/height. */
    val glowX: Float,
    val glowY: Float,
    /** Slight rotation of the emoji, in degrees. */
    val emojiTilt: Float,
)

/** Pure and stable across app starts and devices (no [String.hashCode] salt involved). */
internal fun coverSpec(seedKey: String): CoverSpec {
    val random = CoverRandom(stableHash(seedKey))
    return CoverSpec(
        variant = CoverVariant.entries[random.nextInt(CoverVariant.entries.size)],
        seed = random.nextRaw(),
        angle = random.next() * 360f,
        glowX = 0.15f + random.next() * 0.7f,
        glowY = 0.1f + random.next() * 0.5f,
        emojiTilt = -12f + random.next() * 24f,
    )
}

/** FNV-1a over the UTF-16 code units. */
internal fun stableHash(text: String): Int {
    var hash = FNV_OFFSET
    for (ch in text) {
        hash = hash xor ch.code
        hash *= FNV_PRIME
    }
    return hash
}

/** Tiny xorshift32 generator — deterministic, allocation-free, good enough for decoration. */
internal class CoverRandom(seed: Int) {
    private var state: Int = if (seed == 0) FALLBACK_SEED else seed

    fun nextRaw(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }

    /** Uniform in [0, 1). */
    fun next(): Float = (nextRaw() ushr 8) / 16_777_216f

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int = (next() * bound).toInt().coerceIn(0, bound - 1)
}

/**
 * A typographic cover for articles without a (free) picture — editorial, not clip-art: a soft
 * mesh gradient in the topic's color with a quiet abstract motif (picked by a hash of
 * [seedKey]), a large serif initial of the [title] as a watermark, and a small frosted badge
 * with the topic ("🤖 KI & TECH"). Small covers (thumbnails) keep only the initial and the topic
 * emoji in a little glass circle. Decorative — no semantics. Works in light and dark (the palette
 * comes from [rememberTopicColors]).
 *
 * @param title the headline; its first letter becomes the watermark (falls back to the topic).
 * @param badgeAlignment where the topic badge sits on large covers.
 * @param badgePadding distance of the badge from the cover's edges.
 * @param showBadge false where the caller shows the topic itself (e.g. a chip on top).
 */
@Composable
fun CoverArt(
    topic: Topic,
    seedKey: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    badgeAlignment: Alignment = Alignment.BottomStart,
    badgePadding: PaddingValues = PaddingValues(14.dp),
    showBadge: Boolean = true,
) {
    val colors = rememberTopicColors(topic)
    val spec = remember(seedKey) { coverSpec(seedKey) }
    val initial = remember(title, topic.title) { coverInitial(title, topic.title) }
    val emoji = topic.emoji.ifBlank { "📰" }

    BoxWithConstraints(
        // The shapes deliberately overshoot the edges; never paint over neighbouring content.
        modifier.clipToBounds().drawWithCache {
            onDrawBehind { drawCoverBackground(spec, colors) }
        },
    ) {
        val large = minOf(maxWidth, maxHeight) >= LargeCoverMin
        val initialSize = with(LocalDensity.current) { (maxHeight * if (large) 1.05f else 0.9f).toSp() }
        Text(
            text = initial,
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = initialSize,
                lineHeight = initialSize,
                color = Color.White.copy(alpha = if (large) 0.17f else 0.2f),
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .wrapContentSize(unbounded = true, align = Alignment.BottomEnd)
                .graphicsLayer {
                    // Partly cropped by the edges, slightly turned: a watermark, not a letter to read.
                    translationX = size.width * 0.12f
                    translationY = size.height * 0.2f
                    rotationZ = spec.emojiTilt * 0.4f
                },
        )
        if (!showBadge) return@BoxWithConstraints
        if (large) {
            TopicBadgeOnCover(
                emoji = emoji,
                label = topic.title,
                modifier = Modifier
                    .align(badgeAlignment)
                    .padding(badgePadding),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(minOf(32.dp, minOf(maxWidth, maxHeight) * 0.42f))
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Emoji(emoji, size = 16.sp)
            }
        }
    }
}

/** "🤖 KI & TECH" on a frosted white pill. */
@Composable
private fun TopicBadgeOnCover(emoji: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape)
            .padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji(emoji, size = 15.sp)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The first letter of the first word of [title] (skipping quotes and digits), else of [fallback]. */
internal fun coverInitial(title: String?, fallback: String): String {
    fun firstLetter(text: String?): String? = text?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
    return firstLetter(title) ?: firstLetter(fallback) ?: "N"
}

/** From this size on a cover shows its topic badge; smaller ones are thumbnails. */
private val LargeCoverMin = 120.dp

private fun DrawScope.drawCoverBackground(spec: CoverSpec, colors: TopicColors) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val minDim = min(w, h)
    val maxDim = max(w, h)

    val rad = spec.angle * PI.toFloat() / 180f
    val half = maxDim * 0.75f
    val dx = cos(rad) * half
    val dy = sin(rad) * half
    drawRect(
        Brush.linearGradient(
            listOf(colors.coverStart, colors.coverEnd),
            start = Offset(w / 2f - dx, h / 2f - dy),
            end = Offset(w / 2f + dx, h / 2f + dy),
        ),
    )

    val glowCenter = Offset(w * spec.glowX, h * spec.glowY)
    val glowRadius = maxDim * 0.7f
    drawCircle(
        brush = Brush.radialGradient(listOf(colors.coverGlow.copy(alpha = 0.5f), Color.Transparent), glowCenter, glowRadius),
        radius = glowRadius,
        center = glowCenter,
    )

    // Two more soft fields make it a mesh: a lighter one opposite the glow, a deeper one low down.
    val lightCenter = Offset(w * (1f - spec.glowX), h * (0.7f + spec.glowY * 0.4f))
    val lightRadius = maxDim * 0.6f
    drawCircle(
        brush = Brush.radialGradient(listOf(colors.coverStart.copy(alpha = 0.6f), Color.Transparent), lightCenter, lightRadius),
        radius = lightRadius,
        center = lightCenter,
    )
    val deepCenter = Offset(w * (0.2f + spec.glowY), h * 1.05f)
    val deepRadius = maxDim * 0.55f
    drawCircle(
        brush = Brush.radialGradient(listOf(colors.coverEnd.copy(alpha = 0.65f), Color.Transparent), deepCenter, deepRadius),
        radius = deepRadius,
        center = deepCenter,
    )

    val random = CoverRandom(spec.seed)
    when (spec.variant) {
        CoverVariant.ARCS -> drawArcs(random, minDim)
        CoverVariant.BUBBLES -> drawBubbles(random, minDim, colors)
        CoverVariant.WAVES -> drawWaves(random)
        CoverVariant.RINGS -> drawRings(random, minDim, maxDim)
    }

    // A whisper of shade at the bottom keeps the lower half calm for headlines.
    drawRect(
        Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to colors.coverEnd.copy(alpha = 0.45f),
        ),
    )
}

/** Concentric arcs from one corner, like ripples. */
private fun DrawScope.drawArcs(random: CoverRandom, minDim: Float) {
    val corner = when (random.nextInt(4)) {
        0 -> Offset(0f, 0f)
        1 -> Offset(size.width, 0f)
        2 -> Offset(0f, size.height)
        else -> Offset(size.width, size.height)
    }
    val stroke = minDim * (0.045f + random.next() * 0.04f)
    repeat(7) { i ->
        drawCircle(
            color = Color.White.copy(alpha = (0.17f - i * 0.02f).coerceAtLeast(0.03f)),
            radius = minDim * (0.3f + i * 0.21f),
            center = corner,
            style = Stroke(width = stroke * (1f + i * 0.15f)),
        )
    }
}

/** Soft overlapping circles of different sizes ("bokeh"). */
private fun DrawScope.drawBubbles(random: CoverRandom, minDim: Float, colors: TopicColors) {
    repeat(7) {
        val light = random.next() > 0.3f
        val alpha = 0.07f + random.next() * 0.13f
        drawCircle(
            color = if (light) Color.White.copy(alpha = alpha) else colors.coverEnd.copy(alpha = alpha * 1.6f),
            radius = minDim * (0.12f + random.next() * 0.4f),
            center = Offset(size.width * random.next(), size.height * random.next()),
        )
    }
}

/** Three layered waves rising from the bottom. */
private fun DrawScope.drawWaves(random: CoverRandom) {
    val w = size.width
    val h = size.height
    repeat(3) { layer ->
        val baseline = h * (0.42f + layer * 0.17f + random.next() * 0.06f)
        val amplitude = h * (0.05f + random.next() * 0.06f)
        val phase = random.next() * 2f * PI.toFloat()
        val waves = 1.1f + random.next() * 1.2f
        val path = Path().apply {
            moveTo(0f, h)
            val steps = 48
            for (step in 0..steps) {
                val x = w * step / steps
                val y = baseline + amplitude * sin(phase + (x / w) * waves * 2f * PI.toFloat())
                lineTo(x, y)
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, color = Color.White.copy(alpha = 0.08f + layer * 0.035f))
    }
}

/** Two bold rings and a scatter of dots. */
private fun DrawScope.drawRings(random: CoverRandom, minDim: Float, maxDim: Float) {
    repeat(2) {
        val center = Offset(size.width * random.next(), size.height * (0.2f + random.next() * 0.7f))
        drawCircle(
            color = Color.White.copy(alpha = 0.10f + random.next() * 0.08f),
            radius = maxDim * (0.22f + random.next() * 0.25f),
            center = center,
            style = Stroke(width = minDim * (0.08f + random.next() * 0.08f)),
        )
    }
    val dot = minDim * 0.022f
    val spacing = minDim * 0.11f
    val origin = Offset(size.width * random.next() * 0.6f, size.height * random.next() * 0.6f)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = dot,
                center = Offset(origin.x + col * spacing, origin.y + row * spacing),
            )
        }
    }
}

private const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5 as a signed Int
private const val FNV_PRIME = 0x01000193
private const val FALLBACK_SEED = 0x2545F491
