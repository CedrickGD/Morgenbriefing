package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes

/**
 * "KI-Anbieter wählen" (docs/AI-PROVIDERS.md §10.2): every provider in catalog order as a card
 * with radio dot, name, tagline, capability pills and whether a key (own server: address) is
 * stored. A provider that cannot run here (Claude below Android 8) is dimmed and not selectable.
 *
 * With [liveOnly] it picks the Live provider ("Live-Anbieter wählen"): only providers that can
 * research and run on this device, plus "Wie Chat" when the chat provider can research itself.
 * [onPick] gets null for "Wie Chat"; picking a provider without key is the caller's cue to open
 * its key dialog.
 */
@Composable
internal fun AiProviderPicker(
    ai: AiSettings,
    sdkAvailable: Boolean,
    liveOnly: Boolean,
    onPick: (AiProviderId?) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (liveOnly) AiTexts.LIVE_PROVIDER_ACTION else "KI-Anbieter wählen",
        icon = if (liveOnly) Icons.Rounded.Bolt else Icons.Rounded.AutoAwesome,
        buttons = { DialogButton("Schließen", onDismiss) },
    ) {
        val chat = ProviderCatalog.spec(ai.provider)
        Text(
            if (liveOnly) {
                "⚡ Live recherchiert im Netz und schreibt eigene Artikel – das kann nicht jeder Anbieter. " +
                    "„Frag nach“ bleibt bei ${chat.dativeName}."
            } else {
                "Jeder Anbieter hat seinen eigenen Key und sein eigenes Modell. Das Monatslimit gilt für alle zusammen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (liveOnly) {
                liveProviderChoices(ai, sdkAvailable).forEach { id ->
                    if (id == null) {
                        SameAsChatCard(chat = chat, selected = ai.liveProvider == null || ai.liveProvider == ai.provider, onPick = { onPick(null) })
                    } else {
                        ProviderCard(
                            spec = ProviderCatalog.spec(id),
                            ai = ai,
                            supported = true,
                            selected = ai.liveProvider == id && id != ai.provider,
                            onPick = { onPick(id) },
                        )
                    }
                }
            } else {
                ProviderCatalog.all.forEach { spec ->
                    ProviderCard(
                        spec = spec,
                        ai = ai,
                        supported = !spec.needsSdk || sdkAvailable,
                        selected = ai.provider == spec.id,
                        onPick = { onPick(spec.id) },
                    )
                }
            }
        }
    }
}

/** One provider in the picker. */
@Composable
private fun ProviderCard(spec: ProviderSpec, ai: AiSettings, supported: Boolean, selected: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val configured = supported && ai.isConfigured(spec.id)
    PickerCard(selected = selected, enabled = supported, onPick = onPick) {
        Text(
            spec.name,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
            color = colors.onSurface,
        )
        Text(spec.tagline, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        CapabilityChips(capabilities(spec, ai))
        Spacer(Modifier.height(6.dp))
        Text(
            providerStatusLine(ai, spec.id, supported),
            style = MaterialTheme.typography.labelMedium,
            color = if (configured && !ai.of(spec.id).keyRejected) StatusPalette.ok() else colors.onSurfaceVariant,
        )
    }
}

/** "Wie Chat": Live runs with the chat provider. */
@Composable
private fun SameAsChatCard(chat: ProviderSpec, selected: Boolean, onPick: () -> Unit) {
    PickerCard(selected = selected, enabled = true, onPick = onPick) {
        Text(
            "Wie Chat (${chat.name})",
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Live nutzt denselben Anbieter und Key wie „Frag nach“.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A selectable lamina card with a radio dot and an accent ring when selected. */
@Composable
private fun PickerCard(selected: Boolean, enabled: Boolean, onPick: () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberToggleHaptics()
    val ring by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "providerRing")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .lamina(GlassShapes.tile, strong = selected)
            .border(2.dp, colors.primary.copy(alpha = ring), GlassShapes.tile)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) haptics(true)
                    onPick()
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.padding(top = 2.dp)) { RadioDot(selected = selected, progress = ring) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}
