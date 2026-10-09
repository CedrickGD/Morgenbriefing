package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.UsageSnapshot
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import java.time.LocalDate
import kotlin.math.roundToInt

/** Callbacks of [AiCard]. */
internal class AiActions(
    /** "Wechseln": the provider picker. */
    val onPickProvider: () -> Unit,
    /** The key (own server: address) dialog of the chat provider. */
    val onEditKey: () -> Unit,
    /** A model id of the chat provider (curated, or one the own server listed). */
    val onModel: (String) -> Unit,
    /** "Eigenes Modell …". */
    val onCustomModel: () -> Unit,
    /** "Vom Server laden": the own server's model list. */
    val onLoadServerModels: () -> Unit,
    val onGeminiTier: (GeminiTier) -> Unit,
    /** "Ändern" / "Live-Anbieter wählen": the Live provider picker. */
    val onPickLiveProvider: () -> Unit,
    /** Debug builds: "Entwickler: Basis-URL" of every provider. */
    val onEditDebugBaseUrl: () -> Unit,
    val onLimit: (Double) -> Unit,
    val onLiveAuto: (Boolean) -> Unit,
)

/** The own server's models for the picker: from the last connection test or "Vom Server laden". */
@Immutable
internal data class ServerModels(val ids: List<String> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)

/**
 * Settings section "KI" (docs/AI-PROVIDERS.md §10.1): what it does in one line, the chosen
 * provider with its capabilities, its key (own server: address), the Gemini "Tarif", the model,
 * who runs "⚡ Live", in debug builds the base URL override, the monthly cap with this month's
 * usage and the live auto-refresh switch.
 *
 * @param sdkAvailable the Anthropic SDK runs here (Android 8+); below, Claude asks for another provider.
 * @param debugTools show "Entwickler: Basis-URL" (BuildConfig.DEBUG only).
 */
@Composable
internal fun AiCard(
    settings: AppSettings,
    usage: UsageSnapshot,
    serverModels: ServerModels,
    today: LocalDate,
    sdkAvailable: Boolean,
    debugTools: Boolean,
    reduceMotion: Boolean,
    actions: AiActions,
    modifier: Modifier = Modifier,
) {
    val ai = settings.ai
    val provider = ai.provider
    val spec = ProviderCatalog.spec(provider)
    val supported = !spec.needsSdk || sdkAvailable
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SparkleBadge()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Overline("KI · dein Erklärer")
                Text(
                    "Frag nach & ⚡ Live",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = "Frag zu jedem Artikel nach und hol dir brandaktuelle Meldungen – mit deinem eigenen Key bei einem Anbieter deiner Wahl.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(Modifier.height(20.dp))
        Overline("Anbieter")
        Spacer(Modifier.height(8.dp))
        ProviderSummary(spec = spec, ai = ai, onChange = actions.onPickProvider)
        if (!supported) {
            Text(
                unsupportedProviderHint(spec),
                style = MaterialTheme.typography.bodyMedium,
                color = StatusPalette.attention(),
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            if (provider == AiProviderId.CUSTOM) {
                ServerRow(ai = ai, onEdit = actions.onEditKey)
            } else {
                KeyRow(spec = spec, ai = ai, onEdit = actions.onEditKey)
            }

            if (provider == AiProviderId.GEMINI) {
                Spacer(Modifier.height(22.dp))
                Overline("Tarif")
                Spacer(Modifier.height(8.dp))
                GeminiTierChoice(selected = ai.of(provider).geminiTier, onSelect = actions.onGeminiTier)
            }

            Spacer(Modifier.height(22.dp))
            Overline("Modell")
            Spacer(Modifier.height(8.dp))
            if (provider == AiProviderId.CUSTOM) {
                ServerModelPicker(ai = ai, models = serverModels, actions = actions)
            } else {
                ModelPicker(ai = ai, provider = provider, today = today, actions = actions)
            }
        }

        Spacer(Modifier.height(22.dp))
        Overline("⚡ Live")
        Spacer(Modifier.height(8.dp))
        LiveProviderRow(ai = ai, sdkAvailable = sdkAvailable, onChange = actions.onPickLiveProvider)

        if (debugTools) {
            Spacer(Modifier.height(22.dp))
            Overline("Entwickler")
            Spacer(Modifier.height(8.dp))
            SettingRow(
                icon = Icons.Rounded.Code,
                title = "Entwickler: Basis-URL",
                subtitle = debugBaseUrlSummary(ai),
                action = "Ändern",
                onAction = actions.onEditDebugBaseUrl,
                actionDescription = "Basis-URLs der KI-Anbieter ändern",
            )
        }

        Spacer(Modifier.height(22.dp))
        LimitControl(limit = settings.aiMonthlyLimitUsd, onCommit = actions.onLimit)
        Spacer(Modifier.height(10.dp))
        UsageMeter(usage = usage, limit = settings.aiMonthlyLimitUsd, reduceMotion = reduceMotion)

        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Live automatisch aktualisieren",
            subtitle = "Beim Öffnen von ⚡ Live, wenn die letzte Suche älter als 30 Minuten ist",
            checked = settings.liveAutoRefresh,
            onCheckedChange = actions.onLiveAuto,
        )
    }
}

