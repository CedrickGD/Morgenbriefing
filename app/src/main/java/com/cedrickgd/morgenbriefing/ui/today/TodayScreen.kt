package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.brief.WeatherUi
import com.cedrickgd.morgenbriefing.ui.brief.briefSectionItems
import com.cedrickgd.morgenbriefing.ui.brief.briefTeaserItem
import com.cedrickgd.morgenbriefing.ui.brief.visibleSections
import com.cedrickgd.morgenbriefing.ui.components.ClothingCard
import com.cedrickgd.morgenbriefing.ui.components.DayCurveCard
import com.cedrickgd.morgenbriefing.ui.components.HomeWeatherHero
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.components.TodayHeader
import com.cedrickgd.morgenbriefing.ui.components.WarningBanner
import com.cedrickgd.morgenbriefing.ui.components.WeatherBackdrop
import com.cedrickgd.morgenbriefing.ui.onboarding.OnboardingSheet
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.StatusBarScrim
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import java.time.ZonedDateTime

/** Height of the animated sky below the status bar. */
private val BackdropHeight = 460.dp

/** How much slower than the content the sky scrolls away (parallax). */
private const val BACKDROP_PARALLAX = 0.35f

/**
 * Tab "Heute": animated sky, live weather for home and the day window, clothing advice and
 * today's news. Draws under the status bar; the bottom is padded by the navigation bar.
 *
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

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    // Saved with the tab state, so switching tabs does not count as a new notification tap.
    var handledSignal by rememberSaveable { mutableIntStateOf(openTodaySignal) }
    LaunchedEffect(openTodaySignal) {
        if (openTodaySignal != handledSignal) {
            handledSignal = openTodaySignal
            listState.animateScrollToItem(0)
        }
    }

    TodayContent(
        state = state,
        listState = listState,
        contentPadding = contentPadding,
        onRefresh = viewModel::refresh,
        onRetryWeather = viewModel::retryWeather,
        onNoticeAction = { action ->
            when (action) {
                NoticeAction.OPEN_SETTINGS -> onOpenSettings()
                NoticeAction.RETRY -> viewModel.retryBrief()
            }
        },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayContent(
    state: TodayUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onRetryWeather: () -> Unit,
    onNoticeAction: (NoticeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = rememberLinkActions()
    val statusTop = statusBarTop()
    val reduceMotion = rememberReduceMotion()
    val pullState = rememberPullToRefreshState()
    val weather = state.weather
    val today = state.today
    val news = remember(state.brief, today) { newsBlockFor(state.brief, today) }
    val greeting = remember(state.now) { Fmt.greeting(state.now) }
    val showSkeleton = weather == null && state.weatherLoading
    val headline = weather?.headline?.takeIf { it.isNotBlank() } ?: if (state.weatherLoading) {
        "Einen Moment – ich schau kurz nach draußen."
    } else {
        "Schön, dass du da bist."
    }

    Box(modifier.fillMaxSize()) {
        WeatherBackdrop(
            condition = weather?.condition ?: Condition.MOSTLY_CLEAR,
            isDay = weather?.isDay ?: (state.now.hour in 7..18),
            animate = !reduceMotion,
            modifier = Modifier
                .fillMaxWidth()
                .height(BackdropHeight + statusTop)
                .graphicsLayer {
                    // Reads scroll state in the draw phase only: no recomposition while scrolling.
                    val height = size.height
                    if (height > 0f) {
                        val scrolled = if (listState.firstVisibleItemIndex == 0) {
                            listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(height)
                        } else {
                            height
                        }
                        translationY = -scrolled * BACKDROP_PARALLAX
                        alpha = 1f - scrolled / height
                    }
                },
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
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
                    .horizontalSafeDrawing(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = statusTop + 8.dp,
                    bottom = bottomContentInset(contentPadding) + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header", contentType = "header") {
                    TodayHeader(date = today, greeting = greeting, headline = headline)
                }

                when {
                    weather != null -> weatherItems(weather)
                    showSkeleton -> item(key = "skeleton", contentType = "skeleton") {
                        LoadingSkeleton(Modifier.animateItem())
                    }
                    else -> item(key = "weather-error", contentType = "info") {
                        InfoCard(
                            emoji = "🌦️",
                            title = "Wetter gerade nicht erreichbar",
                            text = "Check kurz deine Verbindung – dann hol ich das Wetter nochmal.",
                            modifier = Modifier.animateItem(),
                            actionLabel = "Nochmal versuchen",
                            onAction = onRetryWeather,
                        )
                    }
                }

                newsItems(
                    news = news,
                    now = state.now,
                    // The skeleton already sketches the news; don't show a second loader.
                    showLoader = !showSkeleton,
                    onOpen = links::openArticle,
                    onShare = links::shareArticle,
                    onNoticeAction = onNoticeAction,
                )

                if (weather != null) {
                    item(key = "footer", contentType = "footer") {
                        WeatherFooter(weather = weather, offline = state.weatherFailed, now = state.now)
                    }
                }
            }
        }

        // Once the header scrolls under the status bar, give the clock a background.
        val scrolledUnderStatusBar by remember(listState) {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > STATUS_SCRIM_THRESHOLD_PX
            }
        }
        StatusBarScrim(visible = scrolledUnderStatusBar)
    }
}

private const val STATUS_SCRIM_THRESHOLD_PX = 24

private fun LazyListScope.weatherItems(weather: WeatherUi) {
    item(key = "hero", contentType = "hero") {
        HomeWeatherHero(snapshot = weather.snapshot, modifier = Modifier.animateItem())
    }
    if (weather.warnings.isNotEmpty()) {
        item(key = "warnings", contentType = "warnings") {
            WarningBanner(warnings = weather.warnings, modifier = Modifier.animateItem())
        }
    }
    item(key = "curve", contentType = "curve") {
        DayCurveCard(snapshot = weather.snapshot, modifier = Modifier.animateItem())
    }
    weather.clothing?.let { advice ->
        item(key = "clothing", contentType = "clothing") {
            ClothingCard(advice = advice, modifier = Modifier.animateItem())
        }
    }
}

private fun LazyListScope.newsItems(
    news: NewsBlock,
    now: ZonedDateTime,
    showLoader: Boolean,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    onNoticeAction: (NoticeAction) -> Unit,
) {
    when (news) {
        NewsBlock.Loading -> if (showLoader) {
            item(key = "news-loading", contentType = "loading") { NewsLoadingRow() }
        }

        is NewsBlock.Demo -> {
            noticeItem(news.notice, onNoticeAction)
            briefSectionItems(news.brief, now, onOpen, onShare, keyPrefix = "today")
        }

        is NewsBlock.Ready -> briefItems(news.brief, news.stale, now, onOpen, onShare)

        is NewsBlock.Problem -> {
            noticeItem(news.notice, onNoticeAction)
            news.brief?.let { briefItems(it, news.stale, now, onOpen, onShare) }
        }
    }
}

private fun LazyListScope.briefItems(
    brief: Brief,
    stale: Boolean,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
) {
    briefTeaserItem(brief = brief, stale = stale, keyPrefix = "today")
    if (brief.visibleSections().isEmpty()) {
        item(key = "news-empty", contentType = "info") {
            InfoCard(
                emoji = "📭",
                title = "Keine Meldungen im Briefing",
                text = "Die Morgen-Routine hat diesmal nichts Berichtenswertes gefunden.",
                modifier = Modifier.animateItem(),
            )
        }
    } else {
        briefSectionItems(brief, now, onOpen, onShare, keyPrefix = "today")
    }
}

private fun LazyListScope.noticeItem(notice: Notice, onAction: (NoticeAction) -> Unit) {
    item(key = "news-notice", contentType = "info") {
        val action = notice.action
        InfoCard(
            emoji = notice.emoji,
            title = notice.title,
            text = notice.text,
            modifier = Modifier.animateItem(),
            actionLabel = notice.actionLabel.takeIf { action != null },
            onAction = action?.let { act -> { onAction(act) } },
        )
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

@Composable
private fun WeatherFooter(
    weather: WeatherUi,
    offline: Boolean,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    val updated = remember(weather, now) { updatedLabel(weather, now) }
    val text = buildString {
        append("Wetter: Open-Meteo")
        if (updated.isNotEmpty()) append(" · aktualisiert $updated")
        if (offline) append(" · gerade offline")
    }
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** "05:31" for a snapshot from today, otherwise relative ("vor 14 Std.") so old data is obvious. */
private fun updatedLabel(weather: WeatherUi, now: ZonedDateTime): String {
    val fetchedDay = parseInstantOrNull(weather.snapshot.fetchedAt)?.atZone(BERLIN)?.toLocalDate()
    if (fetchedDay == null || fetchedDay == now.toLocalDate()) return weather.updatedClock
    return Fmt.relative(weather.snapshot.fetchedAt, now) ?: weather.updatedClock
}
