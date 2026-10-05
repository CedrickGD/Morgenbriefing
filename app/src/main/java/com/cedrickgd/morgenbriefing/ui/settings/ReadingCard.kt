package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.SectionLabel

/**
 * Settings → "Lesen": article text size 85–140 % with a live preview in the reader's style
 * (serif headline, roomy sans body). The slider moves the preview immediately; the value is
 * stored when the finger lifts.
 */
@Composable
internal fun ReadingCard(scale: Float, onCommit: (Float) -> Unit, modifier: Modifier = Modifier) {
    // Local while dragging; follows the stored value whenever it changes.
    var value by remember(scale) { mutableFloatStateOf(ReaderScale.normalize(scale)) }
    val label = ReaderScale.label(value)

    SettingsCard(modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Rounded.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Textgröße", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Für Artikel, die du in der App liest",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value,
                onValueChange = { value = ReaderScale.normalize(it) },
                onValueChangeFinished = { onCommit(value) },
                valueRange = ReaderScale.MIN..ReaderScale.MAX,
                steps = ReaderScale.SLIDER_STEPS,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics {
                        contentDescription = "Textgröße für Artikel"
                        stateDescription = label.replace("%", "Prozent")
                    },
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "85 %",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f),
                )
                Text(
                    text = "140 %",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "A",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReaderPreview(scale = value, modifier = Modifier.padding(top = 16.dp))
            if (value != ReaderScale.DEFAULT) {
                TextButton(
                    onClick = {
                        value = ReaderScale.DEFAULT
                        onCommit(ReaderScale.DEFAULT)
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text("Auf 100 % zurücksetzen")
                }
            }
        }
    }
}

/** A short sample paragraph rendered like an article at [scale]. */
@Composable
private fun ReaderPreview(scale: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Vorschau")
            Text(
                text = "Nebel über der Mulde, Sonne am Nachmittag",
                style = previewHeadline(scale),
                color = colors.onSurface,
            )
            Text(
                text = "So sieht ein Artikel bei dieser Größe aus. Am Morgen liegt dichter Nebel über " +
                    "dem Fluss, gegen Mittag kommt die Sonne durch – ein guter Tag für einen Spaziergang.",
                style = previewBody(scale),
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun previewHeadline(scale: Float): TextStyle = MaterialTheme.typography.headlineSmall.copy(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.SemiBold,
    fontSize = (22 * scale).sp,
    lineHeight = (28 * scale).sp,
)

@Composable
private fun previewBody(scale: Float): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = (17 * scale).sp,
    lineHeight = (27 * scale).sp,
)
