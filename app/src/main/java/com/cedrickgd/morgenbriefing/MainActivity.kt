package com.cedrickgd.morgenbriefing

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cedrickgd.morgenbriefing.ui.AppRoot
import com.cedrickgd.morgenbriefing.ui.shell.AppLinks
import com.cedrickgd.morgenbriefing.ui.shell.MainPage
import com.cedrickgd.morgenbriefing.ui.shell.PageRequest

/**
 * Single activity (launchMode singleTop, notifications add SINGLE_TOP | CLEAR_TOP). A launch
 * intent may carry [AppLinks.EXTRA_OPEN_PAGE] to open a page of the pager:
 *
 * * on a fresh start the pager simply opens on that page ("Heute" without the extra),
 * * while the app is open a notification tap arrives in [onNewIntent]: pushed screens close
 *   and the pager glides to the page ("Heute" — the morning notification — also scrolls back
 *   to the top). An intent without the extra (the launcher icon) changes nothing: the user
 *   comes back to exactly where they were, like from Recents.
 */
class MainActivity : ComponentActivity() {

    /** Latest unhandled request from [onNewIntent]; cleared by the shell once acted on. */
    private var pageRequest by mutableStateOf<PageRequest?>(null)
    private var nextRequestId = 0L

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

        // After a recreation the pager restores its own page; the old intent is history.
        val initialPage = if (savedInstanceState == null) intent.requestedPage() else MainPage.Start

        setContent {
            AppRoot(
                initialPage = initialPage,
                pageRequest = pageRequest,
                onPageRequestHandled = { handled -> if (pageRequest == handled) pageRequest = null },
                onReady = { contentReady = true },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The launcher icon delivers the same MAIN/LAUNCHER intent as a notification would, but
        // without the extra: only an explicit page request navigates.
        val page = MainPage.requestedBy(intent.openPageLink()) ?: return
        pageRequest = PageRequest(page, id = ++nextRequestId)
    }

    /** The page an intent asks for; "Heute" when it names none (or its extras are unreadable). */
    private fun Intent?.requestedPage(): MainPage = MainPage.fromLink(openPageLink())

    /** [AppLinks.EXTRA_OPEN_PAGE] of this intent; null when missing or unreadable. */
    private fun Intent?.openPageLink(): String? =
        runCatching { this?.getStringExtra(AppLinks.EXTRA_OPEN_PAGE) }.getOrNull()

    private companion object {
        const val MAX_SPLASH_MS = 1_200L
    }
}
