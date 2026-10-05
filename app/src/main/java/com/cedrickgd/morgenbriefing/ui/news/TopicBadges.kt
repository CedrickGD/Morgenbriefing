package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.ui.components.Emoji

/** Corner radius of article cards (reader). Feed pictures use 22 dp (GlassShapes.tile). */
internal val CardShape = RoundedCornerShape(24.dp)

/** Small rounded chip with the topic's emoji and title, tinted with the topic color. */
@Composable
fun TopicChip(topic: Topic, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = rememberTopicColors(topic)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (compact) 8.dp else 10.dp,
                end = if (compact) 10.dp else 12.dp,
                top = if (compact) 3.dp else 5.dp,
                bottom = if (compact) 3.dp else 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (topic.emoji.isNotBlank()) {
                Emoji(topic.emoji, size = if (compact) 12.sp else 14.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = topic.title,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The topic emoji in a circle tinted with the topic color (section headers, sheets). */
@Composable
internal fun TopicBadge(
    topic: Topic,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    diameter: Dp = 40.dp,
) {
    val colors = rememberTopicColors(topic)
    Box(
        modifier = modifier
            .size(diameter)
            .background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Emoji(topic.emoji.ifBlank { "📰" }, size = size)
    }
}
