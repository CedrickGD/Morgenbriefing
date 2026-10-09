package com.cedrickgd.morgenbriefing.ai

import android.os.Build
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * Contracts for the AI features (docs/AI.md, docs/AI-PROVIDERS.md). The UI only talks to these
 * types; the provider backends live behind them in this package. Only the Claude backend
 * (Anthropic SDK) needs Android 8.0+ and is never loaded below API 26; everything else runs on
 * every API level.
 */

object AiSupport {
    /** The Anthropic Java SDK needs Android 8.0+. Check before touching ai.claude. */
    val sdkAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** [provider] can run on this device (only Claude needs the SDK). */
    fun supports(provider: AiProviderId): Boolean = !ProviderCatalog.spec(provider).needsSdk || sdkAvailable
}

/** Why an AI feature cannot run right now (shown as a friendly hint, never as an error). */
enum class AiBlock {
    /** The chosen provider needs Android 8.0+ (Claude) or has no backend on this device. */
    UNSUPPORTED_DEVICE,

    /** No key for the provider (own server: no address). */
    NO_KEY,

    /** This month's spending reached the user's cap (never for free providers). */
    LIMIT_REACHED,

    /** "⚡ Live" only: the Live provider cannot research (no web search, or Gemini's terms). */
    LIVE_UNAVAILABLE,
}

sealed interface AiError {
    data object InvalidKey : AiError
    data object RateLimited : AiError
    data object Overloaded : AiError
    data object Network : AiError

    /** The model declined; [category] from `stop_details` when present. */
    data class Refusal(val category: String?) : AiError

    /** Hit max_tokens – the answer is shown but marked as cut off. */
    data object Truncated : AiError
    data class Unknown(val message: String?) : AiError

    /** The account's credit is used up (HTTP 402, billing errors). */
    data object NoCredit : AiError

    /** Anthropic: an identity-linked key that is not bound to a workspace needs a workspace id. */
    data object WorkspaceRequired : AiError

    /** Anthropic: the workspace id is malformed, unknown, or the key may not act in it. */
    data object WorkspaceNotFound : AiError

    /** The provider does not know [model] (any more), or the key may not use it. */
    data class ModelUnavailable(val model: String?) : AiError

    /** The own server did not answer (not running, other network, wrong address). */
    data object ServerUnreachable : AiError

    /** A free tier's quota is used up for now (a free Gemini key, HTTP 429): later it works again. */
    data object QuotaExhausted : AiError
}

data class ChatMessage(
    val id: String,
    val role: Role,
    /** Plain text with light markdown (paragraphs, **bold**, "- " bullets). */
    val text: String,
    /** Web sources the answer used (from web_search / web_fetch results). */
    val sources: List<SourceRef> = emptyList(),
    val status: Status = Status.DONE,
    val error: AiError? = null,
    /**
     * Gemini with Google Search: HTML of the Search Suggestions, which must be shown unmodified
     * with this answer whenever it is shown (also from history).
     */
    val searchSuggestionsHtml: String? = null,
    /** A quiet line under the answer, e.g. "Ohne Websuche beantwortet …". */
    val note: String? = null,
    /**
     * Answers: the provider that wrote it (the chat history survives a provider switch, D12, so
     * every answer names its own author and its errors their own key). Null for questions and for
     * answers saved before v1.3.0 — those are shown under the chat's current provider.
     */
    val provider: AiProviderId? = null,
) {
    enum class Role { USER, ASSISTANT }
    enum class Status { STREAMING, DONE, FAILED }
}

/** What the chat is about: one article ([articleKey]) or the whole brief of [date] (null key). */
data class ChatContext(val date: String, val articleKey: String?)

data class ChatUiState(
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    /** Live status while server tools run, e.g. "Suche im Netz: EZB Zinsentscheid". */
    val activity: String? = null,
    /** Suggested follow-up questions (chips). */
    val suggestions: List<String> = emptyList(),
    val blocked: AiBlock? = null,
    /** The chat provider ([com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec.name]); "" until known. */
    val providerName: String = "",
    val provider: AiProviderId? = null,
    /**
     * A calm hint about this chat as a whole, e.g. that a free Gemini key answers without web
     * search; null when there is nothing to say.
     */
    val note: String? = null,
)

interface ChatSession {
    val state: StateFlow<ChatUiState>
    fun send(text: String)
    /** Cancels the running answer (keeps what has streamed so far). */
    fun stop()
    /** Forgets the conversation for this context. */
    fun clear()
}

interface ChatService {
    /** Same context → same session (history kept per article/brief, also across app restarts). */
    fun session(context: ChatContext): ChatSession
}

/** A live-researched article (docs/AI.md §2); [item] follows brief schema 2. */
data class LiveArticle(val item: NewsItem, val topicId: String, val fetchedAt: String)

data class LiveState(
    val running: Boolean = false,
    /** Human-readable progress lines, newest last ("Suche: …", "Lese tagesschau.de …"). */
    val progress: List<String> = emptyList(),
    /** ISO-8601 with offset of the last successful run today, or null. */
    val lastRunAt: String? = null,
    /** Articles the last run added. */
    val newCount: Int = 0,
    val error: AiError? = null,
    val blocked: AiBlock? = null,
    /** The Live provider; null until known. */
    val provider: AiProviderId? = null,
    val providerName: String = "",
)

interface LiveNewsService {
    val state: StateFlow<LiveState>
    /** Today's live articles, newest first; updates when a run finishes. */
    val todayArticles: Flow<List<LiveArticle>>
    /** Start a live research run for [topicIds] (visible topics or one chip). No-op while running. */
    fun refresh(topicIds: List<String>)
    fun cancel()
    /** Stored live articles of any day (for history and the reader). */
    suspend fun articles(date: LocalDate): List<LiveArticle>
}

data class UsageSnapshot(
    /** `YYYY-MM` */
    val month: String = "",
    val spentUsd: Double = 0.0,
    val limitUsd: Double = 0.0,
    val requests: Int = 0,
    val webSearches: Int = 0,
    /** Requests of this month priced with fallback rates ("Preis teils geschätzt"). */
    val estimatedRequests: Int = 0,
)

interface UsageTracker {
    val usage: StateFlow<UsageSnapshot>
}

enum class AiKeyCheck {
    OK,

    /** The key works, but the account has no credit left: stored, with a warning. */
    OK_NO_CREDIT,
    INVALID,
    NETWORK,

    /** The provider cannot run on this device. */
    UNSUPPORTED,

    /** Anthropic: the key needs a workspace id. */
    WORKSPACE_REQUIRED,

    /** Anthropic: the workspace id is unknown or not allowed for the key. */
    WORKSPACE_INVALID,

    /** Own server: no connection to the address. */
    SERVER_UNREACHABLE,

    /**
     * Own server: something answers, but not with HTTPS the app trusts — a self-signed
     * certificate, or a server that only speaks http:// behind an https:// address (the app
     * trusts only public CAs and ISRG, docs/AI-PROVIDERS.md §7.6).
     */
    SERVER_CERTIFICATE,

    /** Own server: plain http:// to a host outside the cleartext allowlist. */
    CLEARTEXT_BLOCKED,

    /** Own server: something answers, but not an OpenAI-compatible API. */
    NOT_AN_AI_SERVER,
}

/** Result of a key or connection test; [serverModels] = the own server's model ids. */
data class KeyCheckOutcome(val check: AiKeyCheck, val serverModels: List<String> = emptyList())

/** What a key test needs besides the key: the Anthropic workspace id, the own server's address. */
data class KeyExtras(val workspaceId: String? = null, val baseUrl: String? = null)
