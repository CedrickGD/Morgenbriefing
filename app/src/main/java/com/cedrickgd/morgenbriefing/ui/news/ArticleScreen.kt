package com.cedrickgd.morgenbriefing.ui.news

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.listen
import com.cedrickgd.morgenbriefing.listen.ListenMiniPlayerDefaults
import com.cedrickgd.morgenbriefing.listen.ListenState
import com.cedrickgd.morgenbriefing.listen.showsMiniPlayer
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.glass.LocalGlassState
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.reader.AskClaudePill
import com.cedrickgd.morgenbriefing.ui.reader.AskPillHeight
import com.cedrickgd.morgenbriefing.ui.reader.ReaderBarHeight
import com.cedrickgd.morgenbriefing.ui.reader.ReaderMessage
import com.cedrickgd.morgenbriefing.ui.reader.ReaderPage
import com.cedrickgd.morgenbriefing.ui.reader.ReaderSkeleton
import com.cedrickgd.morgenbriefing.ui.reader.ReaderTopBar
import com.cedrickgd.morgenbriefing.ui.reader.TextSizePanel
import com.cedrickgd.morgenbriefing.ui.reader.readingProgress
import com.cedrickgd.morgenbriefing.ui.shell.ChromeController
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Distance over which the floating controls melt into the frosted bar. */
private val CollapseSpan = 56.dp

/**
 * The article reader (docs/DESIGN.md §7): every article of the day in a horizontal pager,
 * starting at [articleKey] — the brief's articles, then the day's live articles.
 *
 * A calm, opaque reading surface (no sky behind the text) with the picture edge-to-edge under
 * the status bar. Floating glass controls: back, "Zuhören" (via `context.listen`), text size
 * and share; at the bottom "✨ Frag Claude zu diesem Artikel" → [onAskAboutArticle] (hidden on
 * Android < 8 where there is no AI). The glass blurs the article itself (own haze source).
 *
 * Keeps clear of the status bar, the gesture handle / 3-button bar and the shell's
 * [com.cedrickgd.morgenbriefing.listen.ListenMiniPlayer] (lifts the pill while it shows).
 *
 * @param date `YYYY-MM-DD` of the brief (from the route).
 */
@Composable
fun ArticleScreen(
    date: String,
    articleKey: String,
    onBack: () -> Unit,
    onAskAboutArticle: (date: String, key: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val day = remember(date) { parseDateOrNull(date) }
    val viewModel: ArticleViewModel = viewModel(factory = ArticleViewModel.factory(day, articleKey))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val still = motionDisabled()

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val pageWidth = maxWidth
        val heroHeight = minOf(statusBarTop() + maxWidth * 0.78f, maxHeight * 0.56f)
        val phase = when {
            state.articles.isNotEmpty() -> Phase.READY
            state.loading -> Phase.LOADING
            else -> Phase.FAILED
        }
        Crossfade(targetState = phase, animationSpec = tween(if (still) 0 else 280), label = "readerPhase") { shown ->
            when (shown) {
                // The outgoing side of the cross-fade sees the newest state: never page through nothing.
                Phase.READY -> if (state.articles.isNotEmpty()) {
                    ReaderPager(
                        state = state,
                        date = date,
                        openedKey = articleKey,
                        pageWidth = pageWidth,
                        heroHeight = heroHeight,
                        onBack = onBack,
                        onAskAboutArticle = onAskAboutArticle,
                        onTextScale = viewModel::changeTextScale,
                    )
                }

                // No picture to blur here: the back button uses the frosted fallback.
                Phase.LOADING -> CompositionLocalProvider(LocalGlassState provides null) {
                    ReaderSkeleton(heroHeight = heroHeight, onBack = onBack)
                }

                Phase.FAILED -> CompositionLocalProvider(LocalGlassState provides null) {
                    ReaderFailure(missingDay = day == null, onBack = onBack, onRetry = viewModel::reload)
                }
            }
        }
    }
}

@Composable
private fun ReaderFailure(missingDay: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    if (missingDay) {
        ReaderMessage(
            emoji = "🤔",
            title = "Diesen Artikel gibt es nicht",
            text = "Der Link zeigt auf kein gültiges Briefing. Geh zurück und wähl einen Artikel aus.",
            actionLabel = "Zurück",
            onAction = onBack,
            onBack = onBack,
        )
    } else {
        ReaderMessage(
            emoji = "🗞️",
            title = "Artikel gerade nicht da",
            text = "Das Briefing dieses Tages ist gerade nicht erreichbar. Check kurz deine Verbindung.",
            actionLabel = "Nochmal versuchen",
            onAction = onRetry,
            onBack = onBack,
        )
    }
}

