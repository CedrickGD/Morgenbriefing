package com.cedrickgd.morgenbriefing.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class PageSpec(val label: String, val icon: ImageVector)

/**
 * Floating glass pill (docs/DESIGN.md §3) that shows where you are in the page pager. A colored
 * capsule slides between the icons with the swipe and stretches a little in between, like a
 * drop of liquid. Tapping an icon calls [onSelect]. Reads the pager offset only in the
 * layout/draw phase, so swiping never recomposes it.
 */
@Composable
fun GlassPageIndicator(
    pagerState: PagerState,
    pages: List<PageSpec>,
    visible: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    // Full-strength ink: the pill floats over photos of any brightness.
    val idle = MaterialTheme.colorScheme.onSurface
    // A drop of colored glass: lit from above, with a bright rim along its top edge.
    val capsuleFill = remember(accent) {
        Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.16f), accent, lerp(accent, Color.Black, 0.06f)))
    }
    val capsuleEdge = remember {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.04f)))
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) +
            slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 2 },
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.9f) +
            slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it / 2 },
    ) {
        Box(
            Modifier
                // A very soft ambient shadow only (docs/DESIGN.md §2: floating, never heavy).
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.10f),
                )
                // Regular, not Thin: it floats over pictures too, and its icons must stay readable.
                .glass(shape = GlassShapes.pill, level = GlassLevel.Regular, floating = true)
                // Taps on its padding never fall through to the list underneath.
                .blockTouchesBehind()
                .height(PILL_HEIGHT)
                .padding(horizontal = PILL_PADDING),
        ) {
            // The liquid capsule.
            Box(
                Modifier
                    .width(ITEM_WIDTH)
                    .fillMaxHeight()
                    .padding(vertical = 5.dp)
                    .graphicsLayer {
                        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val between = abs(pagerState.currentPageOffsetFraction)
                        val stretch = 1f + 0.42f * sin(PI.toFloat() * between)
                        translationX = position * ITEM_WIDTH.toPx()
                        scaleX = stretch
                        scaleY = 1f - 0.1f * sin(PI.toFloat() * between)
                    }
                    .background(capsuleFill, CircleShape)
                    .border(1.dp, capsuleEdge, CircleShape),
            )
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                pages.forEachIndexed { index, page ->
                    Box(
                        Modifier
                            .width(ITEM_WIDTH)
                            .fillMaxHeight()
                            .selectable(
                                selected = pagerState.currentPage == index,
                                onClick = { onSelect(index) },
                                role = Role.Tab,
                                indication = ripple(bounded = false, radius = 24.dp),
                                interactionSource = null,
                            )
                            .semantics { contentDescription = page.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Two tints cross-faded in the draw phase (no recomposition while swiping).
                        Box(
                            Modifier.graphicsLayer {
                                val distance = abs(pagerState.currentPage + pagerState.currentPageOffsetFraction - index)
                                val t = (1f - distance).coerceIn(0f, 1f)
                                scaleX = 0.92f + 0.12f * t
                                scaleY = scaleX
                            },
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                tint = idle,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { alpha = 1f - iconEmphasis(pagerState, index) },
                            )
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                tint = onAccent,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { alpha = iconEmphasis(pagerState, index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 1 when [index] is the settled page, 0 when it is a full page away. */
private fun iconEmphasis(pagerState: PagerState, index: Int): Float {
    val distance = abs(pagerState.currentPage + pagerState.currentPageOffsetFraction - index)
    return (1f - distance * 1.6f).coerceIn(0f, 1f)
}

/** Height of the [GlassPageIndicator] pill (layout room the shell keeps free below content). */
val GlassPageIndicatorHeight: Dp = 48.dp

private val PILL_HEIGHT = GlassPageIndicatorHeight
private val PILL_PADDING = 6.dp
private val ITEM_WIDTH = 58.dp
