package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.brief.WeatherUi
import com.cedrickgd.morgenbriefing.ui.brief.feedSummary
import com.cedrickgd.morgenbriefing.ui.brief.footerLine
import com.cedrickgd.morgenbriefing.ui.brief.rememberExpandedSections
import com.cedrickgd.morgenbriefing.ui.components.AskBriefingEntry
import com.cedrickgd.morgenbriefing.ui.components.ClothingCard
import com.cedrickgd.morgenbriefing.ui.components.DayCurveCard
import com.cedrickgd.morgenbriefing.ui.components.GlassSnackbarHost
import com.cedrickgd.morgenbriefing.ui.components.HomeWeatherHero
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.components.NewsTeaser
import com.cedrickgd.morgenbriefing.ui.components.PageFooter
import com.cedrickgd.morgenbriefing.ui.components.TodayHeader
import com.cedrickgd.morgenbriefing.ui.components.WarningBanner
import com.cedrickgd.morgenbriefing.ui.glass.TopGlassBar
import com.cedrickgd.morgenbriefing.ui.news.FeedActions
import com.cedrickgd.morgenbriefing.ui.news.FeedArticle
import com.cedrickgd.morgenbriefing.ui.news.FeedChipRow
import com.cedrickgd.morgenbriefing.ui.news.FeedFilter
import com.cedrickgd.morgenbriefing.ui.news.FeedGutter
import com.cedrickgd.morgenbriefing.ui.news.FeedSectionHeader
import com.cedrickgd.morgenbriefing.ui.news.FeedViewIntro
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.ParkedChipHeader
import com.cedrickgd.morgenbriefing.ui.news.SourcesSheet
import com.cedrickgd.morgenbriefing.ui.news.TopicPrefs
import com.cedrickgd.morgenbriefing.ui.news.TopicsSheet
import com.cedrickgd.morgenbriefing.ui.news.articleItems
import com.cedrickgd.morgenbriefing.ui.news.buildNewsFeed
import com.cedrickgd.morgenbriefing.ui.news.catalogueOrder
import com.cedrickgd.morgenbriefing.ui.news.feedChips
import com.cedrickgd.morgenbriefing.ui.news.forYouItems
import com.cedrickgd.morgenbriefing.ui.news.indexOfKey
import com.cedrickgd.morgenbriefing.ui.news.isParked
import com.cedrickgd.morgenbriefing.ui.news.liveBadge
import com.cedrickgd.morgenbriefing.ui.news.liveFeed
import com.cedrickgd.morgenbriefing.ui.news.rememberFeedActions
import com.cedrickgd.morgenbriefing.ui.news.rememberListenController
import com.cedrickgd.morgenbriefing.ui.news.rememberListeningKey
import com.cedrickgd.morgenbriefing.ui.news.resolveFilter
import com.cedrickgd.morgenbriefing.ui.news.say
import com.cedrickgd.morgenbriefing.ui.news.sectionFor
import com.cedrickgd.morgenbriefing.ui.news.toggled
import com.cedrickgd.morgenbriefing.ui.news.withTopicVisible
import com.cedrickgd.morgenbriefing.ui.onboarding.OnboardingSheet
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Key of the sticky chip row in the list. */
private const val CHIPS_KEY = "today-chips"

/** Height of the TopGlassBar below the status bar (docs/DESIGN.md §4). */
private val TopBarHeight = 52.dp

/** Scrolled this far into the header, the TopGlassBar slides in. */
private val HeaderGoneAfter = 96.dp

/**
 * Page "Heute" (docs/DESIGN.md §7): transparent over the AmbientSky. Header directly on the
 * sky, the weather as glass cards, then the editorial news feed with a sticky glass chip row
 * ("Für dich", "Top", "⚡ Live", topics), "✨ Frag dein Briefing" and a calm footer.
 *
 * @param contentPadding top is 0 (the page handles the status bar); bottom leaves room for the
 *   floating chrome and the navigation bar.
 * @param openTodaySignal changes on a notification tap — scrolls back to the top.
 * @param onOpenArticle opens the reader for the article [key] of the brief dated [date].
 * @param onAskBriefing opens the chat about the whole brief of [date].
 * @param onAskAboutArticle opens the chat about one article.
 */
