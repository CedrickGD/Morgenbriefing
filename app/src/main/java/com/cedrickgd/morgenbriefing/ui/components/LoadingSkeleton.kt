package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard

/**
 * Shimmering glass placeholders shaped like hero + curve + news while the first load runs.
 * The page header is real text already, so the skeleton starts with the hero.
 */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val shimmer = Shimmer(
        base = colors.onSurface.copy(alpha = if (dark) 0.08f else 0.07f),
        highlight = colors.onSurface.copy(alpha = if (dark) 0.16f else 0.13f),
        progress = rememberShimmerProgress(),
    )
    val shapes = MaterialTheme.shapes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Dein Briefing wird geladen"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero: overline, big temperature, chips.
        CardBone {
            Bone(shimmer, widthFraction = 0.55f, height = 12.dp, shape = shapes.extraSmall)
            Spacer(Modifier.height(18.dp))
            Bone(shimmer, widthFraction = 0.38f, height = 64.dp, shape = shapes.medium)
            Spacer(Modifier.height(14.dp))
            Bone(shimmer, widthFraction = 0.62f, height = 14.dp, shape = shapes.extraSmall)
            Spacer(Modifier.height(18.dp))
            Bone(shimmer, widthFraction = 0.9f, height = 30.dp, shape = shapes.large)
        }

        // Day curve card with a hint of the chart inside.
        CardBone {
            Bone(shimmer, widthFraction = 0.5f, height = 12.dp, shape = shapes.extraSmall)
            Spacer(Modifier.height(18.dp))
            Bone(shimmer, widthFraction = 1f, height = 150.dp, shape = shapes.medium)
            Spacer(Modifier.height(16.dp))
            Bone(shimmer, widthFraction = 0.9f, height = 14.dp, shape = shapes.extraSmall)
        }

        // Three news cards.
        repeat(3) {
            CardBone {
                Bone(shimmer, widthFraction = 0.78f, height = 18.dp, shape = shapes.extraSmall)
                Spacer(Modifier.height(12.dp))
                Bone(shimmer, widthFraction = 1f, height = 12.dp, shape = shapes.extraSmall)
                Spacer(Modifier.height(8.dp))
                Bone(shimmer, widthFraction = 0.64f, height = 12.dp, shape = shapes.extraSmall)
                Spacer(Modifier.height(16.dp))
                Bone(shimmer, widthFraction = 0.36f, height = 10.dp, shape = shapes.extraSmall)
            }
        }
    }
}

private class Shimmer(val base: Color, val highlight: Color, val progress: State<Float>)

/** Sweep position −1 … 2 (in widths); static (no highlight on screen) with reduced motion. */
@Composable
private fun rememberShimmerProgress(): State<Float> =
    if (motionDisabled()) {
        remember { mutableFloatStateOf(-1f) }
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
            label = "shimmerSweep",
        )
    }

@Composable
private fun CardBone(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun Bone(shimmer: Shimmer, widthFraction: Float, height: Dp, shape: Shape) {
    Spacer(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val band = size.width.coerceAtLeast(1f)
                onDrawBehind {
                    val x = shimmer.progress.value * band
                    val brush = Brush.linearGradient(
                        colors = listOf(shimmer.base, shimmer.highlight, shimmer.base),
                        start = Offset(x - band * 0.6f, 0f),
                        end = Offset(x + band * 0.6f, size.height),
                    )
                    drawOutline(outline, brush)
                }
            },
    )
}
