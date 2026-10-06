package com.cedrickgd.morgenbriefing.ui

import android.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.setSingletonImageLoaderFactory
import com.cedrickgd.morgenbriefing.container
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.ui.glass.AmbientSky
import com.cedrickgd.morgenbriefing.ui.glass.LocalGlassState
import com.cedrickgd.morgenbriefing.ui.nav.AppNavHost
import com.cedrickgd.morgenbriefing.ui.nav.coversSky
import com.cedrickgd.morgenbriefing.ui.nav.hasOwnBottomBar
import com.cedrickgd.morgenbriefing.ui.news.newsImageLoader
import com.cedrickgd.morgenbriefing.ui.shell.ChromeController
import com.cedrickgd.morgenbriefing.ui.shell.FloatingChrome
import com.cedrickgd.morgenbriefing.ui.shell.LocalChromeController
import com.cedrickgd.morgenbriefing.ui.shell.LocalSettingsLink
import com.cedrickgd.morgenbriefing.ui.shell.MainPage
import com.cedrickgd.morgenbriefing.ui.shell.PageRequest
import com.cedrickgd.morgenbriefing.ui.shell.SettingsLink
import com.cedrickgd.morgenbriefing.ui.shell.ShellNavigator
import com.cedrickgd.morgenbriefing.ui.shell.isMain
import com.cedrickgd.morgenbriefing.ui.shell.rememberShellInsets
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import com.cedrickgd.morgenbriefing.ui.today.TodayUiState
import com.cedrickgd.morgenbriefing.ui.today.TodayViewModel
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val TAG = "AppRoot"

/**
 * 3-button navigation scrims for Android 8.0–9 (newer versions draw their own scrim only for the
 * 3-button bar). Warm, a little lighter than the platform default so the sky still shows.
 */
private val LightNavScrim = Color.argb(0xCC, 0xFC, 0xFC, 0xFB)
private val DarkNavScrim = Color.argb(0xB3, 0x15, 0x14, 0x12)

/**
 * Root of the UI (docs/DESIGN.md §1–§3): theme from the stored settings → one blur source →
 * the [AmbientSky] behind everything → the navigation graph (page pager + pushed screens) →
 * the floating glass chrome.
 *
 * @param initialPage the page the pager opens on in a fresh activity (notification links).
 * @param pageRequest a page to show now (notification tap while the app is open); reported
 *   back through [onPageRequestHandled] once it has been acted on.
 * @param onReady called once the stored settings are known, so the splash screen can hide
 *   without flashing the wrong theme.
 */
@Composable
fun AppRoot(
    initialPage: MainPage = MainPage.Start,
    pageRequest: PageRequest? = null,
    onPageRequestHandled: (PageRequest) -> Unit = {},
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
        SystemBarsEffect(darkTheme = darkTheme)
        AppShell(
            initialPage = initialPage,
            pageRequest = pageRequest,
            onPageRequestHandled = onPageRequestHandled,
        )
    }
}