private enum class Phase { LOADING, READY, FAILED }

@Composable
private fun ReaderPager(
    state: ArticleUiState,
    date: String,
    openedKey: String,
    pageWidth: Dp,
    heroHeight: Dp,
    onBack: () -> Unit,
    onAskAboutArticle: (date: String, key: String) -> Unit,
    onTextScale: (Boolean) -> Unit,
) {
    val articles = state.articles
    val context = LocalContext.current
    val links = rememberLinkActions()
    val scope = rememberCoroutineScope()
    val still = motionDisabled()
    val haptics = LocalHapticFeedback.current
    val listen = remember(context) { context.listen }
    // Only what the reader shows — progress ticks (several a second) must not recompose it.
    val listenUi by remember(listen) {
        listen.state.map(::ReaderListenUi).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = ReaderListenUi(listen.state.value))
    val pagerState = rememberPagerState(initialPage = state.startIndex) { articles.size }
    val listStates = rememberPageListStates(articles.size)
    // Local chrome state: the "Frag Claude" pill hides while reading down, returns on the way up.
    val askChrome = remember { ChromeController() }
    var textPanelOpen by rememberSaveable { mutableStateOf(false) }

    // Stay on the same article if the list is re-arranged meanwhile (topic settings changed).
    var currentKey by rememberSaveable { mutableStateOf(openedKey) }
    val latestArticles by rememberUpdatedState(articles)
    LaunchedEffect(pagerState) {
        var first = true
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestArticles.getOrNull(page)?.let { currentKey = it.item.key }
            if (!first) {
                askChrome.show()
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            first = false
        }
    }
    LaunchedEffect(articles) {
        val index = articles.indexOfFirst { it.item.key == currentKey }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    val statusTop = statusBarTop()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val askEnabled = AiSupport.isSupported
    val playerLift = if (listenUi.playerVisible) ListenMiniPlayerDefaults.Height + ListenMiniPlayerDefaults.Gap else 0.dp
    // While the mini player shows, "Frag Claude" moves into the top capsule: one floating layer
    // at the bottom, not two stacked over the text.
    val askPillShown = askEnabled && !listenUi.playerVisible
    val bottomPadding = navBottom + 32.dp + playerLift + (if (askPillShown) AskPillHeight + 16.dp else 0.dp)

    val current = pagerState.currentPage.coerceIn(0, articles.lastIndex)
    val currentEntry = articles[current]
    val currentList = listStates[current]

    val density = LocalDensity.current
    val collapseEndPx = with(density) { (heroHeight - statusTop - ReaderBarHeight).toPx() }
    val collapseSpanPx = with(density) { CollapseSpan.toPx() }
    // Lambdas read scroll state in the draw/layer phase only.
    val collapse: () -> Float = remember(pagerState, listStates, collapseEndPx, collapseSpanPx) {
        {
            val list = listStates[pagerState.currentPage.coerceIn(0, listStates.lastIndex)]
            val scrolled = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else Float.MAX_VALUE
            ((scrolled - (collapseEndPx - collapseSpanPx)) / collapseSpanPx).coerceIn(0f, 1f)
        }
    }
    val progress: () -> Float = remember(pagerState, listStates) {
        { listStates[pagerState.currentPage.coerceIn(0, listStates.lastIndex)].readProgress() }
    }
    val collapsed by remember(collapse) { derivedStateOf { collapse() > 0.5f } }
    val atEnd by remember(currentList) {
        derivedStateOf { currentList.layoutInfo.totalItemsCount > 0 && !currentList.canScrollForward }
    }

    ReaderStatusBarIcons(lightIcons = !collapsed)

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                // The article (on its calm opaque surface) is what the floating glass blurs:
                // the top bar, "Frag Claude" and the shell's mini player.
                .glassContent()
                .background(MaterialTheme.colorScheme.background),
            beyondViewportPageCount = 1,
        ) { page ->
            ReaderPage(
                entry = articles[page],
                next = articles.getOrNull(page + 1),
                position = page + 1,
                total = articles.size,
                listState = listStates[page],
                heroHeight = heroHeight,
                pageWidth = pageWidth,
                pageOffset = { pagerState.getOffsetDistanceInPages(page) },
                textScale = state.textScale,
                now = state.now,
                bottomPadding = bottomPadding,
                onOpenLink = links::open,
                onOpenNext = {
                    scope.launch {
                        if (still) pagerState.scrollToPage(page + 1) else pagerState.animateScrollToPage(page + 1)
                    }
                },
                onBack = onBack,
                modifier = Modifier.nestedScroll(askChrome.nestedScrollConnection),
            )
        }

        // Text softly fading out towards the floating controls at the bottom, so no letters
        // peek out around the pill or the mini player.
        BottomFade(height = navBottom + playerLift + (if (askPillShown) AskPillHeight + 16.dp else 0.dp) + 36.dp)

        // Every glass piece above the article blurs the article itself.
        run {
            if (askEnabled) {
                AskClaudePill(
                    visible = askPillShown && (askChrome.indicatorVisible || atEnd) && !textPanelOpen,
                    bottomInset = navBottom,
                    lift = playerLift,
                    onClick = { onAskAboutArticle(date, currentEntry.item.key) },
                )
            }
            ReaderTopBar(
                collapse = collapse,
                collapsed = collapsed,
                readingProgress = progress,
                listening = listenUi.activeKey == currentEntry.item.key,
                speaking = listenUi.speaking && listenUi.activeKey == currentEntry.item.key,
                compact = pageWidth < 360.dp,
                onBack = onBack,
                onListen = {
                    // Reading this article → stop; paused on it → continue; otherwise → read it.
                    val item = currentEntry.item
                    val now = listen.state.value
                    when {
                        now.speaking && now.articleKey == item.key -> listen.stop()
                        else -> listen.play(item, date)
                    }
                },
                onTextSize = { textPanelOpen = !textPanelOpen },
                onShare = { links.shareArticle(currentEntry.item) },
                onAsk = if (askEnabled && !askPillShown) {
                    { onAskAboutArticle(date, currentEntry.item.key) }
                } else {
                    null
                },
            )
            TextSizePanel(
                open = textPanelOpen,
                scale = state.textScale,
                onChange = onTextScale,
                onDismiss = { textPanelOpen = false },
            )
        }
    }
}

