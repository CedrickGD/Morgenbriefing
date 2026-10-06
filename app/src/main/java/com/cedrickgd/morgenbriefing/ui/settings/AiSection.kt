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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ai.UsageSnapshot
import com.cedrickgd.morgenbriefing.data.model.AiModel
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import kotlin.math.roundToInt

/** Callbacks of [AiCard]. */
internal class AiActions(
    val onEditKey: () -> Unit,
    val onModel: (AiModel) -> Unit,
    val onLimit: (Double) -> Unit,
    val onLiveAuto: (Boolean) -> Unit,
)

/**
 * Settings section 7 "KI" (docs/AI.md): what it does in one line, the Claude API key, the model,
 * the monthly cap with this month's usage and the live auto-refresh switch. On Android 7 only an
 * explanation is shown.
 */
@Composable
internal fun AiCard(
    settings: AppSettings,
    supported: Boolean,
    usage: UsageSnapshot,
    reduceMotion: Boolean,
    actions: AiActions,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SparkleBadge()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Overline("Claude · dein Erklärer")
                Text(
                    "Frag nach & ⚡ Live",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = "Frag Claude zu jedem Artikel und hol dir brandaktuelle Meldungen auf Knopfdruck – mit deinem eigenen API-Key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (!supported) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lamina()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Braucht Android 8 oder neuer – auf diesem Gerät bleiben die KI-Funktionen aus. Alles andere läuft wie gewohnt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            return@GlassCard
        }

        Spacer(Modifier.height(16.dp))
        KeyRow(hasKey = settings.hasAiKey, untested = settings.aiKeyUntested, onEdit = actions.onEditKey)

        Spacer(Modifier.height(22.dp))
        Overline("Modell")
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelOption(
                title = "Claude Sonnet 5.5",
                subtitle = "Schnell & günstig",
                badge = "Standard",
                selected = settings.aiModel == AiModel.SONNET,
                onSelect = { actions.onModel(AiModel.SONNET) },
            )
            ModelOption(
                title = "Claude Opus 5.5",
                subtitle = "Maximale Qualität, etwa doppelter Preis",
                badge = null,
                selected = settings.aiModel == AiModel.OPUS,
                onSelect = { actions.onModel(AiModel.OPUS) },
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

@Composable
private fun KeyRow(hasKey: Boolean, untested: Boolean, onEdit: () -> Unit) {
    // Green only for a key that passed the test or answered a request.
    val works = hasKey && !untested
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lamina()
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Key,
            contentDescription = null,
            tint = if (works) StatusPalette.ok() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text("API-Key", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = aiKeyRowLabel(hasKey, untested),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (works) StatusPalette.ok() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassPillButton(
            text = if (hasKey) "Ändern" else "Eintragen",
            onClick = onEdit,
            style = if (hasKey) PillStyle.Tonal else PillStyle.Filled,
            contentDescription = if (hasKey) "Claude-API-Key ändern" else "Claude-API-Key eintragen",
        )
    }
}

/** A selectable lamina card for one model, with a radio dot. */
@Composable
private fun ModelOption(title: String, subtitle: String, badge: String?, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberToggleHaptics()
    val ring by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "modelRing")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lamina(GlassShapes.tile, strong = selected)
            .border(2.dp, colors.primary.copy(alpha = ring), GlassShapes.tile)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) haptics(true)
                    onSelect()
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected = selected, progress = ring)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                    color = colors.onSurface,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = if (isGlassDark()) 0.2f else 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, progress: Float) {
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
 * bar that starts as a small accent stub, and the request counts on their own line.
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
    Column(
        Modifier
            .fillMaxWidth()
            .lamina()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = usageLine(usage.spentUsd, limit, usage.requests, usage.webSearches).replace("$", "Dollar")
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
        if (fraction != null && fraction >= 1f) {
            Text(
                text = "Limit erreicht – bis zum Monatsende pausiert die KI.",
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

/**
 * "Claude-API-Key eintragen/ändern": short steps, link to the Anthropic console, a password
 * field with show/hide, "Speichern & testen" (only a working key is stored; offline also
 * "Ohne Test speichern", which says so) and, for an existing key and a non-null [onRemove],
 * "Key entfernen".
 */
@Composable
internal fun AiKeyDialog(
    hasKey: Boolean,
    state: AiKeyState,
    onSaveAndTest: (key: String, skipTest: Boolean) -> Unit,
    onOpenConsole: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // Deliberately not rememberSaveable: the secret should not end up in saved instance state.
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val checking = state == AiKeyState.Checking
    val saved = state == AiKeyState.Saved || state == AiKeyState.SavedUntested
    val canSave = key.isNotBlank() && !checking
    val message = aiKeyMessage(state)
    val colors = MaterialTheme.colorScheme

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (hasKey) "Claude-API-Key ändern" else "Claude-API-Key eintragen",
        icon = Icons.Rounded.Key,
        dismissOnClickOutside = !checking,
        buttons = {
            if (saved) {
                DialogButton("Fertig", onDismiss)
            } else {
                DialogButton("Abbrechen", onDismiss)
                DialogButton(
                    text = if (checking) "Prüfe …" else "Speichern & testen",
                    onClick = { onSaveAndTest(key, false) },
                    enabled = canSave,
                )
            }
        },
    ) {
        Text(
            "Der Key gehört dir: Anfragen laufen über dein eigenes Anthropic-Konto, unabhängig von einem Claude-Abo.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Steps(
            listOf(
                "console.anthropic.com öffnen und anmelden",
                "„API Keys“ → „Create Key“",
                "Key kopieren und hier einfügen",
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Tipp: Setz dir dort unter „Limits“ ein Ausgabenlimit – so wird nie mehr abgebucht, als du willst.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .lamina()
                .padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        GlassPillButton(
            text = "console.anthropic.com öffnen",
            onClick = onOpenConsole,
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            style = PillStyle.Quiet,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-Key") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            enabled = !checking,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSaveAndTest(key, false) }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Key verbergen" else "Key anzeigen",
                    )
                }
            },
            supportingText = {
                Text(
                    if (key.isNotBlank() && !looksLikeAnthropicKey(key)) {
                        "Claude-Keys beginnen mit „sk-ant-“ – stimmt die Kopie?"
                    } else {
                        "Wird verschlüsselt nur auf diesem Gerät gespeichert."
                    },
                )
            },
        )
        if (message != null) {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state) {
                        AiKeyState.Saved -> StatusPalette.ok()
                        AiKeyState.Checking, AiKeyState.SavedUntested -> colors.onSurfaceVariant
                        else -> colors.error
                    },
                )
            }
        }
        if (state == AiKeyState.Failed(AiKeyProblem.NETWORK) && key.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            GlassPillButton(text = "Ohne Test speichern", onClick = { onSaveAndTest(key, true) }, style = PillStyle.Quiet)
        }
        if (hasKey && onRemove != null && !saved) {
            Spacer(Modifier.height(8.dp))
            DialogButton("Gespeicherten Key entfernen", onRemove, color = colors.error)
        }
    }
}

/** Numbered steps with accent numerals. */
@Composable
internal fun Steps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isGlassDark()) 0.25f else 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** Confirmation before deleting the stored Claude key. */
@Composable
internal fun RemoveAiKeyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Key entfernen?",
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Entfernen", onConfirm, color = MaterialTheme.colorScheme.error)
        },
    ) {
        Text(
            "Danach sind „Frag nach“ und „⚡ Live“ aus, bis du wieder einen Key einträgst. Dein bisheriger Verbrauch bleibt sichtbar.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
