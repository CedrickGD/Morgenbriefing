package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable

/**
 * One morning's news brief, written by the cloud routine to the `briefs` branch as
 * `briefs/YYYY-MM-DD.json` (and copied to `latest.json`). Weather is NOT part of it —
 * the app always fetches weather live. Contract: docs/brief-format.md.
 */
@Serializable
data class Brief(
    val schema: Int = 1,
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
    /** Stable id: `ki`, `wirtschaft`, `politik` (others allowed). */
    val id: String,
    val title: String,
    val emoji: String = "",
    val items: List<NewsItem> = emptyList(),
)

@Serializable
data class NewsItem(
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    /** ISO-8601 when known. */
    val publishedAt: String? = null,
    /** 1–10, how relevant for a reader in Germany this morning. */
    val importance: Int = 5,
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
