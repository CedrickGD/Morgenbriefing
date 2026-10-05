package com.cedrickgd.morgenbriefing.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * Keeps content clear of side insets: a 3-button bar on the left/right in landscape and
 * display cutouts. Vertical insets are handled per screen.
 */
fun Modifier.horizontalSafeDrawing(): Modifier = composed {
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
}

/** Height of the status bar (top inset), for screens that draw underneath it. */
@Composable
fun statusBarTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * Background behind the status bar for screens that scroll content underneath it (Today):
 * fades in once [visible], so the clock and icons never sit on top of text. The lower edge
 * softens into the content instead of a hard line.
 */
@Composable
fun BoxScope.StatusBarScrim(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "statusBarScrim",
    )
    if (alpha == 0f) return
    val background = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusBarTop())
                .background(background.copy(alpha = 0.96f)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.96f), Color.Transparent))),
        )
    }
}

/**
 * Bottom padding for scrolling content: the Scaffold padding when the navigation bar is shown,
 * otherwise (at least) the system navigation bar — gesture handle or 3-button bar.
 */
@Composable
fun bottomContentInset(scaffoldPadding: PaddingValues): Dp = max(
    scaffoldPadding.calculateBottomPadding(),
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
)
