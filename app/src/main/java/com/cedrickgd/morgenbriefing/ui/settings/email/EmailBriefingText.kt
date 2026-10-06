package com.cedrickgd.morgenbriefing.ui.settings.email

import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.EmailSettingsState
import com.cedrickgd.morgenbriefing.data.RemoveRecipientResult
import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** How a [EmailStatusLine] is drawn. */
internal enum class StatusTone { PROGRESS, SUCCESS, HINT, ERROR }

/** The one-line status under the recipient list. */
internal data class EmailStatusLine(val text: String, val tone: StatusTone)

/** Texts and decisions of the "E-Mail-Briefing" section. Pure, unit-tested. */
internal object EmailBriefingText {
    const val TITLE = "E-Mail-Briefing"
    const val EXPLANATION =
        "Zusätzlich zur App kommt das Briefing jeden Morgen gegen 05:20 als E-Mail – gesendet von deinem Gmail-Konto."
    const val SWITCH_LABEL = "Auch per E-Mail"
    const val RECIPIENTS_LABEL = "Empfänger"
    const val INPUT_LABEL = "Neue Adresse"
    const val INPUT_PLACEHOLDER = "name@beispiel.de"
    const val ADD_DESCRIPTION = "Adresse hinzufügen"
    const val SAVING = "Speichern …"
    const val LOADING = "Lade deine E-Mail-Einstellung …"
    const val RELOAD = "Neu laden"

    const val NO_WRITE_PERMISSION_TITLE = "Schreibrecht fehlt"
    const val NO_WRITE_PERMISSION =
        "Dein GitHub-Token darf nur lesen. Öffne github.com → Settings → Developer settings → " +
            "Fine-grained tokens → deinen Morgenbriefing-Token → Repository permissions → " +
            "Contents: Read and write."
    const val NO_WRITE_PERMISSION_AFTER =
        "Deine letzte Änderung ist nicht gespeichert – mach sie danach einfach nochmal."
    const val OPEN_TOKEN_SETTINGS = "Token-Rechte öffnen"
    const val ACKNOWLEDGE = "Verstanden"
    const val GO_TO_TOKEN = "Zum GitHub-Token"

    /** Where fine-grained tokens are managed (permissions of an existing token). */
    const val TOKEN_SETTINGS_URL = "https://github.com/settings/personal-access-tokens"

    /**
     * The e-mail routine starts at 05:20 Berlin time and reads the config then; a change saved
     * before this time (with a few minutes to spare) still counts today.
     */
    private val SAME_DAY_CUTOFF: LocalTime = LocalTime.of(5, 15)

    /**
     * Until 06:59 a later start of the routine (the summer-time retry at 06:20) still sends
     * today's e-mail if none went out yet, so a change in this window may or may not count today.
     */
    private val RUN_WINDOW_END: LocalTime = LocalTime.of(7, 0)

    /** Inline message under the address field; null when there is nothing to say. */
    fun recipientMessage(check: RecipientCheck): String? = when (check) {
        RecipientCheck.OK, RecipientCheck.EMPTY -> null
        RecipientCheck.INVALID -> "Keine gültige Adresse"
        RecipientCheck.DUPLICATE -> "Schon in der Liste"
        RecipientCheck.LIMIT_REACHED -> limitMessage()
        RecipientCheck.UNAVAILABLE -> "Einen Moment – die Liste lädt noch."
    }

    fun limitMessage(): String = "Höchstens ${EmailConfig.MAX_RECIPIENTS} Adressen"

    /** Inline message after tapping a chip's ✕; null when it worked. */
    fun removeMessage(result: RemoveRecipientResult): String? = when (result) {
        RemoveRecipientResult.REMOVED, RemoveRecipientResult.UNAVAILABLE -> null
        RemoveRecipientResult.LAST_RECIPIENT ->
            "Eine Adresse muss bleiben – füg erst eine neue hinzu oder schalte die E-Mail aus."
    }

