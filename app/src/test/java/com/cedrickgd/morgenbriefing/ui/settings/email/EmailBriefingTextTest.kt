package com.cedrickgd.morgenbriefing.ui.settings.email

import com.cedrickgd.morgenbriefing.data.EmailSettingsState
import com.cedrickgd.morgenbriefing.data.RemoveRecipientResult
import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EmailBriefingTextTest {
    private val config = EmailConfig(enabled = true, recipients = listOf("anna@example.de", "ben@example.de"))

    /** 18:52 in Berlin (CEST). */
    private val evening: Instant = Instant.parse("2026-10-05T16:52:07Z")

    /** 04:30 in Berlin (CEST) — before the morning run. */
    private val night: Instant = Instant.parse("2026-10-05T02:30:00Z")

    @Test
    fun `inline validation messages`() {
        assertEquals("Keine gültige Adresse", EmailBriefingText.recipientMessage(RecipientCheck.INVALID))
        assertEquals("Schon in der Liste", EmailBriefingText.recipientMessage(RecipientCheck.DUPLICATE))
        assertEquals("Höchstens 10 Adressen", EmailBriefingText.recipientMessage(RecipientCheck.LIMIT_REACHED))
        assertNotNull(EmailBriefingText.recipientMessage(RecipientCheck.UNAVAILABLE))
        assertNull(EmailBriefingText.recipientMessage(RecipientCheck.OK))
        assertNull(EmailBriefingText.recipientMessage(RecipientCheck.EMPTY))
    }

    @Test
    fun `remove feedback and description`() {
        assertNull(EmailBriefingText.removeMessage(RemoveRecipientResult.REMOVED))
        assertNull(EmailBriefingText.removeMessage(RemoveRecipientResult.UNAVAILABLE))
        assertNotNull(EmailBriefingText.removeMessage(RemoveRecipientResult.LAST_RECIPIENT))
        assertEquals("anna@example.de entfernen", EmailBriefingText.removeDescription("anna@example.de"))
    }

    @Test
    fun `switch subtitle follows the config`() {
        assertEquals("An – geht an 2 Adressen", EmailBriefingText.switchSubtitle(config))
        assertEquals("An – geht an 1 Adresse", EmailBriefingText.switchSubtitle(config.copy(recipients = listOf("a@b.de"))))
        assertEquals("An – es fehlt noch eine Adresse", EmailBriefingText.switchSubtitle(config.copy(recipients = emptyList())))
        assertEquals("Aus – das Briefing gibt's nur in der App", EmailBriefingText.switchSubtitle(config.copy(enabled = false)))
        assertTrue(EmailBriefingText.switchSubtitle(null).isNotBlank())
    }

    @Test
    fun `a change saved before the morning run counts today`() {
        assertEquals("gilt ab morgen früh", EmailBriefingText.appliesFrom(evening))
        assertEquals("gilt ab heute früh", EmailBriefingText.appliesFrom(night))
    }

    @Test
    fun `status line priorities`() {
        assertEquals(
            EmailStatusLine("Speichern …", StatusTone.PROGRESS),
            EmailBriefingText.status(EmailSettingsState(config = config, saving = true, lastSavedAt = evening)),
        )
        assertEquals(
            EmailStatusLine("Gespeichert ✓ · gilt ab morgen früh", StatusTone.SUCCESS),
            EmailBriefingText.status(EmailSettingsState(config = config, lastSavedAt = evening)),
        )
        assertEquals(
            EmailStatusLine(EmailBriefingText.errorMessage(EmailConfigError.NETWORK), StatusTone.ERROR),
            EmailBriefingText.status(EmailSettingsState(config = config, lastSavedAt = evening, error = EmailConfigError.NETWORK)),
        )
        assertEquals(
            StatusTone.PROGRESS,
            EmailBriefingText.status(EmailSettingsState(loading = true))?.tone,
        )
        assertEquals(
            StatusTone.HINT,
            EmailBriefingText.status(EmailSettingsState(config = EmailConfig.UNCONFIGURED))?.tone,
        )
        // Loaded, nothing changed yet: the controls speak for themselves.
        assertNull(EmailBriefingText.status(EmailSettingsState(config = config)))
        assertNull(EmailBriefingText.status(EmailSettingsState()))
    }

    @Test
    fun `token and permission errors get their own block instead of a status line`() {
        for (error in listOf(EmailConfigError.NO_TOKEN, EmailConfigError.NO_WRITE_PERMISSION)) {
            assertTrue(EmailBriefingText.hasOwnBlock(error))
            assertFalse(EmailBriefingText.canReload(error))
            assertNull(EmailBriefingText.status(EmailSettingsState(config = config, error = error)))
        }
        assertTrue(EmailBriefingText.canReload(EmailConfigError.CONFLICT))
        assertFalse(EmailBriefingText.canReload(null))
    }

    @Test
    fun `every error has a German message`() {
        EmailConfigError.entries.forEach { assertTrue(it.name, EmailBriefingText.errorMessage(it).isNotBlank()) }
        assertTrue(EmailBriefingText.NO_WRITE_PERMISSION.contains("Contents: Read and write"))
        assertEquals(EmailBriefingText.NO_WRITE_PERMISSION, EmailBriefingText.errorMessage(EmailConfigError.NO_WRITE_PERMISSION))
        assertEquals("https://github.com/settings/personal-access-tokens", EmailBriefingText.TOKEN_SETTINGS_URL)
    }
}
