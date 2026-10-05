package com.cedrickgd.morgenbriefing.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: wraps ordinary failures in a [Result] but always rethrows
 * [CancellationException], so a cancelled job (e.g. of a cleared ViewModel) really stops instead
 * of being treated like a network error.
 *
 * Inline, so the block may call suspend functions when used from a coroutine.
 */
internal inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** Flattens `attempt { repository.load() }` where the repository itself returns a [Result]. */
internal fun <T> Result<Result<T>>.flatten(): Result<T> =
    fold(onSuccess = { it }, onFailure = { Result.failure(it) })
