package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing

/**
 * The brief's teaser sentence + "Stand 05:07" / stale hint. The articles themselves are
 * rendered by the cards in `ui/news` (top stories, topic sections, reader).
 */
@Composable
fun NewsTeaser(teaser: String, generatedAt: String?, stale: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val stand = generatedAt?.let(Fmt::clock).orEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Emoji("📰", size = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "HEUTE IN DEN NEWS",
                    style = typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (stand.isNotBlank()) {
                    Text(
                        text = "Stand $stand",
                        style = typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = teaser.ifBlank { "Die wichtigsten Meldungen des Morgens – kurz und knapp." },
                style = typography.titleMedium,
            )
            if (stale) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.secondaryContainer.copy(alpha = 0.7f),
                    contentColor = colors.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Das ist noch das Briefing von gestern – das neue kommt gleich.",
                            style = typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
