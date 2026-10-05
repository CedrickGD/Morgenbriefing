package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark

/** 24-hour time picker for the notification time (dial or keyboard input) in a glass dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotifyTimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    var keyboard by remember { mutableStateOf(false) }
    val dark = isGlassDark()
    val dial = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val colors = TimePickerDefaults.colors(
        clockDialColor = dial,
        timeSelectorUnselectedContainerColor = dial,
        periodSelectorUnselectedContainerColor = dial,
    )

    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Wann soll dein Briefing kommen?",
        icon = Icons.Rounded.Schedule,
        buttons = {
            IconButton(onClick = { keyboard = !keyboard }) {
                Icon(
                    imageVector = if (keyboard) Icons.Rounded.Schedule else Icons.Rounded.Keyboard,
                    contentDescription = if (keyboard) "Uhr-Ansicht" else "Uhrzeit eintippen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Übernehmen", { onConfirm(state.hour, state.minute) })
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (keyboard) TimeInput(state = state, colors = colors) else TimePicker(state = state, colors = colors)
        }
    }
}

/**
 * Search dialog for a place (Open-Meteo geocoding). The query is debounced in the ViewModel;
 * this only renders [search] and reports typing and the choice.
 */
@Composable
internal fun PlacePickerDialog(
    target: PlaceTarget,
    current: Place,
    search: PlaceSearchState,
    onQueryChange: (String) -> Unit,
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = target.title,
        icon = Icons.Rounded.Place,
        buttons = { DialogButton("Abbrechen", onDismiss) },
    ) {
        // Inside the dialog's own composition, so the text field is attached when this runs.
        LaunchedEffect(focusRequester) {
            // Open the keyboard right away; harmless if the field cannot take focus yet.
            runCatching { focusRequester.requestFocus() }
        }
        Text(
            text = "Aktuell: ${current.displayLabel()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        OutlinedTextField(
            value = search.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text("Ort suchen, z. B. Leipzig") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (search.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Suche leeren")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Search,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = 4.dp),
        ) {
            if (search.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        PlaceResults(search = search, onPick = onPick)
    }
}

@Composable
private fun PlaceResults(search: PlaceSearchState, onPick: (Place) -> Unit) {
    val message = when {
        search.queryTooShort -> "Gib mindestens zwei Buchstaben ein."
        search.failed -> "Suche gerade nicht möglich – bist du online?"
        !search.loading && search.searchedFor == search.query.trim() && search.results.isEmpty() ->
            "Nichts gefunden für „${search.query.trim()}“."
        else -> null
    }
    if (message != null || search.results.isEmpty()) {
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    // The dialog body scrolls as a whole, so the (at most ten) results are a plain column.
    Column(Modifier.padding(top = 4.dp)) {
        search.results.forEach { place ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = "Auswählen") { onPick(place) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (place.region.isNotBlank()) {
                        Text(
                            text = place.region,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Enter or replace the GitHub token, with the short setup guide. "Speichern & testen" stores
 * the token first and then reports whether it can read the briefs repo.
 */
@Composable
internal fun TokenDialog(
    hasToken: Boolean,
    check: TokenCheck,
    onSaveAndTest: (String) -> Unit,
    onOpenTokenPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately not rememberSaveable: the secret should not end up in saved instance state.
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val checking = check == TokenCheck.Checking
    val canSave = token.isNotBlank() && !checking
    val message = tokenCheckMessage(check)

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (hasToken) "Token ändern" else "GitHub-Token eintragen",
        icon = Icons.Rounded.Key,
        dismissOnClickOutside = !checking,
        buttons = {
            if (check == TokenCheck.Success) {
                DialogButton("Fertig", onDismiss)
            } else {
                DialogButton("Abbrechen", onDismiss)
                DialogButton(if (checking) "Prüfe …" else "Speichern & testen", { onSaveAndTest(token) }, enabled = canSave)
            }
        },
    ) {
        Text(
            text = "So bekommst du einen Token (einmalig, ca. 2 Minuten):",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Steps(
            listOf(
                "GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token",
                "Repository access: „Only select repositories“ → Morgenbriefing",
                "Permissions → Contents: Read-only",
                "Token kopieren und hier einfügen",
            ),
        )
        Spacer(Modifier.size(12.dp))
        GlassPillButton("Token-Seite öffnen", onClick = onOpenTokenPage, icon = Icons.AutoMirrored.Rounded.OpenInNew, style = PillStyle.Quiet)
        Spacer(Modifier.size(14.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Token") },
            placeholder = { Text("github_pat_…") },
            singleLine = true,
            enabled = !checking,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSaveAndTest(token) }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Token verbergen" else "Token anzeigen",
                    )
                }
            },
            supportingText = { Text("Wird verschlüsselt nur auf diesem Gerät gespeichert.") },
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
                    color = when (check) {
                        TokenCheck.Success -> StatusPalette.ok()
                        TokenCheck.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** Confirmation before deleting the stored token. */
@Composable
internal fun RemoveTokenDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Token entfernen?",
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Entfernen", onConfirm, color = MaterialTheme.colorScheme.error)
        },
    ) {
        Text(
            "Danach zeigt die App wieder das Demo-Briefing. Deine gespeicherten Tage im Verlauf bleiben erhalten.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
