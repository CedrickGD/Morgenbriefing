package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.WeatherCard
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.brief.WeatherUi
import com.cedrickgd.morgenbriefing.ui.components.ClothingCard
import com.cedrickgd.morgenbriefing.ui.components.DayCurveCard
import com.cedrickgd.morgenbriefing.ui.components.GlassSnackbarHost
import com.cedrickgd.morgenbriefing.ui.components.HomeWeatherHero
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.components.NewsSectionTitle
import com.cedrickgd.morgenbriefing.ui.components.NoticeBanner
import com.cedrickgd.morgenbriefing.ui.components.PageFooter
import com.cedrickgd.morgenbriefing.ui.components.TodayHeader
import com.cedrickgd.morgenbriefing.ui.components.WarningBanner
import com.cedrickgd.morgenbriefing.ui.glass.TopGlassBar
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.news.ChipRowHeight
import com.cedrickgd.morgenbriefing.ui.news.EmptySectionNote
import com.cedrickgd.morgenbriefing.ui.news.FeedActions
import com.cedrickgd.morgenbriefing.ui.news.FeedArticle
import com.cedrickgd.morgenbriefing.ui.news.FeedChipRow
import com.cedrickgd.morgenbriefing.ui.news.FeedFilter
import com.cedrickgd.morgenbriefing.ui.news.FeedGutter
import com.cedrickgd.morgenbriefing.ui.news.FeedViewIntro
import com.cedrickgd.morgenbriefing.ui.news.ListEndNote
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.NowListening
import com.cedrickgd.morgenbriefing.ui.news.TopicsSheet
import com.cedrickgd.morgenbriefing.ui.news.articleCountLabel
import com.cedrickgd.morgenbriefing.ui.news.articleItems
import com.cedrickgd.morgenbriefing.ui.news.chipsParked
import com.cedrickgd.morgenbriefing.ui.news.feedChips
import com.cedrickgd.morgenbriefing.ui.news.fetchedLabel
import com.cedrickgd.morgenbriefing.ui.news.forYouItems
import com.cedrickgd.morgenbriefing.ui.news.rememberFeedActions
import com.cedrickgd.morgenbriefing.ui.news.rememberListenController
import com.cedrickgd.morgenbriefing.ui.news.rememberListeningKey
import com.cedrickgd.morgenbriefing.ui.news.resolveFilter
import com.cedrickgd.morgenbriefing.ui.news.revealedCount
import com.cedrickgd.morgenbriefing.ui.news.say
import com.cedrickgd.morgenbriefing.ui.news.sectionFor
import com.cedrickgd.morgenbriefing.ui.onboarding.OnboardingSheet
import com.cedrickgd.morgenbriefing.ui.settings.TopicList
import com.cedrickgd.morgenbriefing.ui.shell.LocalSettingsLink
import com.cedrickgd.morgenbriefing.ui.shell.SettingsTarget
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.KeepListEndAboveChrome
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.pageSideInset
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Key of the chip row in the list. */
private const val CHIPS_KEY = "today-chips"

/** Height of the TopGlassBar below the status bar (docs/DESIGN.md §4). */
private val TopBarHeight = 52.dp

/** Scrolled this far into the header, the TopGlassBar slides in. */
private val HeaderGoneAfter = 96.dp

/**
 * Page "Heute" (docs/DESIGN.md §7): transparent over the AmbientSky. Header directly on the
 * sky, the weather as glass cards, then the news: title, the chip row ("Für dich", "Neueste",
 * topics) — which the frosted top bar takes in once it reaches it — and the editorial feed.
 * A topic chip scrolls endlessly through everything its sources delivered.
 *
 * @param contentPadding top is 0 (the page handles the status bar); bottom leaves room for the
 *   floating chrome and the navigation bar.
 * @param openTodaySignal changes on a notification tap — scrolls back to the top.
 */
