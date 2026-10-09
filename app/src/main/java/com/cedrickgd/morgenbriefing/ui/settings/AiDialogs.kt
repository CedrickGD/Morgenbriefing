package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ai.KeyExtras
import com.cedrickgd.morgenbriefing.ai.compat.CustomServer
import com.cedrickgd.morgenbriefing.ai.compat.CustomUrlIssue
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import com.cedrickgd.morgenbriefing.data.normalizeBaseUrl
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark

/** Where Anthropic lists the workspaces and their ids. */
private const val ANTHROPIC_WORKSPACES_URL = "https://platform.claude.com/settings/workspaces"

/** The two Gemini key shapes, the prefixes in monospace: "AIza" never reads as "Alza". */
private val GEMINI_KEY_SHAPES: AnnotatedString = buildAnnotatedString {
    val code = SpanStyle(fontFamily = FontFamily.Monospace)
    append("Neue Keys beginnen mit „")
    withStyle(code) { append("AQ.") }
    append("“, ältere mit „")
    withStyle(code) { append("AIza") }
    append("“ – beide gehen.")
}

/**
 * The key dialog of one provider (docs/AI-PROVIDERS.md §10.3): whose account the requests run on,
 * how to get a key (Gemini: the short AI Studio guide and the "Tarif" choice), a link to the
 * console, a password field with show/hide and a format hint, and "Speichern & testen" — only a
 * key that works is stored; offline also "Ohne Test speichern", which says so. Claude has the
 * workspace id under "Erweitert", the own server an address field and an optional key. With a
 * stored key the field may stay blank: then the stored key is tested with the new settings.
 * [onRemove] (settings only) offers removing what is stored.
 */
@Composable
internal fun AiKeyDialog(
    spec: ProviderSpec,
    stored: ProviderSettings,
    state: AiKeyState,
    onSave: (KeySubmission) -> Unit,
    onOpenLink: (String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val custom = spec.id == AiProviderId.CUSTOM
    val anthropic = spec.id == AiProviderId.ANTHROPIC
    val gemini = spec.id == AiProviderId.GEMINI
    val configured = stored.hasKey || (custom && !stored.baseUrl.isNullOrBlank())
    // Deliberately not rememberSaveable: the secret should not end up in saved instance state.
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(stored.baseUrl.orEmpty()) }
    var workspace by rememberSaveable { mutableStateOf(stored.workspaceId.orEmpty()) }
    var tier by rememberSaveable { mutableStateOf(stored.geminiTier) }
    var advanced by rememberSaveable { mutableStateOf(!stored.workspaceId.isNullOrBlank()) }
    LaunchedEffect(state) {
        if (state == AiKeyState.Failed(AiKeyProblem.WORKSPACE_REQUIRED) || state == AiKeyState.Failed(AiKeyProblem.WORKSPACE_INVALID)) {
            advanced = true
        }
    }
    val checking = state == AiKeyState.Checking
    val saved = state.isSaved
    val addressIssue = if (custom) CustomServer.issue(address) else null
    val canSave = !checking && if (custom) addressIssue == null else key.isNotBlank() || stored.hasKey
    val message = aiKeyMessage(state, spec)
    val colors = MaterialTheme.colorScheme
    // The check's line sits under the key field: with the Gemini tariff texts above it, it can be
    // below the fold when "Speichern & testen" was tapped — scrolled into view once it changes.
    val messageInView = remember { BringIntoViewRequester() }
    LaunchedEffect(state) {
        if (state != AiKeyState.Idle) {
            withFrameNanos { }
            messageInView.bringIntoView()
        }
    }

    fun submit(skipTest: Boolean) {
        onSave(
            KeySubmission(
                key = key,
                skipTest = skipTest,
                extras = KeyExtras(
                    workspaceId = workspace.trim().takeIf { anthropic },
                    baseUrl = address.trim().takeIf { custom },
                ),
                geminiTier = tier.takeIf { gemini },
            ),
        )
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = aiKeyDialogTitle(spec, configured),
        icon = if (custom) Icons.Rounded.Dns else Icons.Rounded.Key,
        dismissOnClickOutside = !checking,
        buttons = {
            if (saved) {
                DialogButton("Fertig", onDismiss)
            } else {
                DialogButton("Abbrechen", onDismiss)
                DialogButton(
                    text = when {
                        checking -> "Prüfe …"
                        custom -> "Verbinden & speichern"
                        else -> "Speichern & testen"
                    },
                    onClick = { submit(skipTest = false) },
                    enabled = canSave,
                )
            }
        },
    ) {
        Text(aiKeyIntro(spec), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        KeyGuide(spec, onOpenLink)
        if (gemini) {
            Spacer(Modifier.height(18.dp))
            Overline("Tarif")
            Spacer(Modifier.height(8.dp))
            GeminiTierChoice(selected = tier, onSelect = { tier = it }, compact = true, enabled = !checking && !saved)
        }
        if (custom) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server-Adresse") },
                placeholder = { Text("http://nas.fritz.box:11434/v1") },
                singleLine = true,
                enabled = !checking,
                isError = addressIssue != null && addressIssue != CustomUrlIssue.BLANK,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                supportingText = {
                    Text(addressIssue?.let(CustomServer::hint) ?: "Ollama nutzt Port 11434, LM Studio Port 1234.")
                },
            )
        }
        Spacer(Modifier.height(if (custom) 4.dp else 14.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (custom) "API-Key (optional)" else "API-Key") },
            placeholder = { Text(spec.keyPlaceholder) },
            singleLine = true,
            enabled = !checking,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) submit(skipTest = false) }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Key verbergen" else "Key anzeigen",
                    )
                }
            },
            supportingText = {
                val issue = if (key.isNotBlank()) aiKeyFormatIssue(key, spec) else null
                Text(
                    when {
                        issue != null -> aiKeyFormatHint(issue, spec)
                        key.isBlank() && stored.hasKey -> "Leer lassen, um den gespeicherten Key zu behalten."
                        custom -> "Nur nötig, wenn dein Server einen verlangt. Wird verschlüsselt gespeichert."
                        else -> "Wird verschlüsselt nur auf diesem Gerät gespeichert."
                    },
                )
            },
        )
        if (anthropic) {
            WorkspaceSection(
                expanded = advanced,
                onToggle = { advanced = !advanced },
                workspace = workspace,
                onWorkspace = { workspace = it },
                enabled = !checking,
                onOpenWorkspaces = { onOpenLink(ANTHROPIC_WORKSPACES_URL) },
            )
        }
        if (message != null) {
            Row(
                modifier = Modifier
                    .bringIntoViewRequester(messageInView)
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
                        AiKeyState.Saved, is AiKeyState.ServerConnected -> StatusPalette.ok()
                        AiKeyState.SavedNoCredit -> StatusPalette.attention()
                        AiKeyState.Checking, AiKeyState.SavedUntested -> colors.onSurfaceVariant
                        else -> colors.error
                    },
                )
            }
        }
        if (canSaveUntested(state) && (key.isNotBlank() || custom)) {
            Spacer(Modifier.height(8.dp))
            GlassPillButton(text = "Ohne Test speichern", onClick = { submit(skipTest = true) }, style = PillStyle.Quiet)
        }
        if (configured && onRemove != null && !saved) {
            Spacer(Modifier.height(8.dp))
            DialogButton(if (custom) "Server entfernen" else "Gespeicherten Key entfernen", onRemove, color = colors.error)
        }
    }
}

