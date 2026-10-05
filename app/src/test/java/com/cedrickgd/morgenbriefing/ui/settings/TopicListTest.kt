package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicListTest {
    private val catalogue = listOf("ki", "wirtschaft", "politik", "welt")

    @Test
    fun `normalize keeps the user order, drops unknown ids and appends missing topics`() {
        assertEquals(catalogue, TopicList.normalize(emptyList(), catalogue))
        assertEquals(
            listOf("politik", "ki", "wirtschaft", "welt"),
            TopicList.normalize(listOf("politik", "gibtsnicht", "ki", "politik"), catalogue),
        )
        assertEquals(Topics.DEFAULT_ORDER, TopicList.normalize(listOf("unbekannt")))
    }

    @Test
    fun `move puts an element at the target position`() {
        assertEquals(listOf("wirtschaft", "politik", "ki", "welt"), TopicList.move(catalogue, 0, 2))
        assertEquals(listOf("welt", "ki", "wirtschaft", "politik"), TopicList.move(catalogue, 3, 0))
        assertEquals(catalogue, TopicList.move(catalogue, 1, 1))
        // Out-of-range indices are clamped instead of crashing.
        assertEquals(listOf("wirtschaft", "politik", "welt", "ki"), TopicList.move(catalogue, -3, 99))
        assertEquals(emptyList<String>(), TopicList.move(emptyList(), 0, 1))
    }

    @Test
    fun `moveBy shifts up and down and stops at the ends`() {
        assertEquals(listOf("wirtschaft", "ki", "politik", "welt"), TopicList.moveBy(catalogue, "wirtschaft", -1))
        assertEquals(listOf("ki", "wirtschaft", "welt", "politik"), TopicList.moveBy(catalogue, "politik", 1))
        assertEquals(catalogue, TopicList.moveBy(catalogue, "ki", -1))
        assertEquals(catalogue, TopicList.moveBy(catalogue, "welt", 1))
        assertEquals(catalogue, TopicList.moveBy(catalogue, "gibtsnicht", 1))
    }

    @Test
    fun `toggling hides and shows topics`() {
        val hidden = TopicList.setVisible(catalogue, emptySet(), "politik", visible = false)
        assertEquals(setOf("politik"), hidden)
        assertEquals(emptySet<String>(), TopicList.setVisible(catalogue, hidden, "politik", visible = true))
        assertEquals(3, TopicList.visibleCount(catalogue, hidden))
    }

    @Test
    fun `the last visible topic can never be hidden`() {
        val allButKi = setOf("wirtschaft", "politik", "welt")
        assertFalse(TopicList.canHide(catalogue, allButKi, "ki"))
        assertEquals(allButKi, TopicList.setVisible(catalogue, allButKi, "ki", visible = false))
        assertEquals(1, TopicList.visibleCount(catalogue, allButKi))
        // Hidden ones can always be shown again, and other topics can still be hidden.
        assertTrue(TopicList.canHide(catalogue, allButKi, "welt"))
        assertTrue(TopicList.canHide(catalogue, setOf("welt"), "ki"))

        var hidden = emptySet<String>()
        catalogue.forEach { hidden = TopicList.setVisible(catalogue, hidden, it, visible = false) }
        assertEquals(1, TopicList.visibleCount(catalogue, hidden))
    }

    @Test
    fun `unknown ids are ignored and leftovers dropped`() {
        assertEquals(emptySet<String>(), TopicList.setVisible(catalogue, emptySet(), "gibtsnicht", visible = false))
        assertEquals(setOf("ki"), TopicList.setVisible(catalogue, setOf("alt", "ki"), "politik", visible = true))
    }

    @Test
    fun `reset restores the catalogue order`() {
        assertTrue(TopicList.isDefaultOrder(Topics.DEFAULT_ORDER))
        assertTrue(TopicList.isDefaultOrder(emptyList()))
        assertFalse(TopicList.isDefaultOrder(Topics.DEFAULT_ORDER.reversed()))
        // A stored order that only lacks newly added catalogue topics still counts as default.
        assertTrue(TopicList.isDefaultOrder(Topics.DEFAULT_ORDER.dropLast(2)))
        assertFalse(TopicList.isDefaultOrder(listOf("politik"), catalogue))
    }

    @Test
    fun `reader scale snaps to 5 percent steps within 85 to 140`() {
        assertEquals(10, ReaderScale.SLIDER_STEPS)
        assertEquals(1f, ReaderScale.normalize(1.01f))
        assertEquals(1.15f, ReaderScale.normalize(1.13f))
        assertEquals(0.85f, ReaderScale.normalize(0.2f))
        assertEquals(1.4f, ReaderScale.normalize(3f))
        assertEquals(1f, ReaderScale.normalize(Float.NaN))
        assertTrue(ReaderScale.normalize(1.4f) in 0.85f..1.4f)
        assertEquals("100 %", ReaderScale.label(1f))
        assertEquals("115 %", ReaderScale.label(1.149f))
        assertEquals("85 %", ReaderScale.label(0.85f))
        assertEquals("140 %", ReaderScale.label(1.4f))
    }
}
