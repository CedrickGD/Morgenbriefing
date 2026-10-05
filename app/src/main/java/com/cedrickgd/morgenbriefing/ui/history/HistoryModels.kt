package com.cedrickgd.morgenbriefing.ui.history

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.DayWeather
import com.cedrickgd.morgenbriefing.data.model.HistoryEntry
import com.cedrickgd.morgenbriefing.data.model.HourPoint
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.Fmt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** All history days of one calendar month, newest first. */
@Immutable
data class MonthGroup(val month: YearMonth, val entries: List<HistoryEntry>)

/**
 * Groups [entries] by month for sticky headers. Defensive about the input: deduplicates by
 * date and sorts newest first, so headers never repeat. Pure, unit-tested.
 */
internal fun groupByMonth(entries: List<HistoryEntry>): List<MonthGroup> =
    entries
        .distinctBy { it.date }
        .sortedByDescending { it.date }
        .groupBy { YearMonth.from(it.date) }
        .map { (month, days) -> MonthGroup(month, days) }

/** Compact weather for a history row: condition emoji and "morning → warmest" temperatures. */
@Immutable
data class MiniWeather(val emoji: String, val morningTemp: Double, val maxTemp: Double)

/** Hour of the day window that best represents the day (closest to early afternoon). */
internal fun representativeHour(day: DayWeather): HourPoint? =
    day.hours.minByOrNull { abs(it.hour - REPRESENTATIVE_HOUR) }

/**
 * Mini weather from a stored morning snapshot: the emoji shows how the day turned out
 * (early afternoon in the day window, not the dark 5:30 sky), temperatures go from the
 * morning at home to the warmest point of the day window.
 */
internal fun miniWeatherOf(snapshot: WeatherSnapshot): MiniWeather {
    val hour = representativeHour(snapshot.day)
    val condition = Condition.fromCode(hour?.code ?: snapshot.home.current.code)
    return MiniWeather(
        emoji = condition.emoji(hour?.isDay ?: snapshot.home.current.isDay),
        morningTemp = snapshot.home.current.temp,
        maxTemp = snapshot.day.maxTemp,
    )
}

/** "Mo", "Di", … — fixed German abbreviations independent of the device locale. */
internal fun weekdayShort(day: DayOfWeek): String = WEEKDAYS_SHORT[day.value - 1]

/** "1 Meldung", "10 Meldungen"; null for 0. */
internal fun itemCountLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 Meldung"
    else -> "$count Meldungen"
}

/** "9° → 21°" */
internal fun MiniWeather.rangeLabel(): String = "${Fmt.temp(morningTemp)} → ${Fmt.temp(maxTemp)}"

/** "Oktober 2026 · 1 Tag", "Oktober 2026 · 12 Tage" */
internal fun monthPillLabel(month: YearMonth, days: Int): String {
    val name = Fmt.monthHeader(month.atDay(1))
    return when {
        days <= 0 -> name
        days == 1 -> "$name · 1 Tag"
        else -> "$name · $days Tage"
    }
}

/**
 * One row of the history timeline: a month pill or a day card. The timeline line runs from the
 * first month pill down to the dot of the last (oldest) day.
 */
@Immutable
sealed interface TimelineRow {
    val key: String

    data class Month(val month: YearMonth, val days: Int, val first: Boolean) : TimelineRow {
        override val key: String get() = "month-$month"
    }

    data class Day(val entry: HistoryEntry, val isToday: Boolean, val last: Boolean) : TimelineRow {
        override val key: String get() = "day-${entry.date}"
    }
}

/** Flattens the month groups into timeline rows (newest first). Pure, unit-tested. */
internal fun timelineRows(groups: List<MonthGroup>, today: LocalDate): List<TimelineRow> {
    val nonEmpty = groups.filter { it.entries.isNotEmpty() }
    val lastDay = nonEmpty.lastOrNull()?.entries?.lastOrNull()?.date
    return buildList {
        nonEmpty.forEachIndexed { index, group ->
            add(TimelineRow.Month(group.month, group.entries.size, first = index == 0))
            group.entries.forEach { entry ->
                add(TimelineRow.Day(entry, isToday = entry.date == today, last = entry.date == lastDay))
            }
        }
    }
}

private const val REPRESENTATIVE_HOUR = 14
private val WEEKDAYS_SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
