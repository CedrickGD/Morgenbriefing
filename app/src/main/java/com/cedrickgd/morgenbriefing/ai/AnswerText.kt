package com.cedrickgd.morgenbriefing.ai

/**
 * Joins the text blocks of a whole answer (across continuations and output items) for every
 * provider: text that follows a tool call, a search or thinking starts a new paragraph; text
 * blocks that only split for citations are glued together as they are.
 */
internal class AnswerText {
    private val builder = StringBuilder()
    private var afterNonText = false

    val value: String get() = builder.toString()

    /** Called when a text block starts; returns a separator to emit first, if any. */
    fun startTextBlock(): String? {
        val separator = if (afterNonText && builder.isNotEmpty() && !builder.endsWith("\n")) "\n\n" else null
        afterNonText = false
        separator?.let(builder::append)
        return separator
    }

    fun nonTextBlock() {
        afterNonText = true
    }

    fun append(text: String) {
        builder.append(text)
    }
}
