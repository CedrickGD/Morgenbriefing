package com.cedrickgd.morgenbriefing.notify

import android.content.Context
import androidx.work.ForegroundInfo
import com.cedrickgd.morgenbriefing.domain.NotificationText

object Notifications {
    const val CHANNEL_MORNING = "morning"
    const val CHANNEL_SYNC = "sync"
    const val ID_MORNING = 530
    const val ID_SYNC = 531

    /** Idempotent; no-op below API 26. */
    fun createChannels(context: Context) { TODO("agent E") }

    /**
     * BigTextStyle notification on [CHANNEL_MORNING], small icon R.drawable.ic_stat_sun, tapping
     * opens MainActivity. [silent] = update an existing one without sound (setOnlyAlertOnce).
     * Respects POST_NOTIFICATIONS on 33+ (silently skips when not granted).
     */
    fun showMorning(context: Context, text: NotificationText, silent: Boolean = false) { TODO("agent E") }

    /** Required for expedited work below API 31. */
    fun syncForegroundInfo(context: Context): ForegroundInfo = TODO("agent E")

    fun canPost(context: Context): Boolean = TODO("agent E")
}
