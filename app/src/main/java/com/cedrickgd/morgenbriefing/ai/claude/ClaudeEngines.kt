package com.cedrickgd.morgenbriefing.ai.claude

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.ChatEngine
import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.ChatTurnResult
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.KeyChecker
import com.cedrickgd.morgenbriefing.ai.LiveEngine
import com.cedrickgd.morgenbriefing.ai.LivePublish
import com.cedrickgd.morgenbriefing.ai.LiveResearchListener
import com.cedrickgd.morgenbriefing.ai.LiveResearchRequest
import com.cedrickgd.morgenbriefing.ai.LiveResearchResult
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TokenUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import com.cedrickgd.morgenbriefing.data.model.Topics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * "Frag nach": one streamed answer. A `pause_turn` (the server-side tool loop paused) is
 * continued by sending the same conversation with the assistant content so far appended —
 * no extra user message — up to [MAX_CONTINUATIONS] times.
 */
internal class ClaudeChatEngine(private val clients: ClaudeClients) : ChatEngine {

    override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        val client = clients.forAuth(request.auth)
        val params = ClaudeRequests.chat(request.model, request.systemPrompt, request.context, webSearch = request.webSearch)
        val messages = ClaudeRequests.toParams(request.history).toMutableList()
        val sources = SourceCollector()
        val text = AnswerText()
        val callbacks = object : StreamCallbacks {
            override fun onText(delta: String) = listener.onText(delta)
            override fun onSources(sources: List<SourceRef>) = listener.onSources(sources)
            override fun onSearch(query: String) = listener.onSearch(query)
            override fun onFetch(url: String) = listener.onFetch(url)
        }

        var requests = 0
        while (true) {
            usage.beforeRequest()
            requests++
            val turn = ClaudeStreamer.run(client, params.messages(messages).build(), request.model, callbacks, usage, sources, text)
            when (turn.stopReason) {
                StopReason.PAUSE_TURN -> {
                    val paused = turn.message?.let(ClaudeRequests::assistantTurn)
                    if (paused == null || requests > MAX_CONTINUATIONS) return result(text, sources, TurnEnd.COMPLETE)
                    messages += paused
                }
                StopReason.MAX_TOKENS -> return result(text, sources, TurnEnd.TRUNCATED)
                StopReason.REFUSAL -> return ChatTurnResult(text = "", sources = emptyList(), end = TurnEnd.REFUSED, refusalCategory = turn.refusalCategory)
                // end_turn, stop_sequence, a stray tool_use (no client tools here) or an unknown reason.
                else -> return result(text, sources, TurnEnd.COMPLETE)
            }
        }
    }

    private fun result(text: AnswerText, sources: SourceCollector, end: TurnEnd) =
        ChatTurnResult(text = text.value.trim(), sources = sources.ranked(), end = end)

    private companion object {
        const val MAX_CONTINUATIONS = 4
    }
}

/**
 * "⚡ Live": a research conversation with web search/fetch and the client tool
 * `publish_articles`. Each tool call is answered with the app's verdict ("ok" or the problems
 * to fix); the conversation runs until the model ends its turn. If it ends without having
 * published, it is reminded once; a run that still does not publish, or that uses up
 * [MAX_REQUESTS], ends [TurnEnd.INCOMPLETE].
 */
internal class ClaudeLiveEngine(private val clients: ClaudeClients) : LiveEngine {

