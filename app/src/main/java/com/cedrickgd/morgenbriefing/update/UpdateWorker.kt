package com.cedrickgd.morgenbriefing.update

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cedrickgd.morgenbriefing.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background check for a new app release, every 12 hours while online. A newer release is
 * remembered for the settings screen and announced once per tag with a notification. Without
 * a GitHub token there is nothing to check, so it ends silently.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val token = context.container.settings.token()
            if (token.isNullOrBlank()) return Result.success()

            val services = context.updates
            val result = services.checker.check(token)
            withContext(Dispatchers.IO) { services.store.record(result) }
            when (result) {
                is UpdateCheckResult.Available -> announce(context, services, result.release)
                is UpdateCheckResult.UpToDate -> Log.i(TAG, "Up to date (latest ${result.latestVersion})")
                is UpdateCheckResult.Failed -> return failed(result.error)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.success()
        }
    }

    private suspend fun announce(context: Context, services: UpdateServices, release: ReleaseInfo) {
        val alreadyAnnounced = withContext(Dispatchers.IO) { services.store.wasAnnounced(release.tag) }
        if (alreadyAnnounced) return
        UpdateNotifications.showAvailable(context, release)
        withContext(Dispatchers.IO) { services.store.markAnnounced(release.tag) }
        Log.i(TAG, "Announced ${release.tag}")
    }

    /** Transient problems are retried a few times; everything else waits for the next period. */
    private fun failed(error: UpdateError): Result {
        val transient = error == UpdateError.NETWORK || error == UpdateError.SERVER
        return if (transient && runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Log.i(TAG, "Update check failed: $error")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "UpdateWorker"
        const val UNIQUE_NAME = "update-check"
        private const val INTERVAL_HOURS = 12L
        private const val MAX_RETRIES = 3

        /** Unique periodic work "update-check" (KEEP: an existing schedule stays untouched). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
