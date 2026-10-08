package com.cedrickgd.morgenbriefing.ai

import android.util.Log
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * restored from [store] after a restart. Streams answers through [engine]; a null engine means
 * the device cannot run the SDK (API < 26) and every session reports
 * [AiBlock.UNSUPPORTED_DEVICE].
 *
 * @param keyRejected told the key a turn used when Anthropic refused it ([AiError.InvalidKey]);
 *   must not throw.
 */
internal class ChatServiceImpl(
    private val scope: CoroutineScope,
    private val settings: Flow<AppSettings>,
    private val apiKey: suspend () -> String?,
    private val usageSink: UsageSink,
    private val usageState: StateFlow<UsageSnapshot>,
    private val engine: ChatEngine?,
    private val store: ChatStore,
    private val contexts: ChatContextSource,
    private val clock: Clock = Clock.system(BERLIN),
    private val keyRejected: suspend (apiKey: String) -> Unit = {},
) : ChatService {
    private val sessions = HashMap<ChatContext, ChatSessionImpl>()

    @Synchronized
    override fun session(context: ChatContext): ChatSession =
        sessions.getOrPut(context) { ChatSessionImpl(context) }

    internal inner class ChatSessionImpl(private val context: ChatContext) : RetryableChatSession {
        private val _state = MutableStateFlow(
            ChatUiState(
                title = if (context.articleKey == null) AiPrompts.BRIEF_TITLE else "",
                suggestions = suggestions(),
                blocked = if (engine == null) AiBlock.UNSUPPORTED_DEVICE else null,
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
                aiBlockFlow(engine != null, settings, usageState).collect { block ->
                    _state.update { it.copy(blocked = block) }
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
            val engine = engine ?: return
            val key = apiKey()
            if (key == null) {
                _state.update { it.copy(blocked = AiBlock.NO_KEY) }
                return
            }
            try {
                usageSink.beforeRequest()
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
            val placeholder = ChatMessage(id = answerId, role = ChatMessage.Role.ASSISTANT, text = "", status = ChatMessage.Status.STREAMING)
            apiTexts[user.id] = apiText
            _state.update {
                it.copy(messages = it.messages + user + placeholder, busy = true, activity = null, suggestions = emptyList())
            }
            val buffer = StringBuilder()
            val listener = object : ChatTurnListener {
                override fun onText(delta: String) {
                    buffer.append(delta)
                    val text = buffer.toString()
                    _state.update { s -> s.copy(messages = s.messages.replace(answerId) { it.copy(text = text) }, activity = null) }
                }

                override fun onSources(sources: List<SourceRef>) {
                    _state.update { s -> s.copy(messages = s.messages.replace(answerId) { it.copy(sources = sources) }) }
                }

                override fun onSearch(query: String) {
                    _state.update { it.copy(activity = "Suche im Netz: ${query.trim().take(80)}") }
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

                val model = settings.first().aiModel.apiId
                val request = ChatTurnRequest(
                    apiKey = key,
                    model = model,
                    systemPrompt = AiPrompts.CHAT_SYSTEM,
                    context = ctx.text,
                    history = history + ApiMessage(fromUser = true, text = apiText),
                )
                val result = engine.answer(request, listener, usageSink)
                finish(answerId) { message ->
                    val text = result.text.ifBlank { buffer.toString() }.trim()
                    when (result.end) {
                        TurnEnd.REFUSED -> message.copy(
                            text = "",
                            sources = emptyList(),
                            status = ChatMessage.Status.FAILED,
                            error = AiError.Refusal(result.refusalCategory),
                        )
                        // INCOMPLETE: not produced by the chat engine; shown like a cut-off answer.
                        TurnEnd.TRUNCATED, TurnEnd.INCOMPLETE -> message.copy(
                            text = text,
                            sources = result.sources,
                            status = if (text.isEmpty()) ChatMessage.Status.FAILED else ChatMessage.Status.DONE,
                            error = AiError.Truncated,
                        )
                        TurnEnd.COMPLETE -> if (text.isEmpty()) {
                            message.copy(status = ChatMessage.Status.FAILED, error = AiError.Unknown("Darauf kam leider keine Antwort. Versuch es noch einmal."))
                        } else {
                            message.copy(text = text, sources = result.sources, status = ChatMessage.Status.DONE, error = null)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // stop(): keep what has streamed so far; drop an empty answer.
                withContext(NonCancellable) {
                    val partial = buffer.toString().trim()
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
                val partial = buffer.toString().trim()
                finish(answerId) {
                    if (partial.isEmpty()) {
                        it.copy(status = ChatMessage.Status.FAILED, error = AiError.Unknown(AiTexts.blockBody(e.block)))
                    } else {
                        it.copy(text = partial, status = ChatMessage.Status.DONE)
                    }
                }
            } catch (e: AiException) {
                // Settings → KI says so before the chat shows the error.
                if (e.error == AiError.InvalidKey) keyRejected(key)
                finish(answerId) { it.copy(text = buffer.toString().trim(), status = ChatMessage.Status.FAILED, error = e.error) }
            }
        }

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
            if (context.articleKey == null) AiPrompts.BRIEF_SUGGESTIONS else AiPrompts.ARTICLE_SUGGESTIONS
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