@Composable
fun TodayScreen(
    contentPadding: PaddingValues,
    openTodaySignal: Int,
    onOpenSettings: () -> Unit,
    onOpenArticle: (date: String, key: String) -> Unit,
    onAskBriefing: (date: String) -> Unit = {},
    onAskAboutArticle: (date: String, key: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val topicPrefs by viewModel.topicPrefs.collectAsStateWithLifecycle()
    val live by viewModel.liveUi.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

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

    val callbacks = TodayCallbacks(
        onRefresh = viewModel::refresh,
        onRetryWeather = viewModel::retryWeather,
        onRetryBrief = viewModel::retryBrief,
        onOpenSettings = onOpenSettings,
        onOpenArticle = onOpenArticle,
        onAskBriefing = onAskBriefing,
        onAskAboutArticle = onAskAboutArticle,
        onRefreshLive = viewModel::refreshLive,
        onCancelLive = viewModel::cancelLive,
        onLiveOpened = viewModel::onLiveOpened,
        onSetTopicVisible = viewModel::setTopicVisible,
    )

    TodayContent(
        state = state,
        topicPrefs = topicPrefs,
        live = live,
        listState = listState,
        contentPadding = contentPadding,
        callbacks = callbacks,
        modifier = modifier,
    )

    onboarding?.let { ui ->
        OnboardingSheet(
            hasToken = ui.hasToken,
            onDone = viewModel::finishOnboarding,
            onOpenSettings = {
                viewModel.finishOnboarding()
                onOpenSettings()
            },
        )
    }
}

/** Everything the page can ask for, bundled to keep the content function readable. */
private class TodayCallbacks(
    val onRefresh: () -> Unit,
    val onRetryWeather: () -> Unit,
    val onRetryBrief: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenArticle: (date: String, key: String) -> Unit,
    val onAskBriefing: (date: String) -> Unit,
    val onAskAboutArticle: (date: String, key: String) -> Unit,
    val onRefreshLive: (List<String>) -> Unit,
    val onCancelLive: () -> Unit,
    val onLiveOpened: (List<String>) -> Unit,
    val onSetTopicVisible: (String, Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayContent(
    state: TodayUiState,
    topicPrefs: TopicPrefs,
    live: LiveUi,
    listState: LazyListState,
    contentPadding: PaddingValues,
    callbacks: TodayCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val links = rememberLinkActions()
    val statusTop = statusBarTop()
    val barHeightPx = with(density) { (statusTop + TopBarHeight).roundToPx() }
    val headerGonePx = with(density) { HeaderGoneAfter.roundToPx() }
    val bottomInset = bottomContentInset(contentPadding)
    val pullState = rememberPullToRefreshState()
    val snackbar = remember { SnackbarHostState() }

    val weather = state.weather
    val today = state.today
    val now = state.now
    val news = remember(state.brief, today) { newsBlockFor(state.brief, today) }
    val shownBrief = news.shownBrief
    val feed = remember(shownBrief, topicPrefs) { shownBrief?.let { buildNewsFeed(it, topicPrefs) } }
    val chips = remember(feed) { feedChips(feed) }
    var selectedKey by rememberSaveable { mutableStateOf(FeedFilter.ForYou.key) }
    val selected = remember(selectedKey, chips) { resolveFilter(FeedFilter.fromKey(selectedKey), chips) }
    var expanded by rememberExpandedSections(shownBrief?.date)
    val listening by rememberListeningKey(rememberListenController())
    var showTopics by rememberSaveable { mutableStateOf(false) }
    var sourcesFor by remember { mutableStateOf<FeedArticle?>(null) }
    val visibleTopicIds = remember(topicPrefs) { topicPrefs.catalogueOrder().map { it.id }.filter { it !in topicPrefs.hidden } }

    val hideTopic: (Topic) -> Unit = { topic ->
        if (topicPrefs.withTopicVisible(topic.id, visible = false) == null) {
            snackbar.say(scope, "Ein Thema bleibt immer sichtbar.")
        } else {
            callbacks.onSetTopicVisible(topic.id, false)
            snackbar.say(scope, "„${topic.title}“ ausgeblendet", action = "Rückgängig") {
                callbacks.onSetTopicVisible(topic.id, true)
            }
        }
    }
    val actions = rememberFeedActions(
        onOpen = { article -> callbacks.onOpenArticle(article.date, article.item.key) },
        onShowSources = { sourcesFor = it },
        onMessage = { snackbar.say(scope, it) },
        onHideTopic = { hideTopic(it.topic) },
        onAsk = if (AiSupport.isSupported) {
            { article -> callbacks.onAskAboutArticle(article.date, article.item.key) }
        } else {
            null
        },
    )
    val onSelect: (FeedFilter) -> Unit = { filter ->
        if (filter != selected) {
            // Switching while the chips are parked: start the new view right below them.
            val parked = isParked(listState, CHIPS_KEY, barHeightPx)
            val index = indexOfKey(listState, CHIPS_KEY)
            selectedKey = filter.key
            if (parked && index != null) {
                scope.launch {
                    listState.scrollToItem(index)
                    listState.scrollBy(-barHeightPx.toFloat())
                }
            }
            if (filter == FeedFilter.Live) callbacks.onLiveOpened(visibleTopicIds)
        }
    }

    val greeting = remember(now) { Fmt.greeting(now) }
    val showSkeleton = weather == null && state.weatherLoading
    val headline = weather?.headline?.takeIf { it.isNotBlank() } ?: if (state.weatherLoading) {
        "Einen Moment – ich schau kurz nach draußen."
    } else {
        "Schön, dass du da bist."
    }

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
                    .horizontalSafeDrawing(),
                contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
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
                    weather != null -> weatherItems(weather)
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

                newsIntroItems(
                    news = news,
                    feed = feed,
                    showLoader = !showSkeleton,
                    onNoticeAction = { action ->
                        when (action) {
                            NoticeAction.OPEN_SETTINGS -> callbacks.onOpenSettings()
                            NoticeAction.RETRY -> callbacks.onRetryBrief()
                        }
                    },
                    onAskBriefing = callbacks.onAskBriefing,
                    onChooseTopics = { showTopics = true },
                )

                if (shownBrief != null && feed != null && !feed.isEmpty) {
                    stickyHeader(key = CHIPS_KEY, contentType = "chips") {
                        ParkedChipHeader(listState = listState, key = CHIPS_KEY, barHeightPx = barHeightPx) {
                            FeedChipRow(
                                chips = chips,
                                selected = selected,
                                liveRunning = live.running,
                                onSelect = onSelect,
                                onOpenTopics = { showTopics = true },
                            )
                        }
                    }
                    feedItems(
                        selected = selected,
                        brief = shownBrief,
                        feed = feed,
                        live = live,
                        liveArticles = { liveFeed(shownBrief, topicPrefs, live.articles, now, today.toString()) },
                        now = now,
                        expanded = expanded,
                        listeningKey = listening,
                        actions = actions,
                        onToggleSection = { id -> expanded = expanded.toggled(id) },
                        onRefreshLive = { callbacks.onRefreshLive(visibleTopicIds) },
                        onCancelLive = callbacks.onCancelLive,
                        onOpenSettings = callbacks.onOpenSettings,
                    )
                }

                if (weather != null || shownBrief != null) {
                    item(key = "footer", contentType = "footer") {
                        PageFooter(
                            line = footerLine(shownBrief, hasWeather = weather != null, weatherOffline = state.weatherFailed),
                            modifier = Modifier.padding(horizontal = FeedGutter),
                        )
                    }
                }
            }
        }

        val headerGone by remember(listState) {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > headerGonePx }
        }
        TopGlassBar(title = "Heute · ${Fmt.shortDate(today)}", visible = headerGone)

        GlassSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 8.dp),
        )
    }

    if (showTopics) {
        TopicsSheet(
            prefs = topicPrefs,
            counts = remember(shownBrief) { shownBrief?.sections?.associate { it.id to it.items.size }.orEmpty() },
            onSetVisible = { id, visible -> callbacks.onSetTopicVisible(id, visible) },
            onOpenSettings = {
                showTopics = false
                callbacks.onOpenSettings()
            },
            onDismiss = { showTopics = false },
        )
    }
    sourcesFor?.let { article ->
        SourcesSheet(article = article, onOpenLink = links::open, onDismiss = { sourcesFor = null })
    }
}

