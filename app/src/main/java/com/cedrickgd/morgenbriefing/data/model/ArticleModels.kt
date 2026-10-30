package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable

/**
 * One block of an article as the reader shows it. [type] is one of [ArticleBlock.PARAGRAPH],
 * [ArticleBlock.HEADING], [ArticleBlock.QUOTE], [ArticleBlock.LIST], [ArticleBlock.IMAGE] or
 * [ArticleBlock.CAPTION]; a flat class keeps the JSON cache simple.
 */
@Serializable
data class ArticleBlock(
    val type: String,
    val text: String = "",
    /** [IMAGE]: the https URL of the picture. */
    val url: String = "",
    /** [IMAGE]: the picture's alt text / caption. */
    val alt: String = "",
    /** [LIST]: the bullet items. */
    val items: List<String> = emptyList(),
) {
    companion object {
        const val PARAGRAPH = "p"
        const val HEADING = "h"
        const val QUOTE = "quote"
        const val LIST = "list"
        const val IMAGE = "img"
        const val CAPTION = "caption"

        fun paragraph(text: String) = ArticleBlock(PARAGRAPH, text = text)
        fun heading(text: String) = ArticleBlock(HEADING, text = text)
        fun quote(text: String) = ArticleBlock(QUOTE, text = text)
        fun list(items: List<String>) = ArticleBlock(LIST, items = items)
        fun image(url: String, alt: String = "") = ArticleBlock(IMAGE, url = url, alt = alt)
        fun caption(text: String) = ArticleBlock(CAPTION, text = text)
    }
}

/**
 * The readable part of a web article: what the reader extracted from the page (text, pictures),
 * without navigation, ads, cookie banners or comments. Cached per article.
 */
@Serializable
data class ArticleContent(
    val url: String,
    /** The page's own title (`<h1>` / `og:title`); the feed's headline is shown instead when known. */
    val title: String,
    /** Blocks in reading order. */
    val blocks: List<ArticleBlock>,
    /** A lead picture the page announces (`og:image`), when the feed had none. */
    val image: String? = null,
    /** The site's name from `og:site_name`, when announced. */
    val site: String = "",
    /** ISO-8601 with offset: when the page was read. */
    val fetchedAt: String,
) {
    /** The plain text of the article (paragraphs separated by blank lines), for reading aloud. */
    val text: String
        get() = blocks.mapNotNull { block ->
            when (block.type) {
                ArticleBlock.PARAGRAPH, ArticleBlock.HEADING, ArticleBlock.QUOTE -> block.text
                ArticleBlock.LIST -> block.items.joinToString("\n")
                else -> null
            }
        }.joinToString("\n\n")

    val wordCount: Int
        get() = blocks.sumOf { block ->
            when (block.type) {
                ArticleBlock.PARAGRAPH, ArticleBlock.HEADING, ArticleBlock.QUOTE -> block.text.split(' ').count { it.isNotBlank() }
                ArticleBlock.LIST -> block.items.sumOf { item -> item.split(' ').count { it.isNotBlank() } }
                else -> 0
            }
        }

    /** Probably only a teaser: a paywall or a page the extractor could not read fully. */
    val looksTruncated: Boolean get() = wordCount < SHORT_WORDS

    companion object {
        /** Below this many words an article is most likely cut off (paywall, teaser page). */
        const val SHORT_WORDS = 120
    }
}
