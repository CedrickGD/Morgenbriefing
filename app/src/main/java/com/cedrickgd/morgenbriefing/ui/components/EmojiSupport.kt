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

    /** Close relatives, best first. Only used when the original has no glyph. */
    private val fallbacks: Map<String, List<String>> = mapOf(
        "🧥" to listOf("🧣", "👕"),
        // Emoji 11–12 (Android 9–10): knitting, lotion, ice, puzzle piece.
        "🧶" to listOf("👕"),
        "🧴" to listOf("☀️"),
        "🧊" to listOf("❄️", "⚠️"),
        "🧩" to listOf("⚠️"),
        "🩺" to listOf("💊", "🏥", "❤️"),
        "🔭" to listOf("🌌", "🚀"),
        "🌡️" to listOf("☀️"),
        // Scarf and gloves only come up in the cold: a snowflake beats a T-shirt (Android 7).
        "🧤" to listOf("🧣", "❄️"),
        "🧣" to listOf("❄️", "👕"),
        "🩳" to listOf("👕"),
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
    fun displayIn(text: String): String = fallbacks.keys.fold(text) { acc, emoji ->
        if (acc.contains(emoji)) acc.replace(emoji, display(emoji)) else acc
    }

    private fun resolve(emoji: String): String {
        if (canDraw(emoji)) return emoji
        return fallbacks[emoji]?.firstOrNull(::canDraw) ?: emoji
    }

    private fun canDraw(emoji: String): Boolean = try {
        PaintCompat.hasGlyph(paint, emoji)
    } catch (e: RuntimeException) {
        // Never let a font quirk break a screen: assume it renders.
        Log.w(TAG, "Glyph check failed for $emoji", e)
        true
    }
}
