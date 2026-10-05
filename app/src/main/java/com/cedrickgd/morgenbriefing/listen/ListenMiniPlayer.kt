package com.cedrickgd.morgenbriefing.listen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.listen
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.blockTouchesBehind
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Measures of the mini player, for screens that keep room for it. */
object ListenMiniPlayerDefaults {
    /** Height of the pill. */
    val Height = 64.dp

    /** Recommended gap between the pill and whatever sits below it (page indicator, system bar). */
    val Gap = 12.dp
}

/** True while the mini player is on screen for this state (reading, paused or explaining a problem). */
fun ListenState.showsMiniPlayer(): Boolean = speaking || paused || issue != null

/**
 * Floating glass mini player for "Zuhören": headphones badge (with a gently moving equalizer
 * while it reads), the title as a one-line marquee, a thin progress line, pause/resume and
 * close. When reading is impossible it explains why and offers to install a voice. Slides up
 * and fades in only while there is something to show; otherwise it takes no space.
 *
 * Fills the width it gets (max 560 dp, centred) with 16 dp side margins; the shell positions
 * it above the page indicator, on pushed screens above the navigation bar.
 *
 * Tapping the pill opens the article being read ([onOpenArticle] with its brief date and key);
 * taps never fall through to the content underneath.
 */
@Composable
fun ListenMiniPlayer(modifier: Modifier = Modifier, onOpenArticle: ((date: String, key: String) -> Unit)? = null) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val controller = remember(context) { context.listen }
    val state by controller.state.collectAsStateWithLifecycle()
    ListenMiniPlayerContent(
        state = state,
        onToggle = controller::pauseOrResume,
        onClose = controller::stop,
        onFix = { controller.installVoiceData(context) },
        modifier = modifier,
        onOpen = onOpenArticle?.let { open ->
            {
                val current = controller.state.value
                val date = current.articleDate
                val key = current.articleKey
                if (date != null && key != null) open(date, key)
            }
        },
    )
}

/** Stateless mini player (previews, tests). */
@Composable
internal fun ListenMiniPlayerContent(
    state: ListenState,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val visible = state.showsMiniPlayer()
    // The controller resets its state when reading ends; keep showing the last visible one
    // while the pill slides away instead of flashing an empty player.
    val last = remember { LastVisible(state) }
    if (visible) last.value = state
    val shown = last.value

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } +
            fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } +
            fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.94f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            // Only a reading whose article is known can be opened (not the "no voice" hint).
            val open = onOpen?.takeIf { shown.issue == null && shown.articleDate != null }
            val title = shown.title.orEmpty()
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Row(
                    modifier = Modifier
                        .shadow(
                            elevation = 16.dp,
                            shape = GlassShapes.pill,
                            ambientColor = Color.Black.copy(alpha = 0.16f),
                            spotColor = Color.Black.copy(alpha = 0.16f),
                        )
                        // Floating glass: blurs the feed or article beneath; opaque without real blur.
                        .glass(shape = GlassShapes.pill, level = GlassLevel.Regular, floating = true)
                        // The whole pill takes its touches: nothing beneath is ever opened by accident.
                        .blockTouchesBehind()
                        .then(
                            if (open != null) {
                                Modifier
                                    .clickable(role = Role.Button, onClickLabel = "Artikel öffnen", onClick = open)
                                    .semantics { contentDescription = "Vorlesen: $title öffnen" }
                            } else {
                                Modifier
                            },
                        )
                        .fillMaxWidth()
                        .height(ListenMiniPlayerDefaults.Height)
                        .padding(start = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val issue = shown.issue
                    if (issue != null) {
                        IssueContent(issue = issue, onFix = onFix, onClose = onClose)
                    } else {
                        PlayerContent(state = shown, onToggle = onToggle, onClose = onClose, onFix = onFix)
                    }
                }
            }
        }
    }
}

/** Plain holder: written during composition, never observed (the state itself drives recomposition). */
private class LastVisible(var value: ListenState)

