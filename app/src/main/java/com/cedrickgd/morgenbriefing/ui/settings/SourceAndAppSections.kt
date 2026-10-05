package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.util.Links
import java.time.ZonedDateTime

/**
 * Settings section 8 "News-Quelle": the connection pill ("Verbunden ✓ · geprüft 18:52"), where the
 * news come from, and the token actions. The token itself is never shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsSourceCard(
    hasToken: Boolean,
    status: SourceStatus,
    now: ZonedDateTime,
    onEditToken: () -> Unit,
    onRemoveToken: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Hub)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Overline("GitHub · privat")
                Text(
                    "${BuildConfig.BRIEF_OWNER}/${BuildConfig.BRIEF_REPO}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (hasToken) {
                IconButton(onClick = onRecheck, enabled = status != SourceStatus.Checking) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Verbindung jetzt prüfen")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        ConnectionPill(status = status, now = now)
        Text(
            text = "Von hier kommen deine News und die App-Updates. Der Token bleibt verschlüsselt auf diesem Gerät.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPillButton(
                text = if (hasToken) "Token ändern" else "Token eintragen",
                onClick = onEditToken,
                style = if (hasToken) PillStyle.Tonal else PillStyle.Filled,
            )
            if (hasToken) {
                GlassPillButton(text = "Entfernen", onClick = onRemoveToken, style = PillStyle.Quiet, contentDescription = "Token entfernen")
            }
        }
    }
}

@Composable
private fun ConnectionPill(status: SourceStatus, now: ZonedDateTime) {
    val (tint, wash) = when (status) {
        is SourceStatus.Connected -> StatusPalette.ok() to StatusPalette.okFill()
        SourceStatus.NoToken, is SourceStatus.Failed -> StatusPalette.attention() to StatusPalette.attentionFill()
        SourceStatus.Checking, SourceStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(wash.copy(alpha = if (isGlassDark()) 0.18f else 0.14f))
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == SourceStatus.Checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(sourceStatusLabel(status, now), style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Settings section 9 "App": name, version and the in-app updater below it. */
@Composable
internal fun AppHeaderCard(installedVersion: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFD27A), Color(0xFFF08A3C), colors.primary))),
                contentAlignment = Alignment.Center,
            ) {
                Emoji("☀️", size = 28.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Morgenbriefing", style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Text(
                    text = "Version ${installedVersion.removeSuffix("-debug")}" + if (BuildConfig.DEBUG) " · Testversion" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private data class AppLink(val icon: ImageVector, val title: String, val subtitle: String, val url: String)

private const val WIKIMEDIA_COMMONS = "https://commons.wikimedia.org/"

/** Credits and source code as tappable rows. */
@Composable
internal fun LinksCard(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val links = listOf(
        AppLink(Icons.Rounded.Cloud, "Wetterdaten", "Open-Meteo.com · CC BY 4.0", Links.OPEN_METEO),
        AppLink(Icons.Rounded.Image, "Artikelbilder", "Wikimedia Commons · freie Lizenzen", WIKIMEDIA_COMMONS),
        AppLink(Icons.Rounded.Code, "Quellcode", "github.com/CedrickGD/Morgenbriefing", Links.SOURCE_CODE),
    )
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        links.forEachIndexed { index, link ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Website öffnen") { onOpen(link.url) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(link.icon, size = 36.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(link.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(link.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The quiet sign-off at the end of the page. */
@Composable
internal fun SettingsFooter(modifier: Modifier = Modifier) {
    Text(
        text = "Gemacht für ruhige Morgen ☀️",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 8.dp),
    )
}
