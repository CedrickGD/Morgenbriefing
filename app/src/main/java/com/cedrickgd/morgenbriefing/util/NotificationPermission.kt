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
 * * API 33+ without permission: shows the system permission dialog — or, once the user has
 *   denied it permanently (the system would silently refuse), opens the app's notification
 *   settings instead.
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
        latestOnResult(granted)
    }
    return remember(context, activity, launcher) {
        {
            val status = SystemStatus.read(context)
            when {
                status.canNotify -> latestOnResult(true)

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !status.permissionGranted -> {
                    if (notificationsNeedSettings(context, activity, status)) {
                        // Denied twice ("don't ask again"): only the settings can help now.
                        SystemIntents.openAppNotificationSettings(context)
                    } else {
                        PermissionMemory(context).askedForNotifications = true
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
        val showRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        return PermissionMemory(context).askedForNotifications && !showRationale
    }
    return true
}

/**
 * Remembers whether the permission dialog was ever shown. Android offers no API to tell
 * "never asked" from "permanently denied" — both report no rationale — so we keep that bit.
 */
private class PermissionMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var askedForNotifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_NOTIFICATIONS = "asked_post_notifications"
    }
}
