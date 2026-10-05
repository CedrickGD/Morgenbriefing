package com.cedrickgd.morgenbriefing.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination

/** The three bottom-bar tabs. */
internal enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
    private val matches: (NavDestination) -> Boolean,
) {
    TODAY(TodayRoute, "Heute", Icons.Rounded.WbSunny, Icons.Outlined.WbSunny, { it.hasRoute<TodayRoute>() }),
    HISTORY(HistoryRoute, "Verlauf", Icons.Rounded.History, Icons.Outlined.History, { it.hasRoute<HistoryRoute>() }),
    SETTINGS(SettingsRoute, "Einstellungen", Icons.Rounded.Settings, Icons.Outlined.Settings, { it.hasRoute<SettingsRoute>() }),
    ;

    /** True when [destination] (or one of its parents) is this tab. */
    fun isSelected(destination: NavDestination?): Boolean =
        destination?.hierarchy?.any(matches) == true
}

/**
 * Bottom-bar navigation: keeps one copy of each tab, saves the state of the tab being left
 * (scroll position, ViewModels, an open day detail) and restores it when coming back.
 */
internal fun NavController.navigateToTopLevel(route: Any) {
    // The graph is set during the first composition; ignore signals that arrive before that.
    if (currentBackStackEntry == null) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