    /** Content description of a chip's remove button. */
    fun removeDescription(address: String): String = "$address entfernen"

    /** Second line of the switch row. */
    fun switchSubtitle(config: EmailConfig?): String = when {
        config == null -> "Das Briefing zusätzlich in dein Postfach"
        !config.enabled -> "Aus – das Briefing gibt's nur in der App"
        config.recipients.isEmpty() -> "An – es fehlt noch eine Adresse"
        else -> "An – geht an ${addressCount(config.recipients.size)}"
    }

    fun addressCount(count: Int): String = if (count == 1) "1 Adresse" else "$count Adressen"

    /** German explanation of [error] for the status line. */
    fun errorMessage(error: EmailConfigError): String = when (error) {
        EmailConfigError.NO_TOKEN ->
            "Ohne GitHub-Token kommt die App nicht an deine E-Mail-Einstellung. " +
                "Trag ihn unter „News-Quelle“ ein."
        EmailConfigError.UNAUTHORIZED -> "GitHub lehnt deinen Token ab – prüf ihn unter „News-Quelle“."
        EmailConfigError.NO_WRITE_PERMISSION -> NO_WRITE_PERMISSION
        EmailConfigError.RATE_LIMITED ->
            "GitHub bremst gerade (zu viele Anfragen). Versuch's in ein paar Minuten nochmal."
        EmailConfigError.CONFLICT ->
            "Die Einstellung wurde gleichzeitig woanders geändert. Lade neu und versuch's nochmal."
        EmailConfigError.INVALID -> "Die Datei ${EmailConfig.PATH} auf GitHub ist beschädigt."
        EmailConfigError.NETWORK -> "Keine Verbindung zu GitHub – versuch's gleich nochmal."
        EmailConfigError.UNKNOWN -> "Das hat gerade nicht geklappt – versuch's gleich nochmal."
    }

    /** Errors with their own block (token hint, permission card) instead of a status line. */
    fun hasOwnBlock(error: EmailConfigError?): Boolean =
        error == EmailConfigError.NO_TOKEN || error == EmailConfigError.NO_WRITE_PERMISSION

    /** "Neu laden" makes sense for these errors (not for the ones with their own block). */
    fun canReload(error: EmailConfigError?): Boolean = error != null && !hasOwnBlock(error)

    /**
     * "gilt ab heute früh" when saved before the morning run, "gilt ab dem nächsten Versand"
     * while today's run may still be going (or retried), otherwise "gilt ab morgen früh".
     */
    fun appliesFrom(savedAt: Instant, zone: ZoneId = BERLIN): String {
        val local = savedAt.atZone(zone).toLocalTime()
        return when {
            local.isBefore(SAME_DAY_CUTOFF) -> "gilt ab heute früh"
            local.isBefore(RUN_WINDOW_END) -> "gilt ab dem nächsten Versand"
            else -> "gilt ab morgen früh"
        }
    }

    /** What the status line says right now; null = nothing (the controls speak for themselves). */
    fun status(state: EmailSettingsState): EmailStatusLine? {
        val config = state.config
        val error = state.error
        return when {
            state.saving -> EmailStatusLine(SAVING, StatusTone.PROGRESS)
            error != null -> if (hasOwnBlock(error)) null else EmailStatusLine(errorMessage(error), StatusTone.ERROR)
            config == null -> if (state.loading) EmailStatusLine(LOADING, StatusTone.PROGRESS) else null
            config.recipients.isEmpty() -> EmailStatusLine(
                if (config.enabled) {
                    "Füg eine Adresse hinzu – dann wird gespeichert."
                } else {
                    "Noch keine Adresse hinterlegt."
                },
                StatusTone.HINT,
            )
            state.lastSavedAt != null ->
                EmailStatusLine("Gespeichert ✓ · ${appliesFrom(state.lastSavedAt)}", StatusTone.SUCCESS)
            else -> null
        }
    }
}
