package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.ui.brief.isBriefFor
import java.time.LocalDate

/** What a notice card's button does. */
enum class NoticeAction { OPEN_SETTINGS, RETRY }

/** Copy for an [com.cedrickgd.morgenbriefing.ui.components.InfoCard]. */
@Immutable
data class Notice(
    val emoji: String,
    val title: String,
    val text: String,
    val actionLabel: String? = null,
    val action: NoticeAction? = null,
)

/** The news part of the Today screen, derived from the latest [BriefLoad]. */
@Immutable
sealed interface NewsBlock {
    /** Nothing known yet (first start without any cache). */
    data object Loading : NewsBlock

    /** No token: bundled sample brief plus a nudge to set one up. */
    data class Demo(val brief: Brief, val notice: Notice) : NewsBlock

    /** A real brief; [stale] = it is not today's (today's is not published yet). */
    data class Ready(val brief: Brief, val stale: Boolean) : NewsBlock

    /** Loading failed; [brief] is the newest cached one, if any. */
    data class Problem(val notice: Notice, val brief: Brief?, val stale: Boolean) : NewsBlock
}

internal val DemoNotice = Notice(
    emoji = "🔑",
    title = "Demo-Briefing",
    text = "Trag in den Einstellungen deinen GitHub-Token ein – dann landen hier jeden Morgen frische News.",
    actionLabel = "Token eintragen",
    action = NoticeAction.OPEN_SETTINGS,
)

/** Maps a brief load result to what the Today screen shows. Pure, unit-tested. */
internal fun newsBlockFor(load: BriefLoad?, today: LocalDate): NewsBlock = when (load) {
    null -> NewsBlock.Loading
    is BriefLoad.Demo -> NewsBlock.Demo(load.brief, DemoNotice)
    is BriefLoad.Loaded -> NewsBlock.Ready(load.brief, stale = !isBriefFor(load.brief, today))
    is BriefLoad.Failed -> {
        val cached = load.cached
        if (load.error == BriefErrorKind.NO_TOKEN && cached == null) {
            NewsBlock.Problem(DemoNotice, null, stale = false)
        } else {
            NewsBlock.Problem(
                notice = failureNotice(load.error, hasCache = cached != null),
                brief = cached,
                stale = cached != null && !isBriefFor(cached, today),
            )
        }
    }
}

/** Friendly German copy per failure kind. */
internal fun failureNotice(kind: BriefErrorKind, hasCache: Boolean): Notice = when (kind) {
    BriefErrorKind.NO_TOKEN -> DemoNotice
    BriefErrorKind.UNAUTHORIZED -> Notice(
        emoji = "🔒",
        title = "Token ungültig oder abgelaufen",
        text = "GitHub lässt die App gerade nicht rein. Trag in den Einstellungen einen neuen Token ein.",
        actionLabel = "Token ändern",
        action = NoticeAction.OPEN_SETTINGS,
    )
    BriefErrorKind.NOT_FOUND -> Notice(
        emoji = "🕔",
        title = "Heute noch kein Briefing veröffentlicht",
        text = if (hasCache) {
            "Die Morgen-Routine ist noch nicht durch – unten siehst du solange das letzte Briefing."
        } else {
            "Die Morgen-Routine ist noch nicht durch. Schau gleich nochmal rein."
        },
        actionLabel = "Nochmal versuchen",
        action = NoticeAction.RETRY,
    )
    BriefErrorKind.NETWORK -> if (hasCache) {
        Notice(
            emoji = "📡",
            title = "Offline – du siehst die zuletzt gespeicherten News",
            text = "Sobald du wieder Netz hast, zieh einfach zum Aktualisieren nach unten.",
            actionLabel = "Nochmal versuchen",
            action = NoticeAction.RETRY,
        )
    } else {
        Notice(
            emoji = "📡",
            title = "Offline – noch keine News gespeichert",
            text = "Sobald du wieder Netz hast, holt die App dein Briefing.",
            actionLabel = "Nochmal versuchen",
            action = NoticeAction.RETRY,
        )
    }
    BriefErrorKind.PARSE, BriefErrorKind.UNKNOWN -> Notice(
        emoji = "🧩",
        title = "News gerade nicht lesbar",
        text = if (hasCache) {
            "Beim Laden ist etwas schiefgelaufen. Unten siehst du das letzte gespeicherte Briefing."
        } else {
            "Beim Laden ist etwas schiefgelaufen. Versuch's gleich nochmal."
        },
        actionLabel = "Nochmal versuchen",
        action = NoticeAction.RETRY,
    )
}

/**
 * Keeps showing what is already on screen when a refresh fails without a cached brief
 * (e.g. the store could not be read): the user should not lose news they were reading.
 * A demo brief is never passed off as cached real news.
 */
internal fun mergeBriefLoad(previous: BriefLoad?, next: BriefLoad): BriefLoad {
    if (next !is BriefLoad.Failed || next.cached != null) return next
    val shown = when (previous) {
        is BriefLoad.Loaded -> previous.brief
        is BriefLoad.Failed -> previous.cached
        is BriefLoad.Demo, null -> null
    }
    return if (shown != null) next.copy(cached = shown) else next
}
