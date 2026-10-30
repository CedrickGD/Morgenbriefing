package com.cedrickgd.morgenbriefing.data.local

import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last live weather as a JSON file (`<dir>/weather/latest.json`) for an offline start.
 * Reads never throw (a corrupt file yields null and is deleted); writes throw [java.io.IOException]
 * when the disk refuses them. All IO on Dispatchers.IO.
 */
class WeatherStore(dir: File, private val json: Json) {
    private val folder = File(dir, "weather")
    private val latestFile = File(folder, "latest.json")
    private val lock = Mutex()

    suspend fun saveLatest(snapshot: WeatherSnapshot) {
        val payload = json.encodeToString(WeatherSnapshot.serializer(), snapshot)
        withContext(Dispatchers.IO) {
            lock.withLock {
                JsonFiles.writeAtomically(latestFile, payload)
                JsonFiles.pruneTemp(folder)
            }
        }
    }

    suspend fun loadLatest(): WeatherSnapshot? = withContext(Dispatchers.IO) {
        val text = JsonFiles.readOrNull(latestFile) ?: return@withContext null
        try {
            json.decodeFromString(WeatherSnapshot.serializer(), text)
        } catch (_: SerializationException) {
            lock.withLock { latestFile.delete() }
            null
        } catch (_: IllegalArgumentException) {
            lock.withLock { latestFile.delete() }
            null
        }
    }
}
