package com.cedrickgd.morgenbriefing.data.local

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Offline history as plain JSON files (atomic writes via temp file + rename):
 * `<dir>/briefs/YYYY-MM-DD.json`, `<dir>/weather/YYYY-MM-DD.json` (the morning snapshot),
 * `<dir>/weather/latest.json` (last live snapshot for offline start).
 * Keeps at most [maxDays] days; older files are pruned on write. All IO on Dispatchers.IO.
 */
class BriefStore(
    private val dir: File,
    private val json: Json,
    private val maxDays: Int = 400,
) {
    suspend fun saveBrief(brief: Brief): Unit = TODO("agent A")
    suspend fun loadBrief(date: LocalDate): Brief? = TODO("agent A")

    /** Morning snapshot for [date]; does not overwrite an existing one unless [overwrite]. */
    suspend fun saveDayWeather(date: LocalDate, snapshot: WeatherSnapshot, overwrite: Boolean = false): Unit = TODO("agent A")
    suspend fun loadDayWeather(date: LocalDate): WeatherSnapshot? = TODO("agent A")

    suspend fun saveLatestWeather(snapshot: WeatherSnapshot): Unit = TODO("agent A")
    suspend fun loadLatestWeather(): WeatherSnapshot? = TODO("agent A")

    /** All dates that have a brief or a weather snapshot, newest first. */
    suspend fun listDays(): List<LocalDate> = TODO("agent A")
}
