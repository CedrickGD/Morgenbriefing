package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AnswerText
import com.cedrickgd.morgenbriefing.ai.ChatTurnListener
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TokenUsage
import com.cedrickgd.morgenbriefing.ai.TurnEnd
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.ApiErrorBody
import com.cedrickgd.morgenbriefing.ai.http.ErrorDiagnostics
import com.cedrickgd.morgenbriefing.ai.http.HttpFailure
import com.cedrickgd.morgenbriefing.ai.http.ProviderErrors
import com.cedrickgd.morgenbriefing.ai.http.RetryPolicy
import com.cedrickgd.morgenbriefing.ai.http.SseEvent
import com.cedrickgd.morgenbriefing.ai.http.array
import com.cedrickgd.morgenbriefing.ai.http.bool
import com.cedrickgd.morgenbriefing.ai.http.long
import com.cedrickgd.morgenbriefing.ai.http.obj
import com.cedrickgd.morgenbriefing.ai.http.objects
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.ai.http.streamSse
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Outcome of one streamed Gemini request. Text and sources went to the [AnswerText] and
 * [SourceCollector] the request was run with.
 *
 * @property searchSuggestionsHtml the latest `searchEntryPoint.renderedContent` (Google's Search
 *   Suggestions, which must be shown with a grounded answer, GE §6.4); null without search.
 */
internal class GeminiTurn(
    val end: TurnEnd,
    val refusalCategory: String?,
    val searchSuggestionsHtml: String?,
)

/**
 * The request was sent with Google Search and Gemini refused the search itself before streaming
 * (a key on the free tier, GE §3.3): the chat answers again without it. Never leaves the engine.
 */
internal class GeminiSearchUnavailable(cause: HttpFailure) : Exception("Google Search not available for this key", cause)

/**
 * Gemini refused the requested model for this key before streaming (404 `NOT_FOUND`, a 400/403
 * about the model, a quota of 0, GE §12.5): the chat tries the next model of the fallback chain.
 * Never leaves the engine; [failure] is the refusal (its diagnostic if no model answers).
 */
internal class GeminiModelUnavailable(val failure: HttpFailure) : Exception("Model not available for this key", failure)

/**
 * Runs one streamed `streamGenerateContent` request: retries per [retry] before the first event
 * (a free key's 429 only for a short named wait, see ProviderErrors), feeds every chunk to a
 * [GeminiChunkParser], and records the request's usage with the [UsageSink] — also when it
 * fails or is cancelled after the stream started (estimated when no `usageMetadata` came).
 */
