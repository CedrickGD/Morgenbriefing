package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.SourceRef

/**
 * Collects the web sources of one answer and ranks them for the chips under it: pages the
 * answer cites come first, then pages Claude actually opened (web fetch); plain search hits
 * are only shown when there is nothing better (then the first few). Deduplicated by URL.
 */
internal class SourceCollector(private val limit: Int = 6, private val searchOnlyLimit: Int = 4) {
    private val cited = LinkedHashMap<String, SourceRef>()
    private val fetched = LinkedHashMap<String, SourceRef>()
    private val searched = LinkedHashMap<String, SourceRef>()

    /** @return true when the ranked list may have changed. */
    @Synchronized
    fun addCited(url: String?, title: String?): Boolean = add(cited, url, title)

    @Synchronized
    fun addFetched(url: String?, title: String?): Boolean = add(fetched, url, title)

    @Synchronized
    fun addSearched(url: String?, title: String?): Boolean = add(searched, url, title)

    @Synchronized
    fun ranked(): List<SourceRef> {
        val primary = LinkedHashMap<String, SourceRef>()
        (cited.entries + fetched.entries).forEach { (key, ref) ->
            val existing = primary[key]
            if (existing == null || (existing.name == hostOf(existing.url) && ref.name != hostOf(ref.url))) primary[key] = ref
        }
        return if (primary.isNotEmpty()) primary.values.take(limit) else searched.values.take(searchOnlyLimit)
    }

    private fun add(into: MutableMap<String, SourceRef>, url: String?, title: String?): Boolean {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("https://", ignoreCase = true) && !clean.startsWith("http://", ignoreCase = true)) return false
        val key = normalize(clean)
        val name = title?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: hostOf(clean)
        val existing = into[key]
        if (existing != null && (existing.name != hostOf(existing.url) || name == hostOf(clean))) return false
        into[key] = SourceRef(name = name.take(MAX_TITLE), url = clean)
        return true
    }

    private fun normalize(url: String): String =
        url.substringBefore('#').trimEnd('/').lowercase().removePrefix("http://").removePrefix("https://").removePrefix("www.")

    private companion object {
        const val MAX_TITLE = 140
        val WHITESPACE = Regex("""\s+""")
    }
}
