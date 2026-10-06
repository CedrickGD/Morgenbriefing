package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Settings section 2 "Morgenbriefing": the time huge in serif (tap → time picker), the on/off
 * switch, the countdown to the next briefing and the test notification.
 */
@Composable
internal fun BriefingCard(
    settings: AppSettings,
    now: ZonedDateTime,
    reduceMotion: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = settings.notificationsEnabled
    val time = clockLabel(settings.notifyHour, settings.notifyMinute)
    val countdown = remember(now, settings.notifyHour, settings.notifyMinute) {
        nextBriefingCountdown(now, nextBriefingAt(now, settings.notifyHour, settings.notifyMinute))
    }
    val haptic = rememberToggleHaptics()
    val timeAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.42f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "timeAlpha",
    )

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Overline("Jeden Morgen um")
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = { value ->
                            haptic(value)
                            onToggle(value)
                        },
                    )
                    .semantics { contentDescription = "Morgenbriefing" }
                    .padding(6.dp),
            ) {
                GlassSwitch(checked = enabled)
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Uhrzeit ändern", role = Role.Button, onClick = onEditTime)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Uhrzeit $time Uhr"
                    stateDescription = if (enabled) "an" else "aus"
                }
                .padding(end = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AnimatedContent(
                targetState = time,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically { -it / 2 } + fadeOut())
                    }
                },
                label = "briefingTime",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .graphicsLayer { alpha = timeAlpha }
                        .clearAndSetSemantics { },
                )
            }
            Text(
                text = "Uhr",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 12.dp)
                    .clearAndSetSemantics { },
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 12.dp, bottom = 16.dp)
                    .size(20.dp),
            )
        }
        Text(
            text = if (enabled) countdown else "Aus – morgens kommt keine Benachrichtigung.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPillButton(
                text = "Test-Benachrichtigung",
                onClick = onTest,
                icon = Icons.Rounded.NotificationsActive,
                style = PillStyle.Tonal,
            )
        }
    }
}

/** Berlin time that ticks once per minute (for countdowns), without per-frame work. */
@Composable
internal fun rememberMinuteClock(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now(BERLIN)) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = ZonedDateTime.now(BERLIN)
            now = current
            val nextMinute = current.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(ChronoUnit.MILLIS.between(current, nextMinute).coerceAtLeast(250))
        }
    }
    return now
}