@Composable
fun TodayScreen(
    contentPadding: PaddingValues,
    openTodaySignal: Int,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // After process death the saved position points into the feed; measuring the header and the
    // loading skeleton first would clamp it to their few items. Until weather and news are known
    // the page runs on a list state of its own; the saved one is attached with the real content.
    val loadingListState = remember { LazyListState() }
    val contentKnownNow = state.feed != null && (state.weather != null || !state.weatherLoading)
    var contentKnown by remember { mutableStateOf(contentKnownNow) }
    LaunchedEffect(contentKnownNow) { if (contentKnownNow) contentKnown = true }
    val settingsLink = LocalSettingsLink.current

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    // Saved with the page state, so coming back does not count as a new notification tap.
    var handledSignal by rememberSaveable { mutableIntStateOf(openTodaySignal) }
    LaunchedEffect(openTodaySignal) {
        if (openTodaySignal != handledSignal) {
            handledSignal = openTodaySignal
            listState.animateScrollToItem(0)
        }
    }

    val openSettingsAt: (SettingsTarget) -> Unit = { target ->
        settingsLink.request(target)
        onOpenSettings()
    }

    val callbacks = TodayCallbacks(
        onRefresh = viewModel::refresh,
        onRetryWeather = viewModel::retryWeather,
        onRetryNews = viewModel::retryNews,
        onOpenSettingsAt = openSettingsAt,
        onSetTopicVisible = viewModel::setTopicVisible,
        onSetTopicOrder = viewModel::setTopicOrder,
        onSetSourceEnabled = viewModel::setSourceEnabled,
    )

    TodayContent(
        state = state,
        listState = if (contentKnown) listState else loadingListState,
        contentPadding = contentPadding,
        callbacks = callbacks,
        modifier = modifier,
    )

    if (onboarding) {
        OnboardingSheet(
            onDone = viewModel::finishOnboarding,
            onChoosePlace = {
                viewModel.finishOnboarding()
                openSettingsAt(SettingsTarget.HOME_PLACE)
            },
        )
    }
}