@Composable
private fun AppShell(
    initialPage: MainPage,
    pageRequest: PageRequest?,
    onPageRequestHandled: (PageRequest) -> Unit,
) {
    // One TodayViewModel for the activity: the Today page and the sky share its weather.
    val owner: ViewModelStoreOwner = (LocalActivity.current as? ViewModelStoreOwner)
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "AppRoot needs a ViewModelStoreOwner" }
    val todayViewModel: TodayViewModel = viewModel(viewModelStoreOwner = owner, factory = TodayViewModel.Factory)

    val hazeState = rememberHazeState()
    val chrome = remember { ChromeController() }
    val settingsLink = remember { SettingsLink() }
    val navController = rememberNavController()
    val pagerState = rememberPagerState(initialPage = initialPage.index) { MainPage.entries.size }
    var openTodaySignal by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val navigator = remember(navController, pagerState, chrome, scope) {
        ShellNavigator(navController, pagerState, chrome, scope, onTodayRequested = { openTodaySignal++ })
    }
    val insets = rememberShellInsets()

    val topEntry by navController.currentBackStackEntryAsState()
    val mainOnTop = topEntry?.isMain() ?: true
    val playerAllowed = topEntry?.hasOwnBottomBar() != true
    val readerOnTop = topEntry?.coversSky() == true
    LaunchedEffect(mainOnTop) { if (mainOnTop) chrome.show() }
    // The chrome returns once scrolling has come to rest (docs/DESIGN.md §3).
    LaunchedEffect(chrome) { chrome.watchScrollRest() }

    val latestHandled by rememberUpdatedState(onPageRequestHandled)
    LaunchedEffect(pageRequest) {
        val request = pageRequest ?: return@LaunchedEffect
        navigator.handle(request)
        latestHandled(request)
    }

    val sky by rememberSkyMood(todayViewModel)
    val keyboardOpen by rememberKeyboardOpen()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AmbientSky(
            condition = sky.condition,
            isDay = sky.isDay,
            hazeState = hazeState,
            // Offset from "Heute" in pages; read in the sky's draw phase only.
            parallax = { pagerState.currentPage + pagerState.currentPageOffsetFraction - MainPage.TODAY.index },
            // The aurora holds still while content scrolls or pages glide: every frame goes to them.
            paused = { chrome.scrolling || pagerState.isScrollInProgress },
            // The reader is opaque: nothing of the sky shows, so nothing of it needs to move (and
            // the glass above the article is not re-blurred every frame).
            covered = readerOnTop,
        )
        CompositionLocalProvider(
            LocalGlassState provides hazeState,
            LocalChromeController provides chrome,
            LocalSettingsLink provides settingsLink,
        ) {
            AppNavHost(
                navController = navController,
                navigator = navigator,
                pagePadding = insets.pagePadding,
                pushedPadding = insets.pushedPadding,
                openTodaySignal = openTodaySignal,
                todayViewModel = todayViewModel,
                modifier = Modifier.fillMaxSize(),
            )
            FloatingChrome(
                pagerState = pagerState,
                insets = insets,
                indicatorVisible = mainOnTop && chrome.indicatorVisible && !keyboardOpen,
                playerAllowed = playerAllowed,
                navScrim = mainOnTop,
                onOpenArticle = navigator::openArticle,
                onSelectPage = { index ->
                    val page = MainPage.ofIndex(index)
                    // Tapping the sun while already on "Heute" scrolls it back to the top.
                    val reselected = page == MainPage.TODAY && pagerState.settledPage == index
                    navigator.showPage(page, scrollTodayToTop = reselected)
                },
            )
        }
    }
}

/** What the sky shows; a calm, sunny default until the first weather is known. */
@Immutable
private data class SkyMood(val condition: Condition, val isDay: Boolean) {
    companion object {
        fun of(state: TodayUiState): SkyMood = state.weather
            ?.let { SkyMood(it.condition, it.isDay) }
            ?: SkyMood(Condition.MOSTLY_CLEAR, isDay = state.now.hour in 7..19)
    }
}

@Composable
private fun rememberSkyMood(viewModel: TodayViewModel): State<SkyMood> {
    val moods = remember(viewModel) { viewModel.state.map(SkyMood::of).distinctUntilChanged() }
    return moods.collectAsStateWithLifecycle(initialValue = SkyMood.of(viewModel.state.value))
}

/**
 * True while the keyboard takes up room (the page indicator steps aside then). Derived from the
 * IME inset height – unlike `isImeVisible` it is false before the first insets arrive – and
 * only changes once per open/close, not on every frame of the keyboard animation.
 */
@Composable
private fun rememberKeyboardOpen(): State<Boolean> {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    return remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
}

/**
 * Keeps the system bars in line with the in-app theme (which may differ from the system's):
 * transparent bars over the sky with matching dark/light icons. From Android 10 the system adds
 * its translucent scrim only behind the 3-button bar (never behind the gesture handle); on
 * Android 8–9 a soft warm scrim keeps the buttons readable.
 */
@Composable
private fun SystemBarsEffect(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LightNavScrim, DarkNavScrim) { darkTheme },
        )
        onDispose { }
    }
}
