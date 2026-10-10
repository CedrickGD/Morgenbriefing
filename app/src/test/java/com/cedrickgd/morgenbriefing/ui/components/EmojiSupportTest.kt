package com.cedrickgd.morgenbriefing.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSupportTest {

    /**
     * Clothing emoji missing from the Android 7.0 (API 24) system image's NotoColorEmoji.ttf
     * (read from its cmap). It has the Unicode 9 🥋 and the older 👕 👔 👚 👖 ❄️ ✋ 👟 ☀️.
     */
    private val missingOnAndroid7 = setOf("🧥", "🧣", "🧤", "🧶", "🩳", "🥾", "🧴", "🦺")
    private val android7: (String) -> Boolean = { it !in missingOnAndroid7 }
    private val everything: (String) -> Boolean = { true }

    @Test
    fun `jacket, pullover, t-shirt and shorts chips stay apart on android 7`() {
        val jacket = EmojiSupport.pick("🧥", android7)
        val pullover = EmojiSupport.pick("🧶", android7)
        val tShirt = EmojiSupport.pick("👕", android7)
        val shorts = EmojiSupport.pick("🩳", android7)
        assertEquals("🥋", jacket)
        assertEquals("👚", pullover)
        assertEquals("👕", tShirt)
        assertEquals("👖", shorts)
        assertEquals(4, setOf(jacket, pullover, tShirt, shorts).size)
        listOf(jacket, pullover, tShirt, shorts).forEach { assertTrue(it, android7(it)) }
    }

    @Test
    fun `a jacket never falls back to a t-shirt or a cold-only glyph`() {
        // Even a font without 🥋 keeps the jacket apart from the pullover's 👕 last resort.
        val noKarate: (String) -> Boolean = { it !in missingOnAndroid7 && it != "🥋" }
        assertEquals("👔", EmojiSupport.pick("🧥", noKarate))
        assertNotEquals(EmojiSupport.pick("🧶", noKarate), EmojiSupport.pick("🧥", noKarate))
    }

    @Test
    fun `scarf and gloves stay apart on android 7`() {
        // "Wintermantel, Schal, Handschuhe" side by side: a snowflake and a raised hand.
        assertEquals("❄️", EmojiSupport.pick("🧣", android7))
        assertEquals("✋", EmojiSupport.pick("🧤", android7))
    }

    @Test
    fun `every clothing fallback on android 7 is drawable and unique`() {
        val clothing = listOf("🧥", "🧣", "🧤", "🧶", "🩳", "🥾", "🧴", "🦺")
        val shown = clothing.associateWith { EmojiSupport.pick(it, android7) }
        shown.forEach { (emoji, fallback) ->
            assertNotEquals("$emoji has no fallback", emoji, fallback)
            assertTrue("$emoji → $fallback is not in the Android 7 font", android7(fallback))
            assertNotEquals("$emoji → $fallback looks like the T-shirt", "👕", fallback)
        }
        assertEquals("fallbacks repeat: $shown", clothing.size, shown.values.toSet().size)
    }

    @Test
    fun `notification text gets the same android 7 fallbacks as the chips`() {
        // The clothing line of the morning notification leads with the first advised item.
        assertEquals("🥋 Morgens Jacke, ab Mittag T-Shirt, kein Schirm.", EmojiSupport.displayIn("🧥 Morgens Jacke, ab Mittag T-Shirt, kein Schirm.", android7))
        assertEquals("👚 Dünner Pulli, Schirm.", EmojiSupport.displayIn("🧶 Dünner Pulli, Schirm.", android7))
        assertEquals("👕 T-Shirt reicht.", EmojiSupport.displayIn("👕 T-Shirt reicht.", android7))
        assertEquals("🧥 Jacke.", EmojiSupport.displayIn("🧥 Jacke.", everything))
    }

    @Test
    fun `drawable emoji are kept and unknown ones pass through`() {
        assertEquals("🧥", EmojiSupport.pick("🧥", everything))
        assertEquals("🧶", EmojiSupport.pick("🧶", everything))
        // No relative to offer: the original stays (rather than nothing).
        assertEquals("🦄", EmojiSupport.pick("🦄") { false })
    }
}
