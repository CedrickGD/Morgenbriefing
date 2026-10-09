package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef

/*
 * The seam between the app-side services (chat sessions, live news, usage) and the provider
 * backends (docs/AI-PROVIDERS.md §4). Everything here is plain Kotlin: the services never see
 * an SDK or wire type, so they load and run on every API level. Only the Claude backend
 * (`ai.claude`) needs Android 8.0+; AiServices creates it behind AiSupport.sdkAvailable.
 */

/** A classified failure of an AI request; [error] is what the UI shows. */
internal class AiException(val error: AiError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/** A request was not sent because of [block] (e.g. the monthly limit was reached meanwhile). */
internal class AiBlockedException(val block: AiBlock) : Exception(block.name)

/**
 * Credentials and endpoint of one request. Never logged; [toString] shows only the provider.
 *
 * @property apiKey null only for the own server without a key.
 * @property baseUrl the own server's address, or (debug builds only) a QA override of the
 *   catalog endpoint; null = the catalog default (for Claude: the SDK default).
 * @property workspaceId Anthropic: sent as `anthropic-workspace-id` when not blank.
 * @property freeTier a Gemini key on the free tier: never send Google Search; a 429 means the
 *   free quota is used up ([AiError.QuotaExhausted]).
 */
internal class ProviderAuth(
    val provider: AiProviderId,
    val apiKey: String?,
    val baseUrl: String? = null,
    val workspaceId: String? = null,
    val freeTier: Boolean = false,
) {
    override fun toString(): String = "ProviderAuth(${provider.key})"
}

/** Monthly budget gate and cost recorder, called by the backend around every API request. */
internal interface UsageSink {
    /** @throws AiBlockedException with [AiBlock.LIMIT_REACHED] when the monthly limit is used up. */
    suspend fun beforeRequest()

    /** Adds one request's [usage] (priced for [modelId] at [provider]) to this month's total. */
    suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage)
}

/** What the server-side tools are doing right now (for "Suche im Netz: …" lines). */
internal interface ToolActivityListener {
    /** A web search started; "" when the provider does not say what it searches for. */
    fun onSearch(query: String) {}
    fun onFetch(url: String) {}

    /** A client tool call started streaming (e.g. `publish_articles`). */
    fun onToolCall(name: String) {}
}

/** One message of the conversation as the API sees it (plain text, D12). */
internal data class ApiMessage(val fromUser: Boolean, val text: String)

internal data class ChatTurnRequest(
    val auth: ProviderAuth,
    val model: String,
    /** Stable German role prompt (cached). */
    val systemPrompt: String,
    /** Article or brief context (cached after the system prompt). */
    val context: String,
    /** Alternating user/assistant messages, starting and ending with a user message. */
    val history: List<ApiMessage>,
    /** Offer the provider's web search; false for providers without one and free Gemini keys. */
    val webSearch: Boolean = true,
)

internal interface ChatTurnListener : ToolActivityListener {
    /** Streamed answer text, in order. */
    fun onText(delta: String) {}

    /** The current, ranked list of sources (replaces the previous one). */
    fun onSources(sources: List<SourceRef>) {}

    /** Gemini only: HTML of the Search Suggestions that must be shown with the answer. */
    fun onSearchSuggestions(html: String) {}

    /**
     * Gemini only: the answer is grounded in Google Search (the first search query or grounding
     * source arrived). Grounded text may only be shown with its Search Suggestions (GE §11), which
     * come last — an answer that ends before them must not keep the grounded text.
     */
    fun onGrounded() {}
}

internal enum class TurnEnd {
    COMPLETE,
    TRUNCATED,
    REFUSED,

    /**
     * Stopped before the model finished its job: the request budget ran out, or a live run
     * ended without the required `publish_articles` call even after the reminder.
     */
    INCOMPLETE,
}

internal data class ChatTurnResult(
    val text: String,
    val sources: List<SourceRef>,
    val end: TurnEnd,
    /** `stop_details.category` of a refusal (or a provider's block reason), when named. */
    val refusalCategory: String? = null,
    /** Gemini: the latest Search Suggestions HTML of a grounded answer. */
    val searchSuggestionsHtml: String? = null,
    /** Gemini free-tier fallback: the answer came without web search although it was asked for. */
    val withoutWebSearch: Boolean = false,
)

internal interface ChatEngine {
    /**
     * Streams one answer (continuing `pause_turn` and the like until the turn ends).
     * @throws AiException on API/network errors, AiBlockedException when the limit stops it.
     */
    suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult
}

internal data class LiveResearchRequest(
    val auth: ProviderAuth,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
)

/** Answer of the app to one `publish_articles` call. */
internal data class PublishReply(val accepted: Boolean, val message: String)

internal interface LiveResearchListener : ToolActivityListener {
    /** Validates and stores one `publish_articles` call; [rawInput] is the tool input JSON. */
    suspend fun onPublish(rawInput: String): PublishReply
}

internal data class LiveResearchResult(
    /** At least one `publish_articles` call was accepted. */
    val published: Boolean,
    /**
     * [TurnEnd.COMPLETE] only when the model published (possibly an empty list) and ended its
     * turn; a run that stopped without publishing is [TurnEnd.INCOMPLETE].
     */
    val end: TurnEnd,
    val refusalCategory: String? = null,
)

internal interface LiveEngine {
    /**
     * Runs one research conversation until the model ends its turn.
     * @throws AiException on API/network errors, AiBlockedException when the limit stops it.
     */
    suspend fun research(request: LiveResearchRequest, listener: LiveResearchListener, usage: UsageSink): LiveResearchResult
}

internal interface KeyChecker {
    /**
     * Checks [auth]'s key (and workspace or server address) with as few tokens as possible,
     * against [model]; any usage goes to [usage]. Never throws except for cancellation.
     */
    suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome
}

/** Everything that talks to one provider family. */
internal interface AiBackend {
    val chat: ChatEngine

    /** Null: the family/provider has no Live (Gemini, providers without web search). */
    val live: LiveEngine?
    val keys: KeyChecker
}
