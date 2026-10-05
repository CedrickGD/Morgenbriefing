package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.ai.LiveState
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.util.isOlderThan
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** A live run starts by itself on opening "⚡ Live" when the last one is older than this. */
internal val LIVE_AUTO_REFRESH_AFTER: Duration = Duration.ofMinutes(30)

/**
 * Everything the "⚡ Live" view needs about live research (docs/AI.md §2), combined from the
 * live service, its stored articles and the settings.
 *
 * @property blocked why live research cannot run (shown as a friendly hint); null = it can.
 * @property articles today's live articles, newest first.
 * @property autoRefresh the user allowed automatic runs when opening "⚡ Live".
 */
@Immutable
data class LiveUi(
    val blocked: AiBlock? = null,
    val running: Boolean = false,
    val progress: List<String> = emptyList(),
    val lastRunAt: String? = null,
    val newCount: Int = 0,
    val error: AiError? = null,
    val articles: List<LiveArticle> = emptyList(),
    val autoRefresh: Boolean = false,
) {
    val canRefresh: Boolean get() = blocked == null && !running

    companion object {
        /** Below Android 8 the AI features are not available at all. */
        val Unsupported = LiveUi(blocked = AiBlock.UNSUPPORTED_DEVICE)
    }
}

/**
 * Combines the service state with the settings. A missing key is reported even before the
 * service knows about it, so "Jetzt aktualisieren" never fires a request that must fail. Pure.
 */
internal fun liveUi(state: LiveState, articles: List<LiveArticle>, hasKey: Boolean, autoRefresh: Boolean): LiveUi =
    LiveUi(
        blocked = state.blocked ?: if (!hasKey) AiBlock.NO_KEY else null,
        running = state.running,
        progress = state.progress,
        lastRunAt = state.lastRunAt,
        newCount = state.newCount,
        error = state.error,
        articles = articles,
        autoRefresh = autoRefresh,
    )

/** True when opening "⚡ Live" should start a run by itself (allowed, possible and due). */
internal fun shouldAutoRefresh(live: LiveUi, now: Instant): Boolean =
    live.autoRefresh && live.canRefresh && isOlderThan(live.lastRunAt, now, LIVE_AUTO_REFRESH_AFTER)

/** Friendly explanation of a block, in the app's voice. */
internal fun liveBlockText(block: AiBlock): String = when (block) {
    AiBlock.UNSUPPORTED_DEVICE -> "Live-Recherche braucht Android 8 oder neuer. Die frischen Meldungen aus dem Morgen-Briefing siehst du trotzdem hier."
    AiBlock.NO_KEY -> "Trag in den Einstellungen deinen Claude-API-Key ein – dann sucht Claude hier auf Knopfdruck brandaktuelle Meldungen."
    AiBlock.LIMIT_REACHED -> "Monatslimit erreicht – in den Einstellungen anpassen."
}

/** Friendly description of what went wrong in the last run. */
internal fun liveErrorText(error: AiError): String = when (error) {
    AiError.InvalidKey -> "Claude hat deinen API-Key abgelehnt. Schau in den Einstellungen nach, ob er noch stimmt."
    AiError.RateLimited -> "Gerade kamen zu viele Anfragen auf einmal. Versuch's in einer Minute nochmal."
    AiError.Overloaded -> "Claude ist gerade stark ausgelastet. Versuch's gleich nochmal."
    AiError.Network -> "Keine Verbindung – check kurz dein Netz und versuch's nochmal."
    is AiError.Refusal -> "Diese Recherche hat Claude nicht durchgeführt. Versuch's später mit anderen Themen."
    AiError.Truncated -> "Die Recherche wurde gekürzt – vielleicht fehlen ein paar Meldungen."
    is AiError.Unknown -> "Da ist etwas schiefgelaufen. Versuch's gleich nochmal."
}

/** The status line under "Live": running, last update with count, or what the view shows. */
internal fun liveStatusLine(live: LiveUi, now: ZonedDateTime): String {
    if (live.running) return "Claude recherchiert gerade …"
    val last = live.lastRunAt?.let { Fmt.relative(it, now) }
    return when {
        last != null && live.newCount > 0 -> "Aktualisiert $last · ${live.newCount} neu"
        last != null -> "Aktualisiert $last · keine neuen Meldungen"
        else -> "Nur was in den letzten 6 Stunden passiert ist"
    }
}
