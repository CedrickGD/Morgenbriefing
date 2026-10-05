package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * Opens the news part of a page: overline ("DEINE NACHRICHTEN · STAND 05:07") and a serif
 * title, straight on the sky.
 */
@Composable
fun NewsSectionTitle(generatedAt: String?, modifier: Modifier = Modifier, title: String = "Heute in den News") {
    val colors = MaterialTheme.colorScheme
    val stand = remember(generatedAt) { generatedAt?.let(Fmt::clock).orEmpty() }
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = if (stand.isBlank()) "DEINE NACHRICHTEN" else "DEINE NACHRICHTEN · STAND $stand",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = headerOverlineColor(colors.onSurfaceVariant, colors.onSurface),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * The brief's summary as an editorial pull quote directly on the sky — no card: an accent rule,
 * the teaser large in serif, a quiet "12 Meldungen · 4 Themen" line, a calm note when the brief
 * is not today's, and an optional [action] (e.g. [AskBriefingPill]) below.
 */
@Composable
fun BriefingPullQuote(
    teaser: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    stale: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // The rule of a newspaper pull quote, fading out downwards.
            Box(
                Modifier
                    .padding(top = 6.dp, bottom = 6.dp, end = 16.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.15f))), CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = teaser.ifBlank { "Die wichtigsten Meldungen des Morgens – ganz in Ruhe zum Nachlesen." },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        lineHeight = 31.sp,
                        letterSpacing = 0.sp,
                    ),
                    color = colors.onSurface,
                )
                if (!meta.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // Non-breaking around the dot: a line never starts with "·".
                        text = meta.replace(" · ", " · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (stale) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Noch das letzte Briefing – das neue kommt gleich.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/**
 * Title and pull quote together (the day detail's news intro).
 *
 * @param title the serif title, e.g. "Die News des Tages".
 * @param meta optional quiet line under the teaser ("27 Meldungen · 11 Themen").
 */
@Composable
fun NewsTeaser(
    teaser: String,
    generatedAt: String?,
    stale: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Heute in den News",
    meta: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        NewsSectionTitle(generatedAt = generatedAt, title = title)
        Spacer(Modifier.height(18.dp))
        BriefingPullQuote(teaser = teaser, meta = meta, stale = stale, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * "✨ Frag dein Briefing →": an inline glass pill into the chat about the whole brief
 * (docs/AI.md §1) — part of the pull quote, not another card.
 */
@Composable
fun AskBriefingPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, pressedScale = 0.96f)
            .glass(shape = GlassShapes.pill, level = GlassLevel.Thin, tint = colors.primaryContainer)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Chat zum Briefing öffnen",
                onClick = onClick,
            )
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text = "Frag dein Briefing", style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A slim tinted banner for a hint about the news (demo brief, offline, token problem): emoji,
 * one bold line, one quiet line, and the action as a text button — much lighter than a card.
 */
@Composable
fun NoticeBanner(
    emoji: String,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = isGlassDark()
    val fill = colors.secondaryContainer.copy(alpha = if (dark) 0.55f else 0.72f)
    val ink = colors.onSecondaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlassShapes.chip)
            .background(fill)
            .border(1.dp, ink.copy(alpha = 0.10f), GlassShapes.chip)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (actionLabel != null) 2.dp else 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Emoji(emoji, size = 20.sp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = ink)
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.82f))
            }
            // The action reads as the banner's last line, like a link — the text keeps the full width.
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * The calm end of a page: "Stand 05:07 · Wetter: Open-Meteo" and a serif "Bis morgen ☀️".
 *
 * @param line the small info line; blank lines are left out.
 */
@Composable
fun PageFooter(line: String, modifier: Modifier = Modifier, farewell: String = "Bis morgen") {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 2.dp)
                .background(colors.onSurfaceVariant.copy(alpha = 0.3f), CircleShape),
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = farewell,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 22.sp,
                ),
                color = colors.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Emoji("☀️", size = 20.sp)
        }
        if (line.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
