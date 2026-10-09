package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * The client tool `publish_articles` of a live run: its JSON schema (strict, schema-2 fields
 * plus `topicId`, images always null; one dialect per provider family) and the validation of what
 * the model sends. The tool keeps its name for every provider. Pure, so it is unit-tested without
 * any SDK.
 */
internal object LivePublish {
    const val TOOL_NAME = "publish_articles"

    const val TOOL_DESCRIPTION =
        "Veröffentlicht die fertig recherchierten und geprüften Live-Artikel in der App des Lesers. " +
            "Rufe dieses Werkzeug genau einmal am Ende der Recherche auf – mit allen Artikeln, oder mit einer " +
            "leeren Liste, wenn es nichts Neues und Belastbares gibt. Meldet es Fehler, korrigiere sie und " +
            "rufe es erneut mit allen Artikeln auf."

    /**
     * How strict JSON schema keywords the provider understands. [FULL] (Claude) is the v1.2 schema
     * byte for byte, so Claude's cached prompt prefix stays the same. [PORTABLE] (Responses
     * family, OpenRouter) drops `format: "uri"` and the always-null `image` field, which not every
     * strict-mode implementation accepts; URLs are still checked by [validate].
     */
    enum class SchemaDialect { FULL, PORTABLE }

    /** JSON schema of one article (`additionalProperties: false` everywhere, all fields required). */
    fun articleSchema(topicIds: List<String>, dialect: SchemaDialect = SchemaDialect.FULL): Map<String, Any> {
        val string = mapOf("type" to "string")
        val url: Map<String, Any> = if (dialect == SchemaDialect.FULL) mapOf("type" to "string", "format" to "uri") else string
        val properties = linkedMapOf<String, Any>(
            "topicId" to mapOf("type" to "string", "enum" to topicIds, "description" to "Rubrik-id"),
            "id" to mapOf("type" to "string", "description" to "Slug: Rubrik-id + \"-live-\" + kurzer Slug, nur a-z, 0-9 und -"),
            "tier" to mapOf("type" to "string", "enum" to listOf(NewsItem.TIER_TOP, NewsItem.TIER_MORE)),
            "headline" to mapOf("type" to "string", "description" to "Höchstens 10 Wörter"),
            "summary" to mapOf("type" to "string", "description" to "Vorspann, 1–2 Sätze, höchstens 300 Zeichen"),
            "body" to mapOf("type" to "string", "description" to "Vollständiger Artikel, 150–400 Wörter, Absätze durch Leerzeile getrennt"),
            "source" to string,
            "url" to url,
            "sources" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to linkedMapOf("name" to string, "url" to url),
                    "required" to listOf("name", "url"),
                    "additionalProperties" to false,
                ),
            ),
            "publishedAt" to mapOf("type" to "string", "format" to "date-time", "description" to "ISO-8601 mit Berliner Offset"),
            "importance" to mapOf("type" to "integer", "description" to "1–10"),
            "readingMinutes" to mapOf("type" to "integer"),
        )
        if (dialect == SchemaDialect.FULL) properties["image"] = mapOf("type" to "null")
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )
    }

    /** The tool input's top-level `articles` property. */
    fun articlesProperty(topicIds: List<String>, dialect: SchemaDialect = SchemaDialect.FULL): Map<String, Any> =
        linkedMapOf("type" to "array", "items" to articleSchema(topicIds, dialect), "description" to "Alle fertigen Artikel, wichtigste zuerst")

    /**
     * The whole parameters object of `publish_articles` for function-calling APIs:
     * `{type: object, properties: {articles}, required: [articles], additionalProperties: false}`.
     */
    fun parametersSchema(topicIds: List<String>, dialect: SchemaDialect): Map<String, Any> = linkedMapOf(
        "type" to "object",
        "properties" to linkedMapOf("articles" to articlesProperty(topicIds, dialect)),
        "required" to listOf("articles"),
        "additionalProperties" to false,
    )

    @Serializable
    internal data class Payload(val articles: List<PayloadArticle> = emptyList())

    @Serializable
    internal data class PayloadArticle(
        val topicId: String = "",
        val id: String = "",
        val tier: String = "",
        val headline: String = "",
        val summary: String = "",
        val body: String = "",
        val source: String = "",
        val url: String = "",
        val sources: List<SourceRef> = emptyList(),
        val publishedAt: String = "",
        val importance: Int = 5,
        val readingMinutes: Int = 0,
    )

    data class Outcome(
        /** Valid articles (topicId, item), ids made unique against what is already stored. */
        val accepted: List<StoredLiveArticle>,
        /** German problem descriptions for the tool result; empty when everything was valid. */
        val problems: List<String>,
    )

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val SLUG = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
    private val HTML = Regex("""<\s*/?\s*[a-zA-Z][^>]*>""")
    private val URL_IN_TEXT = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)

    /**
     * Parses and checks one `publish_articles` input.
     * @param allowedTopics topic ids of this run.
     * @param takenKeys article keys that already exist today (brief + earlier live runs); a
     *   colliding id gets a numeric suffix instead of being rejected.
     */
    fun validate(raw: String, allowedTopics: Set<String>, takenKeys: Set<String>, now: Instant): Outcome {
        val payload = try {
            lenient.decodeFromString(Payload.serializer(), raw)
        } catch (_: SerializationException) {
            return Outcome(emptyList(), listOf("Die Eingabe ist kein gültiges JSON nach dem Schema von publish_articles."))
        } catch (_: IllegalArgumentException) {
            return Outcome(emptyList(), listOf("Die Eingabe ist kein gültiges JSON nach dem Schema von publish_articles."))
        }
        val problems = ArrayList<String>()
        val accepted = ArrayList<StoredLiveArticle>()
        val idsInPayload = HashSet<String>()
        val used = HashSet(takenKeys)

        payload.articles.forEachIndexed { index, article ->
            val label = "Artikel ${index + 1}" + article.id.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val issues = check(article, allowedTopics, now)
            val id = article.id.trim()
            if (id.isNotEmpty() && !idsInPayload.add(id)) issues += "die id kommt mehrfach vor"
            if (issues.isNotEmpty()) {
                problems += "$label: ${issues.joinToString("; ")}."
                return@forEachIndexed
            }
            val uniqueId = uniqueId(id, used)
            used += uniqueId
            accepted += StoredLiveArticle(topicId = article.topicId.trim(), item = article.toNewsItem(uniqueId))
        }
        return Outcome(accepted, problems)
    }

    /** "ok" or the list of problems, as the tool result text for the model. */
    fun replyText(outcome: Outcome): String =
        if (outcome.problems.isEmpty()) {
            "ok"
        } else {
            buildString {
                append("Nicht veröffentlicht – bitte korrigieren und publish_articles erneut mit ALLEN Artikeln aufrufen:\n")
                outcome.problems.forEach { append("- ").append(it).append('\n') }
            }.trimEnd()
        }

    private fun check(a: PayloadArticle, allowedTopics: Set<String>, now: Instant): MutableList<String> {
        val issues = ArrayList<String>()
        if (a.topicId.trim() !in allowedTopics) issues += "topicId \"${a.topicId}\" gehört nicht zu diesem Lauf (${allowedTopics.sorted().joinToString()})"
        val id = a.id.trim()
        if (id.isEmpty() || id.length > 80 || !SLUG.matches(id)) issues += "id muss ein Slug aus a-z, 0-9 und Bindestrichen sein (höchstens 80 Zeichen)"
        if (a.tier != NewsItem.TIER_TOP && a.tier != NewsItem.TIER_MORE) issues += "tier muss \"top\" oder \"more\" sein"
        val headline = a.headline.trim()
        if (headline.isEmpty() || headline.length > 120) issues += "headline fehlt oder ist länger als 120 Zeichen"
        val summary = a.summary.trim()
        if (summary.isEmpty() || summary.length > 300) issues += "summary fehlt oder ist länger als 300 Zeichen"
        val words = wordCount(a.body)
        if (words < MIN_WORDS) issues += "body ist zu kurz ($words Wörter, mindestens $MIN_WORDS)"
        if (words > MAX_WORDS) issues += "body ist zu lang ($words Wörter, höchstens $MAX_WORDS)"
        if (URL_IN_TEXT.containsMatchIn(a.body)) issues += "body darf keine Links oder URLs enthalten"
        if (HTML.containsMatchIn(a.body)) issues += "body darf kein HTML enthalten"
        if (a.source.isBlank()) issues += "source fehlt"
        if (!isHttpsUrl(a.url)) issues += "url muss eine https-Adresse sein"
        val sources = normalizedSources(a)
        if (sources.any { !isHttpsUrl(it.url) || it.name.isBlank() }) issues += "jede Quelle braucht name und eine https-url"
        val distinct = sources.map { it.url.trim() }.distinct().size
        val needed = if (a.tier == NewsItem.TIER_TOP) 2 else 1
        if (distinct < needed) issues += "ein ${a.tier}-Artikel braucht mindestens $needed verschiedene Quellen"
        val published = parseOffsetDateTime(a.publishedAt)
        when {
            published == null -> issues += "publishedAt muss ISO-8601 mit Offset sein, z. B. 2026-10-06T14:10:00+02:00"
            published.toInstant().isAfter(now.plus(FUTURE_TOLERANCE)) -> issues += "publishedAt liegt in der Zukunft"
            published.toInstant().isBefore(now.minus(MAX_AGE)) -> issues += "die Meldung ist älter als 48 Stunden"
        }
        return issues
    }

    private fun PayloadArticle.toNewsItem(uniqueId: String): NewsItem {
        val body = body.replace("\r\n", "\n").trim()
        val published = parseOffsetDateTime(publishedAt)?.atZoneSameInstant(BERLIN)
            ?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return NewsItem(
            id = uniqueId,
            headline = headline.trim(),
            summary = summary.trim(),
            body = body,
            source = source.trim(),
            url = url.trim(),
            sources = normalizedSources(this),
            publishedAt = published,
            importance = importance.coerceIn(1, 10),
            tier = tier,
            image = null,
            readingMinutes = ((wordCount(body) / 200.0).roundToInt()).coerceAtLeast(1),
        )
    }

    /** All sources, with the main url added first when the model forgot it in the list. */
    private fun normalizedSources(a: PayloadArticle): List<SourceRef> {
        val list = a.sources.map { SourceRef(it.name.trim(), it.url.trim()) }
        val main = a.url.trim()
        return if (main.isNotEmpty() && list.none { it.url == main }) listOf(SourceRef(a.source.trim(), main)) + list else list
    }

    private fun uniqueId(id: String, used: Set<String>): String {
        if (id !in used) return id
        var n = 2
        while ("$id-$n" in used) n++
        return "$id-$n"
    }

    internal fun wordCount(text: String): Int =
        text.split(Regex("""\s+""")).count { word -> word.any { it.isLetterOrDigit() } }

    internal fun isHttpsUrl(url: String): Boolean {
        val text = url.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        return try {
            val uri = URI(text)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
    }

    private fun parseOffsetDateTime(text: String): OffsetDateTime? = try {
        OffsetDateTime.parse(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private const val MIN_WORDS = 80
    private const val MAX_WORDS = 1_200
    private val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(15)
    private val MAX_AGE: Duration = Duration.ofHours(48)
}
