package com.cedrickgd.morgenbriefing.util

/**
 * What the app saw of its own window between launching the system permission dialog and the
 * result: how long the activity was paused and how long its window had no focus. Pure, driven by
 * the requester's lifecycle and window-focus callbacks; times come from [clock] (milliseconds).
 */
internal class PermissionDialogWatch(private val clock: () -> Long) {
    private var launchedAt = NONE
    private var pausedAt = NONE
    private var unfocusedAt = NONE
    private var pausedMs = 0L
    private var unfocusedMs = 0L
    private var paused = false
    private var lostFocus = false

    /** The dialog is about to be launched: starts a fresh observation. */
    fun start() {
        launchedAt = clock()
        pausedAt = NONE
        unfocusedAt = NONE
        pausedMs = 0L
        unfocusedMs = 0L
        paused = false
        lostFocus = false
    }

    fun onPause() {
        if (launchedAt == NONE || pausedAt != NONE) return
        paused = true
        pausedAt = clock()
    }

    fun onResume() {
        if (launchedAt == NONE || pausedAt == NONE) return
        pausedMs += clock() - pausedAt
        pausedAt = NONE
    }

    fun onWindowFocus(hasFocus: Boolean) {
        if (launchedAt == NONE) return
        if (!hasFocus) {
            if (unfocusedAt != NONE) return
            lostFocus = true
            unfocusedAt = clock()
        } else if (unfocusedAt != NONE) {
            unfocusedMs += clock() - unfocusedAt
            unfocusedAt = NONE
        }
    }

    /**
     * The result arrived: what was seen since [start], or null when nothing was being watched
     * (e.g. the activity was recreated while the dialog was open).
     */
    fun finish(): DialogSighting? {
        if (launchedAt == NONE) return null
        val now = clock()
        val sighting = DialogSighting(
            elapsedMs = now - launchedAt,
            paused = paused,
            pausedMs = pausedMs + if (pausedAt != NONE) now - pausedAt else 0L,
            lostFocus = lostFocus,
            unfocusedMs = unfocusedMs + if (unfocusedAt != NONE) now - unfocusedAt else 0L,
        )
        launchedAt = NONE
        return sighting
    }

    private companion object {
        const val NONE = -1L
    }
}

/** One observation of [PermissionDialogWatch]. */
internal data class DialogSighting(
    /** From launching the dialog to its result. */
    val elapsedMs: Long,
    /** The activity was paused in between. */
    val paused: Boolean,
    val pausedMs: Long,
    /** The activity's window lost focus in between. */
    val lostFocus: Boolean,
    val unfocusedMs: Long,
)
