package com.cedrickgd.morgenbriefing.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.listen.ListenBars
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlin.math.abs

/** Height of the floating control row below the status bar. */
internal val ReaderBarHeight = 64.dp

/** Height of the "Frag Claude" pill. */
internal val AskPillHeight = 52.dp

private val BarShape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
private val ButtonSize = 48.dp

/** Text size steps shown as dots in the A− / A+ panel. */
private val SCALE_DOTS = listOf(0.85f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f)

/**
 * The reader's floating controls (docs/DESIGN.md §7): a round glass back button on the left and
 * a glass capsule on the right with "Zuhören" ([listening]: this article is loaded in the
 * player, [speaking]: and being read right now), text size ("Aa") and share. Over the picture they
 * float as single glass pieces; once the picture has scrolled away ([collapse] → 1) they melt
 * into a frosted bar under which the text glides, with a thin reading-progress line.
 *
 * [collapse] and [readingProgress] are read in the draw phase only — scrolling never recomposes.
 */
@Composable
internal fun BoxScope.ReaderTopBar(
    collapse: () -> Float,
    readingProgress: () -> Float,
    listening: Boolean,
    speaking: Boolean,
    compact: Boolean,
    onBack: () -> Unit,
    onListen: () -> Unit,
    onTextSize: () -> Unit,
    onShare: () -> Unit,
) {
    val statusTop = statusBarTop()
    val accent = MaterialTheme.colorScheme.primary
    val glassAlpha = remember(collapse) { { 1f - collapse() } }

    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(statusTop + ReaderBarHeight),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .fadingGlass(alpha = collapse, shape = BarShape, level = GlassLevel.Thick),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(2.dp)
                .drawBehind {
                    val visibility = collapse()
                    if (visibility <= 0.01f) return@drawBehind
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(accent.copy(alpha = 0.14f * visibility), cornerRadius = radius)
                    drawRoundRect(
                        color = accent.copy(alpha = visibility),
                        size = Size(size.width * readingProgress().coerceIn(0f, 1f), size.height),
                        cornerRadius = radius,
                    )
                },
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusTop)
                    .horizontalSafeDrawing()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Zurück",
                    glassAlpha = glassAlpha,
                    onClick = onBack,
                )
                Spacer(Modifier.weight(1f))
                ActionCapsule(
                    glassAlpha = glassAlpha,
                    listening = listening,
                    speaking = speaking,
                    showLabel = !compact,
                    onListen = onListen,
                    onTextSize = onTextSize,
                    onShare = onShare,
                )
            }
        }
    }
}

/**
 * Glass that can fade: skipped entirely (no blur work) while [alpha] is ~0. A soft ambient
 * shadow lifts it off busy pictures.
 */
private fun Modifier.fadingGlass(
    alpha: () -> Float,
    shape: androidx.compose.ui.graphics.Shape,
    level: GlassLevel,
    shadow: Dp = 0.dp,
): Modifier = this
    .drawWithContent { if (alpha() > 0.01f) drawContent() }
    .graphicsLayer { this.alpha = alpha() }
    .then(
        if (shadow > 0.dp) {
            Modifier.shadow(shadow, shape, ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.14f))
        } else {
            Modifier
        },
    )
    .glass(shape = shape, level = level)

/** A round 48 dp glass button; its glass fades out when the frosted bar takes over. */
@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassAlpha: () -> Float = { 1f },
) {
    Box(
        modifier = modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .fadingGlass(alpha = glassAlpha, shape = CircleShape, level = GlassLevel.Regular),
        )
        Icon(imageVector = icon, contentDescription = null)
    }
}

