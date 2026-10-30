package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.windowHeight
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Feed pictures: 16:10, 22 dp corners (docs/DESIGN.md §7). */
private const val FEED_IMAGE_RATIO = 16f / 10f

/** In a short window (landscape) a feed picture takes at most this share of its height. */
private const val FEED_IMAGE_MAX_WINDOW_SHARE = 0.45f

/** The tappable suffix of a feed lead: the full article is one tap away, on the newsroom's site. */
internal const val MORE_LABEL = "Weiterlesen"

/**
 * Everything a feed entry can do. Null callbacks hide their menu item. Create it once per
 * screen (`remember`) so entries stay skippable.
 */
@Stable
class FeedActions(
    /** Opens the article: the in-app reader, or the browser when the user prefers that. */
    val onOpen: (FeedArticle) -> Unit,
    /** Always the browser (⋮ menu), even when [onOpen] reads in the app. */
    val onOpenExternal: (FeedArticle) -> Unit,
    val onListen: (FeedArticle) -> Unit,
    val onStopListening: () -> Unit,
    val onShare: (FeedArticle) -> Unit,
    val onCopyLink: (FeedArticle) -> Unit,
    val onHideTopic: ((FeedArticle) -> Unit)? = null,
    val onHideSource: ((FeedArticle) -> Unit)? = null,
)

/**
 * One article in the editorial feed, without a card frame, directly on the sky: a full-width
 * picture (or generated cover), the headline big in serif, the serif lead with an inline
 * "… Weiterlesen" and a meta row with "Zuhören", share and a ⋮ menu. Picture, headline and
 * lead open the article on the newsroom's site (in-app browser).
 *
 * @param listening this article is being read aloud right now — the button turns into "Stopp".
 * @param paused this article is loaded in the player but paused — the button offers "Weiter".
 * @param showTopic adds a small topic overline (mixed views: "Neueste").
 * @param showImage false: only the typographic cover (Einstellungen → Feed → Bilder).
 */
