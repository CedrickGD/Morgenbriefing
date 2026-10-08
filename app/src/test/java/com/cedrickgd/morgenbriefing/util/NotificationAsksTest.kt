package com.cedrickgd.morgenbriefing.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAsksTest {

    private fun blocked(asks: NotificationAsks, rationaleNow: Boolean) =
        notificationsDeniedForGood(granted = false, showRationale = rationaleNow, asks = asks)

    /** A dialog dismissed with Back: denied, no rationale, the dialog was on screen. */
    private fun NotificationAsks.back() = after(granted = false, showRationale = false, silent = false)

    @Test
    fun `never asked is not blocked`() {
        assertFalse(blocked(NotificationAsks(), rationaleNow = false))
    }

    @Test
    fun `dialogs dismissed with back never count, however often`() {
        var asks = NotificationAsks()
        repeat(5) {
            asks = asks.back()
            assertFalse(blocked(asks, rationaleNow = false))
        }
        assertEquals(NotificationAsks(), asks)
    }

    @Test
    fun `one deliberate denial still asks, the second one is final`() {
        val once = NotificationAsks().after(granted = false, showRationale = true, silent = false)
        assertFalse(blocked(once, rationaleNow = true))
        // Dismissed with Back on the second dialog: the rationale stays, so it still asks.
        val dismissed = once.after(granted = false, showRationale = true, silent = false)
        assertFalse(blocked(dismissed, rationaleNow = true))
        // "Nicht zulassen" again: Android drops the rationale and will not show the dialog again.
        val twice = dismissed.after(granted = false, showRationale = false, silent = false)
        assertTrue(blocked(twice, rationaleNow = false))
    }

    @Test
    fun `back twice, then a deliberate denial, then the final one`() {
        val back = NotificationAsks().back().back()
        val denied = back.after(granted = false, showRationale = true, silent = false)
        assertFalse(blocked(denied, rationaleNow = true))
        val last = denied.after(granted = false, showRationale = false, silent = false)
        assertTrue(blocked(last, rationaleNow = false))
    }

    @Test
    fun `a request the system refuses without a dialog is final`() {
        // E.g. switched off in the system settings before the app ever asked.
        val silent = NotificationAsks().back().after(granted = false, showRationale = false, silent = true)
        assertEquals(NotificationAsks(silentDenials = 1), silent)
        assertTrue(blocked(silent, rationaleNow = false))
    }

    @Test
    fun `a grant starts over and granted is never blocked`() {
        val history = NotificationAsks(silentDenials = 3, sawRationale = true)
        assertEquals(NotificationAsks(), history.after(granted = true, showRationale = false, silent = false))
        assertFalse(notificationsDeniedForGood(granted = true, showRationale = false, asks = history))
    }

    @Test
    fun `a due rationale always means the dialog can still ask`() {
        assertFalse(blocked(NotificationAsks(silentDenials = 5, sawRationale = true), rationaleNow = true))
    }

    // ---- Was the dialog shown? ----------------------------------------------------------------

    private fun sighting(pausedMs: Long = 0, unfocusedMs: Long = 0) = DialogSighting(
        elapsedMs = maxOf(pausedMs, unfocusedMs) + 40,
        paused = pausedMs > 0,
        pausedMs = pausedMs,
        lostFocus = unfocusedMs > 0,
        unfocusedMs = unfocusedMs,
    )

    @Test
    fun `a moment away means the system answered without its dialog`() {
        // Measured on Android 16: the permission activity starts and finishes at once.
        assertTrue(refusedWithoutDialog(sighting(pausedMs = 165)))
        assertTrue(refusedWithoutDialog(sighting(pausedMs = 494, unfocusedMs = 127)))
        assertTrue(refusedWithoutDialog(sighting(unfocusedMs = 300)))
    }

    @Test
    fun `a dialog on screen until back keeps the app away longer`() {
        assertFalse(refusedWithoutDialog(sighting(pausedMs = 2153)))
        assertFalse(refusedWithoutDialog(sighting(pausedMs = MIN_DIALOG_MS)))
        // Paused only briefly, but the window had no focus for as long as the dialog was up.
        assertFalse(refusedWithoutDialog(sighting(pausedMs = 200, unfocusedMs = 1500)))
    }

    @Test
    fun `nothing seen or nothing watched is not counted`() {
        // E.g. a second tap while a request was pending: Android answers it at once, unasked.
        assertFalse(refusedWithoutDialog(sighting()))
        // The activity was recreated while the dialog was open.
        assertFalse(refusedWithoutDialog(null))
    }

    // ---- PermissionDialogWatch ------------------------------------------------------------------

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `the watch measures pause and focus loss up to the result`() {
        val clock = FakeClock()
        val watch = PermissionDialogWatch { clock.now }
        watch.start()
        clock.advance(20)
        watch.onPause()
        clock.advance(60)
        watch.onWindowFocus(false)
        clock.advance(2_000)
        // The result arrives before the activity resumes and gets its focus back.
        val seen = watch.finish()
        assertEquals(DialogSighting(elapsedMs = 2_080, paused = true, pausedMs = 2_060, lostFocus = true, unfocusedMs = 2_000), seen)
        assertFalse(refusedWithoutDialog(seen))
        // Events after the result belong to no request.
        watch.onResume()
        watch.onWindowFocus(true)
        assertNull(watch.finish())
    }

    @Test
    fun `a refusal without dialog pauses only for a moment`() {
        val clock = FakeClock()
        val watch = PermissionDialogWatch { clock.now }
        watch.start()
        clock.advance(10)
        watch.onPause()
        clock.advance(120)
        watch.onWindowFocus(false)
        clock.advance(130)
        val seen = watch.finish()!!
        assertEquals(250, seen.pausedMs)
        assertEquals(130, seen.unfocusedMs)
        assertTrue(refusedWithoutDialog(seen))
    }

    @Test
    fun `pauses before the request and repeated events are ignored`() {
        val clock = FakeClock()
        val watch = PermissionDialogWatch { clock.now }
        // Not watching yet: nothing is recorded.
        watch.onPause()
        watch.onWindowFocus(false)
        clock.advance(5_000)
        watch.start()
        clock.advance(100)
        watch.onPause()
        clock.advance(100)
        watch.onPause() // a second ON_PAUSE does not restart the clock
        clock.advance(100)
        watch.onResume()
        clock.advance(100)
        val seen = watch.finish()!!
        assertEquals(400, seen.elapsedMs)
        assertEquals(200, seen.pausedMs)
        assertFalse(seen.lostFocus)
    }

    @Test
    fun `a new request starts a fresh observation`() {
        val clock = FakeClock()
        val watch = PermissionDialogWatch { clock.now }
        watch.start()
        watch.onPause()
        clock.advance(3_000)
        // Second tap: Android answers it at once, before anything else happens.
        watch.start()
        val second = watch.finish()!!
        assertFalse(second.paused)
        assertFalse(refusedWithoutDialog(second))
        // The first request's result then finds nothing being watched.
        assertNull(watch.finish())
    }
}
