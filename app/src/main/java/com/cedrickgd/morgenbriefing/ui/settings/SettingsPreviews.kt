package com.cedrickgd.morgenbriefing.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.update.ApkAsset
import com.cedrickgd.morgenbriefing.update.ReleaseInfo
import com.cedrickgd.morgenbriefing.update.UpdateError
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import java.io.File

// Previews of the settings building blocks in every interesting state (Android Studio only).

@Preview(name = "Hell", showBackground = true, widthDp = 380)
@Preview(name = "Dunkel", showBackground = true, widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ThemePreviews

private val previewRelease = ReleaseInfo(
    tag = "v1.2.0",
    version = "1.2.0",
    title = "Morgenbriefing 1.2.0",
    notes = "## Neu\n- Themen sortieren und ausblenden\n- Ganze Artikel in der App lesen\n- Textgröße einstellbar",
    publishedAt = "2026-10-05T17:12:00Z",
    apk = ApkAsset(id = 1, name = "Morgenbriefing-v1.2.0.apk", size = 12_400_000),
)

private val noActions = UpdateCardActions(
    onCheck = {},
    onDownload = {},
    onCancel = {},
    onInstall = {},
    onAllowInstall = {},
    onDismissError = {},
    onGoToToken = {},
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    }
}

@ThemePreviews
@Composable
private fun UpdateCardStatesPreview() {
    val phases = listOf(
        UpdatePhase.UpToDate,
        UpdatePhase.Available(previewRelease),
        UpdatePhase.Downloading(previewRelease, progress = 0.42f, received = 5_200_000, total = 12_400_000),
        UpdatePhase.NeedsPermission(previewRelease, File("preview.apk")),
        UpdatePhase.Failed(UpdateError.UNAUTHORIZED, release = null),
    )
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            phases.forEach { phase ->
                UpdateCard(
                    state = UpdatePanelState(installedVersion = "1.1.0", lastCheckAt = null, phase = phase),
                    hasToken = true,
                    reduceMotion = true,
                    actions = noActions,
                )
            }
            UpdateCard(
                state = UpdatePanelState(installedVersion = "1.1.0"),
                hasToken = false,
                reduceMotion = true,
                actions = noActions,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun TopicRowsPreview() {
    val topics = Topics.ALL.take(4)
    val noRowActions = TopicRowActions({}, null, null, {}, {}, {})
    PreviewSurface {
        Column(Modifier.width(380.dp)) {
            TopicsIntro()
            topics.forEachIndexed { index, topic ->
                TopicRow(
                    topic = topic,
                    visible = index != 2,
                    canHide = true,
                    position = tilePosition(index, topics.size),
                    lifted = index == 1,
                    actions = noRowActions,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            TopicsFooter(visibleCount = 3, total = 4, isDefaultOrder = false, onReset = {})
        }
    }
}

@ThemePreviews
@Composable
private fun ReadingCardPreview() {
    PreviewSurface { ReadingCard(scale = 1.15f, onCommit = {}) }
}
