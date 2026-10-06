package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.theme.Reading

/**
 * Settings section 6 "Lesen": article text size 85–140 % with a live preview in the reader's
 * own serif styles ([Reading]). The slider moves the preview immediately; the value is stored
 * when the finger lifts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingCard(
    scale: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    // Local while dragging; follows the stored value whenever it changes.
    var value by remember(scale) { mutableFloatStateOf(ReaderScale.normalize(scale)) }
    val label = ReaderScale.label(value)
    val haptics = LocalHapticFeedback.current

    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Overline("Textgröße im Artikel")
                Text(
                    text = label,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (value != ReaderScale.DEFAULT) {
                GlassPillButton(
                    text = "100 %",
                    onClick = {
                        value = ReaderScale.DEFAULT
                        onCommit(ReaderScale.DEFAULT)
                    },
                    icon = Icons.Rounded.RestartAlt,
                    style = PillStyle.Quiet,
                    contentDescription = "Auf 100 Prozent zurücksetzen",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "A",
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Slider(
                value = value,
                onValueChange = {
                    val next = ReaderScale.normalize(it)
                    if (next != value) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    value = next
                },
                onValueChangeFinished = { onCommit(value) },
                valueRange = ReaderScale.MIN..ReaderScale.MAX,
                steps = ReaderScale.SLIDER_STEPS,
                thumb = { CalmSliderThumb() },
                track = { state -> CalmSliderTrack(state) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .semantics {
                        contentDescription = "Textgröße für Artikel"
                        stateDescription = label.replace("%", "Prozent")
                    },
            )
            Text(
                "A",
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Spacer(Modifier.height(12.dp))
        ReaderPreview(scale = value, reduceMotion = reduceMotion)
    }
}

/** A short sample rendered exactly like the reader at [scale]. */
@Composable
private fun ReaderPreview(scale: Float, reduceMotion: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lamina()
            .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Overline("Vorschau")
        Text(text = "Nebel über der Mulde, Sonne am Nachmittag", style = Reading.articleSubheading(scale))
        Text(
            text = "So liest sich ein Artikel bei dieser Größe. Am Morgen liegt dichter Nebel über dem Fluss, " +
                "gegen Mittag kommt die Sonne durch – ein guter Tag für einen Spaziergang.",
            style = Reading.articleBody(scale),
        )
    }
}
