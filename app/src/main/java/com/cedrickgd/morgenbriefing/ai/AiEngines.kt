package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef

/*
 * The seam between the app-side services (chat sessions, live news, usage) and the Claude API
 * backend in `ai.claude`. Everything here is plain Kotlin: the services never see an SDK type,
 * so they load and run on every API level, and the backend (which needs Android 8.0+) is only
 * created when AiSupport.isSupported.
 */

/** A classified failure of an AI request; [error] is what the UI shows. */
internal class AiException(val error: AiError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

/** A request was not sent because of [block] (e.g. the monthly limit was reached meanwhile). */
internal class AiBlockedException(val block: AiBlock) : Exception(block.name)

/** Monthly budget gate and cost recorder, called by the backend around every API request. */
internal interface UsageSink {
    /** @throws AiBlockedException with [AiBlock.LIMIT_REACHED] when the monthly limit is used up. */
    suspend fun beforeRequest()

    /** Adds one request's [usage] (priced for [modelId] at [provider]) to this month's total. */
    suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage)
}

/** What the server-side tools are doing right now (for "Suche im Netz: …" lines). */
internal interface ToolActivityListener {
    fun onSearch(query: String) {}
    fun onFetch(url: String) {}
    /** A client tool call started streaming (e.g. `publish_articles`). */
    fun onToolCall(name: String) {}
}

/** One message of the conversation as the API sees it (plain text). */
internal data class ApiMessage(val fromUser: Boolean, val text: String)

internal data class ChatTurnRequest(
    val apiKey: String,
    val model: String,
    /** Stable German role prompt (cached). */
    val systemPrompt: String,
    /** Article or brief context (cached after the system prompt). */
    val context: String,
    /** Alternating user/assistant messages, starting and ending with a user message. */
    val history: List<ApiMessage>,
)

internal interface ChatTurnListener : ToolActivityListener {
    /** Streamed answer text, in order. */
    fun onText(delta: String) {}

    /** The current, ranked list of sources (replaces the previous one). */
    fun onSources(sources: List<SourceRef>) {}
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
    /** `stop_details.category` of a refusal, when the API named one. */
    val refusalCategory: String? = null,
)

internal interface ChatEngine {
    /**
     * Streams one answer (continuing `pause_turn` until the turn ends).
     * @throws AiException on API/network errors, AiBlockedException when the limit stops it.
     */
    suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult
}

internal data class LiveResearchRequest(
    val apiKey: String,
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
    suspend fun check(key: String, usage: UsageSink): AiKeyCheck
}

/** Everything that talks to the Claude API. Only exists on Android 8.0+. */
internal interface AiBackend {
    val chat: ChatEngine
    val live: LiveEngine
    val keys: KeyChecker
}
