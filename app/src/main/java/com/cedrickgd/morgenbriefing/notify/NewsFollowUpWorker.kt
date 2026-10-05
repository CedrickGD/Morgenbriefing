package com.cedrickgd.morgenbriefing.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.container
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The morning notification went out before today's news was published. This worker checks
 * again every few minutes and, once today's brief is there, quietly refreshes the notification
 * that is still in the shade. It stops as soon as the user has dismissed or opened it, the token
 * is gone, or after [MAX_ATTEMPTS] tries.
 */
class NewsFollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val attempt = inputData.getInt(KEY_ATTEMPT, 1).coerceAtLeast(1)
        try {
            // Tapped or swiped away: the app loads the news itself, nothing left to refresh.
            if (!Notifications.isMorningShowing(context)) return Result.success()

            val container = context.container
            val today = LocalDate.now(BERLIN)
            val load = container.briefs.latest()
            val brief = when (load) {
                is BriefLoad.Loaded -> load.brief
                is BriefLoad.Failed -> load.cached
                is BriefLoad.Demo -> null
            }
            when {
                brief != null && brief.isCurrentFor(today) -> refresh(container, brief, today)
                load is BriefLoad.Demo || (load is BriefLoad.Failed && load.error == BriefErrorKind.NO_TOKEN) ->
                    Log.i(TAG, "No token any more, stopping the news follow-up")
                else -> scheduleNext(context, attempt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "News follow-up $attempt failed", e)
            scheduleNext(context, attempt)
        }
        return Result.success()
    }

    private suspend fun refresh(container: AppContainer, brief: Brief, today: LocalDate) {
        val weather = try {
            container.weather.cached()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading cached weather failed", e)
            null
        }
        val text = morningText(weather, brief, today.toString())
        // Re-check: the user may have dismissed it while we were fetching.
        if (Notifications.isMorningShowing(applicationContext)) {
            Notifications.showMorning(applicationContext, text, silent = true)
            Log.i(TAG, "Morning notification refreshed with today's news")
        }
    }

    private fun scheduleNext(context: Context, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) {
            Log.i(TAG, "Today's news still missing after $attempt checks, giving up")
            return
        }
        try {
            enqueue(context, attempt + 1)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not schedule the next news check", e)
        }
    }

    companion object {
        const val KEY_ATTEMPT = "attempt"

        /** Checks per morning (with the default delay: roughly one hour). */
        internal const val MAX_ATTEMPTS = 6

        private const val TAG = "NewsFollowUp"
        private const val UNIQUE_NAME = "news-followup"

        /**
         * Unique work "news-followup" (replacing any pending check) that runs [delayMinutes]
         * from now once the network is available. [attempt] starts at 1.
         */
        fun enqueue(context: Context, attempt: Int, delayMinutes: Long = 10) {
            val request = OneTimeWorkRequestBuilder<NewsFollowUpWorker>()
                .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_ATTEMPT to attempt))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
