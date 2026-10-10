package com.cedrickgd.morgenbriefing.ui.chat

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.ChatMessage
import com.cedrickgd.morgenbriefing.ai.hostOf
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef

/*
 * The provider-dependent wording of "Frag nach" (docs/AI-PROVIDERS.md §10.6): who answers, what
 * the chat can do with or without web search, and the placeholder while it answers. Pure,
 * unit-tested.
 */

/** The small mark above an answer: the provider's brand, capitalised ("Claude", "Gemini", "Dein Server"). */
internal fun answerMark(spec: ProviderSpec): String = spec.brand.replaceFirstChar { it.uppercaseChar() }

/**
 * Whose answer [message] is: the provider that wrote it — its mark, its error texts and the key
 * dialog behind "Key ändern" — not the one the chat uses now (the history outlives a provider
 * switch, D12). Answers saved before v1.3.0 know no provider and fall back to [chat].
 */
internal fun answerSpec(message: ChatMessage, chat: ProviderSpec): ProviderSpec =
    message.provider?.let(ProviderCatalog::spec) ?: chat

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

/** One button under a failed answer, in the order the card shows them. */
internal enum class ErrorAction(val label: String?) {
    /** The page that fixes it ([AiTexts.errorLink], its label comes with the link). */
    OPEN_LINK(null),

    /** Google refuses the Gemini key: OpenRouter's free tariff instead. */
    SET_UP_OPENROUTER(AiTexts.OPENROUTER_PLAN_B),

    /** The key dialog of the provider that wrote the answer. */
    CHANGE_KEY("Key ändern"),

    /** The same request again: it may simply work in a moment. */
    RETRY("Nochmal versuchen"),

    /**
     * The question once more after the user fixed the cause elsewhere (a new key, credits, another
     * provider or model): retrying alone would not help.
     */
    RESEND("Nochmal senden"),
}

/**
 * The buttons under a failed answer with [error] from [provider]: the page that fixes it,
 * OpenRouter as the way out of a refused Gemini key, "Key ändern" where a key is the cause, and —
 * only under the last answer while the chat can send ([canRetry]) — "Nochmal versuchen" where a
 * retry can help ([AiTexts.isRetryable]; not OpenRouter's used-up day), else "Nochmal senden" for
 * once the cause is fixed. A refusal is not sent again unchanged.
 */
internal fun errorActions(error: AiError, canRetry: Boolean, provider: AiProviderId? = null): List<ErrorAction> = buildList {
    if (AiTexts.errorLink(error) != null) add(ErrorAction.OPEN_LINK)
    if (AiTexts.offersOpenRouter(error)) add(ErrorAction.SET_UP_OPENROUTER)
    if (AiTexts.needsKeyChange(error)) add(ErrorAction.CHANGE_KEY)
    if (canRetry && error !is AiError.Refusal) add(if (AiTexts.isRetryable(error, provider)) ErrorAction.RETRY else ErrorAction.RESEND)
}

/** Gemini's grounding sources are Google redirects on this host; their title is the real domain (GE §6.2). */
private const val GROUNDING_REDIRECT_HOST = "vertexaisearch.cloud.google.com"

/**
 * The two parts of a source pill: the host in bold and the title after it (null when it adds
 * nothing). A Gemini grounding source is a Google redirect whose title is the domain: the pill
 * shows that domain alone instead of Google's redirect host. The link itself stays the redirect
 * and is opened as it is (GE §11).
 */
internal fun sourceChipParts(source: SourceRef): Pair<String, String?> {
    val host = hostOf(source.url)
    val name = source.name.trim()
    if (host == GROUNDING_REDIRECT_HOST && name.isNotEmpty()) return name to null
    return host to name.takeIf { it.isNotEmpty() && it != host }
}
