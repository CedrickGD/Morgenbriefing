package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay

/** Material 3 "emphasized decelerate": quick start, long soft landing — for things arriving. */
internal val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Material 3 "emphasized" standard curve for things that move on screen. */
internal val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** True when decorative motion should be skipped (system setting or IDE preview). */
@Composable
internal fun motionDisabled(): Boolean = rememberReduceMotion() || LocalInspectionMode.current

/**
 * Progress 0 → 1 of a one-time entrance animation, started after [delayMillis].
 *
 * The "already played" flag is saved, so items do not re-animate when they scroll back into a
 * lazy list or after a configuration change. Returns a lambda so callers read the value only in
 * the draw/layer phase (no recomposition per frame).
 */
@Composable
internal fun rememberEntrance(delayMillis: Int = 0, durationMillis: Int = 520): () -> Float {
    val skip = motionDisabled()
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played || skip) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            if (delayMillis > 0) delay(delayMillis.toLong())
            progress.animateTo(1f, tween(durationMillis, easing = EmphasizedDecelerate))
        }
        played = true
    }
    return remember(progress) { { progress.value } }
}

/** Fades in and slides up by [distance] while [progress] goes 0 → 1 (layer only, no relayout). */
internal fun Modifier.entrance(progress: () -> Float, distance: Dp = 16.dp): Modifier =
    graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * distance.toPx()
    }
