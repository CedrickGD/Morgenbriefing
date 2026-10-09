package com.cedrickgd.morgenbriefing.ai.gemini

import android.util.Log
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
import com.cedrickgd.morgenbriefing.ai.GeminiProblem
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.KeyChecker
import com.cedrickgd.morgenbriefing.ai.LiveEngine
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.ErrorDiagnostics
import com.cedrickgd.morgenbriefing.ai.http.ProviderErrors
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.SseEvent
import com.cedrickgd.morgenbriefing.ai.http.array
import com.cedrickgd.morgenbriefing.ai.http.fetch
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.ai.provider.GeminiModels
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.security.MessageDigest

/*
 * Google Gemini (docs/AI-PROVIDERS.md §7.2, docs/providers/gemini.md): chat with Google Search
 * over `streamGenerateContent` and plain OkHttp + SSE, on every API level (D9). No Live: Google's
 * grounding terms forbid storing grounded results as articles (D4).
 */

/**
 * Chat and key checks of Gemini; [live] is null (D4). [searchMemo]: the keys that turned out not
 * to search, owned by the AI facade so that saving the tariff or a key can forget them.
 */
internal class GeminiBackend(
    http: AiHttp,
    retry: RetryPolicy = RetryPolicy(),
    searchMemo: GeminiSearchMemo = GeminiSearchMemo(),
) : AiBackend {
    override val chat: ChatEngine = GeminiChatEngine(GeminiStreamer(http, retry), searchMemo)
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
 * without it ([ChatTurnResult.withoutWebSearch], the chat explains it), and [searchMemo] keeps
 * this key from being offered the search again — until the tariff or a Gemini key is saved
 * ([GeminiSearchMemo.forget]). Without the search the model also gets the system prompt without
 * it ([AiPrompts.chatSystem] `false`: no web search to use, no sources to name), not the one
 * that tells it to search and cite.
 *
 * Model fallback (GE §12.5): when Gemini refuses the requested model for the key before
 * streaming ([GeminiModelUnavailable]), the next model of [GeminiModels.CHAIN] is tried, each at
 * most once; the one that answers is named in [ChatTurnResult.fallbackModel] (the chat keeps it
 * for this key and says so). When none answers, the requested model's refusal is reported.
 */
internal class GeminiChatEngine(
    private val streamer: GeminiStreamer,
    private val searchMemo: GeminiSearchMemo = GeminiSearchMemo(),
) : ChatEngine {

    override suspend fun answer(request: ChatTurnRequest, listener: ChatTurnListener, usage: UsageSink): ChatTurnResult {
        val wanted = request.webSearch && !request.auth.freeTier
        val key = request.auth.apiKey
        val models = ModelCursor(request.model, request.auth.freeTier)
        // Taken before the request: a tariff or key saved while it runs is not undone by its outcome.
        val generation = searchMemo.generation()
        if (wanted && !searchMemo.cannotSearch(key)) {
            try {
                return models.answered(withFallback(models) { model -> turn(request.copy(model = model), search = true, listener, usage) })
            } catch (_: GeminiSearchUnavailable) {
                searchMemo.remember(key, generation)
            }
        }
        // Sent without Google Search: then also without the prompt that asks to search and cite.
        val plain = if (request.webSearch) request.copy(systemPrompt = AiPrompts.chatSystem(webSearch = false)) else request
        val result = models.answered(withFallback(models) { model -> turn(plain.copy(model = model), search = false, listener, usage) })
        return if (wanted) result.copy(withoutWebSearch = true) else result
    }

    /**
     * Runs [block] with the cursor's model and moves on to the next one of the chain while Gemini
     * refuses the model; the requested model's refusal is thrown when the chain runs out.
     */
    private suspend fun withFallback(models: ModelCursor, block: suspend (String) -> ChatTurnResult): ChatTurnResult {
        while (true) {
            try {
                return block(models.current)
            } catch (e: GeminiModelUnavailable) {
                models.refused(e)
                if (models.next() == null) throw models.exhausted()
            }
        }
    }

    /** The models of one answer: the requested one first, then the fallbacks not tried yet. */
    private class ModelCursor(private val requested: String, freeTier: Boolean) {
        private val rest = ArrayDeque(GeminiModels.fallbacksAfter(requested, freeTier))
        private var first: GeminiModelUnavailable? = null
        var current: String = requested
            private set

        fun refused(e: GeminiModelUnavailable) {
            if (first == null) first = e
        }

        fun next(): String? = rest.removeFirstOrNull()?.also { current = it }

        /** The requested model's refusal as the error of the answer (its diagnostic included). */
        fun exhausted(): AiException = AiException(AiError.ModelUnavailable(requested), first?.failure)

        /** [result], naming the model that answered when it was not the requested one. */
        fun answered(result: ChatTurnResult): ChatTurnResult =
            if (current != requested) result.copy(fallbackModel = current) else result
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
}

/**
 * The Gemini keys whose project refused Google Search (the safety net of [GeminiChatEngine]),
 * kept only as SHA-256 and only in memory. [forget] runs when the tariff or a Gemini key is
 * saved: a key switched to "Mit Abrechnung" again, or entered again after billing was enabled,
 * is offered the search again. Thread-safe.
 */
internal class GeminiSearchMemo {
    private val lock = Any()
    private val unavailable = HashSet<String>()
    private var generation = 0L

    /** The current state; handed to [remember] by the request it was taken before. */
    fun generation(): Long = synchronized(lock) { generation }

    /** Google Search was refused for [key] since the last [forget]. */
    fun cannotSearch(key: String?): Boolean {
        val hash = key?.let(::hash) ?: return false
        return synchronized(lock) { hash in unavailable }
    }

    /**
     * Google Search was refused for [key] in a request started at [generation]; ignored when the
     * tariff or a key was saved since (that request ran with the old settings).
     */
    fun remember(key: String?, generation: Long) {
        val hash = key?.let(::hash) ?: return
        synchronized(lock) { if (generation == this.generation) unavailable += hash }
    }

    /** The tariff or a Gemini key was saved: every key may try the search again. */
    fun forget() {
        synchronized(lock) {
            unavailable.clear()
            generation++
        }
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Key test in two steps (GE §13), so that it fails with Google's real reason instead of passing a
 * key that cannot generate:
 *
 * 1. `GET {base}/models?pageSize=1000` (no tokens): proves the key and lists the models it may
 *    see. A refusal of the key ends the test here: 400 `API_KEY_INVALID` → INVALID, 401
 *    `ACCESS_TOKEN_TYPE_UNSUPPORTED` → AUTH_KEY_REJECTED, "reported as leaked" → KEY_LEAKED,
 *    `API_KEY_SERVICE_BLOCKED` / `SERVICE_DISABLED` → API_BLOCKED, `API_KEY_*_BLOCKED` →
 *    KEY_RESTRICTED, any other 401/403 → INVALID, 402 → OK_NO_CREDIT, 5xx → NETWORK. Anything
 *    else (404, 429, another 400) leaves the verdict to step 2.
 * 2. `POST {base}/models/{model}:generateContent` with "Antworte nur: OK" (thinking `low`, at most
 *    [GeminiRequests.KEY_TEST_MAX_OUTPUT_TOKENS] tokens, no search; a few tokens, recorded like a
 *    request): 200 → OK. When Gemini refuses the model for this key ([AiError.ModelUnavailable]:
 *    404, a 400 about the model id, a 403 denying the model, a quota of 0), the next model of
 *    [GeminiModels.CHAIN] that the list shows is tried once each; the one that answers is reported as
 *    [KeyCheckOutcome.fallback], none → MODEL_UNAVAILABLE. Otherwise 400 `FAILED_PRECONDITION` →
 *    FREE_TIER_UNAVAILABLE, the refusals of step 1 likewise, 429 → OK_QUOTA_EXHAUSTED (the key
 *    works), any other 4xx → OK (the key got through, the tiny request was the problem).
 *
 * Every failed verdict carries the key-free [KeyCheckOutcome.diagnostic] of the answer that
 * decided it. Whether the project may use Google Search still shows only in the chat (see
 * [GeminiChatEngine]).
 */
internal class GeminiKeyChecker(private val http: AiHttp) : KeyChecker {

    override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome {
        if (auth.apiKey.isNullOrBlank()) return KeyCheckOutcome(AiKeyCheck.INVALID)
        return try {
            val headers = GeminiRequests.headers(auth)
            val list = http.fetch(http.get(GeminiRequests.modelListUrl(auth), headers))
            val listed = if (list.code in 200..299) {
                listedModels(list.body)
            } else {
                listRefusal(auth, list.code, list.body)?.let { return it }
                null
            }
            generate(auth, headers, model, listed, usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyCheckOutcome(AiKeyCheck.NETWORK, diagnostic = ErrorDiagnostics.of(e))
        } catch (e: AiException) {
            // Nothing was asked: a key that cannot travel in a header, or an unusable base URL (debug override).
            KeyCheckOutcome(if (e.error == AiError.InvalidKey) AiKeyCheck.INVALID else AiKeyCheck.NETWORK)
        }
    }

    /** Step 1's verdict when the model list was refused for the key itself; null = let step 2 decide. */
    private fun listRefusal(auth: ProviderAuth, code: Int, body: String): KeyCheckOutcome? {
        val error = ProviderErrors.http(AiProviderId.GEMINI, code, body, null, null, auth.freeTier).error
        val check = when {
            error == AiError.InvalidKey -> AiKeyCheck.INVALID
            error is AiError.GeminiAccess && error.problem != GeminiProblem.FREE_TIER_UNAVAILABLE -> error.problem.check()
            code == 401 || code == 403 -> AiKeyCheck.INVALID
            code == 402 -> AiKeyCheck.OK_NO_CREDIT
            code >= 500 -> AiKeyCheck.NETWORK
            else -> return null
        }
        return KeyCheckOutcome(check, diagnostic = ErrorDiagnostics.http(code, body))
    }

    /** Step 2: [model], then the listed fallbacks, until one generates or the key is refused. */
    private suspend fun generate(
        auth: ProviderAuth,
        headers: Map<String, String>,
        model: String,
        listed: Set<String>?,
        usage: UsageSink,
    ): KeyCheckOutcome {
        val fallbacks = GeminiModels.fallbacksAfter(model, auth.freeTier).filter { listed == null || it in listed }
        var refusal: Pair<Int, String>? = null
        for (candidate in listOf(model) + fallbacks) {
            val reply = http.fetch(http.post(GeminiRequests.generateUrl(auth, candidate), GeminiRequests.keyTest(), headers))
            if (reply.code in 200..299) {
                record(usage, auth, candidate, reply.body)
                return KeyCheckOutcome(AiKeyCheck.OK, fallback = ModelFallback(model, candidate).takeIf { candidate != model })
            }
            val error = ProviderErrors.http(AiProviderId.GEMINI, reply.code, reply.body, null, candidate, auth.freeTier).error
            if (error is AiError.ModelUnavailable) {
                if (refusal == null) refusal = reply.code to reply.body
                continue
            }
            return KeyCheckOutcome(generationVerdict(reply.code, error), diagnostic = ErrorDiagnostics.http(reply.code, reply.body))
        }
        return KeyCheckOutcome(AiKeyCheck.MODEL_UNAVAILABLE, diagnostic = refusal?.let { (code, body) -> ErrorDiagnostics.http(code, body) })
    }

    private fun generationVerdict(code: Int, error: AiError): AiKeyCheck = when {
        error == AiError.InvalidKey -> AiKeyCheck.INVALID
        error is AiError.GeminiAccess -> error.problem.check()
        code == 402 -> AiKeyCheck.OK_NO_CREDIT
        code == 429 -> AiKeyCheck.OK_QUOTA_EXHAUSTED
        code == 401 || code == 403 -> AiKeyCheck.INVALID
        code == 408 || code >= 500 -> AiKeyCheck.NETWORK
        else -> AiKeyCheck.OK
    }

    /**
     * Records the test generation's tokens like a request (priced at 0 $ on the free tier). Read
     * with the stream parser (one `GenerateContentResponse` is one stream event); never fails the test.
     */
    private suspend fun record(usage: UsageSink, auth: ProviderAuth, model: String, body: String) {
        try {
            val parser = GeminiChunkParser(object : ChatTurnListener {}, SourceCollector(), AnswerText(), model, auth.freeTier)
            parser.onEvent(SseEvent(null, body))
            usage.record(AiProviderId.GEMINI, parser.model ?: model, parser.tokenUsage(GeminiRequests.keyTest().toString().length))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Key test usage not recorded: ${e.javaClass.simpleName}")
        }
    }

    private fun GeminiProblem.check(): AiKeyCheck = when (this) {
        GeminiProblem.AUTH_KEY_REJECTED -> AiKeyCheck.AUTH_KEY_REJECTED
        GeminiProblem.KEY_LEAKED -> AiKeyCheck.KEY_LEAKED
        GeminiProblem.API_BLOCKED -> AiKeyCheck.API_BLOCKED
        GeminiProblem.KEY_RESTRICTED -> AiKeyCheck.KEY_RESTRICTED
        GeminiProblem.FREE_TIER_UNAVAILABLE -> AiKeyCheck.FREE_TIER_UNAVAILABLE
    }

    internal companion object {
        private const val TAG = "GeminiKeyChecker"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The model ids of a `models.list` answer that can `generateContent` (a model without
         * `supportedGenerationMethods` counts); null when the body is no model list.
         */
        fun listedModels(body: String): Set<String>? {
            val root = try {
                JSON.parseToJsonElement(body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val models = root.array("models") ?: return if (root.isEmpty()) emptySet() else null
            return models.mapNotNull { it as? JsonObject }
                .filter { m ->
                    val methods = m.array("supportedGenerationMethods") ?: return@filter true
                    methods.any { (it as? JsonPrimitive)?.content == "generateContent" }
                }
                .mapNotNull { m -> m.str("name")?.let(GeminiModels::normalize)?.takeIf { it.isNotEmpty() } }
                .toSet()
        }
    }
}
