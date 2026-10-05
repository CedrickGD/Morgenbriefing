package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.cedrickgd.morgenbriefing.data.model.Topic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 * A generated cover for articles without a (free) picture: a gradient in the topic's color,
 * soft abstract shapes picked by a hash of [seedKey] and the topic emoji, large. Decorative —
 * no semantics. Works in light and dark (the palette comes from [rememberTopicColors]).
 *
 * @param emojiAlignment where the emoji sits; [Alignment.TopEnd] leaves room for a headline
 *   at the bottom and a chip at the top start.
 * @param emojiFraction emoji size relative to the cover's smaller side.
 */
@Composable
fun CoverArt(
    topic: Topic,
    seedKey: String,
    modifier: Modifier = Modifier,
    emojiAlignment: Alignment = Alignment.TopEnd,
    emojiFraction: Float = 0.34f,
) {
    val colors = rememberTopicColors(topic)
    val spec = remember(seedKey) { coverSpec(seedKey) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val emoji = topic.emoji.ifBlank { "📰" }

    Box(
        // The shapes deliberately overshoot the edges; never paint over neighbouring content.
        modifier.clipToBounds().drawWithCache {
            val emojiPx = min(size.width, size.height) * emojiFraction
            val layout = if (emojiPx >= 1f) {
                measurer.measure(emoji, TextStyle(fontSize = emojiPx.toSp(), lineHeight = (emojiPx * 1.15f).toSp()))
            } else {
                null
            }
            val margin = min(size.width, size.height) * 0.09f
            val emojiTopLeft = layout?.let { result ->
                val box = IntSize(
                    (size.width - margin * 2).roundToInt().coerceAtLeast(0),
                    (size.height - margin * 2).roundToInt().coerceAtLeast(0),
                )
                val offset = emojiAlignment.align(result.size, box, layoutDirection)
                Offset(offset.x + margin, offset.y + margin)
            }
            onDrawBehind {
                drawCoverBackground(spec, colors)
                if (layout != null && emojiTopLeft != null) {
                    val center = Offset(
                        emojiTopLeft.x + layout.size.width / 2f,
                        emojiTopLeft.y + layout.size.height / 2f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(colors.coverGlow.copy(alpha = 0.42f), Color.Transparent),
                            center = center,
                            radius = emojiPx * 1.05f,
                        ),
                        radius = emojiPx * 1.05f,
                        center = center,
                    )
                    rotate(spec.emojiTilt, pivot = center) {
                        drawText(layout, topLeft = emojiTopLeft)
                    }
                }
            }
        },
    )
}

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
