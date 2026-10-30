package com.cedrickgd.morgenbriefing.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-Agent sent with every request (GitHub rejects requests without one). */
internal const val USER_AGENT = "Morgenbriefing-Android"

/** A fully read HTTP response: status code plus the body as text. */
internal class HttpText(
    val code: Int,
    val body: String,
    /** GitHub's `x-ratelimit-remaining` header, if sent. */
    val rateLimitRemaining: String? = null,
) {
    val isSuccessful: Boolean get() = code in 200..299

    /**
     * GitHub's "slow down": 429, or 403 with an exhausted primary limit (`x-ratelimit-remaining: 0`)
     * or a secondary-limit message in the body.
     */
    val isRateLimited: Boolean
        get() = code == 429 ||
            (code == 403 && (rateLimitRemaining == "0" || body.lowercase(Locale.ROOT).contains("rate limit")))
}

/**
 * Executes [request] and reads the whole body as text.
 *
 * The call itself runs on OkHttp's dispatcher and is cancelled together with the calling
 * coroutine; the (blocking) body read happens on [Dispatchers.IO]. Non-2xx responses are
 * returned, not thrown — callers decide how to map them.
 *
 * @throws IOException on connection problems, timeouts or a broken body stream.
 */
internal suspend fun OkHttpClient.fetchText(request: Request): HttpText {
    val response = newCall(request).await()
    return response.use { r ->
        withContext(Dispatchers.IO) { HttpText(r.code, r.body.string(), r.header("x-ratelimit-remaining")) }
    }
}

/** Suspends until the response headers arrived; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // Cancelling an already finished call is harmless; nothing else to do here.
        }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // If the coroutine was cancelled in the meantime, close the orphaned response.
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
