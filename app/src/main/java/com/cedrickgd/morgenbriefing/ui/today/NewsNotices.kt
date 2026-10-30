package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.articleCountLabel
import com.cedrickgd.morgenbriefing.ui.news.fetchedLabel

/** What a notice's button does. */
enum class NoticeAction { RETRY, OPEN_TOPICS, OPEN_SOURCES }

/** Copy for a [com.cedrickgd.morgenbriefing.ui.components.NoticeBanner]. */
@Immutable
data class Notice(
    val emoji: String,
    val title: String,
    val text: String,
    val actionLabel: String? = null,
    val action: NoticeAction? = null,
)

/**
 * The hint above the feed, if any: offline, every topic hidden, every source off, or some
 * newsrooms not answering. Null while loading or when all is well. Pure, unit-tested.
 *
 * @param feed the feed on screen; null until settings are known.
 * @param loading a refresh is running.
 * @param warmed the cache was read.
 */
internal fun newsNotice(feed: NewsFeed?, offline: Boolean, loading: Boolean, warmed: Boolean, attempted: Boolean): Notice? {
    if (feed == null || !warmed) return null
    if (feed.sections.isEmpty()) {
        return Notice(
            emoji = "🙈",
            title = "Alle Themen ausgeblendet",
            text = "Schalte unter „Themen“ mindestens eins wieder ein.",
            actionLabel = "Themen wählen",
            action = NoticeAction.OPEN_TOPICS,
        )
    }
    if (offline) {
        return if (feed.isEmpty) {
            Notice(
                emoji = "📡",
                title = "Offline – noch keine News gespeichert",
                text = "Sobald du wieder Netz hast, holt die App deine Meldungen.",
                actionLabel = "Nochmal versuchen",
                action = NoticeAction.RETRY,
            )
        } else {
            Notice(
                emoji = "📡",
                title = "Offline – du siehst die zuletzt geladenen Meldungen",
                text = "Sobald du wieder Netz hast, zieh einfach zum Aktualisieren nach unten.",
                actionLabel = "Nochmal versuchen",
                action = NoticeAction.RETRY,
            )
        }
    }
    if (loading) return null
    val failed = feed.sections.flatMap { it.failedSources }.distinct()
    if (feed.isEmpty && attempted) {
        return Notice(
            emoji = "📭",
            title = "Keine Meldungen",
            text = if (failed.isNotEmpty()) "Die Quellen antworten gerade nicht." else "Sind unter „Quellen“ noch welche eingeschaltet?",
            actionLabel = if (failed.isNotEmpty()) "Nochmal versuchen" else "Quellen prüfen",
            action = if (failed.isNotEmpty()) NoticeAction.RETRY else NoticeAction.OPEN_SOURCES,
        )
    }
    if (failed.size >= FAILED_SOURCES_NOTICE) {
        return Notice(
            emoji = "🔌",
            title = "${failed.size} Quellen antworten gerade nicht",
            text = failed.take(4).joinToString(", ") + if (failed.size > 4) " …" else "",
            actionLabel = "Nochmal versuchen",
            action = NoticeAction.RETRY,
        )
    }
    return null
}

/** How many sources must fail before the feed says so (one quiet newsroom is normal). */
private const val FAILED_SOURCES_NOTICE = 3

/** "Stand 07:12 · 128 Meldungen · Wetter: Open-Meteo" for the end of the page. */
internal fun footerLine(feed: NewsFeed?, hasWeather: Boolean, weatherOffline: Boolean): String = buildList {
    val stand = fetchedLabel(feed?.fetchedAt)
    if (stand.isNotBlank()) add("Stand $stand")
    feed?.articleCount?.takeIf { it > 0 }?.let { add(articleCountLabel(it)) }
    if (hasWeather) add(if (weatherOffline) "Wetter: Open-Meteo (offline)" else "Wetter: Open-Meteo")
}.joinToString(" · ")
