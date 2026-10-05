package com.cedrickgd.morgenbriefing.data

import android.content.Context
import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefIndexEntry
import com.cedrickgd.morgenbriefing.data.model.BriefLoad
import com.cedrickgd.morgenbriefing.data.model.BriefSourceException
import com.cedrickgd.morgenbriefing.data.model.DayBundle
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.data.remote.GitHubBriefSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** News briefs: GitHub (briefs branch) with the local [BriefStore] as offline history. */
class BriefRepository(
    private val context: Context,
    private val source: GitHubBriefSource,
    private val store: BriefStore,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    /** Parsed once; the bundled asset never changes at runtime. */
    private val sample: Brief by lazy { loadSample() }

    /**
     * No token -> [BriefLoad.Demo] with `assets/sample-brief.json`.
     * Otherwise fetch `latest.json`, store it, return Loaded(fromCache=false); on failure return
     * Failed(kind, newest cached brief).
     */
    suspend fun latest(): BriefLoad {
        val token = settings.token() ?: return BriefLoad.Demo(withContext(Dispatchers.IO) { sampleBrief() })
        return try {
            val brief = source.latest(token)
            saveQuietly(brief)
            BriefLoad.Loaded(brief, fromCache = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BriefSourceException) {
            BriefLoad.Failed(e.kind, newestCached())
        } catch (_: Exception) {
            BriefLoad.Failed(BriefErrorKind.UNKNOWN, newestCached())
        }
    }

    /**
     * Merges remote `index.json` (if a token works; failures are ignored) with local days.
     * Newest first, deduplicated by date, includes local weather snapshots.
     */
    suspend fun history(): List<HistoryEntry> = coroutineScope {
        val today = LocalDate.now(BERLIN)
        val remote = async { remoteIndex() }
        val localDays = store.listDays()
        val remoteByDate = remote.await()

        val dates = (localDays.asSequence() + remoteByDate.keys.asSequence())
            .filter { !it.isAfter(today) }
            .distinct()
            .sortedDescending()
            .toList()

        dates.map { date ->
            async(Dispatchers.IO) {
                val local = store.loadBrief(date)
                val entry = remoteByDate[date]
                HistoryEntry(
                    date = date,
                    teaser = local?.teaser ?: entry?.teaser.orEmpty(),
                    itemCount = local?.itemCount() ?: entry?.itemCount ?: 0,
                    weather = store.loadDayWeather(date),
                    cached = local != null,
                )
            }
        }.awaitAll()
    }

    /** Cache first; if missing and a token exists, fetch `briefs/DATE.json` and store it. */
    suspend fun day(date: LocalDate): DayBundle = coroutineScope {
        val weather = async { store.loadDayWeather(date) }
        val brief = store.loadBrief(date) ?: fetchDay(date)
        DayBundle(date = date, brief = brief, weather = weather.await())
    }

    /** Throws BriefSourceException; used by "Verbindung testen". */
    suspend fun checkAccess(token: String) {
        source.checkAccess(token.trim())
    }

    fun sampleBrief(): Brief = sample

    // ---------------------------------------------------------------------------------------

    /** Remote index by date; empty without token or on any failure (history still works offline). */
    private suspend fun remoteIndex(): Map<LocalDate, BriefIndexEntry> {
        val token = settings.token() ?: return emptyMap()
        val days = try {
            source.index(token).days
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyMap()
        }
        val byDate = LinkedHashMap<LocalDate, BriefIndexEntry>()
        days.forEach { entry -> parseDate(entry.date)?.let { byDate.putIfAbsent(it, entry) } }
        return byDate
    }

    private suspend fun fetchDay(date: LocalDate): Brief? {
        val token = settings.token() ?: return null
        val brief = try {
            source.day(token, date)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        saveQuietly(brief)
        return brief
    }

    /** Newest brief in the offline history, or null. */
    private suspend fun newestCached(): Brief? {
        for (date in store.listDays()) {
            store.loadBrief(date)?.let { return it }
        }
        return null
    }

    /** A full disk must not hide a freshly downloaded brief from the user. */
    private suspend fun saveQuietly(brief: Brief) {
        try {
            store.saveBrief(brief)
        } catch (_: IOException) {
            // Shown anyway; just not available offline.
        } catch (_: IllegalArgumentException) {
            // Invalid date — the source validates this already, keep it defensive.
        }
    }

    private fun loadSample(): Brief = try {
        context.assets.open(SAMPLE_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString(Brief.serializer(), reader.readText())
        }
    } catch (_: IOException) {
        fallbackSample()
    } catch (_: SerializationException) {
        fallbackSample()
    } catch (_: IllegalArgumentException) {
        fallbackSample()
    }

    /** Only if the bundled asset is missing or broken: an empty brief instead of a crash. */
    private fun fallbackSample(): Brief {
        val now = ZonedDateTime.now(BERLIN).truncatedTo(ChronoUnit.SECONDS)
        return Brief(
            date = now.toLocalDate().toString(),
            generatedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            teaser = "Trag in den Einstellungen deinen GitHub-Token ein, dann kommen hier jeden Morgen deine News an.",
        )
    }

    private fun Brief.itemCount(): Int = sections.sumOf { it.items.size }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val SAMPLE_ASSET = "sample-brief.json"
    }
}
