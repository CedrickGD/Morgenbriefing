package com.cedrickgd.morgenbriefing.data

import android.util.Log
import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.EmailConfigException
import com.cedrickgd.morgenbriefing.data.model.EmailRecipients
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import com.cedrickgd.morgenbriefing.data.remote.EmailConfigRemote
import com.cedrickgd.morgenbriefing.data.remote.StoredEmailConfig
import com.cedrickgd.morgenbriefing.util.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What the "E-Mail-Briefing" section shows. */
data class EmailSettingsState(
    /** A read of `config/email.json` is running. */
    val loading: Boolean = false,
    /**
     * The config as the user sees it — optimistic while [saving]. Null until the first successful
     * read; [EmailConfig.UNCONFIGURED] when the file does not exist yet.
     */
    val config: EmailConfig? = null,
    /** Blob sha of the stored file; null while it does not exist (the first save creates it). */
    val sha: String? = null,
    /** A change waits for its debounce or is being written. */
    val saving: Boolean = false,
    /** When the last write succeeded (this process). */
    val lastSavedAt: Instant? = null,
    /** Last failure; cleared by the next change or a successful read. */
    val error: EmailConfigError? = null,
)

/** Outcome of [EmailSettingsRepository.removeRecipient]. */
enum class RemoveRecipientResult {
    REMOVED,

    /** The stored file needs at least one recipient — switch the e-mail off instead. */
    LAST_RECIPIENT,

    /** Not loaded yet or not in the list. */
    UNAVAILABLE,
}

/**
 * The e-mail switch and recipient list (`config/email.json` on the briefs branch).
 *
 * Changes show up immediately (optimistic) and are written right away; switch toggles are
 * debounced by [toggleDebounceMillis] so flipping back and forth costs at most one commit (none if
 * it ends where it started). A failed write rolls the UI back to the last stored state and sets
 * [EmailSettingsState.error]. Writes are serialized; edits made while a write runs are written
 * after it, on top of the new sha. A failure rolls back everything that was not stored yet.
 *
 * Not thread-safe by design: call it from the main thread; [scope] must be confined to it
 * (the default is `Dispatchers.Main.immediate`). The scope outlives screens on purpose, so a
 * change still reaches GitHub after the user leaves the settings page.
 */
