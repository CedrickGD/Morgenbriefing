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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ui.components.Emoji
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.components.pressScale
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.theme.OverlineLetterSpacing
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin

/** Feed pictures: 16:10 like the reference, 22 dp corners (docs/DESIGN.md §7). */
private const val FEED_IMAGE_RATIO = 16f / 10f

/** The tappable suffix of a feed lead (docs/DESIGN.md §7). */
internal const val MORE_LABEL = "Mehr anzeigen"

/**
 * Everything a feed entry can do. Null callbacks hide their menu item (e.g. "Frag nach" below
 * Android 8, "Thema ausblenden" where it makes no sense). Create it once per screen
 * (`remember`) so entries stay skippable.
 */
@Stable
class FeedActions(
    val onOpen: (FeedArticle) -> Unit,
    val onListen: (FeedArticle) -> Unit,
    val onStopListening: () -> Unit,
    val onShare: (FeedArticle) -> Unit,
    val onShowSources: (FeedArticle) -> Unit,
    val onCopyLink: (FeedArticle) -> Unit,
    val onHideTopic: ((FeedArticle) -> Unit)? = null,
    val onAsk: ((FeedArticle) -> Unit)? = null,
)

/**
 * One article in the editorial feed, without a card frame, directly on the sky (the user's
 * reference): a full-width picture (or generated cover) with a tiny credit, the headline big
 * in serif, the serif lead with an inline "… Mehr anzeigen" and a meta row with "Zuhören",
 * share and a ⋮ menu. Picture, headline and lead open the reader.
 *
 * @param listening this article is being read aloud right now — the button turns into "Stopp".
 * @param showTopic adds a small topic overline (mixed views: "Top", "⚡ Live").
 * @param badge replaces the meta line, e.g. "⚡ neu · vor 12 Min." for live articles.
 */
@Composable
fun FeedEntry(
    article: FeedArticle,
    now: ZonedDateTime,
    listening: Boolean,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    showTopic: Boolean = false,
    badge: String? = null,
) {
    val item = article.item
    val interaction = remember { MutableInteractionSource() }
    var imageStatus by remember(item.image) {
        mutableStateOf(if (item.image?.safeUrl() != null) ImageStatus.LOADING else ImageStatus.NONE)
    }
    val meta = remember(item, now, badge) { if (badge != null) "" else feedMeta(item, now) }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .pressScale(interaction, pressedScale = 0.985f)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Artikel lesen",
                    onClick = { actions.onOpen(article) },
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(FEED_IMAGE_RATIO)
                    .clip(GlassShapes.tile),
            ) {
                ArticleVisual(
                    item = item,
                    topic = article.topic,
                    modifier = Modifier.matchParentSize(),
                    contentDescription = item.image?.alt?.takeIf { it.isNotBlank() },
                    emojiAlignment = Alignment.Center,
                    emojiFraction = 0.36f,
                    onStatus = { imageStatus = it },
                )
                val image = item.image
                if (image != null && imageStatus == ImageStatus.SHOWN) {
                    CreditTag(image.creditLine(), Modifier.align(Alignment.BottomEnd))
                }
                if (article.live) LiveTag(Modifier.align(Alignment.TopStart))
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
            badge = badge,
            listening = listening,
            actions = actions,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Compact entry for the "more" articles of a topic: serif headline and meta on the left,
 * a small picture on the right. Opens the reader.
 */
@Composable
fun FeedRow(article: FeedArticle, now: ZonedDateTime, onOpen: (FeedArticle) -> Unit, modifier: Modifier = Modifier) {
    val item = article.item
    val interaction = remember { MutableInteractionSource() }
    val meta = remember(item, now) { feedMeta(item, now) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Artikel lesen",
                onClick = { onOpen(article) },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.headline,
                style = Reading.feedHeadline().copy(fontSize = 19.sp, lineHeight = 25.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(text = meta, style = Reading.meta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(14.dp))
        ArticleVisual(
            item = item,
            topic = article.topic,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(18.dp)),
            emojiAlignment = Alignment.Center,
            emojiFraction = 0.42f,
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

/** Attribution on the picture ("Bild: Ank Kumar · CC BY-SA 4.0"), tiny and quiet. */
@Composable
private fun CreditTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.2.sp),
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(8.dp)
            .widthIn(max = 260.dp)
            .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** "⚡ NEU" on the picture of a live-researched article. */
@Composable
private fun LiveTag(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(10.dp)
            .background(LiveColors.fill, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji("⚡", size = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "NEU",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = OverlineLetterSpacing, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

/**
 * The lead in serif with "… Mehr anzeigen" appended inline: the lead is cut at a word so that
 * the suffix still fits into [maxLines] lines; a lead that fits gets " Mehr anzeigen" without
 * the ellipsis. Measured once per text and width.
 */
@Composable
internal fun LeadText(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    val style = Reading.feedLead()
    val suffix = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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

/** Meta line + "Zuhören" + share + ⋮ menu (with "Thema ausblenden", sources, copy, ask). */
@Composable
private fun FeedMetaRow(
    article: FeedArticle,
    meta: String,
    badge: String?,
    listening: Boolean,
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
        // Live articles show only their badge here; the source is one tap away (⋮ → Quellen).
        val metaText = remember(meta, badge, colors.primary) {
            if (badge == null) {
                AnnotatedString(meta)
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)) { append(badge) }
                }
            }
        }
        Text(
            text = metaText,
            style = Reading.meta(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ListenButton(
            listening = listening,
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

/** "🎧 Zuhören" — or, while this article is being read, a little equalizer and "Stopp". */
@Composable
private fun ListenButton(listening: Boolean, headline: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (listening) colors.primary else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (listening) "Vorlesen beenden" else "Vorlesen: $headline"
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (listening) {
            Equalizer(color = tint, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Rounded.Headphones, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (listening) "Stopp" else "Zuhören",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
        )
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
            actions.onHideTopic?.let { hide ->
                MenuItem("Thema ausblenden", Icons.Rounded.VisibilityOff) { open = false; hide(article) }
            }
            MenuItem("Quellen anzeigen", Icons.Rounded.Link) { open = false; actions.onShowSources(article) }
            MenuItem("Link kopieren", Icons.Rounded.ContentCopy) { open = false; actions.onCopyLink(article) }
            actions.onAsk?.let { ask ->
                MenuItem("Frag nach", Icons.Rounded.AutoAwesome, tint = colors.primary) { open = false; ask(article) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** Accent for everything "⚡ Live": a deep amber that carries white text (5:1) in light and dark. */
internal object LiveColors {
    val fill: Color = Color(0xFFB4530F)
}
