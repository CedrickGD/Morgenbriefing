package com.cedrickgd.morgenbriefing.ai.provider

import com.cedrickgd.morgenbriefing.data.model.AiProviderId

/** The four request/stream families behind the providers (docs/AI-PROVIDERS.md D2). */
enum class EngineKind {
    /** Anthropic Java SDK, Android 8.0+ only. */
    CLAUDE_SDK,

    /** OpenAI Responses API and its dialects (OpenAI, xAI, Perplexity Agent API). */
    RESPONSES,

    /** Gemini `streamGenerateContent`. */
    GEMINI,

    /** OpenAI-compatible Chat Completions (OpenRouter, Mistral, DeepSeek, own server). */
    CHAT_COMPLETIONS,
}

/** Whether "⚡ Live" can run with a provider. */
enum class LiveSupport {
    YES,

    /** Live needs web search, and the provider has none in the app. */
    NO_WEB_SEARCH,

    /** Search works, but its terms forbid storing the results as articles (Gemini, D4). */
    NOT_ALLOWED,
}

/** Which tools the Live system prompt may name ([com.cedrickgd.morgenbriefing.ai.AiPrompts.liveSystem]). */
enum class LiveToolNote {
    /** Claude: `web_search` and `web_fetch` (the v1.2 prompt, byte for byte). */
    SEARCH_AND_FETCH,

    /** Every other provider: one web search tool, no separate page fetch. */
    SEARCH_ONLY,
}

/**
 * One curated model of a provider. [requiresBilling]: the model has no free tier (Gemini Pro),
 * so it cannot be picked with a free Gemini key.
 */
data class ModelOption(
    val id: String,
    val label: String,
    val subtitle: String,
    val badge: String? = null,
    val requiresBilling: Boolean = false,
)

/** Everything the app knows about one provider; all strings German, user-facing. */
data class ProviderSpec(
    val id: AiProviderId,
    /** Titles and pickers, e.g. "Google Gemini". */
    val name: String,
    /** Inside sentences, e.g. "Gemini ist überlastet". */
    val brand: String,
    /** One line in the provider picker. */
    val tagline: String,
    val engine: EngineKind,
    /** API base URL; null = the SDK default (Anthropic) or the user's server (custom). */
    val baseUrl: String?,
    /** The provider can search the web in the chat (Gemini only with billing, see ProviderCatalog.chatWebSearch). */
    val chatWebSearch: Boolean,
    val live: LiveSupport,
    val liveToolNote: LiveToolNote,
    /** Needs the Anthropic SDK and with it Android 8.0+. */
    val needsSdk: Boolean,
    /** Where keys are created; null for the own server. */
    val consoleUrl: String?,
    /** Pill text that opens [consoleUrl], e.g. "platform.openai.com öffnen". */
    val consoleLabel: String?,
    /** "So bekommst du den Key", one step per entry. */
    val keySteps: List<String>,
    /** Tip under the steps, or null. */
    val keyTip: String?,
    val keyPlaceholder: String,
    /** Soft format check of a pasted key; empty = only the whitespace check. */
    val keyPrefixes: List<String>,
    /** Replaces the generic "key refused" sentence (Perplexity: also an empty balance). */
    val invalidKeyText: String?,
    /** Curated models; the one with [defaultModel] carries the badge "Standard". */
    val models: List<ModelOption>,
    /** "" for the own server (its models come from the server). */
    val defaultModel: String,
) {
    /**
     * [name] after a preposition ("mit OpenAI", "bei Google Gemini"); the own server reads "mit
     * deinem eigenen Server", not "mit Eigener Server".
     */
    val dativeName: String
        get() = if (id == AiProviderId.CUSTOM) "deinem eigenen Server" else name

    /** Why "⚡ Live" is not available with this provider; null when it is. One sentence. */
    val liveUnavailableText: String?
        get() = when (live) {
            LiveSupport.YES -> null
            LiveSupport.NO_WEB_SEARCH -> "Live braucht Websuche – mit $dativeName nicht verfügbar."
            LiveSupport.NOT_ALLOWED ->
                "Live ist mit $dativeName nicht verfügbar: Googles Bedingungen für die Websuche erlauben keine gespeicherten Artikel."
        }

    /** The curated entry for [modelId], or null for a free-text model. */
    fun model(modelId: String?): ModelOption? = models.firstOrNull { it.id == modelId }

    override fun toString(): String = "ProviderSpec(${id.key})"
}
