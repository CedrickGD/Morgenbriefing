package com.cedrickgd.morgenbriefing.ui.news

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.InfoCard
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.util.LinkActions
import com.cedrickgd.morgenbriefing.util.Links
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Visible picture height below the status bar. */
private val HeroHeight = 300.dp

/** Height of the floating button row below the status bar. */
private val TopBarHeight = 64.dp

/** Rounded start of the reading sheet that overlaps the picture. */
private val SheetLip = 28.dp

/** Comfortable line length on tablets and in landscape. */
private val ReadingWidth = 680.dp

/** Pictures scroll at this fraction of the content speed (parallax). */
private const val PARALLAX = 0.5f

/**
 * The article reader: all articles of one brief in a horizontal pager, starting at [articleKey].
 * Each page: parallax hero picture (or cover) under the status bar, topic, headline, lead,
 * meta, picture credit, the full article body, its sources and a teaser for the next article.
 * Floating round buttons: back, text size (A−/A+) and share.
 *
 * Draws edge to edge; keeps text clear of the gesture handle / 3-button bar itself (the bottom
 * bar is hidden on this route).
 *
 * @param date `YYYY-MM-DD` of the brief (from the route).
 */
@Composable
fun ArticleScreen(
    date: String,
    articleKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = remember(date) { parseDateOrNull(date) }
    val viewModel: ArticleViewModel = viewModel(factory = ArticleViewModel.factory(day, articleKey))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.articles.isNotEmpty() -> ArticlePager(
                state = state,
                openedKey = articleKey,
                onBack = onBack,
                onTextScale = viewModel::changeTextScale,
            )

            state.loading -> ReaderMessage(onBack = onBack) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Hole den Artikel …",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            else -> ReaderMessage(onBack = onBack) {
                InfoCard(
                    emoji = if (day == null) "🤔" else "🗞️",
                    title = if (day == null) "Diesen Artikel gibt es nicht" else "Artikel gerade nicht verfügbar",
                    text = if (day == null) {
                        "Der Link zeigt auf kein gültiges Briefing. Geh zurück und wähl einen Artikel aus."
                    } else {
                        "Das Briefing dieses Tages ist gerade nicht erreichbar. Check kurz deine Verbindung."
                    },
                    actionLabel = if (day == null) "Zurück" else "Nochmal versuchen",
                    onAction = if (day == null) onBack else viewModel::reload,
                )
            }
        }
    }
}

@Composable
private fun ArticlePager(
    state: ArticleUiState,
    openedKey: String,
    onBack: () -> Unit,
    onTextScale: (Boolean) -> Unit,
) {
    val articles = state.articles
    val links = rememberLinkActions()
    val scope = rememberCoroutineScope()
    val still = motionDisabled()
    val pagerState = rememberPagerState(initialPage = state.startIndex) { articles.size }
    val listStates = rememberPageListStates(articles.size)

    // Stay on the same article if the list is re-arranged meanwhile (topic settings changed).
    var currentKey by rememberSaveable { mutableStateOf(openedKey) }
    val latestArticles by rememberUpdatedState(articles)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestArticles.getOrNull(page)?.let { currentKey = it.item.key }
        }
    }
    LaunchedEffect(articles) {
        val index = articles.indexOfFirst { it.item.key == currentKey }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = statusBarTop()
        val heroHeight = minOf(HeroHeight + statusTop, maxHeight * 0.62f)
        val wide = maxWidth >= 600.dp

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            ArticlePage(
                article = articles[page],
                next = articles.getOrNull(page + 1),
                listState = listStates[page],
                heroHeight = heroHeight,
                wide = wide,
                textScale = state.textScale,
                now = state.now,
                links = links,
                onOpenNext = {
                    scope.launch {
                        if (still) pagerState.scrollToPage(page + 1) else pagerState.animateScrollToPage(page + 1)
                    }
                },
                onBack = onBack,
            )
        }

        val current = pagerState.currentPage.coerceIn(0, articles.lastIndex)
        val currentList = listStates[current]
        val collapseAtPx = with(LocalDensity.current) { (heroHeight - statusTop - TopBarHeight).toPx() }
        val collapsed by remember(currentList, collapseAtPx) {
            derivedStateOf {
                currentList.firstVisibleItemIndex > 0 || currentList.firstVisibleItemScrollOffset > collapseAtPx
            }
        }
        ReaderStatusBarIcons(lightIcons = !collapsed)
        ReaderTopBar(
            collapsed = collapsed,
            position = current + 1,
            total = articles.size,
            textScale = state.textScale,
            onBack = onBack,
            onShare = { links.shareArticle(articles[current].item) },
            onTextScale = onTextScale,
        )
    }
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

