package com.cedrickgd.morgenbriefing.data.news

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.FeedSource
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedCatalogTest {

    @Test
    fun `built-in sources have unique ids, https urls and known topics`() {
        val all = FeedCatalog.builtIn + Regions.ALL.flatMap { it.feeds }
        assertEquals(all.size, all.map { it.id }.distinct().size)
        all.forEach { source ->
            assertTrue(source.id, source.url.startsWith("https://"))
            assertTrue(source.id, source.name.isNotBlank())
            assertTrue(source.id, Topics.byId(source.topicId) != null)
            assertTrue(source.id, source.builtIn)
        }
        Topics.ALL.filter { it.id != Topics.REGION_ID }.forEach { topic ->
            assertTrue(topic.id, FeedCatalog.builtIn.any { it.topicId == topic.id })
        }
    }

    @Test
    fun `every region has at least one feed`() {
        // 16 Bundesländer, Berlin and Brandenburg share one preset.
        assertEquals(15, Regions.ALL.size)
        Regions.ALL.forEach { assertTrue(it.key, it.feeds.isNotEmpty()) }
        assertEquals(Regions.DEFAULT, Regions.byKey("gibts-nicht").key)
    }

    @Test
    fun `sources of a topic follow the settings`() {
        val own = FeedSource("custom:1", "Mein Blog", "https://blog.example/feed", "ki", builtIn = false)
        val settings = AppSettings(region = "sachsen", customFeeds = listOf(own), disabledFeeds = setOf("golem"))
        val ki = FeedCatalog.sourcesFor("ki", settings)
        assertTrue(ki.none { it.id == "golem" })
        assertTrue(ki.last().id == "custom:1")
        assertTrue(FeedCatalog.allSourcesFor("ki", settings).any { it.id == "golem" })
        assertEquals(listOf("mdr-sachsen", "lvz", "l-iz"), FeedCatalog.sourcesFor(Topics.REGION_ID, settings).map { it.id })
        assertEquals(own, FeedCatalog.byId("custom:1", settings))
    }

    @Test
    fun `region is guessed from the place label`() {
        assertEquals("sachsen", Regions.guess(Place("Bad Düben", 51.59, 12.58, "Sachsen, Deutschland"))?.key)
        assertEquals("sachsen-anhalt", Regions.guess(Place("Halle", 51.48, 11.97, "Sachsen-Anhalt, Deutschland"))?.key)
        assertEquals("berlin-brandenburg", Regions.guess(Place("Potsdam", 52.4, 13.06, "Brandenburg, Deutschland"))?.key)
        assertEquals("nordrhein-westfalen", Regions.guess(Place("Köln", 50.93, 6.95, "Nordrhein-Westfalen, Deutschland"))?.key)
        assertNull(Regions.guess(Place("Wien", 48.2, 16.37, "Wien, Österreich")))
        assertNull(Regions.guess(Place("Irgendwo", 0.0, 0.0)))
    }

    @Test
    fun `catalogue puts custom topics after the stored order and titles the region`() {
        val custom = Topic("custom:ab", "Fußball", "⚽", 0xFF123456)
        val settings = AppSettings(
            topicOrder = listOf("politik", "ki"),
            customTopics = listOf(custom),
            region = "bayern",
        )
        val catalogue = Topics.catalogue(settings)
        assertEquals("politik", catalogue[0].id)
        assertEquals("ki", catalogue[1].id)
        assertEquals("Bayern", catalogue.first { it.id == Topics.REGION_ID }.title)
        assertTrue(catalogue.last().custom)
        assertEquals("Fußball", Topics.resolve("custom:ab", settings)?.title)
        assertEquals(Topics.NEUTRAL_SEED, Topics.resolveOrNeutral("custom:zz", settings).seed)
        assertEquals(catalogue.size - 1, Topics.visible(settings.copy(hiddenTopics = setOf("sport"))).size)
    }
}
