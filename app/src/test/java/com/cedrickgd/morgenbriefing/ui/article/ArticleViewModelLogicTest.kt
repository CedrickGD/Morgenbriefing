package com.cedrickgd.morgenbriefing.ui.article

import com.cedrickgd.morgenbriefing.data.model.ArticleBlock
import com.cedrickgd.morgenbriefing.data.model.ArticleContent
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleViewModelLogicTest {

    private fun content(vararg blocks: ArticleBlock) =
        ArticleContent(url = "https://x.de/a", title = "T", blocks = blocks.toList(), fetchedAt = "2026-10-30T18:00:00+01:00")

    @Test
    fun `the lead paragraph that repeats the teaser is dropped`() {
        val teaser = "Am ersten Tag der Kunstrad-WM in Ludwigsburg steigt die Eröffnungsfeier."
        val c = content(ArticleBlock.heading("H"), ArticleBlock.paragraph("Am ersten Tag der Kunstrad-WM in Ludwigsburg steigt die Eröffnungsfeier!"), ArticleBlock.paragraph("Weiter geht es."))
        val trimmed = ArticleViewModel.withoutTeaser(c, teaser)
        assertEquals(listOf("H", "Weiter geht es."), trimmed.blocks.map { it.text })
    }

    @Test
    fun `a teaser cut with an ellipsis matches the paragraph it starts`() {
        val c = content(ArticleBlock.paragraph("Drei entscheidende Zahlen fehlen im Börsenprospekt von Anthropic. Die Financial Times erklärt alles."))
        val trimmed = ArticleViewModel.withoutTeaser(c, "Drei entscheidende Zahlen fehlen im Börsenprospekt von Anthropic. Die Financial Times…")
        assertEquals(0, trimmed.blocks.size)
    }

    @Test
    fun `a different first paragraph stays`() {
        val c = content(ArticleBlock.paragraph("Ganz anderer Einstieg in den Text, der nichts mit dem Teaser zu tun hat."))
        assertEquals(1, ArticleViewModel.withoutTeaser(c, "Der Teaser der Redaktion ist ein anderer Satz.").blocks.size)
        assertEquals(1, ArticleViewModel.withoutTeaser(c, "").blocks.size)
    }
}
