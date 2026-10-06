package com.cedrickgd.morgenbriefing.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.news.ArticleVisual
import com.cedrickgd.morgenbriefing.ui.news.BodyBlock
import com.cedrickgd.morgenbriefing.ui.news.ImageStatus
import com.cedrickgd.morgenbriefing.ui.news.TopicChip
import com.cedrickgd.morgenbriefing.ui.news.creditLine
import com.cedrickgd.morgenbriefing.ui.news.parseArticleBody
import com.cedrickgd.morgenbriefing.ui.news.safeUrl
import com.cedrickgd.morgenbriefing.ui.news.sourceLabel
import com.cedrickgd.morgenbriefing.ui.news.sourcesOf
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.Links
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.statusBarTop
import java.time.ZonedDateTime

/** Comfortable line length on tablets and in landscape. */
private val ReadingWidth = 680.dp

/** Rounded start of the reading surface that overlaps the picture. */
private val SheetLip = 28.dp

/** Vertical parallax: the picture scrolls at this fraction of the content speed. */
private const val PARALLAX = 0.45f

/** Horizontal parallax while swiping between articles (fraction of the page width). */
private const val SWIPE_PARALLAX = 0.15f

/** How much the picture zooms in while it collapses. */
private const val COLLAPSE_ZOOM = 0.06f

/** Centered column with a comfortable line length; clear of side insets. */
internal fun Modifier.readingColumn(): Modifier = this
    .fillMaxWidth()
    .horizontalSafeDrawing()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ReadingWidth)
    .fillMaxWidth()
    .padding(horizontal = 22.dp)

/**
 * One article of the reader: the picture edge-to-edge under the status bar (parallax, zooms
 * and dims softly as it collapses), then on the calm reading surface topic, headline, lead,
 * meta, picture credit, the full text, its sources and the way on to the next article.
 *
 * @param pageOffset how far this page is from the settled pager position (layer phase only).
 */
@Composable
internal fun ReaderPage(
    entry: ReaderEntry,
    next: ReaderEntry?,
    position: Int,
    total: Int,
    listState: LazyListState,
    heroHeight: Dp,
    pageWidth: Dp,
    pageOffset: () -> Float,
    textScale: Float,
    now: ZonedDateTime,
    bottomPadding: Dp,
    onOpenLink: (String) -> Unit,
    onOpenNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = entry.item
    val blocks = remember(item.body) { parseArticleBody(item.body) }
    val sources = remember(item) { sourcesOf(item) }
    var imageStatus by remember(item.image) {
        mutableStateOf(if (item.image?.safeUrl() != null) ImageStatus.LOADING else ImageStatus.NONE)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        item(key = "hero", contentType = "hero") {
            ReaderHero(
                entry = entry,
                height = heroHeight,
                pageWidth = pageWidth,
                listState = listState,
                pageOffset = pageOffset,
                onStatus = { imageStatus = it },
            )
        }
        item(key = "head", contentType = "head") {
            ReaderHead(
                entry = entry,
                position = position,
                total = total,
                now = now,
                textScale = textScale,
                showCredit = imageStatus == ImageStatus.LOADING || imageStatus == ImageStatus.SHOWN,
                onOpenCredit = onOpenLink,
            )
        }
        itemsIndexed(
            items = blocks,
            key = { index, _ -> "block-$index" },
            contentType = { _, block -> block::class.simpleName },
        ) { index, block ->
            BodyBlockView(block = block, first = index == 0, textScale = textScale)
        }
        if (blocks.isEmpty()) {
            item(key = "summary-only", contentType = "notice") {
                SummaryOnlyNotice(item = item, onOpenOriginal = { onOpenLink(item.url) })
            }
        }
        if (sources.isNotEmpty()) {
            item(key = "sources", contentType = "sources") {
                SourcesCard(sources = sources, onOpen = onOpenLink)
            }
        }
        item(key = "next", contentType = "next") {
            if (next != null) NextArticleCard(next = next, onOpen = onOpenNext) else EndOfBriefCard(onBack = onBack)
        }
    }
}

