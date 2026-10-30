package com.cedrickgd.morgenbriefing.ui.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.navigation.NavHostController
import com.cedrickgd.morgenbriefing.ui.nav.MainRoute
import com.cedrickgd.morgenbriefing.ui.nav.isMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A request to show one of the main pages, e.g. from a notification tap (`AppLinks`).
 * [id] keeps two identical requests in a row distinct.
 */
@Immutable
data class PageRequest(val page: MainPage, val id: Long)

/** Spring for programmatic page changes (indicator tap, back, links) – docs/DESIGN.md §6. */
internal val PageSpring: AnimationSpec<Float> = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/**
 * The shell's navigation actions: switching pager pages. Event handlers only – nothing here is
 * read during composition.
 */
@Stable
class ShellNavigator internal constructor(
    private val navController: NavHostController,
    val pagerState: PagerState,
    private val chrome: ChromeController,
    private val scope: CoroutineScope,
    private val onTodayRequested: () -> Unit,
) {
    /** No screen is pushed over the pager right now. */
    val isMainOnTop: Boolean
        get() = navController.currentBackStackEntry?.isMain() ?: true

    /**
     * Shows [page]: animates the pager when it is visible, otherwise closes any pushed screen
     * and lets the pager reappear on that page. [scrollTodayToTop] also scrolls "Heute" back up
     * (notification tap, tapping the already selected sun icon).
     */
    fun showPage(page: MainPage, scrollTodayToTop: Boolean = false) {
        chrome.show()
        if (page == MainPage.TODAY && scrollTodayToTop) onTodayRequested()
        if (isMainOnTop) {
            if (pagerState.currentPage != page.index || pagerState.currentPageOffsetFraction != 0f) {
                scope.launch { pagerState.animateScrollToPage(page.index, animationSpec = PageSpring) }
            }
        } else {
            // The pager is not composed while covered; this only records the position.
            pagerState.requestScrollToPage(page.index)
            navController.popBackStack<MainRoute>(inclusive = false)
        }
    }

    /** Handles a [PageRequest] (always lands on the page; "Heute" also scrolls to the top). */
    fun handle(request: PageRequest) = showPage(request.page, scrollTodayToTop = request.page == MainPage.TODAY)
}
