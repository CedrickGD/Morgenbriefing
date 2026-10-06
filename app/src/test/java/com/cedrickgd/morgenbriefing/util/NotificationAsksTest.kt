package com.cedrickgd.morgenbriefing.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAsksTest {

    private fun blocked(asks: NotificationAsks, rationaleNow: Boolean) =
        notificationsDeniedForGood(granted = false, showRationale = rationaleNow, asks = asks)

    @Test
    fun `never asked is not blocked`() {
        assertFalse(blocked(NotificationAsks(), rationaleNow = false))
    }

    @Test
    fun `dialog dismissed with back on the first request still asks again`() {
        // Back: denied, and Android wants no rationale (nothing was decided).
        val asks = NotificationAsks().after(granted = false, showRationale = false)
        assertEquals(NotificationAsks(denials = 1, sawRationale = false), asks)
        assertFalse(blocked(asks, rationaleNow = false))
    }

    @Test
    fun `one deliberate denial still asks, the second one is final`() {
        val once = NotificationAsks().after(granted = false, showRationale = true)
        assertFalse(blocked(once, rationaleNow = true))
        // Dismissed with Back on the second dialog: the rationale stays, so it still asks.
        val dismissed = once.after(granted = false, showRationale = true)
        assertFalse(blocked(dismissed, rationaleNow = true))
        // "Nicht zulassen" again: Android drops the rationale and will not show the dialog again.
        val twice = dismissed.after(granted = false, showRationale = false)
        assertTrue(blocked(twice, rationaleNow = false))
    }

    @Test
    fun `back first, then a deliberate denial, then the final one`() {
        val back = NotificationAsks().after(granted = false, showRationale = false)
        val denied = back.after(granted = false, showRationale = true)
        assertFalse(blocked(denied, rationaleNow = true))
        val last = denied.after(granted = false, showRationale = false)
        assertTrue(blocked(last, rationaleNow = false))
    }

    @Test
    fun `two denials without any rationale count as refused by the system`() {
        // E.g. switched off in the system settings before the app ever asked: no dialog appears.
        val first = NotificationAsks().after(granted = false, showRationale = false)
        assertFalse(blocked(first, rationaleNow = false))
        val second = first.after(granted = false, showRationale = false)
        assertTrue(blocked(second, rationaleNow = false))
    }

    @Test
    fun `a grant starts over and granted is never blocked`() {
        val history = NotificationAsks(denials = 3, sawRationale = true)
        assertEquals(NotificationAsks(), history.after(granted = true, showRationale = false))
        assertFalse(notificationsDeniedForGood(granted = true, showRationale = false, asks = history))
    }

    @Test
    fun `a due rationale always means the dialog can still ask`() {
        assertFalse(blocked(NotificationAsks(denials = 5, sawRationale = true), rationaleNow = true))
    }
}