@Composable
private fun RowScope.PlayerContent(state: ListenState, onToggle: () -> Unit, onClose: () -> Unit, onFix: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val progress = animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) tween(0) else spring(stiffness = Spring.StiffnessLow),
        label = "listenProgress",
    )
    Badge(speaking = state.speaking && !state.preparing, preparing = state.preparing, reduceMotion = reduceMotion)
    Spacer(Modifier.width(12.dp))
    Column(
        modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val marquee = state.speaking && !reduceMotion
        Text(
            text = state.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (marquee) {
                Modifier
                    .marqueeFadeArea()
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1_500,
                        repeatDelayMillis = 2_400,
                        spacing = MarqueeSpacing(32.dp),
                        velocity = 34.dp,
                    )
            } else {
                Modifier
            },
        )
        Spacer(Modifier.height(6.dp))
        ProgressLine(progress = progress)
    }
    Spacer(Modifier.width(4.dp))
    if (state.onlineVoiceOnly) {
        // Reading uses a network voice: offer the offline German voice (works without internet).
        IconButton(onClick = onFix) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Deutsche Stimme offline installieren",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ToggleButton(paused = state.paused, onToggle = onToggle)
    IconButton(onClick = onClose) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Vorlesen beenden",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Soft edges for the scrolling title instead of a hard cut. The fade zone on the left lies
 * *outside* the text column (over the gap next to the badge), so at rest the title starts fully
 * opaque, aligned with the overline above it, and only letters scrolling out fade away.
 */
private fun Modifier.marqueeFadeArea(): Modifier = this
    .layout { measurable, constraints ->
        val extra = MarqueeFade.roundToPx()
        val wide = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = wide))
        layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
    }
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        if (size.width <= 0f) return@drawWithContent
        val start = (MarqueeFade.toPx() / size.width).coerceAtMost(0.25f)
        val end = (20.dp.toPx() / size.width).coerceAtMost(0.3f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                start to Color.Black,
                1f - end to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
    .padding(start = MarqueeFade)

/** Width of the fade zone left of the title (inside the 12 dp gap next to the badge). */
private val MarqueeFade = 12.dp

/** "LIEST VOR · NOCH ETWA 3 MIN." */
private fun statusLine(state: ListenState): String {
    val status = when {
        state.preparing -> "STIMME WIRD VORBEREITET"
        state.paused -> "PAUSIERT"
        state.onlineVoiceOnly -> "LIEST VOR · ONLINE-STIMME"
        else -> "LIEST VOR"
    }
    val remaining = remainingMinutes(state.estimatedSeconds, state.progress)
    return if (remaining != null && !state.preparing) "$status · NOCH ETWA $remaining MIN." else status
}

/** Whole minutes still to listen to (at least 1), or null when unknown. */
internal fun remainingMinutes(estimatedSeconds: Int, progress: Float): Int? {
    if (estimatedSeconds <= 0) return null
    val seconds = estimatedSeconds * (1f - progress.coerceIn(0f, 1f))
    return (seconds / 60f).roundToInt().coerceAtLeast(1)
}

@Composable
private fun Badge(speaking: Boolean, preparing: Boolean, reduceMotion: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(accent, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = when {
                preparing -> BadgeMode.PREPARING
                speaking -> BadgeMode.SPEAKING
                else -> BadgeMode.IDLE
            },
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "listenBadge",
        ) { mode ->
            when (mode) {
                BadgeMode.PREPARING -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = onAccent,
                    strokeWidth = 2.dp,
                )
                BadgeMode.SPEAKING -> ListenBars(color = onAccent, animate = !reduceMotion)
                BadgeMode.IDLE -> Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = onAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private enum class BadgeMode { PREPARING, SPEAKING, IDLE }

/**
 * Three bars that rise and fall like a voice ("reading now"); drawn in the draw phase only.
 * Static when [animate] is false (system "remove animations").
 */
@Composable
internal fun ListenBars(color: Color, animate: Boolean, modifier: Modifier = Modifier.size(20.dp)) {
    val phase: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "equalizer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "equalizerPhase",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val t = phase?.value ?: 0.25f
        val barWidth = size.width / 5f
        val radius = CornerRadius(barWidth / 2f)
        for (i in 0 until 3) {
            val wave = sin(2.0 * PI * (t + i * 0.27)).toFloat()
            val level = if (phase == null) listOf(0.55f, 0.9f, 0.7f)[i] else 0.45f + 0.4f * (0.5f + 0.5f * wave)
            val height = size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(x = barWidth * (2 * i), y = (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun ProgressLine(progress: State<Float>) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f)
            }
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = track, cornerRadius = radius)
                val width = size.width * progress.value.coerceIn(0f, 1f)
                if (width > 0f) drawRoundRect(color = fill, size = Size(width, size.height), cornerRadius = radius)
            },
    )
}

@Composable
private fun ToggleButton(paused: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = if (paused) "Weiter vorlesen" else "Vorlesen pausieren",
            )
        }
    }
}

@Composable
private fun RowScope.IssueContent(issue: ListenIssue, onFix: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.HeadsetOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(22.dp),
        )
    }
    Spacer(Modifier.width(12.dp))
    Column(
        modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = when (issue) {
                ListenIssue.MISSING_VOICE -> "Keine deutsche Stimme"
                ListenIssue.NO_ENGINE -> "Vorlesen nicht verfügbar"
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when (issue) {
                ListenIssue.MISSING_VOICE -> "Lade sie einmal herunter, dann klappt's."
                ListenIssue.NO_ENGINE -> "Richte eine Sprachausgabe ein."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    TextButton(onClick = onFix) {
        Text(if (issue == ListenIssue.MISSING_VOICE) "Installieren" else "Einrichten")
    }
    IconButton(
        onClick = onClose,
        modifier = Modifier.semantics { contentDescription = "Hinweis schließen" },
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Mini player – liest vor", widthDp = 400)
@Composable
private fun ListenMiniPlayerPreview() {
    MorgenbriefingTheme(themeMode = ThemeMode.LIGHT, accent = AccentId.MORGENROT) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListenMiniPlayerContent(
                state = ListenState(
                    speaking = true,
                    title = "Telekom will mit KI 2,5 Milliarden Euro sparen",
                    articleKey = "ki",
                    progress = 0.35f,
                    estimatedSeconds = 260,
                ),
                onToggle = {},
                onClose = {},
                onFix = {},
            )
            ListenMiniPlayerContent(
                state = ListenState(paused = true, title = "Euro fällt auf 17-Monats-Tief", progress = 0.7f, estimatedSeconds = 180),
                onToggle = {},
                onClose = {},
                onFix = {},
            )
            ListenMiniPlayerContent(
                state = ListenState(available = false, issue = ListenIssue.MISSING_VOICE),
                onToggle = {},
                onClose = {},
                onFix = {},
            )
        }
    }
}
