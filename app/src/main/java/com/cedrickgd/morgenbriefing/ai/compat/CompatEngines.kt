package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.AiBackend
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
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
import com.cedrickgd.morgenbriefing.ai.TokenUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.bool
import com.cedrickgd.morgenbriefing.ai.http.fetch
import com.cedrickgd.morgenbriefing.ai.http.objects
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/*
 * The OpenAI-compatible Chat Completions family (docs/AI-PROVIDERS.md §7.3): OpenRouter (chat with
 * web search and Live), Mistral, DeepSeek and the own server (chat without web search) over
 * plain OkHttp + SSE. Runs on every API level (D9).
 */

/** Chat, Live (OpenRouter only) and key checks of one Chat Completions provider. */
internal class CompatBackend(
    http: AiHttp,
    dialect: CompatDialect,
    retry: RetryPolicy = RetryPolicy(),
) : AiBackend {
    private val streamer = CompatStreamer(http, dialect, retry)
    override val chat: ChatEngine = CompatChatEngine(dialect, streamer)
    override val live: LiveEngine? = if (dialect.live) CompatLiveEngine(dialect, streamer) else null
    override val keys: KeyChecker = CompatKeyChecker(http, dialect)
}

/**
 * "Frag nach": one streamed answer (OpenRouter runs its search server-side within it). The own
 * server needs a model: without one nothing is sent.
 */
internal class CompatChatEngine(
    private val dialect: CompatDialect,
    private val streamer: CompatStreamer,
) : ChatEngine {

    override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        if (request.model.isBlank()) throw AiException(AiError.Unknown(NO_MODEL_TEXT))
        val sources = SourceCollector()
        val text = AnswerText()
        val turn = streamer.run(request.auth, request.model, listener, usage, sources, text) { maxToolCalls ->
            CompatRequests.chat(dialect, request, maxToolCalls)
        }
        if (turn.end == TurnEnd.REFUSED) {
            return ChatTurnResult(text = "", sources = emptyList(), end = TurnEnd.REFUSED, refusalCategory = turn.refusalCategory)
        }
        var answer = text.value.trim()
        if (dialect.markdownLinkSources) {
            val (plain, links) = MarkdownLinks.extract(answer)
            answer = plain
            // Annotations, when OpenRouter sends them, are the better sources; links fill in otherwise.
            if (!turn.citedByProvider && links.isNotEmpty()) {
                links.forEach { sources.addCited(it.url, it.label) }
                listener.onSources(sources.ranked())
            }
        }
        return ChatTurnResult(text = answer, sources = sources.ranked(), end = turn.end)
    }

    internal companion object {
        /** The own server without a chosen model (the settings pick one from the server's list). */
        const val NO_MODEL_TEXT = "Wähl in den Einstellungen ein Modell für deinen Server aus."
    }
}

/**
 * "⚡ Live" over OpenRouter (OC §7.8): its web search server tool and `publish_articles` in one
 * request; OpenRouter runs the search, the function call comes back to the app. The loop (D6)
 * ends as soon as every `publish_articles` call of a response was accepted. Rejected calls go
 * back as the assistant's tool calls (with OpenRouter's `reasoning_details` of that response, so
 * Gemini 3 and Claude upstreams accept the replayed call) plus one `tool` message per call; a
 * response without a call is reminded once ([AiPrompts.LIVE_NUDGE]). Calls of other tools are
 * ignored (OC §7.8: server tool calls may or may not show up). At most [MAX_REQUESTS] requests.
 */
