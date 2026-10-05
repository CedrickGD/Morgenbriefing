package com.cedrickgd.morgenbriefing.data.model

import java.time.LocalDate

enum class BriefErrorKind {
    /** No GitHub token configured yet — the app shows the demo brief. */
    NO_TOKEN,
    /** 401/403: token wrong, expired or without access to the repo. */
    UNAUTHORIZED,
    /** 404: the briefs branch or the file does not exist (yet). */
    NOT_FOUND,
    NETWORK,
    PARSE,
    UNKNOWN,
}

class BriefSourceException(val kind: BriefErrorKind, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: kind.name, cause)

sealed interface BriefLoad {
    /** [brief] may be older than today — compare `brief.date` with today to show "noch nicht da". */
    data class Loaded(val brief: Brief, val fromCache: Boolean) : BriefLoad

    /** No token yet: bundled sample so the app is never empty. */
    data class Demo(val brief: Brief) : BriefLoad

    data class Failed(val error: BriefErrorKind, val cached: Brief?) : BriefLoad
}

data class HistoryEntry(
    val date: LocalDate,
    val teaser: String,
    val itemCount: Int,
    /** The morning snapshot stored that day, if the app was installed then. */
    val weather: WeatherSnapshot?,
    /** Brief is available offline. */
    val cached: Boolean,
)

data class DayBundle(
    val date: LocalDate,
    val brief: Brief?,
    val weather: WeatherSnapshot?,
)
