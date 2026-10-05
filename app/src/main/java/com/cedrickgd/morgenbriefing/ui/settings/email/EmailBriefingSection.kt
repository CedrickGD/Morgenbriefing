package com.cedrickgd.morgenbriefing.ui.settings.email

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.data.EmailSettingsState
import com.cedrickgd.morgenbriefing.data.RemoveRecipientResult
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.ui.components.SectionLabel
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import java.time.Instant

/**
 * Settings section "E-Mail-Briefing": switch whether the brief also arrives by e-mail (~05:20,
 * sent from the user's Gmail by the e-mail routine) and edit the recipients. Every change is
 * written to `config/email.json` on the briefs branch right away.
 *
 * [onGoToToken] scrolls to the GitHub token row (shown when no token is stored); null hides
 * that button.
 */
@Composable
fun EmailBriefingSection(
    modifier: Modifier = Modifier,
    onGoToToken: (() -> Unit)? = null,
    viewModel: EmailSettingsViewModel = viewModel(factory = EmailSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val links = rememberLinkActions()
    val reduceMotion = rememberReduceMotion()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val actions = remember(viewModel, links, onGoToToken) {
        EmailBriefingActions(
            onToggle = viewModel::setEnabled,
            onAdd = viewModel::addRecipient,
            onRemove = viewModel::removeRecipient,
            onReload = viewModel::reload,
            onDismissError = viewModel::dismissError,
            onOpenTokenSettings = { links.open(EmailBriefingText.TOKEN_SETTINGS_URL) },
            onGoToToken = onGoToToken,
        )
    }
    EmailBriefingCard(state = state, actions = actions, reduceMotion = reduceMotion, modifier = modifier)
}

/** Callbacks of [EmailBriefingCard]; add/remove report back so the card can explain a refusal. */
internal class EmailBriefingActions(
    val onToggle: (Boolean) -> Unit,
    val onAdd: (String) -> RecipientCheck,
    val onRemove: (String) -> RemoveRecipientResult,
    val onReload: () -> Unit,
    val onDismissError: () -> Unit,
    val onOpenTokenSettings: () -> Unit,
    val onGoToToken: (() -> Unit)?,
)

/** Stateless glass card for [EmailBriefingSection]. */
@Composable
internal fun EmailBriefingCard(
    state: EmailSettingsState,
    actions: EmailBriefingActions,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val config = state.config
    val error = state.error
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (reduceMotion) {
                    Modifier
                } else {
                    Modifier.animateContentSize(
                        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold),
                    )
                },
            ),
    ) {
        CardHeader()
        Spacer(Modifier.height(8.dp))
        Text(
            text = EmailBriefingText.EXPLANATION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        EmailSwitchRow(config = config, onToggle = actions.onToggle)
        if (config != null) {
            Spacer(Modifier.height(12.dp))
            RecipientEditor(recipients = config.recipients, actions = actions)
        }
        EmailBriefingText.status(state)?.let { line ->
            Spacer(Modifier.height(12.dp))
            StatusRow(line)
        }
        if (EmailBriefingText.canReload(error)) {
            TextButton(onClick = actions.onReload, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EmailBriefingText.RELOAD)
            }
        }
        when (error) {
            EmailConfigError.NO_WRITE_PERMISSION -> {
                Spacer(Modifier.height(12.dp))
                WritePermissionCard(actions)
            }
            EmailConfigError.NO_TOKEN -> {
                Spacer(Modifier.height(12.dp))
                TokenHint(onGoToToken = actions.onGoToToken)
            }
            else -> Unit
        }
    }
}

@Composable
private fun CardHeader() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Email, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Text(
            text = EmailBriefingText.TITLE,
            style = typography.titleLarge.copy(fontFamily = typography.displaySmall.fontFamily),
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun EmailSwitchRow(config: EmailConfig?, onToggle: (Boolean) -> Unit) {
    val checked = config?.enabled == true
    val enabled = config != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassShapes.chip)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = EmailBriefingText.SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = EmailBriefingText.switchSubtitle(config),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The whole row toggles (one 56 dp target, one announcement); the switch only shows it.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RecipientEditor(recipients: List<String>, actions: EmailBriefingActions) {
    var input by rememberSaveable { mutableStateOf("") }
    var inputMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var listMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val limitReached = recipients.size >= EmailConfig.MAX_RECIPIENTS
    // A changed list answers any earlier "can't remove" hint.
    LaunchedEffect(recipients) { listMessage = null }

    val submit: () -> Unit = {
        val check = actions.onAdd(input)
        if (check == RecipientCheck.OK) {
            input = ""
            inputMessage = null
        } else {
            inputMessage = EmailBriefingText.recipientMessage(check)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(EmailBriefingText.RECIPIENTS_LABEL)
        if (recipients.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recipients.forEach { address ->
                    key(address) {
                        RecipientChip(
                            address = address,
                            onRemove = { listMessage = EmailBriefingText.removeMessage(actions.onRemove(address)) },
                        )
                    }
                }
            }
        }
        listMessage?.let { InlineHint(it) }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val supporting = inputMessage ?: if (limitReached) EmailBriefingText.limitMessage() else null
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    inputMessage = null
                },
                modifier = Modifier.weight(1f),
                enabled = !limitReached,
                label = { Text(EmailBriefingText.INPUT_LABEL) },
                placeholder = { Text(EmailBriefingText.INPUT_PLACEHOLDER) },
                isError = inputMessage != null,
                supportingText = supporting?.let { message ->
                    { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                },
                singleLine = true,
                shape = GlassShapes.chip,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            FilledTonalIconButton(
                onClick = submit,
                enabled = input.isNotBlank() && !limitReached,
                shape = GlassShapes.chip,
                // Aligned with the text field's outline (its label sits 8 dp above it).
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(56.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = EmailBriefingText.ADD_DESCRIPTION)
            }
        }
    }
}

