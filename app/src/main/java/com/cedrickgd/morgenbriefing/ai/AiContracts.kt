package com.cedrickgd.morgenbriefing.ai

import android.os.Build
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * Contracts for the AI features (docs/AI.md). The UI only talks to these types; everything
 * that touches the Anthropic SDK lives behind them in this package and is never loaded below
 * API 26 (the SDK does not run on Android 7).
 */

object AiSupport {
    /** The Anthropic Java SDK needs Android 8.0+. Check before any other ai/ call. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}

/** Why an AI feature cannot run right now (shown as a friendly hint, never as an error). */
enum class AiBlock { UNSUPPORTED_DEVICE, NO_KEY, LIMIT_REACHED }

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
)

interface UsageTracker {
    val usage: StateFlow<UsageSnapshot>
}

enum class AiKeyCheck { OK, INVALID, NETWORK, UNSUPPORTED }
