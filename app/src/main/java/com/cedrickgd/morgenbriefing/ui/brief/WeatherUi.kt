package com.cedrickgd.morgenbriefing.ui.brief

import android.util.Log
import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.ClothingAdvice
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.domain.WeatherInsights
import com.cedrickgd.morgenbriefing.domain.WeatherWarning

private const val TAG = "WeatherUi"

/**
 * Everything a screen shows about one [WeatherSnapshot], derived once per snapshot (off the
 * main thread, in the ViewModel) instead of on every recomposition.
 *
 * A text that cannot be derived degrades to empty/null so one odd snapshot never takes the
 * whole screen down.
 */
@Immutable
data class WeatherUi(
    val snapshot: WeatherSnapshot,
    /** Condition at home right now — drives the animated backdrop. */
    val condition: Condition,
    val isDay: Boolean,
    /** Serif headline; empty if it could not be derived. */
    val headline: String,
    val warnings: List<WeatherWarning>,
    val clothing: ClothingAdvice?,
    /** `HH:mm` of the fetch, for "aktualisiert 05:31"; empty if unknown. */
    val updatedClock: String,
)

/** Builds the [WeatherUi]; pure apart from logging, safe to call on any thread. */
fun WeatherSnapshot.toWeatherUi(): WeatherUi = WeatherUi(
    snapshot = this,
    condition = Condition.fromCode(home.current.code),
    isDay = home.current.isDay,
    headline = derive("headline", "") { WeatherInsights.headline(this) },
    warnings = derive("warnings", emptyList()) { WeatherInsights.warnings(this) },
    clothing = derive<ClothingAdvice?>("clothing", null) { WeatherInsights.clothing(this) },
    updatedClock = derive("clock", "") { Fmt.clock(fetchedAt) },
)

private inline fun <T> derive(what: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Could not derive $what from weather snapshot", e)
        fallback
    }
