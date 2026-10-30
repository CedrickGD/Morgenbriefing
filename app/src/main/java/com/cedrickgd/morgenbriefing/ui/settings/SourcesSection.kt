package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.news.FeedCatalog
import com.cedrickgd.morgenbriefing.data.news.Regions
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.news.TopicBadge
import com.cedrickgd.morgenbriefing.ui.news.hostOf

/** Callbacks of the sources section. */
internal class SourcesActions(
    val onSourceEnabled: (sourceId: String, enabled: Boolean) -> Unit,
    val onEnableAll: (topicId: String) -> Unit,
    val onAddFeed: (Topic) -> Unit,
    val onRemoveFeed: (sourceId: String) -> Unit,
    val onEditTopic: (Topic) -> Unit,
    val onDeleteTopic: (Topic) -> Unit,
    val onPickRegion: () -> Unit,
)

/**
 * Settings section "Quellen": the region preset and, per topic, every feed with a switch. A
 * topic row expands to its feeds; own feeds can be removed, own topics edited or deleted, and
 * "Eigene Quelle" adds a feed to any topic.
 *
 * @param expanded ids of the topics whose feeds are shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesSection(
    settings: AppSettings,
    expanded: Set<String>,
    onToggleExpanded: (String) -> Unit,
    actions: SourcesActions,
    modifier: Modifier = Modifier,
) {
    val topics = remember(settings) { Topics.catalogue(settings) }
    val region = remember(settings.region) { Regions.byKey(settings.region) }
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Map)
            Spacer(Modifier.width(14.dp))
            ValueRow(
                label = "Deine Region",
                value = "${region.name} · ${region.feeds.joinToString(", ") { it.name }}",
                onClick = actions.onPickRegion,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
        topics.forEachIndexed { index, topic ->
            key(topic.id) {
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 54.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    )
                }
                TopicSourcesRow(
                    topic = topic,
                    sources = remember(settings, topic.id) { FeedCatalog.allSourcesFor(topic.id, settings) },
                    disabled = settings.disabledFeeds,
                    hidden = topic.id in settings.hiddenTopics,
                    expanded = topic.id in expanded,
                    onToggleExpanded = { onToggleExpanded(topic.id) },
                    actions = actions,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicSourcesRow(
    topic: Topic,
    sources: List<FeedSource>,
    disabled: Set<String>,
    hidden: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actions: SourcesActions,
) {
    val colors = MaterialTheme.colorScheme
    val enabledCount = sources.count { it.id !in disabled }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "sourcesChevron")
    val summary = when {
        sources.isEmpty() -> "Noch keine Quelle – füge eine hinzu"
        enabledCount == sources.size -> "${sources.size} ${if (sources.size == 1) "Quelle" else "Quellen"}"
        else -> "$enabledCount von ${sources.size} Quellen an"
    } + if (hidden) " · Thema ausgeblendet" else ""

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onToggleExpanded)
                .semantics { stateDescription = if (expanded) "ausgeklappt" else "eingeklappt" }
                .heightIn(min = 60.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopicBadge(topic, diameter = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
                sources.forEach { source ->
                    key(source.id) {
                        SourceRow(
                            source = source,
                            enabled = source.id !in disabled,
                            onEnabled = { actions.onSourceEnabled(source.id, it) },
                            onRemove = if (source.isCustom) { { actions.onRemoveFeed(source.id) } } else null,
                        )
                    }
                }
                if (sources.isEmpty()) {
                    Text(
                        text = "Dieses Thema hat noch keine Quelle.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    GlassPillButton("Eigene Quelle", onClick = { actions.onAddFeed(topic) }, icon = Icons.Rounded.Add, style = PillStyle.Tonal)
                    if (enabledCount < sources.size) {
                        GlassPillButton("Alle an", onClick = { actions.onEnableAll(topic.id) }, style = PillStyle.Quiet)
                    }
                    if (topic.custom) {
                        GlassPillButton("Bearbeiten", onClick = { actions.onEditTopic(topic) }, icon = Icons.Rounded.Edit, style = PillStyle.Quiet)
                        GlassPillButton("Löschen", onClick = { actions.onDeleteTopic(topic) }, icon = Icons.Rounded.DeleteOutline, style = PillStyle.Quiet)
                    }
                }
            }
        }
    }
}

/** One feed: name, host, a switch — and a bin for own feeds. */
@Composable
private fun SourceRow(source: FeedSource, enabled: Boolean, onEnabled: (Boolean) -> Unit, onRemove: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val haptic = rememberToggleHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { value ->
                    haptic(value)
                    onEnabled(value)
                },
            )
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else 0.55f }) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = hostOf(source.url) + if (source.isCustom) " · eigene Quelle" else "",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Quelle entfernen", tint = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(6.dp))
        GlassSwitch(checked = enabled)
        Spacer(Modifier.height(0.dp))
    }
}
