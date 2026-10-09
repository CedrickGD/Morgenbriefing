package com.cedrickgd.morgenbriefing.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.hostOf
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.ui.components.motionDisabled
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.glassEdgeBrush
import com.cedrickgd.morgenbriefing.ui.glass.isGlassDark
import com.cedrickgd.morgenbriefing.ui.theme.Reading

/** Reading style of the AI's answers: the reader's serif body, a touch smaller (docs/DESIGN.md §5). */
@Composable
internal fun answerTextStyle(): TextStyle = Reading.articleBody(0.9f)

/**
 * The glass look without a blur layer — for the many small surfaces inside the message list
 * (bubbles, chips). They only ever sit on the smooth sky, where a blur would look the same as a
 * tint, and skipping one RenderEffect per chip keeps scrolling at 60 fps. Same tint levels,
 * sheen and light edge as [glass]; a little denser since nothing is blurred.
 */
internal fun Modifier.frost(
    shape: Shape,
    level: GlassLevel = GlassLevel.Thin,
    tint: Color? = null,
): Modifier = composed {
    val dark = isGlassDark()
    val base = tint ?: MaterialTheme.colorScheme.surface
    val alpha = ((if (dark) level.tintDark else level.tintLight) + 0.14f).coerceAtMost(0.92f)
    val sheen = Color.White.copy(alpha = if (dark) 0.07f else 0.16f)
    val edge = glassEdgeBrush()
    clip(shape)
        .background(base.copy(alpha = alpha))
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to sheen,
                    (14.dp.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent,
                ),
            )
        }
        .border(1.dp, edge, shape)
}

// -------------------------------------------------------------------------------------------
// Round buttons and badges
// -------------------------------------------------------------------------------------------

/** A 48 dp round button on glass: subtle fill, light edge, ripple. */
@Composable
internal fun RoundGlassButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .border(1.dp, glassEdgeBrush(), CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** The small "✦ Claude" mark above an answer, with the [name] of who answers ([answerMark]). */
@Composable
internal fun ProviderMark(name: String, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A calm line with a small icon: that this chat answers without web search (free Gemini key,
 * providers without search), or under one answer that it came without it.
 */
@Composable
internal fun QuietNote(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.SearchOff) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -------------------------------------------------------------------------------------------
// Messages
// -------------------------------------------------------------------------------------------

/** The user's question: right-aligned, in a glass bubble tinted with the accent container. */
@Composable
internal fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .frost(
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp),
                    level = GlassLevel.Regular,
                    tint = MaterialTheme.colorScheme.primaryContainer,
                )
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .semantics { contentDescription = "Du: $text" },
        )
    }
}

/**
 * The answer rendered from light markdown in the reading serif; while [streaming], a soft
 * pulsing cursor sits at the end of the text.
 */
@Composable
internal fun MarkdownAnswer(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseChatMarkdown(text) }
    val style = answerTextStyle()
    val cursor = if (streaming) rememberCursorContent() else emptyMap()
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEachIndexed { index, block ->
                val withCursor = streaming && index == blocks.lastIndex
                when (block) {
                    is MdBlock.Paragraph -> Text(annotated(block.spans, withCursor), style = style, inlineContent = cursor)
                    is MdBlock.Heading -> Text(
                        annotated(block.spans, withCursor),
                        style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.08f),
                        inlineContent = cursor,
                        modifier = Modifier.semantics { heading() },
                    )
                    is MdBlock.Bullets -> ListBlock(
                        items = block.items.mapIndexed { i, spans -> "•" to annotated(spans, withCursor && i == block.items.lastIndex) },
                        style = style,
                        cursor = cursor,
                    )
                    is MdBlock.Numbered -> ListBlock(
                        items = block.items.mapIndexed { i, (n, spans) -> "$n." to annotated(spans, withCursor && i == block.items.lastIndex) },
                        style = style,
                        cursor = cursor,
                    )
                }
            }
            if (blocks.isEmpty() && streaming) Text(annotated(emptyList(), true), style = style, inlineContent = cursor)
        }
    }
}

