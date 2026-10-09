package com.cedrickgd.morgenbriefing.ui.chat

import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId

/*
 * The provider-dependent wording of "Frag nach" (docs/AI-PROVIDERS.md §10.6): who answers, what
 * the chat can do with or without web search, and the placeholder while it answers. Pure,
 * unit-tested.
 */

/** The small mark above an answer: the provider's brand, capitalised ("Claude", "Gemini", "Dein Server"). */
internal fun answerMark(spec: ProviderSpec): String = spec.brand.replaceFirstChar { it.uppercaseChar() }

/** Who writes the answers, inside the disclaimer of the empty chat. */
internal fun answerAuthor(spec: ProviderSpec): String = when (spec.id) {
    AiProviderId.ANTHROPIC -> "Claude von Anthropic"
    AiProviderId.OPENAI -> "ein GPT-Modell von OpenAI"
    AiProviderId.GEMINI -> "Gemini von Google"
    AiProviderId.XAI -> "Grok von xAI"
    AiProviderId.PERPLEXITY -> "ein Modell über Perplexity"
    AiProviderId.OPENROUTER -> "ein Modell über OpenRouter"
    AiProviderId.MISTRAL -> "ein Modell von Mistral"
    AiProviderId.DEEPSEEK -> "ein Modell von DeepSeek"
    AiProviderId.CUSTOM -> "das Modell auf deinem Server"
}

/** The line at the end of the empty chat: who answers, and where to check what matters. */
internal fun chatDisclaimer(spec: ProviderSpec, webSearch: Boolean): String =
    "Antworten schreibt ${answerAuthor(spec)} – sie können Fehler enthalten. " +
        if (webSearch) "Prüf Wichtiges in den verlinkten Quellen." else "Prüf Wichtiges in seriösen Quellen nach."

/**
 * The promise of the empty chat. Without web search (a free Gemini key, Mistral, DeepSeek, the own
 * server) it does not offer to look things up — it explains from the article or the brief.
 */
internal fun emptyChatIntro(aboutArticle: Boolean, webSearch: Boolean): String = when {
    aboutArticle && webSearch ->
        "Ich kenne den ganzen Artikel, erkläre Hintergründe und schaue im Netz nach, was seitdem passiert ist – mit Quellen."
    aboutArticle -> "Ich kenne den ganzen Artikel, erkläre Hintergründe und ordne ein – aus dem Artikel heraus."
    webSearch -> "Ich kenne alle Meldungen des Tages, ordne sie ein und schaue im Netz nach, was seitdem passiert ist – mit Quellen."
    else -> "Ich kenne alle Meldungen des Tages und ordne sie ein – aus dem Briefing heraus."
}

/** The input's placeholder while an answer streams. */
internal fun answeringPlaceholder(spec: ProviderSpec): String = "${answerMark(spec)} antwortet …"
