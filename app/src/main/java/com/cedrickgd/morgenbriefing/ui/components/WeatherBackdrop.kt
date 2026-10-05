package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.domain.Condition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** One full animation loop; every motion completes a whole number of cycles in it (seamless). */
private const val LOOP_MILLIS = 120_000

/** Frozen moment used when motion is off — chosen so no lightning flash is visible. */
private const val STATIC_TIME = 0.37f

private const val TWO_PI = (2 * PI).toFloat()

/**
 * Animated sky behind the top of the screen (edge-to-edge, under the status bar).
 *
 * Layered gradients per [condition] and time of day: a warm sunlit morning, drifting clouds,
 * slow fog banks, thin rain streaks, drifting snow, rare soft lightning or a starry night with a
 * crescent moon. Everything is drawn on one Canvas from a single looping clock that is only read
 * in the draw phase, so the sky never triggers recomposition. The bottom fades into
 * `MaterialTheme.colorScheme.background`. Static when [animate] is false or the system
 * "remove animations" setting is on. Purely decorative (no semantics).
 */
@Composable
fun WeatherBackdrop(condition: Condition, isDay: Boolean, modifier: Modifier = Modifier, animate: Boolean = true) {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val kind = SkyKind.of(condition)
    val palette = remember(kind, isDay, dark) { skyPalette(kind, isDay, dark) }
    val scene = remember(kind, isDay) { SkyScene.create(kind, isDay) }

    // The moon sits just below the status bar on the right, beside the short date/greeting
    // lines of the header rather than behind the long headline.
    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)

    val moving = animate && !motionDisabled()
    val clock: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "sky").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing)),
            label = "skyClock",
        )
    } else {
        remember { mutableFloatStateOf(STATIC_TIME) }
    }

    Spacer(
        modifier.clipToBounds().drawWithCache {
            val w = size.width
            val h = size.height
            val base = if (scene.diagonal) {
                Brush.linearGradient(
                    colors = listOf(palette.top, palette.mid),
                    start = Offset.Zero,
                    end = Offset(w * 0.75f, h * 0.7f),
                )
            } else {
                Brush.verticalGradient(listOf(palette.top, palette.mid), startY = 0f, endY = h * 0.7f)
            }
            val fade = Brush.verticalGradient(
                0f to background.copy(alpha = 0f),
                0.42f to background.copy(alpha = 0f),
                0.7f to background.copy(alpha = 0.55f),
                0.88f to background.copy(alpha = 0.9f),
                1f to background,
                startY = 0f,
                endY = h,
            )
            val unitCloud = unitPuffBrush(palette.cloud)
            val unitFog = unitFogBrush(palette.fog)

            val sunCenter = Offset(w * 0.1f, h * 0.02f)
            val sunRadius = max(w, h) * 0.8f
            val sunBrush = Brush.radialGradient(
                0f to palette.glow,
                0.4f to palette.glow.copy(alpha = palette.glow.alpha * 0.45f),
                1f to palette.glow.copy(alpha = 0f),
                center = sunCenter,
                radius = sunRadius,
            )

            val moonRadius = 15.dp.toPx()
            val moonCenter = Offset(w - 52.dp.toPx(), statusBarTop + 30.dp.toPx())
            val haloBrush = Brush.radialGradient(
                0f to palette.glow,
                1f to palette.glow.copy(alpha = 0f),
                center = moonCenter,
                radius = moonRadius * 5f,
            )
            val moonShadow = Path().apply {
                addOval(Rect(center = Offset(moonCenter.x + moonRadius * 0.45f, moonCenter.y - moonRadius * 0.2f), radius = moonRadius * 0.9f))
            }

            onDrawBehind {
                val t = clock.value
                drawRect(base)
                if (scene.sunGlow) {
                    val pulse = 1f + 0.04f * wave(t, 4)
                    withTransform({ scale(pulse, pulse, pivot = sunCenter) }) {
                        drawCircle(sunBrush, radius = sunRadius, center = sunCenter)
                    }
                }
                if (scene.stars.isNotEmpty()) drawStars(scene.stars, palette.particle, t)
                if (scene.moon) {
                    drawCircle(haloBrush, radius = moonRadius * 5f, center = moonCenter, alpha = 0.85f + 0.15f * wave(t, 3))
                    clipPath(moonShadow, clipOp = ClipOp.Difference) {
                        drawCircle(palette.moon, radius = moonRadius, center = moonCenter)
                    }
                }
                if (scene.clouds.isNotEmpty()) drawClouds(scene.clouds, unitCloud, t)
                if (scene.fogBands.isNotEmpty()) drawFog(scene.fogBands, unitFog, t)
                if (scene.drops.isNotEmpty()) drawRain(scene.drops, palette.particle, scene.dropStroke.dp.toPx(), t)
                if (scene.flakes.isNotEmpty()) drawSnow(scene.flakes, palette.particle, t)
                if (scene.lightning) {
                    val flash = lightning(t)
                    if (flash > 0.001f) drawRect(palette.flash.copy(alpha = palette.flash.alpha * flash))
                }
                drawRect(fade)
            }
        },
    )
}

