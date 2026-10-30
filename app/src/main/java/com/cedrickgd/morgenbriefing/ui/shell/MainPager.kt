package com.cedrickgd.morgenbriefing.ui.shell

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import com.cedrickgd.morgenbriefing.ui.settings.SettingsScreen
import com.cedrickgd.morgenbriefing.ui.today.TodayScreen
import com.cedrickgd.morgenbriefing.ui.today.TodayViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Scale of a page one full page away from the centre (docs/DESIGN.md §6). */
private const val NEIGHBOR_SCALE = 0.94f

/**
 * How far a page one full page away fades into the sky color while you swipe. The content is
 * tinted (not made transparent), so the moon or the aurora never shine through its text.
 */
private const val NEIGHBOR_DIM = 0.55f

/** How far (in pages) the back gesture lets the page lean towards "Heute" before letting go. */
private const val BACK_PEEK = 0.14f

/**
 * The main pages side by side (Heute → Einstellungen) over the shared sky. Pages are
 * transparent; while swiping, the neighbours shrink to 0.94 and fade a little, the settled page
 * gives a light haptic tick, and the back gesture on Einstellungen glides to "Heute" (following
 * the predictive back gesture on Android 14+).
 *
 * @param entry the MainRoute back stack entry.
 * @param onTop the MainRoute entry is the top of the back stack.
 * @param todayViewModel the activity-scoped instance the shell also uses for the sky.
 */
@Composable
internal fun MainPager(
    navigator: ShellNavigator,
    entry: NavBackStackEntry,
    onTop: Boolean,
    contentPadding: PaddingValues,
    openTodaySignal: Int,
    todayViewModel: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    val pagerState = navigator.pagerState
    val chrome = LocalChromeController.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.background

    LaunchedEffect(pagerState, view) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { view.pageTick() }
    }
    LaunchedEffect(pagerState, chrome) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { chrome.show() }
    }

    val lifecycle by entry.lifecycle.currentStateAsState()
    BackToToday(pagerState, scope, enabled = onTop && lifecycle.isAtLeast(Lifecycle.State.STARTED))

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { it },
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = 1f,
            ),
        ),
    ) { page ->
        Box(
            Modifier
                .fillMaxSize()
                .pageDepth(pagerState, page, dim)
                .chromeScrollSource(chrome),
        ) {
            ChromeScrollReported {
                when (MainPage.ofIndex(page)) {
                    MainPage.TODAY -> TodayScreen(
                        contentPadding = contentPadding,
                        openTodaySignal = openTodaySignal,
                        onOpenSettings = { navigator.showPage(MainPage.SETTINGS) },
                        viewModel = todayViewModel,
                    )

                    MainPage.SETTINGS -> SettingsScreen(contentPadding = contentPadding)
                }
            }
        }
    }
}

/**
 * Depth while swiping: pages away from the centre shrink and sink into the sky — their drawn
 * content is tinted towards the sky's base color ([dim]) on an offscreen layer (only while the
 * page is off centre), so nothing behind shines through the text. Reads the pager offset in
 * the draw phase only, so swiping never recomposes the pages.
 */
private fun Modifier.pageDepth(state: PagerState, page: Int, dim: Color): Modifier = this
    .graphicsLayer {
        val distance = abs(state.getOffsetDistanceInPages(page)).coerceIn(0f, 1f)
        val scale = 1f - (1f - NEIGHBOR_SCALE) * distance
        scaleX = scale
        scaleY = scale
        compositingStrategy = if (distance > 0.001f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val distance = abs(state.getOffsetDistanceInPages(page)).coerceIn(0f, 1f)
        if (distance > 0.001f) drawRect(dim.copy(alpha = NEIGHBOR_DIM * distance), blendMode = BlendMode.SrcAtop)
    }

/**
 * Back on Einstellungen returns to "Heute". With predictive back the page leans towards "Heute"
 * as the gesture progresses; a cancelled gesture springs back.
 */
@Composable
private fun BackToToday(pagerState: PagerState, scope: CoroutineScope, enabled: Boolean) {
    val home = MainPage.TODAY.index
    PredictiveBackHandler(enabled = enabled && pagerState.settledPage != home) { progress ->
        val from = pagerState.settledPage
        val towardHome = if (from < home) 1f else -1f
        try {
            progress.collect { event ->
                val eased = 1f - (1f - event.progress) * (1f - event.progress)
                pagerState.requestScrollToPage(from, towardHome * BACK_PEEK * eased)
            }
            scope.launch { pagerState.animateScrollToPage(home, animationSpec = PageSpring) }
        } catch (e: CancellationException) {
            scope.launch { pagerState.animateScrollToPage(from, animationSpec = PageSpring) }
            throw e
        }
    }
}

/** Light tick when a new page settles (respects the system's touch-feedback setting). */
private fun View.pageTick() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(feedback)
}
