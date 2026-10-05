package com.cedrickgd.morgenbriefing.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cedrickgd.morgenbriefing.ui.brief.DayDetailScreen
import com.cedrickgd.morgenbriefing.ui.history.HistoryScreen
import com.cedrickgd.morgenbriefing.ui.news.ArticleScreen
import com.cedrickgd.morgenbriefing.ui.settings.SettingsScreen
import com.cedrickgd.morgenbriefing.ui.today.TodayScreen

private const val TAB_IN_MS = 220
private const val TAB_IN_DELAY_MS = 70
private const val TAB_OUT_MS = 90
private const val DETAIL_MS = 320

/**
 * The app's navigation graph. Tabs cross-fade ("fade through"); the day detail and the article
 * reader slide in from the side over a slightly receding screen. Pop transitions are what
 * predictive back scrubs through while the user swipes.
 *
 * @param contentPadding the Scaffold's inner padding (bottom = navigation bar height).
 * @param openTodaySignal increments whenever a notification tap asks for the Today tab.
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    openTodaySignal: Int,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TodayRoute,
        modifier = modifier,
        enterTransition = { tabEnter() },
        exitTransition = { tabExit() },
        popEnterTransition = { tabEnter() },
        popExitTransition = { tabExit() },
    ) {
        composable<TodayRoute>(
            exitTransition = { if (targetState.isDetail()) detailUnderlayExit() else tabExit() },
            popEnterTransition = { if (initialState.isDetail()) detailUnderlayEnter() else tabEnter() },
        ) { entry ->
            TodayScreen(
                contentPadding = contentPadding,
                openTodaySignal = openTodaySignal,
                onOpenSettings = { navController.navigateToTopLevel(SettingsRoute) },
                onOpenArticle = { date, key ->
                    // Ignore double taps while the transition to the first article is running.
                    if (entry.isResumed()) navController.navigate(ArticleRoute(date, key))
                },
            )
        }

        composable<HistoryRoute>(
            exitTransition = { if (targetState.isDetail()) detailUnderlayExit() else tabExit() },
            popEnterTransition = { if (initialState.isDetail()) detailUnderlayEnter() else tabEnter() },
        ) { entry ->
            HistoryScreen(
                contentPadding = contentPadding,
                onOpenDay = { date ->
                    // Ignore double taps while the transition to the first detail is running.
                    if (entry.isResumed()) navController.navigate(DayRoute(date.toString()))
                },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(contentPadding = contentPadding)
        }

        composable<DayRoute>(
            enterTransition = { detailEnter() },
            exitTransition = { if (targetState.isDetail()) detailUnderlayExit() else fadeOut(tween(TAB_OUT_MS)) },
            popEnterTransition = { if (initialState.isDetail()) detailUnderlayEnter() else fadeIn(tween(TAB_IN_MS)) },
            popExitTransition = { detailPopExit() },
        ) { entry ->
            val route = entry.toRoute<DayRoute>()
            DayDetailScreen(
                date = route.date,
                contentPadding = contentPadding,
                onBack = { if (entry.isResumed()) navController.popBackStack() },
                onOpenArticle = { date, key ->
                    if (entry.isResumed()) navController.navigate(ArticleRoute(date, key))
                },
            )
        }

        composable<ArticleRoute>(
            enterTransition = { detailEnter() },
            exitTransition = { fadeOut(tween(TAB_OUT_MS)) },
            popEnterTransition = { fadeIn(tween(TAB_IN_MS)) },
            popExitTransition = { detailPopExit() },
        ) { entry ->
            val route = entry.toRoute<ArticleRoute>()
            ArticleScreen(
                date = route.date,
                articleKey = route.key,
                onBack = { if (entry.isResumed()) navController.popBackStack() },
            )
        }
    }
}

/** Screens that slide in over the current one (and hide the bottom bar). */
internal fun NavBackStackEntry.isDetail(): Boolean =
    destination.hasRoute<DayRoute>() || destination.hasRoute<ArticleRoute>()

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState == Lifecycle.State.RESUMED

private fun tabEnter(): EnterTransition =
    fadeIn(tween(TAB_IN_MS, delayMillis = TAB_IN_DELAY_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(TAB_IN_MS, delayMillis = TAB_IN_DELAY_MS, easing = LinearOutSlowInEasing), initialScale = 0.97f)

private fun tabExit(): ExitTransition = fadeOut(tween(TAB_OUT_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.Start, tween(DETAIL_MS, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeIn(tween(DETAIL_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailPopExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.End, tween(DETAIL_MS, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeOut(tween(DETAIL_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailUnderlayExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.Start, tween(DETAIL_MS, easing = FastOutSlowInEasing)) { it / 10 } +
        fadeOut(tween(DETAIL_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailUnderlayEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.End, tween(DETAIL_MS, easing = FastOutSlowInEasing)) { it / 10 } +
        fadeIn(tween(DETAIL_MS))
