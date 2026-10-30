package com.cedrickgd.morgenbriefing.ui.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.toRoute
import com.cedrickgd.morgenbriefing.ui.article.ArticleScreen
import com.cedrickgd.morgenbriefing.ui.shell.MainPager
import com.cedrickgd.morgenbriefing.ui.shell.ShellNavigator
import com.cedrickgd.morgenbriefing.ui.today.TodayViewModel

/**
 * The navigation graph: [MainRoute] (the page pager) is the start destination; the article
 * reader ([ArticleRoute]) is pushed over it and covers the shared sky.
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
        composable<ArticleRoute>(
            enterTransition = { slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it / 3 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { it / 3 } + fadeOut() },
        ) { entry ->
            val route = entry.toRoute<ArticleRoute>()
            ArticleScreen(route = route, onBack = { navController.popBackStack() })
        }
    }
}

internal fun NavBackStackEntry.isMain(): Boolean = destination.hasRoute<MainRoute>()

/** The screen on top hides the sky (its own opaque surface): the article reader. */
internal fun NavBackStackEntry.coversSky(): Boolean = destination.hasRoute<ArticleRoute>()
