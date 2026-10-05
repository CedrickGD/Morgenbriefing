package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.update.ReleaseInfo
import com.cedrickgd.morgenbriefing.update.UpdateText
import com.cedrickgd.morgenbriefing.util.parseInstantOrNull
import java.time.Instant
import java.time.ZonedDateTime

/** Callbacks of [UpdateCard]. */
internal class UpdateCardActions(
    val onCheck: () -> Unit,
    val onDownload: (ReleaseInfo) -> Unit,
    val onCancel: () -> Unit,
    val onInstall: (UpdatePhase.ReadyToInstall) -> Unit,
    val onAllowInstall: () -> Unit,
    val onDismissError: () -> Unit,
    val onGoToToken: () -> Unit,
)

/**
 * Settings → "App-Update": installed version, last check, and the updater's current step
 * (check, "Version x verfügbar" with release notes, download progress, verification,
 * permission, install). Needs the GitHub token; without it only a hint is shown.
 */
@Composable
internal fun UpdateCard(
    state: UpdatePanelState,
    hasToken: Boolean,
    reduceMotion: Boolean,
    actions: UpdateCardActions,
    modifier: Modifier = Modifier,
) {
    val lastCheck = remember(state.lastCheckAt) {
        val at = state.lastCheckAt?.let { Instant.ofEpochMilli(it).atZone(BERLIN) }
        UpdateText.lastCheck(ZonedDateTime.now(BERLIN), at)
    }
    SettingsCard(modifier) {
        SettingsRow(
            icon = Icons.Rounded.SystemUpdate,
            title = "Installierte Version",
            subtitle = "Morgenbriefing ${state.installedVersion}",
        )
        SettingsDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hasToken) {
                NoTokenHint(onGoToToken = actions.onGoToToken)
            } else {
                PhaseContent(state = state, lastCheck = lastCheck, reduceMotion = reduceMotion, actions = actions)
            }
        }
    }
}

@Composable
private fun NoTokenHint(onGoToToken: () -> Unit) {
    StatusLine(
        icon = Icons.Rounded.Key,
        text = "Für Updates braucht die App deinen GitHub-Token – das Repo ist privat.",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FilledTonalButton(onClick = onGoToToken, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Zum GitHub-Token")
    }
}

@Composable
private fun PhaseContent(
    state: UpdatePanelState,
    lastCheck: String,
    reduceMotion: Boolean,
    actions: UpdateCardActions,
) {
    val colors = MaterialTheme.colorScheme
    when (val phase = state.phase) {
        UpdatePhase.Idle -> {
            Caption(lastCheck)
            CheckButton(onClick = actions.onCheck)
        }

        UpdatePhase.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                LiveText("Suche nach Updates …")
            }
            TextButton(onClick = actions.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") }
        }

        UpdatePhase.UpToDate -> {
            StatusLine(icon = Icons.Rounded.CheckCircle, text = "Du bist auf dem neuesten Stand", tint = colors.primary)
            Caption(lastCheck)
            CheckButton(onClick = actions.onCheck)
        }

        is UpdatePhase.Available -> {
            ReleaseSummary(phase.release)
            Button(onClick = { actions.onDownload(phase.release) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Herunterladen & installieren")
            }
            Caption(lastCheck)
        }

        is UpdatePhase.Downloading -> {
            LiveText("Lade Version ${phase.release.version} …")
            DownloadProgressBar(phase, reduceMotion)
            TextButton(onClick = actions.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") }
        }

        is UpdatePhase.Verifying -> {
            LiveText("Prüfe Paket und Signatur …")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is UpdatePhase.NeedsPermission -> {
            Text(
                text = "Einmalig erlauben, dass Morgenbriefing Updates installieren darf",
                style = MaterialTheme.typography.titleSmall,
            )
            Caption(
                "Android fragt dafür einmal nach: Schalte „Aus dieser Quelle zulassen“ ein und komm " +
                    "zurück – die Installation geht dann direkt weiter.",
            )
            Button(onClick = actions.onAllowInstall, modifier = Modifier.heightIn(min = 48.dp)) { Text("Erlauben") }
        }

        is UpdatePhase.ReadyToInstall -> {
            StatusLine(
                icon = Icons.Rounded.CheckCircle,
                text = if (phase.installerOpened) {
                    "Version ${phase.release.version} ist geprüft – bestätige die Installation im Android-Dialog."
                } else {
                    "Version ${phase.release.version} ist geladen und geprüft."
                },
                tint = colors.primary,
            )
            Button(onClick = { actions.onInstall(phase) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (phase.installerOpened) "Installation erneut öffnen" else "Jetzt installieren")
            }
        }

        is UpdatePhase.Failed -> {
            StatusLine(
                icon = Icons.Rounded.ErrorOutline,
                text = UpdateText.errorMessage(phase.error, debugBuild = BuildConfig.DEBUG),
                tint = colors.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val release = phase.release
                if (release != null) {
                    FilledTonalButton(onClick = { actions.onDownload(release) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Nochmal versuchen")
                    }
                } else {
                    FilledTonalButton(onClick = actions.onCheck, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Nochmal versuchen")
                    }
                }
                TextButton(onClick = actions.onDismissError, modifier = Modifier.heightIn(min = 48.dp)) { Text("Schließen") }
            }
        }
    }
}

/** "Version 1.2.0 verfügbar", date, size and a release-notes excerpt. */
@Composable
private fun ReleaseSummary(release: ReleaseInfo) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        LiveText(
            text = "Version ${release.version} verfügbar",
            style = MaterialTheme.typography.titleMedium,
            color = colors.primary,
        )
    }
    val meta = listOfNotNull(
        parseInstantOrNull(release.publishedAt)?.let { "vom ${Fmt.shortDate(it.atZone(BERLIN).toLocalDate())}" },
        release.apk.size.takeIf { it > 0 }?.let(UpdateText::megabytes),
    ).joinToString(" · ")
    if (meta.isNotEmpty()) Caption(meta)
    val excerpt = remember(release.notes) { UpdateText.notesExcerpt(release.notes) }
    if (excerpt.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = excerpt,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun DownloadProgressBar(phase: UpdatePhase.Downloading, reduceMotion: Boolean) {
    val fraction = phase.progress
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Caption(if (phase.received > 0) UpdateText.megabytes(phase.received) else "Verbinde …")
        return
    }
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 250),
        label = "downloadProgress",
    )
    LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = UpdateText.percent(fraction),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${UpdateText.megabytes(phase.received)} von ${UpdateText.megabytes(phase.total)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Nach Updates suchen")
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        LiveText(text = text, color = tint)
    }
}

@Composable
private fun LiveText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Caption(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