@Composable
private fun ArticlePage(
    article: ReaderArticle,
    next: ReaderArticle?,
    listState: LazyListState,
    heroHeight: Dp,
    wide: Boolean,
    textScale: Float,
    now: ZonedDateTime,
    links: LinkActions,
    onOpenNext: () -> Unit,
    onBack: () -> Unit,
) {
    val item = article.item
    val blocks = remember(item.body) { parseArticleBody(item.body) }
    val sources = remember(item) { sourcesOf(item) }
    var imageStatus by remember(item.image) {
        mutableStateOf(if (item.image?.safeUrl() != null) ImageStatus.LOADING else ImageStatus.NONE)
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 32.dp),
    ) {
        item(key = "hero", contentType = "hero") {
            ArticleHero(
                article = article,
                height = heroHeight,
                listState = listState,
                onStatus = { imageStatus = it },
            )
        }
        item(key = "head", contentType = "head") {
            ArticleHead(
                article = article,
                now = now,
                wide = wide,
                textScale = textScale,
                showCredit = imageStatus == ImageStatus.LOADING || imageStatus == ImageStatus.SHOWN,
                onOpenCredit = { url -> links.open(url) },
            )
        }
        itemsIndexed(
            items = blocks,
            key = { index, _ -> "block-$index" },
            contentType = { _, block -> block::class.simpleName },
        ) { _, block ->
            BodyBlockView(block = block, textScale = textScale)
        }
        if (blocks.isEmpty()) {
            item(key = "original", contentType = "original") {
                SummaryOnlyNotice(item = item, onOpenOriginal = { links.open(item.url) })
            }
        }
        if (sources.isNotEmpty()) {
            item(key = "sources", contentType = "sources") {
                SourcesCard(sources = sources, onOpen = { url -> links.open(url) })
            }
        }
        item(key = "next", contentType = "next") {
            if (next != null) NextArticleCard(next = next, onOpen = onOpenNext) else EndOfBriefCard(onBack = onBack)
        }
    }
}

@Composable
private fun ArticleHero(
    article: ReaderArticle,
    height: Dp,
    listState: LazyListState,
    onStatus: (ImageStatus) -> Unit,
) {
    val parallax = !motionDisabled()
    val statusTop = statusBarTop()
    val background = MaterialTheme.colorScheme.background
    val alt = article.item.image?.alt?.trim()?.takeIf { it.isNotEmpty() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        ArticleVisual(
            item = article.item,
            topic = article.topic,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    // Read in the layer phase only: scrolling never recomposes the hero.
                    if (parallax && listState.firstVisibleItemIndex == 0) {
                        translationY = listState.firstVisibleItemScrollOffset * PARALLAX
                    }
                },
            contentDescription = alt,
            emojiAlignment = Alignment.Center,
            emojiFraction = 0.3f,
            onStatus = onStatus,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop + TopBarHeight + 48.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SheetLip)
                .background(background, RoundedCornerShape(topStart = SheetLip, topEnd = SheetLip)),
        )
    }
}

/** Centered column with a comfortable line length; also clear of side insets. */
private fun Modifier.readingColumn(): Modifier = this
    .fillMaxWidth()
    .horizontalSafeDrawing()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ReadingWidth)
    .fillMaxWidth()
    .padding(horizontal = 22.dp)

