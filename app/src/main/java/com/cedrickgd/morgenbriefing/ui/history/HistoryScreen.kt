package com.cedrickgd.morgenbriefing.ui.history

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.PageTitle
import com.cedrickgd.morgenbriefing.ui.glass.TopGlassBar
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.settings.GlassPillButton
import com.cedrickgd.morgenbriefing.ui.settings.Overline
import com.cedrickgd.morgenbriefing.ui.settings.PillStyle
import com.cedrickgd.morgenbriefing.ui.settings.lamina
import com.cedrickgd.morgenbriefing.ui.settings.rememberStagger
import com.cedrickgd.morgenbriefing.ui.settings.staggerIn
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.KeepListEndAboveChrome
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Page "Verlauf" (docs/DESIGN.md §7): every stored morning on a timeline — a thin line with a dot
 * per day, month headers as glass pills, each day a glass card with the big day number in serif,
 * weekday, teaser, mini weather and article count; today is highlighted. Tap opens the day,
 * pull to refresh merges in days published while the app was closed.
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
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val reduceMotion = rememberReduceMotion()
    val stagger = rememberStagger(enabled = !reduceMotion)
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val showTopBar by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val groups = state.groups
    val rows = remember(groups, state.today) { groups?.let { timelineRows(it, state.today) } }
    val statusTop = statusBarTop()
    val scope = rememberCoroutineScope()
    KeepListEndAboveChrome(listState, bottomContentInset(contentPadding))

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusTop + 8.dp),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .reportChromeScroll()
                    .glassContent()
                    .horizontalSafeDrawing(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentInset(contentPadding) + 32.dp),
            ) {
                item(key = "title", contentType = "title") {
                    PageTitle(
                        overline = "Dein Archiv",
                        title = "Verlauf",
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .staggerIn(stagger, "title", 0),
                    )
                }
                when {
                    rows == null -> items(SKELETON_ROWS, key = { "skeleton-$it" }, contentType = { "skeleton" }) { index ->
                        SkeletonRow(first = index == 0, last = index == SKELETON_ROWS - 1, reduceMotion = reduceMotion)
                    }

                    rows.isEmpty() -> {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                failed = state.failed,
                                onRetry = viewModel::refresh,
                                modifier = Modifier
                                    .padding(top = 20.dp)
                                    .staggerIn(stagger, "empty", 1),
                            )
                        }
                        // A faded preview of the timeline to come, instead of empty sky.
                        if (!state.failed) {
                            items(GHOST_ROWS, key = { "ghost-$it" }, contentType = { "ghost" }) { index ->
                                GhostRow(index = index, first = index == 0, last = index == GHOST_ROWS - 1)
                            }
                        }
                    }

                    else -> {
                        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> if (row is TimelineRow.Month) "month" else "day" }) { index, row ->
                            val enter = Modifier
                                .staggerIn(stagger, row.key, index + 1)
                                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            when (row) {
                                is TimelineRow.Month -> MonthRow(row, enter)
                                is TimelineRow.Day -> DayRow(row, onOpen = { onOpenDay(row.entry.date) }, modifier = enter)
                            }
                        }
                        // A young archive: the mornings still to come, as a faded promise.
                        val days = rows.count { it is TimelineRow.Day }
                        if (days < FEW_DAYS) {
                            item(key = "upcoming-hint", contentType = "hint") {
                                Text(
                                    text = "Ab morgen früh kommt hier jeden Tag dein Briefing dazu – mit Wetter und allen Artikeln.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = GUTTER, end = 8.dp, top = 8.dp),
                                )
                            }
                            items(GHOST_ROWS - 1, key = { "upcoming-$it" }, contentType = { "ghost" }) { index ->
                                GhostRow(index = index + 1, first = index == 0, last = index == GHOST_ROWS - 2)
                            }
                        }
                    }
                }
            }
        }
        TopGlassBar(
            title = "Verlauf",
            visible = showTopBar,
            onTap = { scope.launch { if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0) } },
        )
    }
}

/** Horizontal room for the timeline left of the cards. */
private val GUTTER = 36.dp
private val LINE_X = 14.dp
private val ROW_GAP = 12.dp

