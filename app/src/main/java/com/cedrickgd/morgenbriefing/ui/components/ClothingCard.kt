package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.domain.ClothingAdvice
import com.cedrickgd.morgenbriefing.domain.ClothingItem
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * "ANZIEHEN": glass card tinted with the accent container, pictogram tiles (🧥 Jacke,
 * ☂️ Schirm …) and the friendly advice text below.
 */
@Composable
fun ClothingCard(advice: ClothingAdvice, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        tint = colors.primaryContainer,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) {
            Text(
                text = "ANZIEHEN",
                style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                modifier = Modifier.semantics { heading() },
            )
            if (advice.items.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (item in advice.items) ClothingTile(item)
                }
            }
            if (advice.text.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(text = advice.text, style = typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ClothingTile(item: ClothingItem) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    CompositionLocalProvider(LocalContentColor provides tint) {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) { }
                .background(tint.copy(alpha = 0.08f), GlassShapes.chip)
                .defaultMinSize(minWidth = 78.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Emoji(item.emoji, size = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