@Composable
private fun ArticleHead(
    article: ReaderArticle,
    now: ZonedDateTime,
    wide: Boolean,
    textScale: Float,
    showCredit: Boolean,
    onOpenCredit: (String) -> Unit,
) {
    val item = article.item
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val meta = remember(item, now) { readerMeta(item, now) }
    val image = item.image

    Column(Modifier.readingColumn()) {
        TopicChip(article.topic)
        Spacer(Modifier.height(14.dp))
        Text(
            text = item.headline,
            style = if (wide) typography.headlineLarge else typography.headlineMedium,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.summary,
                style = leadStyle(textScale),
                color = colors.onSurface.copy(alpha = 0.84f),
            )
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = meta, style = typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
        if (showCredit && image != null) {
            val sourceUrl = image.sourceUrl.trim().takeIf { Links.isWebUrl(it) }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .then(
                        if (sourceUrl != null) {
                            Modifier.clickable(onClickLabel = "Bildquelle öffnen", role = Role.Button) {
                                onOpenCredit(sourceUrl)
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = image.creditLine(),
                    style = typography.labelMedium.copy(
                        textDecoration = if (sourceUrl != null) TextDecoration.Underline else TextDecoration.None,
                    ),
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun leadStyle(scale: Float): TextStyle {
    val size = 18.sp * scale
    return MaterialTheme.typography.titleMedium.copy(
        fontSize = size,
        lineHeight = size * 1.45f,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun bodyStyle(scale: Float): TextStyle {
    val size = 17.sp * scale
    return MaterialTheme.typography.bodyLarge.copy(
        fontSize = size,
        lineHeight = size * 1.6f,
        letterSpacing = 0.1.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BodyBlockView(block: BodyBlock, textScale: Float) {
    val body = bodyStyle(textScale)
    when (block) {
        is BodyBlock.Paragraph -> Text(
            text = block.text,
            style = body,
            modifier = Modifier
                .readingColumn()
                .padding(top = 14.dp),
        )

        is BodyBlock.Subheading -> {
            val title = MaterialTheme.typography.titleLarge
            Text(
                text = block.text,
                style = title.copy(fontSize = title.fontSize * textScale, lineHeight = title.lineHeight * textScale),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .readingColumn()
                    .padding(top = 26.dp)
                    .semantics { heading() },
            )
        }

        is BodyBlock.Bullets -> Column(
            modifier = Modifier
                .readingColumn()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.items.forEach { text ->
                Row {
                    Text(
                        text = "•",
                        style = body.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .width(22.dp)
                            .clearAndSetSemantics { },
                    )
                    Text(text = text, style = body, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Schema-1 items have no full text: the summary plus the one remaining way out to the source. */
@Composable
private fun SummaryOnlyNotice(item: NewsItem, onOpenOriginal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .readingColumn()
            .padding(top = 20.dp),
        shape = CardShape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Hier gibt's nur die Kurzfassung", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val source = item.source.trim().ifEmpty { hostOf(item.url) }
            Text(
                text = if (source.isNotEmpty()) {
                    "Zu dieser Meldung gibt es keinen ganzen Artikel in der App. Die Details findest du bei $source."
                } else {
                    "Zu dieser Meldung gibt es keinen ganzen Artikel in der App."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (Links.isWebUrl(item.url)) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onOpenOriginal) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Ganzen Artikel beim Original lesen")
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<SourceRef>, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        modifier = Modifier
            .readingColumn()
            .padding(top = 28.dp),
        shape = CardShape,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                text = "Quellen",
                style = typography.titleMedium,
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
                    .semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (sources.size > 1) {
                    "In eigenen Worten zusammengefasst aus diesen Berichten."
                } else {
                    "In eigenen Worten zusammengefasst aus diesem Bericht."
                },
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(6.dp))
            sources.forEach { source ->
                SourceRow(source = source, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceRef, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val url = source.url.takeIf { Links.isWebUrl(it) }
    val label = sourceLabel(source)
    val host = hostOf(source.url)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (url != null) {
                    Modifier.clickable(onClickLabel = "Quelle öffnen", role = Role.Button) { onOpen(url) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(colors.surfaceContainerHigh, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (host.isNotEmpty() && !host.equals(label, ignoreCase = true)) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (url != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NextArticleCard(next: ReaderArticle, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .readingColumn()
            .padding(top = 20.dp),
        shape = CardShape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = "Nächsten Artikel lesen", role = Role.Button, onClick = onOpen)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArticleVisual(
                item = next.item,
                topic = next.topic,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(16.dp)),
                emojiAlignment = Alignment.Center,
                emojiFraction = 0.42f,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "NÄCHSTER ARTIKEL",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                    color = colors.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = next.item.headline,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                TopicChip(next.topic, compact = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = colors.primary,
            )
        }
    }
}

@Composable
private fun EndOfBriefCard(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .readingColumn()
            .padding(top = 20.dp),
        shape = CardShape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Emoji("☕", size = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text("Du bist durch", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Das war das ganze Briefing. Hab einen schönen Tag!",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onBack) { Text("Zurück zur Übersicht") }
        }
    }
}

/**
 * Floating buttons over the picture; once the picture has scrolled away they get a solid bar
 * behind them, so they never sit on top of text.
 */
@Composable
private fun ReaderTopBar(
    collapsed: Boolean,
    position: Int,
    total: Int,
    textScale: Float,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onTextScale: (Boolean) -> Unit,
) {
    val still = motionDisabled()
    val background = MaterialTheme.colorScheme.background
    val barAlpha by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = if (still) snap() else tween(220),
        label = "readerBar",
    )
    val overImage = !collapsed

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = barAlpha }
                .background(background),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .horizontalSafeDrawing()
                .height(TopBarHeight)
                .padding(horizontal = 8.dp),
        ) {
            ReaderIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Zurück",
                overImage = overImage,
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (total > 1) {
                PagePill(position = position, total = total, overImage = overImage, modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                TextSizeButton(overImage = overImage, textScale = textScale, onChange = onTextScale)
                ReaderIconButton(
                    icon = Icons.Rounded.Share,
                    contentDescription = "Teilen",
                    overImage = overImage,
                    onClick = onShare,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = barAlpha },
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun readerButtonColors(overImage: Boolean): Pair<Color, Color> {
    val still = motionDisabled()
    val spec = if (still) snap<Color>() else tween(220)
    val container by animateColorAsState(
        targetValue = if (overImage) Color.Black.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spec,
        label = "readerButtonContainer",
    )
    val content by animateColorAsState(
        targetValue = if (overImage) Color.White else MaterialTheme.colorScheme.onSurface,
        animationSpec = spec,
        label = "readerButtonContent",
    )
    return container to content
}

@Composable
private fun ReaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    overImage: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = readerButtonColors(overImage)
    FilledIconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun PagePill(position: Int, total: Int, overImage: Boolean, modifier: Modifier = Modifier) {
    val (container, content) = readerButtonColors(overImage)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Artikel $position von $total" },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = "$position / $total",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TextSizeButton(overImage: Boolean, textScale: Float, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val smallest = READER_TEXT_SCALES.first()
    val largest = READER_TEXT_SCALES.last()
    Box {
        ReaderIconButton(
            icon = Icons.Rounded.FormatSize,
            contentDescription = "Textgröße",
            overImage = overImage,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onChange(false) },
                    enabled = textScale > smallest + 0.001f,
                    modifier = Modifier.semantics { contentDescription = "Text kleiner" },
                ) {
                    Text("A−", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "${(textScale * 100).roundToInt()} %",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 56.dp),
                )
                TextButton(
                    onClick = { onChange(true) },
                    enabled = textScale < largest - 0.001f,
                    modifier = Modifier.semantics { contentDescription = "Text größer" },
                ) {
                    Text("A+", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/**
 * Light status bar icons while the picture (with its dark top scrim) is behind them; the
 * theme's own choice once the reading sheet is there. Restores the theme's choice on leave.
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

/** Back button over a centered message (loading, failure). */
@Composable
private fun ReaderMessage(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .horizontalSafeDrawing()
                .padding(start = 16.dp, end = 16.dp, top = TopBarHeight),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        Box(
            Modifier
                .statusBarsPadding()
                .horizontalSafeDrawing()
                .height(TopBarHeight)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            ReaderIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Zurück",
                overImage = false,
                onClick = onBack,
            )
        }
    }
}
