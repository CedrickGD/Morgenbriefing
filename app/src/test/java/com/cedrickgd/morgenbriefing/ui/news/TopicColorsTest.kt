package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.ui.graphics.Color
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.ui.theme.ColorMath
import com.cedrickgd.morgenbriefing.ui.theme.MIN_TEXT_CONTRAST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicColorsTest {

    private val seeds = Topics.ALL.map { it.seed } + 0xFF5F6B7A // + the neutral fallback

    @Test
    fun `chip and button text is readable on its container in light and dark`() {
        for (seed in seeds) {
            for (dark in listOf(false, true)) {
                val colors = topicColors(seed, dark)
                val ratio = ColorMath.contrastRatio(colors.onContainer, colors.container)
                assertTrue("seed ${seed.toString(16)} dark=$dark: $ratio", ratio >= MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun `white headlines stay readable on the darker end of every cover`() {
        for (seed in seeds) {
            for (dark in listOf(false, true)) {
                val end = topicColors(seed, dark).coverEnd
                // The scrim darkens further; the cover itself must already carry large white text (3:1).
                val ratio = ColorMath.contrastRatio(Color.White, end)
                assertTrue("seed ${seed.toString(16)} dark=$dark: $ratio", ratio >= 3.0)
            }
        }
    }

    @Test
    fun `dark covers are darker than light ones`() {
        for (seed in seeds) {
            val light = ColorMath.relativeLuminance(topicColors(seed, dark = false).coverStart)
            val dark = ColorMath.relativeLuminance(topicColors(seed, dark = true).coverStart)
            assertTrue(dark < light)
        }
    }

    @Test
    fun `cover spec is stable per key and varies between keys`() {
        assertEquals(coverSpec("ki-rechenzentren"), coverSpec("ki-rechenzentren"))
        assertNotEquals(coverSpec("ki-rechenzentren"), coverSpec("ki-rechenzentren-2"))

        val specs = (1..60).map { coverSpec("artikel-$it") }
        assertEquals(CoverVariant.entries.toSet(), specs.map { it.variant }.toSet())
        specs.forEach { spec ->
            assertTrue(spec.angle in 0f..360f)
            assertTrue(spec.glowX in 0.15f..0.85f)
            assertTrue(spec.glowY in 0.1f..0.6f)
            assertTrue(spec.emojiTilt in -12f..12f)
        }
    }

    @Test
    fun `the hash does not depend on the platform string hash`() {
        // FNV-1a reference values.
        assertEquals(0x811C9DC5.toInt(), stableHash(""))
        assertEquals(0xE40C292C.toInt(), stableHash("a"))
    }

    @Test
    fun `random numbers stay in range even for a zero seed`() {
        val random = CoverRandom(0)
        repeat(1_000) {
            val value = random.next()
            assertTrue(value >= 0f && value < 1f)
            assertTrue(random.nextInt(4) in 0..3)
        }
    }
}
