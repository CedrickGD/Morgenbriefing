package com.cedrickgd.morgenbriefing.notify

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.container
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Runs at the morning alarm (or "Test-Benachrichtigung"): fetches live weather and the feeds
 * of the visible topics, then posts the notification with the weather, the clothing tip and
 * the top headlines. Both fetches are cached, so the app opens on fresh content right away.
 */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            deliver(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Morning briefing failed (run ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    /** Shown while the expedited work runs as a foreground service (below API 31). */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.syncForegroundInfo(applicationContext)

    private suspend fun deliver(force: Boolean): Result {
        val context = applicationContext
        val container = context.container
        val settings = container.settings.current()
        val todayIso = LocalDate.now(BERLIN).toString()

        if (!force) {
            if (settings.lastNotifiedDate == todayIso) return Result.success()
            // Switched off after the alarm was already on its way.
            if (!settings.notificationsEnabled) return Result.success()
        }

        val (liveWeather, newsReached) = coroutineScope {
            val weather = async { fetchWeather(container, settings) }
            val news = async { fetchNews(container, settings) }
            weather.await() to news.await()
        }
        currentCoroutineContext().ensureActive()

        if (liveWeather == null && !newsReached && runAttemptCount < MAX_RETRIES) {
            Log.i(TAG, "Neither weather nor news reachable, retrying")
            return Result.retry()
        }

        val snapshot = liveWeather ?: cachedWeather(container)
        val headlines = topHeadlines(container.news.state.value.topics, settings)
        val text = morningText(snapshot, headlines)
        if (!Notifications.canPost(context)) {
            // Data is fetched and cached for the app; there is just nothing we may show.
            Log.i(TAG, "Notifications are blocked, briefing prepared without notification")
            return Result.success()
        }
        Notifications.showMorning(context, text)
        if (!force) markNotified(container, todayIso)
        return Result.success()
    }

    private suspend fun fetchWeather(container: AppContainer, settings: AppSettings): WeatherSnapshot? =
        try {
            container.weather.load(settings)
                .onFailure { Log.w(TAG, "Live weather unavailable: ${it.javaClass.simpleName}") }
                .getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live weather failed", e)
            null
        }

    /** Refreshes the visible topics; true when at least one source answered (or nothing had to be fetched). */
    private suspend fun fetchNews(container: AppContainer, settings: AppSettings): Boolean =
        try {
            container.news.warm()
            val outcome = container.news.refresh(Topics.visible(settings).map { it.id }, settings)
            !outcome.allFailed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading the news failed", e)
            false
        }

    private suspend fun cachedWeather(container: AppContainer): WeatherSnapshot? =
        try {
            container.weather.cached()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading cached weather failed", e)
            null
        }

    /**
     * Remembers that today's notification is out. Runs non-cancellable: once posted, a
     * cancelled worker must not lead to the same briefing ringing twice.
     */
    private suspend fun markNotified(container: AppContainer, todayIso: String) {
        try {
            withContext(NonCancellable) {
                container.settings.update { it.copy(lastNotifiedDate = todayIso) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not remember today's notification", e)
        }
    }

    companion object {
        const val KEY_FORCE = "force"

        private const val TAG = "MorningWorker"
        private const val UNIQUE_NAME = "morning"

        /** Runs with runAttemptCount 0 and 1 may retry; the third run delivers whatever it has. */
        private const val MAX_RETRIES = 2

        /** Expedited unique work "morning"; [force] = ignore lastNotifiedDate (test button). */
        fun enqueue(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<MorningWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_FORCE to force))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