// -------------------------------------------------------------------------------------------
// Scene description (fractions of the canvas, created once per kind)
// -------------------------------------------------------------------------------------------

/**
 * @property x horizontal position 0..1, @property y vertical start 0..1,
 * @property size radius or streak length in dp, @property cycles whole loops per clock period,
 * @property phase 0..1 offset, @property alpha 0..1 multiplier, @property sway secondary cycles.
 */
private class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val cycles: Int,
    val phase: Float,
    val alpha: Float,
    val sway: Int,
)

private class Cloud(val x: Float, val y: Float, val width: Float, val cycles: Int, val alpha: Float)

private class FogBand(
    val y: Float,
    val halfHeight: Float,
    val halfWidth: Float,
    val cycles: Int,
    val phase: Float,
    val amplitude: Float,
    val alpha: Float,
)

private class SkyScene(
    val diagonal: Boolean,
    val sunGlow: Boolean,
    val moon: Boolean,
    val lightning: Boolean,
    val stars: List<Particle>,
    val clouds: List<Cloud>,
    val fogBands: List<FogBand>,
    val drops: List<Particle>,
    val dropStroke: Float,
    val flakes: List<Particle>,
) {
    companion object {
        fun create(kind: SkyKind, isDay: Boolean): SkyScene {
            val random = Random(kind.ordinal * 31 + if (isDay) 7 else 13)
            val night = !isDay
            val starCount = when {
                !night -> 0
                kind == SkyKind.CLEAR -> 46
                kind == SkyKind.FEW_CLOUDS -> 36
                kind == SkyKind.CLOUDY -> 16
                else -> 0
            }
            val cloudCount = when (kind) {
                SkyKind.CLEAR, SkyKind.FOG -> 0
                SkyKind.FEW_CLOUDS -> 2
                SkyKind.SNOW -> 3
                SkyKind.CLOUDY -> 4
                SkyKind.OVERCAST -> 6
                SkyKind.DRIZZLE -> 3
                SkyKind.RAIN -> 4
                SkyKind.HEAVY_RAIN, SkyKind.THUNDER -> 5
            }
            val dropCount = when (kind) {
                SkyKind.DRIZZLE -> 34
                SkyKind.RAIN -> 56
                SkyKind.HEAVY_RAIN -> 84
                SkyKind.THUNDER -> 64
                else -> 0
            }
            return SkyScene(
                diagonal = isDay && (kind == SkyKind.CLEAR || kind == SkyKind.FEW_CLOUDS),
                sunGlow = isDay && (kind == SkyKind.CLEAR || kind == SkyKind.FEW_CLOUDS || kind == SkyKind.CLOUDY),
                moon = night && (kind == SkyKind.CLEAR || kind == SkyKind.FEW_CLOUDS),
                lightning = kind == SkyKind.THUNDER,
                stars = List(starCount) {
                    Particle(
                        x = random.nextFloat(),
                        // Upper sky only, so no stars twinkle behind the hero card's text.
                        y = random.nextFloat() * 0.45f,
                        size = 0.6f + random.nextFloat() * 1.0f,
                        cycles = 10 + random.nextInt(16),
                        phase = random.nextFloat(),
                        alpha = 0.45f + random.nextFloat() * 0.55f,
                        sway = 0,
                    )
                },
                clouds = List(cloudCount) { i ->
                    Cloud(
                        x = (i + random.nextFloat() * 0.6f) / cloudCount.coerceAtLeast(1),
                        y = 0.06f + random.nextFloat() * if (kind.isRainy || kind == SkyKind.OVERCAST) 0.3f else 0.38f,
                        width = 0.32f + random.nextFloat() * 0.3f,
                        cycles = 1 + if (random.nextFloat() < 0.35f) 1 else 0,
                        alpha = 0.7f + random.nextFloat() * 0.3f,
                    )
                },
                fogBands = if (kind == SkyKind.FOG) {
                    List(4) { i ->
                        FogBand(
                            y = 0.12f + i * 0.13f + random.nextFloat() * 0.04f,
                            halfHeight = 0.11f + random.nextFloat() * 0.06f,
                            halfWidth = 0.75f + random.nextFloat() * 0.35f,
                            cycles = 1 + i % 3,
                            phase = random.nextFloat(),
                            amplitude = 0.08f + random.nextFloat() * 0.07f,
                            alpha = 0.55f + random.nextFloat() * 0.45f,
                        )
                    }
                } else {
                    emptyList()
                },
                drops = List(dropCount) {
                    Particle(
                        x = random.nextFloat() * 1.15f - 0.15f,
                        y = random.nextFloat(),
                        size = if (kind == SkyKind.DRIZZLE) 6f + random.nextFloat() * 6f else 10f + random.nextFloat() * 12f,
                        cycles = (if (kind == SkyKind.DRIZZLE) 60 else 80) + random.nextInt(35),
                        phase = 0f,
                        alpha = 0.35f + random.nextFloat() * 0.65f,
                        sway = 0,
                    )
                },
                dropStroke = when (kind) {
                    SkyKind.DRIZZLE -> 1f
                    SkyKind.HEAVY_RAIN -> 1.5f
                    else -> 1.25f
                },
                flakes = if (kind == SkyKind.SNOW) {
                    List(52) {
                        Particle(
                            x = random.nextFloat(),
                            y = random.nextFloat(),
                            size = 1.2f + random.nextFloat() * 1.9f,
                            cycles = 8 + random.nextInt(7),
                            phase = random.nextFloat(),
                            alpha = 0.55f + random.nextFloat() * 0.45f,
                            sway = 18 + random.nextInt(14),
                        )
                    }
                } else {
                    emptyList()
                },
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Drawing
// -------------------------------------------------------------------------------------------

/** sin wave −1..1 that completes [cycles] whole periods per clock loop. */
private fun wave(t: Float, cycles: Int, phase: Float = 0f): Float = sin(TWO_PI * (t * cycles + phase))

private fun frac(v: Float): Float = v - floor(v)

/** Soft round puff of radius 1 around the origin; scaled into place when drawn. */
private fun unitPuffBrush(color: Color): Brush = Brush.radialGradient(
    0f to color,
    0.5f to color.copy(alpha = color.alpha * 0.8f),
    1f to color.copy(alpha = 0f),
    center = Offset.Zero,
    radius = 1f,
)

/** Like [unitPuffBrush] but with a long, gentle falloff so stretched bands have no visible edge. */
private fun unitFogBrush(color: Color): Brush = Brush.radialGradient(
    0f to color,
    0.3f to color.copy(alpha = color.alpha * 0.75f),
    0.65f to color.copy(alpha = color.alpha * 0.28f),
    1f to color.copy(alpha = 0f),
    center = Offset.Zero,
    radius = 1f,
)

private fun DrawScope.drawStars(stars: List<Particle>, color: Color, t: Float) {
    val w = size.width
    val h = size.height
    for (star in stars) {
        val twinkle = 0.5f + 0.5f * wave(t, star.cycles, star.phase)
        val alpha = color.alpha * star.alpha * (0.35f + 0.65f * twinkle)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = star.size.dp.toPx(),
            center = Offset(star.x * w, star.y * h),
        )
    }
}

/** Three overlapping soft puffs per cloud (left, crown, right), drifting slowly to the right. */
private fun DrawScope.drawClouds(clouds: List<Cloud>, unitPuff: Brush, t: Float) {
    val w = size.width
    val h = size.height
    for (cloud in clouds) {
        val cloudWidth = cloud.width * w
        val travel = w + cloudWidth * 2f
        val cx = frac(cloud.x + t * cloud.cycles) * travel - cloudWidth
        val cy = cloud.y * h
        drawPuff(unitPuff, cx - cloudWidth * 0.28f, cy + cloudWidth * 0.06f, cloudWidth * 0.3f, cloud.alpha)
        drawPuff(unitPuff, cx, cy - cloudWidth * 0.05f, cloudWidth * 0.4f, cloud.alpha)
        drawPuff(unitPuff, cx + cloudWidth * 0.3f, cy + cloudWidth * 0.08f, cloudWidth * 0.26f, cloud.alpha)
    }
}

private fun DrawScope.drawPuff(unitPuff: Brush, x: Float, y: Float, radius: Float, alpha: Float) {
    withTransform({
        translate(x, y)
        scale(radius, radius, pivot = Offset.Zero)
    }) {
        drawCircle(unitPuff, radius = 1f, center = Offset.Zero, alpha = alpha)
    }
}

/** Long, flat, translucent fog banks that breathe sideways. */
private fun DrawScope.drawFog(bands: List<FogBand>, unitPuff: Brush, t: Float) {
    val w = size.width
    val h = size.height
    for (band in bands) {
        val x = w * 0.5f + wave(t, band.cycles, band.phase) * band.amplitude * w
        val y = band.y * h + wave(t, band.cycles + 1, band.phase) * h * 0.012f
        withTransform({
            translate(x, y)
            scale(band.halfWidth * w, band.halfHeight * h, pivot = Offset.Zero)
        }) {
            drawCircle(unitPuff, radius = 1f, center = Offset.Zero, alpha = band.alpha)
        }
    }
}

/** Thin slanted streaks falling over the upper ~85 % (the fade swallows their ends). */
private fun DrawScope.drawRain(drops: List<Particle>, color: Color, stroke: Float, t: Float) {
    val w = size.width
    val fallHeight = size.height * 0.85f
    val slant = 0.14f
    for (drop in drops) {
        val length = drop.size.dp.toPx()
        val progress = frac(drop.y + t * drop.cycles)
        val y = -length + progress * (fallHeight + length)
        val x = drop.x * w + y * slant
        drawLine(
            color = color.copy(alpha = color.alpha * drop.alpha),
            start = Offset(x, y),
            end = Offset(x + length * slant, y + length),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Slowly falling flakes that sway sideways. */
private fun DrawScope.drawSnow(flakes: List<Particle>, color: Color, t: Float) {
    val w = size.width
    val fallHeight = size.height * 0.85f
    val swayPx = 10.dp.toPx()
    for (flake in flakes) {
        val radius = flake.size.dp.toPx()
        val progress = frac(flake.y + t * flake.cycles)
        val y = -radius + progress * (fallHeight + radius)
        val x = flake.x * w + wave(t, flake.sway, flake.phase) * swayPx
        drawCircle(color.copy(alpha = color.alpha * flake.alpha), radius = radius, center = Offset(x, y))
    }
}

/** Rare, soft double flashes: three per loop (~every 40 s), never strobing. */
private fun lightning(t: Float): Float =
    max(
        max(pulse(t, 0.18f, 0.003f) + 0.6f * pulse(t, 0.186f, 0.002f), pulse(t, 0.53f, 0.0035f)),
        pulse(t, 0.81f, 0.003f) + 0.5f * pulse(t, 0.817f, 0.002f),
    ).coerceAtMost(1f)

private fun pulse(t: Float, center: Float, halfWidth: Float): Float {
    val d = 1f - abs(t - center) / halfWidth
    return if (d <= 0f) 0f else d * d
}
