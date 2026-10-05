package com.cedrickgd.morgenbriefing.data

import android.content.Context
import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.data.model.DayBundle
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.data.remote.GitHubBriefSource
import kotlinx.serialization.json.Json
import java.time.LocalDate

class BriefRepository(
    private val context: Context,
    private val source: GitHubBriefSource,
    private val store: BriefStore,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    /**
     * No token -> [BriefLoad.Demo] with `assets/sample-brief.json`.
     * Otherwise fetch `latest.json`, store it, return Loaded(fromCache=false); on failure return
     * Failed(kind, newest cached brief).
     */
    suspend fun latest(): BriefLoad = TODO("agent A")

    /**
     * Merges remote `index.json` (if a token works; failures are ignored) with local days.
     * Newest first, deduplicated by date, includes local weather snapshots.
     */
    suspend fun history(): List<HistoryEntry> = TODO("agent A")

    /** Cache first; if missing and a token exists, fetch `briefs/DATE.json` and store it. */
    suspend fun day(date: LocalDate): DayBundle = TODO("agent A")

    /** Throws BriefSourceException; used by "Verbindung testen". */
    suspend fun checkAccess(token: String): Unit = TODO("agent A")

    fun sampleBrief(): Brief = TODO("agent A")
}
