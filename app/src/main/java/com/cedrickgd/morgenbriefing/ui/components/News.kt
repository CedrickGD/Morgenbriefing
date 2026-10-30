package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * Opens the news part of a page: overline ("DEINE NACHRICHTEN · STAND 07:12") and a serif
 * title, straight on the sky.
 *
 * @param stand "07:12" of the last fetch, or empty.
 */
@Composable
fun NewsSectionTitle(stand: String, modifier: Modifier = Modifier, title: String = "Deine Nachrichten") {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = if (stand.isBlank()) "AKTUELL" else "AKTUELL · STAND $stand",
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
 * A slim tinted banner for a hint about the news (offline, sources down): emoji, one bold
 * line, one quiet line, and the action as a text button — much lighter than a card.
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
 * The calm end of a page: "Stand 07:12 · 128 Meldungen aus 31 Quellen" and a serif farewell.
 *
 * @param line the small info line; blank lines are left out.
 */
@Composable
fun PageFooter(line: String, modifier: Modifier = Modifier, farewell: String = "Bis später") {
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
