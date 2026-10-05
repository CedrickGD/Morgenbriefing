package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.history.representativeHour
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.floor

/**
 * Geometry of the topic tile grid (Settings → "Themen"): where slot *i* sits and which slot a
 * point falls into while a tile is dragged. Pure, unit-tested. All values in pixels.
 */
@Immutable
internal data class GridSlots(
    val columns: Int,
    val count: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val gap: Float,
) {
    val rows: Int get() = if (count <= 0 || columns <= 0) 0 else (count + columns - 1) / columns

    /** Total height of all rows including the gaps between them. */
    val height: Float get() = if (rows == 0) 0f else rows * cellHeight + (rows - 1) * gap

    /** Top-left corner of slot [index]. */
    fun x(index: Int): Float = (index % columns) * (cellWidth + gap)

    fun y(index: Int): Float = (index / columns) * (cellHeight + gap)

    /** Center of slot [index]. */
    fun centerX(index: Int): Float = x(index) + cellWidth / 2f

    fun centerY(index: Int): Float = y(index) + cellHeight / 2f

    /**
     * The slot under the point ([px], [py]); points in a gap belong to the nearer slot and
     * points outside the grid to the nearest edge slot. The last row may be incomplete — a
     * point behind its last tile maps to the last slot.
     */
    fun indexAt(px: Float, py: Float): Int {
        if (count <= 0 || columns <= 0) return -1
        val column = floor((px + gap / 2f) / (cellWidth + gap)).toInt().coerceIn(0, columns - 1)
        val row = floor((py + gap / 2f) / (cellHeight + gap)).toInt().coerceIn(0, rows - 1)
        return (row * columns + column).coerceAtMost(count - 1)
    }
}

/** Live mini weather on a place card: "☀️" + "14°" + "jetzt". */
@Immutable
internal data class PlaceWeather(val emoji: String, val temp: String, val caption: String)

/**
 * Mini weather for the two place cards from the last stored snapshot. A snapshot of a place the
 * user has since changed is not shown (null) — better no number than a wrong one. Home shows
 * the current temperature ("vor 12 Min."), the day place the warmest point of its window.
 */
internal fun placeWeather(
    snapshot: WeatherSnapshot?,
    home: Place,
    day: Place,
    now: ZonedDateTime,
): Pair<PlaceWeather?, PlaceWeather?> {
    if (snapshot == null) return null to null
    val homeWeather = if (snapshot.home.place.sameSpotAs(home)) {
        val current = snapshot.home.current
        PlaceWeather(
            emoji = Condition.fromCode(current.code).emoji(current.isDay),
            temp = Fmt.temp(current.temp),
            caption = Fmt.relative(snapshot.fetchedAt, now) ?: "zuletzt",
        )
    } else {
        null
    }
    val dayWeather = if (snapshot.day.place.sameSpotAs(day) && snapshot.day.hours.isNotEmpty()) {
        val hour = representativeHour(snapshot.day)
        val windowDate = runCatching { LocalDate.parse(snapshot.day.date) }.getOrNull()
        val today = now.toLocalDate()
        PlaceWeather(
            emoji = Condition.fromCode(hour?.code ?: OVERCAST_CODE).emoji(hour?.isDay ?: true),
            temp = Fmt.temp(snapshot.day.maxTemp),
            caption = when (windowDate) {
                today -> "max. heute"
                today.plusDays(1) -> "max. morgen"
                else -> "zuletzt"
            },
        )
    } else {
        null
    }
    return homeWeather to dayWeather
}

private const val OVERCAST_CODE = 3
