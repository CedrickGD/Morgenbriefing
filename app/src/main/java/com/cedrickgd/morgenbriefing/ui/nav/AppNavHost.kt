package com.cedrickgd.morgenbriefing.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.cedrickgd.morgenbriefing.ui.shell.MainPager
import com.cedrickgd.morgenbriefing.ui.shell.ShellNavigator
import com.cedrickgd.morgenbriefing.ui.today.TodayViewModel

/**
 * The navigation graph: [MainRoute] (the page pager) is the start destination and, for now,
 * the only one — articles open in the in-app browser. The host stays so a future screen can be
 * pushed over the shared sky without touching the shell.
 *
 * @param pagePadding bottom room for the floating chrome, for the pager pages.
 * @param openTodaySignal increments whenever "Heute" should scroll back to the top.
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    navigator: ShellNavigator,
    pagePadding: PaddingValues,
    openTodaySignal: Int,
    todayViewModel: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = MainRoute,
        modifier = modifier,
    ) {
        composable<MainRoute> { entry ->
            val top by navController.currentBackStackEntryAsState()
            MainPager(
                navigator = navigator,
                entry = entry,
                onTop = top?.isMain() ?: true,
                contentPadding = pagePadding,
                openTodaySignal = openTodaySignal,
                todayViewModel = todayViewModel,
            )
        }
    }
}

internal fun NavBackStackEntry.isMain(): Boolean = destination.hasRoute<MainRoute>()
