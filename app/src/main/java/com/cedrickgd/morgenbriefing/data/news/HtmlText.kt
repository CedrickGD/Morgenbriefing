package com.cedrickgd.morgenbriefing.data.news

/**
 * Turns the HTML snippets feeds put into descriptions into plain text, and finds the picture
 * they embed. Pure Kotlin (no Android), unit-tested.
 */
object HtmlText {

    private val TAG = Regex("<[^>]+>")
    private val BLOCK_END = Regex("(?i)</(p|div|li|h[1-6]|br|tr)\\s*>|<br\\s*/?>")
    private val WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")
    private val LINE_BREAKS = Regex("\\s*\\n\\s*")
    private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});")
    private val NAMED_ENTITY = Regex("&([a-zA-Z][a-zA-Z0-9]{1,8});")
    private val IMG_SRC = Regex("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*([\"'])(.*?)\\1")

    /**
     * The "read more" link newsrooms append to a teaser (golem: `[<a>mehr</a>]`, t3n:
     * `<a>weiterlesen auf t3n.de</a>`), with whatever closing tags follow it. Only a link counts:
     * a sentence that merely ends in "weiter" or "mehr" is left alone.
     */
    private val TRAILING_LINK = Regex(
        "(?is)\\s*\\[?\\s*<a\\b[^>]*>\\s*(mehr\\s*\\.{0,3}|mehr lesen|weiter|weiterlesen(\\s+auf\\s+[^<]+)?|read more|more)\\s*</a>\\s*\\]?\\s*(</[a-z0-9]+\\s*>\\s*)*$",
    )

    /** The plain-text forms of the same thing: "[mehr]", "weiterlesen auf t3n.de", "mehr lesen". Never a bare word. */
    private val TRAILING_PLAIN = Regex(
        "(?i)\\s*(\\[\\s*(mehr|weiter|weiterlesen|more)\\s*\\.{0,3}\\s*\\]|weiterlesen\\s+auf\\s+\\S+|mehr lesen\\s*\\.{0,3}|read more\\s*\\.{0,3})\\s*$",
    )

    /** Hosts that only ever serve counting pixels (VG Wort, INFOnline/IVW, golem's cpx, WordPress stats). */
    private val TRACKER_HOST = Regex(
        "(?i)^https?://([^/]*\\.)?(vgwort\\.de|ioam\\.de|ivwbox\\.de|cpx\\.golem\\.de|pixel\\.wp\\.com|stats\\.wp\\.com|pixel\\.quantserve\\.com)(/|$)",
    )

    /** Pixel-like file names: `/pixel.gif`, `/cpx.php`, `/1x1.png`, `/blank.gif`, `/counter.php`. */
    private val TRACKER_PATH = Regex(
        "(?i)(/cpx\\.php|/pixel(\\.(gif|png|php))?([?#]|$)|/(1x1|blank|spacer|transparent|empty)\\.(gif|png)([?#]|$)|/count(er)?\\.(gif|php)([?#]|$))",
    )

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü",
        "szlig" to "ß", "euro" to "€", "hellip" to "…", "ndash" to "–", "mdash" to "—",
        "laquo" to "«", "raquo" to "»", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "shy" to "", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•", "sect" to "§", "para" to "¶",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "aacute" to "á",
        "acirc" to "â", "ccedil" to "ç", "ntilde" to "ñ", "oacute" to "ó", "ograve" to "ò",
        "ocirc" to "ô", "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "iacute" to "í",
        "igrave" to "ì", "icirc" to "î", "oslash" to "ø", "aring" to "å", "aelig" to "æ",
        "Eacute" to "É", "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "permil" to "‰", "prime" to "′",
        "thinsp" to "\u2009", "ensp" to "\u2002", "emsp" to "\u2003", "zwnj" to "", "zwj" to "",
    )

    /**
     * Plain text: tags removed (block ends become line breaks), entities decoded, whitespace
     * collapsed. With [dropReadMore] (teasers) a trailing "[mehr]" / "weiterlesen auf …" link is
     * dropped as well; headlines pass `false` so "Bahnstreik geht weiter" keeps its last word.
     */
    fun toPlain(html: String?, dropReadMore: Boolean = true): String {
        if (html.isNullOrBlank()) return ""
        var text = html
        // Some feeds escape their HTML once more ("&lt;a href=…"): decode first, then strip.
        if (!text.contains('<') && text.contains("&lt;")) text = decodeEntities(text)
        if (dropReadMore) text = TRAILING_LINK.replace(text, "")
        text = BLOCK_END.replace(text, "\n")
        text = TAG.replace(text, "")
        text = decodeEntities(text)
        text = text.replace('\u00A0', ' ')
        text = WHITESPACE.replace(text, " ")
        text = LINE_BREAKS.replace(text, "\n").trim()
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        if (dropReadMore) text = TRAILING_PLAIN.replace(text, "").trim()
        return text
    }

    /** The first `<img src>` of an HTML snippet (absolute http(s) only), else null. */
    fun firstImageSrc(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val candidates = IMG_SRC.findAll(html).map { decodeEntities(it.groupValues[2]).trim() }
        return candidates.firstOrNull { it.isUsableImageUrl() }
    }

    /** Decodes numeric and the common named entities; unknown ones are left as they are. */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var out = NUMERIC_ENTITY.replace(text) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) raw.substring(1).toIntOrNull(16) else raw.toIntOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) match.value else String(Character.toChars(code))
        }
        out = NAMED_ENTITY.replace(out) { match -> NAMED[match.groupValues[1]] ?: match.value }
        return out
    }

    /**
     * Tracking pixels, data URIs and relative paths are not pictures the app can show. The
     * decision looks at the host and the file name only, so an article about the Pixel phone
     * keeps its photo (…/pixel-10-test.jpg).
     */
    internal fun String.isUsableImageUrl(): Boolean {
        val lower = lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        if (TRACKER_HOST.containsMatchIn(lower) || TRACKER_PATH.containsMatchIn(lower)) return false
        val path = lower.substringBefore('?').substringBefore('#')
        if (path.endsWith(".svg") || path.endsWith(".gif") || path.endsWith(".ico")) return false
        return true
    }
}
