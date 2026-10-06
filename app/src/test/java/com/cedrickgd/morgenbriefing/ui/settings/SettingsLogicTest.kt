package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefSourceException
import com.cedrickgd.morgenbriefing.data.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SettingsLogicTest {

    @Test
    fun `slider has one stop per hour`() {
        // 6..22 inclusive = 17 positions = 15 steps between the two ends.
        assertEquals(15, DayWindow.SLIDER_STEPS)
    }

    @Test
    fun `window keeps values that are already valid`() {
        assertEquals(10 to 18, DayWindow.constrain(10, 18, 10, 18))
        assertEquals(9 to 18, DayWindow.constrain(10, 18, 9, 18))
        assertEquals(6 to 22, DayWindow.constrain(10, 18, 6, 22))
    }

    @Test
    fun `moving start too close to end holds the start back`() {
        assertEquals(16 to 18, DayWindow.constrain(10, 18, 17, 18))
        assertEquals(16 to 18, DayWindow.constrain(10, 18, 18, 18))
    }

    @Test
    fun `moving end too close to start holds the end back`() {
        assertEquals(10 to 12, DayWindow.constrain(10, 18, 10, 11))
        assertEquals(10 to 12, DayWindow.constrain(10, 18, 10, 10))
    }

    @Test
    fun `window respects the bounds`() {
        assertEquals(6 to 22, DayWindow.constrain(10, 18, 3, 30))
        assertEquals(20 to 22, DayWindow.normalize(21, 22))
        assertEquals(6 to 8, DayWindow.normalize(6, 6))
        assertEquals(15 to 17, DayWindow.normalize(15, 10))
        val (start, end) = DayWindow.normalize(-5, 99)
        assertTrue(end - start >= DayWindow.MIN_SPAN)
    }

    @Test
    fun `labels`() {
        assertEquals("10–18 Uhr", DayWindow.label(10, 18))
        assertEquals("05:30", clockLabel(5, 30))
        assertEquals("18:05", clockLabel(18, 5))
        assertEquals("Bad Düben · Sachsen, Deutschland", Place.BAD_DUEBEN.displayLabel())
        assertEquals("Irgendwo", Place("Irgendwo", 0.0, 0.0).displayLabel())
    }

    @Test
    fun `next briefing label says today, tomorrow or the day after`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 6, 4, 0, 0, 0, zone)
        assertEquals("Nächstes Briefing: heute um 05:30", nextBriefingLabel(now, now.withHour(5).withMinute(30)))
        assertEquals(
            "Nächstes Briefing: morgen um 05:30",
            nextBriefingLabel(now.withHour(6), now.plusDays(1).withHour(5).withMinute(30)),
        )
        assertEquals("Nächstes Briefing: übermorgen um 06:00", nextBriefingLabel(now, now.plusDays(2).withHour(6)))
    }

    @Test
    fun `countdown to the next briefing`() {
        assertEquals("gleich", countdownLabel(0))
        assertEquals("gleich", countdownLabel(-5))
        assertEquals("in 45 Min.", countdownLabel(45))
        assertEquals("in 1 Std.", countdownLabel(60))
        assertEquals("in 2 Std. 15 Min.", countdownLabel(135))
        assertEquals("in 9 Std.", countdownLabel(9 * 60 + 10))
        assertEquals("in 10 Std.", countdownLabel(9 * 60 + 40))
    }

    @Test
    fun `next briefing countdown names the day`() {
        val zone = ZoneId.of("Europe/Berlin")
        val evening = ZonedDateTime.of(2026, 10, 5, 20, 30, 0, 0, zone)
        val next = ZonedDateTime.of(2026, 10, 6, 5, 30, 0, 0, zone)
        assertEquals("Nächstes Briefing: morgen · in 9 Std.", nextBriefingCountdown(evening, next))
        val early = ZonedDateTime.of(2026, 10, 6, 4, 50, 0, 0, zone)
        assertEquals("Nächstes Briefing: heute · in 40 Min.", nextBriefingCountdown(early, next))
        assertEquals("heute", dayWord(early, next))
        assertEquals("am Fr, 9. Okt.", dayWord(evening, ZonedDateTime.of(2026, 10, 9, 5, 30, 0, 0, zone)))
    }

    @Test
    fun `a gesture on the window slider is classified by its first clear axis`() {
        val slop = 20f
        assertEquals(GestureAxis.UNDECIDED, gestureAxis(dx = 5f, dy = -12f, slop = slop))
        // Scrolling the page with a finger that landed on the track.
        assertEquals(GestureAxis.VERTICAL, gestureAxis(dx = 6f, dy = -30f, slop = slop))
        assertEquals(GestureAxis.VERTICAL, gestureAxis(dx = -3f, dy = 25f, slop = slop))
        // Dragging a thumb, even a little wobbly.
        assertEquals(GestureAxis.HORIZONTAL, gestureAxis(dx = 30f, dy = 10f, slop = slop))
        assertEquals(GestureAxis.HORIZONTAL, gestureAxis(dx = -40f, dy = 35f, slop = slop))
    }

    @Test
    fun `only a released horizontal drag or tap of the window slider is saved`() {
        // Drag or tap that ends normally while the app is in front.
        assertTrue(saveSliderTouch(GestureAxis.HORIZONTAL, TouchEnd.RELEASED, appInFront = true))
        assertTrue(saveSliderTouch(GestureAxis.UNDECIDED, TouchEnd.RELEASED, appInFront = true))
        // Page scroll that started on the track.
        assertFalse(saveSliderTouch(GestureAxis.VERTICAL, TouchEnd.RELEASED, appInFront = true))
        // Home swipe from the gesture strip: the system cancels the touch.
        assertFalse(saveSliderTouch(GestureAxis.UNDECIDED, TouchEnd.CANCELLED, appInFront = true))
        assertFalse(saveSliderTouch(GestureAxis.HORIZONTAL, TouchEnd.CANCELLED, appInFront = true))
        // Released after the window lost focus or the screen was left.
        assertFalse(saveSliderTouch(GestureAxis.HORIZONTAL, TouchEnd.RELEASED, appInFront = false))
    }

    @Test
    fun `the slider guard waits for the end of a touch before saving`() {
        val slop = 20f
        val guard = SliderTouchGuard()

        // Horizontal drag: the slider's finish arrives before the release and only waits.
        guard.onDown()
        guard.onMove(15f, 2f, slop)
        guard.onMove(30f, -4f, slop)
        assertFalse(guard.onSliderFinished())
        assertEquals(SliderVerdict.SAVE, guard.onTouchEnd(TouchEnd.RELEASED, appInFront = true))

        // Tap on the track.
        guard.onDown()
        assertFalse(guard.onSliderFinished())
        assertEquals(SliderVerdict.SAVE, guard.onTouchEnd(TouchEnd.RELEASED, appInFront = true))

        // Home swipe over the slider in the gesture strip: no movement seen, then a cancel.
        guard.onDown()
        assertFalse(guard.onSliderFinished())
        assertEquals(SliderVerdict.REVERT, guard.onTouchEnd(TouchEnd.CANCELLED, appInFront = true))

        // Page scroll: once vertical, a later sideways wobble does not make it a drag.
        guard.onDown()
        guard.onMove(3f, -40f, slop)
        guard.onMove(80f, 0f, slop)
        assertFalse(guard.onSliderFinished())
        assertEquals(SliderVerdict.REVERT, guard.onTouchEnd(TouchEnd.RELEASED, appInFront = true))

        // Released while the app was no longer in front.
        guard.onDown()
        guard.onMove(40f, 0f, slop)
        assertEquals(SliderVerdict.REVERT, guard.onTouchEnd(TouchEnd.RELEASED, appInFront = false))

        // Keyboard or TalkBack: no touch, the finish saves at once and no touch end follows.
        assertTrue(guard.onSliderFinished())
        assertNull(guard.onTouchEnd(TouchEnd.RELEASED, appInFront = true))

        // The slider left the screen mid-touch: the next finish is not held back.
        guard.onDown()
        guard.reset()
        assertTrue(guard.onSliderFinished())
    }

    @Test
    fun `the countdown follows the alarm, which rings in the device zone`() {
        // 06:48 in Berlin on a phone set to GMT (04:48 there): the 05:30 alarm rings in 42 minutes.
        val now = ZonedDateTime.of(2026, 10, 6, 6, 48, 0, 0, ZoneId.of("Europe/Berlin"))
        val next = nextBriefingAt(now, 5, 30, ZoneId.of("GMT"))
        assertEquals(Instant.parse("2026-10-06T05:30:00Z"), next.toInstant())
        assertEquals("Nächstes Briefing: heute · in 42 Min.", nextBriefingCountdown(now, next))
        assertEquals("heute", dayWord(now, next))
        // In Berlin itself nothing changes.
        val berlin = nextBriefingAt(now, 5, 30, ZoneId.of("Europe/Berlin"))
        assertEquals("Nächstes Briefing: morgen · in 23 Std.", nextBriefingCountdown(now, berlin))
    }

    @Test
    fun `token check messages`() {
        assertNull(tokenCheckMessage(TokenCheck.Idle))
        assertEquals("✓ Verbindung klappt – ab morgen kommen deine News.", tokenCheckMessage(TokenCheck.Success))
        assertEquals("✗ Token ungültig oder abgelaufen", tokenCheckMessage(TokenCheck.Failed(BriefErrorKind.UNAUTHORIZED)))
        assertEquals(
            "✗ Repo nicht gefunden – hat der Token Zugriff auf Morgenbriefing?",
            tokenCheckMessage(TokenCheck.Failed(BriefErrorKind.NOT_FOUND)),
        )
        assertEquals("✗ Keine Verbindung", tokenCheckMessage(TokenCheck.Failed(BriefErrorKind.NETWORK)))
        BriefErrorKind.entries.forEach { kind ->
            assertTrue(kind.name, tokenCheckMessage(TokenCheck.Failed(kind))!!.startsWith("✗"))
        }
    }

    @Test
    fun `thrown errors map to error kinds`() {
        assertEquals(BriefErrorKind.NOT_FOUND, BriefSourceException(BriefErrorKind.NOT_FOUND).toBriefErrorKind())
        assertEquals(BriefErrorKind.NETWORK, IOException("offline").toBriefErrorKind())
        assertEquals(BriefErrorKind.NETWORK, SocketTimeoutException().toBriefErrorKind())
        assertEquals(BriefErrorKind.UNKNOWN, IllegalStateException().toBriefErrorKind())
    }

    @Test
    fun `material you is only offered from android 12`() {
        assertFalse(AccentId.DYNAMIC in availableAccents(sdkInt = 30))
        assertTrue(AccentId.DYNAMIC in availableAccents(sdkInt = 31))
        assertEquals(AccentId.entries.size - 1, availableAccents(sdkInt = 24).size)
        assertEquals(AccentId.MORGENROT, effectiveAccent(AccentId.DYNAMIC, sdkInt = 28))
        assertEquals(AccentId.DYNAMIC, effectiveAccent(AccentId.DYNAMIC, sdkInt = 34))
        assertEquals(AccentId.OZEAN, effectiveAccent(AccentId.OZEAN, sdkInt = 24))
    }

    @Test
    fun `place search state knows short queries`() {
        assertTrue(PlaceSearchState(query = " L ").queryTooShort)
        assertFalse(PlaceSearchState(query = "Le").queryTooShort)
    }
}
