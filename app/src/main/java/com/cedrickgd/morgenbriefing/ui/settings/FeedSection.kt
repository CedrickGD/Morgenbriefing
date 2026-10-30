package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.NewsAge
import com.cedrickgd.morgenbriefing.data.model.WeatherCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import kotlin.math.roundToInt

/**
 * Settings section "Feed": how many articles a topic shows in "Für dich", how far back the feed
 * reaches, and whether pictures are loaded.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FeedCard(
    settings: AppSettings,
    onSectionSize: (Int) -> Unit,
    onNewsAge: (NewsAge) -> Unit,
    onShowImages: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local while dragging; follows the stored value whenever it changes.
    var size by remember(settings.sectionSize) { mutableIntStateOf(settings.sectionSize) }
    val haptics = LocalHapticFeedback.current
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Overline("Meldungen pro Thema in „Für dich“")
                Text(
                    text = size.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = "Danach führt „Alle Meldungen zu …“ in die endlose Liste des Themas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = size.toFloat(),
            onValueChange = {
                val next = it.roundToInt().coerceIn(AppSettings.MIN_SECTION_SIZE, AppSettings.MAX_SECTION_SIZE)
                if (next != size) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                size = next
            },
            onValueChangeFinished = { onSectionSize(size) },
            valueRange = AppSettings.MIN_SECTION_SIZE.toFloat()..AppSettings.MAX_SECTION_SIZE.toFloat(),
            steps = AppSettings.MAX_SECTION_SIZE - AppSettings.MIN_SECTION_SIZE - 1,
            thumb = { CalmSliderThumb() },
            track = { state -> CalmSliderTrack(state) },
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics {
                    contentDescription = "Meldungen pro Thema"
                    stateDescription = size.toString()
                },
        )
        Spacer(Modifier.height(16.dp))
        Overline("Zeitraum")
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            NewsAge.entries.forEach { age -> ChoicePill(label = age.label, selected = age == settings.newsAge, onClick = { onNewsAge(age) }) }
        }
        Text(
            text = "Ein Thema zeigt immer mindestens seine drei neuesten Meldungen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Bilder anzeigen",
            subtitle = if (settings.showImages) "Die Bilder der Redaktionen im Feed" else "Nur ruhige Cover in Themenfarbe – spart Daten",
            checked = settings.showImages,
            onCheckedChange = onShowImages,
            leading = { IconBadge(Icons.Rounded.Image) },
        )
    }
}

/** Settings section "Wetter auf Heute": which blocks the page shows besides the hero. */
@Composable
internal fun WeatherCardsCard(settings: AppSettings, onToggle: (WeatherCard, Boolean) -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Overline("Auf „Heute“ zeigen")
        Text(
            text = "„Vor der Tür“ mit der Temperatur ist immer da.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        WeatherCard.entries.forEach { card ->
            ToggleRow(
                title = card.label,
                subtitle = card.description,
                checked = card in settings.weatherCards,
                onCheckedChange = { onToggle(card, it) },
            )
        }
    }
}

/** A selectable pill for one of a few options (segmented choice on a glass card). */
@Composable
internal fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.primary) else Modifier.lamina(CircleShape, strong = true))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimary else colors.onSurface,
            maxLines = 1,
        )
    }
}
