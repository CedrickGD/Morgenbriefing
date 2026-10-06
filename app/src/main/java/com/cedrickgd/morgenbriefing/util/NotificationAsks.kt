package com.cedrickgd.morgenbriefing.util

/*
 * When the notification permission (API 33+) is denied for good, so that only the system
 * settings can help. Android has no API for that: "never asked" and "denied for good" both
 * report no rationale, and a dialog dismissed with Back also comes back denied without one.
 * Pure logic, unit-tested; NotificationPermission.kt stores the history.
 */

/** What earlier results of the system permission dialog showed. */
internal data class NotificationAsks(
    /** Requests that came back denied, including a dialog dismissed with Back. */
    val denials: Int = 0,
    /** The system wanted a rationale after a denial: the user said no on purpose once. */
    val sawRationale: Boolean = false,
)

/** Adds one result of the dialog, with the rationale flag read right after it; a grant starts over. */
internal fun NotificationAsks.after(granted: Boolean, showRationale: Boolean): NotificationAsks =
    if (granted) {
        NotificationAsks()
    } else {
        NotificationAsks(denials = denials + 1, sawRationale = sawRationale || showRationale)
    }

/**
 * True when the system will not show the dialog again: the permission is denied, no rationale is
 * due now, and either the user denied it on purpose before (a rationale was due after an earlier
 * denial, so this "no rationale" means the second "Nicht zulassen" made it final) or two requests
 * came back denied (the system refuses without asking). A single denial without a rationale –
 * the dialog dismissed with Back – keeps "Erlauben" asking.
 */
internal fun notificationsDeniedForGood(granted: Boolean, showRationale: Boolean, asks: NotificationAsks): Boolean =
    !granted && !showRationale && (asks.sawRationale || asks.denials >= 2)
