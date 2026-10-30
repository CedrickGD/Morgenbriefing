package com.cedrickgd.morgenbriefing.data.article

import com.cedrickgd.morgenbriefing.data.model.ArticleBlock
import com.cedrickgd.morgenbriefing.data.model.ArticleContent
import com.cedrickgd.morgenbriefing.data.news.HtmlText
import net.dankito.readability4j.Readability4J
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns a newsroom's web page into the reader's blocks: Mozilla's Readability algorithm (via
 * Readability4J) finds the article, this walks the result into paragraphs, headings, quotes,
 * lists and pictures. Pure Kotlin (jsoup), unit-tested; no JavaScript, no ads, no cookie banners.
 */
object ArticleExtractor {

    /**
     * The readable article of [html] (loaded from [url]), or null when the page holds no article
     * worth showing (a landing page, a video page, an empty paywall shell).
     *
     * @param now stamp for [ArticleContent.fetchedAt].
     */
    fun extract(html: String, url: String, now: ZonedDateTime = ZonedDateTime.now()): ArticleContent? {
        val document = Jsoup.parse(html, url)
        val meta = Meta.of(document)
        val article = try {
            Readability4J(url, document.outerHtml()).parse()
        } catch (e: Exception) {
            null
        }
        val root = article?.articleContent
        // og:title is the headline itself; the <title> tag usually carries " – Zeitung" as well.
        val title = meta.title.ifEmpty { article?.title.orEmpty().trim() }
        val blocks = ArrayList<ArticleBlock>()
        if (root != null) Walker(url, title, blocks).walk(root)
        // Readability falls back to the whole body on pages without an article: a menu, a
        // one-liner, a paywall shell. Too few words is no article.
        val words = blocks.sumOf { block ->
            when (block.type) {
                ArticleBlock.PARAGRAPH, ArticleBlock.HEADING, ArticleBlock.QUOTE -> block.text.split(' ').count { it.isNotBlank() }
                ArticleBlock.LIST -> block.items.sumOf { item -> item.split(' ').count { it.isNotBlank() } }
                else -> 0
            }
        }
        if (words < MIN_WORDS) return null
        return ArticleContent(
            url = url,
            title = title,
            blocks = blocks.take(MAX_BLOCKS),
            image = meta.image?.takeIf { it.isUsableImage() },
            site = meta.site,
            fetchedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    /**
     * True when [content] is a consent page, not the article: some newsrooms (golem.de) serve
     * "Cookies zustimmen" to every client without their consent cookie. Short, and about cookies.
     */
    fun looksLikeConsentWall(content: ArticleContent): Boolean {
        if (content.wordCount > WALL_MAX_WORDS) return false
        val head = content.text.take(2500)
        return CONSENT_WALL.containsMatchIn(head)
    }

    private const val WALL_MAX_WORDS = 450
    private val CONSENT_WALL = Regex(
        "(?i)(cookies? zustimmen|nutzung aller cookies|zustimmen und weiter|mit werbung und tracking|" +
            "pur-abo|werbefrei lesen|zustimmen oder abo|alle cookies akzeptieren|accept all cookies|" +
            "privacy manager|consent management|einwilligung in die (datenverarbeitung|nutzung))",
    )

    private const val MAX_BLOCKS = 400
    private const val MIN_WORDS = 20
    private const val MIN_PARAGRAPH_CHARS = 2
    private const val SHORT_PARAGRAPH = 90
    private const val MIN_IMAGE_WIDTH = 200

    /** Short lines newsrooms sprinkle between paragraphs: teasers, share bars, notices. */
    private val JUNK = Regex(
        "(?i)^(anzeige|werbung|advertisement|lesen sie auch|lese-tipp|lesetipp|mehr zum thema|auch interessant|" +
            "zur startseite|artikel teilen|teilen|drucken|jetzt abonnieren|newsletter|alle rechte vorbehalten|" +
            "folgen sie uns|zum thema|weitere artikel|mehr dazu|das könnte sie auch interessieren|" +
            "empfehlungen|bildergalerie|video|quelle)[:\\s.…!-]*$",
    )

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val SKIPPED = setOf("script", "style", "iframe", "form", "nav", "aside", "footer", "header", "button", "svg", "noscript", "template", "video", "audio")
    private val BLOCKS = setOf("p", "div", "section", "article", "main", "ul", "ol", "blockquote", "figure", "table", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "picture", "img", "hr", "br", "li", "dl")

    private class Meta(val title: String, val image: String?, val site: String) {
        companion object {
            fun of(document: Document): Meta {
                fun meta(selector: String): String = document.selectFirst(selector)?.attr("content")?.trim().orEmpty()
                val title = meta("meta[property=og:title]").ifEmpty { document.title().trim() }
                val image = meta("meta[property=og:image]").ifEmpty { meta("meta[name=twitter:image]") }
                    .let { resolve(document.location(), it) }
                val site = meta("meta[property=og:site_name]")
                return Meta(title, image, site)
            }
        }
    }

    private class Walker(private val baseUrl: String, private val title: String, private val out: MutableList<ArticleBlock>) {
        private val seenImages = HashSet<String>()
        private val seenText = HashSet<String>()

        fun walk(element: Element) {
            for (child in element.children()) {
                if (out.size >= MAX_BLOCKS) return
                val name = child.normalName()
                when {
                    name in SKIPPED -> Unit
                    name == "p" -> paragraph(child)
                    name in HEADINGS -> heading(child.text())
                    name == "blockquote" -> quote(child.text())
                    name == "ul" || name == "ol" -> list(child)
                    name == "figure" -> figure(child)
                    name == "picture" -> child.selectFirst("img")?.let { image(it) }
                    name == "img" -> image(child)
                    name == "pre" -> addText(child.wholeText().trim(), ArticleBlock::paragraph)
                    name == "table" -> Unit
                    name == "br" || name == "hr" -> Unit
                    child.children().any { it.normalName() in BLOCKS } -> walk(child)
                    else -> paragraph(child)
                }
            }
        }

        private fun paragraph(element: Element) {
            addText(element.text(), ArticleBlock::paragraph)
            // Pictures sitting inside a paragraph (common in WordPress newsrooms).
            element.select("img").forEach { image(it) }
        }

        private fun heading(text: String) = addText(text, ArticleBlock::heading)

        private fun quote(text: String) = addText(text, ArticleBlock::quote)

        private fun list(element: Element) {
            val items = element.children()
                .filter { it.normalName() == "li" }
                .map { clean(it.text()) }
                .filter { it.length >= MIN_PARAGRAPH_CHARS }
            if (items.isNotEmpty()) out += ArticleBlock.list(items)
        }

        private fun figure(element: Element) {
            element.selectFirst("img")?.let { image(it, caption = element.selectFirst("figcaption")?.text()) }
        }

        private fun image(img: Element, caption: String? = null) {
            val width = img.attr("width").trim().toIntOrNull()
            if (width != null && width < MIN_IMAGE_WIDTH) return
            val url = imageUrl(img) ?: return
            if (!seenImages.add(url)) return
            val alt = clean(img.attr("alt"))
            out += ArticleBlock.image(url, alt)
            val text = clean(caption.orEmpty()).ifEmpty { "" }
            if (text.isNotEmpty() && text != alt) out += ArticleBlock.caption(text)
        }

        private fun imageUrl(img: Element): String? {
            val candidates = ArrayList<String>()
            img.attr("srcset").takeIf { it.isNotBlank() }?.let { srcset ->
                // "a.jpg 400w, b.jpg 1200w": the last candidate is the largest in practice.
                srcset.split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() }.lastOrNull()?.let(candidates::add)
            }
            listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "src").forEach { attr ->
                img.attr(attr).trim().takeIf { it.isNotEmpty() }?.let { candidates.add(it.substringBefore(' ').substringBefore(',')) }
            }
            return candidates.asSequence()
                .mapNotNull { resolve(baseUrl, it) }
                .firstOrNull { it.isUsableImage() }
        }

        private fun addText(raw: String, block: (String) -> ArticleBlock) {
            val text = clean(raw)
            if (text.length < MIN_PARAGRAPH_CHARS) return
            if (text.equals(title, ignoreCase = true)) return
            if (text.length <= SHORT_PARAGRAPH && JUNK.matches(text)) return
            if (!seenText.add(text)) return
            out += block(text)
        }
    }

    private fun clean(text: String): String = HtmlText.decodeEntities(text).replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** Absolute https URL for [raw] against [base]; http is upgraded, anything else is dropped. */
    private fun resolve(base: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return null
        val resolved = base.toHttpUrlOrNull()?.resolve(trimmed) ?: trimmed.toHttpUrlOrNull() ?: return null
        return resolved.newBuilder().scheme("https").build().toString()
    }

    private fun String.isUsableImage(): Boolean = with(HtmlText) { isUsableImageUrl() }
}
