package com.cedrickgd.morgenbriefing.data.model

import com.cedrickgd.morgenbriefing.data.news.Regions

/**
 * The built-in topic catalogue. Ids are stable (they are stored in the settings); the "Region"
 * topic takes its title from the chosen [Regions] preset. Custom topics live in
 * [AppSettings.customTopics] and are resolved with [resolve].
 */
object Topics {
    const val REGION_ID = "region"

    /** Neutral color for topics the app does not know (a custom topic that was deleted). */
    const val NEUTRAL_SEED = 0xFF5F6B7A

    val ALL: List<Topic> = listOf(
        Topic("ki", "KI & Tech", "🤖", 0xFF7B61FF),
        Topic("wirtschaft", "Wirtschaft & Märkte", "📈", 0xFF2E8B57),
        Topic("politik", "Politik", "🏛️", 0xFF2F6FDE),
        Topic("welt", "Welt", "🌍", 0xFF0F9D9A),
        Topic(REGION_ID, "Region", "📍", 0xFFC6613F),
        Topic("sport", "Sport", "⚽", 0xFFE0A100),
        Topic("wissenschaft", "Wissenschaft & Raumfahrt", "🔭", 0xFF3949AB),
        Topic("gesundheit", "Gesundheit", "🩺", 0xFFD81B60),
        Topic("klima", "Klima & Umwelt", "🌱", 0xFF43A047),
        Topic("entertainment", "Games & Entertainment", "🎮", 0xFF8E24AA),
    )

    val DEFAULT_ORDER: List<String> = ALL.map { it.id }

    private val byId = ALL.associateBy { it.id }

    /** A built-in topic by id (the region topic with its generic title). */
    fun byId(id: String): Topic? = byId[id]

    /** True for ids the user made up (Einstellungen → Themen → "Eigenes Thema"). */
    fun isCustomId(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    /** The id of a new custom topic. */
    fun newCustomId(): String = CUSTOM_PREFIX + java.util.UUID.randomUUID().toString().take(8)

    /**
     * Every topic the settings know, in the user's order: built-in ones (the region topic titled
     * after the chosen preset) and the user's own. Hidden topics are included; filter with
     * [AppSettings.hiddenTopics]. Ids in the stored order that no longer exist are dropped.
     */
    fun catalogue(settings: AppSettings): List<Topic> {
        val custom = settings.customTopics.associateBy { it.id }
        val known = LinkedHashMap<String, Topic>()
        ALL.forEach { known[it.id] = if (it.id == REGION_ID) it.copy(title = Regions.byKey(settings.region).name) else it }
        custom.values.forEach { known[it.id] = it.copy(custom = true) }
        val ordered = settings.topicOrder.mapNotNull { known.remove(it) }
        return ordered + known.values
    }

    /** The topics the feed shows: [catalogue] without the hidden ones. */
    fun visible(settings: AppSettings): List<Topic> = catalogue(settings).filter { it.id !in settings.hiddenTopics }

    /** Resolves [id] against the built-in catalogue and the user's topics; null when unknown. */
    fun resolve(id: String, settings: AppSettings): Topic? = catalogue(settings).firstOrNull { it.id == id }

    /** Like [resolve], but never null: unknown ids get a neutral stand-in. */
    fun resolveOrNeutral(id: String, settings: AppSettings): Topic =
        resolve(id, settings) ?: Topic(id, id.replaceFirstChar { it.uppercase() }, "📰", NEUTRAL_SEED, custom = isCustomId(id))

    private const val CUSTOM_PREFIX = "custom:"
}
