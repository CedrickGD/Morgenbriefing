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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
private val ChromeGap = 12.dp

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
 * With 3-button navigation a soft scrim rises behind the system buttons on the pager pages, so
 * they stay legible over bright pictures ([navScrim]).
 *
 * @param indicatorVisible pager on top and the chrome not hidden by scrolling.
 * @param playerAllowed false on screens with their own bottom controls (chat input).
 * @param onOpenArticle the mini player was tapped: open the article being read.
 */
@Composable
internal fun BoxScope.FloatingChrome(
    pagerState: PagerState,
    insets: ShellInsets,
    indicatorVisible: Boolean,
    playerAllowed: Boolean,
    navScrim: Boolean,
    onSelectPage: (Int) -> Unit,
    onOpenArticle: (date: String, key: String) -> Unit,
) {
    if (navScrim) ButtonBarScrim()
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
                ListenMiniPlayer(onOpenArticle = onOpenArticle)
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

/**
 * A soft gradient behind the 3-button navigation bar (docs/DESIGN.md §8): the system buttons
 * stay legible over bright pictures without a hard bar. Nothing is drawn with gesture
 * navigation, whose handle the system tints by itself.
 */
@Composable
private fun BoxScope.ButtonBarScrim() {
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    if (navigationBottom <= GestureBarMax) return
    val color = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(navigationBottom + 32.dp)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to color.copy(alpha = 0f),
                        0.45f to color.copy(alpha = 0.45f),
                        1f to color.copy(alpha = 0.72f),
                    ),
                )
            },
    )
}

/** Gesture handles are at most this tall; anything taller is a 3-button bar. */
private val GestureBarMax = 24.dp
