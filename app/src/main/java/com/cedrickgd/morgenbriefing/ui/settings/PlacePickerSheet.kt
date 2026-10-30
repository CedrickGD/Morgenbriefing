package com.cedrickgd.morgenbriefing.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.location.LocationIssue
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.GlassSheet
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import androidx.compose.foundation.interaction.MutableInteractionSource

/** Quick picks shown before the user types: big German cities. */
internal val QuickPlaces: List<Place> = listOf(
    Place("Berlin", 52.5244, 13.4105, "Berlin, Deutschland", "DE"),
    Place("Hamburg", 53.5753, 10.0153, "Hamburg, Deutschland", "DE"),
    Place("München", 48.1374, 11.5755, "Bayern, Deutschland", "DE"),
    Place("Köln", 50.9333, 6.95, "Nordrhein-Westfalen, Deutschland", "DE"),
    Place("Frankfurt am Main", 50.1155, 8.6842, "Hessen, Deutschland", "DE"),
    Place("Leipzig", 51.3397, 12.3731, "Sachsen, Deutschland", "DE"),
    Place("Dresden", 51.0509, 13.7383, "Sachsen, Deutschland", "DE"),
    Place("Stuttgart", 48.7823, 9.177, "Baden-Württemberg, Deutschland", "DE"),
)

/** 🇩🇪 for "DE"; empty for unknown codes. */
internal fun flagEmoji(countryCode: String): String {
    val code = countryCode.trim().uppercase()
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/**
 * The place picker as a glass sheet: a search field with live results (Open-Meteo geocoding),
 * "Mein Standort" (one coarse fix + the town name) and quick picks while nothing is typed. The
 * query is debounced in the ViewModel; this renders [search] and reports typing and the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlacePickerSheet(
    target: PlaceTarget,
    current: Place,
    search: PlaceSearchState,
    location: LocationState,
    onQueryChange: (String) -> Unit,
    onUseLocation: () -> Unit,
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denied as well: the lookup then fails with NO_PERMISSION and the sheet says so.
        onUseLocation()
    }
    val issue = location.issue

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = target.overline.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = target.question,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(14.dp))

            LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = GlassShapes.chip,
                placeholder = { Text("Ort suchen, z. B. Leipzig") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Suche leeren")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface.copy(alpha = if (isGlassDark()) 0.18f else 0.55f),
                    unfocusedContainerColor = colors.surface.copy(alpha = if (isGlassDark()) 0.12f else 0.4f),
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.results.firstOrNull()?.let(onPick) }),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(min = 4.dp),
            ) {
                if (search.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // "Mein Standort" + the place in use right now.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocationPill(
                    searching = location.searching,
                    onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                )
                CurrentPlaceChip(current)
            }
            AnimatedVisibility(visible = issue != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = when (issue) {
                        LocationIssue.NO_PERMISSION -> "Ohne Standortfreigabe geht das nicht – suche den Ort einfach oben."
                        LocationIssue.UNAVAILABLE -> "Der Standort ließ sich gerade nicht bestimmen. Ist er im System eingeschaltet?"
                        null -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(16.dp))
            when {
                search.queryTooShort -> {
                    Text(
                        text = "SCHNELL AUSGEWÄHLT",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickPlaces.forEach { place -> QuickPlaceChip(place, selected = place.sameSpotAs(current), onClick = { onPick(place) }) }
                    }
                }

                search.failed -> ResultsMessage("Suche gerade nicht möglich – bist du online?")

                !search.loading && search.searchedFor == search.query.trim() && search.results.isEmpty() ->
                    ResultsMessage("Nichts gefunden für „${search.query.trim()}“.")

                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    search.results.forEach { place -> PlaceResultRow(place, selected = place.sameSpotAs(current), onClick = { onPick(place) }) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LocationPill(searching: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .pressScale(interaction, pressedScale = 0.96f)
            .clip(CircleShape)
            .background(colors.primaryContainer)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = !searching, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimaryContainer)
        } else {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (searching) "Suche Standort …" else "Mein Standort",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/** "Aktuell: 🇩🇪 Leipzig" — quiet, so the search stays the main thing. */
@Composable
private fun CurrentPlaceChip(place: Place) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .lamina(CircleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Aktuell:", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        val flag = flagEmoji(place.countryCode)
        if (flag.isNotEmpty()) {
            Emoji(flag, size = 14.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(place.name, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickPlaceChip(place: Place, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .pressScale(interaction, pressedScale = 0.95f)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.primary) else Modifier.lamina(CircleShape, strong = true))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = place.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimary else colors.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaceResultRow(place: Place, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clip(RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.background(colors.primaryContainer.copy(alpha = 0.6f)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Auswählen", onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = if (isGlassDark()) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            val flag = flagEmoji(place.countryCode)
            if (flag.isNotEmpty()) Emoji(flag, size = 18.sp) else Icon(Icons.Rounded.Place, contentDescription = null, tint = colors.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.region.isNotBlank()) {
                Text(place.region, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ResultsMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
