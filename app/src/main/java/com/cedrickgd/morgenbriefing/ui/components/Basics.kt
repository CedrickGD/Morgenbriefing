package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import java.util.Locale

/** Small uppercase label used above cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.GERMAN),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * Generic friendly glass card for empty/error/setup states: emoji, title, text and an optional
 * glass action pill.
 */
@Composable
fun InfoCard(
    emoji: String,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column {
            if (emoji.isNotBlank()) {
                Emoji(emoji, size = 34.sp)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                GlassPillButton(text = actionLabel, onClick = onAction, filled = true)
            }
        }
    }
}

/**
 * Emoji glyph that is decorative by default (screen readers skip it). Emoji the device font
 * cannot draw are swapped for a close relative ([EmojiSupport]) instead of showing a box.
 */
@Composable
internal fun Emoji(
    emoji: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    decorative: Boolean = true,
) {
    val shown = remember(emoji) { EmojiSupport.display(emoji) }
    Text(
        text = shown,
        style = TextStyle(fontSize = size, lineHeight = size * 1.15f),
        modifier = if (decorative) modifier.clearAndSetSemantics { } else modifier,
    )
}

/**
 * Small rounded "pill" with an optional leading emoji — used for weather facts and the
 * clothing items.
 */
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = if (emoji != null) 10.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (emoji != null) {
                Emoji(emoji, size = 15.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = textStyle, maxLines = 1)
        }
    }
}
