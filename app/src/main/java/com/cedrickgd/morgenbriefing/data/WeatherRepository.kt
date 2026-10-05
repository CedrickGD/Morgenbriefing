package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.data.remote.OpenMeteoClient

class WeatherRepository(
    private val client: OpenMeteoClient,
    private val store: BriefStore,
) {
    /**
     * Live fetch (one retry after 1.5 s on failure). On success also saves it as latest and,
     * between 04:00 and 11:59 local time, as today's morning snapshot (first one wins).
     */
    suspend fun load(settings: AppSettings): Result<WeatherSnapshot> = TODO("agent A")

    /** Last successful snapshot, for offline start. */
    suspend fun cached(): WeatherSnapshot? = TODO("agent A")

    suspend fun search(query: String): Result<List<Place>> = TODO("agent A")
}
