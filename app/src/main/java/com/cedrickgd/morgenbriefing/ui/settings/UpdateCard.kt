package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
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
 * Settings → "App" → update: last check and the updater's current step (check, "Version x
 * verfügbar" with release notes, download progress, verification, permission, install). Needs
 * the GitHub token; without it only a hint is shown.
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
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (state.phase is UpdatePhase.Available) Icons.Rounded.NewReleases else Icons.Rounded.SystemUpdate,
                tint = if (state.phase is UpdatePhase.Available) StatusPalette.attention() else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Overline("App-Update")
                Text(
                    text = headline(state.phase, hasToken),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!hasToken) {
                Caption("Für Updates braucht die App deinen GitHub-Token – das Repo ist privat.")
                GlassPillButton("Zum GitHub-Token", onClick = actions.onGoToToken, icon = Icons.Rounded.Key, style = PillStyle.Tonal)
            } else {
                PhaseContent(state = state, lastCheck = lastCheck, reduceMotion = reduceMotion, actions = actions)
            }
        }
    }
}

private fun headline(phase: UpdatePhase, hasToken: Boolean): String = when {
    !hasToken -> "Updates brauchen den Token"
    else -> when (phase) {
        UpdatePhase.Idle -> "Nach neuen Versionen suchen"
        UpdatePhase.Checking -> "Suche nach Updates …"
        UpdatePhase.UpToDate -> "Du bist auf dem neuesten Stand"
        is UpdatePhase.Available -> "Version ${phase.release.version} ist da"
        is UpdatePhase.Downloading -> "Lade Version ${phase.release.version} …"
        is UpdatePhase.Verifying -> "Prüfe Paket und Signatur …"
        is UpdatePhase.NeedsPermission -> "Einmal erlauben, dann geht's weiter"
        is UpdatePhase.ReadyToInstall -> "Version ${phase.release.version} ist bereit"
        is UpdatePhase.Failed -> "Das hat nicht geklappt"
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            GlassPillButton("Nach Updates suchen", onClick = actions.onCheck, icon = Icons.Rounded.Refresh)
        }

        UpdatePhase.Checking -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
            GlassPillButton("Abbrechen", onClick = actions.onCancel, style = PillStyle.Quiet)
        }

        UpdatePhase.UpToDate -> {
            StatusLine(icon = Icons.Rounded.CheckCircle, text = "Morgenbriefing ${state.installedVersion} ist aktuell.", tint = StatusPalette.ok())
            Caption(lastCheck)
            GlassPillButton("Nochmal suchen", onClick = actions.onCheck, icon = Icons.Rounded.Refresh, style = PillStyle.Quiet)
        }

        is UpdatePhase.Available -> {
            ReleaseSummary(phase.release)
            GlassPillButton(
                "Herunterladen & installieren",
                onClick = { actions.onDownload(phase.release) },
                icon = Icons.Rounded.Download,
                style = PillStyle.Filled,
            )
            Caption(lastCheck)
        }

        is UpdatePhase.Downloading -> {
            DownloadProgressBar(phase, reduceMotion)
            GlassPillButton("Abbrechen", onClick = actions.onCancel, style = PillStyle.Quiet)
        }

        is UpdatePhase.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
        }

        is UpdatePhase.NeedsPermission -> {
            Caption(
                "Android fragt dafür einmal nach: Schalte „Aus dieser Quelle zulassen“ ein und komm " +
                    "zurück – die Installation geht dann direkt weiter.",
            )
            GlassPillButton("Erlauben", onClick = actions.onAllowInstall, style = PillStyle.Filled)
        }

        is UpdatePhase.ReadyToInstall -> {
            StatusLine(
                icon = Icons.Rounded.CheckCircle,
                text = if (phase.installerOpened) {
                    "Geprüft – bestätige die Installation im Android-Dialog."
                } else {
                    "Geladen und geprüft."
                },
                tint = StatusPalette.ok(),
            )
            GlassPillButton(
                if (phase.installerOpened) "Installation erneut öffnen" else "Jetzt installieren",
                onClick = { actions.onInstall(phase) },
                icon = Icons.Rounded.InstallMobile,
                style = PillStyle.Filled,
            )
        }

        is UpdatePhase.Failed -> {
            StatusLine(
                icon = Icons.Rounded.ErrorOutline,
                text = UpdateText.errorMessage(phase.error, debugBuild = BuildConfig.DEBUG),
                tint = colors.error,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val release = phase.release
                GlassPillButton(
                    "Nochmal versuchen",
                    onClick = { if (release != null) actions.onDownload(release) else actions.onCheck() },
                    icon = Icons.Rounded.Refresh,
                )
                GlassPillButton("Schließen", onClick = actions.onDismissError, style = PillStyle.Quiet)
            }
        }
    }
}

/** Date, size and a release-notes excerpt of an available version. */
@Composable
private fun ReleaseSummary(release: ReleaseInfo) {
    val meta = listOfNotNull(
        parseInstantOrNull(release.publishedAt)?.let { "vom ${Fmt.shortDate(it.atZone(BERLIN).toLocalDate())}" },
        release.apk.size.takeIf { it > 0 }?.let(UpdateText::megabytes),
    ).joinToString(" · ")
    if (meta.isNotEmpty()) Caption(meta)
    val excerpt = remember(release.notes) { UpdateText.notesExcerpt(release.notes) }
    if (excerpt.isNotEmpty()) {
        Text(
            text = excerpt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .lamina()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DownloadProgressBar(phase: UpdatePhase.Downloading, reduceMotion: Boolean) {
    val fraction = phase.progress
    val track = if (isGlassDark()) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round, trackColor = track)
        Caption(if (phase.received > 0) UpdateText.megabytes(phase.received) else "Verbinde …")
        return
    }
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 250),
        label = "downloadProgress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape),
        strokeCap = StrokeCap.Round,
        trackColor = track,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
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
private fun StatusLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        LiveText(text = text, color = MaterialTheme.colorScheme.onSurface)
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
