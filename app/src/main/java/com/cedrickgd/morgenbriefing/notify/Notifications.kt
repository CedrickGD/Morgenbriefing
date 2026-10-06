package com.cedrickgd.morgenbriefing.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.cedrickgd.morgenbriefing.R
import com.cedrickgd.morgenbriefing.domain.NotificationText
import com.cedrickgd.morgenbriefing.ui.components.EmojiSupport
import com.cedrickgd.morgenbriefing.ui.shell.AppLinks

/**
 * Everything the app posts to the notification shade: the morning briefing on
 * [CHANNEL_MORNING] and the quiet "Briefing wird geladen…" notice on [CHANNEL_SYNC] that
 * WorkManager shows while expedited work runs as a foreground service (below API 31).
 */
object Notifications {
    const val CHANNEL_MORNING = "morning"
    const val CHANNEL_SYNC = "sync"
    const val ID_MORNING = 530
    const val ID_SYNC = 531

    private const val TAG = "Notifications"
    private const val SYNC_TITLE = "Briefing wird geladen…"
    private const val SYNC_TEXT = "Wetter und News werden gerade frisch geholt."

    /** Idempotent; no-op below API 26. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val morning = NotificationChannelCompat.Builder(CHANNEL_MORNING, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.channel_morning_name))
            .setDescription(context.getString(R.string.channel_morning_desc))
            .setShowBadge(true)
            .build()
        val sync = NotificationChannelCompat.Builder(CHANNEL_SYNC, NotificationManagerCompat.IMPORTANCE_MIN)
            .setName(context.getString(R.string.channel_sync_name))
            .setDescription(context.getString(R.string.channel_sync_desc))
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(morning, sync))
    }

    /**
     * BigTextStyle notification on [CHANNEL_MORNING], small icon R.drawable.ic_stat_sun, tapping
     * opens MainActivity. [silent] = update an existing one without sound (setOnlyAlertOnce).
     * Respects POST_NOTIFICATIONS on 33+ (silently skips when not granted).
     */
    fun showMorning(context: Context, text: NotificationText, silent: Boolean = false) {
        if (!canPost(context)) {
            Log.i(TAG, "Notifications are blocked, skipping the morning briefing")
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_MORNING)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            // The system draws these with its own font: swap emoji it cannot draw (Android 7–9).
            .setContentTitle(EmojiSupport.displayIn(text.title))
            .setContentText(EmojiSupport.displayIn(text.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(EmojiSupport.displayIn(text.bigText)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        openAppIntent(context)?.let(builder::setContentIntent)
        post(context, ID_MORNING, builder)
    }

    /** Required for expedited work below API 31. */
    fun syncForegroundInfo(context: Context): ForegroundInfo {
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(SYNC_TITLE)
            .setContentText(SYNC_TEXT)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        openAppIntent(context)?.let(builder::setContentIntent)
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_SYNC, notification)
        }
    }

    /** POST_NOTIFICATIONS granted (API 33+) and notifications not switched off for the app. */
    fun canPost(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Whether the morning notification is still in the shade (not tapped or swiped away).
     * Returns false when the system refuses to tell, so a dismissed briefing is never revived.
     */
    internal fun isMorningShowing(context: Context): Boolean = try {
        NotificationManagerCompat.from(context).activeNotifications
            .any { it.id == ID_MORNING && it.tag == null }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not read active notifications", e)
        false
    }

    /**
     * Opens (or brings to front) the launcher activity on "Heute" without referencing it
     * directly. The explicit page extra is what tells this tap apart from the launcher icon,
     * whose intent is otherwise identical (MainActivity only navigates for an extra).
     */
    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(AppLinks.EXTRA_OPEN_PAGE, AppLinks.PAGE_TODAY)
        return PendingIntent.getActivity(
            context,
            ID_MORNING,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Callers check [canPost] first; the SecurityException catch covers a revocation race. */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between canPost() and notify(): nothing to show, nothing to crash.
            Log.w(TAG, "Not allowed to post notification $id", e)
        }
    }
}
