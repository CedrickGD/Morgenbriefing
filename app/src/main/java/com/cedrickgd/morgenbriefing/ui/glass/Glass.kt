package com.cedrickgd.morgenbriefing.ui.glass

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The app-wide blur source: [AmbientSky] registers itself as `hazeSource`, every glass surface
 * blurs it. Null (previews, tests) → glass falls back to a frosted translucent fill.
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Glass thickness levels from docs/DESIGN.md §2. */
enum class GlassLevel(val blur: Dp, val tintLight: Float, val tintDark: Float, val noise: Float) {
    /** Chips, pills, the page indicator. */
    Thin(20.dp, 0.38f, 0.30f, 0.05f),

    /** Cards. */
    Regular(28.dp, 0.55f, 0.45f, 0.06f),

    /** Dialogs, sheets, top bars. */
    Thick(36.dp, 0.72f, 0.62f, 0.07f),
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

/** Haze style for [level], tinted with [tint] (defaults to the theme surface). */
@Composable
fun glassStyle(level: GlassLevel = GlassLevel.Regular, tint: Color? = null): HazeStyle {
    val dark = isGlassDark()
    val base = tint ?: MaterialTheme.colorScheme.surface
    val alpha = if (dark) level.tintDark else level.tintLight
    return HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tints = listOf(HazeTint(base.copy(alpha = alpha))),
        blurRadius = level.blur,
        noiseFactor = level.noise,
        fallbackTint = HazeTint(base.copy(alpha = fallbackAlpha(alpha))),
    )
}

/**
 * Below Android 12 there is no blur, so whatever lies under the glass (scrolling text under the
 * page indicator or a top bar) would show through: a milky, denser tint keeps text on the glass
 * readable while the sky's color still glows through a little.
 */
private fun fallbackAlpha(alpha: Float): Float = (alpha + 0.36f).coerceAtMost(0.96f)

/** Light catching the top-left edge, fading towards the bottom-right. */
@Composable
fun glassEdgeBrush(): Brush {
    val dark = isGlassDark()
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.22f else 0.55f),
        0.45f to Color.White.copy(alpha = if (dark) 0.08f else 0.18f),
        1f to Color.White.copy(alpha = if (dark) 0.04f else 0.08f),
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
 * Turns any element into frosted glass: blurred sky behind, tint, grain, a soft inner sheen
 * at the top and a light-catching 1 dp edge.
 */
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    level: GlassLevel = GlassLevel.Regular,
    tint: Color? = null,
    edge: Boolean = true,
): Modifier = composed {
    val state = LocalGlassState.current
    val style = glassStyle(level, tint)
    val dark = isGlassDark()
    val sheen = Color.White.copy(alpha = if (dark) 0.07f else 0.16f)
    val edgeBrush = glassEdgeBrush()
    val fallback = (tint ?: MaterialTheme.colorScheme.surface)
        .copy(alpha = fallbackAlpha(if (dark) level.tintDark else level.tintLight))

    val surface = clip(shape).then(
        if (state != null) {
            Modifier.hazeEffect(state = state, style = style) { inputScale = GlassInputScale }
        } else {
            Modifier.background(fallback)
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
