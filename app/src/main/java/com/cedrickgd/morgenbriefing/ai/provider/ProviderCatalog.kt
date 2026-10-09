package com.cedrickgd.morgenbriefing.ai.provider

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier

/**
 * The one place with per-provider facts (docs/AI-PROVIDERS.md §2): names, engines, endpoints,
 * capabilities, key guides and curated models. Prices live in [com.cedrickgd.morgenbriefing.ai.AiPricing].
 * Console URLs are link-checked before every release (T11).
 */
object ProviderCatalog {
    private const val STANDARD = "Standard"
    private const val COPY_KEY = "Key kopieren und hier einfügen"

    val ANTHROPIC = ProviderSpec(
        id = AiProviderId.ANTHROPIC,
        name = "Claude (Anthropic)",
        brand = "Claude",
        tagline = "Gründlich und quellenstark – liest Seiten selbst nach.",
        engine = EngineKind.CLAUDE_SDK,
        baseUrl = null,
        chatWebSearch = true,
        live = LiveSupport.YES,
        liveToolNote = LiveToolNote.SEARCH_AND_FETCH,
        needsSdk = true,
        consoleUrl = "https://platform.claude.com/settings/keys",
        consoleLabel = "platform.claude.com öffnen",
        keySteps = listOf(
            "platform.claude.com öffnen und anmelden",
            "Settings → API keys → „Create key“",
            "Beim Erstellen: „Linked account“ = du, „Workspace“ = „Default Workspace“ wählen",
            COPY_KEY,
        ),
        keyTip = "Tipp: Setz dir unter „Limits“ ein Ausgabenlimit – so wird nie mehr abgebucht, als du willst.",
        keyPlaceholder = "sk-ant-…",
        keyPrefixes = listOf("sk-ant-"),
        invalidKeyText = null,
        models = listOf(
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5", "Schnell und gründlich", badge = STANDARD),
            ModelOption("claude-opus-5-5", "Claude Opus 5.5", "Maximale Qualität, etwa doppelter Preis"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5", "Sehr günstig und flott"),
        ),
        defaultModel = "claude-sonnet-5-5",
    )

    val OPENAI = ProviderSpec(
        id = AiProviderId.OPENAI,
        name = "OpenAI",
        brand = "OpenAI",
        tagline = "GPT-Modelle mit Websuche – vielseitig und schnell.",
        engine = EngineKind.RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        chatWebSearch = true,
        live = LiveSupport.YES,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://platform.openai.com/api-keys",
        consoleLabel = "platform.openai.com öffnen",
        keySteps = listOf(
            "platform.openai.com öffnen und anmelden",
            "„API keys“ → „Create new secret key“",
            "Unter „Billing“ Guthaben aufladen (mind. 5 $)",
            COPY_KEY,
        ),
        keyTip = "Ohne Guthaben antwortet OpenAI mit „Guthaben aufgebraucht“. Ein Limit setzt du im Projekt unter „Limits“.",
        keyPlaceholder = "sk-…",
        keyPrefixes = listOf("sk-"),
        invalidKeyText = null,
        models = listOf(
            ModelOption("gpt-6.1-sol", "GPT-6.1 Sol", "Ausgewogen", badge = STANDARD),
            ModelOption("gpt-6-luna", "GPT-6 Luna", "Sehr günstig"),
            ModelOption("gpt-6-astra", "GPT-6 Astra", "Spitzenmodell, deutlich teurer"),
        ),
        defaultModel = "gpt-6.1-sol",
    )

    /**
     * Gemini: chat only (D4). A key works on the free tier (no Google Search, no Pro model, no
     * costs) or with billing; see [GeminiTiers] for the texts of the "Tarif" choice. Keys come in
     * two shapes (`AIza…`, `AQ.…`) and the format is undocumented, so there is no prefix check.
     */
    val GEMINI = ProviderSpec(
        id = AiProviderId.GEMINI,
        name = "Google Gemini",
        brand = "Gemini",
        tagline = "Auch kostenlos nutzbar – mit Abrechnung im Chat mit Google-Suche.",
        engine = EngineKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        chatWebSearch = true,
        live = LiveSupport.NOT_ALLOWED,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://aistudio.google.com/apikey",
        consoleLabel = "aistudio.google.com öffnen",
        keySteps = listOf(
            "aistudio.google.com öffnen und mit Google anmelden",
            "„Get API key“ → „Create API key“",
            COPY_KEY,
        ),
        keyTip = null,
        keyPlaceholder = "AIza… oder AQ.…",
        keyPrefixes = emptyList(),
        invalidKeyText = null,
        models = listOf(
            ModelOption("gemini-3.8-flash", "Gemini 3.8 Flash", "Schnell und günstig (ab 2027 doppelter Preis)", badge = STANDARD),
            ModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Am günstigsten"),
            ModelOption("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)", "Stärkstes Gemini", requiresBilling = true),
        ),
        defaultModel = "gemini-3.8-flash",
    )

    val XAI = ProviderSpec(
        id = AiProviderId.XAI,
        name = "xAI Grok",
        brand = "Grok",
        tagline = "Grok mit Websuche, günstig pro Suche.",
        engine = EngineKind.RESPONSES,
        baseUrl = "https://api.x.ai/v1",
        chatWebSearch = true,
        live = LiveSupport.YES,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://console.x.ai",
        consoleLabel = "console.x.ai öffnen",
        keySteps = listOf(
            "console.x.ai öffnen und anmelden",
            "„API Keys“ → „Create API Key“",
            "Unter „Billing“ Guthaben aufladen",
            COPY_KEY,
        ),
        keyTip = null,
        keyPlaceholder = "xai-…",
        keyPrefixes = emptyList(),
        invalidKeyText = null,
        models = listOf(
            ModelOption("grok-4.3", "Grok 4.3", "Günstig", badge = STANDARD),
            ModelOption("grok-4.7", "Grok 4.7", "Spitzenmodell"),
        ),
        defaultModel = "grok-4.3",
    )

    val PERPLEXITY = ProviderSpec(
        id = AiProviderId.PERPLEXITY,
        name = "Perplexity",
        brand = "Perplexity",
        tagline = "Such-Spezialist: Antworten mit vielen Quellen.",
        engine = EngineKind.RESPONSES,
        baseUrl = "https://api.perplexity.ai/v1",
        chatWebSearch = true,
        live = LiveSupport.YES,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://www.perplexity.ai/account/api/keys",
        consoleLabel = "perplexity.ai öffnen",
        keySteps = listOf(
            "perplexity.ai öffnen und anmelden",
            "Einstellungen → „API“ → „API-Key erstellen“",
            "Guthaben aufladen",
            COPY_KEY,
        ),
        keyTip = "Ist das Guthaben leer, meldet Perplexity den Key als ungültig.",
        keyPlaceholder = "pplx-…",
        keyPrefixes = emptyList(),
        invalidKeyText = "Perplexity lehnt den Key ab – er ist ungültig oder dein Guthaben ist leer.",
        models = listOf(
            // Perplexity writes Anthropic ids with dashes (OC §6.2).
            ModelOption("google/gemini-3.8-flash", "Gemini 3.8 Flash", "Mit Perplexity-Suche", badge = STANDARD),
            ModelOption("perplexity/sonar", "Sonar", "Perplexitys eigenes Modell, günstig"),
            ModelOption("anthropic/claude-sonnet-5-5", "Claude Sonnet 5.5", "Claude mit Perplexity-Suche"),
        ),
        defaultModel = "google/gemini-3.8-flash",
    )

    val OPENROUTER = ProviderSpec(
        id = AiProviderId.OPENROUTER,
        name = "OpenRouter",
        brand = "OpenRouter",
        tagline = "Ein Key für viele Modelle – Claude, GPT, Gemini und mehr.",
        engine = EngineKind.CHAT_COMPLETIONS,
        baseUrl = "https://openrouter.ai/api/v1",
        chatWebSearch = true,
        live = LiveSupport.YES,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://openrouter.ai/settings/keys",
        consoleLabel = "openrouter.ai öffnen",
        keySteps = listOf(
            "openrouter.ai öffnen und anmelden",
            "„Keys“ → „Create Key“",
            "Unter „Credits“ Guthaben kaufen",
            COPY_KEY,
        ),
        keyTip = "Ein Key für viele Modelle. Am Key kannst du ein Ausgabenlimit setzen.",
        keyPlaceholder = "sk-or-v1-…",
        keyPrefixes = listOf("sk-or-"),
        invalidKeyText = null,
        models = listOf(
            // OpenRouter writes Anthropic ids with dots (OC §7.2).
            ModelOption("google/gemini-3.8-flash", "Gemini 3.8 Flash", "Schnell und günstig", badge = STANDARD),
            ModelOption("openai/gpt-6-luna", "GPT-6 Luna", "Sehr günstig"),
            ModelOption("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", "Gründlich"),
        ),
        defaultModel = "google/gemini-3.8-flash",
    )

    val MISTRAL = ProviderSpec(
        id = AiProviderId.MISTRAL,
        name = "Mistral",
        brand = "Mistral",
        tagline = "Europäischer Anbieter – Chat ohne Websuche.",
        engine = EngineKind.CHAT_COMPLETIONS,
        baseUrl = "https://api.mistral.ai/v1",
        chatWebSearch = false,
        live = LiveSupport.NO_WEB_SEARCH,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://console.mistral.ai/api-keys",
        consoleLabel = "console.mistral.ai öffnen",
        keySteps = listOf(
            "console.mistral.ai öffnen und anmelden",
            "Eine Zahlungsmethode hinterlegen",
            "„API Keys“ → „Create new key“",
            "Key kopieren (er wird nur einmal angezeigt)",
        ),
        keyTip = null,
        keyPlaceholder = "…",
        keyPrefixes = emptyList(),
        invalidKeyText = null,
        models = listOf(
            ModelOption("mistral-medium-3-5", "Mistral Medium 3.5", "Ausgewogen", badge = STANDARD),
            ModelOption("mistral-small-2603", "Mistral Small 4", "Sehr günstig"),
            ModelOption("mistral-large-2512", "Mistral Large 3", "Stark und günstig"),
        ),
        defaultModel = "mistral-medium-3-5",
    )

    val DEEPSEEK = ProviderSpec(
        id = AiProviderId.DEEPSEEK,
        name = "DeepSeek",
        brand = "DeepSeek",
        tagline = "Sehr günstig – Chat ohne Websuche.",
        engine = EngineKind.CHAT_COMPLETIONS,
        baseUrl = "https://api.deepseek.com",
        chatWebSearch = false,
        live = LiveSupport.NO_WEB_SEARCH,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = "https://platform.deepseek.com/api_keys",
        consoleLabel = "platform.deepseek.com öffnen",
        keySteps = listOf(
            "platform.deepseek.com öffnen und anmelden",
            "Guthaben aufladen",
            "„API keys“ → „Create new API key“",
            COPY_KEY,
        ),
        keyTip = null,
        keyPlaceholder = "sk-…",
        keyPrefixes = emptyList(),
        invalidKeyText = null,
        models = listOf(
            ModelOption("deepseek-flash", "DeepSeek V4.1 Flash", "Sehr günstig", badge = STANDARD),
            ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", "Stärker"),
        ),
        defaultModel = "deepseek-flash",
    )

    val CUSTOM = ProviderSpec(
        id = AiProviderId.CUSTOM,
        name = "Eigener Server",
        brand = "dein Server",
        tagline = "Ollama, LM Studio & Co. auf NAS oder PC – kostenlos, ohne Websuche.",
        engine = EngineKind.CHAT_COMPLETIONS,
        baseUrl = null,
        chatWebSearch = false,
        live = LiveSupport.NO_WEB_SEARCH,
        liveToolNote = LiveToolNote.SEARCH_ONLY,
        needsSdk = false,
        consoleUrl = null,
        consoleLabel = null,
        keySteps = listOf(
            "Ollama oder LM Studio im Heimnetz starten (Server für das Netzwerk freigeben)",
            "Adresse eintragen, z. B. http://nas.fritz.box:11434/v1",
            "Key nur eintragen, wenn dein Server einen verlangt",
        ),
        keyTip = "Unverschlüsseltes http:// geht nur mit Namen wie nas.fritz.box, nas.local oder *.home.arpa. " +
            "Für eine IP-Adresse brauchst du https://.",
        keyPlaceholder = "optional",
        keyPrefixes = emptyList(),
        invalidKeyText = null,
        models = emptyList(),
        defaultModel = "",
    )

    /** Every provider in picker order. */
    val all: List<ProviderSpec> = listOf(ANTHROPIC, OPENAI, GEMINI, XAI, PERPLEXITY, OPENROUTER, MISTRAL, DEEPSEEK, CUSTOM)

    private val byId: Map<AiProviderId, ProviderSpec> = all.associateBy { it.id }

    fun spec(id: AiProviderId): ProviderSpec = byId.getValue(id)

    /**
     * The model requests of [id] use: the stored one, else the catalog default. A free Gemini key
     * never runs a model that needs billing (the tier may have been switched back) — it falls
     * back to the default instead of failing.
     */
    fun effectiveModel(ai: AiSettings, id: AiProviderId): String {
        val spec = spec(id)
        val stored = ai.of(id).model?.trim()?.takeIf { it.isNotEmpty() } ?: return spec.defaultModel
        return if (isModelSelectable(ai, id, stored)) stored else spec.defaultModel
    }

    /** False only for a curated model that needs billing while the Gemini key is on the free tier. */
    fun isModelSelectable(ai: AiSettings, id: AiProviderId, modelId: String): Boolean =
        !(ai.isGeminiFree(id) && spec(id).model(modelId)?.requiresBilling == true)

    /**
     * Whether the chat with [id] may search the web as configured: the provider can, and a Gemini
     * key is not on the free tier (free keys get no Google Search on Gemini 3.x).
     */
    fun chatWebSearch(ai: AiSettings, id: AiProviderId): Boolean = spec(id).chatWebSearch && !ai.isGeminiFree(id)
}

/**
 * Wording of the Gemini "Tarif" (key dialog, settings, chat). [GeminiTier.FREE] is the default
 * of a new Gemini setup.
 */
object GeminiTiers {
    /** The short guide of the key dialog (with the link [ProviderCatalog.GEMINI]'s console URL). */
    const val KEY_GUIDE = "aistudio.google.com → Get API key → Create API key"

