package com.cedrickgd.morgenbriefing.ui.nav

import kotlinx.serialization.Serializable

/** Tab "Heute": live weather + today's brief. Start destination. */
@Serializable
data object TodayRoute

/** Tab "Verlauf": all previous mornings. */
@Serializable
data object HistoryRoute

/** Tab "Einstellungen". */
@Serializable
data object SettingsRoute

/** One stored morning; [date] is `YYYY-MM-DD` (Europe/Berlin). */
@Serializable
data class DayRoute(val date: String)

/**
 * The article reader: pages through every article of the brief of [date] (`YYYY-MM-DD`),
 * starting at the article whose `NewsItem.key` is [key].
 */
@Serializable
data class ArticleRoute(val date: String, val key: String)

/** "Frag nach": chat about the article [key] of [date], or about the whole brief when [key] is null. */
@Serializable
data class ChatRoute(val date: String, val key: String? = null)
