package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * `config/email.json` on the briefs branch: whether the separate e-mail routine (~05:20 Berlin)
 * also sends the brief by mail, and to whom.
 *
 * ```json
 * {"schema": 1, "enabled": true, "recipients": ["a@b.de"], "updatedAt": "2026-10-05T18:52:00+02:00"}
 * ```
 *
 * A written file always has 1–[MAX_RECIPIENTS] unique, lowercase-trimmed recipients
 * ([EmailRecipients]); `updatedAt` is ISO-8601 with offset.
 */
@Serializable
data class EmailConfig(
    val schema: Int = SCHEMA,
    val enabled: Boolean = false,
    val recipients: List<String> = emptyList(),
    val updatedAt: String = "",
) {
    /** Recipients trimmed, lowercased and de-duplicated (first occurrence wins), blanks dropped. */
    fun normalized(): EmailConfig = copy(recipients = EmailRecipients.normalizeAll(recipients))

    /**
     * Stored recipients the e-mail routine would reject (a hand-edited or older file). While
     * there is one, the routine sends nothing at all — not even to the valid addresses.
     */
    fun invalidRecipients(): List<String> = recipients.filterNot(EmailRecipients::isValid)

    /** Same switch state and recipients — `schema` and `updatedAt` are bookkeeping only. */
    fun sameSettingsAs(other: EmailConfig?): Boolean =
        other != null && enabled == other.enabled && recipients == other.recipients

    companion object {
        const val SCHEMA = 1
        const val MAX_RECIPIENTS = 10

        /** Path of the file inside the briefs branch. */
        const val PATH = "config/email.json"

        /** What the app shows while the file does not exist yet: off, nobody. */
        val UNCONFIGURED = EmailConfig(enabled = false, recipients = emptyList())
    }
}

/** Outcome of checking a new recipient against the current list. */
enum class RecipientCheck {
    OK,

    /** Nothing typed (after trimming). */
    EMPTY,

    /** Not an address the e-mail routine accepts ([EmailRecipients.isValid]). */
    INVALID,

    /** Already in the list (case-insensitive). */
    DUPLICATE,

    /** The list already holds [EmailConfig.MAX_RECIPIENTS] addresses. */
    LIMIT_REACHED,

    /** The list is not loaded yet, so nothing can be added (repository only). */
    UNAVAILABLE,
}

/**
 * E-mail address rules for the recipient list. Pure Kotlin (no `android.util.Patterns`), so it
 * runs in JVM unit tests exactly like on the device.
 */
object EmailRecipients {
    private const val MAX_LENGTH = 254
    private const val MAX_LOCAL_LENGTH = 64

    /**
     * Exactly the rules of the e-mail routine (`routine/email-config.mjs`, docs/brief-format.md
     * "E-Mail-Briefing"), which rejects the whole file — nobody gets an e-mail — if a single
     * recipient breaks them: before the @ only a–z 0–9 . _ % + - (no dot at either end, none
     * doubled); a host name of at least two DNS labels; a TLD of letters only or punycode
     * `xn--…`. Applied to [normalize]d (lowercase) input.
     */
    private val ADDRESS = Regex(
        "^[a-z0-9_%+-]+(?:\\.[a-z0-9_%+-]+)*" +
            "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
            "(?:[a-z]{2,63}|xn--[a-z0-9-]{0,58}[a-z0-9])$",
    )

    /** Trims whitespace, drops a pasted `mailto:` prefix and lowercases. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val withoutScheme = if (trimmed.startsWith("mailto:", ignoreCase = true)) {
            trimmed.substring("mailto:".length).trim()
        } else {
            trimmed
        }
        return withoutScheme.lowercase(Locale.ROOT)
    }

    /** True for an address the e-mail routine accepts; expects [normalize]d input. */
    fun isValid(address: String): Boolean {
        if (address.length > MAX_LENGTH) return false
        val local = address.substringBefore('@', missingDelimiterValue = "")
        if (local.isEmpty() || local.length > MAX_LOCAL_LENGTH) return false
        return ADDRESS.matches(address)
    }

    /** Normalizes every entry, drops blanks and duplicates (first occurrence wins). */
    fun normalizeAll(addresses: List<String>): List<String> =
        addresses.map(::normalize).filter { it.isNotEmpty() }.distinct()

    /** May [raw] be added to [existing]? Order of checks: empty, limit, invalid, duplicate. */
    fun check(existing: List<String>, raw: String): RecipientCheck {
        val address = normalize(raw)
        val current = normalizeAll(existing)
        return when {
            address.isEmpty() -> RecipientCheck.EMPTY
            current.size >= EmailConfig.MAX_RECIPIENTS -> RecipientCheck.LIMIT_REACHED
            !isValid(address) -> RecipientCheck.INVALID
            address in current -> RecipientCheck.DUPLICATE
            else -> RecipientCheck.OK
        }
    }
}

/** Why reading or writing `config/email.json` failed. */
enum class EmailConfigError {
    /** No GitHub token stored. */
    NO_TOKEN,

    /** 401 (or 403 on read): token wrong, expired or without access to the repo. */
    UNAUTHORIZED,

    /** 403/404 on write: the token may only read (needs Contents: Read and write). */
    NO_WRITE_PERMISSION,

    /** GitHub's API rate limit is exhausted for now. */
    RATE_LIMITED,

    /** The file changed on GitHub twice while saving (sha mismatch after a retry). */
    CONFLICT,

    /** The file on the branch is not a valid config. */
    INVALID,

    NETWORK,
    UNKNOWN,
}

/** Every failure of [com.cedrickgd.morgenbriefing.data.remote.GitHubConfigStore]; never carries the token. */
class EmailConfigException(val kind: EmailConfigError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: kind.name, cause)
