package com.cedrickgd.morgenbriefing.update

import com.cedrickgd.morgenbriefing.domain.Fmt
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** German texts of the updater (settings row and notification). Pure, unit-tested. */
object UpdateText {

    /** Friendly German explanation for [error]; [debugBuild] tailors the wrong-package case. */
    fun errorMessage(error: UpdateError, debugBuild: Boolean = false): String = when (error) {
        UpdateError.UNAUTHORIZED ->
            "GitHub lässt die Anfrage gerade nicht durch. Versuch's in ein paar Minuten nochmal."
        UpdateError.NOT_FOUND -> "Noch kein Release auf GitHub gefunden. Sobald eins veröffentlicht ist, taucht es hier auf."
        UpdateError.RATE_LIMITED -> "GitHub bremst gerade. Versuch's in ein paar Minuten nochmal."
        UpdateError.NETWORK -> "Keine Verbindung zu GitHub – bist du online?"
        UpdateError.SERVER -> "GitHub hat unerwartet geantwortet. Versuch's gleich nochmal."
        UpdateError.INVALID_RELEASE -> "Das neueste Release hat keine gültige Versionsnummer."
        UpdateError.NO_APK -> "Das neueste Release enthält keine APK zum Installieren."
        UpdateError.INCOMPLETE_DOWNLOAD -> "Der Download kam unvollständig an. Versuch's nochmal."
        UpdateError.STORAGE -> "Nicht genug freier Speicher für das Update."
        UpdateError.APK_UNREADABLE -> "Die heruntergeladene Datei ist keine gültige App."
        UpdateError.WRONG_PACKAGE -> if (debugBuild) {
            "Das ist die Debug-Version – sie kann keine Release-Updates installieren."
        } else {
            "Die Datei gehört zu einer anderen App. Sie wird nicht installiert."
        }
        UpdateError.WRONG_SIGNATURE ->
            "Das Update ist nicht mit deinem Schlüssel signiert. Aus Sicherheitsgründen wird es nicht installiert."
        UpdateError.NOT_NEWER -> "Die heruntergeladene Version ist nicht neuer als deine."
        UpdateError.INSTALLER_MISSING -> "Der Paket-Installer von Android ließ sich nicht öffnen."
    }

    /**
     * A short plain-text excerpt of GitHub release notes: markdown headings, emphasis, links and
     * the auto-generated "Full Changelog" line are stripped, bullets become "•". Ends with "…"
     * when something was cut.
     */
    fun notesExcerpt(notes: String, maxLines: Int = 6, maxChars: Int = 320): String {
        val lines = notes.lineSequence()
            .map(::plainLine)
            .filter { it.isNotEmpty() && !it.startsWith("Full Changelog", ignoreCase = true) }
            .toList()
        if (lines.isEmpty()) return ""
        var truncated = lines.size > maxLines
        var text = lines.take(maxLines).joinToString("\n")
        if (text.length > maxChars) {
            val cut = text.lastIndexOfAny(charArrayOf(' ', '\n'), startIndex = maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            text = text.substring(0, cut).trimEnd(' ', '\n', ',', ';', ':', '–', '-')
            truncated = true
        }
        return if (truncated) "$text …" else text
    }

    private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val BULLET = Regex("""^[-*+]\s+""")
    private val RULE = Regex("""^([-*_])\1{2,}$""")

    private fun plainLine(raw: String): String {
        var line = raw.trim()
        if (RULE.matches(line)) return ""
        line = HEADING.replace(line, "")
        line = LINK.replace(line) { it.groupValues[1] }
        line = line.replace("**", "").replace("__", "").replace("`", "")
        line = BULLET.replace(line, "• ")
        return line.trim()
    }

    /** "12,3 MB" (decimal megabytes, German comma). */
    fun megabytes(bytes: Long): String {
        val mb = (bytes.coerceAtLeast(0) / 1_000_000.0).coerceAtLeast(if (bytes > 0) 0.1 else 0.0)
        return String.format(Locale.GERMANY, "%.1f MB", mb)
    }

    /** "42 %" — rounded down, so 100 % only shows when everything arrived. */
    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()} %"

    /** "Zuletzt geprüft: heute um 14:32" etc.; "Noch nicht geprüft" for null. */
    fun lastCheck(now: ZonedDateTime, checkedAt: ZonedDateTime?): String {
        if (checkedAt == null) return "Noch nicht geprüft"
        val at = checkedAt.withZoneSameInstant(now.zone)
        val ago = Duration.between(at, now)
        if (!ago.isNegative && ago < Duration.ofMinutes(1)) return "Zuletzt geprüft: gerade eben"
        val time = String.format(Locale.GERMANY, "%02d:%02d", at.hour, at.minute)
        val day = when (ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())) {
            0L -> "heute"
            1L -> "gestern"
            else -> Fmt.shortDate(at.toLocalDate())
        }
        return "Zuletzt geprüft: $day um $time"
    }
}
