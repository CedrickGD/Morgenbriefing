package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.util.SystemStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Pure logic behind the status hero of Settings (docs/DESIGN.md §7, Einstellungen 1):
 * which requirements are met, what the hero says and how the news connection is labelled.
 * No Android types, unit-tested.
 */

/** One requirement shown as a pill in the status hero. */
enum class StatusKind { NOTIFICATIONS, EXACT_ALARM, BATTERY, NEWS, AI }

/** Green (fine), amber (needs a tap) or neutral (optional / still checking). */
enum class StatusLevel { OK, ATTENTION, NEUTRAL }

@Immutable
data class StatusItem(
    val kind: StatusKind,
    val level: StatusLevel,
    /** Pill text, e.g. "Benachrichtigungen". */
    val label: String,
    /** Spoken state, e.g. "erlaubt" / "nicht erlaubt". */
    val state: String,
)

/** Connection to the private briefs repo, checked when Settings opens and after token changes. */
@Immutable
sealed interface SourceStatus {
    /** No token stored: the app shows the demo brief. */
    data object NoToken : SourceStatus

    /** A token is stored but not checked yet. */
    data object Unknown : SourceStatus

    data object Checking : SourceStatus

    /** [checkedAt] epoch millis. */
    data class Connected(val checkedAt: Long) : SourceStatus

    data class Failed(val kind: BriefErrorKind, val checkedAt: Long) : SourceStatus
}

/** The hero's big line and the friendly sentence below it. */
@Immutable
data class HeroText(val title: String, val subtitle: String, val ready: Boolean)

/**
 * The pills of the status hero, in display order. The KI pill follows the chat provider of [ai]
 * (docs/AI-PROVIDERS.md §10.5, [aiStatusItem]) and is shown on every API level.
 */
internal fun statusItems(
    system: SystemStatus,
    source: SourceStatus,
    ai: AiSettings,
    aiProviderSupported: Boolean,
): List<StatusItem> = buildList {
    add(
        StatusItem(
            kind = StatusKind.NOTIFICATIONS,
            level = if (system.canNotify) StatusLevel.OK else StatusLevel.ATTENTION,
            label = "Benachrichtigungen",
            state = when {
                system.canNotify -> "erlaubt"
                system.permissionGranted && system.appNotificationsEnabled -> "Kanal ausgeschaltet"
                else -> "nicht erlaubt"
            },
        ),
    )
    // Below Android 12 every alarm may be exact, so the pill is simply green there.
    val exact = !system.exactAlarmRelevant || system.exactAlarmsAllowed
    add(
        StatusItem(
            kind = StatusKind.EXACT_ALARM,
            level = if (exact) StatusLevel.OK else StatusLevel.ATTENTION,
            label = "Exakte Uhrzeit",
            state = if (exact) "erlaubt" else "nicht erlaubt",
        ),
    )
    add(
        StatusItem(
            kind = StatusKind.BATTERY,
            level = if (system.batteryUnrestricted) StatusLevel.OK else StatusLevel.ATTENTION,
            label = "Akku",
            state = if (system.batteryUnrestricted) "nicht eingeschränkt" else "Optimierung an",
        ),
    )
    add(
        StatusItem(
            kind = StatusKind.NEWS,
            level = when (source) {
                is SourceStatus.Connected -> StatusLevel.OK
                SourceStatus.NoToken, is SourceStatus.Failed -> StatusLevel.ATTENTION
                SourceStatus.Checking, SourceStatus.Unknown -> StatusLevel.NEUTRAL
            },
            label = "News-Verbindung",
            state = when (source) {
                is SourceStatus.Connected -> "verbunden"
                SourceStatus.NoToken -> STATE_NO_TOKEN
                is SourceStatus.Failed -> "gestört"
                SourceStatus.Checking, SourceStatus.Unknown -> "wird geprüft"
            },
        ),
    )
    add(aiStatusItem(ai, aiProviderSupported))
}

/**
 * The KI pill for the chat provider of [ai]. [supported] false (Claude below Android 8) is a
 * neutral "Claude braucht Android 8". A missing key (own server: address) is neutral, because KI
 * is optional for the morning briefing, and so is a key saved without a passed test: the pill
 * must not claim it works. A key the provider refused needs a tap – the pill opens the key
 * dialog – but still does not hold up the morning briefing ([heroText]).
 */
