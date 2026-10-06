package com.cedrickgd.morgenbriefing.ui.brief

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.components.ClothingCard
import com.cedrickgd.morgenbriefing.ui.components.DayCurveCard
import com.cedrickgd.morgenbriefing.ui.components.GlassIconButton
import com.cedrickgd.morgenbriefing.ui.components.GlassSnackbarHost
import com.cedrickgd.morgenbriefing.ui.components.HomeWeatherHero
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.components.NewsTeaser
import com.cedrickgd.morgenbriefing.ui.components.PageFooter
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.PageTitle
import com.cedrickgd.morgenbriefing.ui.glass.blockTouchesBehind
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.news.FeedArticle
import com.cedrickgd.morgenbriefing.ui.news.FeedGutter
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.SourcesSheet
import com.cedrickgd.morgenbriefing.ui.news.buildNewsFeed
import com.cedrickgd.morgenbriefing.ui.news.forYouItems
import com.cedrickgd.morgenbriefing.ui.news.rememberFeedActions
import com.cedrickgd.morgenbriefing.ui.news.rememberListenController
import com.cedrickgd.morgenbriefing.ui.news.rememberListeningKey
import com.cedrickgd.morgenbriefing.ui.news.say
import com.cedrickgd.morgenbriefing.ui.news.toggled
import com.cedrickgd.morgenbriefing.ui.news.withTopicVisible
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.ui.theme.german
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.pageSideInset
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import java.time.LocalDate

/** Room above the page title for the floating back button. */
private val BackButtonRoom = 48.dp

/** Scrolled this far, the frosted bar with the small title fades in. */
private val TitleGoneAfter = 88.dp

/**
 * One stored morning from the history, transparent over the AmbientSky: page title (weekday +
 * date), that morning's weather as glass cards and its brief as the editorial feed ("Für dich"
 * sections). A round glass back button floats top left; once the title scrolls away a frosted
 * bar with the date fades in around it.
 *
 * @param date `YYYY-MM-DD` from the route.
 * @param onOpenArticle opens the reader for the article [key] of the brief dated [date].
 */
@Composable
fun DayDetailScreen(
    date: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenArticle: (date: String, key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = remember(date) { parseDateOrNull(date) }
    val viewModel: DayDetailViewModel = viewModel(factory = DayDetailViewModel.factory(day))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val topicPrefs by viewModel.topicPrefs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val links = rememberLinkActions()
    val density = LocalDensity.current
    val statusTop = statusBarTop()
    val bottomInset = bottomContentInset(contentPadding)
    val titleGonePx = with(density) { TitleGoneAfter.roundToPx() }

    val shortTitle = remember(day) { day?.let(Fmt::shortDate) ?: "Tag" }
    val overline = remember(day) { overlineFor(day) }
    val brief = state.brief
    val feed = remember(brief, topicPrefs) { brief?.let { buildNewsFeed(it, topicPrefs) } }
    var expanded by rememberExpandedSections(brief?.date)
    val listening by rememberListeningKey(rememberListenController())
    var sourcesFor by remember { mutableStateOf<FeedArticle?>(null) }

    val actions = rememberFeedActions(
        onOpen = { article -> onOpenArticle(article.date, article.item.key) },
        onShowSources = { sourcesFor = it },
        onMessage = { snackbar.say(scope, it) },
        onHideTopic = { article ->
            val topic = article.topic
            if (topicPrefs.withTopicVisible(topic.id, visible = false) == null) {
                snackbar.say(scope, "Ein Thema bleibt immer sichtbar.")
            } else {
                viewModel.setTopicVisible(topic.id, false)
                snackbar.say(scope, "„${topic.title}“ ausgeblendet", action = "Rückgängig") {
                    viewModel.setTopicVisible(topic.id, true)
                }
            }
        },
        // The chat route needs a callback this screen's contract does not have; asking stays in the reader.
        onAsk = null,
    )

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .reportChromeScroll()
                .glassContent()
                .horizontalSafeDrawing(),
            // Landscape and tablets: the feed stays a centred column.
            contentPadding = PaddingValues(start = pageSideInset(), end = pageSideInset(), bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title", contentType = "title") {
                PageTitle(
                    overline = overline,
                    title = shortTitle,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = BackButtonRoom),
                )
            }

            when {
                day == null -> item(key = "invalid", contentType = "info") {
                    InfoCard(
                        emoji = "🤔",
                        title = "Diesen Tag gibt es nicht",
                        text = "Der Link zeigt auf kein gültiges Datum. Geh zurück in den Verlauf und wähl einen Tag aus.",
                        actionLabel = "Zurück",
                        onAction = onBack,
                        modifier = Modifier.padding(horizontal = FeedGutter),
                    )
                }

                state.loading && brief == null && state.weather == null -> item(key = "loading", contentType = "skeleton") {
                    LoadingSkeleton(Modifier.padding(horizontal = FeedGutter))
                }

                else -> {
                    dayItems(
                        state = state,
                        brief = brief,
                        feed = feed,
                        onRetry = viewModel::reload,
                    )
                    if (brief != null && feed != null && !feed.isEmpty) {
                        forYouItems(
                            sections = feed.sections,
                            date = brief.date,
                            now = state.now,
                            expanded = expanded,
                            listening = listening,
                            actions = actions,
                            onToggleSection = { id -> expanded = expanded.toggled(id) },
                            keyPrefix = "day",
                        )
                    }
                    if (brief != null || state.weather != null) {
                        item(key = "footer", contentType = "footer") {
                            PageFooter(
                                line = footerLine(brief, hasWeather = state.weather != null, weatherOffline = false),
                                farewell = "Schön war's",
                                modifier = Modifier.padding(horizontal = FeedGutter),
                            )
                        }
                    }
                }
            }
        }

        val titleGone by remember(listState) {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleGonePx }
        }
        DetailTopBar(title = shortTitle, scrolled = titleGone, statusTop = statusTop, onBack = onBack)

        GlassSnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 8.dp),
        )
    }

    sourcesFor?.let { article ->
        SourcesSheet(article = article, onOpenLink = links::open, onDismiss = { sourcesFor = null })
    }
}

