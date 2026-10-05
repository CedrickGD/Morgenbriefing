package com.cedrickgd.morgenbriefing.update

import com.cedrickgd.morgenbriefing.data.remote.USER_AGENT
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The GitHub REST API root used by the updater. */
internal val GITHUB_API_URL: HttpUrl = "https://api.github.com".toHttpUrl()

private const val GITHUB_API_VERSION = "2022-11-28"

/** `{api}/repos/{owner}/{repo}/` + [segments]. */
internal fun HttpUrl.repoUrl(owner: String, repo: String, vararg segments: String): HttpUrl =
    newBuilder()
        .addPathSegment("repos")
        .addPathSegment(owner)
        .addPathSegment(repo)
        .apply { segments.forEach { addPathSegment(it) } }
        .build()

/**
 * An authenticated GitHub API GET. Returns null when OkHttp rejects the header value (e.g. a
 * pasted line break) — deliberately without the exception, whose message could quote the token.
 */
internal fun gitHubRequest(url: HttpUrl, token: String, accept: String): Request? = try {
    Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .header("User-Agent", USER_AGENT)
        .get()
        .build()
} catch (_: IllegalArgumentException) {
    null
}

/** 429, or 403 with an exhausted rate limit (GitHub's way of saying "slow down"). */
internal fun Response.isRateLimited(): Boolean =
    code == 429 || (code == 403 && header("x-ratelimit-remaining") == "0")

/** Maps a non-2xx status of the GitHub API to an [UpdateError]. */
internal fun Response.toUpdateError(): UpdateError = when {
    isRateLimited() -> UpdateError.RATE_LIMITED
    code == 401 || code == 403 -> UpdateError.UNAUTHORIZED
    code == 404 -> UpdateError.NOT_FOUND
    else -> UpdateError.SERVER
}

/**
 * Network interceptor that makes sure the GitHub token never reaches another host: when
 * GitHub redirects an asset download to its storage host, any `Authorization` header still
 * present on that hop is removed. OkHttp already drops it on cross-host redirects; this guard
 * keeps the guarantee even if that behaviour or the client configuration ever changes.
 */
internal class TokenHostGuard(private val tokenHost: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") == null || request.url.host.equals(tokenHost, ignoreCase = true)) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().removeHeader("Authorization").build())
    }
}

/** Suspends until the response headers arrived; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // Cancelling a finished call is harmless.
        }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
