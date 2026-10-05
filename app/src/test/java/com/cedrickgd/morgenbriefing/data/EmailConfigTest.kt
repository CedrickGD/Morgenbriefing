package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailRecipients
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailConfigTest {

    @Test
    fun `json round trip keeps every field`() {
        val config = EmailConfig(
            schema = 1,
            enabled = true,
            recipients = listOf("a@b.de", "c@d.de"),
            updatedAt = "2026-10-05T18:52:07+02:00",
        )
        val text = AppJson.encodeToString(EmailConfig.serializer(), config)
        assertEquals(config, AppJson.decodeFromString(EmailConfig.serializer(), text))
        assertTrue(text.contains("\"schema\":1"))
        assertTrue(text.contains("\"updatedAt\":\"2026-10-05T18:52:07+02:00\""))
    }

    @Test
    fun `documented file decodes and unknown keys are ignored`() {
        val json = """
            {"schema": 1, "enabled": false, "recipients": ["business@example.com"],
             "updatedAt": "2026-10-05T04:00:00+02:00", "futureField": {"x": 1}}
        """.trimIndent()
        val config = AppJson.decodeFromString(EmailConfig.serializer(), json)
        assertEquals(EmailConfig(1, false, listOf("business@example.com"), "2026-10-05T04:00:00+02:00"), config)
    }

    @Test
    fun `missing fields fall back to a safe default (off, nobody)`() {
        val config = AppJson.decodeFromString(EmailConfig.serializer(), "{}")
        assertEquals(EmailConfig.SCHEMA, config.schema)
        assertFalse(config.enabled)
        assertTrue(config.recipients.isEmpty())
        assertEquals(EmailConfig.UNCONFIGURED, config)
    }

    @Test
    fun `normalize trims, lowercases and drops a mailto prefix`() {
        assertEquals("anna@example.de", EmailRecipients.normalize("  Anna@Example.DE \n"))
        assertEquals("anna@example.de", EmailRecipients.normalize("MAILTO: Anna@example.de"))
        assertEquals("", EmailRecipients.normalize("   "))
    }

    @Test
    fun `plausible addresses are valid`() {
        listOf(
            "a@b.de",
            "business.grabe@gmail.com",
            "first.last+tag@sub.example.co.uk",
            "x_y-z@xn--mller-kva.de",
            "o'brien@example.ie",
            "1234@123.example.com",
            "${"l".repeat(64)}@example.de",
        ).forEach { assertTrue(it, EmailRecipients.isValid(it)) }
    }

    @Test
    fun `broken addresses are invalid`() {
        listOf(
            "",
            "plain",
            "@example.de",
            "a@",
            "a@b",
            "a@b.c",
            "a@b.1de",
            "a..b@c.de",
            ".a@c.de",
            "a.@c.de",
            "a@-b.de",
            "a@b-.de",
            "a@b..de",
            "a@@b.de",
            "a b@c.de",
            "a@b.de ",
            "\"quoted\"@example.de",
            "ä@example.de",
            "a@[127.0.0.1]",
            "${"l".repeat(65)}@example.de",
            "a@${"d".repeat(63)}.${"e".repeat(63)}.${"f".repeat(63)}.${"g".repeat(61)}.de",
        ).forEach { assertFalse(it, EmailRecipients.isValid(it)) }
    }

    @Test
    fun `check reports empty, limit, invalid and duplicate in that order`() {
        val list = listOf("a@b.de", "C@D.de ")
        assertEquals(RecipientCheck.EMPTY, EmailRecipients.check(list, "  "))
        assertEquals(RecipientCheck.INVALID, EmailRecipients.check(list, "kein-at-zeichen"))
        assertEquals(RecipientCheck.DUPLICATE, EmailRecipients.check(list, " A@B.DE"))
        assertEquals(RecipientCheck.DUPLICATE, EmailRecipients.check(list, "c@d.de"))
        assertEquals(RecipientCheck.OK, EmailRecipients.check(list, "neu@example.de"))

        val full = (1..EmailConfig.MAX_RECIPIENTS).map { "user$it@example.de" }
        assertEquals(RecipientCheck.LIMIT_REACHED, EmailRecipients.check(full, "neu@example.de"))
        assertEquals(RecipientCheck.LIMIT_REACHED, EmailRecipients.check(full, "kaputt"))
        assertEquals(RecipientCheck.OK, EmailRecipients.check(full.drop(1), "neu@example.de"))
    }

    @Test
    fun `normalized config is unique, lowercase and without blanks`() {
        val config = EmailConfig(enabled = true, recipients = listOf(" A@b.de", "a@B.de", "", "c@d.de"))
        assertEquals(listOf("a@b.de", "c@d.de"), config.normalized().recipients)
        assertEquals(listOf("a@b.de"), EmailRecipients.normalizeAll(listOf("a@b.de", " A@B.DE ", "   ")))
    }

    @Test
    fun `sameSettingsAs ignores bookkeeping fields`() {
        val a = EmailConfig(enabled = true, recipients = listOf("a@b.de"), updatedAt = "2026-10-05T04:00:00+02:00")
        assertTrue(a.sameSettingsAs(a.copy(updatedAt = "2026-10-06T04:00:00+02:00")))
        assertFalse(a.sameSettingsAs(a.copy(enabled = false)))
        assertFalse(a.sameSettingsAs(a.copy(recipients = listOf("a@b.de", "c@d.de"))))
        assertFalse(a.sameSettingsAs(null))
    }
}