internal class GeminiStreamer(
    private val http: AiHttp,
    private val retry: RetryPolicy,
) {
    /**
     * @param search the body asks for Google Search: a failure that says the key may not search
     *   becomes [GeminiSearchUnavailable] instead of an error (no retries for it).
     * @throws GeminiModelUnavailable when Gemini refuses [model] for the key before streaming
     *   (no retries for it either).
     */
    suspend fun run(
        auth: ProviderAuth,
        model: String,
        body: JsonObject,
        search: Boolean,
        listener: ChatTurnListener,
        usage: UsageSink,
        sources: SourceCollector,
        text: AnswerText,
    ): GeminiTurn {
        val request = http.post(GeminiRequests.streamUrl(auth, model), body, GeminiRequests.headers(auth), stream = true)
        val requestChars = body.toString().length
        val mapper = ProviderErrors.mapper(auth, model)
        val passThrough = { e: Throwable -> e is GeminiSearchUnavailable || e is GeminiModelUnavailable }
        return retry.run(AiProviderId.GEMINI.key, { e -> if (passThrough(e)) null else mapper.map(e) }) { attempt ->
            usage.beforeRequest()
            val parser = GeminiChunkParser(listener, sources, text, model, auth.freeTier)
            try {
                http.streamSse(request) { event ->
                    attempt.delivered = true
                    parser.onEvent(event)
                }
            } catch (e: HttpFailure) {
                if (search && !attempt.delivered && isSearchUnavailable(e)) throw GeminiSearchUnavailable(e)
                if (!attempt.delivered && isModelUnavailable(e, model, auth.freeTier)) throw GeminiModelUnavailable(e)
                throw e
            } catch (e: Throwable) {
                // Whatever streamed was billed: record it even when cancelled or failed.
                if (attempt.delivered) withContext(NonCancellable) { record(parser, usage, model, requestChars) }
                throw e
            }
            record(parser, usage, model, requestChars)
            parser.finish()
        }
    }

    private suspend fun record(parser: GeminiChunkParser, usage: UsageSink, requestModel: String, requestChars: Int) {
        usage.record(AiProviderId.GEMINI, parser.model ?: requestModel, parser.tokenUsage(requestChars))
    }

    internal companion object {
        /**
         * Gemini refused Google Search for this key before streaming (docs/AI-PROVIDERS.md §7.2,
         * GE §16 open question 1, so the signal is broad): 400 `FAILED_PRECONDITION`, or a 400,
         * 403 or 429 whose message names search or grounding. Never for an invalid key; a 402
         * (no credit) is no search problem either.
         */
        fun isSearchUnavailable(failure: HttpFailure): Boolean {
            if (failure.code !in setOf(400, 403, 429)) return false
            val error = ApiErrorBody.parse(failure.body)
            // A refused key or a blocked API is no missing search, whatever the message says.
            if (error.isGeminiKeyInvalid() || error.reasons.any { r -> KEY_REASONS.any(r::startsWith) }) return false
            if (failure.code == 400 && error.status == "FAILED_PRECONDITION") return true
            val message = error.message.orEmpty()
            return SEARCH_WORDS.any { message.contains(it, ignoreCase = true) }
        }

        private val SEARCH_WORDS = listOf("search", "grounding")

        /** ErrorInfo reasons about the key or its project (GE §12.4). */
        private val KEY_REASONS = listOf("API_KEY", "ACCESS_TOKEN", "SERVICE_DISABLED", "CONSUMER")

        /**
         * Gemini refused [model] for this key, not the key or the request (GE §12.5): the same
         * line as the error mapping's [AiError.ModelUnavailable] — 404, a 400/403 about the model,
         * a quota of 0 that is not the search's.
         */
        fun isModelUnavailable(failure: HttpFailure, model: String, freeTier: Boolean): Boolean =
            ProviderErrors.http(AiProviderId.GEMINI, failure.code, failure.body, failure.retryAfter, model, freeTier).error is AiError.ModelUnavailable
    }
}

/**
 * The Gemini stream state machine (GE §5, docs/AI-PROVIDERS.md §7.2): every event's `data` is
 * one complete `GenerateContentResponse`; there are no event names and no `[DONE]`, the stream
 * ends at EOF.
 *
 * * `candidates[0].content.parts[]`: `text` is answer text, parts with `thought: true` are
 *   thought summaries and never shown.
 * * `groundingMetadata`: `groundingChunks[].web` arrive incrementally and are appended in order
 *   as cited sources (the title is a domain, the uri a Google redirect that is opened as it is,
 *   never resolved, GE §11); each new distinct `webSearchQueries` entry is one billed search
 *   (GE §3.3); `searchEntryPoint.renderedContent` is the Search Suggestions HTML (latest wins).
 *   The first query or source marks the answer grounded ([ChatTurnListener.onGrounded]): the
 *   suggestions only come with the last chunk.
 * * `finishReason` is remembered, reading goes on to EOF (grounding and usage may follow).
 * * A top-level `error` is a mid-stream failure; `promptFeedback.blockReason` a blocked prompt.
 */
