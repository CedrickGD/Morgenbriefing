package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pure logic of Settings → "KI" (docs/AI.md): the key check dialog, the monthly limit slider
 * and the usage line. No Android types, unit-tested.
 */

/** Where the user creates a Claude API key. */
internal const val ANTHROPIC_KEYS_URL = "https://console.anthropic.com/settings/keys"

/** Why "Speichern & testen" did not store the key. */
enum class AiKeyProblem { INVALID, NETWORK, UNSUPPORTED, SAVE_FAILED, UNKNOWN }

/** State of the API key dialog. */
@Immutable
sealed interface AiKeyState {
    data object Idle : AiKeyState
    data object Checking : AiKeyState

    /** Tested OK and stored. */
    data object Saved : AiKeyState
    data class Failed(val problem: AiKeyProblem) : AiKeyState
}

/** Maps the facade's check result; null = OK (store the key). */
internal fun AiKeyCheck.toProblem(): AiKeyProblem? = when (this) {
    AiKeyCheck.OK -> null
    AiKeyCheck.INVALID -> AiKeyProblem.INVALID
    AiKeyCheck.NETWORK -> AiKeyProblem.NETWORK
    AiKeyCheck.UNSUPPORTED -> AiKeyProblem.UNSUPPORTED
}

/** German feedback line under the key field; null while idle. */
internal fun aiKeyMessage(state: AiKeyState): String? = when (state) {
    AiKeyState.Idle -> null
    AiKeyState.Checking -> "Prüfe den Key bei Anthropic …"
    AiKeyState.Saved -> "✓ Der Key funktioniert und ist gespeichert."
    is AiKeyState.Failed -> when (state.problem) {
        AiKeyProblem.INVALID -> "✗ Anthropic lehnt den Key ab – hast du ihn vollständig kopiert?"
        AiKeyProblem.NETWORK -> "✗ Keine Verbindung zu Anthropic – bist du online?"
        AiKeyProblem.UNSUPPORTED -> "✗ KI-Funktionen laufen erst ab Android 8."
        AiKeyProblem.SAVE_FAILED -> "✗ Der Key konnte nicht gespeichert werden. Versuch's nochmal."
        AiKeyProblem.UNKNOWN -> "✗ Das hat gerade nicht geklappt – versuch's gleich nochmal."
    }
}

/** Claude API keys start with "sk-ant-"; anything else is most likely a copy mistake. */
internal fun looksLikeAnthropicKey(raw: String): Boolean {
    val key = raw.trim()
    return key.startsWith("sk-ant-") && key.length >= MIN_KEY_LENGTH && key.none { it.isWhitespace() }
}

private const val MIN_KEY_LENGTH = 20

/** The monthly cap slider: 0–50 $ in whole dollars, 0 = no cap. */
internal object AiLimit {
    const val MAX = 50
    const val SLIDER_STEPS = MAX - 1

    fun normalize(value: Double): Double {
        if (value.isNaN()) return 0.0
        return value.roundToInt().coerceIn(0, MAX).toDouble()
    }

    /** "Kein Limit", "5 $ im Monat" */
    fun label(value: Double): String {
        val v = normalize(value)
        return if (v <= 0.0) "Kein Limit" else "${usd(v)} im Monat"
    }
}

/**
 * Dollar amounts the German way: "5 $", "0,84 $", "12,50 $"; tiny positive amounts become
 * "< 0,01 $" instead of a misleading "0 $".
 */
internal fun usd(value: Double): String {
    if (value.isNaN() || value <= 0.0) return "0 $"
    if (value < 0.005) return "< 0,01 $"
    val cents = (value * 100).roundToInt()
    return if (cents % 100 == 0) {
        "${cents / 100} $"
    } else {
        String.format(Locale.GERMANY, "%.2f $", cents / 100.0)
    }
}

/** "Diesen Monat: 0,84 $ von 5 $ · 23 Anfragen · 7 Websuchen" ([limit] 0 = no "von …"). */
internal fun usageLine(spent: Double, limit: Double, requests: Int, webSearches: Int): String {
    val budget = if (limit > 0.0) "${usd(spent)} von ${usd(limit)}" else usd(spent)
    val req = if (requests == 1) "1 Anfrage" else "${requests.coerceAtLeast(0)} Anfragen"
    val web = if (webSearches == 1) "1 Websuche" else "${webSearches.coerceAtLeast(0)} Websuchen"
    return "Diesen Monat: $budget · $req · $web"
}

/** Share of the cap used, 0..1; null without a cap. */
internal fun usageFraction(spent: Double, limit: Double): Float? {
    if (limit <= 0.0) return null
    if (spent.isNaN() || spent <= 0.0) return 0f
    return (spent / limit).toFloat().coerceIn(0f, 1f)
}

/** "23 Anfragen · 7 Websuchen" — non-breaking around the dot, so a line never starts with "·". */
internal fun usageCounts(requests: Int, webSearches: Int): String {
    val req = if (requests == 1) "1 Anfrage" else "${requests.coerceAtLeast(0)} Anfragen"
    val web = if (webSearches == 1) "1 Websuche" else "${webSearches.coerceAtLeast(0)} Websuchen"
    return "$req\u00A0·\u00A0$web"
}