@Composable
private fun ActionCapsule(
    glassAlpha: () -> Float,
    listening: Boolean,
    speaking: Boolean,
    showLabel: Boolean,
    onListen: () -> Unit,
    onTextSize: () -> Unit,
    onShare: () -> Unit,
) {
    Box(Modifier.height(ButtonSize)) {
        Box(
            Modifier
                .matchParentSize()
                .fadingGlass(alpha = glassAlpha, shape = GlassShapes.pill, level = GlassLevel.Regular, shadow = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListenSegment(listening = listening, speaking = speaking, showLabel = showLabel, onClick = onListen)
            SegmentDivider()
            Box(
                modifier = Modifier
                    .size(ButtonSize)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Textgröße ändern", onClick = onTextSize)
                    .semantics { contentDescription = "Textgröße" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aa",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
                )
            }
            SegmentDivider()
            Box(
                modifier = Modifier
                    .size(ButtonSize)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onShare)
                    .semantics { contentDescription = "Teilen" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ListenSegment(listening: Boolean, speaking: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val still = motionDisabled()
    Row(
        modifier = Modifier
            .height(ButtonSize)
            .widthIn(min = ButtonSize)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = when {
                    speaking -> "Vorlesen beenden"
                    listening -> "Weiter vorlesen"
                    else -> "Artikel vorlesen"
                },
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Zuhören"
                stateDescription = when {
                    speaking -> "Liest vor"
                    listening -> "Pausiert"
                    else -> "Aus"
                }
            }
            .padding(start = if (showLabel) 14.dp else 13.dp, end = if (showLabel) 14.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = speaking,
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.7f)) togetherWith fadeOut(tween(120)) },
            label = "listenIcon",
        ) { active ->
            if (active) {
                ListenBars(color = accent, animate = !still, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(21.dp))
            }
        }
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    speaking -> "Stopp"
                    listening -> "Weiter"
                    else -> "Zuhören"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (speaking) accent else LocalContentColor.current,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SegmentDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(LocalContentColor.current.copy(alpha = 0.14f)),
    )
}

/**
 * The A− / A+ panel under the capsule: a glass card with the size as seven steps, the
 * percentage and a live preview sentence in the reader's serif. Tapping outside or back closes it.
 */
@Composable
internal fun BoxScope.TextSizePanel(
    open: Boolean,
    scale: Float,
    onChange: (larger: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = open, onBack = onDismiss)
    if (open) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Schließen",
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible = open,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = statusBarTop() + ReaderBarHeight + 6.dp)
            .horizontalSafeDrawing()
            .padding(end = 12.dp),
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.86f, transformOrigin = TransformOrigin(0.9f, 0f)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.92f, transformOrigin = TransformOrigin(0.9f, 0f)),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .shadow(20.dp, GlassShapes.card, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                    .glass(shape = GlassShapes.card, level = GlassLevel.Thick)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "TEXTGRÖSSE",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SizeStepButton(
                        label = "A−",
                        fontSize = 15,
                        description = "Text kleiner",
                        enabled = scale > MIN_TEXT_SCALE + 0.001f,
                        onClick = { onChange(false) },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ScaleDots(scale = scale)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = textScaleLabel(scale),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SizeStepButton(
                        label = "A+",
                        fontSize = 21,
                        description = "Text größer",
                        enabled = scale < MAX_TEXT_SCALE - 0.001f,
                        onClick = { onChange(true) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "So liest sich dein Artikel – ruhig und gut lesbar.",
                    style = Reading.articleBody(scale),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SizeStepButton(label: String, fontSize: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val content = LocalContentColor.current
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .background(content.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = fontSize.sp,
            ),
            color = content.copy(alpha = if (enabled) 1f else 0.38f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Seven dots, the chosen one stretched into an accent capsule. */
@Composable
private fun ScaleDots(scale: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val selected = SCALE_DOTS.indices.minByOrNull { abs(SCALE_DOTS[it] - scale) } ?: 2
    val still = motionDisabled()
    val position by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = if (still) tween(0) else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "scaleDot",
    )
    Box(
        Modifier
            .width(120.dp)
            .height(10.dp)
            .drawBehind {
                val step = size.width / (SCALE_DOTS.size - 1)
                val dot = 3.dp.toPx()
                SCALE_DOTS.indices.forEach { i ->
                    drawCircle(idle, radius = dot, center = Offset(i * step, size.height / 2f))
                }
                val x = position * step
                val half = 9.dp.toPx()
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x - half, size.height / 2f - 4.dp.toPx()),
                    size = Size(half * 2f, 8.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            },
    )
}

/**
 * "✨ Frag Claude zu diesem Artikel": a prominent, accent-tinted glass pill at the bottom. Slides
 * away while reading down and comes back on the way up; [lift] makes room for the mini player.
 * A soft light sweeps across it once when it first appears.
 */
@Composable
internal fun BoxScope.AskClaudePill(
    visible: Boolean,
    bottomInset: Dp,
    lift: Dp,
    onClick: () -> Unit,
) {
    val still = motionDisabled()
    val density = LocalDensity.current
    val liftPx by animateFloatAsState(
        targetValue = with(density) { lift.toPx() },
        animationSpec = if (still) tween(0) else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "askLift",
    )
    val sweep = remember { Animatable(if (still) 2f else -0.6f) }
    LaunchedEffect(visible) {
        if (visible && sweep.value < 1.5f) {
            sweep.animateTo(1.6f, tween(durationMillis = 1_300, delayMillis = 500, easing = FastOutSlowInEasing))
        }
    }
    val colors = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 16.dp)
            .horizontalSafeDrawing()
            .padding(horizontal = 24.dp)
            .graphicsLayer { translationY = -liftPx },
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } +
            fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it * 2 } +
            fadeOut(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        Row(
            modifier = Modifier
                .shadow(14.dp, GlassShapes.pill, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = colors.primary.copy(alpha = 0.22f))
                .glass(shape = GlassShapes.pill, level = GlassLevel.Thick, tint = colors.primaryContainer)
                .drawWithContent {
                    drawContent()
                    val p = sweep.value
                    if (p > -0.5f && p < 1.5f) {
                        val center = size.width * p
                        val band = size.width * 0.35f
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.28f),
                                1f to Color.Transparent,
                                startX = center - band,
                                endX = center + band,
                            ),
                        )
                    }
                }
                .clickable(role = Role.Button, onClickLabel = "Chat öffnen", onClick = onClick)
                .heightIn(min = AskPillHeight)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "✨", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Frag Claude zu diesem Artikel",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onPrimaryContainer,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}