@Composable
private fun ReaderHero(
    entry: ReaderEntry,
    height: Dp,
    pageWidth: Dp,
    listState: LazyListState,
    pageOffset: () -> Float,
    onStatus: (ImageStatus) -> Unit,
) {
    val parallax = !motionDisabled()
    val statusTop = statusBarTop()
    val background = MaterialTheme.colorScheme.background
    val alt = entry.item.image?.alt?.trim()?.takeIf { it.isNotEmpty() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        ArticleVisual(
            item = entry.item,
            topic = entry.topic,
            modifier = Modifier
                // Wider than the page so the sideways parallax never shows an edge.
                .requiredWidth(if (parallax) pageWidth * (1f + 2f * SWIPE_PARALLAX) else pageWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    // Read in the layer phase only: scrolling and swiping never recompose the hero.
                    if (!parallax) return@graphicsLayer
                    val scrolled = if (listState.firstVisibleItemIndex == 0) {
                        listState.firstVisibleItemScrollOffset.toFloat()
                    } else {
                        size.height
                    }
                    val collapsed = (scrolled / size.height).coerceIn(0f, 1f)
                    translationY = scrolled * PARALLAX
                    val zoom = 1f + COLLAPSE_ZOOM * collapsed
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pageOffset().coerceIn(-1f, 1f) * pageWidth.toPx() * SWIPE_PARALLAX
                },
            contentDescription = alt,
            // Above the reading surface's rounded lip, clear of the floating controls at the top.
            // The topic chip sits right below on the reading surface: no second topic badge here.
            showCoverBadge = false,
            onStatus = onStatus,
        )
        // Dims softly while the picture collapses, so the frosted bar reads as a new layer.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val scrolled = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else size.height
                    alpha = (scrolled / size.height).coerceIn(0f, 1f) * 0.45f
                }
                .background(Color.Black),
        )
        // Status bar and floating controls stay legible on bright pictures.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusTop + ReaderBarHeight + 40.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent))),
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

@Composable
private fun ReaderHead(
    entry: ReaderEntry,
    position: Int,
    total: Int,
    now: ZonedDateTime,
    textScale: Float,
    showCredit: Boolean,
    onOpenCredit: (String) -> Unit,
) {
    val item = entry.item
    val colors = MaterialTheme.colorScheme
    val meta = remember(entry, now) { readerMetaLine(entry, now) }
    val image = item.image
    val primarySource = remember(item) { sourcesOf(item).firstOrNull()?.let(::sourceLabel).orEmpty() }

    Column(Modifier.readingColumn()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TopicChip(entry.topic, modifier = Modifier.weight(1f, fill = false))
                if (entry.live) {
                    Spacer(Modifier.width(8.dp))
                    LiveBadge()
                }
            }
            if (total > 1) {
                Text(
                    text = "$position / $total",
                    style = Reading.meta(),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clearAndSetSemantics { contentDescription = "Artikel $position von $total" },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = item.headline,
            style = Reading.articleHeadline(textScale),
            modifier = Modifier.semantics { heading() },
        )
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.summary,
                style = Reading.articleLead(textScale).copy(color = colors.onSurface.copy(alpha = 0.78f)),
            )
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (primarySource.isNotEmpty()) {
                    SourceMonogram(primarySource, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(text = meta, style = Reading.meta(), modifier = Modifier.weight(1f))
            }
        }
        if (showCredit && image != null) {
            val sourceUrl = image.sourceUrl.trim().takeIf { Links.isWebUrl(it) }
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(
                        if (sourceUrl != null) {
                            Modifier.clickable(onClickLabel = "Bildquelle auf Wikimedia Commons öffnen", role = Role.Button) {
                                onOpenCredit(sourceUrl)
                            }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = image.creditLine(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        textDecoration = if (sourceUrl != null) TextDecoration.Underline else TextDecoration.None,
                    ),
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        // A short editorial rule before the text begins.
        Box(
            Modifier
                .padding(top = 6.dp, bottom = 4.dp)
                .width(40.dp)
                .height(3.dp)
                .background(colors.primary.copy(alpha = 0.7f), CircleShape),
        )
    }
}

@Composable
private fun LiveBadge() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Text(
            text = "⚡ Live",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** First letter of a source in a quiet circle ("H" for Handelsblatt). */
@Composable
internal fun SourceMonogram(label: String, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .background(colors.surfaceContainerHigh, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = (size.value * 0.46f).sp),
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun BodyBlockView(block: BodyBlock, first: Boolean, textScale: Float) {
    val body = Reading.articleBody(textScale)
    when (block) {
        is BodyBlock.Paragraph -> Text(
            text = block.text,
            style = body,
            modifier = Modifier
                .readingColumn()
                .padding(top = if (first) 14.dp else 18.dp),
        )

        is BodyBlock.Subheading -> Text(
            text = block.text,
            style = Reading.articleSubheading(textScale),
            modifier = Modifier
                .readingColumn()
                .padding(top = 34.dp, bottom = 2.dp)
                .semantics { heading() },
        )

        is BodyBlock.Bullets -> Column(
            modifier = Modifier
                .readingColumn()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dotTop = with(androidx.compose.ui.platform.LocalDensity.current) {
                // Centre the dot on the first line: half the line height minus half the dot.
                (body.lineHeight.toDp() / 2) - 3.dp
            }
            block.items.forEach { text ->
                Row {
                    Box(
                        Modifier
                            .padding(top = dotTop, start = 2.dp)
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(text = text, style = body, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