/** Everything the page can ask for, bundled to keep the content function readable. */
private class TodayCallbacks(
    val onRefresh: () -> Unit,
    val onRetryWeather: () -> Unit,
    val onRetryNews: () -> Unit,
    val onOpenSettingsAt: (SettingsTarget) -> Unit,
    val onSetTopicVisible: (String, Boolean) -> Unit,
    val onSetTopicOrder: (List<String>) -> Unit,
    val onSetSourceEnabled: (String, Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayContent(
    state: TodayUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    callbacks: TodayCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val statusTop = statusBarTop()
    val barHeightPx = with(density) { (statusTop + TopBarHeight).roundToPx() }
    val headerGonePx = with(density) { HeaderGoneAfter.roundToPx() }
    val bottomInset = bottomContentInset(contentPadding)
    val pullState = rememberPullToRefreshState()
    // Landscape and tablets: the feed stays a column, pictures do not grow with the window.
    val pageSide = pageSideInset()
    val snackbar = remember { SnackbarHostState() }

    val weather = state.weather
    val today = state.today
    val now = state.now
    val prefs = state.prefs
    val feed = state.feed
    val chips = remember(feed) { feedChips(feed) }
    var selectedKey by rememberSaveable { mutableStateOf(FeedFilter.ForYou.key) }
    val selected = remember(selectedKey, chips) { resolveFilter(FeedFilter.fromKey(selectedKey), chips) }
    val listening by rememberListeningKey(rememberListenController())
    var showTopics by rememberSaveable { mutableStateOf(false) }
    val showImages = prefs?.showImages ?: true

    // The chip row: in the list until it reaches the top bar, then carried by the bar.
    val chipsShown = feed != null && feed.sections.isNotEmpty()
    val chipIndex = if (chipsShown) chipRowIndex(weather, state.weatherCards) else -1
    val chipRowState = rememberLazyListState()
    val chipsParked by remember(listState, barHeightPx, chipIndex) {
        derivedStateOf { chipsParked(listState, chipIndex, barHeightPx) }
    }
    val parked = chipsShown && chipsParked

    val hideTopic: (Topic) -> Unit = { topic ->
        val catalogue = prefs?.catalogue?.map { it.id }.orEmpty()
        if (prefs == null || !TopicList.canHide(catalogue, prefs.hidden, topic.id)) {
            snackbar.say(scope, "Ein Thema bleibt immer sichtbar.")
        } else {
            callbacks.onSetTopicVisible(topic.id, false)
            snackbar.say(scope, "„${topic.title}“ ausgeblendet", action = "Rückgängig") {
                callbacks.onSetTopicVisible(topic.id, true)
            }
        }
    }
    val hideSource: (FeedArticle) -> Unit = { article ->
        callbacks.onSetSourceEnabled(article.item.sourceId, false)
        snackbar.say(scope, "„${article.item.source}“ ausgeblendet – Einstellungen → Quellen", action = "Rückgängig") {
            callbacks.onSetSourceEnabled(article.item.sourceId, true)
        }
    }
    val actions = rememberFeedActions(
        onMessage = { snackbar.say(scope, it) },
        onHideTopic = { hideTopic(it.topic) },
        onHideSource = hideSource,
    )
    val onSelect: (FeedFilter) -> Unit = { filter ->
        // Always remember the tap: after a hidden topic fell back to "Für dich", the stored key
        // still names that topic, and tapping "Für dich" must replace it.
        selectedKey = filter.key
        if (filter != selected) {
            // Switching while the chips ride in the bar: start the new view right below them.
            if (parked && chipIndex >= 0) {
                scope.launch {
                    listState.scrollToItem(chipIndex)
                    listState.scrollBy(-barHeightPx.toFloat())
                }
            }
        }
    }
    val chipRow: @Composable (onGlass: Boolean) -> Unit = { onGlass ->
        FeedChipRow(
            chips = chips,
            selected = selected,
            onSelect = onSelect,
            onOpenTopics = { showTopics = true },
            rowState = chipRowState,
            onGlass = onGlass,
        )
    }

    // Endless lists ("Neueste", a topic): reveal more whenever the end comes into view.
    var pages by rememberSaveable(selectedKey) { mutableIntStateOf(1) }
    val total = when (selected) {
        FeedFilter.Latest -> feed?.latest?.size ?: 0
        is FeedFilter.OneTopic -> feed?.sectionFor(selected.topicId)?.count ?: 0
        FeedFilter.ForYou -> 0
    }
    val revealed = revealedCount(pages, total)
    LaunchedEffect(listState, revealed, total) {
        if (revealed >= total) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.filter { (last, count) -> count > 0 && last >= count - REVEAL_AHEAD }.first()
        pages++
    }

    val greeting = remember(now) { Fmt.greeting(now) }
    val showSkeleton = weather == null && state.weatherLoading
    val headline = weather?.headline?.takeIf { it.isNotBlank() } ?: if (state.weatherLoading) {
        "Einen Moment – ich schau kurz nach draußen."
    } else {
        "Schön, dass du da bist."
    }
    val notice = remember(feed, state.offline, state.newsLoading, state.newsWarmed, state.newsAttempted) {
        newsNotice(feed, state.offline, state.newsLoading, state.newsWarmed, state.newsAttempted)
    }

    KeepListEndAboveChrome(listState, bottomInset)

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = callbacks.onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusTop),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .reportChromeScroll()
                    // The floating glass above (bar, indicator, player) blurs this content.
                    .glassContent()
                    .horizontalSafeDrawing(),
                contentPadding = PaddingValues(start = pageSide, end = pageSide, bottom = bottomInset + 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header", contentType = "header") {
                    TodayHeader(
                        date = today,
                        greeting = greeting,
                        headline = headline,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = statusTop + 12.dp, bottom = 4.dp),
                    )
                }

                when {
                    weather != null -> weatherItems(weather, state.weatherCards)
                    showSkeleton -> item(key = "skeleton", contentType = "skeleton") {
                        LoadingSkeleton(Modifier.animateItem().padding(horizontal = FeedGutter))
                    }
                    else -> item(key = "weather-error", contentType = "info") {
                        InfoCard(
                            emoji = "🌦️",
                            title = "Wetter gerade nicht erreichbar",
                            text = "Check kurz deine Verbindung – dann hol ich das Wetter nochmal.",
                            modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                            actionLabel = "Nochmal versuchen",
                            onAction = callbacks.onRetryWeather,
                        )
                    }
                }

                if (feed == null) {
                    if (!showSkeleton) item(key = "news-loading", contentType = "loading") { NewsLoadingRow() }
                } else {
                    item(key = "news-title", contentType = "news-title") {
                        NewsSectionTitle(
                            stand = fetchedLabel(feed.fetchedAt),
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = FeedGutter)
                                .padding(top = 20.dp),
                        )
                    }
                    if (chipsShown) {
                        item(key = CHIPS_KEY, contentType = "chips") {
                            // Parked: the bar shows the row; this spot keeps its room in the list.
                            if (parked) Spacer(Modifier.height(ChipRowHeight)) else chipRow(false)
                        }
                    }
                    notice?.let { n ->
                        item(key = "news-notice", contentType = "notice") {
                            NoticeBanner(
                                emoji = n.emoji,
                                title = n.title,
                                text = n.text,
                                actionLabel = n.actionLabel.takeIf { n.action != null },
                                onAction = n.action?.let { act -> { noticeAction(act, callbacks, onTopics = { showTopics = true }) } },
                                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                            )
                        }
                    }
                    if (chipsShown) {
                        feedItems(
                            selected = selected,
                            feed = feed,
                            loadingTopics = state.loadingTopics,
                            now = now,
                            listening = listening,
                            actions = actions,
                            showImages = showImages,
                            revealed = revealed,
                            onShowAll = { section -> onSelect(FeedFilter.OneTopic(section.id)) },
                        )
                    }
                }

                if (weather != null || feed != null) {
                    item(key = "footer", contentType = "footer") {
                        PageFooter(
                            line = footerLine(feed, hasWeather = weather != null, weatherOffline = state.weatherFailed),
                            modifier = Modifier.padding(horizontal = FeedGutter),
                        )
                    }
                }
            }
        }

        val headerGone by remember(listState) {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > headerGonePx }
        }
        TopGlassBar(
            title = "Heute · ${Fmt.shortDate(today)}",
            visible = headerGone || parked,
            onTap = {
                scope.launch { if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0) }
            },
            bottom = if (parked) {
                {
                    Column(Modifier.horizontalSafeDrawing().padding(horizontal = pageSide)) {
                        chipRow(true)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            } else {
                null
            },
        )

        GlassSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 8.dp),
        )
    }

    if (showTopics && prefs != null) {
        TopicsSheet(
            prefs = prefs,
            counts = remember(feed) { feed?.sections?.associate { it.id to it.count }.orEmpty() },
            onSetVisible = { id, visible -> callbacks.onSetTopicVisible(id, visible) },
            onSetOrder = callbacks.onSetTopicOrder,
            onOpenSettings = {
                showTopics = false
                callbacks.onOpenSettingsAt(SettingsTarget.SOURCES)
            },
            onDismiss = { showTopics = false },
        )
    }
}

