package com.cedrickgd.morgenbriefing.ui.settings

import android.os.Build
import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** The day window ("Zeitfenster") slider: 6–22 Uhr in full hours, at least 2 h wide. */
internal object DayWindow {
    const val MIN_HOUR = 6
    const val MAX_HOUR = 22
    const val MIN_SPAN = 2

    /** Number of steps between the ends for a Material slider with one stop per hour. */
    const val SLIDER_STEPS = MAX_HOUR - MIN_HOUR - 1

    /**
     * Applies bounds and the minimum span while dragging. When the span gets too small, the
     * thumb the user did not move stays put and the moving one is held back.
     */
    fun constrain(previousStart: Int, previousEnd: Int, start: Int, end: Int): Pair<Int, Int> {
        var newStart = start.coerceIn(MIN_HOUR, MAX_HOUR - MIN_SPAN)
        var newEnd = end.coerceIn(MIN_HOUR + MIN_SPAN, MAX_HOUR)
        if (newEnd - newStart < MIN_SPAN) {
            if (newStart != previousStart) newStart = newEnd - MIN_SPAN else newEnd = newStart + MIN_SPAN
        }
        return newStart to newEnd
    }

    /** Makes any stored pair valid (used before persisting). */
    fun normalize(start: Int, end: Int): Pair<Int, Int> = constrain(start, end, start, end)

    /** "10–18 Uhr" */
    fun label(start: Int, end: Int): String = "$start–$end Uhr"
}

/** How a gesture on a horizontal control started. */
internal enum class GestureAxis { UNDECIDED, HORIZONTAL, VERTICAL }

/**
 * The axis a gesture has committed to after travelling [dx]/[dy]: vertical once its vertical
 * travel passes [slop] and outweighs the horizontal one, horizontal once that passes [slop].
 */
internal fun gestureAxis(dx: Float, dy: Float, slop: Float): GestureAxis = when {
    abs(dy) > slop && abs(dy) > abs(dx) -> GestureAxis.VERTICAL
    abs(dx) > slop -> GestureAxis.HORIZONTAL
    else -> GestureAxis.UNDECIDED
}

/** How a touch on the window slider ended. */
internal enum class TouchEnd {
    /** The finger was lifted inside the app. */
    RELEASED,

    /** The system took the touch over, e.g. a Home or Back swipe from the gesture strip. */
    CANCELLED,
}

/** What becomes of the change a touch made on the window slider. */
internal enum class SliderVerdict { SAVE, REVERT }

/**
 * Whether the window slider saves what a touch did: only a deliberate horizontal drag or a tap
 * that ended with a normal release while the app was in front. Dropped are a page scroll that
 * started on the track, a touch the system took over (a Home swipe that starts on the slider in
 * the bottom gesture strip ends in a cancel) and a touch that ended after the window lost focus
 * or the screen was left.
 */
internal fun saveSliderTouch(axis: GestureAxis, end: TouchEnd, appInFront: Boolean): Boolean =
    end == TouchEnd.RELEASED && appInFront && axis != GestureAxis.VERTICAL

/**
 * Follows one touch on the window slider from its first contact to its end.
 *
 * material3's RangeSlider moves the nearest thumb to the first contact and calls
 * onValueChangeFinished for every gesture that ends – also for a page scroll and for a cancel,
 * which it even handles before an element around it learns about the cancel. So a finish during
 * a touch only waits ([onSliderFinished] is false) and the end of the touch decides
 * ([onTouchEnd]); a finish without a touch (keyboard, TalkBack) is saved right away.
 */
internal class SliderTouchGuard {
    private var touching = false
    private var dx = 0f
    private var dy = 0f
    private var axis = GestureAxis.UNDECIDED

    /** First contact, seen before the slider moves a thumb. */
    fun onDown() {
        touching = true
        dx = 0f
        dy = 0f
        axis = GestureAxis.UNDECIDED
    }

    /** Movement of the followed finger; the first clear axis sticks. */
    fun onMove(deltaX: Float, deltaY: Float, slop: Float) {
        if (!touching || axis != GestureAxis.UNDECIDED) return
        dx += deltaX
        dy += deltaY
        axis = gestureAxis(dx, dy, slop)
    }

    /** The slider's onValueChangeFinished: true = save now, false = [onTouchEnd] decides. */
    fun onSliderFinished(): Boolean = !touching