/** ✨ on a soft gradient disc in the accent colors. */
@Composable
private fun SparkleBadge() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
    }
}

/** The chosen provider: name in serif, its tagline and capabilities, and "Wechseln". */
@Composable
private fun ProviderSummary(spec: ProviderSpec, ai: AiSettings, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lamina(strong = true)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 10.dp),
        ) {
            Text(
                spec.name,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(spec.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            CapabilityChips(capabilities(spec, ai))
        }
        GlassPillButton(text = "Wechseln", onClick = onChange, contentDescription = "KI-Anbieter wechseln")
    }
}

/** The capability pills of a provider ("✓ Chat", "✗ Live", "ab Android 8"), read as one sentence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CapabilityChips(capabilities: List<Capability>, modifier: Modifier = Modifier) {
    val spoken = capabilitySemantics(capabilities)
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        capabilities.forEach { capability ->
            val tint = if (capability.available) StatusPalette.ok() else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = if (isGlassDark()) 0.16f else 0.10f))
                    .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        capability.available -> Icons.Rounded.Check
                        capability.device -> Icons.Rounded.PhoneAndroid
                        else -> Icons.Rounded.Close
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(capability.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun KeyRow(spec: ProviderSpec, ai: AiSettings, onEdit: () -> Unit) {
    val p = ai.of(spec.id)
    // Green only for a key that passed the test or answered a request, amber for one the
    // provider refused.
    val works = p.hasKey && !p.keyUntested && !p.keyRejected
    val tint = when {
        p.hasKey && p.keyRejected -> StatusPalette.attention()
        works -> StatusPalette.ok()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SettingRow(
        icon = Icons.Rounded.Key,
        iconTint = tint,
        title = "API-Key",
        subtitle = aiKeyRowLabel(spec, p.hasKey, p.keyUntested, p.keyRejected),
        subtitleColor = tint,
        action = if (p.hasKey) "Ändern" else "Eintragen",
        onAction = onEdit,
        // A missing or refused key needs a new one: the strong button invites the change.
        strong = !p.hasKey || p.keyRejected,
        actionDescription = aiKeyDialogTitle(spec, p.hasKey),
    )
}

/** The own server: its address, and whether it is set up. */
@Composable
private fun ServerRow(ai: AiSettings, onEdit: () -> Unit) {
    val configured = ai.isConfigured(AiProviderId.CUSTOM)
    val tint = if (configured) StatusPalette.ok() else MaterialTheme.colorScheme.onSurfaceVariant
    SettingRow(
        icon = Icons.Rounded.Dns,
        iconTint = tint,
        title = "Server",
        subtitle = serverLabel(ai.of(AiProviderId.CUSTOM).baseUrl) + if (ai.of(AiProviderId.CUSTOM).hasKey) " · mit Key" else "",
        subtitleColor = if (configured) MaterialTheme.colorScheme.onSurfaceVariant else tint,
        action = if (configured) "Ändern" else "Einrichten",
        onAction = onEdit,
        strong = !configured,
        actionDescription = aiKeyDialogTitle(ProviderCatalog.CUSTOM, configured),
    )
}

