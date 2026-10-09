package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.http.toJsonElement
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant

internal object LiveFixtures {
    val NOW: Instant = Instant.parse("2026-10-05T12:30:00Z")

    private val BODY = buildString {
        repeat(4) { p ->
            append("Absatz ${p + 1}: Die Leipziger Verkehrsbetriebe haben am Montag eine neue Buslinie zwischen ")
            append("Bad Düben und Leipzig angekündigt. Nach Angaben des Unternehmens soll sie ab Dezember stündlich fahren ")
            append("und Pendlern aus Nordsachsen den Umstieg erleichtern. Der Landkreis beteiligt sich an den Kosten.")
            if (p < 3) append("\n\n")
        }
    }

    fun article(
        id: String = "region-live-neue-buslinie",
        topicId: String = "region",
        tier: String = "top",
        body: String = BODY,
        url: String = "https://www.lvz.de/leipzig/buslinie-100.html",
        sources: List<Pair<String, String>> = listOf(
            "LVZ" to "https://www.lvz.de/leipzig/buslinie-100.html",
            "MDR" to "https://www.mdr.de/nachrichten/sachsen/leipzig/buslinie-100.html",
        ),
        publishedAt: String = "2026-10-05T13:05:00+02:00",
    ): JsonObject = JsonObject(
        mapOf(
            "topicId" to JsonPrimitive(topicId),
            "id" to JsonPrimitive(id),
            "tier" to JsonPrimitive(tier),
            "headline" to JsonPrimitive("Neue Buslinie verbindet Bad Düben mit Leipzig"),
            "summary" to JsonPrimitive("Ab Dezember fährt stündlich ein Bus. Pendler aus Nordsachsen sollen schneller in die Stadt kommen."),
            "body" to JsonPrimitive(body),
            "source" to JsonPrimitive("LVZ"),
            "url" to JsonPrimitive(url),
            "sources" to JsonArray(sources.map { (n, u) -> JsonObject(mapOf("name" to JsonPrimitive(n), "url" to JsonPrimitive(u))) }),
            "publishedAt" to JsonPrimitive(publishedAt),
            "importance" to JsonPrimitive(6),
            "readingMinutes" to JsonPrimitive(1),
            "image" to JsonNull,
        ),
    )

    fun payload(vararg articles: JsonElement): String =
        Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("articles" to JsonArray(articles.toList()))))
}

class LivePublishTest {
    private val topics = setOf("region", "politik")

    @Test
    fun `a valid article becomes a schema-2 news item`() {
        val outcome = LivePublish.validate(LiveFixtures.payload(LiveFixtures.article()), topics, emptySet(), LiveFixtures.NOW)

        assertTrue(outcome.problems.toString(), outcome.problems.isEmpty())
        val stored = outcome.accepted.single()
        assertEquals("region", stored.topicId)
        val item = stored.item
        assertEquals("region-live-neue-buslinie", item.id)
        assertEquals(NewsItem.TIER_TOP, item.tier)
        assertEquals(2, item.sources.size)
        assertNull(item.image)
        assertEquals("2026-10-05T13:05:00+02:00", item.publishedAt)
        assertEquals(1, item.readingMinutes)
        assertEquals("ok", LivePublish.replyText(outcome))
    }

    @Test
    fun `problems are listed per article`() {
        val bad = LiveFixtures.article(
            id = "Region Live!",
            topicId = "sport",
            tier = "top",
            body = "Zu kurz. Siehe https://example.org",
            url = "http://www.lvz.de/x",
            sources = listOf("LVZ" to "http://www.lvz.de/x"),
            publishedAt = "2026-10-05 13:05",
        )
        val outcome = LivePublish.validate(LiveFixtures.payload(LiveFixtures.article(), bad), topics, emptySet(), LiveFixtures.NOW)

        assertEquals(1, outcome.accepted.size)
        val problem = outcome.problems.single()
        assertTrue(problem, problem.startsWith("Artikel 2"))
        listOf("topicId", "id muss", "zu kurz", "URLs", "url muss", "https-url", "2 verschiedene Quellen", "publishedAt").forEach {
            assertTrue("missing '$it' in: $problem", problem.contains(it))
        }
        assertTrue(LivePublish.replyText(outcome).startsWith("Nicht veröffentlicht"))
    }

    @Test
    fun `dates must carry an offset and be fresh`() {
        fun problemsFor(publishedAt: String) =
            LivePublish.validate(LiveFixtures.payload(LiveFixtures.article(publishedAt = publishedAt)), topics, emptySet(), LiveFixtures.NOW).problems

        assertTrue(problemsFor("2026-10-05T13:05:00+02:00").isEmpty())
        assertTrue(problemsFor("2026-10-05T13:05:00").single().contains("Offset"))
        assertTrue(problemsFor("2026-10-05T18:00:00+02:00").single().contains("Zukunft"))
        assertTrue(problemsFor("2026-10-02T08:00:00+02:00").single().contains("48 Stunden"))
    }