    /** The touch ended; null when no touch was followed. */
    fun onTouchEnd(end: TouchEnd, appInFront: Boolean): SliderVerdict? {
        if (!touching) return null
        touching = false
        return if (saveSliderTouch(axis, end, appInFront)) SliderVerdict.SAVE else SliderVerdict.REVERT
    }

    /** The slider left the screen in the middle of a touch. */
    fun reset() {
        touching = false
    }
}

/** "05:30" */
internal fun clockLabel(hour: Int, minute: Int): String =
    String.format(Locale.GERMANY, "%02d:%02d", hour, minute)

/** "Nächstes Briefing: morgen um 05:30" for the next alarm [next] as seen from [now]. */
internal fun nextBriefingLabel(now: ZonedDateTime, next: ZonedDateTime): String =
    "Nächstes Briefing: ${dayWord(now, next)} um ${clockLabel(next.hour, next.minute)}"

/** "heute", "morgen", "übermorgen" or "am Di, 6. Okt." for [next] as seen from [now] (in [next]'s zone). */
internal fun dayWord(now: ZonedDateTime, next: ZonedDateTime): String =
    when (ChronoUnit.DAYS.between(now.withZoneSameInstant(next.zone).toLocalDate(), next.toLocalDate())) {
        0L -> "heute"
        1L -> "morgen"
        2L -> "übermorgen"
        else -> "am ${Fmt.shortDate(next.toLocalDate())}"
    }

/**
 * Time until the next briefing: "in 45 Min.", "in 2 Std. 15 Min." (below 3 hours, where the
 * minutes still matter) and "in 9 Std." (rounded) beyond that. Negative values count as 0.
 */
internal fun countdownLabel(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m < 1 -> "gleich"
        m < 60 -> "in $m Min."
        m < 180 -> {
            val rest = m % 60
            if (rest == 0L) "in ${m / 60} Std." else "in ${m / 60} Std. $rest Min."
        }
        else -> "in ${(m + 30) / 60} Std."
    }
}

/**
 * When the morning alarm fires next, exactly as [AlarmScheduler.sync] schedules it: at
 * [hour]:[minute] in the device's time zone, which is not Berlin when travelling.
 */
internal fun nextBriefingAt(now: ZonedDateTime, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    AlarmScheduler.nextTrigger(now.withZoneSameInstant(zone), hour, minute)

/** "Nächstes Briefing: morgen · in 9 Std." */
internal fun nextBriefingCountdown(now: ZonedDateTime, next: ZonedDateTime): String {
    val minutes = ChronoUnit.MINUTES.between(now, next)
    return "Nächstes Briefing: ${dayWord(now, next)} · ${countdownLabel(minutes)}"
}

/** Accents offered on this device: Material You only where it exists (API 31+). */
internal fun availableAccents(sdkInt: Int = Build.VERSION.SDK_INT): List<AccentId> =
    AccentId.entries.filter { it != AccentId.DYNAMIC || sdkInt >= Build.VERSION_CODES.S }

/** The accent the theme really uses: DYNAMIC falls back to MORGENROT below API 31. */
internal fun effectiveAccent(accent: AccentId, sdkInt: Int = Build.VERSION.SDK_INT): AccentId =
    if (accent == AccentId.DYNAMIC && sdkInt < Build.VERSION_CODES.S) AccentId.MORGENROT else accent

/** "Bad Düben · Sachsen, Deutschland" */
internal fun Place.displayLabel(): String =
    if (region.isBlank()) name else "$name · $region"

/** Same place within ~1 km (geocoding results and stored snapshots round differently). */
internal fun Place.sameSpotAs(other: Place): Boolean =
    abs(lat - other.lat) < SAME_SPOT_DEGREES && abs(lon - other.lon) < SAME_SPOT_DEGREES

private const val SAME_SPOT_DEGREES = 0.01

/** Which place the picker edits. */
enum class PlaceTarget(val title: String, val overline: String, val question: String) {
    HOME("Ort für „Vor der Tür“", "Vor der Tür", "Wo bist du morgens?"),
    DAY("Ort für „Tagsüber“", "Tagsüber", "Wo verbringst du den Tag?"),
}

/** Place search inside the picker dialog. */
@Immutable
data class PlaceSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Place> = emptyList(),
    val failed: Boolean = false,
    /** Query the [results] belong to; distinguishes "nothing found" from "not searched yet". */
    val searchedFor: String? = null,
) {
    val queryTooShort: Boolean get() = query.trim().length < PLACE_QUERY_MIN_LENGTH
}

internal const val PLACE_QUERY_MIN_LENGTH = 2