/** A lamina row: icon, title and state, and one pill on the right. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    actionDescription: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strong: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lamina()
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = subtitleColor,
            )
        }
        GlassPillButton(
            text = action,
            onClick = onAction,
            style = if (strong) PillStyle.Filled else PillStyle.Tonal,
            contentDescription = actionDescription,
        )
    }
}

/** The curated models of [provider] with prices, and "Eigenes Modell …". */
@Composable
private fun ModelPicker(ai: AiSettings, provider: AiProviderId, today: LocalDate, actions: AiActions) {
    val choices = remember(ai, provider, today) { modelChoices(ai, provider, today) }
    val custom = customModelOf(ai, provider)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            ChoiceRow(
                title = choice.title,
                subtitle = choice.subtitle,
                badge = choice.badge,
                selected = choice.selected,
                enabled = choice.enabled,
                onSelect = { actions.onModel(choice.id) },
            )
        }
        ChoiceRow(
            title = "Eigenes Modell …",
            subtitle = custom?.let { id -> "$id · ${priceLine(ai, provider, id, today)}" } ?: "z. B. ${customModelExample(provider)}",
            selected = custom != null,
            onSelect = actions.onCustomModel,
        )
    }
}

/**
 * The own server's models: the ones it listed (last connection test or "Vom Server laden") as
 * choices, the stored one even when the list is not loaded, and "Eigenes Modell …".
 */