internal class CompatLiveEngine(
    private val dialect: CompatDialect,
    private val streamer: CompatStreamer,
) : LiveEngine {

    override suspend fun research(request: LiveResearchRequest, listener: LiveResearchListener, usage: UsageSink): LiveResearchResult {
        val activity = object : ChatTurnListener {
            override fun onSearch(query: String) = listener.onSearch(query)
            override fun onFetch(url: String) = listener.onFetch(url)
            override fun onToolCall(name: String) {
                if (name == LivePublish.TOOL_NAME) listener.onToolCall(name)
            }
            override fun onSources(sources: List<SourceRef>) = Unit
        }
        val messages = mutableListOf(CompatRequests.message("user", request.userPrompt))
        var published = false
        var reminded = false

        repeat(MAX_REQUESTS) {
            val text = AnswerText()
            val turn = streamer.run(request.auth, request.model, activity, usage, SourceCollector(), text) { maxToolCalls ->
                CompatRequests.live(dialect, request, messages, maxToolCalls)
            }
            when (turn.end) {
                TurnEnd.REFUSED -> return LiveResearchResult(published, TurnEnd.REFUSED, turn.refusalCategory)
                TurnEnd.TRUNCATED -> return LiveResearchResult(published, TurnEnd.TRUNCATED)
                TurnEnd.INCOMPLETE -> return LiveResearchResult(published, TurnEnd.INCOMPLETE)
                TurnEnd.COMPLETE -> Unit
            }
            val calls = turn.toolCalls.filter { it.name == LivePublish.TOOL_NAME }
            if (calls.isEmpty()) {
                if (published) return LiveResearchResult(true, TurnEnd.COMPLETE)
                if (reminded) return LiveResearchResult(false, TurnEnd.INCOMPLETE)
                reminded = true
                text.value.trim().takeIf { it.isNotEmpty() }?.let { messages += CompatRequests.message("assistant", it) }
                messages += CompatRequests.message("user", AiPrompts.LIVE_NUDGE)
                return@repeat
            }
            val replies = calls.map { listener.onPublish(it.arguments) }
            if (replies.any { it.accepted }) published = true
            if (replies.all { it.accepted }) return LiveResearchResult(true, TurnEnd.COMPLETE)
            val reasoning = turn.reasoningDetails.takeIf { dialect.replaysReasoningDetails }
            messages += CompatRequests.assistantToolCalls(text.value.trim(), calls, reasoning)
            messages += calls.zip(replies).map { (call, reply) -> CompatRequests.toolResult(call.id, reply.message) }
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
 * Key checks without tokens (§7.3): Mistral lists the models, OpenRouter reads the key's own
 * status, DeepSeek its balance. The own server is a connection test (§7.6): its model list
 * proves an OpenAI-compatible API and fills [KeyCheckOutcome.serverModels].
 */
internal class CompatKeyChecker(
    private val http: AiHttp,
    private val dialect: CompatDialect,
) : KeyChecker {

    override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome {
        if (dialect == CompatDialect.CUSTOM) return checkServer(auth)
        if (auth.apiKey.isNullOrBlank()) return KeyCheckOutcome(AiKeyCheck.INVALID)
        return try {
            val base = CompatRequests.baseUrl(dialect, auth)
            val headers = CompatRequests.headers(dialect, auth)
            val check = when (dialect.keyCheck) {
                KeyCheckKind.MODELS -> verdict(http.fetch(http.get("$base/models", headers)).code)
                KeyCheckKind.OPENROUTER_KEY -> verdict(http.fetch(http.get("$base/key", headers)).code)
                KeyCheckKind.DEEPSEEK_BALANCE -> {
                    val reply = http.fetch(http.get("$base/user/balance", headers))
                    if (reply.code in 200..299 && parse(reply.body)?.bool("is_available") == false) AiKeyCheck.OK_NO_CREDIT else verdict(reply.code)
                }
            }
            KeyCheckOutcome(check)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            KeyCheckOutcome(AiKeyCheck.NETWORK)
        } catch (e: AiException) {
            // Nothing was asked: a key that cannot travel in a header, or an unusable base URL.
            KeyCheckOutcome(if (e.error == AiError.InvalidKey) AiKeyCheck.INVALID else AiKeyCheck.NETWORK)
        }
    }

    /** 2xx and 429 (authenticated, just busy) prove the key; 401/403 refuse it; 402 means no credit (or payment method). */
    private fun verdict(code: Int): AiKeyCheck = when (code) {
        in 200..299, 429 -> AiKeyCheck.OK
        401, 403 -> AiKeyCheck.INVALID
        402 -> AiKeyCheck.OK_NO_CREDIT
        else -> AiKeyCheck.NETWORK
    }

    /**
     * `GET {base}/models` (Bearer only with a key): 200 with `data[]` → OK and the model ids;
     * 401/403 → INVALID; plain http to a host outside the allowlist → CLEARTEXT_BLOCKED (checked
     * up front: Android would refuse the connection anyway); a failed TLS setup (a self-signed
     * certificate — the app trusts only public CAs and ISRG — or a server that only speaks http
     * behind an https:// address) → SERVER_CERTIFICATE, so the user is not sent to check the
     * network; no connection → SERVER_UNREACHABLE; 5xx → SERVER_UNREACHABLE (it answers, but not
     * usefully); 404 or anything that is not a model list → NOT_AN_AI_SERVER.
     */
    private suspend fun checkServer(auth: ProviderAuth): KeyCheckOutcome {
        if (CustomServer.issue(auth.baseUrl) == CustomUrlIssue.CLEARTEXT_HOST) return KeyCheckOutcome(AiKeyCheck.CLEARTEXT_BLOCKED)
        val base = CustomServer.normalize(auth.baseUrl) ?: return KeyCheckOutcome(AiKeyCheck.SERVER_UNREACHABLE)
        return try {
            val reply = http.fetch(http.get("$base/models", CompatRequests.headers(dialect, auth)))
            when (reply.code) {
                in 200..299 -> modelIds(reply.body)?.let { KeyCheckOutcome(AiKeyCheck.OK, it) } ?: KeyCheckOutcome(AiKeyCheck.NOT_AN_AI_SERVER)
                401, 403 -> KeyCheckOutcome(AiKeyCheck.INVALID)
                in 500..599 -> KeyCheckOutcome(AiKeyCheck.SERVER_UNREACHABLE)
                else -> KeyCheckOutcome(AiKeyCheck.NOT_AN_AI_SERVER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnknownServiceException) {
            KeyCheckOutcome(AiKeyCheck.CLEARTEXT_BLOCKED)
        } catch (_: SSLException) {
            // The first contact of the test: a TLS failure here is the certificate (or no TLS at all).
            KeyCheckOutcome(AiKeyCheck.SERVER_CERTIFICATE)
        } catch (_: IOException) {
            // Refused, unknown name, timeout: the server cannot be reached as typed.
            KeyCheckOutcome(AiKeyCheck.SERVER_UNREACHABLE)
        } catch (e: AiException) {
            // A key that cannot travel in a header (nothing was sent), or an unusable address.
            KeyCheckOutcome(if (e.error == AiError.InvalidKey) AiKeyCheck.INVALID else AiKeyCheck.SERVER_UNREACHABLE)
        }
    }

    private fun parse(body: String): JsonObject? = try {
        JSON.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** `data[].id` of an OpenAI-style model list; null when [body] is none (an empty list is a list). */
    private fun modelIds(body: String): List<String>? {
        val list = parse(body) ?: return null
        if (list["data"] !is JsonArray) return null
        return list.objects("data").mapNotNull { it.str("id")?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** The own server's model ids for the model picker ("Vom Server laden", docs/AI-PROVIDERS.md §10.4). */
internal object CompatModels {
    /** `GET {base}/models` of the own server at [baseUrl] (with [key] when set); empty on any failure. */
    suspend fun list(http: AiHttp, baseUrl: String, key: String?): List<String> {
        val auth = ProviderAuth(AiProviderId.CUSTOM, key?.trim()?.takeIf { it.isNotEmpty() }, baseUrl = baseUrl)
        val outcome = CompatKeyChecker(http, CompatDialect.CUSTOM).check(auth, "", NO_USAGE)
        return if (outcome.check == AiKeyCheck.OK) outcome.serverModels else emptyList()
    }

    /** A model list costs nothing. */
    private val NO_USAGE = object : UsageSink {
        override suspend fun beforeRequest() = Unit
        override suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage) = Unit
    }
}
