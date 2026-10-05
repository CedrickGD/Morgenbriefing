package com.cedrickgd.morgenbriefing.data

import kotlinx.serialization.json.Json
import java.time.ZoneId

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** The app's reference time zone: everything is "morning in Germany". */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