@Composable
private fun ServerModelPicker(ai: AiSettings, models: ServerModels, actions: AiActions) {
    val stored = ai.of(AiProviderId.CUSTOM).model?.trim()?.takeIf { it.isNotEmpty() }
    val ids = (listOfNotNull(stored) + models.ids).distinct()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id ->
            ChoiceRow(
                title = id,
                subtitle = if (id in models.ids) "Auf deinem Server · kostenlos" else "Gewählt · kostenlos",
                selected = id == stored,
                onSelect = { actions.onModel(id) },
            )
        }
        ChoiceRow(
            title = "Eigenes Modell …",
            subtitle = "Modell-ID von Hand, z. B. ${customModelExample(AiProviderId.CUSTOM)}",
            selected = false,
            onSelect = actions.onCustomModel,
        )
    }
    if (stored == null) {
        Text(
            "Noch kein Modell gewählt – lade die Liste vom Server oder trag eins ein.",
            style = MaterialTheme.typography.bodySmall,
            color = StatusPalette.attention(),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (models.failed) {
        Text(
            "Der Server hat keine Modelle geliefert. Läuft er, und stimmt die Adresse?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (ai.isConfigured(AiProviderId.CUSTOM)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassPillButton(
                text = if (models.loading) "Lade …" else "Vom Server laden",
                onClick = actions.onLoadServerModels,
                icon = Icons.Rounded.Refresh,
                style = PillStyle.Quiet,
                enabled = !models.loading,
            )
            if (models.loading) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * "⚡ Live": who researches, and "Ändern"; when nobody can (the chat provider has no Live and no
 * own Live provider is chosen) the one-sentence reason in amber and "Live-Anbieter wählen".
 */
@Composable
private fun LiveProviderRow(ai: AiSettings, sdkAvailable: Boolean, onChange: () -> Unit) {
    val line = liveProviderLine(ai)
    if (line == null) {
        val chat = ProviderCatalog.spec(ai.effectiveLiveProvider)
        Text(
            chat.liveUnavailableText ?: "Live ist gerade nicht verfügbar.",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusPalette.attention(),
        )
        Spacer(Modifier.height(10.dp))
        GlassPillButton(text = AiTexts.LIVE_PROVIDER_ACTION, onClick = onChange, icon = Icons.Rounded.Bolt, style = PillStyle.Filled)
        return
    }
    val live = ai.effectiveLiveProvider
    val liveSpec = ProviderCatalog.spec(live)
    val supported = !liveSpec.needsSdk || sdkAvailable
    val ready = supported && ai.isConfigured(live) && liveSpec.live == LiveSupport.YES
    SettingRow(
        icon = Icons.Rounded.Bolt,
        iconTint = if (ready) StatusPalette.ok() else StatusPalette.attention(),
        title = line,
        subtitle = providerStatusLine(ai, live, supported),
        subtitleColor = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else StatusPalette.attention(),
        action = "Ändern",
        onAction = onChange,
        actionDescription = AiTexts.LIVE_PROVIDER_ACTION,
    )
}

/**
 * A selectable lamina row with a radio dot (models, Gemini tariff, picker entries). [enabled]
 * false greys it out (a Pro model with a free Gemini key); [compact] is for dialogs.
 */
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    badge: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberToggleHaptics()
    val ring by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "choiceRing")
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
                    onSelect()
                },
            )
            .heightIn(min = if (compact) 56.dp else 64.dp)
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected = selected, progress = ring)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                        .copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = if (isGlassDark()) 0.2f else 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                subtitle,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RadioDot(selected: Boolean, progress: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(2.dp, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((12 * progress).dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

/** "Monatslimit": 0–50 $ in whole dollars; 0 = no cap. Stored when the finger lifts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitControl(limit: Double, onCommit: (Double) -> Unit) {
    var value by remember(limit) { mutableFloatStateOf(AiLimit.normalize(limit).toFloat()) }
    val haptics = LocalHapticFeedback.current
    val label = AiLimit.label(value.toDouble())
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Overline("Monatslimit")
            Text(label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Slider(
        value = value,
        onValueChange = {
            val next = it.roundToInt().toFloat()
            if (next != value) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            value = next
        },
        onValueChangeFinished = { onCommit(value.toDouble()) },
        valueRange = 0f..AiLimit.MAX.toFloat(),
        steps = AiLimit.SLIDER_STEPS,
        thumb = { CalmSliderThumb() },
        track = { state -> CalmSliderTrack(state) },
        modifier = Modifier.semantics {
            contentDescription = "Monatliches KI-Limit"
            stateDescription = label.replace("$", "Dollar")
        },
    )
    Text(
        text = AiLimit.hint(value.toDouble()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * This month's spending: the amount large in serif ("0,84 $" with a quiet "von 5 $"), a labelled
 * bar that starts as a small accent stub, the request counts on their own line and, when some
 * requests ran with a model the price table does not know, that the sum is partly estimated.
 */
@Composable
private fun UsageMeter(usage: UsageSnapshot, limit: Double, reduceMotion: Boolean) {
    val fraction = usageFraction(usage.spentUsd, limit)
    val colors = MaterialTheme.colorScheme
    val fill = when {
        fraction == null -> colors.primary
        fraction >= 1f -> colors.error
        fraction >= WARN_AT -> StatusPalette.attentionFill()
        else -> colors.primary
    }
    val animated = remember { Animatable(0f) }
    LaunchedEffect(fraction, reduceMotion) {
        val target = fraction ?: 0f
        if (reduceMotion) animated.snapTo(target) else animated.animateTo(target, tween(900))
    }
    val track = if (isGlassDark()) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val estimated = estimatedNote(usage.estimatedRequests)
    Column(
        Modifier
            .fillMaxWidth()
            .lamina()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = (usageLine(usage.spentUsd, limit, usage.requests, usage.webSearches) + (estimated?.let { ". $it" } ?: ""))
                    .replace("$", "Dollar")
            },
    ) {
        Overline("Diesen Monat")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = usd(usage.spentUsd),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (limit > 0.0) "von ${usd(limit)}" else "ohne Limit",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (fraction != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(track, cornerRadius = r)
                        // Never an empty track that looks broken: a small stub marks the start.
                        val w = (size.width * animated.value).coerceAtLeast(UsageStub.toPx())
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(fill.copy(alpha = 0.75f), fill), endX = w.coerceAtLeast(1f)),
                            size = Size(w, size.height),
                            cornerRadius = r,
                        )
                    },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = usageCounts(usage.requests, usage.webSearches),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        if (estimated != null) {
            Text(
                text = estimated,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (fraction != null && fraction >= 1f) {
            Text(
                text = "Limit erreicht – bis zum Monatsende pausiert die KI (außer kostenlosen Anbietern).",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Width of the bar's start stub at 0 $. */
private val UsageStub = 8.dp

private const val WARN_AT = 0.8f
