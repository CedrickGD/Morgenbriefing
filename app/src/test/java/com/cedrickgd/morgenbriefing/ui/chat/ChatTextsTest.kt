package com.cedrickgd.morgenbriefing.ui.chat

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.ChatMessage
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextsTest {

    @Test
    fun `the answer mark names the provider`() {
        assertEquals("Claude", answerMark(ProviderCatalog.ANTHROPIC))
        assertEquals("Gemini", answerMark(ProviderCatalog.GEMINI))
        assertEquals("Grok", answerMark(ProviderCatalog.XAI))
        assertEquals("Dein Server", answerMark(ProviderCatalog.CUSTOM))
        assertEquals("Gemini antwortet …", answeringPlaceholder(ProviderCatalog.GEMINI))
    }

    @Test
    fun `an answer stays its author's after the chat provider changed`() {
        val quota = ChatMessage("a", ChatMessage.Role.ASSISTANT, "", status = ChatMessage.Status.FAILED, error = AiError.QuotaExhausted, provider = AiProviderId.GEMINI)
        // The chat now runs on OpenAI; the stored Gemini quota card still speaks of Gemini.
        val spec = answerSpec(quota, chat = ProviderCatalog.OPENAI)
        assertEquals(AiProviderId.GEMINI, spec.id)
        assertEquals("Gemini", answerMark(spec))
        assertTrue(AiTexts.error(quota.error!!, spec).startsWith("Gemini-Gratiskontingent"))
        // A rejected Claude key opens Claude's key dialog, not the current provider's.
        val rejected = quota.copy(error = AiError.InvalidKey, provider = AiProviderId.ANTHROPIC)
        assertEquals(AiProviderId.ANTHROPIC, answerSpec(rejected, chat = ProviderCatalog.CUSTOM).id)
        // Answers saved before v1.3.0 name no provider: the chat's.
        assertEquals(AiProviderId.OPENAI, answerSpec(quota.copy(provider = null), chat = ProviderCatalog.OPENAI).id)
    }

    @Test
    fun `the disclaimer names who writes and where to check`() {
        assertEquals(
            "Antworten schreibt Claude von Anthropic – sie können Fehler enthalten. Prüf Wichtiges in den verlinkten Quellen.",
            chatDisclaimer(ProviderCatalog.ANTHROPIC, webSearch = true),
        )
        val free = chatDisclaimer(ProviderCatalog.GEMINI, webSearch = false)
        assertTrue(free.startsWith("Antworten schreibt Gemini von Google"))
        assertFalse(free.contains("verlinkten Quellen"))
        ProviderCatalog.all.forEach { assertTrue(answerAuthor(it).isNotBlank()) }
    }

    @Test
    fun `without web search the chat does not promise to look things up`() {
        assertTrue(emptyChatIntro(aboutArticle = true, webSearch = true).contains("im Netz"))
        assertTrue(emptyChatIntro(aboutArticle = false, webSearch = true).contains("im Netz"))
        assertFalse(emptyChatIntro(aboutArticle = true, webSearch = false).contains("Netz"))
        assertFalse(emptyChatIntro(aboutArticle = false, webSearch = false).contains("Netz"))
        assertTrue(emptyChatIntro(aboutArticle = false, webSearch = false).contains("Briefing"))
    }

    @Test
    fun `the streaming cursor has a text alternative compose accepts`() {
        // An empty alternate text made appendInlineContent throw while an answer streamed.
        assertTrue(CURSOR_ALTERNATE_TEXT.isNotEmpty())
    }

    @Test
    fun `gemini grounding sources show their domain, not google's redirect host`() {
        val grounded = SourceRef("tagesschau.de", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQ")
        assertEquals("tagesschau.de" to null, sourceChipParts(grounded))
        assertEquals("zeit.de" to "Hintergrund", sourceChipParts(SourceRef("Hintergrund", "https://www.zeit.de/x")))
        assertEquals("zeit.de" to null, sourceChipParts(SourceRef("zeit.de", "https://zeit.de/x")))
        // A redirect without a title still shows something.
        assertEquals("vertexaisearch.cloud.google.com" to null, sourceChipParts(SourceRef("", grounded.url)))
    }

    @Test
    fun `a refused gemini auth key offers openrouter for free next to the key`() {
        val auth = com.cedrickgd.morgenbriefing.ai.AiError.GeminiAccess(com.cedrickgd.morgenbriefing.ai.GeminiProblem.AUTH_KEY_REJECTED)
        assertEquals(
            listOf(ErrorAction.OPEN_LINK, ErrorAction.SET_UP_OPENROUTER, ErrorAction.CHANGE_KEY, ErrorAction.RESEND),
            errorActions(auth, canRetry = true),
        )
        assertEquals("OpenRouter kostenlos einrichten", ErrorAction.SET_UP_OPENROUTER.label)
        assertEquals(listOf(ErrorAction.OPEN_LINK, ErrorAction.SET_UP_OPENROUTER, ErrorAction.CHANGE_KEY), errorActions(auth, canRetry = false))
        // Other errors keep their buttons.
        assertEquals(listOf(ErrorAction.RETRY), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.Network, canRetry = true))
        assertEquals(emptyList<ErrorAction>(), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.Network, canRetry = false))
    }

    @Test
    fun `a failed answer can be sent again once its cause is fixed`() {
        assertEquals("Nochmal senden", ErrorAction.RESEND.label)
        // A new key, credits, another model or tariff fix it elsewhere: then "Nochmal senden".
        assertEquals(listOf(ErrorAction.CHANGE_KEY, ErrorAction.RESEND), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.InvalidKey, canRetry = true))
        assertEquals(listOf(ErrorAction.RESEND), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.NoCredit, canRetry = true))
        assertEquals(listOf(ErrorAction.RESEND), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.ModelUnavailable("m"), canRetry = true))
        assertEquals(listOf(ErrorAction.RESEND), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.Truncated, canRetry = true))
        assertEquals(
            listOf(ErrorAction.OPEN_LINK, ErrorAction.RESEND),
            errorActions(com.cedrickgd.morgenbriefing.ai.AiError.GeminiAccess(com.cedrickgd.morgenbriefing.ai.GeminiProblem.API_BLOCKED), canRetry = true),
        )
        // Busy or not the last answer: nothing to send; a refusal is never sent again unchanged.
        assertEquals(listOf(ErrorAction.CHANGE_KEY), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.InvalidKey, canRetry = false))
        assertEquals(emptyList<ErrorAction>(), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.Refusal("cyber"), canRetry = true))
        // A retry that may simply work keeps its own words.
        assertEquals(listOf(ErrorAction.RETRY), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.QuotaExhausted, canRetry = true))
        assertEquals(
            listOf(ErrorAction.RETRY),
            errorActions(com.cedrickgd.morgenbriefing.ai.AiError.QuotaExhausted, canRetry = true, provider = AiProviderId.GEMINI),
        )
    }

    @Test
    fun `openrouter's used-up day is sent again once something changed, not retried`() {
        val quota = com.cedrickgd.morgenbriefing.ai.AiError.QuotaExhausted
        assertEquals(listOf(ErrorAction.RESEND), errorActions(quota, canRetry = true, provider = AiProviderId.OPENROUTER))
        val credits = com.cedrickgd.morgenbriefing.ai.AiError.Unknown(com.cedrickgd.morgenbriefing.ai.provider.OpenRouterTiers.QUOTA_EXHAUSTED_WITH_CREDITS)
        assertEquals(listOf(ErrorAction.RESEND), errorActions(credits, canRetry = true, provider = AiProviderId.OPENROUTER))
        // A busy moment still offers a retry.
        assertEquals(
            listOf(ErrorAction.RETRY),
            errorActions(com.cedrickgd.morgenbriefing.ai.AiError.RateLimited, canRetry = true, provider = AiProviderId.OPENROUTER),
        )
        assertEquals(listOf(ErrorAction.RETRY), errorActions(com.cedrickgd.morgenbriefing.ai.AiError.Unknown("x"), canRetry = true))
    }
}
