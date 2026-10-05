package com.cedrickgd.morgenbriefing.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Shared state of the floating chrome (page indicator, mini player) above the page pager:
 * hides while the user scrolls content down, comes back on scroll up or when the list reaches
 * its top. Pages report their scrolling with [reportChromeScroll]. Provided by the shell via
 * [LocalChromeController]; the default instance makes pages work in isolation (previews).
 */
@Stable
class ChromeController {
    var indicatorVisible by mutableStateOf(true)
        private set

    private var travel = 0f

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            // Overscroll at the top (nothing consumed, pulling down) always reveals the chrome.
            if (dy == 0f && available.y > 0f) show()
            if (dy != 0f) {
                travel = if ((travel < 0f) == (dy < 0f)) travel + dy else dy
                when {
                    travel < -HIDE_AFTER_PX -> setVisible(false)
                    travel > SHOW_AFTER_PX -> setVisible(true)
                }
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (available.y > 0f) show() // flung into the top edge
            return Velocity.Zero
        }
    }

    fun show() = setVisible(true)

    private fun setVisible(visible: Boolean) {
        if (indicatorVisible != visible) indicatorVisible = visible
        travel = 0f
    }

    private companion object {
        const val HIDE_AFTER_PX = 48f
        const val SHOW_AFTER_PX = 24f
    }
}

val LocalChromeController = staticCompositionLocalOf { ChromeController() }

/** True inside content whose scrolling the shell already reports (see [chromeScrollSource]). */
private val LocalChromeScrollReported = staticCompositionLocalOf { false }

/**
 * Apply to a page's scrolling container so the floating chrome reacts to it. Inside the main
 * pager the shell already reports every page's scrolling; there this is a no-op, so nothing is
 * counted twice.
 */
fun Modifier.reportChromeScroll(): Modifier = composed {
    if (LocalChromeScrollReported.current) {
        Modifier
    } else {
        nestedScroll(LocalChromeController.current.nestedScrollConnection)
    }
}

/**
 * Shell only: reports all vertical scrolling inside this element to the chrome (so a page
 * hides the indicator while scrolling down even without its own [reportChromeScroll]). Wrap
 * the element's content in [ChromeScrollReported].
 */
internal fun Modifier.chromeScrollSource(chrome: ChromeController): Modifier =
    nestedScroll(chrome.nestedScrollConnection)

/** Marks [content] as already reported by an enclosing [chromeScrollSource]. */
@Composable
internal fun ChromeScrollReported(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChromeScrollReported provides true, content = content)
}
