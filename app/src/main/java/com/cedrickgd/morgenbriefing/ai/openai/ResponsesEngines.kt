package com.cedrickgd.morgenbriefing.ai.openai

import com.cedrickgd.morgenbriefing.ai.AiBackend
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.AiPrompts
import com.cedrickgd.morgenbriefing.ai.AnswerText
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
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.bool
import com.cedrickgd.morgenbriefing.ai.http.fetch
import com.cedrickgd.morgenbriefing.ai.http.obj
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/*
 * The OpenAI Responses family (docs/AI-PROVIDERS.md §7.1): OpenAI, xAI Grok and the Perplexity
 * Agent API over plain OkHttp + SSE. Runs on every API level (D9).
 */

/** Chat, Live and key checks of one Responses provider. */
internal class ResponsesBackend(
    http: AiHttp,
    dialect: ResponsesDialect,
    retry: RetryPolicy = RetryPolicy(),
) : AiBackend {
    private val streamer = ResponsesStreamer(http, dialect, retry)
    override val chat: ChatEngine = ResponsesChatEngine(dialect, streamer)
    override val live: LiveEngine = ResponsesLiveEngine(dialect, streamer)
    override val keys: KeyChecker = ResponsesKeyChecker(http, dialect)
}

/**
 * "Frag nach": one streamed response. The provider runs its web search server-side within that
 * response, so there is no continuation loop.
 */
internal class ResponsesChatEngine(
    private val dialect: ResponsesDialect,
    private val streamer: ResponsesStreamer,
) : ChatEngine {

    override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        val sources = SourceCollector()
        val text = AnswerText()
        val turn = streamer.run(request.auth, ResponsesRequests.chat(dialect, request), request.model, listener, usage, sources, text)
        if (turn.end == TurnEnd.REFUSED) {
            return ChatTurnResult(text = "", sources = emptyList(), end = TurnEnd.REFUSED, refusalCategory = turn.refusalCategory)
        }
        return ChatTurnResult(text = text.value.trim(), sources = sources.ranked(), end = turn.end)
    }
}

/**
 * "⚡ Live": web search and `publish_articles` in one request (OA §8, OC §4.7, §6.7). The loop
 * (D6) ends as soon as every `publish_articles` call of a response was accepted — no extra "ok"
 * round trip. Rejected calls get the app's problems as `function_call_output` together with
 * every output item of the response (reasoning, searches, calls; `store: false`), a response
 * without a call is reminded once ([AiPrompts.LIVE_NUDGE]). At most [MAX_REQUESTS] requests.
 */
internal class ResponsesLiveEngine(
    private val dialect: ResponsesDialect,
    private val streamer: ResponsesStreamer,
) : LiveEngine {

    override suspend fun research(request: LiveResearchRequest, listener: LiveResearchListener, usage: UsageSink): LiveResearchResult {
        val activity = object : ChatTurnListener {
            override fun onSearch(query: String) = listener.onSearch(query)
            override fun onFetch(url: String) = listener.onFetch(url)
            override fun onToolCall(name: String) = listener.onToolCall(name)
            override fun onSources(sources: List<SourceRef>) = Unit
        }
        val input = mutableListOf(ResponsesRequests.message(dialect, "user", request.userPrompt))
        var published = false
        var reminded = false

        repeat(MAX_REQUESTS) {
            val turn = streamer.run(
                request.auth, ResponsesRequests.live(dialect, request, input), request.model, activity, usage, SourceCollector(), AnswerText(),
            )
            when (turn.end) {
                TurnEnd.REFUSED -> return LiveResearchResult(published, TurnEnd.REFUSED, turn.refusalCategory)
                TurnEnd.TRUNCATED -> return LiveResearchResult(published, TurnEnd.TRUNCATED)
                TurnEnd.INCOMPLETE -> return LiveResearchResult(published, TurnEnd.INCOMPLETE)
                TurnEnd.COMPLETE -> Unit
            }
            val (calls, others) = turn.functionCalls.partition { it.name == LivePublish.TOOL_NAME }
            // Every call in the replayed items needs its output, or the next request is a 400.
            val unknown = others.map { ResponsesRequests.functionOutput(it.callId, "Unbekanntes Werkzeug: ${it.name}") }
            if (calls.isEmpty()) {
                if (published) return LiveResearchResult(true, TurnEnd.COMPLETE)
                if (reminded) return LiveResearchResult(false, TurnEnd.INCOMPLETE)
                reminded = true
                input += turn.replayItems
                input += unknown
                input += ResponsesRequests.message(dialect, "user", AiPrompts.LIVE_NUDGE)
                return@repeat
            }
            val replies = calls.map { listener.onPublish(it.arguments) }
            if (replies.any { it.accepted }) published = true
            if (replies.all { it.accepted }) return LiveResearchResult(true, TurnEnd.COMPLETE)
            input += turn.replayItems
            input += calls.zip(replies).map { (call, reply) -> ResponsesRequests.functionOutput(call.callId, reply.message) }
            input += unknown
        }
        // Request budget used up while the model was still correcting.
        return LiveResearchResult(published, TurnEnd.INCOMPLETE)
    }

    private companion object {
        /** Requests per run: the research plus corrections and the one reminder. */
        const val MAX_REQUESTS = 6
    }
}

