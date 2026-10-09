package com.cedrickgd.morgenbriefing.ai.http

import com.cedrickgd.morgenbriefing.ai.AiException
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * The short technical line behind a failed request, for the "Details" of an error in the chat
 * and in the key dialog (docs/providers/gemini.md §12.6): what the provider answered, in its own
 * words, so the user can copy it and send it along with a question —
 * "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED".
 *
 * Key-free by construction: only the HTTP status and identifier-like fields of the error body
 * go in (google.rpc `status`, `ErrorInfo.reason`, the quota id and limit, `RetryInfo`, an
 * OpenAI-style `code`), each one checked against a strict pattern; never a message, a metadata
 * value, a help link or any URL. [redact] removes the key once more where it is at hand.
 */
internal object ErrorDiagnostics {
    const val SEPARATOR = " · "

    /** The diagnostic of [failure] (also of an [AiException]'s cause); null when there is nothing to say. */
    fun of(failure: Throwable?): String? = when (failure) {
        null -> null
        is AiException -> failure.diagnostic
        is HttpFailure -> http(failure.code, failure.body)
        is IOException -> token(failure.javaClass.simpleName)?.let { "Verbindung$SEPARATOR$it" }
        else -> null
    }

    /** A non-2xx answer: "HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED". */
    fun http(code: Int, body: String): String = (listOf("HTTP $code") + fields(ApiErrorBody.parse(body), code)).joinToString(SEPARATOR)

    /** An error chunk inside a 200 stream (Gemini, OpenRouter): "Stream · 503 · UNAVAILABLE". */
    fun stream(chunk: JsonObject): String {
        val error = ApiErrorBody.parse(chunk.toString())
        val code = error.httpCode
        return (listOfNotNull("Stream", code?.toString()) + fields(error, code)).joinToString(SEPARATOR)
    }

    /** Gemini ended the answer itself with [finishReason] (e.g. `OTHER`). */
    fun finishReason(finishReason: String): String? = token(finishReason)?.let { "finishReason$SEPARATOR$it" }

    /**
     * [text] without [key]: a diagnostic never holds a key, but where the key is known it is
     * checked once more. Null stays null; a diagnostic that would contain the key is dropped.
     */
    fun redact(text: String?, key: String?): String? {
        val secret = key?.trim()?.takeIf { it.length >= 8 } ?: return text
        return text?.takeUnless { it.contains(secret) }
    }

    /** The identifier-like fields of [e], in a fixed order, without repeats. */
    private fun fields(e: ApiErrorBody, httpCode: Int?): List<String> {
        val out = LinkedHashSet<String>()
        token(e.status)?.let(out::add)
        // OpenAI style: `code` / `type` are identifiers ("insufficient_quota"); google.rpc's `code` is the HTTP status.
        listOfNotNull(e.code, e.type).filter { it.toIntOrNull() == null && it != httpCode?.toString() }.mapNotNull(::token).forEach { out += it }
        e.reasons.mapNotNull(::token).forEach { out += it }
        e.quotaViolations.firstOrNull()?.let { v ->
            token(v.id)?.let(out::add)
            v.value?.trim()?.toLongOrNull()?.let { out += "limit $it" }
        }
        e.retryDelay?.let { out += "retry ${it.inWholeSeconds}s" }
        return out.toList()
    }

    /** [text] when it looks like an identifier (letters, digits, `_ . -`, at most 64), else null. */
    private fun token(text: String?): String? = text?.trim()?.takeIf { TOKEN.matches(it) }

    private val TOKEN = Regex("""[A-Za-z][A-Za-z0-9_.\-]{0,63}""")
}
