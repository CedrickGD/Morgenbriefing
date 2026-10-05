package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.domain.WarningLevel
import com.cedrickgd.morgenbriefing.domain.WeatherWarning
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass

/**
 * Stacked weather hints as tinted glass banners (docs/DESIGN.md §7), colored by severity
 * (info → secondary, caution → tertiary, severe → error container). Renders nothing for an
 * empty list.
 */
@Composable
fun WarningBanner(warnings: List<WeatherWarning>, modifier: Modifier = Modifier) {
    if (warnings.isEmpty()) return
    val skip = motionDisabled()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (skip) Modifier else Modifier.animateContentSize()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (warning in warnings) {
            key(warning.level, warning.title) { WarningRow(warning) }
        }
    }
}

@Composable
private fun WarningRow(warning: WeatherWarning) {
    val colors = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (warning.level) {
        WarningLevel.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
        WarningLevel.CAUTION -> colors.tertiaryContainer to colors.onTertiaryContainer
        WarningLevel.SEVERE -> colors.errorContainer to colors.onErrorContainer
    }
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(shape = GlassShapes.tile, level = GlassLevel.Regular, tint = container)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (warning.emoji.isNotBlank()) {
                Emoji(warning.emoji, size = 22.sp, modifier = Modifier.padding(top = 1.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = warning.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                if (warning.text.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = warning.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
