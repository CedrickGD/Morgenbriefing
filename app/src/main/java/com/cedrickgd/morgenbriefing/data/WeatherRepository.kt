package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.data.remote.OpenMeteoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.ZonedDateTime

/** Live weather from Open-Meteo plus the offline copies kept in [BriefStore]. */
class WeatherRepository(
    private val client: OpenMeteoClient,
    private val store: BriefStore,
) {
    /**
     * Live fetch (one retry after 1.5 s on failure). On success also saves it as latest and,
     * between 04:00 and 11:59 local time, as today's morning snapshot (first one wins).
     */
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
        persist(snapshot)
        return Result.success(snapshot)
    }

    /** Last successful snapshot, for offline start. */
    suspend fun cached(): WeatherSnapshot? = store.loadLatestWeather()

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
            store.saveLatestWeather(snapshot)
        } catch (_: IOException) {
            // Offline start simply falls back to an older copy.
        }
        val now = ZonedDateTime.now(BERLIN)
        if (now.hour in MORNING_HOURS) {
            try {
                store.saveDayWeather(now.toLocalDate(), snapshot, overwrite = false)
            } catch (_: IOException) {
                // The history just lacks this morning's weather.
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_500L

        /** 04:00–11:59 Berlin time counts as "this morning". */
        val MORNING_HOURS = 4..11
    }
}
