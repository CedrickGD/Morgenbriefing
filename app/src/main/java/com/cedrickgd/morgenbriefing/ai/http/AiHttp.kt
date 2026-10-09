package com.cedrickgd.morgenbriefing.ai.http

import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.remote.HttpText
import com.cedrickgd.morgenbriefing.data.remote.USER_AGENT
import com.cedrickgd.morgenbriefing.data.remote.fetchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * Plain HTTPS + SSE for every provider family without an SDK (docs/AI-PROVIDERS.md §6, D3):
 * one OkHttp client with timeouts for long, quiet streams, one SSE reader, and the error type a
 * non-2xx answer becomes. Keys only ever travel in headers. Nothing here logs a request or
 * response body or a header: error bodies can echo key fragments.
 */

/**
 * The OkHttp client of the AI requests, derived from the app's shared client (same connection
 * pool and dispatcher). Thinking and server-side search can keep a stream silent for a long
 * time, so the read timeout is generous and a whole call may run for minutes.
 *
 * @param userAgent sent with every request: `Morgenbriefing-Android/<versionName>`.
 */
internal class AiHttp(
    base: OkHttpClient,
    val userAgent: String = "$USER_AGENT/${BuildConfig.VERSION_NAME}",
) {
    val client: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * A POST of [body] (JSON) to [url] with [headers] (e.g. `Authorization`); [stream] asks for
     * Server-Sent Events.
     */
    fun post(url: String, body: JsonObject, headers: Map<String, String> = emptyMap(), stream: Boolean = false): Request =
        builder(url, headers, stream).post(body.toString().toRequestBody(JSON)).build()

    /** A GET of [url] with [headers], e.g. a model list or a key check. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Request = builder(url, headers, stream = false).get().build()

    private fun builder(url: String, headers: Map<String, String>, stream: Boolean): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        builder.header("Accept", if (stream) "text/event-stream" else "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Error bodies are read up to this size (they only feed the error classification). */
        const val MAX_ERROR_BODY = 64L * 1024
    }
}

/**
 * A non-2xx answer. [body] is the error body as text (at most [AiHttp.MAX_ERROR_BODY]), also
 * when the server labelled it `text/event-stream` (Gemini does, GE §5.1); [retryAfter] is the
 * `Retry-After` header. Never put [body] into a log: it can echo parts of the key.
 */
internal class HttpFailure(val code: Int, val body: String, val retryAfter: Duration?) : IOException("HTTP $code")

/** One Server-Sent Event: the `event:` name (null when there was none) and the joined `data:` lines. */
internal data class SseEvent(val event: String?, val data: String)

/** How a stream ended. */
internal enum class SseEnd {
    /** `data: [DONE]` (Chat Completions, some Responses dialects). */
    DONE,

    /** The server closed the body. Whether that was a complete answer is the family's call. */
    EOF,

    /** The caller stopped reading ([streamSse]'s `onEvent` returned false). */
    STOPPED,
}

/**
 * Executes [request] and streams its SSE body to [onEvent] (in order, on an IO thread).
 * A non-2xx answer throws [HttpFailure] before any event; connection problems throw
 * [IOException]. Cancelling the coroutine cancels the OkHttp call at once, also in the middle
 * of a blocking read; an [IOException] caused by that surfaces as [CancellationException].
 * [onEvent] returns false to stop reading (the call is then cancelled).
 */
internal suspend fun AiHttp.streamSse(request: Request, onEvent: suspend (SseEvent) -> Boolean): SseEnd {
    val call = client.newCall(request)
    try {
        return withContext(Dispatchers.IO) {
            // The watcher cancels the call when this coroutine is cancelled: that ends a blocking
            // read on this thread right away (OkHttp closes the socket).
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                val response = runInterruptible { call.execute() }
                response.use { r ->
                    if (!r.isSuccessful) throw HttpFailure(r.code, r.errorBody(), r.retryAfter())
                    val reader = SseReader(r.body.source())
                    var end: SseEnd? = null
                    while (end == null) {
                        ensureActive()
                        val event = reader.next()
                        end = when {
                            event == null -> if (reader.done) SseEnd.DONE else SseEnd.EOF
                            !onEvent(event) -> SseEnd.STOPPED
                            else -> null
                        }
                    }
                    end
                }
            } finally {
                watcher.cancel()
            }
        }
    } catch (e: IOException) {
        // Cancelling closes the socket under a blocking read: that is no network failure.
        if (e !is HttpFailure && !currentCoroutineContext().isActive) {
            currentCoroutineContext().ensureActive()
            throw CancellationException("Abgebrochen").apply { initCause(e) }
        }
        throw e
    }
}

/**
 * A plain request (key checks, model lists): status code and body text. Non-2xx answers are
 * returned, not thrown; connection problems throw [IOException]. Cancelling the coroutine
 * cancels the call.
 */
internal suspend fun AiHttp.fetch(request: Request): HttpText = client.fetchText(request)

/** The error body as text, cut at [AiHttp.MAX_ERROR_BODY]; "" when it cannot be read. */
private fun Response.errorBody(): String = try {
    val source = body.source()
    source.request(AiHttp.MAX_ERROR_BODY)
    source.buffer.readUtf8(minOf(source.buffer.size, AiHttp.MAX_ERROR_BODY))
} catch (_: IOException) {
    ""
}

/** `Retry-After` as seconds (also fractional) or an HTTP date; null when absent or unreadable. */
internal fun Response.retryAfter(): Duration? = parseRetryAfter(header("Retry-After"), ZonedDateTime.now())

internal fun parseRetryAfter(value: String?, now: ZonedDateTime): Duration? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toDoubleOrNull()?.let { seconds ->
        return if (seconds.isFinite() && seconds >= 0) (seconds * 1000).toLong().milliseconds else null
    }
    return try {
        val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
        java.time.Duration.between(now, at).toMillis().coerceAtLeast(0).milliseconds
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Seconds as a [Duration] (Gemini's `RetryInfo.retryDelay` is "37s" or "1.5s"). */
internal fun parseSecondsDuration(text: String?): Duration? {
    val value = text?.trim()?.removeSuffix("s")?.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0) (value * 1000).toLong().milliseconds else null
}
