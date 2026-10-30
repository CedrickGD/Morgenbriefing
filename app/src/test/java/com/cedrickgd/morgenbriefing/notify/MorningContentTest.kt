package com.cedrickgd.morgenbriefing.notify

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.TopicNews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningContentTest {

    private fun news(topic: String, vararg headlines: String) = TopicNews(
        topicId = topic,
        fetchedAt = "2026-10-29T06:00:00+01:00",
        items = headlines.mapIndexed { i, h -> NewsItem("$topic-$i", h, url = "https://x/$topic/$i", source = "S", sourceId = "s", topicId = topic) },
    )

    @Test
    fun `one headline per visible topic in the user's order`() {
        val settings = AppSettings(topicOrder = listOf("sport", "politik", "ki"), hiddenTopics = setOf("politik"))
        val repo = mapOf(
            "ki" to news("ki", "KI eins", "KI zwei"),
            "politik" to news("politik", "Politik eins"),
            "sport" to news("sport", "Sport eins"),
            "welt" to news("welt", "Welt eins"),
        )
        assertEquals(listOf("Sport eins", "KI eins", "Welt eins"), topHeadlines(repo, settings))
        assertEquals(listOf("Sport eins"), topHeadlines(repo, settings, count = 1))
        assertTrue(topHeadlines(emptyMap(), settings).isEmpty())
    }

    @Test
    fun `notification text carries the headlines and survives without weather`() {
        val text = morningText(null, listOf("Eins", "Zwei", "Drei", "Vier"))
        assertEquals("Eins", text.text)
        assertTrue(text.bigText.contains("📰 Eins · Zwei · Drei"))
        val none = morningText(null, emptyList())
        assertTrue(none.bigText.contains("Die Nachrichten warten in der App."))
    }
}
