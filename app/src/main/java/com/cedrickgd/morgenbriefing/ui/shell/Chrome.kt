package com.cedrickgd.morgenbriefing.ui.shell

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

/** Apply to a page's scrolling container so the floating chrome reacts to it. */
fun Modifier.reportChromeScroll(): Modifier = composed {
    nestedScroll(LocalChromeController.current.nestedScrollConnection)
}
