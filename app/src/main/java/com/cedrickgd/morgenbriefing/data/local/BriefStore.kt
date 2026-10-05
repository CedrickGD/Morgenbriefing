package com.cedrickgd.morgenbriefing.data.local

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Offline history as plain JSON files (atomic writes via temp file + rename):
 * `<dir>/briefs/YYYY-MM-DD.json`, `<dir>/weather/YYYY-MM-DD.json` (the morning snapshot),
 * `<dir>/weather/latest.json` (last live snapshot for offline start).
 * Keeps at most [maxDays] days; older files are pruned on write. All IO on Dispatchers.IO.
 *
 * Reads never throw: a missing, unreadable or corrupt file yields `null` (corrupt files are
 * deleted so they cannot shadow a fresh download). Writes throw [IOException] when the disk
 * refuses them.
 */
class BriefStore(
    private val dir: File,
    private val json: Json,
    private val maxDays: Int = 400,
) {
    private val briefsDir = File(dir, "briefs")
    private val weatherDir = File(dir, "weather")
    private val latestWeatherFile = File(weatherDir, LATEST_NAME)

    /** Serialises every mutation (write, replace, prune, corrupt-file cleanup). */
    private val writeLock = Mutex()

    /**
     * Stores [brief] under its own `date`, replacing an older copy of that day.
     * @throws IllegalArgumentException if `brief.date` is not `YYYY-MM-DD`.
     * @throws IOException if the file cannot be written.
     */
    suspend fun saveBrief(brief: Brief) {
        val date = parseDate(brief.date)
            ?: throw IllegalArgumentException("Briefing hat ein ungültiges Datum: ${brief.date}")
        val payload = json.encodeToString(Brief.serializer(), brief)
        mutate {
            writeAtomically(dayFile(briefsDir, date), payload)
            pruneOldDays()
        }
    }

    suspend fun loadBrief(date: LocalDate): Brief? = read(dayFile(briefsDir, date), Brief.serializer())

    /** Morning snapshot for [date]; does not overwrite an existing one unless [overwrite]. */
    suspend fun saveDayWeather(date: LocalDate, snapshot: WeatherSnapshot, overwrite: Boolean = false) {
        val payload = json.encodeToString(WeatherSnapshot.serializer(), snapshot)
        val file = dayFile(weatherDir, date)
        mutate {
            // An existing but corrupt file does not count as "already there".
            if (!overwrite && decodeOrDiscard(file, WeatherSnapshot.serializer()) != null) return@mutate
            writeAtomically(file, payload)
            pruneOldDays()
        }
    }

    suspend fun loadDayWeather(date: LocalDate): WeatherSnapshot? =
        read(dayFile(weatherDir, date), WeatherSnapshot.serializer())

    suspend fun saveLatestWeather(snapshot: WeatherSnapshot) {
        val payload = json.encodeToString(WeatherSnapshot.serializer(), snapshot)
        mutate { writeAtomically(latestWeatherFile, payload) }
    }

    suspend fun loadLatestWeather(): WeatherSnapshot? = read(latestWeatherFile, WeatherSnapshot.serializer())

    /** All dates that have a brief or a weather snapshot, newest first. */
    suspend fun listDays(): List<LocalDate> = withContext(Dispatchers.IO) {
        storedDays().sortedDescending()
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private suspend fun mutate(block: () -> Unit) = withContext(Dispatchers.IO) {
        writeLock.withLock { block() }
    }

    private suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return@withContext null
        }
        decode(text, serializer) ?: writeLock.withLock {
            // Re-check under the lock: a writer may have replaced the file meanwhile.
            decodeOrDiscard(file, serializer)
        }
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Decodes [file]; deletes it when its content is corrupt. Caller holds [writeLock]. */
    private fun <T> decodeOrDiscard(file: File, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        return decode(text, serializer) ?: run {
            file.delete()
            null
        }
    }

    /** Write to a sibling temp file, flush to disk, then rename over the target. */
    private fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("Kein Elternordner für ${target.path}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Ordner ${parent.path} konnte nicht angelegt werden")
        }
        val tmp = File.createTempFile(".${target.name}.", TMP_SUFFIX, parent)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            replace(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * rename(2) replaces atomically on Android/Linux. Platforms whose rename refuses to
     * overwrite (Windows JVM in unit tests) fall back to delete + rename, then copy.
     */
    private fun replace(source: File, target: File) {
        if (source.renameTo(target)) return
        if (target.exists() && !target.delete()) {
            throw IOException("${target.path} konnte nicht ersetzt werden")
        }
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
    }

    /** Dates of all day files (briefs + weather), unsorted, without duplicates. */
    private fun storedDays(): Set<LocalDate> = buildSet {
        for (folder in listOf(briefsDir, weatherDir)) {
            folder.listFiles()?.forEach { file -> dayOf(file)?.let(::add) }
        }
    }

    /** Deletes whole days (brief + weather) beyond [maxDays], oldest first, plus stale temp files. */
    private fun pruneOldDays() {
        val days = storedDays()
        val excess = days.size - maxDays.coerceAtLeast(1)
        if (excess > 0) {
            days.sorted().take(excess).forEach { date ->
                dayFile(briefsDir, date).delete()
                dayFile(weatherDir, date).delete()
            }
        }
        val staleBefore = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        for (folder in listOf(briefsDir, weatherDir)) {
            folder.listFiles()
                ?.filter { it.name.endsWith(TMP_SUFFIX) && it.lastModified() < staleBefore }
                ?.forEach { it.delete() }
        }
    }

    private fun dayFile(folder: File, date: LocalDate) = File(folder, "$date.json")

    /** `2026-10-06.json` → date; anything else (latest.json, temp files, junk) → null. */
    private fun dayOf(file: File): LocalDate? {
        if (!file.isFile) return null
        val match = DAY_FILE.matchEntire(file.name) ?: return null
        return parseDate(match.groupValues[1])
    }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val LATEST_NAME = "latest.json"
        const val TMP_SUFFIX = ".tmp"
        val DAY_FILE = Regex("""(\d{4}-\d{2}-\d{2})\.json""")
    }
}
