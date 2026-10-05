package com.cedrickgd.morgenbriefing.ui.shell

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.listen.ListenMiniPlayer
import com.cedrickgd.morgenbriefing.ui.glass.GlassPageIndicator
import com.cedrickgd.morgenbriefing.ui.glass.GlassPageIndicatorHeight

/** Distance of the floating chrome from the navigation bar (and of content from the chrome). */
private val ChromeEdge = 12.dp

/** Gap between the mini player and the page indicator. */
private val ChromeGap = 10.dp

/**
 * Live bottom room for the floating chrome. Pages get [pagePadding], pushed screens
 * [pushedPadding]; both are single, stable [PaddingValues] objects whose bottom value is read
 * from snapshot state when it is used (usually in a lazy list's measure pass), so the mini
 * player appearing or the navigation mode changing re-lays out content without recomposing
 * the pages.
 */
@Stable
internal class ShellInsets(private val navigationBars: WindowInsets, private val layoutDensity: Density) {

    /** Measured height of the mini player; 0 while nothing is being read aloud. */
    var playerHeightPx by mutableIntStateOf(0)

    private val navigationBottom: Dp
        get() = with(layoutDensity) { navigationBars.getBottom(this).toDp() }

    private val playerHeight: Dp
        get() = with(layoutDensity) { playerHeightPx.toDp() }

    /** navigation bar + 12 + indicator + 12 (+ mini player + gap while something is read aloud). */
    val pagePadding: PaddingValues = BottomPadding {
        val player = if (playerHeightPx > 0) playerHeight + ChromeGap else 0.dp
        navigationBottom + ChromeEdge + GlassPageIndicatorHeight + ChromeEdge + player
    }

    /** navigation bar (+ 12 + mini player + 12 while something is read aloud). */
    val pushedPadding: PaddingValues = BottomPadding {
        val player = if (playerHeightPx > 0) ChromeEdge + playerHeight + ChromeEdge else 0.dp
        navigationBottom + player
    }
}

@Composable
internal fun rememberShellInsets(): ShellInsets {
    val navigationBars = WindowInsets.navigationBars
    val density = LocalDensity.current
    return remember(navigationBars, density) { ShellInsets(navigationBars, density) }
}

/** [PaddingValues] with only a bottom value, evaluated lazily on every read. */
@Stable
private class BottomPadding(private val bottom: () -> Dp) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateTopPadding(): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateBottomPadding(): Dp = bottom()
    override fun toString(): String = "BottomPadding(${bottom()})"
}

/**
 * The floating glass chrome at the bottom of the window (docs/DESIGN.md §3): the page
 * indicator on the main pager and, while something is read aloud, the mini player – above the
 * indicator, sliding down into its place whenever the indicator is hidden (scrolling, pushed
 * screens). The slide is read in the draw phase only.
 *
 * @param indicatorVisible pager on top and the chrome not hidden by scrolling.
 * @param playerAllowed false on screens with their own bottom controls (chat input).
 */
@Composable
internal fun BoxScope.FloatingChrome(
    pagerState: PagerState,
    insets: ShellInsets,
    indicatorVisible: Boolean,
    playerAllowed: Boolean,
    onSelectPage: (Int) -> Unit,
) {
    val lift = animateFloatAsState(
        targetValue = if (indicatorVisible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "playerLift",
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            // Navigation bar only: with the keyboard open the chrome stays down (the caller
            // hides the indicator then) instead of riding on top of the keyboard.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(bottom = ChromeEdge),
    ) {
        if (playerAllowed) {
            DisposableEffect(insets) { onDispose { insets.playerHeightPx = 0 } }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = -lift.value * (GlassPageIndicatorHeight + ChromeGap).toPx()
                    }
                    .onSizeChanged { insets.playerHeightPx = it.height },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // The player brings its own 16 dp side margins and 560 dp width cap.
                ListenMiniPlayer()
            }
        }
        GlassPageIndicator(
            pagerState = pagerState,
            pages = MainPage.specs,
            visible = indicatorVisible,
            onSelect = onSelectPage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