@Composable
private fun RecipientChip(address: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = GlassShapes.chip,
        color = colors.primary.copy(alpha = 0.10f),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.24f)),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AlternateEmail,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 40 dp visual, 48 dp touch target (minimum interactive size).
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = EmailBriefingText.removeDescription(address),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun InlineHint(text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(line: EmailStatusLine) {
    val colors = MaterialTheme.colorScheme
    val textColor = when (line.tone) {
        StatusTone.SUCCESS -> colors.primary
        StatusTone.ERROR -> colors.error
        StatusTone.PROGRESS, StatusTone.HINT -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (line.tone) {
            StatusTone.PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            StatusTone.HINT -> Icon(Icons.Rounded.Info, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            StatusTone.ERROR -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp),
            )
            StatusTone.SUCCESS -> Unit // the text carries the ✓
        }
        Text(text = line.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}

/** 403/404 on write: the token is read-only — explain where to grant "Contents: Read and write". */
@Composable
private fun WritePermissionCard(actions: EmailBriefingActions) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        shape = GlassShapes.tile,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = EmailBriefingText.NO_WRITE_PERMISSION_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(text = EmailBriefingText.NO_WRITE_PERMISSION, style = MaterialTheme.typography.bodyMedium)
            Text(text = EmailBriefingText.NO_WRITE_PERMISSION_AFTER, style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = actions.onOpenTokenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(EmailBriefingText.OPEN_TOKEN_SETTINGS)
                }
                TextButton(onClick = actions.onDismissError, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(EmailBriefingText.ACKNOWLEDGE)
                }
            }
        }
    }
}

/** No token stored: point to the token row. */
@Composable
private fun TokenHint(onGoToToken: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Text(
                text = EmailBriefingText.errorMessage(EmailConfigError.NO_TOKEN),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onGoToToken != null) {
            FilledTonalButton(onClick = onGoToToken, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(EmailBriefingText.GO_TO_TOKEN)
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.5f

// ---------------------------------------------------------------------------------------------
// Previews (Android Studio only)
// ---------------------------------------------------------------------------------------------

@Preview(name = "Hell", showBackground = true, widthDp = 380)
@Preview(name = "Dunkel", showBackground = true, widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class EmailPreviews

private val previewActions = EmailBriefingActions(
    onToggle = {},
    onAdd = { RecipientCheck.OK },
    onRemove = { RemoveRecipientResult.REMOVED },
    onReload = {},
    onDismissError = {},
    onOpenTokenSettings = {},
    onGoToToken = {},
)

private val previewConfig = EmailConfig(
    enabled = true,
    recipients = listOf("anna@beispiel.de", "familie.mueller@posteo.de"),
    updatedAt = "2026-10-05T18:52:07+02:00",
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@EmailPreviews
@Composable
private fun EmailBriefingSavedPreview() {
    PreviewSurface {
        EmailBriefingCard(
            state = EmailSettingsState(config = previewConfig, sha = "abc", lastSavedAt = Instant.parse("2026-10-05T16:52:07Z")),
            actions = previewActions,
            reduceMotion = true,
        )
    }
}

@EmailPreviews
@Composable
private fun EmailBriefingErrorsPreview() {
    PreviewSurface {
        EmailBriefingCard(
            state = EmailSettingsState(config = previewConfig, error = EmailConfigError.NO_WRITE_PERMISSION),
            actions = previewActions,
            reduceMotion = true,
        )
        EmailBriefingCard(
            state = EmailSettingsState(error = EmailConfigError.NO_TOKEN),
            actions = previewActions,
            reduceMotion = true,
        )
        EmailBriefingCard(
            state = EmailSettingsState(config = EmailConfig.UNCONFIGURED.copy(enabled = true), saving = false),
            actions = previewActions,
            reduceMotion = true,
        )
    }
}
