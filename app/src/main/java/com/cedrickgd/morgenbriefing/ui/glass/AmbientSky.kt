package com.cedrickgd.morgenbriefing.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 * [parallax] returns the pager position in pages (e.g. 0.0…2.0); the sky shifts slightly with
 * it so pages appear to glide over a stage that stays put. Read only in the draw phase.
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
    val dark = isGlassDark()
    val scheme = MaterialTheme.colorScheme
    val kind = SkyKind.of(condition)
    val palette = remember(kind, isDay, dark) { skyPalette(kind, isDay, dark) }

    val background = scheme.background
    val lowerA = lerp(background, scheme.primaryContainer, if (dark) 0.22f else 0.30f)
    val lowerB = lerp(background, scheme.tertiaryContainer, if (dark) 0.20f else 0.28f)
    val auroraAlpha = if (dark) 0.30f else 0.42f
    val aurora = listOf(
        palette.top.copy(alpha = auroraAlpha),
        scheme.primaryContainer.copy(alpha = auroraAlpha),
        palette.mid.copy(alpha = auroraAlpha * 0.9f),
        scheme.tertiaryContainer.copy(alpha = auroraAlpha * 0.85f),
    )

    val clock: State<Float>? = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 96_000, easing = LinearEasing), RepeatMode.Restart),
            label = "auroraClock",
        )
    }

    Box(modifier.fillMaxSize().hazeSource(state = hazeState)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Oversized so the parallax shift never reveals an edge.
                    scaleX = 1.12f
                    scaleY = 1.04f
                    translationX = -parallax() * size.width * PARALLAX_PER_PAGE
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
            drawAurora(aurora, clock?.value ?: 0.18f)
        }

        // Weather particles and glow for the upper part; its built-in fade to the plain
        // background is masked away so it melts into the full-height gradient instead.
        WeatherBackdrop(
            condition = condition,
            isDay = isDay,
            animate = !reduceMotion,
            modifier = Modifier
                .fillMaxWidth()
                .height(SKY_HEIGHT + statusBarTop())
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationX = -parallax() * size.width * PARALLAX_PER_PAGE * 0.6f
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

private const val PARALLAX_PER_PAGE = 0.06f
private val SKY_HEIGHT = 560.dp
