package com.cedrickgd.morgenbriefing.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.cedrickgd.morgenbriefing.ui.brief.DayDetailScreen
import com.cedrickgd.morgenbriefing.ui.chat.ChatScreen
import com.cedrickgd.morgenbriefing.ui.news.ArticleScreen
import com.cedrickgd.morgenbriefing.ui.shell.MainPage
import com.cedrickgd.morgenbriefing.ui.shell.MainPager
import com.cedrickgd.morgenbriefing.ui.shell.ShellNavigator
import com.cedrickgd.morgenbriefing.ui.shell.isMain
import com.cedrickgd.morgenbriefing.ui.today.TodayViewModel

/**
 * The navigation graph: [MainRoute] (the page pager) is the start destination and never left;
 * the day detail, the article reader and the chat are pushed over it. Every screen is
 * transparent over the shared sky, so a push slides the new screen in while the one beneath
 * recedes and fades – the sky itself never moves. Pop transitions are what predictive back
 * scrubs through while the user swipes.
 *
 * @param pagePadding bottom room for the floating chrome, for the pager pages.
 * @param pushedPadding bottom room on pushed screens (navigation bar + mini player).
 * @param openTodaySignal increments whenever "Heute" should scroll back to the top.
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    navigator: ShellNavigator,
    pagePadding: PaddingValues,
    pushedPadding: PaddingValues,
    openTodaySignal: Int,
    todayViewModel: TodayViewModel,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = MainRoute,
        modifier = modifier,
        enterTransition = { pushEnter() },
        exitTransition = { underlayExit() },
        popEnterTransition = { underlayReturn() },
        popExitTransition = { popExit() },
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

        composable<DayRoute> { entry ->
            val route = entry.toRoute<DayRoute>()
            DayDetailScreen(
                date = route.date,
                contentPadding = pushedPadding,
                onBack = { navigator.back(entry) },
                onOpenArticle = { date, key -> navigator.push(entry, ArticleRoute(date, key)) },
            )
        }

        composable<ArticleRoute> { entry ->
            val route = entry.toRoute<ArticleRoute>()
            ArticleScreen(
                date = route.date,
                articleKey = route.key,
                onBack = { navigator.back(entry) },
                onAskAboutArticle = { date, key -> navigator.push(entry, ChatRoute(date, key)) },
            )
        }

        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatScreen(
                date = route.date,
                articleKey = route.key,
                onBack = { navigator.back(entry) },
                onOpenSettings = { navigator.showPage(MainPage.SETTINGS) },
            )
        }
    }
}

/** Screens with their own bottom controls, where the floating mini player would be in the way. */
internal fun NavBackStackEntry.hasOwnBottomBar(): Boolean = destination.hasRoute<ChatRoute>()

private const val PUSH_MS = 420
private const val POP_MS = 340
private const val UNDERLAY_FADE_MS = 180

/** Material 3 "emphasized" curves: quick start, long gentle landing. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** A pushed screen glides in from the right (a fifth of the width) and fades up. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.Start, tween(PUSH_MS, easing = EmphasizedDecelerate)) { it / 5 } +
        fadeIn(tween(PUSH_MS / 2, delayMillis = 40, easing = Standard))

/** The screen underneath sinks back a little and fades into the sky. */
private fun underlayExit(): ExitTransition =
    fadeOut(tween(UNDERLAY_FADE_MS, easing = Standard)) +
        scaleOut(tween(PUSH_MS, easing = EmphasizedDecelerate), targetScale = 0.94f)

/** …and rises again when the pushed screen is closed. */
private fun underlayReturn(): EnterTransition =
    fadeIn(tween(POP_MS, delayMillis = 40, easing = Standard)) +
        scaleIn(tween(POP_MS, easing = Standard), initialScale = 0.94f)

/** The closing screen slides back out to the right while it fades. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.End, tween(POP_MS, easing = EmphasizedAccelerate)) { it / 5 } +
        fadeOut(tween(POP_MS, easing = Standard))
