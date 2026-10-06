package com.cedrickgd.morgenbriefing.ui.glass

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * The app-wide blur state. [AmbientSky] registers the sky as `hazeSource` (zIndex 0), every
 * page registers its scrolling content above it ([glassContent], zIndex 1). Glass *inside* the
 * content (cards, chips) blurs only the sky beneath it; floating glass above the content (top
 * bars, the page indicator, the mini player) blurs sky **and** content, so pictures glide softly
 * blurred beneath it. Null (previews, tests) → glass falls back to a frosted fill.
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Real blur needs RenderEffect (Android 12+). Below that Haze could only paint a scrim, so the
 * app skips Haze there entirely (no recording, no extra layers) and paints a frosted tint.
 */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Glass thickness levels from docs/DESIGN.md §2. Tints are alphas of [glassTint]: in light mode
 * the theme surface, in dark mode a lifted, faintly accent-tinted night tone that is lighter
 * than the sky — dark glass reads as frosted glass, not as a hole.
 */
enum class GlassLevel(val blur: Dp, val tintLight: Float, val tintDark: Float, val noise: Float) {
    /** Chips, pills, the page indicator. */
    Thin(20.dp, 0.34f, 0.40f, 0.05f),

    /** Cards. */
    Regular(28.dp, 0.50f, 0.36f, 0.06f),

    /** Dialogs, sheets, top bars. */
    Thick(36.dp, 0.62f, 0.58f, 0.07f),
}

object GlassShapes {
    val card = RoundedCornerShape(28.dp)
    val tile = RoundedCornerShape(22.dp)
    val chip = RoundedCornerShape(16.dp)
    val pill = CircleShape
}

/** Dark when the theme background is dark — works for dynamic and custom schemes alike. */
@Composable
fun isGlassDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

/**
 * The color glass is tinted with: [tint] if given; else the theme surface in light mode, and
 * in dark mode the highest container lifted towards white with a breath of the accent.
 */
@Composable
fun glassTint(tint: Color? = null): Color {
    if (tint != null) return tint
    val scheme = MaterialTheme.colorScheme
    return if (isGlassDark()) {
        lerp(lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, 0.08f), Color.White, 0.07f)
    } else {
        scheme.surface
    }
}

/**
 * Haze style for [level], tinted with [tint] (defaults to [glassTint]). [floating] glass hovers
 * over pictures of any brightness, so its tint never drops below a floor that keeps icons and
 * text on it legible (≈ 3:1 for icons even over a white or black photo).
 */
@Composable
fun glassStyle(level: GlassLevel = GlassLevel.Regular, tint: Color? = null, floating: Boolean = false): HazeStyle {
    val dark = isGlassDark()
    val base = glassTint(tint)
    val levelAlpha = if (dark) level.tintDark else level.tintLight
    val alpha = if (floating) maxOf(levelAlpha, if (dark) FLOATING_MIN_DARK else FLOATING_MIN_LIGHT) else levelAlpha
    return HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tints = listOf(HazeTint(base.copy(alpha = alpha))),
        blurRadius = level.blur,
        noiseFactor = level.noise,
        fallbackTint = HazeTint(base.copy(alpha = fallbackAlpha(alpha, floating = false))),
    )
}

/**
 * Without blur, whatever lies under the glass would show through. Glass inside the content only
 * ever covers the smooth sky: a denser, milky tint is enough and the sky still glows through a
 * little. [floating] glass (top bars, page indicator, mini player, sheets, dialogs) covers
 * scrolling text and pictures: it becomes practically opaque, so nothing ghosts through.
 */
internal fun fallbackAlpha(alpha: Float, floating: Boolean): Float =
    if (floating) FLOATING_FALLBACK_ALPHA else (alpha + 0.30f).coerceAtMost(0.92f)

private const val FLOATING_FALLBACK_ALPHA = 0.97f

/** Lowest tint of floating glass with real blur (light / dark). */
private const val FLOATING_MIN_LIGHT = 0.62f
private const val FLOATING_MIN_DARK = 0.66f

/** Light catching the top-left edge, fading towards the bottom-right. */
@Composable
fun glassEdgeBrush(): Brush {
    val dark = isGlassDark()
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.30f else 0.55f),
        0.45f to Color.White.copy(alpha = if (dark) 0.10f else 0.18f),
        1f to Color.White.copy(alpha = if (dark) 0.05f else 0.08f),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * The sky is blurred at half resolution: with 20–36 dp of blur the result looks the same, but
 * the GPU blurs a quarter of the pixels – it matters because the living sky re-blurs every
 * glass surface on every frame. (Haze's `Auto` picks ⅓, which makes the grain visibly blocky.)
 */
private val GlassInputScale: HazeInputScale = HazeInputScale.Fixed(0.5f)

/**
 * Registers a page's scrolling content as a blur source above the sky, so floating glass (top
 * bars, page indicator, mini player) blurs the content gliding beneath it. Apply it to the
 * scroll container; glass inside it keeps blurring the sky only. A no-op without real blur.
 */
fun Modifier.glassContent(zIndex: Float = 1f): Modifier = composed {
    val state = LocalGlassState.current
    if (state != null && GlassBlurSupported) hazeSource(state, zIndex = zIndex) else Modifier
}

/**
 * Turns any element into frosted glass: the blurred sky (and, for glass floating above a page,
 * the blurred content) behind, tint, grain, a soft inner sheen at the top and a light-catching
 * 1 dp edge. Set [floating] for glass that hovers over scrolling content: without real blur
 * (Android < 12) it then turns practically opaque instead of letting text ghost through.
 */
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    level: GlassLevel = GlassLevel.Regular,
    tint: Color? = null,
    edge: Boolean = true,
    floating: Boolean = false,
): Modifier = composed {
    val state = LocalGlassState.current
    val dark = isGlassDark()
    val sheen = Color.White.copy(alpha = if (dark) 0.10f else 0.16f)
    val edgeBrush = glassEdgeBrush()

    val surface = clip(shape).then(
        if (state != null && GlassBlurSupported) {
            val style = glassStyle(level, tint, floating)
            Modifier.hazeEffect(state = state, style = style) { inputScale = GlassInputScale }
        } else {
            val alpha = fallbackAlpha(if (dark) level.tintDark else level.tintLight, floating)
            Modifier.background(glassTint(tint).copy(alpha = alpha))
        },
    )
    val withSheen = surface.drawBehind {
        drawRect(
            Brush.verticalGradient(
                0f to sheen,
                (18.dp.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent,
            ),
        )
    }
    if (edge) withSheen.border(1.dp, edgeBrush, shape) else withSheen
}

/**
 * Makes a floating surface (top bar, indicator, player) the target of every touch inside it:
 * taps on its empty parts no longer reach the list scrolling beneath. Children keep their own
 * click handling; nothing is consumed, the surface just takes part in hit testing.
 */
fun Modifier.blockTouchesBehind(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * A glass card: [GlassLevel.Regular], 28 dp corners, 20 dp padding, gentle press feedback.
 * Content color is the theme's onSurface so text stays readable on every sky.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Regular,
    shape: Shape = GlassShapes.card,
    tint: Color? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "glassPress",
    )
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .glass(shape = shape, level = level, tint = tint)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = ripple(),
                            onClickLabel = onClickLabel,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            content = content,
        )
    }
}
