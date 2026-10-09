package com.cedrickgd.morgenbriefing.ai

import android.util.Log
import com.cedrickgd.morgenbriefing.ai.http.ErrorDiagnostics
import com.cedrickgd.morgenbriefing.ai.provider.GeminiModels
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Title and context text of a conversation (an article or a whole brief). */
internal data class LoadedChatContext(val title: String, val text: String)

/** Finds the article/brief a chat is about. Null when it is not available (anymore). */
internal fun interface ChatContextSource {
    suspend fun load(context: ChatContext): LoadedChatContext?
}

/** A chat session that can repeat its last failed question (the "Nochmal" button). */
interface RetryableChatSession : ChatSession {
    /** Sends the last question again after a failure or after it was stopped without answer. */
    fun retry()
}

/**
 * "Frag nach" (docs/AI.md §1): one session per [ChatContext], kept for the whole process and
 * restored from [store] after a restart. Each turn asks [access] for the chat provider of that
 * moment (D1) and streams the answer through its backend; every session shows the provider and
 * why it is blocked, live ([AiProviderAccess.status]). A key the provider refuses
 * ([AiError.InvalidKey]) is reported to [AiProviderAccess.keyRejected].
 */
internal class ChatServiceImpl(
    private val scope: CoroutineScope,
    private val access: AiProviderAccess,
    private val store: ChatStore,
    private val contexts: ChatContextSource,
    private val clock: Clock = Clock.system(BERLIN),
) : ChatService {
    private val sessions = HashMap<ChatContext, ChatSessionImpl>()

    @Synchronized
    override fun session(context: ChatContext): ChatSession =
        sessions.getOrPut(context) { ChatSessionImpl(context) }

    internal inner class ChatSessionImpl(private val context: ChatContext) : RetryableChatSession {
        /** The chat provider searches the web as configured (picks the example chips); declared first, read below. */
        @Volatile
        private var webSearch = true

        private val _state = MutableStateFlow(
            ChatUiState(
                title = if (context.articleKey == null) AiPrompts.BRIEF_TITLE else "",
                suggestions = suggestions(),
            ),
        )
        override val state: StateFlow<ChatUiState> = _state.asStateFlow()

        /** API text (question + date note) per user message id. */
        private val apiTexts = ConcurrentHashMap<String, String>()

        @Volatile
        private var loaded: LoadedChatContext? = null

        /** The current turn's job; guarded by `this`. */
        private var turn: Job? = null

        /** True from send() until the turn has ended and been saved; guarded by `this`. */
        private var sending = false
        private var turnCounter = 0L

        /** Token of the turn that is running (turns run one after another). */
        @Volatile
        private var currentToken = 0L
        private val ready: Deferred<Unit> = scope.async { restore() }

        init {
            scope.launch {
                access.status(AiPurpose.CHAT).collect { status ->
                    webSearch = status.webSearch
                    _state.update {
                        it.copy(
                            blocked = status.block,
                            provider = status.spec.id,
                            providerName = status.spec.name,
                            note = AiTexts.chatNote(status.spec, status.webSearch, status.freeTier),
                            // Shown examples follow the provider: no "andere Quellen" without web search.
                            suggestions = if (it.suggestions.isEmpty()) it.suggestions else suggestions(),
                        )
                    }
                }
            }
        }

        override fun send(text: String) {
            val question = text.trim()
            if (question.isEmpty()) return
            synchronized(this) {
                if (sending) return
                sending = true
                val token = ++turnCounter
                val previous = turn
                turn = scope.launch {
                    try {
                        previous?.join()
                        currentToken = token
                        runTurn(question)
                    } finally {
                        release(token)
                    }
                }
            }
        }

        /** Ends the "sending" phase of turn [token] (a later turn is not affected). */
        private fun release(token: Long) {
            synchronized(this) { if (token == turnCounter) sending = false }
        }

        override fun stop() {
            synchronized(this) { turn?.cancel() }
        }

        override fun clear() {
            synchronized(this) { turn?.cancel() }
            scope.launch {
                ready.await()
                turn?.join()
                apiTexts.clear()
                loaded = null
                _state.update { it.copy(messages = emptyList(), busy = false, activity = null, suggestions = suggestions()) }
                try {
                    store.delete(context)
                } catch (e: IOException) {
                    Log.w(TAG, "Chat history could not be deleted", e)
                }
                loadContextQuietly()
            }
        }

        override fun retry() {
            val question: String
            synchronized(this) {
                if (sending) return
                val messages = _state.value.messages
                val last = messages.lastOrNull() ?: return
                val drop: Int
                when {
                    last.role == ChatMessage.Role.USER -> {
                        question = last.text
                        drop = 1
                    }
                    last.status == ChatMessage.Status.FAILED &&
                        messages.getOrNull(messages.size - 2)?.role == ChatMessage.Role.USER -> {
                        question = messages[messages.size - 2].text
                        drop = 2
                    }
                    else -> return
                }
                _state.update { it.copy(messages = it.messages.dropLast(drop)) }
            }
            send(question)
        }

        // ---------------------------------------------------------------------------------

        private suspend fun runTurn(question: String) {
            ready.await()
            val resolved = when (val resolution = access.resolve(AiPurpose.CHAT)) {
                is Resolution.Blocked -> {
                    _state.update { it.copy(blocked = resolution.block) }
                    return
                }
                is Resolution.Ready -> resolution
            }
            val usage = resolved.usage
            try {
                usage.beforeRequest()
            } catch (e: AiBlockedException) {
                _state.update { it.copy(blocked = e.block) }
                return
            }

            val history = buildApiHistory(_state.value.messages, apiTexts)
            val now = ZonedDateTime.now(clock).withZoneSameInstant(BERLIN)
            val note = AiPrompts.dayNote(now)
            val dayMarker = "Heute ist ${AiPrompts.longDate(now.toLocalDate())}"
            val needsNote = history.none { it.fromUser && dayMarker in it.text }
            val apiText = AiPrompts.userTurn(question, if (needsNote) note else null)

            val user = ChatMessage(id = newId(), role = ChatMessage.Role.USER, text = question)
            val answerId = newId()
            // The answer names the provider that writes it: the history outlives a provider switch (D12).
            val placeholder = ChatMessage(
                id = answerId,
                role = ChatMessage.Role.ASSISTANT,
                text = "",
                status = ChatMessage.Status.STREAMING,
                provider = resolved.spec.id,
            )
            apiTexts[user.id] = apiText
            _state.update {
                it.copy(messages = it.messages + user + placeholder, busy = true, activity = null, suggestions = emptyList())
            }
            val buffer = StringBuilder()
            // Gemini grounded this answer in Google Search: its text needs the Search Suggestions.
            val grounded = AtomicBoolean(false)
            val listener = object : ChatTurnListener {
                override fun onGrounded() {
                    grounded.set(true)
                }

                override fun onText(delta: String) {
                    buffer.append(delta)
                    val text = buffer.toString()
                    _state.update { s -> s.copy(messages = s.messages.replace(answerId) { it.copy(text = text) }, activity = null) }
                }

                override fun onSources(sources: List<SourceRef>) {
                    _state.update { s -> s.copy(messages = s.messages.replace(answerId) { it.copy(sources = sources) }) }
                }

                override fun onSearchSuggestions(html: String) {
                    _state.update { s -> s.copy(messages = s.messages.replace(answerId) { it.copy(searchSuggestionsHtml = html) }) }
                }

                override fun onSearch(query: String) {
                    val what = query.trim().take(80)
                    _state.update { it.copy(activity = if (what.isEmpty()) "Suche im Netz …" else "Suche im Netz: $what") }
                }

                override fun onFetch(url: String) {
                    _state.update { it.copy(activity = "Lese ${hostOf(url)} …") }
                }
            }

            // Everything from here on runs inside one try: a stop() while the question is saved or
            // the context still loads must end the turn too, or the chat would stay busy for good.
            try {
                // The question survives even if the app is closed mid-answer (retry() picks it up).
                persist()

                val ctx = loaded ?: contexts.loadQuietly(context)?.also { applyContext(it) }
                if (ctx == null) {
                    finish(answerId) {
                        it.copy(
                            status = ChatMessage.Status.FAILED,
                            error = AiError.Unknown("Zu diesem Artikel finde ich keinen Text mehr – vielleicht ist er nicht mehr gespeichert."),
                        )
                    }
                    return
                }

                val request = ChatTurnRequest(
                    auth = resolved.auth,
                    model = resolved.model,
                    systemPrompt = AiPrompts.chatSystem(resolved.webSearch),
                    context = ctx.text,
                    history = history + ApiMessage(fromUser = true, text = apiText),
                    webSearch = resolved.webSearch,
                )
                val result = resolved.backend.chat.answer(request, listener, usage)
                // Another model answered because the one asked for is not open to this key: kept for
                // the key, so the next question goes there directly (GE §12.5). Stored and named for
                // the chosen model — the one asked for may already be a fallback of it, which
                // ProviderCatalog.effectiveModel would not apply to the chosen one.
                val fallback = result.fallbackModel?.takeIf { it != resolved.model }
                if (fallback != null) access.modelFallback(resolved.auth.provider, resolved.auth.apiKey, resolved.selectedModel, fallback)
                finish(answerId) { message ->
                    val text = result.text.ifBlank { buffer.toString() }.trim()
                    // Grounded text is never shown without its Search Suggestions (Gemini terms).
                    val suggestions = result.searchSuggestionsHtml ?: message.searchSuggestionsHtml
                    val answerNote = listOfNotNull(
                        fallback?.let { GeminiModels.fallbackNote(resolved.selectedModel, it) },
                        AiTexts.WITHOUT_WEB_SEARCH_NOTE.takeIf { result.withoutWebSearch },
                    ).joinToString("\n").ifEmpty { null }
                    when (result.end) {
                        TurnEnd.REFUSED -> message.copy(
                            text = "",
                            sources = emptyList(),
                            status = ChatMessage.Status.FAILED,
                            error = AiError.Refusal(result.refusalCategory),
                            searchSuggestionsHtml = null,
                        )
                        // INCOMPLETE: not produced by the chat engine; shown like a cut-off answer.
                        TurnEnd.TRUNCATED, TurnEnd.INCOMPLETE -> message.copy(
                            text = text,
                            sources = result.sources,
                            status = if (text.isEmpty()) ChatMessage.Status.FAILED else ChatMessage.Status.DONE,
                            error = AiError.Truncated,
                            searchSuggestionsHtml = suggestions.takeIf { text.isNotEmpty() },
                            note = answerNote,
                        )
                        TurnEnd.COMPLETE -> if (text.isEmpty()) {
                            message.copy(
                                status = ChatMessage.Status.FAILED,
                                error = AiError.Unknown("Darauf kam leider keine Antwort. Versuch es noch einmal."),
                                searchSuggestionsHtml = null,
                            )
                        } else {
                            message.copy(
                                text = text,
                                sources = result.sources,
                                status = ChatMessage.Status.DONE,
                                error = null,
                                searchSuggestionsHtml = suggestions,
                                note = answerNote,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // stop(): keep what has streamed so far; drop an empty answer — and a grounded one
                // that ended before its Search Suggestions came (Gemini terms, GE §11).
                withContext(NonCancellable) {
                    val partial = if (withheld(answerId, grounded)) "" else buffer.toString().trim()
                    if (partial.isEmpty()) {
                        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == answerId }) }
                        endTurn()
                    } else {
                        finish(answerId) { it.copy(text = partial, status = ChatMessage.Status.DONE) }
                    }
                }
                throw e
            } catch (e: AiBlockedException) {
                _state.update { it.copy(blocked = e.block) }
                val withheld = withheld(answerId, grounded)
                val partial = if (withheld) "" else buffer.toString().trim()
                finish(answerId) {
                    if (partial.isEmpty()) {
                        it.copy(
                            text = "",
                            sources = if (withheld) emptyList() else it.sources,
                            status = ChatMessage.Status.FAILED,
                            error = AiError.Unknown(AiTexts.blockBody(e.block, resolved.spec)),
                        )
                    } else {
                        it.copy(text = partial, status = ChatMessage.Status.DONE)
                    }
                }
            } catch (e: AiException) {
                // Settings → KI says so before the chat shows the error.
                if (e.error.rejectsKey) access.keyRejected(resolved.auth.provider, resolved.auth.apiKey)
                val withheld = withheld(answerId, grounded)
                finish(answerId) {
                    it.copy(
                        text = if (withheld) "" else buffer.toString().trim(),
                        sources = if (withheld) emptyList() else it.sources,
                        status = ChatMessage.Status.FAILED,
                        error = e.error.namingChosenModel(resolved),
                        errorDetail = ErrorDiagnostics.redact(e.diagnostic, resolved.auth.apiKey),
                    )
                }
            }
        }

        /**
         * An answer that ended early (stopped, failed, limit) is Gemini text grounded in Google
         * Search whose Search Suggestions never came (they come with the last chunk): it may not be
         * shown or kept, nor its grounded sources (GE §11) — "a mid-stream error keeps the text"
         * does not hold for it (docs/AI-PROVIDERS.md §8.2).
         */
        private fun withheld(answerId: String, grounded: AtomicBoolean): Boolean =
            grounded.get() && _state.value.messages.firstOrNull { it.id == answerId }?.searchSuggestionsHtml == null

        /** Applies [change] to the answer, ends the busy state and saves the conversation. */
        private suspend fun finish(answerId: String, change: (ChatMessage) -> ChatMessage) {
            _state.update { s -> s.copy(messages = s.messages.replace(answerId, change)) }
            endTurn()
        }

        /** Saves the conversation, then reports "not busy" — a follow-up can start right away. */
        private suspend fun endTurn() {
            _state.update { it.copy(activity = null) }
            persist()
            release(currentToken)
            _state.update { it.copy(busy = false) }
        }

        private suspend fun restore() {
            val stored = try {
                store.load(context)
            } catch (e: IOException) {
                Log.w(TAG, "Chat history could not be read", e)
                null
            }
            if (stored != null && stored.messages.isNotEmpty()) {
                val messages = stored.messages.mapNotNull { it.toChatMessage() }
                stored.messages.forEach { m -> if (m.apiText != null) apiTexts[m.id] = m.apiText }
                if (stored.context.isNotBlank()) loaded = LoadedChatContext(stored.title, stored.context)
                _state.update { s ->
                    s.copy(
                        title = stored.title.ifBlank { s.title },
                        messages = messages + s.messages,
                        suggestions = if (messages.isEmpty() && s.messages.isEmpty()) suggestions() else emptyList(),
                    )
                }
                if (loaded != null) return
            }
            loadContextQuietly()
        }

        private suspend fun loadContextQuietly() {
            contexts.loadQuietly(context)?.let(::applyContext)
        }

        private fun applyContext(ctx: LoadedChatContext) {
            loaded = ctx
            if (ctx.title.isNotBlank()) _state.update { it.copy(title = ctx.title) }
        }

        private suspend fun persist() {
            val ctx = loaded
            val messages = _state.value.messages.mapNotNull { it.toStored(apiTexts[it.id]) }
            try {
                if (messages.isEmpty()) {
                    store.delete(context)
                } else {
                    store.save(
                        StoredChat(
                            date = context.date,
                            articleKey = context.articleKey,
                            title = ctx?.title ?: _state.value.title,
                            context = ctx?.text.orEmpty(),
                            messages = messages,
                        ),
                    )
                }
            } catch (e: IOException) {
                Log.w(TAG, "Chat history could not be saved", e)
            }
        }

        private fun suggestions(): List<String> =
            AiPrompts.chatSuggestions(article = context.articleKey != null, webSearch = webSearch)
    }

    private suspend fun ChatContextSource.loadQuietly(context: ChatContext): LoadedChatContext? = try {
        load(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Chat context could not be loaded", e)
        null
    }

    private companion object {
        const val TAG = "ChatService"

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * The conversation as the API sees it: only question/answer pairs whose answer is usable
 * (streamed text, not refused, not failed), so roles always alternate. User turns use the exact
 * text that was sent then ([apiTexts]) — the history stays byte-identical and keeps hitting the
 * prompt cache.
 */
internal fun buildApiHistory(messages: List<ChatMessage>, apiTexts: Map<String, String>): List<ApiMessage> {
    val out = ArrayList<ApiMessage>()
    var i = 0
    while (i < messages.size) {
        val question = messages[i]
        val answer = messages.getOrNull(i + 1)
        if (question.role == ChatMessage.Role.USER && answer != null && answer.role == ChatMessage.Role.ASSISTANT) {
            if (answer.isUsableAnswer()) {
                out += ApiMessage(fromUser = true, text = apiTexts[question.id] ?: question.text)
                out += ApiMessage(fromUser = false, text = answer.text.trim())
            }
            i += 2
        } else {
            i += 1
        }
    }
    return out
}

/**
 * The error as the chat shows it: a model refusal of the model the request ran with names the
 * model the user chose when that one was a fallback of it (GE §12.5) — the user never picked the
 * fallback, and the chosen model was refused before, or there would be no fallback.
 */
private fun AiError.namingChosenModel(resolved: Resolution.Ready): AiError =
    if (this is AiError.ModelUnavailable && model == resolved.model && resolved.model != resolved.selectedModel) {
        AiError.ModelUnavailable(resolved.selectedModel)
    } else {
        this
    }

private fun ChatMessage.isUsableAnswer(): Boolean =
    text.isNotBlank() && error !is AiError.Refusal &&
        (status == ChatMessage.Status.DONE || error == AiError.Truncated)

private inline fun List<ChatMessage>.replace(id: String, change: (ChatMessage) -> ChatMessage): List<ChatMessage> =
    map { if (it.id == id) change(it) else it }

private fun ChatMessage.toStored(apiText: String?): StoredChatMessage? {
    // An answer that is still streaming is stored as far as it got (or not at all when empty).
    if (status == ChatMessage.Status.STREAMING && text.isBlank()) return null
    return StoredChatMessage(
        id = id,
        role = if (role == ChatMessage.Role.USER) StoredChatMessage.ROLE_USER else StoredChatMessage.ROLE_ASSISTANT,
        text = text,
        apiText = apiText,
        sources = sources,
        status = if (status == ChatMessage.Status.FAILED) StoredChatMessage.STATUS_FAILED else StoredChatMessage.STATUS_DONE,
        error = error?.let(StoredAiError::of),
        searchSuggestionsHtml = searchSuggestionsHtml,
        note = note,
        provider = provider?.key,
        errorDetail = errorDetail,
    )
}

private fun StoredChatMessage.toChatMessage(): ChatMessage? {
    val role = when (role) {
        StoredChatMessage.ROLE_USER -> ChatMessage.Role.USER
        StoredChatMessage.ROLE_ASSISTANT -> ChatMessage.Role.ASSISTANT
        else -> return null
    }
    return ChatMessage(
        id = id,
        role = role,
        text = text,
        sources = sources,
        status = if (status == StoredChatMessage.STATUS_FAILED) ChatMessage.Status.FAILED else ChatMessage.Status.DONE,
        error = error?.toAiError(),
        searchSuggestionsHtml = searchSuggestionsHtml,
        note = note,
        provider = AiProviderId.fromKey(provider),
        errorDetail = errorDetail,
    )
}

/** `https://www.tagesschau.de/…` → `tagesschau.de`; falls back to the raw text. */
internal fun hostOf(url: String): String {
    val host = try {
        URI(url.trim()).host
    } catch (_: Exception) {
        null
    }
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url.trim().take(40)
}
