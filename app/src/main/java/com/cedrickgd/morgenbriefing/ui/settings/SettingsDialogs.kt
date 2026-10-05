package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** 24-hour time picker for the notification time (dial or keyboard input). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotifyTimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    var displayMode by remember { mutableStateOf(TimePickerDisplayMode.Picker) }

    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Wann soll dein Briefing kommen?",
                style = MaterialTheme.typography.labelLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Übernehmen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
        modeToggleButton = {
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = {
                    displayMode = if (displayMode == TimePickerDisplayMode.Picker) {
                        TimePickerDisplayMode.Input
                    } else {
                        TimePickerDisplayMode.Picker
                    }
                },
                displayMode = displayMode,
            )
        },
    ) {
        if (displayMode == TimePickerDisplayMode.Picker) TimePicker(state = state) else TimeInput(state = state)
    }
}

/**
 * Search dialog for a place (Open-Meteo geocoding). The query is debounced in the ViewModel;
 * this only renders [search] and reports typing and the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    BasicAlertDialog(onDismissRequest = onDismiss) {
        // Inside the dialog's own composition, so the text field is attached when this runs.
        LaunchedEffect(focusRequester) {
            // Open the keyboard right away; harmless if the field cannot take focus yet.
            runCatching { focusRequester.requestFocus() }
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = "Aktuell: ${current.displayLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
                )
                OutlinedTextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
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
                    if (search.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        )
                    }
                }

                PlaceResults(search = search, onPick = onPick)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                }
            }
        }
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
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
        items(search.results, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Auswählen") { onPick(place) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasToken) "Token ändern" else "GitHub-Token eintragen") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "So bekommst du einen Token (einmalig, ca. 2 Minuten):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TokenSteps()
                TextButton(onClick = onOpenTokenPage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Token-Seite öffnen")
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token") },
                    placeholder = { Text("github_pat_…") },
                    singleLine = true,
                    enabled = !checking,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
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
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (check) {
                            TokenCheck.Success -> MaterialTheme.colorScheme.primary
                            TokenCheck.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            if (check == TokenCheck.Success) {
                TextButton(onClick = onDismiss) { Text("Fertig") }
            } else {
                TextButton(onClick = { onSaveAndTest(token) }, enabled = canSave) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Speichern & testen")
                }
            }
        },
        dismissButton = {
            if (check != TokenCheck.Success) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun TokenSteps() {
    val steps = listOf(
        "GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token",
        "Repository access: „Only select repositories“ → Morgenbriefing",
        "Permissions → Contents: Read-only",
        "Token kopieren und hier einfügen",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Row {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp),
                )
                Text(text = step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Confirmation before deleting the stored token. */
@Composable
internal fun RemoveTokenDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token entfernen?") },
        text = {
            Text("Danach zeigt die App wieder das Demo-Briefing. Deine gespeicherten Tage im Verlauf bleiben erhalten.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Entfernen", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