    /** Under the tariff choice: what Google may do with free-tier requests. */
    const val PRIVACY = "Im kostenlosen Tarif darf Google deine Anfragen zur Verbesserung seiner Produkte nutzen."

    /** With billing: the search allowance and how the app counts it. */
    const val SEARCH_ALLOWANCE =
        "Mit Abrechnung sind 5.000 Google-Suchen im Monat frei, danach kosten 1.000 Suchen 14 $. " +
            "Die App rechnet vorsichtig jede Suche mit."

    /** Calm note in a chat with a free Gemini key. */
    const val NO_WEB_SEARCH_NOTE = "Ohne Websuche – mit dem kostenlosen Gemini-Tarif nicht verfügbar"

    /** Under an answer that fell back to no search because the key turned out to be a free one. */
    const val FALLBACK_NOTE = "Ohne Websuche beantwortet – für die Websuche braucht Gemini ein Projekt mit Abrechnung."

    /** Marks a model that cannot be picked on the free tier. */
    const val BILLING_ONLY = "nur mit Abrechnung"

    /** Name of a tariff in the picker. */
    fun label(tier: GeminiTier): String = when (tier) {
        GeminiTier.FREE -> "Kostenlos (ohne Abrechnung)"
        GeminiTier.PAID -> "Mit Abrechnung (Paid Tier)"
    }

    /** One line per tariff; together the two-line explanation of the key dialog. */
    fun description(tier: GeminiTier): String = when (tier) {
        GeminiTier.FREE -> "Kostenlos: kein Guthaben nötig, Antworten nur aus Artikel und Briefing – ohne Websuche."
        GeminiTier.PAID -> "Mit Abrechnung (Prepay im Google-Projekt): Antworten auch mit Google-Suche und Gemini Pro."
    }
}
