package com.cedrickgd.morgenbriefing.ui.glass

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.ui.components.SkyKind
import com.cedrickgd.morgenbriefing.ui.components.WeatherBackdrop
import com.cedrickgd.morgenbriefing.ui.components.skyPalette
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.statusBarTop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stage behind the whole app (docs/DESIGN.md §1): a full-height sky gradient for the
 * current weather and time of day, slowly drifting aurora light and the weather particles at
 * the top. It is the blur source for every glass surface ([hazeState]).
 *
 * [parallax] returns how far the pager is from its centre page, in pages (e.g. -1…1 for three
 * pages with "Heute" in the middle); the sky shifts slightly against it so pages appear to
 * glide over a stage that stays put. It is read in the draw phase only.
 *
 * A change of weather, time of day or light/dark cross-fades to the new sky; with the system
 * "remove animations" setting everything is static.
 */
@Composable
fun AmbientSky(
    condition: Condition,
    isDay: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    parallax: () -> Float = { 0f },
) {
    val reduceMotion = rememberReduceMotion()
    val look = SkyLook(SkyKind.of(condition), condition, isDay, isGlassDark())

    // One clock for every sky, so the aurora keeps drifting through a cross-fade.
    val clock: State<Float>? = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = AURORA_LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "auroraClock",
        )
    }

    Box(modifier.fillMaxSize().hazeSource(state = hazeState)) {
        Crossfade(
            targetState = look,
            animationSpec = if (reduceMotion) snap() else tween(SKY_CROSSFADE_MS),
            label = "sky",
        ) { sky ->
            SkyLayers(sky, clock, reduceMotion, parallax)
        }
    }
}

/** Everything that changes the sky's colors or particles. */
@Immutable
private data class SkyLook(val kind: SkyKind, val condition: Condition, val isDay: Boolean, val dark: Boolean)

@Composable
private fun SkyLayers(look: SkyLook, clock: State<Float>?, reduceMotion: Boolean, parallax: () -> Float) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(look) { skyPalette(look.kind, look.isDay, look.dark) }

    val background = scheme.background
    val lowerA = lerp(background, scheme.primaryContainer, if (look.dark) 0.24f else 0.32f)
    val lowerB = lerp(background, scheme.tertiaryContainer, if (look.dark) 0.22f else 0.30f)
    // Light fields: the sky's own colors plus a breath of the accent and its tertiary partner, so
    // the lower half of every page stays alive. Kept light enough that text directly on the sky
    // keeps its contrast.
    val auroraAlpha = if (look.dark) 0.32f else 0.44f
    val accentLight = lerp(scheme.primaryContainer, scheme.primary, if (look.dark) 0.12f else 0.22f)
    val tertiaryLight = lerp(scheme.tertiaryContainer, scheme.tertiary, if (look.dark) 0.10f else 0.20f)
    val aurora = listOf(
        palette.top.copy(alpha = auroraAlpha),
        accentLight.copy(alpha = auroraAlpha * 0.9f),
        palette.mid.copy(alpha = auroraAlpha * 0.9f),
        tertiaryLight.copy(alpha = auroraAlpha * 0.85f),
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Oversized so the parallax shift never reveals an edge.
                    scaleX = 1f + 2f * PARALLAX_PER_PAGE
                    scaleY = 1.04f
                    translationX = -parallax().coerceIn(-1f, 1f) * size.width * PARALLAX_PER_PAGE
                },
        ) {
            drawRect(
                Brush.verticalGradient(
                    0f to palette.top,
                    0.32f to palette.mid,
                    0.62f to lerp(palette.mid, lowerA, 0.7f),
                    1f to lowerB,
                ),
            )
            drawAurora(aurora, clock?.value ?: STATIC_AURORA_TIME)
        }

        // Weather particles and glow for the upper part; its built-in fade to the plain
        // background is masked away so it melts into the full-height gradient instead.
        WeatherBackdrop(
            condition = look.condition,
            isDay = look.isDay,
            animate = !reduceMotion,
            modifier = Modifier
                .fillMaxWidth()
                .height(SKY_HEIGHT + statusBarTop())
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // Moves less than the gradient (depth) and is wide enough for the shift.
                    scaleX = 1f + 2f * PARALLAX_PER_PAGE * BACKDROP_DEPTH
                    translationX = -parallax().coerceIn(-1f, 1f) * size.width * PARALLAX_PER_PAGE * BACKDROP_DEPTH
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.38f to Color.Black,
                            0.66f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

/** Soft radial light fields drifting on slow Lissajous paths. */
private fun DrawScope.drawAurora(colors: List<Color>, t: Float) {
    val w = size.width
    val h = size.height
    colors.forEachIndexed { i, color ->
        val phase = t * 2f * PI.toFloat() + i * 1.7f
        val cx = w * (0.5f + 0.38f * sin(phase * (1f + i * 0.13f)))
        val cy = h * (0.18f + i * 0.22f + 0.06f * cos(phase * 0.8f))
        val radius = w * (0.62f + 0.12f * ((i % 2) + sin(phase * 0.5f) * 0.5f))
        drawCircle(
            brush = Brush.radialGradient(
                0f to color,
                0.55f to color.copy(alpha = color.alpha * 0.45f),
                1f to Color.Transparent,
                center = Offset(cx, cy),
                radius = radius,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )
    }
}

/** Sky shift per page of pager offset, as a fraction of the width (≈ 12 % across all pages). */
private const val PARALLAX_PER_PAGE = 0.06f

/** The particle layer is "further away" and moves at 60 % of the gradient's shift. */
private const val BACKDROP_DEPTH = 0.6f

private const val AURORA_LOOP_MS = 96_000
private const val STATIC_AURORA_TIME = 0.18f
private const val SKY_CROSSFADE_MS = 1_200
private val SKY_HEIGHT = 560.dp
