package com.cedrickgd.morgenbriefing.ui

import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.setSingletonImageLoaderFactory
import com.cedrickgd.morgenbriefing.container
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.ui.nav.AppNavHost
import com.cedrickgd.morgenbriefing.ui.nav.TodayRoute
import com.cedrickgd.morgenbriefing.ui.nav.TopLevelDestination
import com.cedrickgd.morgenbriefing.ui.nav.isDetail
import com.cedrickgd.morgenbriefing.ui.nav.navigateToTopLevel
import com.cedrickgd.morgenbriefing.ui.news.newsImageLoader
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "AppRoot"

/** Same scrims androidx.activity uses for 3-button navigation (light ≈ 90 % white, dark ≈ 50 %). */
private val LightNavScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * Root of the UI: theme from the stored settings, bottom navigation and the nav graph.
 *
 * @param openTodayRequests increments on every notification tap (Activity.onNewIntent);
 *   each change jumps to the Today tab.
 * @param onReady called once the stored settings are known, so the splash screen can hide
 *   without flashing the wrong theme.
 */
@Composable
fun AppRoot(
    openTodayRequests: Int = 0,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    // Article pictures: one loader for the whole app, sharing the app's OkHttp client.
    val http = remember(context) { context.container.http }
    setSingletonImageLoaderFactory { platformContext -> newsImageLoader(platformContext, http) }

    val settingsFlow = remember(context) {
        context.container.settings.settings
            .map<AppSettings, AppSettings?> { it }
            .catch { e ->
                Log.w(TAG, "Settings could not be read, using defaults", e)
                emit(AppSettings())
            }
    }
    val loaded by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings = loaded ?: AppSettings()

    val latestOnReady by rememberUpdatedState(onReady)
    val ready = loaded != null
    LaunchedEffect(ready) { if (ready) latestOnReady() }

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MorgenbriefingTheme(themeMode = settings.themeMode, accent = settings.accent) {
        AppShell(openTodayRequests = openTodayRequests, darkTheme = darkTheme)
    }
}

@Composable
private fun AppShell(openTodayRequests: Int, darkTheme: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = backStackEntry?.isDetail() != true

    SystemBarsEffect(darkTheme = darkTheme, bottomBarVisible = showBottomBar)

    LaunchedEffect(openTodayRequests) {
        if (openTodayRequests > 0) {
            navController.navigateToTopLevel(TodayRoute)
            // The tab restores its own stack — close an article that was open on top of Today.
            if (navController.currentBackStackEntry?.isDetail() == true) {
                navController.popBackStack<TodayRoute>(inclusive = false)
            }
        }
    }

    Scaffold(
        // Screens draw edge-to-edge and handle the top inset themselves; the bottom inset is
        // consumed by the NavigationBar (or added by the detail screen when the bar is hidden).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            ) {
                AppNavigationBar(
                    destination = destination,
                    onSelect = { tab -> navController.navigateToTopLevel(tab.route) },
                )
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            contentPadding = innerPadding,
            openTodaySignal = openTodayRequests,
        )
    }
}

/**
 * Material 3 navigation bar. It keeps its default window insets, so it sits above the gesture
 * handle as well as above the 3-button bar (and extends its color underneath both).
 */
@Composable
private fun AppNavigationBar(destination: NavDestination?, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { tab ->
            val selected = tab.isSelected(destination)
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                // The label already names the tab; a description would be read twice.
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/**
 * Keeps the system bars in line with the in-app theme (which may differ from the system's):
 * transparent status bar with matching icons; with the bottom bar visible the 3-button
 * navigation bar shows the NavigationBar color seamlessly, on the detail screen it gets the
 * usual translucent scrim so the buttons stay readable over scrolling content.
 */
@Composable
private fun SystemBarsEffect(darkTheme: Boolean, bottomBarVisible: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme, bottomBarVisible) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LightNavScrim, DarkNavScrim) { darkTheme },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = !bottomBarVisible
        }
        onDispose { }
    }
}
