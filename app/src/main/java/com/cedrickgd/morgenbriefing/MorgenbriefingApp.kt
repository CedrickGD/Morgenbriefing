package com.cedrickgd.morgenbriefing

import android.app.Application
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import com.cedrickgd.morgenbriefing.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MorgenbriefingApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Process-wide scope for fire-and-forget work (alarm rescheduling etc.). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)

        // Keep the morning alarm in sync with the settings (time, on/off).
        appScope.launch {
            container.settings.settings
                .map { Triple(it.notificationsEnabled, it.notifyHour, it.notifyMinute) }
                .distinctUntilChanged()
                .collect { AlarmScheduler.sync(this@MorgenbriefingApp, container.settings.current()) }
        }
    }
}