/** "SO WAR DEIN MORGEN" — with the year when it is not this year's. */
private fun overlineFor(day: LocalDate?): String {
    if (day == null) return "Verlauf"
    val thisYear = LocalDate.now().year
    return if (day.year == thisYear) "So war dein Morgen" else "So war dein Morgen · ${day.year}"
}

/** Weather headline, weather cards, teaser and the hints when parts of the day are missing. */
private fun LazyListScope.dayItems(
    state: DayDetailUiState,
    brief: Brief?,
    feed: NewsFeed?,
    onRetry: () -> Unit,
) {
    val weather = state.weather
    val headline = weather?.headline?.takeIf { it.isNotBlank() }
    if (headline != null) {
        item(key = "headline", contentType = "headline") {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 28.sp).german(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
    if (weather != null) {
        item(key = "hero", contentType = "hero") {
            HomeWeatherHero(snapshot = weather.snapshot, modifier = Modifier.animateItem().padding(horizontal = FeedGutter))
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

    when {
        brief != null && feed != null -> {
            item(key = "teaser", contentType = "teaser") {
                NewsTeaser(
                    teaser = brief.teaser,
                    generatedAt = brief.generatedAt,
                    stale = false,
                    title = "Die News des Tages",
                    meta = feedSummary(feed),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = FeedGutter)
                        .padding(top = if (weather != null) 20.dp else 0.dp),
                )
            }
            when {
                feed.allHidden -> item(key = "all-hidden", contentType = "info") {
                    InfoCard(
                        emoji = "🙈",
                        title = "Alle Themen ausgeblendet",
                        text = "Dieses Briefing enthält nur Themen, die du ausgeblendet hast. In den Einstellungen kannst du sie wieder einschalten.",
                        modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                    )
                }
                feed.isEmpty -> item(key = "news-empty", contentType = "info") {
                    InfoCard(
                        emoji = "📭",
                        title = "Keine Meldungen an diesem Tag",
                        text = "Das Briefing von damals enthält keine einzelnen Meldungen.",
                        modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                    )
                }
            }
        }

        weather != null -> item(key = "no-news", contentType = "info") {
            InfoCard(
                emoji = "📭",
                title = "Keine News gespeichert",
                text = "Für diesen Morgen gibt es nur das Wetter. Wenn du gerade offline bist, versuch's gleich nochmal.",
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                actionLabel = "Nochmal versuchen",
                onAction = onRetry,
            )
        }

        else -> item(key = "nothing", contentType = "info") {
            InfoCard(
                emoji = "🗂️",
                title = if (state.failed) "Konnte den Tag nicht laden" else "Für diesen Tag ist nichts gespeichert",
                text = if (state.failed) {
                    "Da ist beim Laden etwas schiefgelaufen. Probier's gleich nochmal."
                } else {
                    "Vielleicht war die App da noch nicht installiert – oder das Briefing ist nicht mehr abrufbar."
                },
                modifier = Modifier.animateItem().padding(horizontal = FeedGutter),
                actionLabel = "Nochmal versuchen",
                onAction = onRetry,
            )
        }
    }
}

/**
 * The floating round glass back button; once [scrolled], a thick frosted bar with the small
 * serif title fades in around it (status bar + 52 dp, like the TopGlassBar).
 */
@Composable
private fun BoxScope.DetailTopBar(title: String, scrolled: Boolean, statusTop: Dp, onBack: () -> Unit) {
    val reveal by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "detailBar",
    )
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = reveal }
                .glass(shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp), level = GlassLevel.Thick, floating = true),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrolled) Modifier.blockTouchesBehind() else Modifier)
                .padding(top = statusTop)
                .height(52.dp)
                .padding(start = 12.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Zurück",
                onClick = onBack,
                level = GlassLevel.Regular,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.displaySmall.fontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = reveal
                        translationY = (1f - reveal) * 8.dp.toPx()
                    },
            )
        }
    }
}
