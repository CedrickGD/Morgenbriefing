package com.cedrickgd.morgenbriefing

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cedrickgd.morgenbriefing.ui.AppRoot

/**
 * Single activity (launchMode singleTop). A tap on the morning notification while the app is
 * open arrives in [onNewIntent] and switches to the Today tab.
 */
class MainActivity : ComponentActivity() {

    /** Bumped on every new intent; observed by [AppRoot]. Resets with the activity on purpose. */
    private var openTodayRequests by mutableIntStateOf(0)

    /** Set once the settings are loaded; read by the splash screen on the UI thread. */
    @Volatile
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash until the theme is known (no light/dark flash), but never for long.
        val shownAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            !contentReady && SystemClock.uptimeMillis() - shownAt < MAX_SPLASH_MS
        }

        setContent {
            AppRoot(
                openTodayRequests = openTodayRequests,
                onReady = { contentReady = true },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTodayRequests++
    }

    private companion object {
        const val MAX_SPLASH_MS = 1_200L
    }
}