@Composable
private fun ListBlock(items: List<Pair<String, AnnotatedString>>, style: TextStyle, cursor: Map<String, InlineTextContent>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (marker, text) ->
            Row {
                Text(
                    marker,
                    style = style.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.widthIn(min = 22.dp).clearAndSetSemantics { },
                )
                Text(text, style = style, inlineContent = cursor, modifier = Modifier.weight(1f))
            }
        }
    }
}

private const val CURSOR_ID = "cursor"

private fun annotated(spans: List<MdSpan>, cursor: Boolean): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            ),
        ) { append(span.text) }
    }
    if (cursor) appendInlineContent(CURSOR_ID, "")
}

@Composable
private fun rememberCursorContent(): Map<String, InlineTextContent> {
    val color = MaterialTheme.colorScheme.primary
    val alpha = pulse(min = 0.2f, max = 1f, periodMillis = 560)
    return remember(color, alpha) {
        mapOf(
            CURSOR_ID to InlineTextContent(Placeholder(0.5.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 3.dp, top = 2.dp, bottom = 2.dp)
                        .graphicsLayer { this.alpha = alpha.value }
                        .background(color, RoundedCornerShape(2.dp)),
                )
            },
        )
    }
}

/** A value pulsing between [min] and [max] (static at [max] when motion is reduced). Read it in draw. */
@Composable
internal fun pulse(min: Float, max: Float, periodMillis: Int): State<Float> =
    if (motionDisabled()) {
        remember { mutableFloatStateOf(max) }
    } else {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = min,
            targetValue = max,
            animationSpec = infiniteRepeatable(tween(periodMillis), RepeatMode.Reverse),
            label = "pulseValue",
        )
    }

/**
 * What the AI is doing right now ("Suche im Netz: …", "Denkt nach …"): a quiet line with a
 * light sweeping over it. The sweep is drawn in the draw phase only.
 */
@Composable
internal fun ActivityLine(text: String, modifier: Modifier = Modifier) {
    val reduced = motionDisabled()
    val sweep = if (reduced) {
        remember { mutableFloatStateOf(-1f) }
    } else {
        rememberInfiniteTransition(label = "activity").animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing)),
            label = "activitySweep",
        )
    }
    val sparkle = pulse(min = 0.45f, max = 1f, periodMillis = 900)
    val highlight = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .heightIn(min = 32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = highlight,
            modifier = Modifier.size(16.dp).graphicsLayer { alpha = sparkle.value },
        )
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "activityText",
        ) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val x = sweep.value * size.width
                        val band = size.width * 0.35f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, highlight.copy(alpha = 0.95f), Color.Transparent),
                                start = Offset(x - band, 0f),
                                end = Offset(x + band, 0f),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
        }
    }
}

/** Web sources under an answer: small glass pills, tap opens the page in a Custom Tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceChips(sources: List<SourceRef>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "Quellen",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sources.forEach { source -> SourceChip(source, onOpen) }
        }
    }
}

@Composable
private fun SourceChip(source: SourceRef, onOpen: (String) -> Unit) {
    val host = hostOf(source.url)
    val title = source.name.takeIf { it.isNotBlank() && it != host }
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .frost(shape = GlassShapes.pill, level = GlassLevel.Thin)
            .clickable(role = Role.Button, onClickLabel = "Quelle öffnen") { onOpen(source.url) }
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Quelle: ${title ?: host}, $host" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(host, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (title != null) {
            Text(
                " · $title",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

/** Copy and share under a finished answer (48 dp targets, quiet icons). */
@Composable
internal fun AnswerActions(onCopy: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Antwort kopieren", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = "Antwort teilen", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * Under the last question when it never got an answer (stopped before the first word, or the
 * app was closed mid-answer): one tap asks it again.
 */
