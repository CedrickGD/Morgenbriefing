package com.cedrickgd.morgenbriefing.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs at the morning alarm (or "Test-Benachrichtigung"): fetches live weather + latest
 * brief, stores today's morning snapshot, posts the notification. If today's brief is not
 * published yet, it notifies with the weather and enqueues [NewsFollowUpWorker].
 */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = TODO("agent E")

    companion object {
        const val KEY_FORCE = "force"

        /** Expedited unique work "morning"; [force] = ignore lastNotifiedDate (test button). */
        fun enqueue(context: Context, force: Boolean = false) { TODO("agent E") }
    }
}
