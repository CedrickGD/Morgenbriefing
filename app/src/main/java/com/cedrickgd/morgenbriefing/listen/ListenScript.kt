package com.cedrickgd.morgenbriefing.listen

import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.ui.news.BodyBlock
import com.cedrickgd.morgenbriefing.ui.news.parseArticleBody
import com.cedrickgd.morgenbriefing.ui.news.plainText
import com.cedrickgd.morgenbriefing.ui.news.sourceLabel
import com.cedrickgd.morgenbriefing.ui.news.sourcesOf

/**
 * One utterance handed to TextToSpeech, followed by [pauseAfterMs] of silence (a natural break
 * after a headline, a paragraph or a list item).
 */
internal data class SpeechPart(val text: String, val pauseAfterMs: Int = 0)

/**
 * What "Zuhören" reads for one article, already cut into utterances: headline, lead, the body
 * (subheadings and list items become their own sentences with pauses instead of `## ` / `- `
 * markers) and finally the sources. Pure — built and unit-tested without Android.
 */
internal class ListenScript(val parts: List<SpeechPart>) {

    /** Estimated speaking time of each part (text + the pause after it), in seconds. */
    private val weights: DoubleArray = DoubleArray(parts.size) { i ->
        parts[i].text.length / CHARS_PER_SECOND + parts[i].pauseAfterMs / 1000.0
    }

    private val prefix: DoubleArray = DoubleArray(parts.size + 1).also { sums ->
        for (i in parts.indices) sums[i + 1] = sums[i] + weights[i]
    }

    private val total: Double get() = prefix[parts.size]

    val isEmpty: Boolean get() = parts.isEmpty()
    val lastIndex: Int get() = parts.lastIndex

    /** Rough length of the whole reading, for "noch etwa 3 Min.". */
    val estimatedSeconds: Int get() = total.toInt()

    /**
     * Progress 0..1 over the whole article while part [index] is being spoken and the voice
     * has reached character [charOffset] of it (from `onRangeStart`, 0 when unknown). Weighted
     * by speaking time, so a long paragraph moves the bar further than a short headline.
     */
    fun progressAt(index: Int, charOffset: Int = 0): Float {
        if (parts.isEmpty() || total <= 0.0) return 0f
        if (index < 0) return 0f
        if (index > lastIndex) return 1f
        val length = parts[index].text.length
        val within = if (length == 0) 0.0 else charOffset.coerceIn(0, length).toDouble() / length
        val spoken = prefix[index] + within * (length / CHARS_PER_SECOND)
        return (spoken / total).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** German TTS at normal speed, pauses included in the parts' own weights. */
        const val CHARS_PER_SECOND = 15.0

        /** Utterances are packed up to this length: short enough to resume close to where it stopped. */
        const val SOFT_LIMIT = 300

        const val PAUSE_HEADLINE_MS = 700
        const val PAUSE_LEAD_MS = 600
        const val PAUSE_PARAGRAPH_MS = 420
        const val PAUSE_SECTION_MS = 650
        const val PAUSE_SUBHEADING_MS = 380
        const val PAUSE_BULLET_MS = 260

        /**
         * Builds the script for [item]. [maxLength] is the engine's hard limit per utterance
         * (`TextToSpeech.getMaxSpeechInputLength()`); every part stays below it.
         */
        fun from(item: NewsItem, maxLength: Int = DEFAULT_MAX_LENGTH): ListenScript {
            val builder = Builder(maxLength)
            builder.add(item.headline, PAUSE_HEADLINE_MS)
            builder.add(item.summary, PAUSE_LEAD_MS)
            parseArticleBody(item.body).forEach { block ->
                when (block) {
                    is BodyBlock.Paragraph -> builder.add(block.text, PAUSE_PARAGRAPH_MS)
                    is BodyBlock.Subheading -> {
                        builder.breathe(PAUSE_SECTION_MS)
                        builder.add(asSentence(block.text), PAUSE_SUBHEADING_MS)
                    }
                    is BodyBlock.Bullets -> {
                        block.items.forEachIndexed { i, text ->
                            val pause = if (i == block.items.lastIndex) PAUSE_PARAGRAPH_MS else PAUSE_BULLET_MS
                            builder.add(asSentence(text), pause)
                        }
                    }
                }
            }
            sourcesSentence(item)?.let { sentence ->
                builder.breathe(PAUSE_SECTION_MS)
                builder.add(sentence, 0)
            }
            return builder.build()
        }

        /** 4000 on every engine we know; used when the platform value is unavailable (tests). */
        const val DEFAULT_MAX_LENGTH = 4000

        /** "Quelle: Handelsblatt." / "Quellen: A, B und C." — null when no source is known. */
        internal fun sourcesSentence(item: NewsItem): String? {
            val names = sourcesOf(item)
                .map { cleanForSpeech(sourceLabel(it)) }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            if (names.isEmpty()) return null
            val list = if (names.size == 1) names.first() else names.dropLast(1).joinToString(", ") + " und " + names.last()
            return (if (names.size == 1) "Quelle: " else "Quellen: ") + list + "."
        }
    }

