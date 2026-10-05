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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Shared state of the floating chrome (page indicator, mini player) above the page pager:
 * hides while the user scrolls content down, comes back on scroll up, when the list reaches
 * its top and — docs/DESIGN.md §3 — as soon as scrolling has come to rest ([watchScrollRest]).
 * Pages report their scrolling with [reportChromeScroll]. Provided by the shell via
 * [LocalChromeController]; the default instance makes pages work in isolation (previews).
 */
@Stable
class ChromeController {
    var indicatorVisible by mutableStateOf(true)
        private set

    /**
     * True from the first scrolled pixel until the content has rested for a moment. Read it
     * outside of composition only (e.g. in a `snapshotFlow`): it flips twice per gesture.
     */
    var scrolling by mutableStateOf(false)
        private set

    private var travel = 0f

    /** One pulse per scrolled frame; [watchScrollRest] waits for them to stop. */
    private val scrollPulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            // Overscroll at the top (nothing consumed, pulling down) always reveals the chrome.
            if (dy == 0f && available.y > 0f) show()
            if (dy != 0f) {
                if (!scrolling) scrolling = true
                scrollPulses.tryEmit(Unit)
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

    /**
     * Runs for as long as the shell lives: once no scroll has happened for [REST_MS] — the
     * finger lifted and any fling settled, or the list hit its end — the chrome returns.
     */
    suspend fun watchScrollRest() {
        scrollPulses.collectLatest {
            delay(REST_MS)
            scrolling = false
            show()
        }
    }

    private fun setVisible(visible: Boolean) {
        if (indicatorVisible != visible) indicatorVisible = visible
        travel = 0f
    }

    private companion object {
        const val HIDE_AFTER_PX = 48f
        const val SHOW_AFTER_PX = 24f

        /** Pause after the last scrolled frame before the chrome comes back. */
        const val REST_MS = 650L
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
