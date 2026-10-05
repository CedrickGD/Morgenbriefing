package com.cedrickgd.morgenbriefing.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Deep links into the system settings the morning notification depends on. Every entry point
 * has a fallback chain (ending at the app's info page), because OEM builds — Samsung included —
 * do not always ship every settings activity. All functions return whether something opened.
 */
internal object SystemIntents {

    /** The app's notification settings (API 26+), else the app info page. */
    fun openAppNotificationSettings(context: Context): Boolean {
        val pkg = context.packageName
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        } else {
            // Undocumented but long-standing extras understood by API 21–25 settings apps.
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("app_package", pkg)
                .putExtra("app_uid", context.applicationInfo.uid)
        }
        return context.startFirst(primary, appDetailsIntent(context))
    }

    /** Settings of a single notification channel (API 26+), else the app's notification settings. */
    fun openChannelSettings(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            if (context.startFirst(channel)) return true
        }
        return openAppNotificationSettings(context)
    }

    /** "Alarme & Erinnerungen" for this app (API 31+). Below 31 exact alarms are always allowed. */
    fun requestExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val request = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        return context.startFirst(request, appDetailsIntent(context))
    }

    /**
     * System dialog "Akku-Optimierung ignorieren?" for this app; falls back to the global battery
     * optimization list and finally the app info page (where Samsung keeps "Akku").
     * The app is sideloaded and declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS on purpose.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = context.startFirst(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetailsIntent(context),
    )

    /** The app info page ("App-Info"). */
    fun openAppDetails(context: Context): Boolean = context.startFirst(appDetailsIntent(context))

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** Starts the first intent that resolves; adds NEW_TASK when [this] is not an Activity. */
    private fun Context.startFirst(vararg intents: Intent): Boolean {
        val needsNewTask = findActivity() == null
        for (intent in intents) {
            if (needsNewTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // Not available on this build, try the next one.
            } catch (e: SecurityException) {
                // Some OEM settings activities are not exported to third-party apps.
            }
        }
        return false
    }
}

/** Unwraps [ContextWrapper]s until an [Activity] is found. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
