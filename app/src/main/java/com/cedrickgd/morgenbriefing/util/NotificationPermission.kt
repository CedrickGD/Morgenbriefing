package com.cedrickgd.morgenbriefing.util

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cedrickgd.morgenbriefing.notify.Notifications

/**
 * Returns an action that makes notifications work, whatever is blocking them:
 *
 * * API 33+ without permission: shows the system permission dialog — or, once it is denied
 *   for good ([notificationsDeniedForGood]: the system refuses without asking), opens the app's
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
    // Whether the system showed its dialog ([refusedWithoutDialog]): the activity is paused and
    // its window loses focus while the dialog is up – only for a moment when the system refuses
    // without asking.
    val watch = remember { PermissionDialogWatch(SystemClock::elapsedRealtime) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(lifecycleOwner, view, watch) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> watch.onPause()
                Lifecycle.Event.ON_RESUME -> watch.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener(watch::onWindowFocus)
        val treeObserver = view.viewTreeObserver.takeIf { it.isAlive }
        treeObserver?.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            val alive = treeObserver?.takeIf { it.isAlive } ?: view.viewTreeObserver.takeIf { it.isAlive }
            alive?.removeOnWindowFocusChangeListener(focusListener)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val sighting = watch.finish()
        val rationale = notificationRationale(activity)
        // Remembered from the result, not before the request: a process that dies with the
        // dialog open has not been refused anything.
        PermissionMemory(context).recordNotificationResult(granted, rationale, silent = refusedWithoutDialog(sighting))
        latestOnResult(granted)
    }
    return remember(context, activity, launcher, watch) {
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
                            watch.start()
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } catch (e: ActivityNotFoundException) {
                            watch.finish()
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

    /**
     * Older versions stored "asked once" (up to 1.1) or every denial (1.2 test builds), which
     * cannot tell a Back dismissal from a refusal: both start from zero here. If the system
     * really refuses for good, the next request shows that.
     */
    val notificationAsks: NotificationAsks
        get() = NotificationAsks(
            silentDenials = prefs.getInt(KEY_SILENT_DENIALS, 0),
            sawRationale = prefs.getBoolean(KEY_RATIONALE, false),
        )

    fun recordNotificationResult(granted: Boolean, showRationale: Boolean, silent: Boolean) {
        val next = notificationAsks.after(granted, showRationale, silent)
        prefs.edit {
            putInt(KEY_SILENT_DENIALS, next.silentDenials)
            putBoolean(KEY_RATIONALE, next.sawRationale)
            remove(KEY_LEGACY_ASKED)
            remove(KEY_LEGACY_DENIALS)
        }
    }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_LEGACY_ASKED = "asked_post_notifications"
        const val KEY_LEGACY_DENIALS = "post_notifications_denials"
        const val KEY_SILENT_DENIALS = "post_notifications_silent_denials"
        const val KEY_RATIONALE = "post_notifications_rationale_seen"
    }
}
