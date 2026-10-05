package com.cedrickgd.morgenbriefing.ui.chat

import androidx.compose.runtime.Immutable

/**
 * The light markdown Claude is asked to write (docs/AI.md: paragraphs, **fett**, "- " lists):
 * parsed into blocks and inline spans. Forgiving by design — it runs on every streamed delta,
 * so half-written markup (an unclosed `**`) simply renders as emphasis up to the end, and
 * anything heavier (links, code, headings) is reduced to readable text.
 */
@Immutable
internal sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val spans: List<MdSpan>) : MdBlock
    data class Bullets(val items: List<List<MdSpan>>) : MdBlock
    data class Numbered(val items: List<Pair<String, List<MdSpan>>>) : MdBlock
}

@Immutable
internal data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false)

private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
private val BULLET = Regex("""^[-*•–]\s+(.*)$""")
private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
private val RULE = Regex("""^([-*_])(?:\s*\1){2,}$""")
private val LINK = Regex("""\[([^\]\n]+)]\((?:[^)\s]*)\)""")
private val CODE_TICKS = Regex("""`+""")

/** Splits [text] into blocks: blank lines separate paragraphs; list lines group into lists. */
internal fun parseChatMarkdown(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    val bullets = ArrayList<List<MdSpan>>()
    val numbered = ArrayList<Pair<String, List<MdSpan>>>()

    fun flushParagraph() {
        val content = paragraph.toString().trim()
        if (content.isNotEmpty()) blocks += MdBlock.Paragraph(parseInline(content))
        paragraph.clear()
    }

    fun flushLists() {
        if (bullets.isNotEmpty()) blocks += MdBlock.Bullets(bullets.toList())
        if (numbered.isNotEmpty()) blocks += MdBlock.Numbered(numbered.toList())
        bullets.clear()
        numbered.clear()
    }

    for (raw in text.replace("\r\n", "\n").split('\n')) {
        val line = raw.trim()
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val number = NUMBERED.matchEntire(line)
        when {
            line.isEmpty() || RULE.matches(line) -> {
                flushParagraph()
                flushLists()
            }
            heading != null -> {
                flushParagraph()
                flushLists()
                val content = heading.groupValues[1].trim().trimEnd('#').trim()
                if (content.isNotEmpty()) blocks += MdBlock.Heading(parseInline(content))
            }
            bullet != null && !line.startsWith("**") -> {
                flushParagraph()
                if (numbered.isNotEmpty()) flushLists()
                bullets += parseInline(bullet.groupValues[1].trim())
            }
            number != null -> {
                flushParagraph()
                if (bullets.isNotEmpty()) flushLists()
                numbered += number.groupValues[1] to parseInline(number.groupValues[2].trim())
            }
            bullets.isNotEmpty() && raw.startsWith(" ") -> {
                // An indented continuation of the last bullet.
                bullets[bullets.lastIndex] = bullets.last() + MdSpan(" ") + parseInline(line)
            }
            else -> {
                flushLists()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    flushLists()
    return blocks
}

/**
 * Inline emphasis: `**bold**` (also `__bold__`) and `*italic*`. Links keep their label, code
 * ticks are dropped. An unclosed marker at the end keeps its style to the end (streaming).
 */
internal fun parseInline(text: String): List<MdSpan> {
    val clean = LINK.replace(text) { it.groupValues[1] }.replace(CODE_TICKS, "")
    val spans = ArrayList<MdSpan>()
    val current = StringBuilder()
    var bold = false
    var italic = false

    fun flush() {
        if (current.isNotEmpty()) {
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + current)
            } else {
                spans += MdSpan(current.toString(), bold, italic)
            }
            current.clear()
        }
    }

    var i = 0
    while (i < clean.length) {
        val c = clean[i]
        val next = clean.getOrNull(i + 1)
        when {
            (c == '*' && next == '*') || (c == '_' && next == '_') -> {
                flush()
                bold = !bold
                i += 2
            }
            c == '*' && isItalicMarker(clean, i, italic) -> {
                flush()
                italic = !italic
                i += 1
            }
            else -> {
                current.append(c)
                i += 1
            }
        }
    }
    flush()
    return spans
}

/** A single `*` opens emphasis before a word and closes it after one; "5 * 3" stays text. */
private fun isItalicMarker(text: String, index: Int, open: Boolean): Boolean {
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + 1)
    return if (!open) {
        after != null && !after.isWhitespace() && (before == null || before.isWhitespace() || before in "([„\"")
    } else {
        before != null && !before.isWhitespace()
    }
}

/** Plain text of a message (markup removed) for copying and sharing. */
internal fun chatPlainText(text: String): String = parseChatMarkdown(text).joinToString("\n\n") { block ->
    when (block) {
        is MdBlock.Paragraph -> block.spans.joinToString("") { it.text }
        is MdBlock.Heading -> block.spans.joinToString("") { it.text }
        is MdBlock.Bullets -> block.items.joinToString("\n") { item -> "• " + item.joinToString("") { it.text } }
        is MdBlock.Numbered -> block.items.joinToString("\n") { (n, item) -> "$n. " + item.joinToString("") { it.text } }
    }
}
