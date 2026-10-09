package com.cedrickgd.morgenbriefing.ai.openai

/**
 * Removes Perplexity's inline citation markers — `[1]` (fast preset) and `[web:1]` (OC §6.5) —
 * from streamed text; the sources are shown as chips instead. A `[` whose marker may still be
 * completed by the next chunk is held back (at most [MAX_HOLD] characters) until it is clear;
 * [flush] releases what is left at the end of the stream.
 */
internal class CitationMarkerStripper {
    private val pending = StringBuilder()

    /** Text of [delta] that can be shown now (may be empty). */
    fun feed(delta: String): String {
        pending.append(delta)
        val out = StringBuilder()
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            if (c != '[') {
                out.append(c)
                i++
                continue
            }
            val match = markerAt(pending, i)
            when {
                match > 0 -> i += match
                match == PARTIAL && pending.length - i <= MAX_HOLD -> break
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        pending.delete(0, i)
        return out.toString()
    }

    /** Whatever is still held back (an unfinished `[…` at the end of the answer is kept as text). */
    fun flush(): String = pending.toString().also { pending.setLength(0) }

    /**
     * The length of the marker starting at [start] (a `[`), [PARTIAL] if [s] ends before it is
     * decided, or [NONE].
     */
    private fun markerAt(s: CharSequence, start: Int): Int {
        var i = start + 1
        var k = 0
        while (i < s.length && k < WEB.length && s[i] == WEB[k]) {
            i++
            k++
        }
        if (i == s.length) return PARTIAL
        if (k in 1 until WEB.length) return NONE
        val digits = i
        while (i < s.length && s[i].isDigit()) i++
        if (i == s.length) return PARTIAL
        if (i == digits || s[i] != ']') return NONE
        return i - start + 1
    }

    private companion object {
        const val WEB = "web:"
        const val PARTIAL = 0
        const val NONE = -1

        /** "[web:" plus up to six digits and "]". */
        const val MAX_HOLD = 12
    }
}
