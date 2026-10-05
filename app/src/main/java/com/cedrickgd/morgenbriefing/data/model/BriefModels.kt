package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable

/**
 * One morning's news brief, written by the cloud routine to the `briefs` branch as
 * `briefs/YYYY-MM-DD.json` (and copied to `latest.json`). Weather is NOT part of it —
 * the app always fetches weather live. Contract: docs/brief-format.md.
 */
@Serializable
data class Brief(
    /** 1 = summaries only, 2 = full articles with images and sources. The app reads both. */
    val schema: Int = 2,
    /** Local date in Europe/Berlin, `YYYY-MM-DD`. */
    val date: String,
    /** ISO-8601 with offset, e.g. `2026-10-06T05:07:12+02:00`. */
    val generatedAt: String,
    /** One sentence that sums up the news day. */
    val teaser: String = "",
    val sections: List<NewsSection> = emptyList(),
)

@Serializable
data class NewsSection(
    /** Stable topic id from [Topics] (unknown ids are shown with a neutral style). */
    val id: String,
    val title: String,
    val emoji: String = "",
    /** Ordered: the `top` items first, then `more` (revealed by "+ Mehr"). */
    val items: List<NewsItem> = emptyList(),
)

@Serializable
data class NewsItem(
    /** Stable slug, unique within the brief (schema 2). Older briefs: empty — use the url. */
    val id: String = "",
    val headline: String,
    /** Lead/teaser: 1–2 sentences, shown on cards and in the notification. */
    val summary: String,
    /**
     * The complete article in the routine's own words (schema 2): paragraphs separated by a
     * blank line, optional `## ` subheadings and `- ` bullet lines. Empty in schema 1.
     */
    val body: String = "",
    /** Primary source (display + fallback link). */
    val source: String,
    val url: String,
    /** All sources the article is based on (schema 2). */
    val sources: List<SourceRef> = emptyList(),
    /** ISO-8601 when known. */
    val publishedAt: String? = null,
    /** 1–10, how relevant for a reader in Germany this morning. */
    val importance: Int = 5,
    /** `top` (always shown) or `more` (behind "+ Mehr"). */
    val tier: String = TIER_TOP,
    /** Freely licensed picture (Wikimedia Commons only) or null → the app draws a cover. */
    val image: ArticleImage? = null,
    val readingMinutes: Int = 0,
) {
    val key: String get() = id.ifBlank { url }
    val isMore: Boolean get() = tier == TIER_MORE

    companion object {
        const val TIER_TOP = "top"
        const val TIER_MORE = "more"
    }
}

@Serializable
data class SourceRef(val name: String, val url: String)

@Serializable
data class ArticleImage(
    /** https://upload.wikimedia.org/… thumbnail (≈1280 px wide). */
    val url: String,
    /** Author as shown on Commons, e.g. "Raimond Spekking". */
    val credit: String = "",
    /** Short license name, e.g. "CC BY-SA 4.0", "CC0", "Public domain". */
    val license: String = "",
    /** File page on commons.wikimedia.org (attribution link). */
    val sourceUrl: String = "",
    /** Short German description for accessibility. */
    val alt: String = "",
)

/** `index.json` on the briefs branch: newest first. */
@Serializable
data class BriefIndex(
    val updatedAt: String = "",
    val days: List<BriefIndexEntry> = emptyList(),
)

@Serializable
data class BriefIndexEntry(
    val date: String,
    val teaser: String = "",
    val itemCount: Int = 0,
)
