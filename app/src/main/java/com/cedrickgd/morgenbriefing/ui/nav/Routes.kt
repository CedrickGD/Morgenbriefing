package com.cedrickgd.morgenbriefing.ui.nav

import kotlinx.serialization.Serializable

/**
 * Start destination: the swipeable pages Verlauf ← Heute → Einstellungen (docs/DESIGN.md §3).
 * Which page is shown lives in the shell's pager state, not in the route.
 */
@Serializable
data object MainRoute

/** One stored morning; [date] is `YYYY-MM-DD` (Europe/Berlin). Pushed over the pager. */
@Serializable
data class DayRoute(val date: String)

/**
 * The article reader: pages through every article of the brief of [date] (`YYYY-MM-DD`),
 * starting at the article whose `NewsItem.key` is [key]. Pushed over the pager.
 */
@Serializable
data class ArticleRoute(val date: String, val key: String)

/** "Frag nach": chat about the article [key] of [date], or about the whole brief when [key] is null. */
@Serializable
data class ChatRoute(val date: String, val key: String? = null)
