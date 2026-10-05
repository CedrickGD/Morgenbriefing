package com.cedrickgd.morgenbriefing.data.model

/**
 * The topic catalogue. Ids are the contract with the cloud routine (routine/PROMPT.md and
 * docs/brief-format.md use the same list). [seed] is the topic's own color (ARGB) for covers,
 * chips and section accents — independent of the app accent.
 */
data class Topic(val id: String, val title: String, val emoji: String, val seed: Long)

object Topics {
    val ALL: List<Topic> = listOf(
        Topic("ki", "KI & Tech", "🤖", 0xFF7B61FF),
        Topic("wirtschaft", "Wirtschaft & Märkte", "📈", 0xFF2E8B57),
        Topic("politik", "Politik", "🏛️", 0xFF2F6FDE),
        Topic("welt", "Welt", "🌍", 0xFF0F9D9A),
        Topic("region", "Leipzig & Region", "📍", 0xFFC6613F),
        Topic("sport", "Sport", "⚽", 0xFFE0A100),
        Topic("wissenschaft", "Wissenschaft & Raumfahrt", "🔭", 0xFF3949AB),
        Topic("gesundheit", "Gesundheit", "🩺", 0xFFD81B60),
        Topic("klima", "Klima & Umwelt", "🌱", 0xFF43A047),
        Topic("entertainment", "Games & Entertainment", "🎮", 0xFF8E24AA),
    )

    val DEFAULT_ORDER: List<String> = ALL.map { it.id }

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): Topic? = byId[id]

    /** Neutral fallback for ids the app does not know yet (newer routine). */
    fun forSection(section: NewsSection): Topic =
        byId[section.id] ?: Topic(section.id, section.title, section.emoji.ifBlank { "📰" }, 0xFF5F6B7A)

    /**
     * Applies the user's order and hidden set to a brief's sections. Unknown/new topics keep
     * their brief order and appear after the ordered ones; hidden ones are dropped.
     */
    fun arrange(sections: List<NewsSection>, order: List<String>, hidden: Set<String>): List<NewsSection> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return sections
            .filter { it.id !in hidden && it.items.isNotEmpty() }
            .withIndex()
            .sortedWith(compareBy({ rank[it.value.id] ?: (order.size + it.index) }, { it.index }))
            .map { it.value }
    }
}
