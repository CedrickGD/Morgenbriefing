package com.cedrickgd.morgenbriefing.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Owns the single daily alarm that wakes the app for the morning briefing. The alarm fires
 * [MorningAlarmReceiver], which starts [MorningWorker] and calls [sync] again for the next day.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 530

    /** Schedules the next morning alarm if enabled, cancels it otherwise. Safe to call often. */
    fun sync(context: Context, settings: AppSettings) {
        if (!settings.notificationsEnabled) {
            cancel(context)
            return
        }
        val alarmManager = alarmManager(context)
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable, cannot schedule the morning briefing")
            return
        }
        val trigger = nextTrigger(ZonedDateTime.now(ZoneId.systemDefault()), settings.notifyHour, settings.notifyMinute)
        val triggerAtMillis = trigger.toInstant().toEpochMilli()
        val operation = morningIntent(context)

        if (canScheduleExact(context) && setExact(alarmManager, triggerAtMillis, operation)) {
            Log.i(TAG, "Exact morning alarm set for $trigger")
            return
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        Log.i(TAG, "Inexact morning alarm set for $trigger")
    }

    fun cancel(context: Context) {
        alarmManager(context)?.cancel(morningIntent(context))
    }

    /** Exact alarms allowed (always true below API 31). */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context)?.canScheduleExactAlarms() == true

    /**
     * Next occurrence of hour:minute strictly after [now], in [now]'s zone (DST-safe).
     *
     * A time that falls into a spring-forward gap is moved forward by the gap length
     * (02:30 becomes 03:30); in an autumn overlap the earlier offset wins, so the alarm fires
     * once per day. Out-of-range values are clamped to 00:00–23:59.
     */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val time = timeOf(hour, minute)
        val today = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
        return if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
    }

    /** The configured notification time as a valid [LocalTime]. */
    internal fun timeOf(hour: Int, minute: Int): LocalTime =
        LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /**
     * Returns false when the platform refused the exact alarm, so the caller can fall back.
     * Callers check [canScheduleExact] first; a revocation race surfaces as SecurityException.
     */
    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun setExact(alarmManager: AlarmManager, triggerAtMillis: Long, operation: PendingIntent): Boolean =
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, falling back to an inexact one", e)
            false
        }

    private fun morningIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, MorningAlarmReceiver::class.java).setAction(MorningAlarmReceiver.ACTION_MORNING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmManager(context: Context): AlarmManager? =
        ContextCompat.getSystemService(context, AlarmManager::class.java)
}
