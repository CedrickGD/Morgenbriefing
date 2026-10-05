package com.cedrickgd.morgenbriefing.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import com.cedrickgd.morgenbriefing.notify.Notifications

/**
 * Everything outside the app that decides whether the 5:30 notification arrives on time.
 * Cheap to read; screens re-read it on every resume because the user changes these in the
 * system settings.
 */
@Immutable
data class SystemStatus(
    /** POST_NOTIFICATIONS granted (always true below API 33). */
    val permissionGranted: Boolean,
    /** App-level notification switch in the system settings. */
    val appNotificationsEnabled: Boolean,
    /** The user switched off just the morning channel (API 26+). */
    val morningChannelBlocked: Boolean,
    /** The exact-alarm permission exists on this Android version (API 31+). */
    val exactAlarmRelevant: Boolean,
    val exactAlarmsAllowed: Boolean,
    /** The app is exempt from battery optimization (Doze may otherwise delay the alarm). */
    val batteryUnrestricted: Boolean,
) {
    /** A morning notification would actually be shown. */
    val canNotify: Boolean
        get() = permissionGranted && appNotificationsEnabled && !morningChannelBlocked

    companion object {
        fun read(context: Context): SystemStatus {
            val manager = NotificationManagerCompat.from(context)
            val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannelCompat(Notifications.CHANNEL_MORNING)?.importance ==
                NotificationManagerCompat.IMPORTANCE_NONE
            val power = ContextCompat.getSystemService(context, PowerManager::class.java)
            return SystemStatus(
                permissionGranted = permissionGranted,
                appNotificationsEnabled = manager.areNotificationsEnabled(),
                morningChannelBlocked = channelBlocked,
                exactAlarmRelevant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                exactAlarmsAllowed = AlarmScheduler.canScheduleExact(context),
                batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            )
        }
    }
}
