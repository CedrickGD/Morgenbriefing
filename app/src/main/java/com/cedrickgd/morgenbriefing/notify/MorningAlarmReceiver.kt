package com.cedrickgd.morgenbriefing.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cedrickgd.morgenbriefing.container

/**
 * Receives the daily alarm set by [AlarmScheduler]: hands the actual work to [MorningWorker]
 * (network, retries, foreground handling) and immediately schedules tomorrow's alarm.
 */
class MorningAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MORNING) return
        val app = context.applicationContext
        try {
            MorningWorker.enqueue(app)
        } catch (e: IllegalStateException) {
            // WorkManager not initialised; still reschedule so tomorrow works.
            Log.e(TAG, "Could not enqueue the morning briefing", e)
        }
        runAsync(app, TAG) {
            AlarmScheduler.sync(app, app.container.settings.current())
        }
    }

    companion object {
        const val ACTION_MORNING = "com.cedrickgd.morgenbriefing.MORNING"
        private const val TAG = "MorningAlarm"
    }
}
