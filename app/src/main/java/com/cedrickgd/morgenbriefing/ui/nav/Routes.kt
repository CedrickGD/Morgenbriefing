package com.cedrickgd.morgenbriefing.ui.nav

import kotlinx.serialization.Serializable

/**
 * Start destination: the swipeable pages Heute → Einstellungen (docs/DESIGN.md §3).
 * Which page is shown lives in the shell's pager state, not in the route.
 */
@Serializable
data object MainRoute

/**
 * The article reader, pushed over the pager: the article at [url]. [itemId] and [topicId] find
 * the feed item (headline, teaser, picture) when the feed still has it; the mini player passes
 * only the URL.
 */
@Serializable
data class ArticleRoute(val url: String, val itemId: String? = null, val topicId: String? = null)