private fun LazyListScope.weatherItems(weather: WeatherUi) {
    item(key = "hero", contentType = "hero") {
        HomeWeatherHero(snapshot = weather.snapshot, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
    }
    if (weather.warnings.isNotEmpty()) {
        item(key = "warnings", contentType = "warnings") {
            WarningBanner(warnings = weather.warnings, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
        }
    }
    item(key = "curve", contentType = "curve") {
        DayCurveCard(snapshot = weather.snapshot, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
    }
    weather.clothing?.let { advice ->
        item(key = "clothing", contentType = "clothing") {
            ClothingCard(advice = advice, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
        }
    }
}

/** Notice (demo, problems), teaser card, "✨ Frag dein Briefing" and empty/hidden hints. */
private fun LazyListScope.newsIntroItems(
    news: NewsBlock,
    feed: NewsFeed?,
    showLoader: Boolean,
    onNoticeAction: (NoticeAction) -> Unit,
    onAskBriefing: (String) -> Unit,
    onChooseTopics: () -> Unit,
) {
    val (notice, brief, stale) = when (news) {
        NewsBlock.Loading -> {
            if (showLoader) item(key = "news-loading", contentType = "loading") { NewsLoadingRow() }
            return
        }
        is NewsBlock.Demo -> Triple(news.notice, news.brief, false)
        is NewsBlock.Ready -> Triple(null, news.brief, news.stale)
        is NewsBlock.Problem -> Triple(news.notice, news.brief, news.stale)
    }

    if (brief != null) {
        item(key = "news-teaser", contentType = "teaser") {
            NewsTeaser(
                teaser = brief.teaser,
                generatedAt = brief.generatedAt,
                stale = stale,
                meta = feed?.let(::feedSummary),
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = FeedGutter)
                    .padding(top = 20.dp),
            )
        }
    }
    if (notice != null) {
        item(key = "news-notice", contentType = "info") {
            val action = notice.action
            InfoCard(
                emoji = notice.emoji,
                title = notice.title,
                text = notice.text,
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                actionLabel = notice.actionLabel.takeIf { action != null },
                onAction = action?.let { act -> { onNoticeAction(act) } },
            )
        }
    }
    if (brief == null || feed == null) return

    if (AiSupport.isSupported && !feed.isEmpty) {
        item(key = "ask-briefing", contentType = "ask") {
            AskBriefingEntry(
                onClick = { onAskBriefing(brief.date) },
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
            )
        }
    }
    when {
        feed.allHidden -> item(key = "news-all-hidden", contentType = "info") {
            InfoCard(
                emoji = "🙈",
                title = "Alle Themen ausgeblendet",
                text = "In diesem Briefing stehen nur Themen, die du ausgeblendet hast.",
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                actionLabel = "Themen wählen",
                onAction = onChooseTopics,
            )
        }
        feed.isEmpty -> item(key = "news-empty", contentType = "info") {
            InfoCard(
                emoji = "📭",
                title = "Keine Meldungen im Briefing",
                text = "Die Morgen-Routine hat diesmal nichts Berichtenswertes gefunden.",
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
            )
        }
    }
}

/** The feed below the chips, depending on the selected chip. */
private fun LazyListScope.feedItems(
    selected: FeedFilter,
    brief: Brief,
    feed: NewsFeed,
    live: LiveUi,
    liveArticles: () -> List<FeedArticle>,
    now: ZonedDateTime,
    expanded: Set<String>,
    listeningKey: String?,
    actions: FeedActions,
    onToggleSection: (String) -> Unit,
    onRefreshLive: () -> Unit,
    onCancelLive: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (selected) {
        FeedFilter.ForYou -> forYouItems(
            sections = feed.sections,
            date = brief.date,
            now = now,
            expanded = expanded,
            listeningKey = listeningKey,
            actions = actions,
            onToggleSection = onToggleSection,
            keyPrefix = "foryou",
        )

        FeedFilter.Top -> {
            item(key = "top-intro", contentType = "feed-intro") {
                FeedViewIntro(
                    overline = "Das Wichtigste",
                    title = "Top-Meldungen über alle Themen",
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter).padding(top = 4.dp),
                )
            }
            articleItems(
                articles = feed.topStories.map { FeedArticle(it.item, it.topic, brief.date) },
                now = now,
                listeningKey = listeningKey,
                actions = actions,
                keyPrefix = "top",
                showTopic = true,
            )
        }

        FeedFilter.Live -> {
            val fresh = liveArticles()
            item(key = "live-header", contentType = "live-header") {
                LiveHeader(
                    live = live,
                    now = now,
                    freshCount = fresh.size,
                    onRefresh = onRefreshLive,
                    onCancel = onCancelLive,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                )
            }
            if (fresh.isEmpty()) {
                item(key = "live-empty", contentType = "info") {
                    LiveEmpty(blocked = live.blocked != null, modifier = Modifier.animateItem())
                }
            } else {
                articleItems(
                    articles = fresh,
                    now = now,
                    listeningKey = listeningKey,
                    actions = actions,
                    keyPrefix = "live",
                    showTopic = true,
                    badge = { if (it.live) liveBadge(it, now) else null },
                )
            }
        }

        is FeedFilter.OneTopic -> {
            val section = feed.sectionFor(selected.topicId) ?: return
            item(key = "topic-header-${section.id}", contentType = "feed-section-header") {
                FeedSectionHeader(
                    topic = section.topic,
                    count = section.count,
                    modifier = Modifier.animateItem().padding(horizontal = FeedGutter).padding(top = 4.dp, bottom = 4.dp),
                )
            }
            articleItems(
                articles = (section.top + section.more).map { FeedArticle(it, section.topic, brief.date) },
                now = now,
                listeningKey = listeningKey,
                actions = actions,
                keyPrefix = "topic-${section.id}",
                showTopic = false,
            )
        }
    }
}

@Composable
private fun NewsLoadingRow(modifier: Modifier = Modifier) {
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
            text = "Hole die News …",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