/**
 * "So bekommst du den Key": Gemini gets the short AI Studio path in one line (G2), every other
 * provider its numbered steps; then the tip and the link to the console.
 */
@Composable
private fun KeyGuide(spec: ProviderSpec, onOpenLink: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (spec.id != AiProviderId.CUSTOM) {
        Overline("So bekommst du den Key")
        Spacer(Modifier.height(8.dp))
    }
    if (spec.id == AiProviderId.GEMINI) {
        Text(
            GeminiTiers.KEY_GUIDE,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .lamina()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            GEMINI_KEY_SHAPES,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    } else {
        Steps(spec.keySteps)
    }
    spec.keyTip?.let { tip ->
        Spacer(Modifier.height(12.dp))
        Text(
            tip,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .lamina()
                .padding(12.dp),
        )
    }
    val url = spec.consoleUrl
    val label = spec.consoleLabel
    if (url != null && label != null) {
        Spacer(Modifier.height(12.dp))
        GlassPillButton(
            text = label,
            onClick = { onOpenLink(url) },
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            style = PillStyle.Quiet,
        )
    }
}

/**
 * The Gemini "Tarif" (G1): "Kostenlos" (the default of a new setup) or "Mit Abrechnung", each with
 * its one-line explanation, then what Google may do on the free tier and, with billing, the free
 * search allowance. [compact]: inside the key dialog (smaller rows); [enabled] false while the key
 * is tested and once it is stored (a change then would not be saved).
 */
@Composable
internal fun GeminiTierChoice(selected: GeminiTier, onSelect: (GeminiTier) -> Unit, compact: Boolean = false, enabled: Boolean = true) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GeminiTier.entries.forEach { tier ->
            ChoiceRow(
                title = GeminiTiers.label(tier),
                subtitle = geminiTierSubtitle(tier),
                selected = tier == selected,
                onSelect = { onSelect(tier) },
                enabled = enabled,
                compact = compact,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(GeminiTiers.PRIVACY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (selected == GeminiTier.PAID) {
        Spacer(Modifier.height(4.dp))
        Text(GeminiTiers.SEARCH_ALLOWANCE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Erweitert: Workspace-ID" of the Claude key dialog, collapsed unless an id is stored or needed. */
@Composable
private fun WorkspaceSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    workspace: String,
    onWorkspace: (String) -> Unit,
    enabled: Boolean,
    onOpenWorkspaces: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Zuklappen" else "Aufklappen", onClick = onToggle)
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "aufgeklappt" else "zugeklappt" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Erweitert: Workspace-ID", style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant)
    }
    AnimatedVisibility(visible = expanded) {
        Column {
            Text(
                "Nur nötig, wenn dein Key keinem Workspace zugeordnet ist. Die ID steht in der Konsole unter " +
                    "Settings → Workspaces (Spalte „ID“).",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = workspace,
                onValueChange = onWorkspace,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Workspace-ID") },
                placeholder = { Text("wrkspc_…") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                supportingText = workspaceIdHint(workspace)?.let { hint -> { Text(hint) } },
            )
            GlassPillButton(
                text = "Workspaces öffnen",
                onClick = onOpenWorkspaces,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                style = PillStyle.Quiet,
            )
        }
    }
}

/**
 * "Eigenes Modell …": a model id exactly as the provider writes it. A model the price table does
 * not know is allowed, but the dialog says its price is estimated. [blockedHint] names why an id
 * cannot be taken (a Gemini Pro id with a free key): it is shown instead, and "Übernehmen" waits.
 */
@Composable
internal fun CustomModelDialog(
    spec: ProviderSpec,
    current: String?,
    priceHint: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    blockedHint: (String) -> String? = { null },
) {
    var model by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val clean = model.trim()
    val blocked = blockedHint(clean)
    val canConfirm = clean.isNotEmpty() && clean.none { it.isWhitespace() } && blocked == null
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Eigenes Modell",
        icon = Icons.Rounded.Tune,
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Übernehmen", { onConfirm(clean) }, enabled = canConfirm)
        },
    ) {
        Text(
            "Modell-ID, genau wie bei ${if (spec.id == AiProviderId.CUSTOM) "deinem Server" else spec.name}, z. B. ${customModelExample(spec.id)}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it.take(AiSettings.MAX_MODEL_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Modell-ID") },
            placeholder = { Text(customModelExample(spec.id)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(clean) }),
            isError = blocked != null,
            supportingText = (blocked ?: priceHint(clean))?.let { hint -> { Text(hint) } },
        )
    }
}

/**
 * Debug builds only (G5): points any provider at a test server instead of its real API, e.g. the
 * mock server of `tools/mock-ai-server.mjs` on the host machine (`10.0.2.2` from the emulator) —
 * one field per provider, so a separate Live provider can be redirected too, and „Alle auf
 * Mock-Server“ for a whole QA pass. Release builds never show this and ignore anything stored.
 * [onSave] gets every provider's value (null = the real API).
 */
@Composable
internal fun DebugBaseUrlDialog(
    ai: AiSettings,
    onSave: (Map<AiProviderId, String?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val urls = remember {
        mutableStateMapOf<AiProviderId, String>().apply {
            debugBaseUrlProviders.forEach { put(it, ai.of(it).debugBaseUrl.orEmpty()) }
        }
    }
    fun valid(id: AiProviderId): Boolean = urls[id].orEmpty().trim().let { it.isEmpty() || normalizeBaseUrl(it) != null }
    val allValid = debugBaseUrlProviders.all(::valid)
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Entwickler: Basis-URL",
        icon = Icons.Rounded.Code,
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton(
                "Speichern",
                { onSave(debugBaseUrlProviders.associateWith { urls[it].orEmpty().trim().ifEmpty { null } }) },
                enabled = allValid,
            )
        },
    ) {
        Text(
            "Leitet Anfragen auf einen Testserver um, z. B. den Mock-Server aus tools/mock-ai-server.mjs. " +
                "Leer = echte API. Nur in Debug-Builds – Release-Builds ignorieren die Einstellung.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPillButton(
                text = "Alle auf Mock-Server",
                onClick = { debugBaseUrlProviders.forEach { urls[it] = debugBaseUrlExample(it) } },
                style = PillStyle.Quiet,
            )
            GlassPillButton(text = "Alle leeren", onClick = { debugBaseUrlProviders.forEach { urls[it] = "" } }, style = PillStyle.Quiet)
        }
        Spacer(Modifier.height(6.dp))
        debugBaseUrlProviders.forEach { id ->
            val spec = ProviderCatalog.spec(id)
            OutlinedTextField(
                value = urls[id].orEmpty(),
                onValueChange = { urls[id] = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                label = { Text(spec.name) },
                placeholder = { Text(debugBaseUrlExample(id)) },
                singleLine = true,
                isError = !valid(id),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                supportingText = if (valid(id)) null else ({ Text("Keine gültige http(s)-Adresse.") }),
            )
        }
        Text(
            "Unverschlüsselt nur für 10.0.2.2, localhost und lokale Namen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Confirmation before deleting what is stored for [spec] (key, or the own server). */
@Composable
internal fun RemoveAiKeyDialog(spec: ProviderSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val custom = spec.id == AiProviderId.CUSTOM
    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (custom) "Server entfernen?" else "Key entfernen?",
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Entfernen", onConfirm, color = MaterialTheme.colorScheme.error)
        },
    ) {
        Text(
            "Danach sind „Frag nach“ und „⚡ Live“ mit ${spec.dativeName} aus, bis du wieder " +
                "${if (custom) "einen Server einrichtest" else "einen Key einträgst"}. Dein bisheriger Verbrauch bleibt sichtbar.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
