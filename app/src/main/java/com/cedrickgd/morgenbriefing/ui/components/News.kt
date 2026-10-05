package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * Opens the news part of a page: overline ("DEINE NACHRICHTEN · Stand 05:07"), a serif title and
 * the brief's teaser sentence on a glass card, with a hint when the brief is not today's.
 *
 * @param title the serif title, e.g. "Heute in den News".
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
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val stand = remember(generatedAt) { generatedAt?.let(Fmt::clock).orEmpty() }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = if (stand.isBlank()) "DEINE NACHRICHTEN" else "DEINE NACHRICHTEN · STAND $stand",
            style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = headerOverlineColor(colors.onSurfaceVariant, colors.onSurface),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = typography.headlineMedium,
            color = colors.onSurface,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row {
                // A quiet accent rule, like a pull quote in a newspaper.
                Box(
                    Modifier
                        .padding(top = 4.dp, end = 14.dp)
                        .width(3.dp)
                        .height(44.dp)
                        .background(
                            Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0f))),
                            CircleShape,
                        ),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = teaser.ifBlank { "Die wichtigsten Meldungen des Morgens – ganz in Ruhe zum Nachlesen." },
                        style = typography.bodyLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = 19.sp,
                            lineHeight = 28.sp,
                            letterSpacing = 0.sp,
                        ),
                        color = colors.onSurface,
                    )
                    if (!meta.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(text = meta, style = typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (stale) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.secondaryContainer.copy(alpha = 0.75f), GlassShapes.chip)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = colors.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Das ist noch ein älteres Briefing – das neue kommt gleich.",
                        style = typography.bodySmall,
                        color = colors.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * "✨ Frag dein Briefing": a glass entry into the chat about the whole brief (docs/AI.md §1).
 * Sparkle in an accent circle, title, one-line explanation, arrow.
 */
@Composable
fun AskBriefingEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, pressedScale = 0.98f)
            .glass(shape = GlassShapes.tile, level = GlassLevel.Thin)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Chat zum Briefing öffnen",
                onClick = onClick,
            )
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(listOf(colors.primary, colors.tertiary)),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Frag dein Briefing", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                text = "Hintergründe, Zusammenhänge, Nachfragen",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp),
        )
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