    @Test
    fun `ids are unique within a call and against stored articles`() {
        val duplicate = LiveFixtures.payload(LiveFixtures.article(), LiveFixtures.article())
        assertTrue(LivePublish.validate(duplicate, topics, emptySet(), LiveFixtures.NOW).problems.single().contains("mehrfach"))

        val taken = setOf("region-live-neue-buslinie", "region-live-neue-buslinie-2")
        val outcome = LivePublish.validate(LiveFixtures.payload(LiveFixtures.article()), topics, taken, LiveFixtures.NOW)
        assertEquals("region-live-neue-buslinie-3", outcome.accepted.single().item.id)
    }

    @Test
    fun `the main url is added to the sources when missing`() {
        val article = LiveFixtures.article(
            tier = "more",
            sources = listOf("MDR" to "https://www.mdr.de/nachrichten/sachsen/leipzig/buslinie-100.html"),
        )
        val item = LivePublish.validate(LiveFixtures.payload(article), topics, emptySet(), LiveFixtures.NOW).accepted.single().item
        assertEquals(listOf("https://www.lvz.de/leipzig/buslinie-100.html", "https://www.mdr.de/nachrichten/sachsen/leipzig/buslinie-100.html"), item.sources.map { it.url })
    }

    @Test
    fun `broken json and an empty list`() {
        assertTrue(LivePublish.validate("{\"articles\": [", topics, emptySet(), LiveFixtures.NOW).problems.single().contains("JSON"))
        val empty = LivePublish.validate("{\"articles\": []}", topics, emptySet(), LiveFixtures.NOW)
        assertTrue(empty.accepted.isEmpty() && empty.problems.isEmpty())
    }

    @Test
    fun `schema is strict everywhere`() {
        val schema = LivePublish.articleSchema(listOf("region"))
        assertEquals(false, schema["additionalProperties"])
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Any>
        assertEquals(properties.keys.toList(), schema["required"])
        @Suppress("UNCHECKED_CAST")
        val sourceItem = (properties["sources"] as Map<String, Any>)["items"] as Map<String, Any>
        assertEquals(false, sourceItem["additionalProperties"])
    }

    @Test
    fun `the Claude schema is the v1_2 schema byte for byte`() {
        val json = Json.encodeToString(JsonElement.serializer(), LivePublish.articlesProperty(Topics.ALL.map { it.id }).toJsonElement())
        assertEquals(1328, json.length)
        val sha = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("20d5589245a29c4f16f9b8450fb7fe5ce3e39948d8ad3c529f37b39bcd236a6c", sha)
        assertTrue(json.contains("\"format\":\"uri\""))
        assertTrue(json.contains("\"image\""))
    }

    @Test
    fun `the portable schema leaves out uri formats and the image field`() {
        val ids = Topics.ALL.map { it.id }
        val portable = LivePublish.articleSchema(ids, LivePublish.SchemaDialect.PORTABLE)
        @Suppress("UNCHECKED_CAST")
        val properties = portable["properties"] as Map<String, Any>
        assertTrue("image" !in properties)
        assertEquals(properties.keys.toList(), portable["required"])
        assertEquals(false, portable["additionalProperties"])
        val json = Json.encodeToString(JsonElement.serializer(), LivePublish.parametersSchema(ids, LivePublish.SchemaDialect.PORTABLE).toJsonElement())
        assertFalse(json.contains("\"uri\""))
        assertFalse(json.contains("\"image\""))
        // OpenAI strict mode supports date-time.
        assertTrue(json.contains("\"date-time\""))

        val parameters = Json.parseToJsonElement(json).jsonObject
        assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("articles"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, parameters["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals("array", parameters["properties"]!!.jsonObject["articles"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // The validator does not care which dialect produced the call.
        val outcome = LivePublish.validate(LiveFixtures.payload(LiveFixtures.article()), setOf("region"), emptySet(), LiveFixtures.NOW)
        assertTrue(outcome.problems.isEmpty())
    }

    @Test
    fun `plain values become JSON trees`() {
        val tree = linkedMapOf("a" to listOf(1, 2.5, true, null), "b" to arrayOf("x"), "c" to JsonPrimitive("raw")).toJsonElement()
        assertEquals("""{"a":[1,2.5,true,null],"b":["x"],"c":"raw"}""", tree.toString())
        try {
            Any().toJsonElement()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