    private class Builder(maxLength: Int) {
        private val hard = (maxLength - 1).coerceAtLeast(MIN_HARD_LIMIT)
        private val soft = SOFT_LIMIT.coerceAtMost(hard)
        private val parts = mutableListOf<SpeechPart>()

        fun add(raw: String, pauseAfterMs: Int) {
            val text = cleanForSpeech(raw)
            if (text.isEmpty()) return
            val chunks = chunkText(text, soft, hard)
            chunks.forEachIndexed { i, chunk ->
                parts += SpeechPart(chunk, if (i == chunks.lastIndex) pauseAfterMs else 0)
            }
        }

        /** Makes sure the part before the next one ends with at least [ms] of silence. */
        fun breathe(ms: Int) {
            val last = parts.lastOrNull() ?: return
            if (last.pauseAfterMs < ms) parts[parts.lastIndex] = last.copy(pauseAfterMs = ms)
        }

        fun build(): ListenScript {
            // Nothing follows the last part: no trailing silence before "finished".
            if (parts.isNotEmpty()) parts[parts.lastIndex] = parts.last().copy(pauseAfterMs = 0)
            return ListenScript(parts.toList())
        }
    }
}

private const val MIN_HARD_LIMIT = 40

private val WHITESPACE = Regex("""\s+""")

/** Characters that end a sentence for the speech splitter. */
private const val SENTENCE_END = ".!?…"

/** Closing quotes and brackets that may follow a sentence end ("… gesagt.“ Danach"). */
private const val CLOSERS = "\"'“”„«»‘’)]"

/** Where a too long sentence may be cut, best first. */
private val CLAUSE_BREAKS = charArrayOf(';', ':', ',', '–', '—')

/**
 * German abbreviations whose dot does not end a sentence ("z. B.", "Dr.", "bzw."); lowercase,
 * without the dot. Single letters and numbers ("am 3. Oktober") are handled separately.
 */
private val ABBREVIATIONS = setOf(
    "abs", "abt", "allg", "bspw", "bzgl", "bzw", "ca", "chr", "co", "corp", "dr", "etc", "evtl",
    "fr", "geb", "gegr", "gem", "gest", "ggf", "hr", "inc", "inkl", "insb", "jh", "jhd", "jr",
    "kap", "lt", "ltd", "max", "mind", "min", "mio", "mr", "mrd", "mrs", "ms", "nr", "prof", "sog",
    "sr", "st", "std", "str", "tel", "usw", "vgl", "vs", "zb", "zt", "ziff",
)

/**
 * Text as it should be spoken: markup reduced to plain text (shared with the reader's body
 * parser), line breaks and runs of spaces collapsed.
 */