@Composable
internal fun UnansweredQuestion(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Noch ohne Antwort",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Nochmal fragen")
        }
    }
}

/** "Antwort gekürzt" under a truncated answer. */
@Composable
internal fun TruncatedNote(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Antwort gekürzt – frag gern nach dem Rest.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A failed answer as a small glass card: what happened and, where it helps, "Nochmal" or the
 * way to the settings (rejected key).
 */
@Composable
internal fun ErrorCard(
    error: AiError,
    spec: ProviderSpec,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refusal = error is AiError.Refusal
    GlassCard(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        level = GlassLevel.Regular,
        shape = GlassShapes.tile,
        tint = if (refusal) null else MaterialTheme.colorScheme.errorContainer,
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (error) {
                    AiError.Network -> Icons.Rounded.WifiOff
                    AiError.InvalidKey -> Icons.Rounded.Key
                    is AiError.Refusal -> Icons.Rounded.Shield
                    else -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
                tint = if (refusal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 1.dp).size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                AiTexts.error(error, spec),
                style = MaterialTheme.typography.bodyMedium,
                color = if (refusal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (AiTexts.needsKeyChange(error)) {
                TextButton(onClick = onOpenSettings) { Text("Key ändern") }
            } else if (canRetry && AiTexts.isRetryable(error)) {
                TextButton(onClick = onRetry) { Text("Nochmal versuchen") }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Bottom: input bar and blocked card
// -------------------------------------------------------------------------------------------

/**
 * The glass input: a multiline field and a round button that sends — or, while the answer
 * streams ([busyPlaceholder], e.g. "Gemini antwortet …"), stops it.
 */
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    busyPlaceholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = value.isNotBlank() && !busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = RoundedCornerShape(28.dp), level = GlassLevel.Thick, floating = true)
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (busy) busyPlaceholder else "Frag nach …", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            textStyle = MaterialTheme.typography.bodyLarge,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Box(Modifier.padding(bottom = 4.dp)) {
            SendStopButton(busy = busy, canSend = canSend, onSend = onSend, onStop = onStop)
        }
    }
}

@Composable
private fun SendStopButton(busy: Boolean, canSend: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val enabled = busy || canSend
    val container = when {
        busy -> scheme.onSurface
        canSend -> scheme.primary
        else -> scheme.onSurface.copy(alpha = 0.12f)
    }
    val content = when {
        busy -> scheme.surface
        canSend -> scheme.onPrimary
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(color = content),
            ) { if (busy) onStop() else onSend() }
            .semantics { contentDescription = if (busy) "Antwort stoppen" else "Frage senden" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = busy,
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f)) },
            label = "sendStop",
        ) { stopping ->
            Icon(
                imageVector = if (stopping) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(if (stopping) 20.dp else 22.dp),
            )
        }
    }
}

/**
 * Why the chat cannot answer here (no key, limit reached, Claude below Android 8): a friendly
 * glass card in place of the input, with the one way forward ([AiTexts.blockAction]: the key
 * dialog, the monthly limit, another provider).
 */
@Composable
internal fun BlockedCard(block: AiBlock, spec: ProviderSpec, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        level = GlassLevel.Thick,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (block) {
                        AiBlock.NO_KEY -> Icons.Rounded.Key
                        AiBlock.LIMIT_REACHED -> Icons.Rounded.Savings
                        AiBlock.UNSUPPORTED_DEVICE -> Icons.Rounded.PhoneAndroid
                        AiBlock.LIVE_UNAVAILABLE -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AiTexts.blockTitle(block, spec),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(AiTexts.blockBody(block, spec), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            // The one way forward: a filled accent button, like every other key/token action.
            Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(AiTexts.blockAction(block, spec))
            }
        }
    }
}

/** Back arrow used by the top bar (auto-mirrored for RTL). */
internal val BackIcon: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
