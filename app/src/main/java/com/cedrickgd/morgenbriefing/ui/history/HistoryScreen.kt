package com.cedrickgd.morgenbriefing.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import java.time.LocalDate
import java.time.YearMonth

/**
 * Tab "Verlauf": every stored morning, grouped by month with sticky headers. Tapping a day
 * opens its detail. Pull to refresh merges in days published while the app was closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    contentPadding: PaddingValues,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val listPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 4.dp,
        bottom = bottomContentInset(contentPadding) + 24.dp,
    )

    Column(
        modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        LargeTopAppBar(
            title = { Text("Verlauf") },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = pullState,
        ) {
            val groups = state.groups
            when {
                groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                groups.isEmpty() -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalSafeDrawing(),
                    contentPadding = listPadding,
                ) {
                    item(key = "empty") {
                        if (state.failed) {
                            InfoCard(
                                emoji = "⚠️",
                                title = "Verlauf gerade nicht verfügbar",
                                text = "Beim Laden ist etwas schiefgelaufen. Probier's gleich nochmal.",
                                actionLabel = "Nochmal versuchen",
                                onAction = viewModel::refresh,
                            )
                        } else {
                            InfoCard(
                                emoji = "🗓️",
                                title = "Noch kein Verlauf",
                                text = "Ab morgen früh sammelt sich hier jeden Tag dein Briefing.",
                            )
                        }
                    }
                }

                else -> HistoryList(
                    groups = groups,
                    today = state.today,
                    contentPadding = listPadding,
                    onOpenDay = onOpenDay,
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    groups: List<MonthGroup>,
    today: LocalDate,
    contentPadding: PaddingValues,
    onOpenDay: (LocalDate) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .horizontalSafeDrawing(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { group ->
            stickyHeader(key = "month-${group.month}", contentType = "month") {
                MonthHeader(month = group.month)
            }
            items(
                items = group.entries,
                key = { "day-${it.date}" },
                contentType = { "day" },
            ) { entry ->
                HistoryRow(
                    entry = entry,
                    isToday = entry.date == today,
                    onClick = { onOpenDay(entry.date) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, modifier: Modifier = Modifier) {
    val label = remember(month) { Fmt.monthHeader(month.atDay(1)) }
    // Opaque, so rows scrolling underneath the pinned header stay hidden.
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mini = remember(entry.weather) { entry.weather?.let(::miniWeatherOf) }
    val teaser = entry.teaser.ifBlank {
        if (entry.weather != null) "Nur das Wetter von diesem Morgen ist gespeichert." else "Ohne Zusammenfassung."
    }
    val colors = MaterialTheme.colorScheme

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isToday) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DateBlock(date = entry.date, highlight = isToday)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isToday || mini != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isToday) TodayPill()
                        mini?.let { MiniWeatherLabel(it) }
                    }
                }
                Text(
                    text = teaser,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = itemCountLabel(entry.itemCount)
                if (count != null || !entry.cached) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        count?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (!entry.cached) {
                            Icon(
                                imageVector = Icons.Rounded.CloudQueue,
                                contentDescription = "Wird beim Öffnen geladen",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateBlock(date: LocalDate, highlight: Boolean) {
    val fullDate = remember(date) { Fmt.shortDate(date) }
    Column(
        modifier = Modifier
            .width(48.dp)
            .semantics(mergeDescendants = true) { contentDescription = fullDate },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = weekdayShort(date.dayOfWeek).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TodayPill() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = "Heute",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MiniWeatherLabel(mini: MiniWeather) {
    val from = Fmt.temp(mini.morningTemp)
    val to = Fmt.temp(mini.maxTemp)
    Text(
        text = "${mini.emoji} $from → $to",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "Morgens $from, später bis $to" },
    )
}
