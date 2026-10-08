package com.cedrickgd.morgenbriefing.util

import kotlin.math.max

/*
 * When the notification permission (API 33+) is denied for good, so that only the system
 * settings can help. Android has no API for that: "never asked" and "denied for good" both
 * report no rationale, and a dialog dismissed with Back also comes back denied without one.
 * What tells them apart is whether the system showed its dialog at all ([refusedWithoutDialog]).
 * Pure logic, unit-tested; NotificationPermission.kt watches the dialog and stores the history.
 */

/** What earlier results of the system permission dialog showed. */
internal data class NotificationAsks(
    /** Requests the system refused without showing its dialog: it will not ask any more. */
    val silentDenials: Int = 0,
    /** The system wanted a rationale after a denial: the user said no on purpose once. */
    val sawRationale: Boolean = false,
)

/**
 * Adds one result of the dialog, with the rationale flag read right after it; a grant starts
 * over. Only a denial without a dialog ([refusedWithoutDialog]) counts as a silent one – a
 * dialog dismissed with Back (also several times) does not.
 */
internal fun NotificationAsks.after(granted: Boolean, showRationale: Boolean, silent: Boolean): NotificationAsks =
    if (granted) {
        NotificationAsks()
    } else {
        NotificationAsks(
            silentDenials = silentDenials + if (silent) 1 else 0,
            sawRationale = sawRationale || showRationale,
        )
    }

/**
 * True when the system will not show the dialog again: the permission is denied, no rationale is
 * due now, and either the user denied it on purpose before (a rationale was due after an earlier
 * denial, so this "no rationale" means the second "Nicht zulassen" made it final) or the system
 * already answered a request without showing its dialog. Denials after a dialog dismissed with
 * Back keep "Erlauben" asking, however often.
 */
internal fun notificationsDeniedForGood(granted: Boolean, showRationale: Boolean, asks: NotificationAsks): Boolean =
    !granted && !showRationale && (asks.sawRationale || asks.silentDenials >= 1)

/**
 * Shortest time the app is away (paused or without window focus) when the system really shows
 * its dialog: it slides in, and even a hasty Back needs the user to see it first. A request the
 * system refuses without a dialog pauses the app as well – the permission activity starts and
 * finishes at once – but only for a moment. Measured on Android 16 (emulator): refused without
 * dialog 0.17–0.5 s, a scripted Back 0.5 s after the tap 0.5 s, Back by hand 2 s and more.
 */
internal const val MIN_DIALOG_MS = 650L

/**
 * True when the system answered a permission request without showing its dialog: the app was
 * paused or lost window focus (the permission activity started) but only for a moment. Nothing
 * seen at all – no request reached the system, e.g. a second tap while one was pending – or no
 * observation ([sighting] null, e.g. the activity was recreated meanwhile) is not counted.
 */
internal fun refusedWithoutDialog(sighting: DialogSighting?): Boolean {
    if (sighting == null) return false
    if (!sighting.paused && !sighting.lostFocus) return false
    return max(sighting.pausedMs, sighting.unfocusedMs) < MIN_DIALOG_MS
}
