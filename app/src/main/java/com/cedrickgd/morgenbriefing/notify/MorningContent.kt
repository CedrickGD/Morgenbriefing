package com.cedrickgd.morgenbriefing.notify

import android.util.Log
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.NotificationText
import com.cedrickgd.morgenbriefing.domain.WeatherInsights
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val TAG = "MorningContent"

/** The brief the notification shows: fresh news, the demo, or the newest cached one. */
internal fun BriefLoad.shownBrief(): Brief? = when (this) {
    is BriefLoad.Loaded -> brief
    is BriefLoad.Demo -> brief
    is BriefLoad.Failed -> cached
}

/** True if this brief belongs to [today] (or a later day, should the device clock lag). */
internal fun Brief.isCurrentFor(today: LocalDate): Boolean {
    val day = try {
        LocalDate.parse(date.trim())
    } catch (e: DateTimeParseException) {
        return false
    }
    return !day.isBefore(today)
}

/**
 * Whether [NewsFollowUpWorker] should keep looking for today's news: only with a token (without
 * one the demo is all there is) and only while no brief for [today] is at hand.
 */
internal fun needsNewsFollowUp(load: BriefLoad, hasToken: Boolean, today: LocalDate): Boolean {
    if (!hasToken) return false
    return when (load) {
        is BriefLoad.Loaded -> !load.brief.isCurrentFor(today)
        // Token was saved while the demo was being loaded: real news is one fetch away.
        is BriefLoad.Demo -> true
        is BriefLoad.Failed -> load.error != BriefErrorKind.NO_TOKEN && load.cached?.isCurrentFor(today) != true
    }
}

/**
 * Notification text from [WeatherInsights]; falls back to a plain greeting if building the
 * text fails, so a formatting problem never costs the user the whole morning notification.
 */
internal fun morningText(snapshot: WeatherSnapshot?, brief: Brief?, today: String, newsPending: Boolean = true): NotificationText =
    try {
        WeatherInsights.notification(snapshot, brief, today, newsPending)
    } catch (e: Exception) {
        Log.e(TAG, "Building the notification text failed, using the fallback", e)
        fallbackText(brief, today)
    }

private fun fallbackText(brief: Brief?, today: String): NotificationText {
    val todays = brief?.takeIf { it.date.trim() == today }
    val teaser = todays?.teaser?.trim().orEmpty()
    val headlines = todays?.sections.orEmpty()
        .flatMap { it.items }
        .sortedByDescending { it.importance }
        .take(3)
        .map { "• ${it.headline.trim()}" }
    val text = teaser.ifEmpty { "Wetter und News warten auf dich." }
    val bigText = if (headlines.isEmpty()) text else text + "\n\n" + headlines.joinToString("\n")
    return NotificationText(title = "Guten Morgen! ☀️", text = text, bigText = bigText)
}
