package com.cedrickgd.morgenbriefing.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.ai
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.ChatContext
import com.cedrickgd.morgenbriefing.ai.ChatMessage
import com.cedrickgd.morgenbriefing.ai.ChatSession
import com.cedrickgd.morgenbriefing.ai.ChatUiState
import com.cedrickgd.morgenbriefing.ai.RetryableChatSession
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.ui.components.entrance
import com.cedrickgd.morgenbriefing.ui.components.rememberEntrance
import com.cedrickgd.morgenbriefing.ui.glass.GlassCard
import com.cedrickgd.morgenbriefing.ui.glass.GlassLevel
import com.cedrickgd.morgenbriefing.ui.glass.GlassShapes
import com.cedrickgd.morgenbriefing.ui.glass.blockTouchesBehind
import com.cedrickgd.morgenbriefing.ui.glass.glass
import com.cedrickgd.morgenbriefing.ui.glass.glassContent
import com.cedrickgd.morgenbriefing.ui.settings.InlineAiKeyDialog
import com.cedrickgd.morgenbriefing.ui.shell.LocalSettingsLink
import com.cedrickgd.morgenbriefing.ui.shell.SettingsTarget
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.ui.theme.Reading
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.launch

/**
 * "Frag nach" (docs/AI.md §1): chat about the article [articleKey] of [date], or about the whole
 * brief of [date] when [articleKey] is null. Full screen over the pager, edge-to-edge.
 */
@Composable
fun ChatScreen(
    date: String,
    articleKey: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember(context, date, articleKey) { context.ai.chat.session(ChatContext(date, articleKey)) }
    // Leaving the chat stops a running answer (the partial text stays); rotating does not.
    viewModel { ChatLeaveStopper(session) }
    val state by session.state.collectAsStateWithLifecycle()
    val settingsLink = LocalSettingsLink.current
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    ChatContent(
        state = state,
        aboutArticle = articleKey != null,
        onBack = onBack,
        // A missing or rejected key is entered right here (Back keeps the user in the chat); the
        // monthly limit and another provider (Claude below Android 8) are the settings' KI section.
        onOpenSettings = {
            when (state.blocked) {
                AiBlock.LIMIT_REACHED -> {
                    settingsLink.request(SettingsTarget.AI)
                    onOpenSettings()
                }
                AiBlock.UNSUPPORTED_DEVICE -> {
                    settingsLink.request(SettingsTarget.AI_PROVIDER)
                    onOpenSettings()
                }
                AiBlock.LIVE_UNAVAILABLE -> {
                    settingsLink.request(SettingsTarget.AI_LIVE_PROVIDER)
                    onOpenSettings()
                }
                AiBlock.NO_KEY, null -> keyDialog = true
            }
        },
        onSend = session::send,
        onStop = session::stop,
        onRetry = { (session as? RetryableChatSession)?.retry() },
        onClear = session::clear,
        modifier = modifier,
    )
    if (keyDialog) InlineAiKeyDialog(provider = state.provider ?: AiProviderId.ANTHROPIC, onDismiss = { keyDialog = false })
}

/**
 * Lives as long as the chat's back stack entry: cleared once the chat is left for good (Back,
 * or the entry is popped otherwise), not when the activity is recreated. Then nothing would
 * read the answer any more, so it is stopped instead of streaming (and billing) in the
 * background; what has streamed so far is kept.
 */
internal class ChatLeaveStopper(private val session: ChatSession) : ViewModel() {
    override fun onCleared() {
        session.stop()
    }
}

/**
 * The chat layout, stateless: messages over the sky, a glass top bar, a floating glass input
 * (or a friendly card when AI is blocked). The message list is a second blur source above the
 * sky, so both bars frost the text that scrolls beneath them.
 */