internal fun aiStatusItem(ai: AiSettings, supported: Boolean): StatusItem {
    val spec = ProviderCatalog.spec(ai.provider)
    val p = ai.of(spec.id)
    val custom = spec.id == AiProviderId.CUSTOM
    val (level, state) = when {
        !supported -> StatusLevel.NEUTRAL to "${spec.brand} braucht Android 8"
        custom && !ai.isConfigured(spec.id) -> StatusLevel.NEUTRAL to "aus – kein Server eingerichtet"
        custom -> StatusLevel.OK to "eingerichtet · eigener Server"
        !p.hasKey -> StatusLevel.NEUTRAL to "aus – kein Key für ${spec.brand}"
        p.keyRejected -> StatusLevel.ATTENTION to "Key von ${spec.brand} abgelehnt"
        p.keyUntested -> StatusLevel.NEUTRAL to "Key noch nicht getestet"
        else -> StatusLevel.OK to "eingerichtet · ${spec.brand}"
    }
    return StatusItem(kind = StatusKind.AI, level = level, label = "KI", state = state)
}

/**
 * What the hero says. [day] is "morgen"/"heute"/…, [time] "05:30". Only the requirements of
 * the morning briefing count (not the optional KI); a blocked notification is the worst case.
 */
internal fun heroText(enabled: Boolean, items: List<StatusItem>, day: String, time: String): HeroText {
    if (!enabled) {
        return HeroText(
            title = "Dein Morgenbriefing ist aus",
            subtitle = "Schalte es unten wieder ein – dann kommt es jeden Morgen um $time.",
            ready = false,
        )
    }
    val problems = items.filter { it.level == StatusLevel.ATTENTION && it.kind != StatusKind.AI }
    if (problems.isEmpty()) {
        return HeroText(
            title = "Alles bereit für $day, $time",
            subtitle = "Wetter und News kommen pünktlich – du musst nichts weiter tun.",
            ready = true,
        )
    }
    val title = if (problems.any { it.kind == StatusKind.NOTIFICATIONS }) {
        "Noch kommt morgens nichts an"
    } else {
        "Fast bereit für $day, $time"
    }
    val subtitle = if (problems.size == 1) {
        hintFor(problems.single())
    } else {
        "${problems.size} Kleinigkeiten fehlen noch – tippe auf die gelben Punkte."
    }
    return HeroText(title = title, subtitle = subtitle, ready = false)
}

private const val STATE_NO_TOKEN = "kein Token, Demo-Modus"

private fun hintFor(item: StatusItem): String = when (item.kind) {
    StatusKind.NOTIFICATIONS -> "Erlaube Benachrichtigungen – tippe unten auf den gelben Punkt."
    StatusKind.EXACT_ALARM -> "Mit „Exakte Uhrzeit“ kommt dein Briefing auf die Minute."
    StatusKind.BATTERY -> "Nimm die App aus der Akku-Optimierung, damit Android sie morgens nicht bremst."
    StatusKind.NEWS -> if (item.state == STATE_NO_TOKEN) {
        "Trag deinen GitHub-Token ein – bis dahin siehst du das Demo-Briefing."
    } else {
        "Die News-Quelle antwortet gerade nicht – tippe, um es dir anzusehen."
    }
    StatusKind.AI -> ""
}

/**
 * The connection pill of "News-Quelle": "Verbunden ✓ · geprüft 18:52", "Demo-Modus – kein
 * Token", "Token ungültig · geprüft 18:52" … Times are Berlin wall-clock; another day than
 * [now] shows the short date instead.
 */
internal fun sourceStatusLabel(status: SourceStatus, now: ZonedDateTime, zone: ZoneId = now.zone): String =
    when (status) {
        SourceStatus.NoToken -> "Demo-Modus – kein Token"
        SourceStatus.Unknown -> "Token gespeichert"
        SourceStatus.Checking -> "Prüfe die Verbindung …"
        is SourceStatus.Connected -> "Verbunden ✓ · ${checkedLabel(status.checkedAt, now, zone)}"
        is SourceStatus.Failed -> {
            val what = when (status.kind) {
                BriefErrorKind.UNAUTHORIZED, BriefErrorKind.NO_TOKEN -> "Token ungültig"
                BriefErrorKind.NOT_FOUND -> "Kein Zugriff aufs Repo"
                BriefErrorKind.NETWORK -> "Keine Verbindung"
                BriefErrorKind.PARSE, BriefErrorKind.UNKNOWN -> "GitHub antwortet seltsam"
            }
            "$what · ${checkedLabel(status.checkedAt, now, zone)}"
        }
    }

private fun checkedLabel(epochMillis: Long, now: ZonedDateTime, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return if (at.toLocalDate() == now.withZoneSameInstant(zone).toLocalDate()) {
        "geprüft ${clockLabel(at.hour, at.minute)}"
    } else {
        "geprüft ${Fmt.shortDate(at.toLocalDate())}"
    }
}
