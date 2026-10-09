package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.LiveToolNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The Claude prompts must stay byte-identical to v1.2 (the cached prompt prefix of every Claude
 * user would be invalidated otherwise); the variants for other providers only swap the lines
 * that name tools or web search.
 */
class AiPromptsTest {
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `the Claude chat prompt is the v1_2 prompt byte for byte`() {
        val prompt = AiPrompts.chatSystem(webSearch = true)
        assertEquals(2121, prompt.length)
        assertEquals("33b1613809f4303beae7a999fc4614afa231ee061fad2cd5fa7fa3f895ba2fdb", sha256(prompt))
    }

    @Test
    fun `the Claude Live prompt is the v1_2 prompt byte for byte`() {
        val prompt = AiPrompts.liveSystem(LiveToolNote.SEARCH_AND_FETCH)
        assertEquals(5173, prompt.length)
        assertEquals("4c9a80774ba903712685194cce9728b0c42e49ff4f899166201ce5e9adf15074", sha256(prompt))
    }

    @Test
    fun `without web search the chat says what it does not know`() {
        val web = AiPrompts.chatSystem(webSearch = true)
        val offline = AiPrompts.chatSystem(webSearch = false)
        assertNotEquals(web, offline)
        assertTrue(offline.contains("Du hast hier keinen Internetzugang"))
        assertTrue(offline.contains("Erfinde keine Quellen."))
        assertFalse(offline.contains("nutze die Websuche"))
        assertFalse(offline.contains("Websuche"))
        // Only that one bullet differs.
        val webLines = web.lines()
        val offlineLines = offline.lines()
        assertEquals(webLines.size, offlineLines.size)
        assertEquals(1, webLines.indices.count { webLines[it] != offlineLines[it] })
        // Stable: the same object every time (cache-friendly), no date in it.
        assertTrue(offline === AiPrompts.chatSystem(webSearch = false))
        assertFalse(offline.contains("Heute ist"))
    }

    @Test
    fun `example chips without web search ask only what the context answers`() {
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS, AiPrompts.chatSuggestions(article = true, webSearch = true))
        val offline = AiPrompts.chatSuggestions(article = true, webSearch = false)
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS_OFFLINE, offline)
        assertTrue("Was sagen andere Quellen?" in AiPrompts.ARTICLE_SUGGESTIONS)
        assertTrue(offline.none { "Quellen" in it || "Netz" in it || "aktuell" in it.lowercase() })
        assertEquals(AiPrompts.ARTICLE_SUGGESTIONS.size, offline.size)
        // The brief's examples come from the brief itself, with or without search.
        assertEquals(AiPrompts.BRIEF_SUGGESTIONS, AiPrompts.chatSuggestions(article = false, webSearch = true))
        assertEquals(AiPrompts.BRIEF_SUGGESTIONS, AiPrompts.chatSuggestions(article = false, webSearch = false))
    }

    @Test
    fun `the search-only Live prompt names no Claude tool`() {
        val claude = AiPrompts.liveSystem(LiveToolNote.SEARCH_AND_FETCH)
        val other = AiPrompts.liveSystem(LiveToolNote.SEARCH_ONLY)
        assertTrue(claude.contains("web_fetch") && claude.contains("web_search"))
        assertFalse(other.contains("web_fetch"))
        assertFalse(other.contains("web_search"))
        assertTrue(other.contains("Recherchiere mit der Websuche (deutsch und englisch)"))
        assertTrue(other.contains("Prüfe jede Geschichte an mindestens zwei Quellen aus der Websuche."))
        assertTrue(other.contains("Lies die Quellen, die dir die Websuche liefert"))
        assertTrue(other.contains("Schreib vor dem Aufruf keinen Text."))
        // The client tool keeps its name everywhere, and so do the rules and topics.
        assertTrue(other.contains("publish_articles"))
        assertTrue(other.contains("Nur Neues:"))
        assertTrue(other.contains("- region (Leipzig & Region):"))
        assertEquals(claude.lines().size, other.lines().size)
    }
}
