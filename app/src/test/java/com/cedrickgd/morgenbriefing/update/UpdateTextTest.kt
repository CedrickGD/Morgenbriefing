package com.cedrickgd.morgenbriefing.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UpdateTextTest {

    @Test
    fun `release notes become a plain excerpt`() {
        val notes = """
            ## Neu in 1.2.0

            - **Themen** sortieren und ausblenden
            * Artikel komplett in der App lesen ([Details](https://example.com))
            ---
            `Kleinigkeiten` behoben

            **Full Changelog**: https://github.com/CedrickGD/Morgenbriefing/compare/v1.1.0...v1.2.0
        """.trimIndent()

        assertEquals(
            "Neu in 1.2.0\n• Themen sortieren und ausblenden\n• Artikel komplett in der App lesen (Details)\nKleinigkeiten behoben",
            UpdateText.notesExcerpt(notes),
        )
    }

    @Test
    fun `long notes are cut with an ellipsis`() {
        val manyLines = (1..10).joinToString("\n") { "- Punkt $it" }
        val excerpt = UpdateText.notesExcerpt(manyLines, maxLines = 3)
        assertEquals("• Punkt 1\n• Punkt 2\n• Punkt 3 …", excerpt)

        val longLine = "Wort ".repeat(100).trim()
        val cut = UpdateText.notesExcerpt(longLine, maxChars = 60)
        assertTrue(cut.endsWith(" …"))
        assertTrue(cut.length <= 62)
        assertTrue("cuts at a word boundary", cut.removeSuffix(" …").split(" ").all { it == "Wort" })
    }

    @Test
    fun `empty notes stay empty`() {
        assertEquals("", UpdateText.notesExcerpt(""))
        assertEquals("", UpdateText.notesExcerpt("\n\n**Full Changelog**: https://x\n"))
    }

    @Test
    fun `sizes and percentages are german`() {
        assertEquals("12,3 MB", UpdateText.megabytes(12_345_678))
        assertEquals("0,1 MB", UpdateText.megabytes(2_000))
        assertEquals("0,0 MB", UpdateText.megabytes(0))
        assertEquals("0 %", UpdateText.percent(0f))
        assertEquals("42 %", UpdateText.percent(0.429f))
        assertEquals("99 %", UpdateText.percent(0.999f))
        assertEquals("100 %", UpdateText.percent(1f))
    }

    @Test
    fun `last check label`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 6, 14, 40, 0, 0, zone)
        assertEquals("Noch nicht geprüft", UpdateText.lastCheck(now, null))
        assertEquals("Zuletzt geprüft: gerade eben", UpdateText.lastCheck(now, now.minusSeconds(20)))
        assertEquals("Zuletzt geprüft: heute um 14:32", UpdateText.lastCheck(now, now.withMinute(32)))
        assertEquals("Zuletzt geprüft: gestern um 08:05", UpdateText.lastCheck(now, now.minusDays(1).withHour(8).withMinute(5)))
        assertEquals(
            "Zuletzt geprüft: Fr, 2. Okt. um 07:00",
            UpdateText.lastCheck(now, now.minusDays(4).withHour(7).withMinute(0)),
        )
        // Stored instants are shown in the reader's zone.
        val utc = now.withZoneSameInstant(ZoneId.of("UTC")).minusHours(1)
        assertEquals("Zuletzt geprüft: heute um 13:40", UpdateText.lastCheck(now, utc))
    }

    @Test
    fun `every error has its own friendly message`() {
        val messages = UpdateError.entries.map { UpdateText.errorMessage(it) }
        messages.forEach { assertTrue(it.isNotBlank() && it.length < 140) }
        assertEquals(messages.size, messages.toSet().size)
        assertNotEquals(
            UpdateText.errorMessage(UpdateError.WRONG_PACKAGE, debugBuild = true),
            UpdateText.errorMessage(UpdateError.WRONG_PACKAGE, debugBuild = false),
        )
    }
}
