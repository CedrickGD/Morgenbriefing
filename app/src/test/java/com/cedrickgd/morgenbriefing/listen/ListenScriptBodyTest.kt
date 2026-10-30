package com.cedrickgd.morgenbriefing.listen

import com.cedrickgd.morgenbriefing.data.model.NewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenScriptBodyTest {

    private val item = NewsItem(
        id = "a1", headline = "Bahnstreik geht weiter", summary = "Der Teaser.", url = "https://x.de/a",
        source = "Golem", sourceId = "golem", topicId = "ki",
    )

    @Test
    fun `the full text replaces the teaser and keeps the source`() {
        val script = ListenScript.from(item, 4000, body = "Erster Absatz.\n\nZweiter Absatz.")
        val texts = script.parts.map { it.text }
        assertEquals("Bahnstreik geht weiter", texts.first())
        assertTrue(texts.contains("Erster Absatz."))
        assertTrue(texts.contains("Zweiter Absatz."))
        assertTrue(texts.none { it.contains("Teaser") })
        assertEquals("Quelle: Golem.", texts.last())
    }

    @Test
    fun `without a body the teaser is read as before`() {
        val texts = ListenScript.from(item, 4000, body = "   ").parts.map { it.text }
        assertEquals(listOf("Bahnstreik geht weiter", "Der Teaser.", "Quelle: Golem."), texts)
    }
}
