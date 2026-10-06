package com.cedrickgd.morgenbriefing.util

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import com.cedrickgd.morgenbriefing.notify.Notifications

/**
 * Returns an action that makes notifications work, whatever is blocking them:
 *
 * * API 33+ without permission: shows the system permission dialog — or, once it is denied
 *   for good ([notificationsDeniedForGood]: the system would silently refuse), opens the app's
 *   notification settings instead. A dialog dismissed with Back asks again next time.
 * * Morning channel switched off: opens that channel's settings.
 * * Notifications disabled for the app (any API level): opens the app's notification settings.
 *
 * [onResult] is called with the permission result, or `true` right away when everything is
 * already fine. Screens re-check [SystemStatus] on resume for the settings round trips.
 */
@Composable
fun rememberNotificationPermissionRequester(onResult: (granted: Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val latestOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Remembered from the result, not before the request: a process that dies with the
        // dialog open has not been refused anything.
        PermissionMemory(context).recordNotificationResult(granted, notificationRationale(activity))
        latestOnResult(granted)
    }
    return remember(context, activity, launcher) {
        {
            val status = SystemStatus.read(context)
            when {
                status.canNotify -> latestOnResult(true)

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.permissionGranted -> {
                    if (notificationsNeedSettings(context, activity, status)) {
                        // Denied for good: only the settings can help now.
                        SystemIntents.openAppNotificationSettings(context)
                    } else {
                        try {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } catch (e: ActivityNotFoundException) {
                            SystemIntents.openAppNotificationSettings(context)
                        }
                    }
                }

                status.appNotificationsEnabled && status.morningChannelBlocked ->
                    SystemIntents.openChannelSettings(context, Notifications.CHANNEL_MORNING)

                else -> SystemIntents.openAppNotificationSettings(context)
            }
        }
    }
}

/**
 * True when only the system settings can make notifications work — the requester above opens
 * them instead of asking: the permission was denied for good (API 33+), or notifications are
 * switched off for the app or the morning channel. False when all is fine or the system dialog
 * can still ask.
 */
fun notificationsNeedSettings(
    context: Context,
    activity: Activity?,
    status: SystemStatus = SystemStatus.read(context),
): Boolean {
    if (status.canNotify) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.permissionGranted) {
        return notificationsDeniedForGood(
            granted = false,
            showRationale = notificationRationale(activity),
            asks = PermissionMemory(context).notificationAsks,
        )
    }
    return true
}

/** The system would explain the notification permission before asking (API 33+). */
private fun notificationRationale(activity: Activity?): Boolean =
    activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Remembers what the permission dialog returned ([NotificationAsks]). Android offers no API to
 * tell "never asked" from "permanently denied" — both report no rationale — so we keep that.
 */
private class PermissionMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Up to 1.2 only "the dialog was shown once" was stored: that counts as one denial. */
    val notificationAsks: NotificationAsks
        get() = NotificationAsks(
            denials = prefs.getInt(KEY_DENIALS, if (prefs.getBoolean(KEY_LEGACY_ASKED, false)) 1 else 0),
            sawRationale = prefs.getBoolean(KEY_RATIONALE, false),
        )

    fun recordNotificationResult(granted: Boolean, showRationale: Boolean) {
        val next = notificationAsks.after(granted, showRationale)
        prefs.edit {
            putInt(KEY_DENIALS, next.denials)
            putBoolean(KEY_RATIONALE, next.sawRationale)
            remove(KEY_LEGACY_ASKED)
        }
    }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_LEGACY_ASKED = "asked_post_notifications"
        const val KEY_DENIALS = "post_notifications_denials"
        const val KEY_RATIONALE = "post_notifications_rationale_seen"
    }
}
