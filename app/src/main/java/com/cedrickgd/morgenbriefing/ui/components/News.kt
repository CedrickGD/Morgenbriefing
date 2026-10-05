package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import java.time.ZonedDateTime

/** Items with at least this importance get the "Top" pill. */
private const val TOP_IMPORTANCE = 8

/** Stagger between news cards entering, capped so long lists do not drag. */
private const val STAGGER_MILLIS = 70
private const val MAX_STAGGER_MILLIS = 350

/** The brief's teaser sentence + "Stand 05:07" / stale hint. */
@Composable
fun NewsTeaser(teaser: String, generatedAt: String?, stale: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val stand = generatedAt?.let(Fmt::clock).orEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Emoji("📰", size = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "HEUTE IN DEN NEWS",
                    style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (stand.isNotBlank()) {
                    Text(
                        text = "Stand $stand",
                        style = typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = teaser.ifBlank { "Die wichtigsten Meldungen des Morgens – kurz und knapp." },
                style = typography.titleMedium,
            )
            if (stale) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.secondaryContainer.copy(alpha = 0.7f),
                    contentColor = colors.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Das ist noch das Briefing von gestern – das neue kommt gleich.",
                            style = typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One news rubric: header (emoji, title, count) and a card per item. Cards open the article,
 * the share button shares it; both are 48 dp touch targets. Cards enter gently staggered.
 */
@Composable
fun NewsSectionBlock(
    section: NewsSection,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (section.emoji.isNotBlank()) {
                Emoji(section.emoji, size = 22.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = section.title,
                style = typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (section.items.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                val count = section.items.size
                Pill(
                    text = count.toString(),
                    container = colors.surfaceContainerHigh,
                    content = colors.onSurfaceVariant,
                    textStyle = typography.labelMedium,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = if (count == 1) "1 Meldung" else "$count Meldungen"
                    },
                )
            }
        }

        if (section.items.isEmpty()) {
            Text(
                text = "Heute keine Meldungen in dieser Rubrik.",
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        section.items.forEachIndexed { index, item ->
            key(item.url, item.headline) {
                val entrance = rememberEntrance(
                    delayMillis = (index * STAGGER_MILLIS).coerceAtMost(MAX_STAGGER_MILLIS),
                )
                NewsCard(
                    item = item,
                    now = now,
                    onOpen = onOpen,
                    onShare = onShare,
                    modifier = Modifier.entrance(entrance, distance = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun NewsCard(
    item: NewsItem,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val relative = Fmt.relative(item.publishedAt, now)
    val meta = listOfNotNull(item.source.takeIf { it.isNotBlank() }, relative).joinToString(" · ")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = "Artikel öffnen", role = Role.Button) { onOpen(item) }
                .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                if (item.importance >= TOP_IMPORTANCE) {
                    Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                        Text(
                            text = "Top",
                            style = typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = item.headline,
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.summary,
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meta,
                    style = typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onShare(item) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Teilen",
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
