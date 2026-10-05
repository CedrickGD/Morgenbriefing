package com.cedrickgd.morgenbriefing.listen

import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenScriptTest {

    private fun item(
        headline: String = "Telekom will mit KI sparen",
        summary: String = "Die Telekom setzt auf KI.",
        body: String = "",
        source: String = "Handelsblatt",
        url: String = "https://www.handelsblatt.com/a",
        sources: List<SourceRef> = emptyList(),
    ) = NewsItem(id = "ki-a", headline = headline, summary = summary, body = body, source = source, url = url, sources = sources)

    private fun texts(script: ListenScript) = script.parts.map { it.text }

    // --- sentences ------------------------------------------------------------------------

    @Test
    fun `splits sentences at end punctuation before an uppercase start`() {
        assertEquals(
            listOf("Der Euro fällt.", "Die Börse reagiert!", "Was nun?"),
            splitSentences("Der Euro fällt. Die Börse reagiert! Was nun?"),
        )
    }

    @Test
    fun `keeps ordinals, initials and abbreviations inside the sentence`() {
        val text = "Am 3. Oktober kam z. B. Dr. Müller bzw. Prof. Schmidt nach Leipzig. Danach regnete es."
        assertEquals(
            listOf("Am 3. Oktober kam z. B. Dr. Müller bzw. Prof. Schmidt nach Leipzig.", "Danach regnete es."),
            splitSentences(text),
        )
    }

    @Test
    fun `closing quotes stay with their sentence`() {
        assertEquals(
            listOf("„KI verändert alles.“", "Das sagte er."),
            splitSentences("„KI verändert alles.“ Das sagte er."),
        )
    }

    @Test
    fun `lowercase continuation is not a new sentence`() {
        assertEquals(listOf("Es kostet 2,5 Mrd. euro im Jahr."), splitSentences("Es kostet 2,5 Mrd. euro im Jahr."))
    }

    // --- chunking -------------------------------------------------------------------------

    @Test
    fun `packs whole sentences up to the soft limit`() {
        val chunks = chunkText("Eins ist hier. Zwei ist da. Drei folgt jetzt.", soft = 30, hard = 100)
        assertEquals(listOf("Eins ist hier. Zwei ist da.", "Drei folgt jetzt."), chunks)
    }

    @Test
    fun `no chunk exceeds the hard limit, long sentences break at clauses`() {
        val sentence = (1..40).joinToString(", ") { "Teil $it mit Text" } + "."
        val chunks = chunkText(sentence, soft = 120, hard = 150)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 150 })
        assertTrue("cut after a comma", chunks.dropLast(1).all { it.endsWith(",") })
        assertEquals(sentence.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `a word longer than the hard limit is cut hard`() {
        val chunks = chunkText("x".repeat(25), soft = 10, hard = 10)
        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), chunks)
    }

    @Test
    fun `every script part stays below the engine limit`() {
        val body = (1..30).joinToString("\n\n") { "Absatz $it. " + "Ein recht langer Satz über Märkte und Zinsen. ".repeat(12) }
        val script = ListenScript.from(item(body = body), maxLength = 200)
        assertTrue(script.parts.isNotEmpty())
        assertTrue(script.parts.all { it.text.length < 200 })
    }

    // --- markers --------------------------------------------------------------------------

    @Test
    fun `subheading and bullet markers become sentences with pauses`() {
        val body = "Erster Absatz.\n\n## Was jetzt kommt\n\n- erster Punkt\n- zweiter Punkt!\n\nLetzter Absatz."
        val script = ListenScript.from(item(body = body))
        val all = texts(script)
        assertTrue(all.none { it.contains("##") || it.startsWith("- ") })
        assertTrue("Was jetzt kommt." in all)
        assertTrue("erster Punkt." in all)
        assertTrue("zweiter Punkt!" in all)
        val heading = script.parts.indexOfFirst { it.text == "Was jetzt kommt." }
        assertEquals(ListenScript.PAUSE_SECTION_MS, script.parts[heading - 1].pauseAfterMs)
        assertEquals(ListenScript.PAUSE_SUBHEADING_MS, script.parts[heading].pauseAfterMs)
        assertEquals(ListenScript.PAUSE_BULLET_MS, script.parts.first { it.text == "erster Punkt." }.pauseAfterMs)
    }

    @Test
    fun `reads headline, lead, body and sources in that order`() {
        val script = ListenScript.from(
            item(
                body = "Der Text.",
                sources = listOf(SourceRef("Handelsblatt", "https://www.handelsblatt.com/a"), SourceRef("ZEIT", "https://zeit.de/b")),
            ),
        )
        assertEquals(
            listOf("Telekom will mit KI sparen", "Die Telekom setzt auf KI.", "Der Text.", "Quellen: Handelsblatt und ZEIT."),
            texts(script),
        )
        assertEquals(ListenScript.PAUSE_HEADLINE_MS, script.parts[0].pauseAfterMs)
        assertEquals("nothing after the last part", 0, script.parts.last().pauseAfterMs)
    }

    @Test
    fun `markup in the text is not read out`() {
        val script = ListenScript.from(item(summary = "Mehr bei **[tagesschau](https://x.de)** &amp; <b>MDR</b>."))
        assertEquals("Mehr bei tagesschau & MDR.", script.parts[1].text)
    }

    @Test
    fun `sources sentence lists names naturally`() {
        assertEquals("Quelle: Handelsblatt.", ListenScript.sourcesSentence(item()))
        val three = item(sources = listOf(SourceRef("A", "https://a.de"), SourceRef("B", "https://b.de"), SourceRef("C", "https://c.de")))
        assertEquals("Quellen: Handelsblatt, A, B und C.", ListenScript.sourcesSentence(three))
        assertNull(ListenScript.sourcesSentence(item(source = "", url = "")))
    }

    @Test
    fun `list items and headings get a final punctuation mark`() {
        assertEquals("Punkt.", asSentence("Punkt"))
        assertEquals("Punkt?", asSentence("Punkt?"))
        assertEquals("Er sagte: „Ja.“", asSentence("Er sagte: „Ja.“"))
        assertEquals("", asSentence("  "))
    }

    @Test
    fun `an article without text gives an empty script`() {
        assertTrue(ListenScript.from(item(headline = " ", summary = "", source = "", url = "")).isEmpty)
    }

    // --- progress -------------------------------------------------------------------------

    @Test
    fun `progress runs from 0 to 1, weighted by speaking time`() {
        val script = ListenScript(
            listOf(
                SpeechPart("a".repeat(15), pauseAfterMs = 0),
                SpeechPart("b".repeat(45), pauseAfterMs = 0),
            ),
        )
        assertEquals(0f, script.progressAt(0), 0.0001f)
        assertEquals(0.25f, script.progressAt(1), 0.0001f)
        assertEquals(0.625f, script.progressAt(1, charOffset = 22), 0.01f)
        assertEquals(1f, script.progressAt(1, charOffset = 45), 0.0001f)
        assertEquals(1f, script.progressAt(5), 0.0001f)
        assertEquals(0f, script.progressAt(-1), 0.0001f)
        assertEquals(4, script.estimatedSeconds)
    }

    @Test
    fun `progress never goes backwards while reading through a real script`() {
        val script = ListenScript.from(item(body = "Erster Absatz mit Text.\n\n## Zwischentitel\n\n- a\n- b\n\nSchluss."))
        var last = -1f
        script.parts.forEachIndexed { i, part ->
            for (offset in 0..part.text.length step 3) {
                val p = script.progressAt(i, offset)
                assertTrue("part $i offset $offset: $p < $last", p >= last - 1e-6f)
                last = p
            }
        }
        assertEquals(1f, script.progressAt(script.lastIndex, script.parts.last().text.length), 0.0001f)
    }

    @Test
    fun `remaining minutes round sensibly and never show zero`() {
        assertEquals(3, remainingMinutes(estimatedSeconds = 180, progress = 0f))
        assertEquals(2, remainingMinutes(estimatedSeconds = 180, progress = 0.4f))
        assertEquals(1, remainingMinutes(estimatedSeconds = 180, progress = 0.99f))
        assertNull(remainingMinutes(estimatedSeconds = 0, progress = 0f))
    }

    // --- utterance ids --------------------------------------------------------------------

    @Test
    fun `utterance ids round-trip and reject foreign ids`() {
        assertEquals(Triple(7, 12, false), UtteranceIds.parse(UtteranceIds.speech(7, 12)))
        assertEquals(Triple(7, 3, true), UtteranceIds.parse(UtteranceIds.silence(7, 3)))
        assertNull(UtteranceIds.parse(null))
        assertNull(UtteranceIds.parse("x:1:2"))
        assertNull(UtteranceIds.parse("u:one:2"))
        assertFalse(UtteranceIds.parse("u:1:2")!!.third)
    }

    @Test
    fun `state helpers`() {
        val reading = ListenState(speaking = true, articleKey = "k")
        assertTrue(reading.isReading("k"))
        assertFalse(reading.isReading("other"))
        assertTrue(ListenState(paused = true, articleKey = "k").isReading("k"))
        assertFalse(ListenState(articleKey = "k").isReading("k"))
        assertTrue(ListenState(issue = ListenIssue.MISSING_VOICE).showsMiniPlayer())
        assertFalse(ListenState().showsMiniPlayer())
    }
}