@Composable
fun FeedEntry(
    article: FeedArticle,
    now: ZonedDateTime,
    listening: Boolean,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    showTopic: Boolean = false,
    showImage: Boolean = true,
) {
    val item = article.item
    val interaction = remember { MutableInteractionSource() }
    val meta = remember(item, now) { feedMetaParts(item, now) }
    val maxImageHeight = windowHeight() * FEED_IMAGE_MAX_WINDOW_SHARE

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .pressScale(interaction, pressedScale = 0.985f)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Artikel öffnen",
                    onClick = { actions.onOpen(article) },
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatioAtMost(FEED_IMAGE_RATIO, maxImageHeight)
                    .clip(GlassShapes.tile),
            ) {
                ArticleVisual(
                    item = item,
                    topic = article.topic,
                    modifier = Modifier.matchParentSize(),
                    showImage = showImage,
                    contentDescription = item.image?.alt?.takeIf { it.isNotBlank() },
                )
            }
            Spacer(Modifier.height(16.dp))
            if (showTopic) {
                TopicOverline(article)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = item.headline,
                style = Reading.feedHeadline(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (item.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                LeadText(item.summary)
            }
        }
        FeedMetaRow(
            article = article,
            meta = meta,
            listening = listening,
            paused = paused,
            actions = actions,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** "🤖 KI & TECH" above the headline, in the topic's color. */
@Composable
private fun TopicOverline(article: FeedArticle) {
    val colors = rememberTopicColors(article.topic)
    val tint = if (isDarkTheme()) colors.onContainer else colors.strong
    Row(verticalAlignment = Alignment.CenterVertically) {
        Emoji(article.topic.emoji.ifBlank { "📰" }, size = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = article.topic.title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The lead in serif with "… Weiterlesen" appended inline: the lead is cut at a word so that
 * the suffix still fits into [maxLines] lines; a lead that fits gets " Weiterlesen" without
 * the ellipsis. Measured once per text and width.
 */
@Composable
internal fun LeadText(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    val style = Reading.feedLead()
    // A quiet continuation: lighter than the lead, never bolder.
    val suffix = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
    val measurer = rememberTextMeasurer(cacheSize = 8)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val annotated = remember(text, width, style, suffix, measurer) {
            leadWithMore(text.trim(), width, style, suffix, measurer, maxLines)
        }
        Text(text = annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private fun leadWithMore(
    text: String,
    width: Int,
    style: TextStyle,
    suffix: SpanStyle,
    measurer: TextMeasurer,
    maxLines: Int,
): AnnotatedString {
    fun build(body: String, cut: Boolean) = buildAnnotatedString {
        append(body)
        append(if (cut) "… " else " ")
        withStyle(suffix) { append(MORE_LABEL) }
    }
    if (width <= 0 || width == Constraints.Infinity) return build(text, cut = false)
    val constraints = Constraints(maxWidth = width)
    fun fits(candidate: AnnotatedString): Boolean =
        !measurer.measure(candidate, style, maxLines = maxLines, constraints = constraints).hasVisualOverflow

    val whole = build(text, cut = false)
    if (fits(whole)) return whole

    // Start where the plain lead is cut off, then step back word by word until the suffix fits.
    val plain = measurer.measure(text, style, maxLines = maxLines, constraints = constraints)
    val lastLine = (minOf(maxLines, plain.lineCount) - 1).coerceAtLeast(0)
    var candidate = text.substring(0, plain.getLineEnd(lastLine, visibleEnd = true).coerceIn(0, text.length))
    repeat(MAX_CUT_STEPS) {
        val body = candidate.trimEnd().trimEnd(',', ';', ':', '–', '-', '.', '…').trimEnd()
        val attempt = build(body, cut = true)
        if (fits(attempt)) return attempt
        val space = body.lastIndexOf(' ')
        if (space <= 0) return attempt
        candidate = body.substring(0, space)
    }
    return build(candidate.trimEnd(), cut = true)
}

private const val MAX_CUT_STEPS = 24

/**
 * Meta line + "Zuhören" + share + ⋮ menu. The time stays whole and only the source is
 * shortened, on one line — the row never wraps or jumps.
 */
@Composable
private fun FeedMetaRow(
    article: FeedArticle,
    meta: FeedMetaParts,
    listening: Boolean,
    paused: Boolean,
    actions: FeedActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            meta.time?.let { time ->
                Text(text = time, style = Reading.meta(), maxLines = 1, softWrap = false)
            }
            meta.source?.let { source ->
                Text(
                    text = if (meta.time != null) " · $source" else source,
                    style = Reading.meta(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        ListenButton(
            listening = listening,
            paused = paused,
            headline = article.item.headline,
            onClick = { if (listening) actions.onStopListening() else actions.onListen(article) },
        )
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(width = 1.dp, height = 20.dp)
                .background(colors.onSurfaceVariant.copy(alpha = 0.3f)),
        )
        IconButton(onClick = { actions.onShare(article) }) {
            Icon(Icons.Rounded.Share, contentDescription = "Teilen", tint = colors.onSurfaceVariant)
        }
        FeedMenu(article = article, actions = actions)
    }
}

/**
 * "🎧 Zuhören"; while this article is read, a little equalizer and "Stopp"; while it is paused,
 * "Weiter". The button always keeps the width of its longest label, so toggling never reflows
 * the row.
 */
@Composable
private fun ListenButton(listening: Boolean, paused: Boolean, headline: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (listening || paused) colors.primary else colors.onSurfaceVariant
    val label = when {
        listening -> "Stopp"
        paused -> "Weiter"
        else -> "Zuhören"
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = when {
                    listening -> "Vorlesen beenden"
                    paused -> "Weiter vorlesen: $headline"
                    else -> "Vorlesen: $headline"
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Invisible widest label: reserves the room, so "Stopp"/"Weiter" do not shrink the button.
        Row(Modifier.graphicsLayer { alpha = 0f }, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            Text(text = "Zuhören", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (listening) {
                Equalizer(color = tint, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Rounded.Headphones, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}

/** Three bars bouncing gently while an article is read aloud (static with reduced motion). */
@Composable
private fun Equalizer(color: Color, modifier: Modifier = Modifier) {
    val still = motionDisabled()
    val phase = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "equalizer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "equalizerPhase",
        )
    }
    Canvas(modifier) {
        val bar = size.width / 5f
        val t = phase?.value ?: 0.3f
        for (i in 0 until 3) {
            val wave = sin((t + i * 0.27f) * 2f * PI.toFloat())
            val h = size.height * (0.3f + 0.6f * wave * wave)
            drawRoundRect(
                color = color,
                topLeft = Offset(bar * (i * 2f), (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

@Composable
private fun FeedMenu(article: FeedArticle, actions: FeedActions) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val source = article.item.source.ifBlank { hostOf(article.item.url) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Weitere Optionen", tint = colors.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            MenuItem("Artikel lesen", Icons.AutoMirrored.Rounded.Article) { open = false; actions.onOpen(article) }
            MenuItem("Im Browser öffnen", Icons.AutoMirrored.Rounded.OpenInNew) { open = false; actions.onOpenExternal(article) }
            MenuItem("Link kopieren", Icons.Rounded.ContentCopy) { open = false; actions.onCopyLink(article) }
            actions.onHideSource?.let { hide ->
                MenuItem("„$source“ ausblenden", Icons.Rounded.VolumeOff) { open = false; hide(article) }
            }
            actions.onHideTopic?.let { hide ->
                MenuItem("Thema „${article.topic.title}“ ausblenden", Icons.Rounded.VisibilityOff) { open = false; hide(article) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/**
 * The full width at [ratio] (width / height), but never taller than [maxHeight]: in landscape
 * a picture as wide as the column would be taller than the window, so it is cropped wider.
 */
private fun Modifier.aspectRatioAtMost(ratio: Float, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = (width / ratio).roundToInt()
        .coerceAtMost(maxHeight.roundToPx())
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
