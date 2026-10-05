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
