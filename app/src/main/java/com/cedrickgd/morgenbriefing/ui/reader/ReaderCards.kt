package com.cedrickgd.morgenbriefing.ui.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.news.ArticleVisual
import com.cedrickgd.morgenbriefing.ui.news.TopicChip
import com.cedrickgd.morgenbriefing.ui.news.hostOf
import com.cedrickgd.morgenbriefing.ui.news.sourceLabel
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.Links

/** Overline above a card at the end of the article ("QUELLEN", "NÄCHSTER ARTIKEL"). */
@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

/** Card surface on the reading page: tonal, no glass (the text surface stays calm). */
@Composable
private fun ReaderCardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = GlassShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

/** Schema-1 items have no full text: the summary plus the one remaining way out to the source. */
@Composable
internal fun SummaryOnlyNotice(item: NewsItem, onOpenOriginal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ReaderCardSurface(Modifier.readingColumn().padding(top = 24.dp)) {
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
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Beim Original weiterlesen")
                }
            }
        }
    }
}

/** "Quellen": every report the article is based on, each opening in the in-app browser. */
@Composable
internal fun SourcesCard(sources: List<SourceRef>, onOpen: (String) -> Unit) {
    Column(Modifier.readingColumn().padding(top = 40.dp)) {
        Overline("Quellen", Modifier.padding(start = 4.dp, bottom = 10.dp))
        ReaderCardSurface {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = sourcesFootnote(sources.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                )
                sources.forEach { source -> SourceRow(source = source, onOpen = onOpen) }
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
            .heightIn(min = 56.dp)
            .then(
                if (url != null) Modifier.clickable(onClickLabel = "Quelle öffnen", role = Role.Button) { onOpen(url) } else Modifier,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceMonogram(label, size = 36.dp)
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

/** "Nächster Artikel": its picture, topic and serif headline; the whole card turns the page. */
@Composable
internal fun NextArticleCard(next: ReaderEntry, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "nextPress",
    )
    val colors = MaterialTheme.colorScheme
    Column(Modifier.readingColumn().padding(top = 40.dp)) {
        Overline("Nächster Artikel", Modifier.padding(start = 4.dp, bottom = 10.dp))
        ReaderCardSurface(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(GlassShapes.card)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClickLabel = "Nächsten Artikel lesen",
                    role = Role.Button,
                    onClick = onOpen,
                ),
        ) {
            Column(Modifier.padding(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(GlassShapes.tile),
                ) {
                    ArticleVisual(
                        item = next.item,
                        topic = next.topic,
                        modifier = Modifier.matchParentSize(),
                        showCoverBadge = false,
                    )
                    TopicChip(
                        topic = next.topic,
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    )
                }
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
                    Text(
                        text = next.item.headline,
                        style = Reading.feedHeadline(0.84f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Weiterlesen", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** After the last article of the day. */
@Composable
internal fun EndOfBriefCard(onBack: () -> Unit) {
    ReaderCardSurface(Modifier.readingColumn().padding(top = 40.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Emoji("☕", size = 34.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Du bist durch",
                style = Reading.articleSubheading(),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Das war alles für heute. Hab einen schönen Tag!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(onClick = onBack) { Text("Zurück zur Übersicht") }
        }
    }
}
