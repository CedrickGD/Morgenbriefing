package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.GlassSheet
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading

/**
 * "Themen": glass sheet to show or hide topics in the feed. Every catalogue topic in the
 * user's order with today's article count and a switch; the order itself is changed in the
 * settings (hint + link at the end).
 *
 * @param counts articles per topic id in the brief on screen.
 * @param onSetVisible shows/hides a topic; the last visible one cannot be switched off here.
 */
@Composable
fun TopicsSheet(
    prefs: TopicPrefs,
    counts: Map<String, Int>,
    onSetVisible: (topicId: String, visible: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val topics = remember(prefs.order) { prefs.catalogueOrder() }
    GlassSheet(onDismissRequest = onDismiss) {
        SheetTitle(overline = "Dein Feed", title = "Welche Themen willst du sehen?")
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            topics.forEach { topic ->
                val visible = topic.id !in prefs.hidden
                val lastVisible = visible && topics.count { it.id !in prefs.hidden } <= 1
                TopicToggleRow(
                    topic = topic,
                    count = counts[topic.id] ?: 0,
                    visible = visible,
                    enabled = !lastVisible,
                    onToggle = { onSetVisible(topic.id, !visible) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.SwapVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Reihenfolge in den Einstellungen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Öffnen") }
        }
    }
}

@Composable
private fun TopicToggleRow(topic: Topic, count: Int, visible: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (visible) colors.onSurface.copy(alpha = 0.04f) else colors.onSurface.copy(alpha = 0f), GlassShapes.chip)
            .toggleable(value = visible, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopicBadge(topic, diameter = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = topic.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                text = when {
                    !enabled -> "Ein Thema bleibt immer an"
                    count > 0 -> "Heute ${articleCountLabel(count)}"
                    else -> "Heute keine Meldungen"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The whole row toggles; the switch only shows the state.
        Switch(checked = visible, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * "Quellen anzeigen": the sources an article is based on, each opening in the in-app browser.
 */
@Composable
fun SourcesSheet(article: FeedArticle, onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    val sources = remember(article.item) { sourcesOf(article.item) }
    val colors = MaterialTheme.colorScheme
    val topicColors = rememberTopicColors(article.topic)
    GlassSheet(onDismissRequest = onDismiss) {
        SheetTitle(overline = if (sources.size == 1) "1 Quelle" else "${sources.size} Quellen", title = article.item.headline, serif = true)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            if (sources.isEmpty()) {
                Text(
                    text = "Für diese Meldung sind keine Quellen hinterlegt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            sources.forEach { source ->
                val host = hostOf(source.url)
                val label = sourceLabel(source)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable(enabled = source.url.isNotBlank(), role = Role.Button, onClickLabel = "Quelle öffnen") {
                            onOpenLink(source.url)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(topicColors.container, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = topicColors.onContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.isNotEmpty() && !host.equals(label, ignoreCase = true)) {
                            Text(host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (source.url.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(
            text = "Der Artikel in der App ist in eigenen Worten aus diesen Quellen geschrieben.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SheetTitle(overline: String, title: String, serif: Boolean = false) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            text = overline.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = if (serif) Reading.feedHeadline().copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize) else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}
