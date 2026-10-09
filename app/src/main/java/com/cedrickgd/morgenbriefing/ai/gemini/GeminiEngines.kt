package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiBackend
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.AnswerText
import com.cedrickgd.morgenbriefing.ai.ChatEngine
import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.ChatTurnResult
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.KeyChecker
import com.cedrickgd.morgenbriefing.ai.LiveEngine
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.ApiErrorBody
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.fetch
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/*
 * Google Gemini (docs/AI-PROVIDERS.md §7.2, docs/providers/gemini.md): chat with Google Search
 * over `streamGenerateContent` and plain OkHttp + SSE, on every API level (D9). No Live: Google's
 * grounding terms forbid storing grounded results as articles (D4).
 */

/** Chat and key checks of Gemini; [live] is null (D4). */
internal class GeminiBackend(
    http: AiHttp,
    retry: RetryPolicy = RetryPolicy(),
) : AiBackend {
    override val chat: ChatEngine = GeminiChatEngine(GeminiStreamer(http, retry))
    override val live: LiveEngine? = null
    override val keys: KeyChecker = GeminiKeyChecker(http)
}

/**
 * "Frag nach": one streamed answer. Google Search runs server-side within it and is only asked
 * for when the chat offers web search and the key is not on the free tier ("Kostenlos": free
 * keys cannot search on Gemini 3.x, G1). A grounded answer carries Google's Search Suggestions.
 *
 * Safety net for a key that is set to "Mit Abrechnung" but turns out to be a free one: when
 * Gemini refuses the search itself before streaming, the same question is sent once more
 * without it ([ChatTurnResult.withoutWebSearch], the chat explains it), and this key — kept
 * only as a hash, for the life of the process — is not offered the search again.
 */
internal class GeminiChatEngine(private val streamer: GeminiStreamer) : ChatEngine {
    /** SHA-256 of keys whose project cannot use Google Search. */
    private val searchUnavailable: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        val wanted = request.webSearch && !request.auth.freeTier
        val keyHash = request.auth.apiKey?.let(::hash)
        val known = keyHash != null && keyHash in searchUnavailable
        if (wanted && !known) {
            try {
                return turn(request, search = true, listener, usage)
            } catch (_: GeminiSearchUnavailable) {
                keyHash?.let(searchUnavailable::add)
            }
        }
        return turn(request, search = false, listener, usage).let { if (wanted) it.copy(withoutWebSearch = true) else it }
    }

    private suspend fun turn(request: ChatTurnRequest, search: Boolean, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        val sources = SourceCollector()
        val text = AnswerText()
        val body = GeminiRequests.chat(request, search)
        val turn = streamer.run(request.auth, request.model, body, search, listener, usage, sources, text)
        if (turn.end == TurnEnd.REFUSED) {
            return ChatTurnResult(text = "", sources = emptyList(), end = TurnEnd.REFUSED, refusalCategory = turn.refusalCategory)
        }
        return ChatTurnResult(
            text = text.value.trim(),
            sources = sources.ranked(),
            end = turn.end,
            searchSuggestionsHtml = turn.searchSuggestionsHtml,
        )
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Key check without tokens (GE §13): `GET {base}/models/{model}`. It proves the key, not the
 * tariff: whether the project may search shows in the chat (see [GeminiChatEngine]).
 *
 * * 200 → OK; 404 → OK too (the key works, only the model is unknown);
 * * 400 `API_KEY_INVALID` (classic `AIza…` keys), 401 (`AQ.…` keys), 403 → INVALID;
 * * 400 `FAILED_PRECONDITION` (the free tier is not offered in the country) and 402 (prepaid
 *   credit used up) → OK_NO_CREDIT: the key is fine, but needs billing;
 * * any other 400 (e.g. `INVALID_ARGUMENT` for a typed model id) → OK, like 404: the key got
 *   through, the model is the problem — the same line as the chat's error mapping (§7.2, §8.1);
 * * 429 → OK (authenticated, just busy); 5xx and connection problems → NETWORK.
 */
internal class GeminiKeyChecker(private val http: AiHttp) : KeyChecker {

    override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome {
        if (auth.apiKey.isNullOrBlank()) return KeyCheckOutcome(AiKeyCheck.INVALID)
        val verdict = try {
            val reply = http.fetch(http.get(GeminiRequests.modelInfoUrl(auth, model), GeminiRequests.headers(auth)))
            verdict(reply.code, reply.body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            AiKeyCheck.NETWORK
        } catch (e: AiException) {
            // Nothing was asked: a key that cannot travel in a header, or an unusable base URL (debug override).
            if (e.error == AiError.InvalidKey) AiKeyCheck.INVALID else AiKeyCheck.NETWORK
        }
        return KeyCheckOutcome(verdict)
    }

    private fun verdict(code: Int, body: String): AiKeyCheck = when (code) {
        in 200..299, 404, 429 -> AiKeyCheck.OK
        400 -> {
            val error = ApiErrorBody.parse(body)
            when {
                error.isGeminiKeyInvalid() -> AiKeyCheck.INVALID
                error.status == "FAILED_PRECONDITION" -> AiKeyCheck.OK_NO_CREDIT
                else -> AiKeyCheck.OK
            }
        }
        401, 403 -> AiKeyCheck.INVALID
        402 -> AiKeyCheck.OK_NO_CREDIT
        else -> AiKeyCheck.NETWORK
    }
}