@Composable
internal fun ChatContent(
    state: ChatUiState,
    aboutArticle: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var draft by rememberSaveable { mutableStateOf("") }
    var topBarHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    val send: (String) -> Unit = { text ->
        if (text.isNotBlank() && !state.busy && state.blocked == null) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onSend(text)
            draft = ""
        }
    }

    val topPadding = with(density) { topBarHeight.toDp() } + 12.dp
    val bottomPadding = with(density) { bottomHeight.toDp() } + 16.dp

    Box(
        modifier
            .fillMaxSize()
            // The keyboard pushes the whole chat up; nested insets below see it as consumed.
            .imePadding(),
    ) {
        if (state.messages.isEmpty()) {
            EmptyChat(
                state = state,
                aboutArticle = aboutArticle,
                onSuggestion = send,
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            )
        } else {
            MessageList(
                state = state,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            )
        }

        ChatTopBar(
            title = state.title,
            canClear = state.messages.isNotEmpty(),
            onBack = onBack,
            onClear = { confirmClear = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeight = it.height },
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomHeight = it.height }
                .navigationBarsPadding()
                .horizontalSafeDrawing()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        ) {
            val blocked = state.blocked
            // A running answer keeps its stop button even if the limit is reached meanwhile.
            if (blocked != null && !state.busy) {
                // Same 20 dp gutter as the cards of the chat above.
                BlockedCard(blocked, state.providerSpec, onOpenSettings, Modifier.padding(horizontal = 8.dp))
            } else {
                ChatInputBar(
                    value = draft,
                    onValueChange = { draft = it },
                    busy = state.busy,
                    busyPlaceholder = answeringPlaceholder(state.providerSpec),
                    onSend = { send(draft) },
                    onStop = onStop,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Verlauf löschen?") },
            text = {
                Text(
                    if (aboutArticle) "Die Unterhaltung zu diesem Artikel wird gelöscht." else "Die Unterhaltung zu diesem Briefing wird gelöscht.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    draft = ""
                    onClear()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    canClear: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .glass(shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp), level = GlassLevel.Thick, floating = true)
            .blockTouchesBehind()
            .padding(top = statusBarTop())
            .horizontalSafeDrawing(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundGlassButton(icon = BackIcon, contentDescription = "Zurück", onClick = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) { heading() },
            ) {
                Text(
                    "✦ FRAG NACH",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    title.ifBlank { " " },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The menu only holds "Verlauf löschen": without a conversation there is nothing to offer.
            if (canClear) Box {
                RoundGlassButton(icon = Icons.Rounded.MoreVert, contentDescription = "Weitere Optionen", onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Verlauf löschen") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                        enabled = canClear,
                        onClick = {
                            menuOpen = false
                            onClear()
                        },
                    )
                }
            }
        }
    }
}

/** True while the "activity" line (thinking / searching) follows the streaming answer. */
private fun footerShown(state: ChatUiState): Boolean {
    val last = state.messages.lastOrNull() ?: return false
    return state.busy && last.role == ChatMessage.Role.ASSISTANT && last.status == ChatMessage.Status.STREAMING &&
        (state.activity != null || last.text.isBlank())
}

@Composable
private fun MessageList(
    state: ChatUiState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val links = rememberLinkActions()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val footer = footerShown(state)
    val note = state.note
    val itemCount = state.messages.size + (if (footer) 1 else 0) + (if (note != null) 1 else 0)
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0

    // Follow the newest text while the reader is at the end; a drag upwards lets them read in peace.
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll -> if (!canScroll) follow = true }
    }
    LaunchedEffect(state.messages.size) {
        follow = true
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1, END_OFFSET)
    }
    LaunchedEffect(lastLength, footer, state.activity) {
        if (follow && itemCount > 0 && !dragged) listState.scrollToItem(itemCount - 1, END_OFFSET)
    }
    // The keyboard (or a taller input) shrinks the viewport: keep the end in view.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect {
            val count = listState.layoutInfo.totalItemsCount
            if (follow && count > 0) listState.scrollToItem(count - 1, END_OFFSET)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // Scrolling the conversation lets the sky behind the transparent chat hold still.
            .reportChromeScroll()
            // The bars above frost the messages gliding beneath them.
            .glassContent(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // What this chat cannot do (no web search) — once, calmly, above the conversation.
        if (note != null) {
            item(key = "chat-note", contentType = "note") {
                QuietNote(note, Modifier.animateItem().padding(horizontal = 2.dp))
            }
        }
        itemsIndexed(state.messages, key = { _, m -> m.id }, contentType = { _, m -> m.role }) { index, message ->
            val appear = rememberEntrance(durationMillis = 420)
            val itemModifier = Modifier
                .animateItem()
                .entrance(appear, distance = 12.dp)
            when (message.role) {
                // A question without answer at the end (stopped early, app closed mid-answer).
                ChatMessage.Role.USER -> if (index == state.messages.lastIndex && !state.busy && state.blocked == null) {
                    Column(itemModifier) {
                        UserBubble(message.text)
                        UnansweredQuestion(onRetry = onRetry)
                    }
                } else {
                    UserBubble(message.text, itemModifier)
                }
                ChatMessage.Role.ASSISTANT -> AssistantMessage(
                    message = message,
                    spec = state.providerSpec,
                    isLast = index == state.messages.lastIndex,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                    onOpenSource = links::open,
                    onCopy = {
                        scope.launch {
                            val label = "Antwort von ${answerMark(state.providerSpec)}"
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, chatPlainText(message.text))))
                            // Android 13+ confirms copies itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, "Antwort kopiert", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = { shareAnswer(context, state.title, message) },
                    modifier = itemModifier,
                )
            }
        }
        if (footer) {
            item(key = "activity", contentType = "activity") {
                ActivityLine(
                    text = state.activity ?: "Denkt nach …",
                    modifier = Modifier.animateItem().padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    spec: ProviderSpec,
    isLast: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSource: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = message.status == ChatMessage.Status.STREAMING
    Column(modifier.fillMaxWidth().padding(end = 4.dp)) {
        ProviderMark(answerMark(spec))
        Spacer(Modifier.height(10.dp))
        if (message.text.isNotBlank()) {
            MarkdownAnswer(text = message.text, streaming = streaming)
        }
        // Google's Search Suggestions belong to a grounded answer whenever it is shown, as wide
        // as the answer and right under it (GE §6.4).
        val suggestions = message.searchSuggestionsHtml
        if (suggestions != null && message.text.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SearchSuggestions(html = suggestions, onOpenLink = onOpenSource)
        }
        message.note?.let { note ->
            Spacer(Modifier.height(10.dp))
            QuietNote(note)
        }
        if (message.error == AiError.Truncated && message.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            TruncatedNote()
        }
        val error = message.error
        if (message.status == ChatMessage.Status.FAILED && error != null) {
            if (message.text.isNotBlank()) Spacer(Modifier.height(12.dp))
            ErrorCard(error = error, spec = spec, canRetry = isLast, onRetry = onRetry, onOpenSettings = onOpenSettings)
        }
        if (message.sources.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SourceChips(message.sources, onOpenSource)
        }
        if (message.status == ChatMessage.Status.DONE && message.text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            AnswerActions(onCopy = onCopy, onShare = onShare, modifier = Modifier.offset(x = (-12).dp))
        }
    }
}

/** First look: what this chat knows, a short intro and suggested questions as glass chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(
    state: ChatUiState,
    aboutArticle: Boolean,
    onSuggestion: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val orb = rememberEntrance(delayMillis = 40)
    val headline = rememberEntrance(delayMillis = 100)
    val card = rememberEntrance(delayMillis = 160)
    val chips = rememberEntrance(delayMillis = 220)
    val glow = pulse(min = 0.4f, max = 1f, periodMillis = 1_800)
    // The chat's note says what it cannot do: without one, it searches the web.
    val webSearch = state.note == null
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding())
            .padding(horizontal = 20.dp),
    ) {
        Box(
            Modifier
                .entrance(orb)
                .size(64.dp)
                .glass(shape = CircleShape, level = GlassLevel.Regular, tint = MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            // A soft glow breathing behind the spark (alpha read in the draw phase only).
            Box(
                Modifier
                    .size(40.dp)
                    .graphicsLayer { alpha = glow.value }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.primary.copy(alpha = 0f)),
                        ),
                    ),
            )
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp).clearAndSetSemantics { },
            )
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.entrance(headline)) {
            Text(
                if (aboutArticle) "FRAG NACH ZUM ARTIKEL" else "FRAG DEIN BRIEFING",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Was möchtest du wissen?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                emptyChatIntro(aboutArticle, webSearch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.note?.let { note ->
                Spacer(Modifier.height(12.dp))
                QuietNote(
                    note,
                    Modifier
                        .frost(shape = GlassShapes.pill, level = GlassLevel.Thin)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        if (aboutArticle && state.title.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth().entrance(card),
                level = GlassLevel.Regular,
                shape = GlassShapes.tile,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    "DEIN ARTIKEL",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    state.title,
                    style = Reading.feedHeadline(0.8f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.suggestions.isNotEmpty()) {
            // While AI is blocked the questions still show what the chat can do — as a quiet preview.
            val blocked = state.blocked != null
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier
                    .entrance(chips)
                    .graphicsLayer { alpha = if (blocked) 0.6f else 1f },
            ) {
                Text(
                    if (blocked) "Das kannst du dann fragen" else "Zum Beispiel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { suggestion ->
                        Row(
                            Modifier
                                .minimumInteractiveComponentSize()
                                .frost(shape = GlassShapes.pill, level = GlassLevel.Thin)
                                .clickable(enabled = !blocked, role = Role.Button, onClickLabel = "Frage stellen") { onSuggestion(suggestion) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(suggestion, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            chatDisclaimer(state.providerSpec, webSearch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shares an answer as plain text, with its sources. */
private fun shareAnswer(context: Context, title: String, message: ChatMessage) {
    val text = buildString {
        if (title.isNotBlank()) append(title.trim()).append("\n\n")
        append(chatPlainText(message.text))
        if (message.sources.isNotEmpty()) {
            append("\n\nQuellen:\n")
            message.sources.forEach { append("• ").append(it.name).append(": ").append(it.url).append('\n') }
        }
    }.trim()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotBlank()) putExtra(Intent.EXTRA_TITLE, title)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Antwort teilen"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Teilen ist auf diesem Gerät gerade nicht möglich.", Toast.LENGTH_SHORT).show()
    }
}

/** Scroll offset that lands on the very end of the last item (LazyColumn clamps it). */
private const val END_OFFSET = 100_000

/** The chat provider (Claude until the session knows it). */
private val ChatUiState.providerSpec: ProviderSpec
    get() = ProviderCatalog.spec(provider ?: AiProviderId.ANTHROPIC)