/** The part of [ListenState] the reader shows (mini player room, the capsule's state). */
private data class ReaderListenUi(val playerVisible: Boolean, val activeKey: String?, val speaking: Boolean) {
    constructor(state: ListenState) : this(
        playerVisible = state.showsMiniPlayer(),
        activeKey = state.articleKey.takeIf { state.active },
        speaking = state.speaking,
    )
}

/** Reading progress of this page's list, 0..1 (see [readingProgress]). Draw phase only. */
private fun LazyListState.readProgress(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    var sum = 0
    for (i in visible.indices) sum += visible[i].size
    return readingProgress(
        firstIndex = firstVisibleItemIndex,
        firstOffset = firstVisibleItemScrollOffset,
        totalItems = info.totalItemsCount,
        averageItemSize = sum.toFloat() / visible.size,
        viewport = info.viewportEndOffset - info.viewportStartOffset,
        canScrollForward = canScrollForward,
    )
}

/** One scroll position per page, kept across configuration changes. */
@Composable
private fun rememberPageListStates(count: Int): List<LazyListState> = rememberSaveable(
    count,
    saver = listSaver(
        save = { states -> states.flatMap { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) } },
        restore = { saved ->
            saved.chunked(2)
                .map { (index, offset) -> LazyListState(index, offset) }
                .takeIf { it.size == count }
        },
    ),
) { List(count) { LazyListState() } }

/**
 * Light status bar icons while the picture (with its dark top scrim) is behind them; the
 * theme's own choice once the frosted bar is there. Restores the theme's choice on leave.
 */
@Composable
private fun ReaderStatusBarIcons(lightIcons: Boolean) {
    val activity = LocalActivity.current
    val view = LocalView.current
    val darkTheme = isDarkTheme()
    DisposableEffect(activity, view, lightIcons, darkTheme) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !lightIcons && !darkTheme
        onDispose { controller?.isAppearanceLightStatusBars = !darkTheme }
    }
}

/**
 * A soft fade from transparent into the reading surface at the bottom of the screen, behind the
 * floating controls ("Frag Claude", the mini player): text glides out instead of peeking out
 * around their rounded ends.
 */
@Composable
private fun BoxScope.BottomFade(height: Dp) {
    val surface = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0f),
                        0.35f to surface.copy(alpha = 0.75f),
                        1f to surface.copy(alpha = 0.92f),
                    ),
                )
            },
    )
}