    override suspend fun research(request: LiveResearchRequest, listener: LiveResearchListener, usage: UsageSink): LiveResearchResult {
        val client = clients.forAuth(request.auth)
        val params = ClaudeRequests.live(request.model, request.systemPrompt, Topics.ALL.map { it.id })
        val messages = mutableListOf<MessageParam>(ClaudeRequests.userText(request.userPrompt))
        val callbacks = object : StreamCallbacks {
            override fun onSearch(query: String) = listener.onSearch(query)
            override fun onFetch(url: String) = listener.onFetch(url)
            override fun onToolCall(name: String) = listener.onToolCall(name)
        }
        var published = false
        var reminded = false

        // The turn is over: complete only if the model got to publish (possibly an empty list).
        fun ended() = LiveResearchResult(published, if (published) TurnEnd.COMPLETE else TurnEnd.INCOMPLETE)

        repeat(MAX_REQUESTS) {
            usage.beforeRequest()
            val turn = ClaudeStreamer.run(client, params.messages(messages).build(), request.model, callbacks, usage, SourceCollector(), AnswerText())
            val assistant = turn.message?.let(ClaudeRequests::assistantTurn)
            when (turn.stopReason) {
                StopReason.PAUSE_TURN -> {
                    messages += assistant ?: return ended()
                }
                StopReason.TOOL_USE -> {
                    // Run and answer exactly the calls the echoed turn contains: never a call it
                    // dropped (a refused attempt's, before a fallback marker) — its tool_result
                    // would be an orphan (400) — and never leave one of its calls unanswered.
                    val echoed = assistant?.let(ClaudeRequests::toolUseIds).orEmpty()
                    val calls = turn.toolCalls.filter { it.id in echoed }
                    if (assistant == null || calls.isEmpty()) return ended()
                    val results = ArrayList<ToolResultBlockParam>()
                    (echoed - calls.map { it.id }.toSet()).forEach { id ->
                        results += ClaudeRequests.toolResult(id, "Dieser Aufruf kam nicht vollständig an.", isError = true)
                    }
                    for (call in calls) {
                        results += if (call.name == LivePublish.TOOL_NAME) {
                            val reply = listener.onPublish(call.inputJson)
                            if (reply.accepted) published = true
                            ClaudeRequests.toolResult(call.id, reply.message, isError = !reply.accepted)
                        } else {
                            ClaudeRequests.toolResult(call.id, "Unbekanntes Werkzeug: ${call.name}", isError = true)
                        }
                    }
                    messages += assistant
                    messages += ClaudeRequests.toolResults(results)
                }
                StopReason.MAX_TOKENS -> return LiveResearchResult(published, TurnEnd.TRUNCATED)
                StopReason.REFUSAL -> return LiveResearchResult(published, TurnEnd.REFUSED, turn.refusalCategory)
                else -> {
                    if (published || reminded || assistant == null) return ended()
                    reminded = true
                    messages += assistant
                    messages += ClaudeRequests.userText(AiPrompts.LIVE_NUDGE)
                }
            }
        }
        // Request budget used up while the model was still working.
        return LiveResearchResult(published, TurnEnd.INCOMPLETE)
    }

    private companion object {
        /** Requests per run, counting pause_turn continuations and tool round trips. */
        const val MAX_REQUESTS = 10
    }
}

/**
 * One tiny request with the given key (and workspace id) against the chosen model: 401/403 →
 * INVALID, a workspace problem → WORKSPACE_REQUIRED / WORKSPACE_INVALID, an empty balance →
 * OK_NO_CREDIT, no connection → NETWORK. Any other 4xx (e.g. an unknown model) still proves
 * the key authenticated.
 */
internal class ClaudeKeyChecker(private val clients: ClaudeClients) : KeyChecker {

    override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome =
        KeyCheckOutcome(checkKey(auth, model, usage))

    private suspend fun checkKey(auth: ProviderAuth, model: String, usage: UsageSink): AiKeyCheck {
        if (auth.apiKey.isNullOrBlank()) return AiKeyCheck.INVALID
        val client = clients.create(auth)
        return try {
            val message = runInterruptible(Dispatchers.IO) { client.messages().create(ClaudeRequests.keyCheck(model)) }
            runCatching {
                val u = message.usage()
                usage.record(
                    AiProviderId.ANTHROPIC,
                    message.model().asString(),
                    TokenUsage(
                        inputTokens = u.inputTokens(),
                        outputTokens = u.outputTokens(),
                        cacheWriteTokens = u.cacheCreationInputTokens().orElse(0L),
                        cacheReadTokens = u.cacheReadInputTokens().orElse(0L),
                    ),
                )
            }
            AiKeyCheck.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            AiKeyCheck.INVALID
        } catch (e: PermissionDeniedException) {
            AiKeyCheck.INVALID
        } catch (e: RateLimitException) {
            // Authenticated, just busy: the key works.
            AiKeyCheck.OK
        } catch (e: AnthropicServiceException) {
            when (ClaudeErrors.keyVerdict(e)) {
                // Bug fix: a 400 about the workspace is no proof that the key works.
                ClaudeErrors.KeyVerdict.WORKSPACE_REQUIRED -> AiKeyCheck.WORKSPACE_REQUIRED
                ClaudeErrors.KeyVerdict.WORKSPACE_INVALID -> AiKeyCheck.WORKSPACE_INVALID
                ClaudeErrors.KeyVerdict.NO_CREDIT -> AiKeyCheck.OK_NO_CREDIT
                // Other 4xx mean the key was accepted; 5xx says nothing about it.
                ClaudeErrors.KeyVerdict.OTHER -> if (e.statusCode() in 500..599) AiKeyCheck.NETWORK else AiKeyCheck.OK
            }
        } catch (e: AnthropicIoException) {
            AiKeyCheck.NETWORK
        } catch (e: AnthropicRetryableException) {
            AiKeyCheck.NETWORK
        } catch (e: IOException) {
            AiKeyCheck.NETWORK
        } catch (e: RuntimeException) {
            if (ClaudeErrors.map(e).let { it is AiException && it.error == AiError.Network }) AiKeyCheck.NETWORK else AiKeyCheck.INVALID
        } finally {
            runCatching { client.close() }
        }
    }
}