/** Distance from a day card's top to its dot (aligned with the day number). */
private val DOT_Y = 40.dp
private const val SKELETON_ROWS = 4
private const val GHOST_ROWS = 3

/** Below this many days the timeline shows the mornings still to come. */
private const val FEW_DAYS = 3

@Composable
private fun lineColor(): Color =
    if (isGlassDark()) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

/** Month header: a glass pill sitting on the timeline. */
@Composable
private fun MonthRow(row: TimelineRow.Month, modifier: Modifier = Modifier) {
    val line = lineColor()
    val label = remember(row.month, row.days) { monthPillLabel(row.month, row.days) }
    val topGap = if (row.first) 12.dp else 8.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // The line continues below the pill (and, except for the first month, above it).
                // Everything stays inside the row: lazy items may be clipped to their bounds.
                val x = LINE_X.toPx()
                val stroke = 2.dp.toPx()
                val pillTop = topGap.toPx()
                val pillBottom = size.height - ROW_GAP.toPx()
                if (!row.first) drawLine(line, Offset(x, 0f), Offset(x, pillTop), stroke)
                drawLine(line, Offset(x, pillBottom), Offset(x, size.height), stroke)
            }
            .padding(top = topGap, bottom = ROW_GAP),
    ) {
        Row(
            modifier = Modifier
                .glass(shape = CircleShape, level = GlassLevel.Thin)
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** One morning: dot on the timeline + glass card. */
@Composable
private fun DayRow(row: TimelineRow.Day, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val entry = row.entry
    val colors = MaterialTheme.colorScheme
    val line = lineColor()
    val accent = colors.primary
    val dark = isGlassDark()
    val hasBrief = entry.itemCount > 0 || entry.teaser.isNotBlank()
    val dotFill = when {
        row.isToday -> accent
        hasBrief -> if (dark) Color.White.copy(alpha = 0.85f) else colors.onSurface.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    val dotRing = if (row.isToday) accent.copy(alpha = 0.28f) else Color.Transparent
    val hollow = if (dark) Color.White.copy(alpha = 0.6f) else colors.onSurface.copy(alpha = 0.4f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = LINE_X.toPx()
                val stroke = 2.dp.toPx()
                val dotY = DOT_Y.toPx()
                val end = if (row.last) dotY else size.height
                drawLine(line, Offset(x, 0f), Offset(x, end), stroke)
                if (dotRing.alpha > 0f) drawCircle(dotRing, radius = 12.dp.toPx(), center = Offset(x, dotY))
                if (dotFill.alpha > 0f) {
                    drawCircle(dotFill, radius = if (row.isToday) 7.dp.toPx() else 5.dp.toPx(), center = Offset(x, dotY))
                } else {
                    drawCircle(hollow, radius = 5.dp.toPx(), center = Offset(x, dotY), style = Stroke(1.5.dp.toPx()))
                }
            }
            .padding(start = GUTTER, bottom = ROW_GAP),
    ) {
        DayCard(entry = entry, isToday = row.isToday, onClick = onOpen, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DayCard(entry: HistoryEntry, isToday: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val mini = remember(entry.weather) { entry.weather?.let(::miniWeatherOf) }
    val teaser = entry.teaser.ifBlank {
        if (entry.weather != null) "Nur das Wetter von diesem Morgen ist gespeichert." else "Ohne Zusammenfassung."
    }
    val fullDate = remember(entry.date) { Fmt.shortDate(entry.date) }
    val count = itemCountLabel(entry.itemCount)
    val description = buildString {
        if (isToday) append("Heute, ")
        if (entry.demo) append("Demo-Briefing, ")
        append(fullDate)
        append(". ")
        append(teaser)
        mini?.let { append(". Morgens ${Fmt.temp(it.morningTemp)}, später bis ${Fmt.temp(it.maxTemp)}") }
        count?.let { append(". $it") }
        if (!entry.cached) append(". Wird beim Öffnen geladen")
    }
    val dark = isGlassDark()
    GlassCard(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        onClick = onClick,
        onClickLabel = "Tag öffnen",
        tint = if (isToday) {
            if (dark) lerp(colors.surface, colors.primary, 0.22f) else lerp(colors.surface, colors.primaryContainer, 0.7f)
        } else {
            null
        },
        contentPadding = PaddingValues(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = entry.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 42.sp),
                    color = if (isToday) colors.primary else colors.onSurface,
                )
                Text(
                    text = weekdayShort(entry.date.dayOfWeek).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isToday) colors.primary else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isToday || entry.demo) {
                    Text(
                        text = when {
                            isToday && entry.demo -> "HEUTE · DEMO"
                            entry.demo -> "DEMO"
                            else -> "HEUTE"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primary)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = teaser,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mini != null || count != null || !entry.cached) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mini?.let { WeatherChip(it) }
                        count?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        if (!entry.cached) {
                            Icon(
                                imageVector = Icons.Rounded.CloudQueue,
                                contentDescription = null,
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
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(24.dp),
            )
        }
    }
}

/** "☀️ 9° → 21°" on a small lamina. */
@Composable
private fun WeatherChip(mini: MiniWeather) {
    Row(
        modifier = Modifier
            .lamina(CircleShape)
            .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji(mini.emoji, size = 14.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = mini.rangeLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Calm placeholder rows (pulsing gently) while the first load runs. */
@Composable
private fun SkeletonRow(first: Boolean, last: Boolean, reduceMotion: Boolean) {
    val line = lineColor()
    val pulse = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 16.dp else 0.dp)
            .drawBehind {
                val x = LINE_X.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, if (last) DOT_Y.toPx() else size.height), 2.dp.toPx())
                drawCircle(line, radius = 5.dp.toPx(), center = Offset(x, DOT_Y.toPx()))
            }
            .padding(start = GUTTER, bottom = ROW_GAP)
            .graphicsLayer { alpha = pulse?.value ?: 0.8f },
    ) {
        GlassCard(Modifier.weight(1f)) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 36.dp)
                    .lamina(),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .lamina(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .lamina(CircleShape),
            )
        }
    }
}

/**
 * One faded day of the timeline still to come (shown under the empty state): dot, line and a
 * ghost card, fading further with every row.
 */
@Composable
private fun GhostRow(index: Int, first: Boolean, last: Boolean) {
    val line = lineColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 20.dp else 0.dp)
            .graphicsLayer { alpha = 0.55f - index * 0.15f }
            .drawBehind {
                val x = LINE_X.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, if (last) DOT_Y.toPx() else size.height), 2.dp.toPx())
                drawCircle(line, radius = 5.dp.toPx(), center = Offset(x, DOT_Y.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            .padding(start = GUTTER, bottom = ROW_GAP)
            .clearAndSetSemantics { },
    ) {
        GlassCard(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
            Row {
                Column(Modifier.width(56.dp)) {
                    Box(Modifier.size(width = 34.dp, height = 34.dp).lamina())
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(width = 22.dp, height = 10.dp).lamina(CircleShape))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.9f).height(13.dp).lamina(CircleShape))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.6f).height(13.dp).lamina(CircleShape))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.size(width = 74.dp, height = 20.dp).lamina(CircleShape))
                }
            }
        }
    }
}

/** No days yet — or the history could not be loaded. */
@Composable
private fun EmptyState(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
        Emoji(if (failed) "🌫️" else "🗓️", size = 40.sp)
        Spacer(Modifier.height(14.dp))
        Overline(if (failed) "Kurz verschwunden" else "Noch leer")
        Text(
            text = if (failed) "Verlauf gerade nicht verfügbar" else "Hier sammeln sich deine Morgen",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (failed) {
                "Beim Laden ist etwas schiefgelaufen. Zieh nach unten oder versuch's gleich nochmal."
            } else {
                "Ab morgen früh landet hier jeden Tag dein Briefing – mit Wetter, Schlagzeilen und allen Artikeln zum Nachlesen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failed) {
            Spacer(Modifier.height(16.dp))
            GlassPillButton("Nochmal versuchen", onClick = onRetry, icon = Icons.Rounded.Refresh, style = PillStyle.Tonal)
        }
    }
}
