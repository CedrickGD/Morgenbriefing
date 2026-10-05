package com.cedrickgd.morgenbriefing.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Stays below the shortest broadcast ANR limit (10 s for foreground broadcasts), so
 * [BroadcastReceiver.PendingResult.finish] always runs in time.
 */
private const val RECEIVER_BUDGET_MS = 9_000L

/**
 * Runs [block] on the process-wide app scope while keeping the broadcast alive via
 * [BroadcastReceiver.goAsync]. Failures are logged, never thrown; the pending result is
 * always finished.
 */
internal fun BroadcastReceiver.runAsync(context: Context, tag: String, block: suspend () -> Unit) {
    val app = context.applicationContext as MorgenbriefingApp
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    app.appScope.launch {
        try {
            withTimeout(RECEIVER_BUDGET_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(tag, "Gave up after $RECEIVER_BUDGET_MS ms", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Background work failed", e)
        } finally {
            pending?.finish()
        }
    }
}
