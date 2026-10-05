package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.EmailConfigException
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import com.cedrickgd.morgenbriefing.data.remote.EmailConfigRemote
import com.cedrickgd.morgenbriefing.data.remote.StoredEmailConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant

class EmailSettingsRepositoryTest {

    private val now: Instant = Instant.parse("2026-10-05T16:52:07Z")
    private val clock: Clock = Clock.fixed(now, BERLIN)
    private val initial = EmailConfig(1, true, listOf("anna@example.de"), "2026-10-05T04:00:00+02:00")

    private class FakeRemote(var stored: StoredEmailConfig?) : EmailConfigRemote {
        val writes = mutableListOf<Pair<EmailConfig, String?>>()
        val tokens = mutableListOf<String>()
        var reads = 0
        var readError: EmailConfigError? = null
        var writeError: EmailConfigError? = null
        var writeLatencyMs = 0L
        private var nextSha = 1

        override suspend fun read(token: String): StoredEmailConfig? {
            tokens += token
            reads++
            readError?.let { throw EmailConfigException(it) }
            return stored
        }

        override suspend fun write(token: String, config: EmailConfig, sha: String?): StoredEmailConfig {
            tokens += token
            writes += config to sha
            delay(writeLatencyMs)
            writeError?.let { throw EmailConfigException(it) }
            return StoredEmailConfig(config, "sha-${nextSha++}").also { stored = it }
        }
    }

    private class Harness(val repo: EmailSettingsRepository, val remote: FakeRemote) {
        val state get() = repo.state.value
    }

    private fun TestScope.harness(
        stored: StoredEmailConfig? = StoredEmailConfig(initial, "sha-0"),
        token: () -> String? = { TOKEN },
    ): Harness {
        val remote = FakeRemote(stored)
        val repo = EmailSettingsRepository(
            remote = remote,
            tokenProvider = { token() },
            // Foreground on purpose: advanceUntilIdle() only drives foreground work, and every
            // job of the repository must finish by itself (runTest waits for it).
            scope = this,
            clock = clock,
            toggleDebounceMillis = 600,
        )
        return Harness(repo, remote)
    }

    private fun TestScope.loaded(stored: StoredEmailConfig? = StoredEmailConfig(initial, "sha-0")): Harness =
        harness(stored).also {
            it.repo.load()
            runCurrent()
        }

    // --- load ------------------------------------------------------------------------------

    @Test
    fun `load shows the stored config and its sha`() = runTest {
        val h = harness()
        assertNull(h.state.config)

        h.repo.load()
        assertTrue(h.state.loading)
        runCurrent()

        assertEquals(initial, h.state.config)
        assertEquals("sha-0", h.state.sha)
        assertFalse(h.state.loading)
        assertNull(h.state.error)
        assertEquals(listOf(TOKEN), h.remote.tokens)
    }

    @Test
    fun `a missing file shows the unconfigured state`() = runTest {
        val h = loaded(stored = null)
        assertEquals(EmailConfig.UNCONFIGURED, h.state.config)
        assertNull(h.state.sha)
        assertNull(h.state.error)
    }

    @Test
    fun `without a token nothing is requested and the error is NO_TOKEN`() = runTest {
        val h = harness(token = { null })
        h.repo.load()
        runCurrent()

        assertEquals(EmailConfigError.NO_TOKEN, h.state.error)
        assertNull(h.state.config)
        assertEquals(0, h.remote.reads)
        assertEquals(RecipientCheck.UNAVAILABLE, h.repo.addRecipient("neu@example.de"))
    }

    @Test
    fun `a fresh read is not repeated unless forced`() = runTest {
        val h = loaded()
        h.repo.load()
        runCurrent()
        assertEquals(1, h.remote.reads)

        h.repo.load(force = true)
        runCurrent()
        assertEquals(2, h.remote.reads)
    }