class EmailSettingsRepository(
    private val remote: EmailConfigRemote,
    /** The stored GitHub token, or null; never logged. */
    private val tokenProvider: suspend () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: Clock = Clock.system(BERLIN),
    private val toggleDebounceMillis: Long = TOGGLE_DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(EmailSettingsState())
    val state: StateFlow<EmailSettingsState> = _state.asStateFlow()

    /** What is on GitHub as far as we know; base of every write and target of every rollback. */
    private var confirmed: StoredEmailConfig? = null

    /** Serializes reads and writes so every write starts from the newest known sha. */
    private val io = Mutex()
    private var loadJob: Job? = null
    private var debounceJob: Job? = null

    /** A change is waiting for its debounce delay. */
    private var debouncing = false

    /** Writes launched but not finished (waiting for [io] or running). */
    private var writesInFlight = 0

    /** Incremented by every user change; a read only replaces the shown config if none happened meanwhile. */
    private var edits = 0L
    private var lastReadAt: Instant? = null

    /**
     * Reads the config from GitHub. Skipped while a read runs, while local changes are not yet
     * stored (they are newer) and — unless [force] — if the last read is less than a minute old.
     */
    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true || debouncing || writesInFlight > 0) return
        val s = _state.value
        val fresh = lastReadAt?.let { Duration.between(it, clock.instant()) < FRESH_FOR } == true
        if (!force && fresh && s.config != null && s.error == null) return
        _state.update { it.copy(loading = true) }
        loadJob = scope.launch { read() }
    }

    /** "Auch per E-Mail" on/off; written after [toggleDebounceMillis] without further toggles. */
    fun setEnabled(enabled: Boolean) {
        change(toggleDebounceMillis) { it.copy(enabled = enabled) }
    }

    /** Validates [raw] (normalized: trimmed, lowercase) and, if [RecipientCheck.OK], adds and saves it. */
    fun addRecipient(raw: String): RecipientCheck {
        val current = _state.value.config ?: return RecipientCheck.UNAVAILABLE
        val check = EmailRecipients.check(current.recipients, raw)
        if (check == RecipientCheck.OK) {
            val address = EmailRecipients.normalize(raw)
            change(delayMillis = 0) { it.copy(recipients = it.recipients + address) }
        }
        return check
    }

    /** Removes [address] and saves; the last recipient stays (the file needs at least one). */
    fun removeRecipient(address: String): RemoveRecipientResult {
        val current = _state.value.config ?: return RemoveRecipientResult.UNAVAILABLE
        val target = EmailRecipients.normalize(address)
        if (target !in current.recipients) return RemoveRecipientResult.UNAVAILABLE
        if (current.recipients.size <= 1) return RemoveRecipientResult.LAST_RECIPIENT
        change(delayMillis = 0) { config -> config.copy(recipients = config.recipients.filterNot { it == target }) }
        return RemoveRecipientResult.REMOVED
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---------------------------------------------------------------------------------------

    private fun change(delayMillis: Long, transform: (EmailConfig) -> EmailConfig) {
        val current = _state.value.config ?: return
        val next = transform(current)
        if (next == current) return
        edits++
        _state.update { it.copy(config = next, error = null, saving = true) }
        debounceJob?.cancel()
        debouncing = true
        debounceJob = scope.launch {
            delay(delayMillis)
            debouncing = false
            writesInFlight++
            // Separate job: a later change cancels the debounce, never a running PUT.
            scope.launch {
                try {
                    io.withLock { writeLatest() }
                } finally {
                    writesInFlight--
                    settleSaving()
                }
            }
        }
    }

    /** Writes the shown config if it differs from the stored one. Runs under [io]. */
    private suspend fun writeLatest() {
        val desired = _state.value.config ?: return
        val base = confirmed ?: return
        if (desired.sameSettingsAs(base.config)) return
        // Without a recipient there is nothing valid to store yet; the first address saves it.
        if (desired.recipients.isEmpty()) return

        val auth = currentToken() ?: return rollback(EmailConfigError.NO_TOKEN)
        val toWrite = desired.normalized().copy(schema = EmailConfig.SCHEMA, updatedAt = timestamp())
        val result = attempt { remote.write(auth, toWrite, base.sha) }
        val stored = result.getOrElse { e ->
            Log.w(TAG, "Saving the e-mail config failed", e)
            return rollback(e.toEmailConfigError())
        }
        confirmed = stored
        _state.update { s ->
            s.copy(
                // Newer local edits stay on screen (and are written next); otherwise show what was stored.
                config = if (desired.sameSettingsAs(s.config)) stored.config else s.config,
                sha = stored.sha,
                lastSavedAt = clock.instant(),
                error = null,
            )
        }
    }

    /** Back to the stored state; pending (not yet written) changes are dropped. */
    private fun rollback(error: EmailConfigError) {
        debounceJob?.cancel()
        debouncing = false
        _state.update { it.copy(config = confirmed?.config ?: it.config, error = error) }
    }

    private fun settleSaving() {
        if (!debouncing && writesInFlight == 0) _state.update { it.copy(saving = false) }
    }

    private suspend fun read() {
        val editsAtStart = edits
        io.withLock {
            val auth = currentToken()
            if (auth == null) {
                forget(EmailConfigError.NO_TOKEN)
                return
            }
            val result = attempt { remote.read(auth) }
            val stored = result.getOrElse { e ->
                Log.w(TAG, "Reading the e-mail config failed", e)
                val kind = e.toEmailConfigError()
                if (kind == EmailConfigError.UNAUTHORIZED) {
                    forget(kind)
                } else {
                    _state.update { it.copy(loading = false, error = kind) }
                }
                return
            } ?: StoredEmailConfig(EmailConfig.UNCONFIGURED, sha = null)

            confirmed = stored
            lastReadAt = clock.instant()
            _state.update { s ->
                s.copy(
                    loading = false,
                    config = if (s.config == null || edits == editsAtStart) stored.config else s.config,
                    sha = stored.sha,
                    error = null,
                )
            }
        }
    }

    /** No (usable) token: whatever was shown may belong to another account — clear it. */
    private fun forget(error: EmailConfigError) {
        confirmed = null
        lastReadAt = null
        _state.update { it.copy(loading = false, config = null, sha = null, error = error) }
    }

    private suspend fun currentToken(): String? =
        attempt { tokenProvider() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** ISO-8601 with offset, seconds precision, e.g. `2026-10-05T18:52:07+02:00`. */
    private fun timestamp(): String =
        OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun Throwable.toEmailConfigError(): EmailConfigError =
        (this as? EmailConfigException)?.kind ?: EmailConfigError.UNKNOWN

    companion object {
        private const val TAG = "EmailSettings"

        /** Rapid switch flips within this window become one write. */
        const val TOGGLE_DEBOUNCE_MS = 600L

        /** A read younger than this is not repeated when the section reappears. */
        private val FRESH_FOR: Duration = Duration.ofMinutes(1)
    }
}
