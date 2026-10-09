package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.GlassPillButton
import com.cedrickgd.morgenbriefing.ui.components.entrance
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.rememberEntrance
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.news.LiveColors
import java.time.ZonedDateTime

/** How many progress lines the panel shows (newest last). */
private const val VISIBLE_PROGRESS_LINES = 4

/**
 * Head of the "⚡ Live" view: what it shows, when it was last updated, "Jetzt aktualisieren"
 * and — while Claude researches — an animated panel with the live progress lines. Blocks
 * (old Android, no key, monthly limit) and errors are explained in a friendly way.
 */
@Composable
internal fun LiveHeader(
    live: LiveUi,
    now: ZonedDateTime,
    freshCount: Int,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveOrb(running = live.running)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = liveStatusLine(live, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        AnimatedVisibility(
            visible = live.running,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(),
            label = "liveProgress",
        ) {
            ProgressPanel(lines = live.progress, modifier = Modifier.padding(top = 16.dp))
        }

        val block = live.blocked
        val error = live.error
        when {
            block != null -> {
                Spacer(Modifier.height(14.dp))
                Text(text = liveBlockText(block, live.spec), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                if (block != AiBlock.UNSUPPORTED_DEVICE) {
                    Spacer(Modifier.height(14.dp))
                    // No key: entered right here. Monthly limit and the Live provider: in the settings' KI section.
                    GlassPillButton(
                        text = when (block) {
                            AiBlock.NO_KEY -> "Key eintragen"
                            AiBlock.LIVE_UNAVAILABLE -> AiTexts.LIVE_PROVIDER_ACTION
                            else -> "Limit anpassen"
                        },
                        icon = if (block == AiBlock.NO_KEY) Icons.Rounded.Key else Icons.Rounded.Settings,
                        onClick = onOpenSettings,
                        filled = true,
                    )
                }
            }

            live.running -> {
                Spacer(Modifier.height(14.dp))
                GlassPillButton(text = "Abbrechen", icon = Icons.Rounded.Close, onClick = onCancel)
            }

            else -> {
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(text = liveErrorText(error, live.spec), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                } else if (live.lastRunAt == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (freshCount > 0) {
                            "Claude sucht, was seit dem Morgen-Briefing passiert ist, prüft es an mehreren Quellen und schreibt es dir auf."
                        } else {
                            "Seit dem Morgen ist hier noch nichts Neues. Claude kann für dich nachsehen, was gerade passiert."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                GlassPillButton(text = "Jetzt aktualisieren", icon = Icons.Rounded.Refresh, onClick = onRefresh, filled = true)
            }
        }
    }
}

/** The ⚡ in an amber circle; breathes while a run is going on. */
@Composable
private fun LiveOrb(running: Boolean) {
    val still = motionDisabled() || !running
    val pulse = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "liveOrb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "liveOrbPulse",
        )
    }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // Expanding halo while running.
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer {
                    val p = pulse?.value ?: 0f
                    scaleX = 0.8f + 0.45f * p
                    scaleY = scaleX
                    alpha = if (pulse == null) 0f else (1f - p) * 0.45f
                }
                .background(LiveColors.fill, CircleShape),
        )
        Box(
            Modifier
                .size(44.dp)
                .background(LiveColors.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Emoji("⚡", size = 20.sp)
        }
    }
}

/**
 * The last progress lines of the running research ("Suche: …", "Lese tagesschau.de …"), each
 * rising in as it arrives, older ones fading; a light sweeps over the panel like a scanner.
 */
@Composable
private fun ProgressPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val still = motionDisabled()
    val sweep = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "liveSweep").animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
            label = "liveSweepX",
        )
    }
    val highlight = Color.White.copy(alpha = if (isGlassDark()) 0.06f else 0.35f)
    val start = (lines.size - VISIBLE_PROGRESS_LINES).coerceAtLeast(0)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.onSurface.copy(alpha = 0.05f), GlassShapes.chip)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    val x = (sweep?.value ?: -1f) * size.width
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, highlight, Color.Transparent),
                            startX = x - size.width * 0.25f,
                            endX = x + size.width * 0.25f,
                        ),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (lines.isEmpty()) {
            ProgressLine(text = "Starte die Recherche …", newest = true)
        }
        for (index in start until lines.size) {
            key(index) {
                val entrance = rememberEntrance(durationMillis = 360)
                ProgressLine(
                    text = lines[index],
                    newest = index == lines.lastIndex,
                    modifier = Modifier.entrance(entrance, distance = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String, newest: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (newest) LiveColors.fill else colors.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (newest) FontWeight.SemiBold else FontWeight.Normal),
            color = if (newest) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown in "⚡ Live" when nothing fresh is there (yet). */
@Composable
internal fun LiveEmpty(blocked: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Emoji("🌤️", size = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "In den letzten 6 Stunden ist nichts Neues dazugekommen.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!blocked) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tipp: „Jetzt aktualisieren“ lässt Claude nach brandaktuellen Meldungen suchen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