    @Test
    fun `a network error keeps the shown config, a rejected token clears it`() = runTest {
        val h = loaded()
        h.remote.readError = EmailConfigError.NETWORK
        h.repo.load(force = true)
        runCurrent()
        assertEquals(EmailConfigError.NETWORK, h.state.error)
        assertEquals(initial, h.state.config)

        h.remote.readError = EmailConfigError.UNAUTHORIZED
        h.repo.load(force = true)
        runCurrent()
        assertEquals(EmailConfigError.UNAUTHORIZED, h.state.error)
        assertNull(h.state.config)
        assertNull(h.state.sha)
    }

    // --- switch ----------------------------------------------------------------------------

    @Test
    fun `toggle is optimistic and written after the debounce`() = runTest {
        val h = loaded()

        h.repo.setEnabled(false)
        assertFalse(h.state.config!!.enabled)
        assertTrue(h.state.saving)

        advanceTimeBy(599)
        assertTrue(h.remote.writes.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        val (written, sha) = h.remote.writes.single()
        assertEquals("sha-0", sha)
        assertEquals(EmailConfig(1, false, listOf("anna@example.de"), "2026-10-05T18:52:07+02:00"), written)
        assertFalse(h.state.saving)
        assertEquals(written, h.state.config)
        assertEquals("sha-1", h.state.sha)
        assertEquals(now, h.state.lastSavedAt)
        assertNull(h.state.error)
    }

    @Test
    fun `rapid toggles become one write with the final value`() = runTest {
        val h = loaded()

        h.repo.setEnabled(false)
        advanceTimeBy(300)
        h.repo.setEnabled(true)
        advanceTimeBy(300)
        h.repo.setEnabled(false)
        advanceUntilIdle()

        assertEquals(1, h.remote.writes.size)
        assertFalse(h.remote.writes.single().first.enabled)
    }

    @Test
    fun `toggling back to the stored value writes nothing`() = runTest {
        val h = loaded()

        h.repo.setEnabled(false)
        advanceTimeBy(100)
        h.repo.setEnabled(true)
        advanceUntilIdle()

        assertTrue(h.remote.writes.isEmpty())
        assertFalse(h.state.saving)
        assertNull(h.state.lastSavedAt)
    }

    @Test
    fun `a failed write rolls back and reports the error`() = runTest {
        val h = loaded()
        h.remote.writeError = EmailConfigError.NO_WRITE_PERMISSION

        h.repo.setEnabled(false)
        advanceUntilIdle()

        assertEquals(1, h.remote.writes.size)
        assertEquals(initial, h.state.config)
        assertEquals("sha-0", h.state.sha)
        assertEquals(EmailConfigError.NO_WRITE_PERMISSION, h.state.error)
        assertFalse(h.state.saving)
        assertNull(h.state.lastSavedAt)

        // The next change clears the error and tries again.
        h.remote.writeError = null
        h.repo.setEnabled(false)
        assertNull(h.state.error)
        advanceUntilIdle()
        assertEquals(2, h.remote.writes.size)
        assertFalse(h.state.config!!.enabled)
        assertEquals("sha-1", h.state.sha) // the fake only hands out a sha for stored writes
    }

    @Test
    fun `a lost token while saving rolls back with NO_TOKEN`() = runTest {
        var token: String? = TOKEN
        val h = harness(token = { token })
        h.repo.load()
        runCurrent()

        token = null
        h.repo.setEnabled(false)
        advanceUntilIdle()

        assertTrue(h.remote.writes.isEmpty())
        assertEquals(EmailConfigError.NO_TOKEN, h.state.error)
        assertTrue(h.state.config!!.enabled)
    }

    // --- recipients ------------------------------------------------------------------------

    @Test
    fun `addRecipient validates, normalizes and saves without debounce`() = runTest {
        val h = loaded()

        assertEquals(RecipientCheck.EMPTY, h.repo.addRecipient("   "))
        assertEquals(RecipientCheck.INVALID, h.repo.addRecipient("kein-at-zeichen"))
        assertEquals(RecipientCheck.DUPLICATE, h.repo.addRecipient(" ANNA@example.de"))
        assertTrue(h.remote.writes.isEmpty())

        assertEquals(RecipientCheck.OK, h.repo.addRecipient("  Ben@Example.DE "))
        assertEquals(listOf("anna@example.de", "ben@example.de"), h.state.config!!.recipients)
        runCurrent()

        val (written, sha) = h.remote.writes.single()
        assertEquals(listOf("anna@example.de", "ben@example.de"), written.recipients)
        assertTrue(written.enabled)
        assertEquals("sha-0", sha)
        assertFalse(h.state.saving)
    }

    @Test
    fun `at most ten recipients`() = runTest {
        val ten = (1..10).map { "user$it@example.de" }
        val h = loaded(StoredEmailConfig(initial.copy(recipients = ten), "sha-0"))

        assertEquals(RecipientCheck.LIMIT_REACHED, h.repo.addRecipient("neu@example.de"))
        advanceUntilIdle()
        assertTrue(h.remote.writes.isEmpty())
    }

    @Test
    fun `the last recipient cannot be removed`() = runTest {
        val h = loaded(StoredEmailConfig(initial.copy(recipients = listOf("anna@example.de", "ben@example.de")), "sha-0"))

        assertEquals(RemoveRecipientResult.REMOVED, h.repo.removeRecipient("BEN@example.de"))
        assertEquals(listOf("anna@example.de"), h.state.config!!.recipients)
        runCurrent()
        assertEquals(listOf("anna@example.de"), h.remote.writes.single().first.recipients)

        assertEquals(RemoveRecipientResult.LAST_RECIPIENT, h.repo.removeRecipient("anna@example.de"))
        assertEquals(RemoveRecipientResult.UNAVAILABLE, h.repo.removeRecipient("nicht@da.de"))
        advanceUntilIdle()
        assertEquals(1, h.remote.writes.size)
        assertEquals(listOf("anna@example.de"), h.state.config!!.recipients)
    }

    @Test
    fun `without a file the first address creates it`() = runTest {
        val h = loaded(stored = null)

        h.repo.setEnabled(true)
        advanceUntilIdle()
        assertTrue("nothing valid to store without a recipient", h.remote.writes.isEmpty())
        assertTrue(h.state.config!!.enabled)
        assertFalse(h.state.saving)

        assertEquals(RecipientCheck.OK, h.repo.addRecipient("anna@example.de"))
        advanceUntilIdle()

        val (written, sha) = h.remote.writes.single()
        assertNull(sha)
        assertEquals(EmailConfig(1, true, listOf("anna@example.de"), "2026-10-05T18:52:07+02:00"), written)
        assertEquals("sha-1", h.state.sha)
    }

    // --- concurrency -----------------------------------------------------------------------

    @Test
    fun `an edit during a running write is written afterwards on the new sha`() = runTest {
        val h = loaded()
        h.remote.writeLatencyMs = 1_000

        h.repo.setEnabled(false)
        advanceTimeBy(800) // debounce over, first PUT is running
        assertEquals(1, h.remote.writes.size)
        assertEquals(RecipientCheck.OK, h.repo.addRecipient("ben@example.de"))
        assertTrue(h.state.saving)
        advanceUntilIdle()

        assertEquals(2, h.remote.writes.size)
        val (second, sha) = h.remote.writes[1]
        assertEquals("sha-1", sha)
        assertFalse(second.enabled)
        assertEquals(listOf("anna@example.de", "ben@example.de"), second.recipients)
        assertEquals(second, h.state.config)
        assertEquals("sha-2", h.state.sha)
        assertFalse(h.state.saving)
    }

    @Test
    fun `a pending change is not overwritten by a reload`() = runTest {
        val h = loaded()

        h.repo.setEnabled(false)
        h.repo.load(force = true)
        runCurrent()
        assertEquals(1, h.remote.reads)
        assertFalse(h.state.config!!.enabled)

        advanceUntilIdle()
        assertFalse(h.remote.writes.single().first.enabled)
    }

    @Test
    fun `dismissError clears the error only`() = runTest {
        val h = loaded()
        h.remote.writeError = EmailConfigError.NETWORK
        h.repo.setEnabled(false)
        advanceUntilIdle()
        assertEquals(EmailConfigError.NETWORK, h.state.error)

        h.repo.dismissError()
        assertNull(h.state.error)
        assertEquals(initial, h.state.config)
    }

    private companion object {
        const val TOKEN = "github_pat_TEST0123456789"
    }
}