/**
 * Key checks without tokens where the provider allows it (§7.1): OpenAI lists the models, xAI
 * reads the key's own status (blocked, disabled, team without credit), Perplexity has no free
 * endpoint and answers one tiny request (its usage is recorded).
 */
internal class ResponsesKeyChecker(
    private val http: AiHttp,
    private val dialect: ResponsesDialect,
) : KeyChecker {

    override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome =
        KeyCheckOutcome(checkKey(auth, usage))

    private suspend fun checkKey(auth: ProviderAuth, usage: UsageSink): AiKeyCheck {
        if (auth.apiKey.isNullOrBlank()) return AiKeyCheck.INVALID
        val base = ResponsesRequests.baseUrl(dialect, auth)
        val headers = ResponsesRequests.headers(auth)
        return try {
            when (dialect) {
                ResponsesDialect.OPENAI -> verdict(http.fetch(http.get("$base/models", headers)).code)
                ResponsesDialect.XAI -> {
                    val reply = http.fetch(http.get("$base/api-key", headers))
                    if (reply.code !in 200..299) return verdict(reply.code)
                    val key = parse(reply.body)
                    when {
                        key?.bool("api_key_blocked") == true || key?.bool("api_key_disabled") == true -> AiKeyCheck.INVALID
                        key?.bool("team_blocked") == true -> AiKeyCheck.OK_NO_CREDIT
                        else -> AiKeyCheck.OK
                    }
                }
                ResponsesDialect.PERPLEXITY -> {
                    val reply = http.fetch(http.post("$base${dialect.path}", ResponsesRequests.perplexityKeyCheck(), headers))
                    if (reply.code in 200..299) recordCheck(parse(reply.body), usage)
                    verdict(reply.code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AiKeyCheck.NETWORK
        }
    }

    /** 2xx and 429 (authenticated, just busy) prove the key; 401/403 refuse it; 402 means no credit. */
    private fun verdict(code: Int): AiKeyCheck = when (code) {
        in 200..299, 429 -> AiKeyCheck.OK
        401, 403 -> AiKeyCheck.INVALID
        402 -> AiKeyCheck.OK_NO_CREDIT
        else -> AiKeyCheck.NETWORK
    }

    private suspend fun recordCheck(response: JsonObject?, usage: UsageSink) {
        val tokens = response?.obj("usage") ?: return
        try {
            usage.record(dialect.provider, response.str("model") ?: ResponsesRequests.PERPLEXITY_CHECK_MODEL, dialect.usageOf(tokens))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The check result counts, not its bookkeeping.
        }
    }

    private fun parse(body: String): JsonObject? = try {
        JSON.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
