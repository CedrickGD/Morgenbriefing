package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.util.SystemStatus

/*
 * Pure logic behind the status hero of Settings (docs/DESIGN.md §7, Einstellungen 1):
 * which requirements are met and what the hero says. No Android types, unit-tested.
 */

/** One requirement shown as a pill in the status hero. */
enum class StatusKind { NOTIFICATIONS, EXACT_ALARM, BATTERY }

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

/** The hero's big line and the friendly sentence below it. */
@Immutable
data class HeroText(val title: String, val subtitle: String, val ready: Boolean)

/** The pills of the status hero, in display order. */
internal fun statusItems(system: SystemStatus): List<StatusItem> = buildList {
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
}

/**
 * What the hero says. [day] is "morgen"/"heute"/…, [time] "05:30". A blocked notification is
 * the worst case; the sentence counts every yellow pill it asks to tap.
 */
internal fun heroText(enabled: Boolean, items: List<StatusItem>, day: String, time: String): HeroText {
    if (!enabled) {
        return HeroText(
            title = "Dein Morgenbriefing ist aus",
            subtitle = "Schalte es unten wieder ein – dann kommt es jeden Morgen um $time.",
            ready = false,
        )
    }
    val problems = items.filter { it.level == StatusLevel.ATTENTION }
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

private fun hintFor(item: StatusItem): String = when (item.kind) {
    StatusKind.NOTIFICATIONS -> "Erlaube Benachrichtigungen – tippe unten auf den gelben Punkt."
    StatusKind.EXACT_ALARM -> "Mit „Exakte Uhrzeit“ kommt dein Briefing auf die Minute."
    StatusKind.BATTERY -> "Nimm die App aus der Akku-Optimierung, damit Android sie morgens nicht bremst."
}
