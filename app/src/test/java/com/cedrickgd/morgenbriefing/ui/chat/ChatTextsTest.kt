package com.cedrickgd.morgenbriefing.ui.chat

import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
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
}
