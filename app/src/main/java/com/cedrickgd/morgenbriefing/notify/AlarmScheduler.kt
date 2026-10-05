package com.cedrickgd.morgenbriefing.notify

import android.content.Context
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import java.time.ZonedDateTime

object AlarmScheduler {
    /** Schedules the next morning alarm if enabled, cancels it otherwise. Safe to call often. */
    fun sync(context: Context, settings: AppSettings) { TODO("agent E") }

    fun cancel(context: Context) { TODO("agent E") }

    /** Exact alarms allowed (always true below API 31). */
    fun canScheduleExact(context: Context): Boolean = TODO("agent E")

    /** Next occurrence of hour:minute strictly after [now], in [now]'s zone (DST-safe). */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime = TODO("agent E")
}
