package com.cedrickgd.morgenbriefing.ui.components

import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.PaintCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Emoji that are newer than the device's emoji font render as empty boxes ("tofu") on older
 * Android versions — e.g. 🩺 (Emoji 12, Android 10) or 🧥 on some Android 7 builds. Every emoji
 * the app shows goes through [display], which swaps a missing one for a close relative the
 * font does have. Checked once per emoji and cached.
 */
object EmojiSupport {
    private const val TAG = "EmojiSupport"

    /**
     * Close relatives, best first. Only used when the original has no glyph. Clothing chips
     * that appear side by side must stay apart: on Android 7, whose emoji font lacks 🧥, 🧶, 🩳,
     * 🧣 and 🧤, a jacket shows 🥋, a pullover 👚, shorts 👖, a scarf ❄️, gloves ✋ and the
     * T-shirt stays 👕.
     */
    private val fallbacks: Map<String, List<String>> = mapOf(
        // Coat and jacket (Emoji 5, Android 8): the grey wrap jacket of 🥋 (Unicode 9, in the
        // Android 7 font) still reads as a jacket; 👔 for fonts older than that. A scarf would be
        // wrong for "leichte Jacke" and "Regenjacke", a T-shirt is the pullover's last resort.
        "🧥" to listOf("🥋", "👔"),
        // Emoji 11–12 (Android 9–10): knitting (the pullover), lotion, ice, puzzle piece.
        "🧶" to listOf("👚", "👕"),
        "🧴" to listOf("☀️"),
        "🧊" to listOf("❄️", "⚠️"),
        "🧩" to listOf("⚠️"),
        "🩺" to listOf("💊", "🏥", "❤️"),
        "🔭" to listOf("🌌", "🚀"),
        "🌡️" to listOf("☀️"),
        // Scarf and gloves (both Emoji 5, so a font has both or neither) appear side by side in
        // the cold: the scarf becomes a snowflake, the gloves a raised hand (Unicode 6, in the
        // Android 7 font) – never the same glyph, and never the T-shirt.
        "🧤" to listOf("✋", "❄️"),
        "🧣" to listOf("❄️", "👕"),
        "🩳" to listOf("👖", "👕"),
        "🥾" to listOf("👟"),
        "🫧" to listOf("💧"),
        "🪁" to listOf("🌬️"),
        "🌬️" to listOf("💨"),
        "🛰️" to listOf("🚀"),
        "🧪" to listOf("🔬"),
        "🦠" to listOf("🔬"),
    )

    private val paint by lazy { Paint() }
    private val resolved = ConcurrentHashMap<String, String>()

    /** [emoji] if the device can draw it, else the closest drawable relative (or [emoji] itself). */
    fun display(emoji: String): String {
        if (emoji.isBlank()) return emoji
        return resolved.getOrPut(emoji) { resolve(emoji) }
    }

    /**
     * [text] with every emoji the device cannot draw swapped for its relative, for text the
     * system draws itself (notifications), where the [Emoji] composable is not involved.
     */
    fun displayIn(text: String): String = swapIn(text, ::display)

    /** [displayIn] for a font that draws exactly what [canDraw] accepts. */
    internal fun displayIn(text: String, canDraw: (String) -> Boolean): String = swapIn(text) { pick(it, canDraw) }

    private fun swapIn(text: String, shown: (String) -> String): String = fallbacks.keys.fold(text) { acc, emoji ->
        if (acc.contains(emoji)) acc.replace(emoji, shown(emoji)) else acc
    }

    private fun resolve(emoji: String): String = pick(emoji, ::canDraw)

    /** [emoji] if [canDraw], else its first drawable relative, else [emoji] itself. */
    internal fun pick(emoji: String, canDraw: (String) -> Boolean): String {
        if (canDraw(emoji)) return emoji
        return fallbacks[emoji]?.firstOrNull(canDraw) ?: emoji
    }

    private fun canDraw(emoji: String): Boolean = try {
        PaintCompat.hasGlyph(paint, emoji)
    } catch (e: RuntimeException) {
        // Never let a font quirk break a screen: assume it renders.
        Log.w(TAG, "Glyph check failed for $emoji", e)
        true
    }
}