internal fun cleanForSpeech(raw: String): String = WHITESPACE.replace(plainText(raw.replace('\n', ' ')), " ").trim()

/** A list item or subheading read as its own sentence: ends with punctuation for a natural cadence. */
internal fun asSentence(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return trimmed
    val last = trimmed.trimEnd { it in CLOSERS }.lastOrNull() ?: return trimmed
    return if (last in SENTENCE_END || last == ':' || last == ';') trimmed else "$trimmed."
}

/**
 * Splits [text] into sentences. Conservative: a dot after a number ("3. Oktober"), a single
 * letter ("z. B.") or a known abbreviation does not end a sentence, and a sentence only ends
 * when the next word starts with an uppercase letter, a digit or an opening quote.
 */
internal fun splitSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c in SENTENCE_END) {
            // Swallow runs like "?!" or "..." and closing quotes/brackets.
            var end = i + 1
            while (end < text.length && (text[end] in SENTENCE_END || text[end] in CLOSERS)) end++
            var next = end
            while (next < text.length && text[next].isWhitespace()) next++
            val hasGap = next > end
            if (hasGap && next < text.length && startsSentence(text[next]) && !(c == '.' && isNonFinalDot(text, i))) {
                text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
                start = next
                i = next
                continue
            }
            i = end
            continue
        }
        i++
    }
    text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
    return sentences
}

private fun startsSentence(c: Char): Boolean = c.isUpperCase() || c.isDigit() || c in "\"„“«»'(‚"

/** True when the dot at [dotIndex] belongs to a number, an initial or an abbreviation. */
private fun isNonFinalDot(text: String, dotIndex: Int): Boolean {
    var start = dotIndex
    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
    val word = text.substring(start, dotIndex)
    if (word.isEmpty()) return false
    if (word.all { it.isDigit() }) return true
    if (word.length == 1 && word[0].isLetter()) return true
    return word.lowercase() in ABBREVIATIONS
}

/**
 * Cuts [text] into utterances: whole sentences packed up to [soft] characters; a single
 * sentence longer than [hard] is cut at a clause break or a space. No piece exceeds [hard].
 */
internal fun chunkText(text: String, soft: Int, hard: Int): List<String> {
    require(hard >= 1 && soft >= 1)
    val result = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) result += current.toString()
        current.clear()
    }
    splitSentences(text).flatMap { if (it.length > hard) hardSplit(it, hard) else listOf(it) }.forEach { piece ->
        when {
            current.isEmpty() -> current.append(piece)
            current.length + 1 + piece.length <= soft -> current.append(' ').append(piece)
            else -> {
                flush()
                current.append(piece)
            }
        }
    }
    flush()
    return result
}

/** Cuts one over-long sentence below [hard]: prefers clause breaks in the second half, then spaces. */
private fun hardSplit(sentence: String, hard: Int): List<String> {
    val pieces = mutableListOf<String>()
    var rest = sentence.trim()
    while (rest.length > hard) {
        val window = rest.substring(0, hard)
        val clause = window.lastIndexOfAny(CLAUSE_BREAKS).takeIf { it >= hard / 2 }
        val cut = when {
            clause != null -> clause + 1
            else -> window.lastIndexOf(' ').takeIf { it > 0 } ?: hard
        }
        rest.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) pieces += rest
    return pieces
}

/** Utterance ids carry the playback session, so callbacks of a replaced reading are ignored. */
internal object UtteranceIds {
    private const val SPEECH = "u"
    private const val SILENCE = "p"

    fun speech(session: Int, index: Int): String = "$SPEECH:$session:$index"

    fun silence(session: Int, index: Int): String = "$SILENCE:$session:$index"

    /** (session, index, isSilence) or null for foreign ids. */
    fun parse(id: String?): Triple<Int, Int, Boolean>? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 3) return null
        val silence = when (parts[0]) {
            SPEECH -> false
            SILENCE -> true
            else -> return null
        }
        val session = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return Triple(session, index, silence)
    }
}
