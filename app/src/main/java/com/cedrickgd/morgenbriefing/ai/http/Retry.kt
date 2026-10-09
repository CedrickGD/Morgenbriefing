package com.cedrickgd.morgenbriefing.ai.http

import android.util.Log
import com.cedrickgd.morgenbriefing.ai.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When a failed request is sent again (docs/AI-PROVIDERS.md §6): only **before the first event
 * reached the app** (never replay after output was shown, OA §11, OC §9), at most [maxRetries]
 * times, and only when the family's error mapper calls the failure retryable (rate limits, 408,
 * 5xx/529, a dropped connection) — never billing, quota or key errors. The wait is the
 * server's `Retry-After` capped at [maxRetryAfter], else [backoff] (1 s, then 3 s) ± [jitter].
 *
 * @param sleep how to wait (tests record instead of sleeping).
 */
internal class RetryPolicy(
    val maxRetries: Int = 2,
    private val backoff: List<Duration> = listOf(1.seconds, 3.seconds),
    private val maxRetryAfter: Duration = 20.seconds,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    /** The wait before retry number [retry] (1-based); [retryAfter] is what the server asked for. */
    fun delayBefore(retry: Int, retryAfter: Duration?): Duration {
        if (retryAfter != null) return minOf(retryAfter, maxRetryAfter)
        val base = backoff.getOrElse(retry - 1) { backoff.lastOrNull() ?: Duration.ZERO }
        val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter
        return base * factor
    }

    /**
     * Runs [block] (one HTTP request, which calls `usage.beforeRequest()` itself, so the monthly
     * limit is checked before every try) and retries it as described above. [map] classifies a
     * failure; null means "not an API failure" and the exception passes through unchanged (e.g.
     * [com.cedrickgd.morgenbriefing.ai.AiBlockedException]). Every API failure leaves as an
     * [AiException]. [tag] names the provider in the log (status codes only, never bodies).
     */
    suspend fun <T> run(tag: String, map: (Throwable) -> MappedError?, block: suspend (RetryAttempt) -> T): T {
        var retry = 0
        while (true) {
            val attempt = RetryAttempt(retry)
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = map(e) ?: throw e
                if (!mapped.retryable || attempt.delivered || retry >= maxRetries) {
                    throw e as? AiException ?: AiException(mapped.error, e)
                }
                retry++
                val wait = delayBefore(retry, mapped.retryAfter)
                Log.i(TAG, "$tag: ${(e as? HttpFailure)?.code?.let { "HTTP $it" } ?: e.javaClass.simpleName}, retry $retry in $wait")
                sleep(wait)
            }
        }
    }

    private companion object {
        const val TAG = "AiRetry"
    }
}

/**
 * One try of a request. The request sets [delivered] as soon as the first stream event reached
 * the app: from then on a failure is final (the user may already see text).
 */
internal class RetryAttempt(val retry: Int) {
    @Volatile
    var delivered: Boolean = false
}
