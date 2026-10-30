package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NewLabel
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.news.Region
import com.cedrickgd.morgenbriefing.data.news.Regions
import com.cedrickgd.morgenbriefing.ui.components.Emoji
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

/** Colors offered for an own topic (ARGB seeds), a calm palette that works on every sky. */
internal val TopicSeeds: List<Long> = listOf(
    0xFF7B61FF, 0xFF2E8B57, 0xFF2F6FDE, 0xFF0F9D9A, 0xFFC6613F, 0xFFE0A100,
    0xFF3949AB, 0xFFD81B60, 0xFF43A047, 0xFF8E24AA, 0xFF5F6B7A, 0xFF00897B,
)

/**
 * Create or edit an own topic: name, an emoji and one of [TopicSeeds]. The sources come after,
 * in "Quellen".
 *
 * @param topic the topic to edit, or null for a new one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomTopicDialog(
    topic: Topic?,
    onConfirm: (title: String, emoji: String, seed: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(topic?.id) { mutableStateOf(topic?.title.orEmpty()) }
    var emoji by rememberSaveable(topic?.id) { mutableStateOf(topic?.emoji ?: "📰") }
    var seed by rememberSaveable(topic?.id) { mutableStateOf(topic?.seed ?: TopicSeeds.first()) }
    val canSave = title.isNotBlank()

    GlassDialog(
        onDismissRequest = onDismiss,
        title = if (topic == null) "Eigenes Thema" else "Thema bearbeiten",
        icon = Icons.Rounded.NewLabel,
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton(if (topic == null) "Anlegen" else "Speichern", { onConfirm(title, emoji, seed) }, enabled = canSave)
        },
    ) {
        Text(
            text = "Ein Thema ist ein Stapel von Quellen: Füge danach unter „Quellen“ die Feeds hinzu, die hier reinsollen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = emoji,
                onValueChange = { emoji = it.take(4) },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                label = { Text("Emoji") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(40) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("z. B. Fußball, Börse, Leipzig") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm(title, emoji, seed) }),
            )
        }
        Spacer(Modifier.size(16.dp))
        Overline("Farbe")
        Spacer(Modifier.size(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            TopicSeeds.forEach { candidate ->
                val selected = candidate == seed
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(candidate))
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.5f), CircleShape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { seed = candidate })
                        .semantics { contentDescription = "Farbe" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * "Quelle hinzufügen" to [topic]: paste a feed URL or just the newsroom's website — the app
 * looks for the feed and shows what it found before saving.
 */
@Composable
internal fun AddFeedDialog(
    topic: Topic,
    state: AddFeedState,
    onLookUp: (String) -> Unit,
    onSave: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable(topic.id) { mutableStateOf("") }
    var name by rememberSaveable(topic.id) { mutableStateOf("") }
    val found = state as? AddFeedState.Found
    LaunchedEffect(found) { if (found != null && name.isBlank()) name = found.title }
    LaunchedEffect(state) { if (state == AddFeedState.Saved) onDismiss() }
    val checking = state == AddFeedState.Checking

    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Quelle für „${topic.title}“",
        icon = Icons.Rounded.AddLink,
        dismissOnClickOutside = !checking,
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            if (found != null) {
                DialogButton("Hinzufügen", { onSave(name) })
            } else {
                DialogButton(if (checking) "Suche …" else "Feed suchen", { onLookUp(input) }, enabled = input.isNotBlank() && !checking)
            }
        },
    ) {
        Text(
            text = "Die Adresse eines RSS- oder Atom-Feeds – oder einfach die Website der Redaktion, dann sucht die App den Feed selbst.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !checking && found == null,
            label = { Text("Adresse") },
            placeholder = { Text("z. B. tagesschau.de oder …/feed.xml") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (input.isNotBlank()) onLookUp(input) }),
        )
        Spacer(Modifier.size(10.dp))
        when (state) {
            AddFeedState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Suche den Feed …", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is AddFeedState.Failed -> Text(
                text = "✗ ${state.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            is AddFeedState.Found -> {
                Text(
                    text = "✓ Gefunden: ${state.title} · ${state.itemCount} Meldungen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusPalette.ok(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name der Quelle") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(name) }),
                )
            }

            AddFeedState.Idle, AddFeedState.Saved -> Unit
        }
    }
}

/** Pick the Bundesland behind the "Region" topic. */
@Composable
internal fun RegionPickerDialog(current: String, onPick: (Region) -> Unit, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "Deine Region",
        icon = Icons.Rounded.Map,
        buttons = { DialogButton("Fertig", onDismiss) },
    ) {
        Text(
            text = "Regionale Nachrichten kommen von den öffentlich-rechtlichen Sendern deines Bundeslandes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(Modifier.selectableGroup()) {
            Regions.ALL.forEach { region ->
                val selected = region.key == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(region) })
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(region.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = region.feeds.joinToString(" · ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Confirmation before deleting an own topic with its sources. */
@Composable
internal fun DeleteTopicDialog(topic: Topic, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = "„${topic.title}“ löschen?",
        buttons = {
            DialogButton("Abbrechen", onDismiss)
            DialogButton("Löschen", onConfirm, color = MaterialTheme.colorScheme.error)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Emoji(topic.emoji.ifBlank { "📰" }, size = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text("Das Thema und seine Quellen werden entfernt. Die eingebauten Themen bleiben.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A simple row that opens [onClick]: label, value and a chevron-like arrow. */
@Composable
internal fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Ändern", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
