package com.cedrickgd.morgenbriefing.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.ui.components.SkyKind
import com.cedrickgd.morgenbriefing.ui.components.WeatherBackdrop
import com.cedrickgd.morgenbriefing.ui.components.skyPalette
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.statusBarTop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
 * "remove animations" setting everything is static. While [paused] returns true (the shell
 * passes "a list is scrolling") the aurora holds still; while [covered] (an opaque screen such
 * as the reader lies over all of it) nothing moves at all. On Android 7, low-RAM devices and in
 * battery saver the aurora drifts at 10 fps and the particles stand still.
 */
@Composable
fun AmbientSky(
    condition: Condition,
    isDay: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    parallax: () -> Float = { 0f },
    paused: () -> Boolean = { false },
    covered: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val context = LocalContext.current
    // Old or low-memory devices (or battery saver): the sky drifts at 10 fps and the weather
    // particles stand still – it still looks alive, but costs a fraction of the work.
    val gentle = remember(context) { prefersGentleSky(context) }
    val look = SkyLook(SkyKind.of(condition), condition, isDay, isGlassDark())

    // One clock for every sky, so the aurora keeps drifting through a cross-fade.
    val clock: State<Float>? = if (reduceMotion) {
        null
    } else {
        rememberAuroraClock({ covered || paused() }, frameIntervalMs = if (gentle) GENTLE_FRAME_MS else 0L)
    }

    Box(
        modifier
            .fillMaxSize()
            .then(if (GlassBlurSupported) Modifier.hazeSource(state = hazeState, zIndex = 0f) else Modifier),
    ) {
        Crossfade(
            targetState = look,
            animationSpec = if (reduceMotion) snap() else tween(SKY_CROSSFADE_MS),
            label = "sky",
        ) { sky ->
            SkyLayers(sky, clock, reduceMotion || gentle || covered, parallax)
        }
    }
}

/**
 * The aurora's time, 0..1 over [AURORA_LOOP_MS]. Advances frame by frame (or every
 * [frameIntervalMs]) and holds still while [paused] — e.g. while a list scrolls, so the sky
 * does not compete with the content for frames. Holding (instead of restarting) means the
 * light never jumps.
 */
@Composable
private fun rememberAuroraClock(paused: () -> Boolean, frameIntervalMs: Long): State<Float> {
    val time = remember { mutableFloatStateOf(STATIC_AURORA_TIME) }
    val latestPaused by rememberUpdatedState(paused)
    LaunchedEffect(frameIntervalMs) {
        snapshotFlow { latestPaused() }.collectLatest { isPaused ->
            if (isPaused) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                if (frameIntervalMs > 0) delay(frameIntervalMs)
                val now = withFrameNanos { it }
                val elapsedMs = ((now - last) / 1_000_000f).coerceIn(0f, MAX_STEP_MS)
                last = now
                time.floatValue = (time.floatValue + elapsedMs / AURORA_LOOP_MS) % 1f
            }
        }
    }
    return time
}

/** Android 7, a low-RAM device or battery saver: keep the sky gentle on the battery. */
private fun prefersGentleSky(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val activity = context.getSystemService(ActivityManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    return activity?.isLowRamDevice == true || power?.isPowerSaveMode == true
}

/** Everything that changes the sky's colors or particles. */
@Immutable
private data class SkyLook(val kind: SkyKind, val condition: Condition, val isDay: Boolean, val dark: Boolean)

@Composable
private fun SkyLayers(look: SkyLook, clock: State<Float>?, stillParticles: Boolean, parallax: () -> Float) {
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
    // A warm horizon glow low on the page: the end of a long feed never turns into a flat wash.
    val horizon = lerp(lerp(scheme.tertiaryContainer, scheme.primary, if (look.dark) 0.28f else 0.36f), palette.mid, 0.15f)
        .copy(alpha = if (look.dark) 0.30f else 0.34f)

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
            val t = clock?.value ?: STATIC_AURORA_TIME
            drawAurora(aurora, t)
            drawHorizon(horizon, t)
        }

        // Weather particles and glow for the upper part; its built-in fade to the plain
        // background is masked away so it melts into the full-height gradient instead.
        WeatherBackdrop(
            condition = look.condition,
            isDay = look.isDay,
            animate = !stillParticles,
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

/**
 * A wide, breathing glow rising from below the bottom edge (docs/DESIGN.md §1: the bottom of
 * the page is never one flat color).
 */
private fun DrawScope.drawHorizon(color: Color, t: Float) {
    val w = size.width
    val h = size.height
    val phase = t * 2f * PI.toFloat()
    val center = Offset(w * (0.5f + 0.18f * sin(phase * 1.3f)), h * (1.02f + 0.02f * cos(phase)))
    val radius = w * (0.95f + 0.08f * sin(phase * 0.7f))
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            0.5f to color.copy(alpha = color.alpha * 0.5f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Sky shift per page of pager offset, as a fraction of the width (≈ 12 % across all pages). */
private const val PARALLAX_PER_PAGE = 0.06f

/** The particle layer is "further away" and moves at 60 % of the gradient's shift. */
private const val BACKDROP_DEPTH = 0.6f

private const val AURORA_LOOP_MS = 96_000
private const val STATIC_AURORA_TIME = 0.18f
private const val SKY_CROSSFADE_MS = 1_200

/** Frame interval of the gentle sky (10 fps). */
private const val GENTLE_FRAME_MS = 100L

/** A long pause (app in the background) must not jump the aurora forward. */
private const val MAX_STEP_MS = 250f
private val SKY_HEIGHT = 560.dp
