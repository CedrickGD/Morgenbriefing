package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.util.Links

/** Settings section "App": name, version and the in-app updater below it. */
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
        Text(
            text = "Läuft komplett auf deinem Handy: Wetter von Open-Meteo, Nachrichten direkt aus den RSS-Feeds der Redaktionen. Kein Konto, keine Cloud, keine Werbung.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

private data class AppLink(val icon: ImageVector, val title: String, val subtitle: String, val url: String)

/** Credits and source code as tappable rows. */
@Composable
internal fun LinksCard(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val links = listOf(
        AppLink(Icons.Rounded.Cloud, "Wetterdaten", "Open-Meteo.com · CC BY 4.0", Links.OPEN_METEO),
        AppLink(Icons.Rounded.RssFeed, "Nachrichten", "RSS-Feeds der Redaktionen · alle Rechte bei den Verlagen", Links.SOURCE_CODE + "#quellen"),
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
