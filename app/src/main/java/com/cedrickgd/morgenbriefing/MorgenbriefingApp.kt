package com.cedrickgd.morgenbriefing

import android.app.Application
import android.util.Log
import com.cedrickgd.morgenbriefing.listen.ListenController
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import com.cedrickgd.morgenbriefing.notify.Notifications
import com.cedrickgd.morgenbriefing.update.UpdateNotifications
import com.cedrickgd.morgenbriefing.update.UpdateServices
import com.cedrickgd.morgenbriefing.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MorgenbriefingApp : Application() {
    lateinit var container: AppContainer
        private set

    /** In-app updater (release check, download, verification); created on first use. */
    val updates: UpdateServices by lazy { UpdateServices(this, container) }

    /** "Zuhören": text-to-speech for articles. */
    val listen: ListenController by lazy { ListenController(this) }

    /** Process-wide scope for fire-and-forget work (alarm rescheduling etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        UpdateNotifications.createChannel(this)

        // Keep the morning alarm in sync with the settings (time, on/off).
        appScope.launch {
            container.settings.settings
                .map { Triple(it.notificationsEnabled, it.notifyHour, it.notifyMinute) }
                .distinctUntilChanged()
                .collect { AlarmScheduler.sync(this@MorgenbriefingApp, container.settings.current()) }
        }

        scheduleUpdateChecks()
        // Downloaded APKs are useless once their version is installed.
        appScope.launch(Dispatchers.IO) { updates.pruneAfterUpdate() }
    }

    /** Twice-daily background release check ("update-check", kept if already scheduled). */
    private fun scheduleUpdateChecks() {
        try {
            UpdateWorker.schedule(this)
        } catch (e: IllegalStateException) {
            // WorkManager not initialised (should not happen with the default initializer).
            Log.w(TAG, "Could not schedule the update check", e)
        }
    }

    private companion object {
        const val TAG = "MorgenbriefingApp"
    }
}
