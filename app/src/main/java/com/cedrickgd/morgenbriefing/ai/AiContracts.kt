package com.cedrickgd.morgenbriefing.ai

import android.os.Build
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
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

    /**
     * Gemini: Google refused the request for a reason that lies in the key's Google project or
     * tariff rather than in a mistyped key (docs/providers/gemini.md §12.4). [problem] picks the
     * explanation and the link that fixes it.
     */
    data class GeminiAccess(val problem: GeminiProblem) : AiError
}

/** Why Google refused a Gemini key or request ([AiError.GeminiAccess], GE §12.4). */
enum class GeminiProblem {
    /**
     * 401 `UNAUTHENTICATED` with reason `ACCESS_TOKEN_TYPE_UNSUPPORTED`: Google does not take the
     * key. Seen for mistyped `AQ.…` keys, but also for new AI Studio auth keys that AI Studio shows
     * as valid (forum reports since 2026-08, no official fix).
     */
    AUTH_KEY_REJECTED,

    /**
     * "Your API key was reported as leaked": Google blocked the key because it showed up in public
     * (ai.google.dev troubleshooting); only a new key helps.
     */
    KEY_LEAKED,

    /** `API_KEY_SERVICE_BLOCKED` / `SERVICE_DISABLED`: the Gemini API is blocked or off in the key's project. */
    API_BLOCKED,

    /** `API_KEY_ANDROID_APP_BLOCKED`, `…_IP_ADDRESS_BLOCKED`, …: the key is restricted to other apps, sites or addresses. */
    KEY_RESTRICTED,

    /** 400 `FAILED_PRECONDITION`: the free tier is not available for the country or project; billing is needed. */
    FREE_TIER_UNAVAILABLE,
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
    /**
     * Failed answers: a short, key-free technical summary of what the provider answered, e.g.
     * "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED" — shown under "Details" so it
     * can be copied and sent along with a question. Null when there is nothing to add.
     */
    val errorDetail: String? = null,
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

    /** Gemini: Google does not take this key ([GeminiProblem.AUTH_KEY_REJECTED]). */
    AUTH_KEY_REJECTED,

    /** Gemini: Google blocked the key as leaked ([GeminiProblem.KEY_LEAKED]). */
    KEY_LEAKED,

    /** Gemini: the Gemini API is blocked or not enabled in the key's project ([GeminiProblem.API_BLOCKED]). */
    API_BLOCKED,

    /** Gemini: the key is restricted to other apps, sites or addresses ([GeminiProblem.KEY_RESTRICTED]). */
    KEY_RESTRICTED,

    /** Gemini: the free tier is not available for the country or project ([GeminiProblem.FREE_TIER_UNAVAILABLE]). */
    FREE_TIER_UNAVAILABLE,

    /** The key works, but none of the app's models may generate with it. */
    MODEL_UNAVAILABLE,

    /** The key works, but its quota is used up right now (Gemini 429): stored, with a note. */
    OK_QUOTA_EXHAUSTED,
}

/**
 * Result of a key or connection test.
 *
 * @property serverModels the own server's model ids.
 * @property diagnostic a key-free technical summary of the answer that decided a failed test
 *   (e.g. "HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED"), shown under "Details".
 * @property fallback Gemini: the chosen model could not generate with this key, [ModelFallback.to]
 *   could — the app uses that one for this key from now on.
 * @property openRouter OpenRouter: what `GET /key` says about the account.
 */
data class KeyCheckOutcome(
    val check: AiKeyCheck,
    val serverModels: List<String> = emptyList(),
    val diagnostic: String? = null,
    val fallback: ModelFallback? = null,
    val openRouter: OpenRouterKeyInfo? = null,
)

/**
 * What OpenRouter's `GET /key` says about an account (OC §7.9); every field null when not sent.
 *
 * @property freeTier `is_free_tier`: the account never bought credits (50 free requests a day).
 * @property freeRequestsLeft `free_model_daily_requests.remaining`: requests to free models left today.
 * @property freeRequestsPerDay `free_model_daily_requests.limit`: 50, or 1,000 with 10 $ bought.
 */
data class OpenRouterKeyInfo(
    val freeTier: Boolean? = null,
    val freeRequestsLeft: Int? = null,
    val freeRequestsPerDay: Int? = null,
)

/**
 * What a key test needs besides the key: the Anthropic workspace id, the own server's address and
 * the OpenRouter tariff the key is tested for (null = the stored one).
 */
data class KeyExtras(val workspaceId: String? = null, val baseUrl: String? = null, val openRouterTier: OpenRouterTier? = null)