/** How close to the end (in items) the next page of an endless list is revealed. */
private const val REVEAL_AHEAD = 4

/**
 * List index of the chip row: header, the weather items (or their skeleton / error card) and the
 * news title come before it. Must mirror the item order of [TodayContent].
 */
private fun chipRowIndex(weather: WeatherUi?, cards: Set<WeatherCard>): Int {
    val weatherItems = when {
        weather != null -> 1 +
            (if (WeatherCard.WARNINGS in cards && weather.warnings.isNotEmpty()) 1 else 0) +
            (if (WeatherCard.CURVE in cards) 1 else 0) +
            (if (WeatherCard.CLOTHING in cards && weather.clothing != null) 1 else 0)
        else -> 1 // skeleton or error card
    }
    return 1 + weatherItems + 1
}

/** Runs a notice's button. */
private fun noticeAction(action: NoticeAction, callbacks: TodayCallbacks, onTopics: () -> Unit) {
    when (action) {
        NoticeAction.RETRY -> callbacks.onRetryNews()
        NoticeAction.OPEN_TOPICS -> onTopics()
        NoticeAction.OPEN_SOURCES -> callbacks.onOpenSettingsAt(SettingsTarget.SOURCES)
    }
}

private fun LazyListScope.weatherItems(weather: WeatherUi, cards: Set<WeatherCard>) {
    item(key = "hero", contentType = "hero") {
        HomeWeatherHero(snapshot = weather.snapshot, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
    }
    if (WeatherCard.WARNINGS in cards && weather.warnings.isNotEmpty()) {
        item(key = "warnings", contentType = "warnings") {
            WarningBanner(warnings = weather.warnings, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
        }
    }
    if (WeatherCard.CURVE in cards) {
        item(key = "curve", contentType = "curve") {
            DayCurveCard(snapshot = weather.snapshot, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
        }
    }
    if (WeatherCard.CLOTHING in cards) {
        weather.clothing?.let { advice ->
            item(key = "clothing", contentType = "clothing") {
                ClothingCard(advice = advice, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
            }
        }
    }
}

/** The feed below the chips, depending on the selected chip. */
private fun LazyListScope.feedItems(
    selected: FeedFilter,
    feed: NewsFeed,
    loadingTopics: Set<String>,
    now: ZonedDateTime,
    listening: NowListening?,
    actions: FeedActions,
    showImages: Boolean,
    revealed: Int,
    onShowAll: (section: com.cedrickgd.morgenbriefing.ui.news.SectionUi) -> Unit,
) {
    when (selected) {
        FeedFilter.ForYou -> forYouItems(
            sections = feed.sections,
            loadingTopics = loadingTopics,
            now = now,
            listening = listening,
            actions = actions,
            showImages = showImages,
            onShowAll = onShowAll,
            keyPrefix = "foryou",
        )

        FeedFilter.Latest -> {
            item(key = "latest-intro", contentType = "feed-intro") {
                FeedViewIntro(
                    overline = "Alle Themen",
                    title = "Das Neueste zuerst",
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter).padding(top = 4.dp),
                )
            }
            if (feed.latest.isEmpty()) {
                item(key = "latest-empty", contentType = "feed-empty") {
                    EmptySectionNote(
                        text = if (loadingTopics.isNotEmpty()) "Hole die Meldungen …" else "Noch keine Meldungen geladen.",
                        loading = loadingTopics.isNotEmpty(),
                        modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                    )
                }
            } else {
                articleItems(
                    articles = feed.latest.take(revealed),
                    now = now,
                    listening = listening,
                    actions = actions,
                    showImages = showImages,
                    keyPrefix = "latest",
                    showTopic = true,
                )
                endItems(revealed, feed.latest.size, "latest")
            }
        }

        // The selected chip already names the topic; a small intro gives the count and the stand.
        is FeedFilter.OneTopic -> {
            val section = feed.sectionFor(selected.topicId) ?: return
            item(key = "topic-${section.id}-intro", contentType = "feed-intro") {
                FeedViewIntro(
                    overline = listOfNotNull(articleCountLabel(section.count), fetchedLabel(feed.fetchedAt).takeIf { it.isNotBlank() }?.let { "Stand $it" }).joinToString(" · "),
                    title = section.topic.title,
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter).padding(top = 4.dp),
                )
            }
            if (section.isEmpty) {
                item(key = "topic-${section.id}-empty", contentType = "feed-empty") {
                    val loading = section.id in loadingTopics
                    EmptySectionNote(
                        text = when {
                            loading -> "Hole die Meldungen …"
                            section.failedSources.isNotEmpty() -> "Gerade nicht erreichbar: ${section.failedSources.joinToString(", ")}"
                            else -> "Hier ist gerade nichts – schau später nochmal rein."
                        },
                        loading = loading,
                        modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                    )
                }
            } else {
                articleItems(
                    articles = section.all.take(revealed).map { FeedArticle(it, section.topic) },
                    now = now,
                    listening = listening,
                    actions = actions,
                    showImages = showImages,
                    keyPrefix = "topic-${section.id}",
                    showTopic = false,
                )
                endItems(revealed, section.count, "topic-${section.id}")
            }
        }
    }
}

/** A spinner while more is revealed, or the calm end once everything is on screen. */
private fun LazyListScope.endItems(revealed: Int, total: Int, keyPrefix: String) {
    if (revealed < total) {
        item(key = "$keyPrefix-more", contentType = "loading") { NewsLoadingRow(text = "Mehr kommt …") }
    } else {
        item(key = "$keyPrefix-end", contentType = "end") {
            ListEndNote("Das war's · ${articleCountLabel(total)}", modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun NewsLoadingRow(modifier: Modifier = Modifier, text: String = "Hole die News …") {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
