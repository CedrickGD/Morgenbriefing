package com.cedrickgd.morgenbriefing.ui.today

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.LiveArticle
import com.cedrickgd.morgenbriefing.ai.LiveState
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
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
 * @property provider who researches (the Live provider).
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
    val provider: AiProviderId = AiProviderId.ANTHROPIC,
) {
    val spec: ProviderSpec get() = ProviderCatalog.spec(provider)

    val canRefresh: Boolean get() = blocked == null && !running
}

/**
 * Combines the service state with the settings. [blocked] (computed from the settings, see
 * [com.cedrickgd.morgenbriefing.ai.aiBlock]) is reported even before the service knows about it,
 * so "Jetzt aktualisieren" never fires a request that must fail; the service's own block (e.g.
 * the monthly limit) wins. Pure.
 */
internal fun liveUi(
    state: LiveState,
    articles: List<LiveArticle>,
    blocked: AiBlock?,
    autoRefresh: Boolean,
    provider: AiProviderId = AiProviderId.ANTHROPIC,
): LiveUi =
    LiveUi(
        blocked = state.blocked ?: blocked,
        running = state.running,
        progress = state.progress,
        lastRunAt = state.lastRunAt,
        newCount = state.newCount,
        error = state.error,
        articles = articles,
        autoRefresh = autoRefresh,
        provider = provider,
    )

/** True when opening "⚡ Live" should start a run by itself (allowed, possible and due). */
internal fun shouldAutoRefresh(live: LiveUi, now: Instant): Boolean =
    live.autoRefresh && live.canRefresh && isOlderThan(live.lastRunAt, now, LIVE_AUTO_REFRESH_AFTER)

/** Friendly explanation of a block, in the app's voice; [spec] is the Live provider. */
internal fun liveBlockText(block: AiBlock, spec: ProviderSpec): String = when (block) {
    AiBlock.UNSUPPORTED_DEVICE ->
        "Live-Recherche mit ${spec.brand} braucht Android 8 oder neuer – wähl in den Einstellungen einen anderen Anbieter. " +
            "Die frischen Meldungen aus dem Morgen-Briefing siehst du trotzdem hier."
    AiBlock.NO_KEY -> "Trag einmal deinen ${spec.brand}-API-Key ein – dann sucht ${spec.brand} hier auf Knopfdruck brandaktuelle Meldungen."
    AiBlock.LIMIT_REACHED -> "Monatslimit erreicht – in den Einstellungen anpassen."
    // One sentence why, the panel offers "Live-Anbieter wählen" next to it.
    AiBlock.LIVE_UNAVAILABLE -> AiTexts.blockBody(block, spec)
}

/** Friendly description of what went wrong in the last run; [spec] is the Live provider. */
internal fun liveErrorText(error: AiError, spec: ProviderSpec): String {
    val brand = spec.brand
    return when (error) {
        AiError.InvalidKey -> spec.invalidKeyText ?: "$brand hat deinen API-Key abgelehnt. Schau in den Einstellungen nach, ob er noch stimmt."
        AiError.RateLimited -> "Gerade kamen zu viele Anfragen auf einmal. Versuch's in einer Minute nochmal."
        AiError.Overloaded -> "$brand ist gerade stark ausgelastet. Versuch's gleich nochmal."
        AiError.Network -> "Keine Verbindung – check kurz dein Netz und versuch's nochmal."
        is AiError.Refusal -> "Diese Recherche hat $brand nicht durchgeführt. Versuch's später mit anderen Themen."
        AiError.Truncated -> "Die Recherche wurde gekürzt – vielleicht fehlen ein paar Meldungen."
        // The chat's sentences fit Live as well.
        AiError.NoCredit, AiError.QuotaExhausted, AiError.ServerUnreachable, AiError.WorkspaceRequired,
        AiError.WorkspaceNotFound, is AiError.ModelUnavailable -> AiTexts.error(error, spec)
        // Unknown errors carry a user-facing German sentence when the cause is known.
        is AiError.Unknown -> error.message?.takeIf { it.isNotBlank() } ?: "Da ist etwas schiefgelaufen. Versuch's gleich nochmal."
    }
}

/**
 * Before the first run of the day: what "Jetzt aktualisieren" will do ([freshCount] > 0: the
 * morning's fresh articles are already on screen). [spec] is the Live provider.
 */
internal fun liveIntroText(spec: ProviderSpec, freshCount: Int): String = if (freshCount > 0) {
    "${spec.brand} sucht, was seit dem Morgen-Briefing passiert ist, prüft es an mehreren Quellen und schreibt es dir auf."
} else {
    "Seit dem Morgen ist hier noch nichts Neues. ${spec.brand} kann für dich nachsehen, was gerade passiert."
}

/** The tip under an empty "⚡ Live" view. */
internal fun liveEmptyTip(spec: ProviderSpec): String = "Tipp: „Jetzt aktualisieren“ lässt ${spec.brand} nach brandaktuellen Meldungen suchen."

/** The status line under "Live": running, last update with count, or what the view shows. */
internal fun liveStatusLine(live: LiveUi, now: ZonedDateTime): String {
    if (live.running) return "${live.spec.brand} recherchiert gerade …"
    val last = live.lastRunAt?.let { Fmt.relative(it, now) }
    return when {
        last != null && live.newCount > 0 -> "Aktualisiert $last · ${live.newCount} neu"
        last != null -> "Aktualisiert $last · keine neuen Meldungen"
        else -> "Nur was in den letzten 6 Stunden passiert ist"
    }
}