internal class GeminiChunkParser(
    private val listener: ChatTurnListener,
    private val sources: SourceCollector,
    private val text: AnswerText,
    private val requestModel: String,
    private val freeTier: Boolean,
) {
    /** The model that answered (`modelVersion`), or null. */
    var model: String? = null
        private set

    private val queries = LinkedHashSet<String>()
    private var finishReason: String? = null
    private var blockReason: String? = null
    private var usage: JsonObject? = null
    private var suggestions: String? = null
    private var textStarted = false
    private var grounded = false
    private var streamedChars = 0L

    /** Handles one event; always true (the stream ends at EOF). */
    fun onEvent(event: SseEvent): Boolean {
        val root = try {
            JSON.parseToJsonElement(event.data)
        } catch (_: Exception) {
            return true
        }
        chunksOf(root).forEach(::onChunk)
        return true
    }

    private fun chunksOf(root: JsonElement): List<JsonObject> = when (root) {
        is JsonObject -> listOf(root)
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun onChunk(chunk: JsonObject) {
        if (chunk["error"] is JsonObject) {
            throw AiException(ProviderErrors.streamError(AiProviderId.GEMINI, chunk, requestModel, freeTier), diagnostic = ErrorDiagnostics.stream(chunk))
        }
        chunk.str("modelVersion")?.removePrefix("models/")?.takeIf { it.isNotBlank() }?.let { model = it }
        chunk.obj("usageMetadata")?.let { usage = it }
        chunk.obj("promptFeedback")?.str("blockReason")?.let { blockReason = it }
        val candidate = chunk.objects("candidates").firstOrNull() ?: return
        candidate.obj("content")?.objects("parts")?.forEach(::onPart)
        candidate.obj("groundingMetadata")?.let(::onGrounding)
        candidate.str("finishReason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
    }

    private fun onPart(part: JsonObject) {
        if (part.bool("thought") == true) return
        val delta = part.str("text")?.takeIf { it.isNotEmpty() } ?: return
        if (!textStarted) {
            textStarted = true
            text.startTextBlock()?.let(listener::onText)
        }
        streamedChars += delta.length
        text.append(delta)
        listener.onText(delta)
    }

    private fun onGrounding(grounding: JsonObject) {
        grounding.array("webSearchQueries").orEmpty().forEach { element ->
            val query = (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            // Empty queries are not billed; the status line only makes sense before the answer.
            if (queries.add(query)) {
                markGrounded()
                if (!textStarted) listener.onSearch(query)
            }
        }
        var changed = false
        grounding.objects("groundingChunks").forEach { chunk ->
            val web = chunk.obj("web") ?: return@forEach
            markGrounded()
            changed = sources.addCited(web.str("uri"), web.str("title")) || changed
        }
        if (changed) listener.onSources(sources.ranked())
        grounding.obj("searchEntryPoint")?.str("renderedContent")?.takeIf { it.isNotBlank() }?.let { html ->
            if (html != suggestions) {
                suggestions = html
                listener.onSearchSuggestions(html)
            }
        }
    }

    /** Tells the listener once that this answer is grounded (before its suggestions arrive). */
    private fun markGrounded() {
        if (grounded) return
        grounded = true
        listener.onGrounded()
    }

    /**
     * The turn after EOF. A blocked prompt or a safety finish is a refusal (category = the
     * lowercased reason), `MAX_TOKENS` a cut-off answer; any other finish reason means Gemini
     * stopped the answer itself ([AiError.Unknown]), and no finish reason at all a broken
     * connection ([AiError.Network]).
     */
    fun finish(): GeminiTurn {
        blockReason?.let { return GeminiTurn(TurnEnd.REFUSED, it.lowercase(), null) }
        val end = when (val reason = finishReason) {
            null -> throw AiException(AiError.Network)
            "STOP" -> TurnEnd.COMPLETE
            "MAX_TOKENS" -> TurnEnd.TRUNCATED
            in REFUSAL_REASONS -> return GeminiTurn(TurnEnd.REFUSED, reason.lowercase(), null)
            else -> throw AiException(
                AiError.Unknown("${ProviderCatalog.GEMINI.brand} hat die Antwort abgebrochen."),
                diagnostic = ErrorDiagnostics.finishReason(reason),
            )
        }
        return GeminiTurn(end, null, suggestions)
    }

    /**
     * Usage of this request (GE §9.2; TokenUsage convention: uncached input, thinking counts as
     * output, one search per distinct query). Without `usageMetadata` (the stream broke off) it
     * is estimated: the request at ≈ 4 characters per input token, the streamed text at ≈ 3 per
     * output token.
     */
    fun tokenUsage(requestChars: Int): TokenUsage {
        val searches = queries.size.toLong()
        val u = usage ?: return TokenUsage(inputTokens = requestChars / 4L, outputTokens = streamedChars / 3, webSearches = searches)
        val cached = u.long("cachedContentTokenCount") ?: 0L
        val prompt = u.long("promptTokenCount") ?: 0L
        return TokenUsage(
            inputTokens = (prompt - cached).coerceAtLeast(0) + (u.long("toolUsePromptTokenCount") ?: 0L),
            outputTokens = (u.long("candidatesTokenCount") ?: (streamedChars / 3)) + (u.long("thoughtsTokenCount") ?: 0L),
            cacheReadTokens = cached,
            webSearches = searches,
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Finish reasons that mean "will not answer this" (GE §5.3). */
        val REFUSAL_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")
    }
}
