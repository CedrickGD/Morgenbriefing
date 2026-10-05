package com.cedrickgd.morgenbriefing.ui.news

import androidx.compose.runtime.Immutable

/**
 * One block of an article body. The routine writes plain text: paragraphs separated by a blank
 * line, `## ` subheadings and `- ` bullet lines (see docs/brief-format.md).
 */
@Immutable
sealed interface BodyBlock {
    data class Paragraph(val text: String) : BodyBlock
    data class Subheading(val text: String) : BodyBlock
    data class Bullets(val items: List<String>) : BodyBlock
}

/** `## Titel`; a bare `##` is an empty heading (dropped), `#hashtag` is text. */
private val HEADING = Regex("""^#{1,6}(?:\s+(.*))?$""")

/** `- Punkt`; a bare `-` is an empty bullet (dropped), `-5 Grad` is text. */
private val BULLET = Regex("""^[-•*–](?:\s+(.*))?$""")

/** `---` / `***` / `___`: a horizontal rule, treated like a blank line. */
private val RULE = Regex("""^([-*_])(?:\s*\1){2,}$""")
private val MARKDOWN_LINK = Regex("""\[([^\]\n]+)]\([^)\s]*\)""")
private val HTML_TAG = Regex("""</?[A-Za-z][^>\n]*>""")
private val EMPHASIS = Regex("""\*\*|__""")
private val SPACES = Regex("""[ \t ]{2,}""")

/** `&amp;` comes last so `&amp;lt;` stays the literal text `&lt;`. */
private val ENTITIES = listOf(
    "&nbsp;" to " ",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&",
)

/**
 * Parses an article body into blocks. Pure and forgiving:
 * * blank lines separate blocks; single line breaks inside a paragraph are soft wraps,
 * * `#`…`######` + space starts a subheading (the format uses `## `),
 * * `- ` (also `•`, `*`, `–`) starts a bullet; an indented follow-up line continues it,
 * * stray markdown links, emphasis markers, HTML tags and common entities are reduced to
 *   plain text — the reader never renders markup or links from the body.
 */
internal fun parseArticleBody(body: String): List<BodyBlock> {
    val blocks = mutableListOf<BodyBlock>()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += BodyBlock.Paragraph(text)
        paragraph.clear()
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks += BodyBlock.Bullets(bullets.toList())
        bullets.clear()
    }

    val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    for (raw in lines) {
        val line = raw.trim()
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            line.isEmpty() || RULE.matches(line) -> {
                flushParagraph()
                flushBullets()
            }

            heading != null -> {
                flushParagraph()
                flushBullets()
                val text = plainText(heading.groupValues[1]).trimEnd('#', ' ')
                if (text.isNotEmpty()) blocks += BodyBlock.Subheading(text)
            }

            bullet != null -> {
                flushParagraph()
                val text = plainText(bullet.groupValues[1])
                if (text.isNotEmpty()) bullets += text
            }

            // An indented line right after a bullet continues that bullet.
            bullets.isNotEmpty() && raw.first().isWhitespace() -> {
                val text = plainText(line)
                if (text.isNotEmpty()) bullets[bullets.lastIndex] = bullets.last() + " " + text
            }

            else -> {
                flushBullets()
                val text = plainText(line)
                if (text.isNotEmpty()) {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(text)
                }
            }
        }
    }
    flushParagraph()
    flushBullets()
    return blocks
}

/** Reduces accidental markup to its text: `[Text](url)` → `Text`, `<b>` → ``, `**` → ``. */
internal fun plainText(text: String): String {
    var result = MARKDOWN_LINK.replace(text) { it.groupValues[1] }
    result = HTML_TAG.replace(result, "")
    result = EMPHASIS.replace(result, "")
    ENTITIES.forEach { (entity, replacement) -> result = result.replace(entity, replacement) }
    return SPACES.replace(result, " ").trim()
}
