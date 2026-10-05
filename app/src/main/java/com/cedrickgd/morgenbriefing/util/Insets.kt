package com.cedrickgd.morgenbriefing.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
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
 * Bottom padding for scrolling content: the given padding (room for the floating glass chrome)
 * or at least the system navigation bar — gesture handle or 3-button bar.
 */
@Composable
fun bottomContentInset(scaffoldPadding: PaddingValues): Dp = max(
    scaffoldPadding.calculateBottomPadding(),
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
)
