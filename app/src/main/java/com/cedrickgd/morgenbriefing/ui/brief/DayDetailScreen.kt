package com.cedrickgd.morgenbriefing.ui.brief

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.components.ClothingCard
import com.cedrickgd.morgenbriefing.ui.components.DayCurveCard
import com.cedrickgd.morgenbriefing.ui.components.HomeWeatherHero
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.components.TodayHeader
import com.cedrickgd.morgenbriefing.ui.news.TopicPrefs
import com.cedrickgd.morgenbriefing.ui.news.buildNewsFeed
import com.cedrickgd.morgenbriefing.ui.news.toggled
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import java.time.LocalDate

/**
 * One stored morning from the history: the weather snapshot of that morning (hero, day curve,
 * clothing) and the brief. The bottom bar is hidden here, so this screen keeps its content
 * clear of the gesture handle / 3-button bar itself.
 *
 * @param date `YYYY-MM-DD` from the route.
 * @param onOpenArticle opens the reader for the article [key] of the brief dated [date].
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val title = remember(day) { day?.let(Fmt::shortDate) ?: "Tag" }

    Column(
        modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zurück")
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        val listPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomContentInset(contentPadding) + 24.dp,
        )

        when {
            day == null -> Box(Modifier.horizontalSafeDrawing().padding(listPadding)) {
                InfoCard(
                    emoji = "🤔",
                    title = "Diesen Tag gibt es nicht",
                    text = "Der Link zeigt auf kein gültiges Datum. Geh zurück in den Verlauf und wähl einen Tag aus.",
                    actionLabel = "Zurück",
                    onAction = onBack,
                )
            }

            state.loading && state.brief == null && state.weather == null ->
                Box(Modifier.horizontalSafeDrawing().padding(listPadding)) { LoadingSkeleton() }

            else -> DayDetailList(
                day = day,
                state = state,
                topicPrefs = topicPrefs,
                contentPadding = listPadding,
                onRetry = viewModel::reload,
                onOpenArticle = onOpenArticle,
            )
        }
    }
}

private val DayEmptyNews = EmptyNewsCopy(
    emoji = "📭",
    title = "Keine Meldungen an diesem Tag",
    text = "Das Briefing von damals enthält keine einzelnen Meldungen.",
)

@Composable
private fun DayDetailList(
    day: LocalDate,
    state: DayDetailUiState,
    topicPrefs: TopicPrefs,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onOpenArticle: (date: String, key: String) -> Unit,
) {
    val links = rememberLinkActions()
    val weather = state.weather
    val brief = state.brief
    val feed = remember(brief, topicPrefs) { brief?.let { buildNewsFeed(it, topicPrefs) } }
    var expanded by rememberExpandedSections(brief?.date)
    val headline = weather?.headline?.takeIf { it.isNotBlank() }
        ?: brief?.teaser?.takeIf { it.isNotBlank() }
        ?: "Von diesem Morgen ist leider nichts mehr übrig."

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .horizontalSafeDrawing(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", contentType = "header") {
            TodayHeader(date = day, greeting = "So war dein Morgen", headline = headline)
        }

        if (weather != null) {
            item(key = "hero", contentType = "hero") {
                HomeWeatherHero(snapshot = weather.snapshot, modifier = Modifier.animateItem())
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

        when {
            brief != null && feed != null -> briefNewsItems(
                brief = brief,
                feed = feed,
                stale = false,
                now = state.now,
                expanded = expanded,
                topStoriesTitle = "Das Wichtigste des Tages",
                empty = DayEmptyNews,
                onToggleSection = { id -> expanded = expanded.toggled(id) },
                onOpen = { item -> onOpenArticle(brief.date, item.key) },
                onShare = links::shareArticle,
                keyPrefix = "day",
            )

            weather != null -> item(key = "no-news", contentType = "info") {
                InfoCard(
                    emoji = "📭",
                    title = "Keine News gespeichert",
                    text = "Für diesen Morgen gibt es nur das Wetter. Wenn du gerade offline bist, versuch's gleich nochmal.",
                    modifier = Modifier.animateItem(),
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
                    modifier = Modifier.animateItem(),
                    actionLabel = "Nochmal versuchen",
                    onAction = onRetry,
                )
            }
        }
    }
}
