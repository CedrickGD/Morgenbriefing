package com.cedrickgd.morgenbriefing.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cedrickgd.morgenbriefing.container
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Re-arms the morning alarm whenever the system may have dropped or shifted it (reboot, app
 * update, clock or time-zone change, exact-alarm permission change). If the phone was off at
 * the scheduled time and today's briefing is still missing, it is delivered late rather than
 * not at all.
 */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val app = context.applicationContext
        runAsync(app, TAG) {
            val settings = app.container.settings.current()
            AlarmScheduler.sync(app, settings)
            if (shouldCatchUp(settings, ZonedDateTime.now(ZoneId.systemDefault()), LocalDate.now(BERLIN))) {
                Log.i(TAG, "Today's briefing was missed ($action), catching up")
                MorningWorker.enqueue(app)
            }
        }
    }

    companion object {
        private const val TAG = "SystemEvents"

        /** How late a missed briefing may still be delivered. */
        internal val CATCH_UP_WINDOW: Duration = Duration.ofHours(3)

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, // "android.intent.action.TIME_SET"
            Intent.ACTION_TIMEZONE_CHANGED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31)
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )

        /**
         * True if notifications are on, [today] (Europe/Berlin) has not been notified yet and
         * today's scheduled time (in [now]'s zone) passed less than [CATCH_UP_WINDOW] ago.
         */
        internal fun shouldCatchUp(settings: AppSettings, now: ZonedDateTime, today: LocalDate): Boolean {
            if (!settings.notificationsEnabled) return false
            if (settings.lastNotifiedDate == today.toString()) return false
            val time = AlarmScheduler.timeOf(settings.notifyHour, settings.notifyMinute)
            val scheduled = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
            if (scheduled.isAfter(now)) return false
            return Duration.between(scheduled, now) < CATCH_UP_WINDOW
        }
    }
}
