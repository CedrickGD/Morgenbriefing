package com.cedrickgd.morgenbriefing.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.ChatMessage
import com.cedrickgd.morgenbriefing.ai.ChatUiState
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme

private val previewConversation = ChatUiState(
    title = "Neue Buslinie verbindet Bad Düben mit Leipzig",
    messages = listOf(
        ChatMessage("1", ChatMessage.Role.USER, "Erklär mir den Hintergrund"),
        ChatMessage(
            "2",
            ChatMessage.Role.ASSISTANT,
            "Die Linie schließt eine Lücke im **Nahverkehr Nordsachsens**: Bisher mussten Pendler aus Bad Düben " +
                "in Eilenburg umsteigen.\n\n- stündlich ab Dezember\n- der Landkreis zahlt mit\n\nOb der Takt reicht, " +
                "hängt laut LVZ von der Nachfrage im ersten Jahr ab.",
            sources = listOf(
                SourceRef("Neue Buslinie ab Dezember", "https://www.lvz.de/leipzig/buslinie-100.html"),
                SourceRef("Landkreis beteiligt sich", "https://www.mdr.de/nachrichten/sachsen/buslinie-100.html"),
            ),
        ),
        ChatMessage("3", ChatMessage.Role.USER, "Was sagen andere Quellen?"),
        ChatMessage("4", ChatMessage.Role.ASSISTANT, "Laut MDR plant der Landkreis", status = ChatMessage.Status.STREAMING),
    ),
    busy = true,
    activity = "Suche im Netz: Buslinie Bad Düben Leipzig",
)

@Composable
private fun PreviewStage(dark: Boolean, content: @Composable () -> Unit) {
    MorgenbriefingTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, accent = AccentId.MORGENROT) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background, scheme.tertiaryContainer))),
        ) { content() }
    }
}

@Preview(name = "Chat – Gespräch", widthDp = 412, heightDp = 892)
@Composable
private fun ChatConversationPreview() = PreviewStage(dark = false) {
    ChatContent(previewConversation, aboutArticle = true, onBack = {}, onOpenSettings = {}, onSend = {}, onStop = {}, onRetry = {}, onClear = {})
}

@Preview(name = "Chat – dunkel, Fehler", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatErrorPreview() = PreviewStage(dark = true) {
    ChatContent(
        previewConversation.copy(
            busy = false,
            activity = null,
            messages = previewConversation.messages.take(3) +
                ChatMessage("4", ChatMessage.Role.ASSISTANT, "", status = ChatMessage.Status.FAILED, error = AiError.Network),
        ),
        aboutArticle = true,
        onBack = {}, onOpenSettings = {}, onSend = {}, onStop = {}, onRetry = {}, onClear = {},
    )
}

@Preview(name = "Chat – leer", widthDp = 412, heightDp = 892)
@Composable
private fun ChatEmptyPreview() = PreviewStage(dark = false) {
    ChatContent(
        ChatUiState(title = previewConversation.title, suggestions = AiPrompts.ARTICLE_SUGGESTIONS),
        aboutArticle = true,
        onBack = {}, onOpenSettings = {}, onSend = {}, onStop = {}, onRetry = {}, onClear = {},
    )
}

@Preview(name = "Chat – kein Key", widthDp = 412, heightDp = 892, fontScale = 1.3f)
@Composable
private fun ChatNoKeyPreview() = PreviewStage(dark = false) {
    ChatContent(
        ChatUiState(title = AiPrompts.BRIEF_TITLE, suggestions = AiPrompts.BRIEF_SUGGESTIONS, blocked = AiBlock.NO_KEY),
        aboutArticle = false,
        onBack = {}, onOpenSettings = {}, onSend = {}, onStop = {}, onRetry = {}, onClear = {},
    )
}
