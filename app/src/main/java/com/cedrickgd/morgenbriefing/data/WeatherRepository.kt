package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.local.WeatherStore
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.data.remote.OpenMeteoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.time.ZonedDateTime

/** Live weather from Open-Meteo plus the offline copy kept in [WeatherStore]. */
class WeatherRepository(
    private val client: OpenMeteoClient,
    private val store: WeatherStore,
) {
    private val _latest = MutableStateFlow<WeatherSnapshot?>(null)

    /**
     * The newest snapshot this process has seen (live fetch or offline copy), so every screen
     * (Heute, the place cards in Einstellungen) shows the same weather. Null until one is known.
     */
    val latest: StateFlow<WeatherSnapshot?> = _latest.asStateFlow()

    /** Live fetch (one retry after 1.5 s on failure); a success is also saved for an offline start. */
    suspend fun load(settings: AppSettings): Result<WeatherSnapshot> {
        val snapshot = try {
            fetch(settings)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            delay(RETRY_DELAY_MS)
            try {
                fetch(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        _latest.value = snapshot
        persist(snapshot)
        return Result.success(snapshot)
    }

    /** Last successful snapshot, for offline start. */
    suspend fun cached(): WeatherSnapshot? {
        _latest.value?.let { return it }
        val stored = store.loadLatest() ?: return null
        _latest.compareAndSet(null, stored)
        return _latest.value ?: stored
    }

    suspend fun search(query: String): Result<List<Place>> = try {
        Result.success(client.searchPlaces(query))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun fetch(settings: AppSettings): WeatherSnapshot {
        val now = ZonedDateTime.now(BERLIN)
        return client.snapshot(settings.home, settings.day, settings.dayStartHour, settings.dayEndHour, now)
    }

    /** Storage problems must never turn a successful live fetch into a failure. */
    private suspend fun persist(snapshot: WeatherSnapshot) {
        try {
            store.saveLatest(snapshot)
        } catch (_: IOException) {
            // Offline start simply falls back to an older copy.
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_500L
    }
}
