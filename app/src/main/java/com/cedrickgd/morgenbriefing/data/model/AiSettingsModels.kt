package com.cedrickgd.morgenbriefing.data.model

/*
 * The AI part of the settings (docs/AI-PROVIDERS.md §3): which provider answers the chat and
 * runs "⚡ Live", and per provider whether a key is stored, its state and the chosen model.
 * Plain data without any ai.* dependency; the per-provider facts (names, models, prices) live
 * in ai/provider/ProviderCatalog.kt.
 */

/** The AI providers the app can talk to. [key] is what DataStore stores (`ai_<key>_…`). */
enum class AiProviderId(val key: String) {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GEMINI("gemini"),
    XAI("xai"),
    PERPLEXITY("perplexity"),
    OPENROUTER("openrouter"),
    MISTRAL("mistral"),
    DEEPSEEK("deepseek"),
    CUSTOM("custom"),
    ;

    /** Only the custom server works without a key. */
    val keyOptional: Boolean get() = this == CUSTOM

    companion object {
        fun fromKey(key: String?): AiProviderId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The "Tarif" of a Gemini key. A key of a project without billing ([FREE], the default for a
 * new Gemini setup) cannot use Google Search on Gemini 3.x and has no Pro models, but costs
 * nothing; [PAID] chats with Google Search and is priced like any other provider.
 */
enum class GeminiTier(val key: String) {
    FREE("free"),
    PAID("paid"),
    ;

    companion object {
        fun fromKey(key: String?): GeminiTier? = entries.firstOrNull { it.key == key }
    }
}

/** What is stored for one provider. The key itself never leaves SettingsRepository. */
data class ProviderSettings(
    /** A key is stored (encrypted). */
    val hasKey: Boolean = false,
    /**
     * The key was saved without a passed test ("Ohne Test speichern") and no request has gone
     * through with it yet. Only meaningful with [hasKey].
     */
    val keyUntested: Boolean = false,
    /**
     * The provider refused a chat or Live request or a key test with the stored key as not
     * authenticated, and no request has gone through with it since. Cleared by saving a key. Only
     * meaningful with [hasKey].
     */
    val keyRejected: Boolean = false,
    /** Model id (curated or free text); null = the catalog default. */
    val model: String? = null,
    /** [AiProviderId.CUSTOM] only: the normalized server address, e.g. `http://nas.fritz.box:11434/v1`. */
    val baseUrl: String? = null,
    /** [AiProviderId.ANTHROPIC] only: `anthropic-workspace-id` for keys not bound to one workspace. */
    val workspaceId: String? = null,
    /** [AiProviderId.GEMINI] only: the key's tariff. */
    val geminiTier: GeminiTier = GeminiTier.FREE,
    /**
     * Debug builds only (QA against a local mock server, e.g. `http://10.0.2.2:8080/v1`): replaces
     * the catalog base URL of this provider verbatim. Always null in release builds — the
     * repository never reads it there.
     */
    val debugBaseUrl: String? = null,
)

data class AiSettings(
    /** The provider of "Frag nach" (and of Live unless [liveProvider] is set). */
    val provider: AiProviderId = AiProviderId.ANTHROPIC,
    /** Live research provider; null = the chat provider. */
    val liveProvider: AiProviderId? = null,
    val providers: Map<AiProviderId, ProviderSettings> = emptyMap(),
) {
    fun of(id: AiProviderId): ProviderSettings = providers[id] ?: ProviderSettings()

    val effectiveLiveProvider: AiProviderId get() = liveProvider ?: provider

    /** Key present (or optional) and, for the custom server, a base URL. */
    fun isConfigured(id: AiProviderId): Boolean {
        val p = of(id)
        return (p.hasKey || id.keyOptional) && (id != AiProviderId.CUSTOM || !p.baseUrl.isNullOrBlank())
    }

    /** A Gemini key on the free tier: no Google Search, no Pro models, no costs. */
    fun isGeminiFree(id: AiProviderId): Boolean = id == AiProviderId.GEMINI && of(id).geminiTier == GeminiTier.FREE

    /**
     * Requests with [id] cost nothing: the own server, or Gemini on the free tier. They count as
     * 0 $, and the monthly limit never blocks them.
     */
    fun costsNothing(id: AiProviderId): Boolean = id == AiProviderId.CUSTOM || isGeminiFree(id)

    /** A copy with [settings] for [id]. */
    fun with(id: AiProviderId, settings: ProviderSettings): AiSettings = copy(providers = providers + (id to settings))

    /** A copy with [model] (null = catalog default) for [id]. */
    fun withModel(id: AiProviderId, model: String?): AiSettings =
        with(id, of(id).copy(model = model?.trim()?.take(MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() }))

    companion object {
        /** Model ids are stored trimmed and at most this long. */
        const val MAX_MODEL_LENGTH = 200
    }
}
