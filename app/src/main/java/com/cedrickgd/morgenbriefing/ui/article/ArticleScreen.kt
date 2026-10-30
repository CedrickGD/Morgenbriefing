package com.cedrickgd.morgenbriefing.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.cedrickgd.morgenbriefing.data.model.ArticleBlock
import com.cedrickgd.morgenbriefing.data.model.ArticleContent
import com.cedrickgd.morgenbriefing.data.model.ArticleImage
import com.cedrickgd.morgenbriefing.listen
import com.cedrickgd.morgenbriefing.listen.ListenMiniPlayerDefaults
import com.cedrickgd.morgenbriefing.ui.components.GlassIconButton
import com.cedrickgd.morgenbriefing.ui.components.GlassPillButton
import com.cedrickgd.morgenbriefing.ui.components.LoadingSkeleton
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.nav.ArticleRoute
import com.cedrickgd.morgenbriefing.ui.news.ArticleVisual
import com.cedrickgd.morgenbriefing.ui.news.feedMeta
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop

/**
 * The article reader (docs/DESIGN.md §7): the article's own text and pictures on a calm, opaque
 * surface — no ads, no cookie banners, no navigation. The feed's picture (or the page's lead
 * picture) sits edge-to-edge under the status bar, then source and time, the headline in serif,
 * the teaser, and the extracted body. Floating glass controls: back, "Zuhören" (reads the full
 * text), share and "Im Browser öffnen" as the way out when a page cannot be read.
 */
@Composable
fun ArticleScreen(
    route: ArticleRoute,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ArticleViewModel = viewModel(key = "article:${route.url}", factory = ArticleViewModel.factory(route))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = rememberLinkActions()
    val listen = remember(context) { context.listen }
    val listenState by listen.state.collectAsStateWithLifecycle()
    val listeningThis = listenState.articleKey == state.item.key && (listenState.speaking || listenState.paused)
    val playerShown = listenState.speaking || listenState.paused
    val colors = MaterialTheme.colorScheme
    val statusTop = statusBarTop()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val playerLift = if (playerShown) ListenMiniPlayerDefaults.Height + ListenMiniPlayerDefaults.Gap else 0.dp
    val openExternal = { links.open(state.item.url) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val heroHeight = minOf(statusTop + maxWidth * 0.72f, maxHeight * 0.5f)
        val listState = rememberLazyListState()
        val content = state.content
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // The article is what the floating glass (controls, mini player) blurs.
                .glassContent(),
            contentPadding = PaddingValues(bottom = navBottom + 32.dp + playerLift),
        ) {
            item(key = "hero") { Hero(state, heroHeight) }
            item(key = "head") { Head(state) }
            when {
                content != null -> {
                    itemsIndexed(content.blocks, key = { index, _ -> "block$index" }) { _, block ->
                        BlockView(block, showImages = state.showImages)
                    }
                    item(key = "end") { EndNote(state, content, onOpenExternal = openExternal) }
                }

                state.loading -> item(key = "skeleton") { BodySkeleton() }

                else -> item(key = "failed") {
                    LoadFailed(reason = state.error, onRetry = viewModel::retry, onOpenExternal = openExternal)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusTop + 8.dp, start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zurück", onClick = onBack)
            Spacer(Modifier.weight(1f))
            val speakingThis = listeningThis && listenState.speaking
            GlassIconButton(
                icon = if (speakingThis) Icons.Rounded.Pause else Icons.Rounded.Headphones,
                contentDescription = if (speakingThis) "Vorlesen pausieren" else "Zuhören",
                onClick = {
                    if (listeningThis) listen.pauseOrResume() else listen.play(state.item, state.content?.text)
                },
            )
            GlassIconButton(Icons.Rounded.Share, contentDescription = "Teilen", onClick = { links.shareArticle(state.item) })
            GlassIconButton(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Im Browser öffnen", onClick = openExternal)
        }
    }
}

@Composable
private fun Hero(state: ArticleUiState, height: Dp) {
    val item = state.item
    val leadImage = state.content?.image
    // The feed's picture, else the page's lead picture — both through the same loader and cover.
    val visualItem = remember(item, leadImage) {
        if (item.image == null && leadImage != null) item.copy(image = ArticleImage(leadImage)) else item
    }
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        ArticleVisual(
            item = visualItem,
            topic = state.topic,
            modifier = Modifier.fillMaxSize(),
            showImage = state.showImages,
            contentDescription = visualItem.image?.alt?.ifBlank { null },
            showCoverBadge = true,
        )
        // Legible status bar icons and controls over any picture; the picture melts into the page below.
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
        )
    }
}

@Composable
private fun Head(state: ArticleUiState) {
    val colors = MaterialTheme.colorScheme
    val item = state.item
    val headline = item.headline.ifBlank { state.content?.title.orEmpty() }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = feedMeta(item, state.now).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (headline.isNotBlank()) {
            Text(text = headline, style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        }
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 19.sp,
                    lineHeight = 29.sp,
                ),
                color = colors.onSurface.copy(alpha = 0.92f),
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun BlockView(block: ArticleBlock, showImages: Boolean) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 29.sp)
    when (block.type) {
        ArticleBlock.PARAGRAPH -> Text(
            text = block.text,
            style = body,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        ArticleBlock.HEADING -> Text(
            text = block.text,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        )

        ArticleBlock.QUOTE -> Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.primary, CircleShape),
            )
            Text(
                text = block.text,
                style = body.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        ArticleBlock.LIST -> Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { entry ->
                Row {
                    Text("•", style = body, color = colors.primary, modifier = Modifier.width(22.dp))
                    Text(entry, style = body, color = colors.onSurface, modifier = Modifier.weight(1f))
                }
            }
        }

        ArticleBlock.IMAGE -> if (showImages) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .clip(GlassShapes.tile),
                )
                if (block.alt.isNotBlank()) {
                    Text(
                        text = block.alt,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
                    )
                }
            }
        }

        ArticleBlock.CAPTION -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun EndNote(state: ArticleUiState, content: ArticleContent, onOpenExternal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        if (content.looksTruncated) {
            Text(
                text = "Das war vermutlich nur der Anfang – den Rest zeigt die Redaktion nur auf ihrer Seite.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        val source = state.item.source.ifBlank { content.site }
        if (source.isNotBlank()) {
            Text(
                text = "Quelle: $source",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        GlassPillButton(text = "Im Browser öffnen", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = onOpenExternal)
    }
}

@Composable
private fun BodySkeleton() {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(1f, 0.94f, 0.98f, 0.62f, 1f, 0.9f, 0.97f, 0.48f).forEach { width ->
            LoadingSkeleton(
                Modifier
                    .fillMaxWidth(width)
                    .height(18.dp),
            )
        }
    }
}

@Composable
private fun LoadFailed(reason: String?, onRetry: () -> Unit, onOpenExternal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val wall = reason?.contains("Cookie", ignoreCase = true) == true
        Text(if (wall) "🍪" else "🗞️", fontSize = 40.sp)
        Text(
            text = if (wall) "Nur bei der Redaktion lesbar" else "Artikel gerade nicht da",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = reason ?: "Der Artikel ließ sich gerade nicht laden.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        GlassPillButton(text = "Nochmal versuchen", icon = Icons.Rounded.Refresh, onClick = onRetry, filled = true)
        GlassPillButton(text = "Im Browser öffnen", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = onOpenExternal)
    }
}
