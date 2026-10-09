package com.cedrickgd.morgenbriefing.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.ai.UsageSnapshot
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import com.cedrickgd.morgenbriefing.update.ApkAsset
import com.cedrickgd.morgenbriefing.update.ReleaseInfo
import com.cedrickgd.morgenbriefing.update.UpdateError
import com.cedrickgd.morgenbriefing.util.SystemStatus
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

// Previews of the settings building blocks in their interesting states (Android Studio only).
// Without the app's sky (no Haze state) the glass falls back to its frosted tint.

@Preview(name = "Hell", showBackground = true, widthDp = 400)
@Preview(name = "Dunkel", showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ThemePreviews

private val previewRelease = ReleaseInfo(
    tag = "v1.2.0",
    version = "1.2.0",
    title = "Morgenbriefing 1.2.0",
    notes = "## Neu\n- Glas-Design\n- Frag nach zu jedem Artikel\n- ⚡ Live-Nachrichten",
    publishedAt = "2026-10-05T17:12:00Z",
    apk = ApkAsset(id = 1, name = "Morgenbriefing-v1.2.0.apk", size = 12_400_000),
)

private val noUpdateActions = UpdateCardActions(
    onCheck = {},
    onDownload = {},
    onCancel = {},
    onInstall = {},
    onAllowInstall = {},
    onDismissError = {},
    onGoToToken = {},
)

private val previewNow: ZonedDateTime = ZonedDateTime.of(2026, 10, 5, 20, 30, 0, 0, ZoneId.of("Europe/Berlin"))

@Composable
private fun PreviewSky(content: @Composable () -> Unit) {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier
                .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.background)))
                .padding(16.dp),
        ) { content() }
    }
}

@ThemePreviews
@Composable
private fun StatusHeroPreview() {
    val status = SystemStatus(
        permissionGranted = true,
        appNotificationsEnabled = true,
        morningChannelBlocked = false,
        exactAlarmRelevant = true,
        exactAlarmsAllowed = false,
        batteryUnrestricted = true,
    )
    val items = statusItems(status, SourceStatus.Connected(0L), aiSupported = true, hasAiKey = false)
    PreviewSky {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusHero(
                hero = heroText(enabled = true, items = items, day = "morgen", time = "05:30"),
                enabled = true,
                items = items,
                reduceMotion = true,
                onPill = {},
                onOpenAppDetails = {},
            )
            BriefingCard(
                settings = AppSettings(),
                now = previewNow,
                reduceMotion = true,
                onToggle = {},
                onEditTime = {},
                onTest = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun AppearanceAndAiPreview() {
    PreviewSky {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppearanceCard(themeMode = ThemeMode.SYSTEM, accent = AccentId.OZEAN, onThemeMode = {}, onAccent = {})
            AiCard(
                settings = AppSettings(ai = AiSettings(providers = mapOf(AiProviderId.ANTHROPIC to ProviderSettings(hasKey = true)))),
                supported = true,
                usage = UsageSnapshot(month = "2026-10", spentUsd = 0.84, limitUsd = 5.0, requests = 23, webSearches = 7),
                reduceMotion = true,
                actions = AiActions({}, {}, {}, {}),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun UpdateAndReadingPreview() {
    PreviewSky {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UpdateCard(
                state = UpdatePanelState(installedVersion = "1.1.0", phase = UpdatePhase.Available(previewRelease)),
                hasToken = true,
                reduceMotion = true,
                actions = noUpdateActions,
            )
            UpdateCard(
                state = UpdatePanelState(
                    installedVersion = "1.1.0",
                    phase = UpdatePhase.Downloading(previewRelease, progress = 0.42f, received = 5_200_000, total = 12_400_000),
                ),
                hasToken = true,
                reduceMotion = true,
                actions = noUpdateActions,
            )
            UpdateCard(
                state = UpdatePanelState(installedVersion = "1.1.0", phase = UpdatePhase.NeedsPermission(previewRelease, File("preview.apk"))),
                hasToken = true,
                reduceMotion = true,
                actions = noUpdateActions,
            )
            UpdateCard(
                state = UpdatePanelState(installedVersion = "1.1.0", phase = UpdatePhase.Failed(UpdateError.UNAUTHORIZED, release = null)),
                hasToken = true,
                reduceMotion = true,
                actions = noUpdateActions,
            )
            ReadingCard(scale = 1.15f, onCommit = {})
        }
    }
}
