package com.cedrickgd.morgenbriefing.ui.brief

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.domain.Fmt
import com.cedrickgd.morgenbriefing.ui.news.NewsFeed
import com.cedrickgd.morgenbriefing.ui.news.articleCountLabel
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import java.time.LocalDate

/** True when [brief] belongs to [day] (by its Berlin date). Malformed dates count as "not". */
internal fun isBriefFor(brief: Brief, day: LocalDate): Boolean = parseDateOrNull(brief.date) == day

/** "27 Meldungen · 11 Themen" for the visible part of a brief; empty when nothing is visible. */
internal fun feedSummary(feed: NewsFeed): String {
    val count = feed.sections.sumOf { it.count }
    if (count == 0) return ""
    val topics = feed.sections.size
    return "${articleCountLabel(count)} · ${if (topics == 1) "1 Thema" else "$topics Themen"}"
}

/** The calm last line of a page: "Stand 05:07 · Wetter: Open-Meteo". */
internal fun footerLine(brief: Brief?, hasWeather: Boolean, weatherOffline: Boolean): String = buildList {
    brief?.generatedAt?.let(Fmt::clock)?.takeIf { it.isNotBlank() }?.let { add("Stand $it") }
    if (hasWeather) add(if (weatherOffline) "Wetter: Open-Meteo (offline)" else "Wetter: Open-Meteo")
}.joinToString(" · ")

/**
 * Ids of the sections whose "+ Mehr" block is open. Survives configuration changes and
 * navigating to the reader and back; starts closed again for a different brief ([briefKey]).
 */
@Composable
internal fun rememberExpandedSections(briefKey: String?): MutableState<Set<String>> =
    rememberSaveable(briefKey, saver = ExpandedSectionsSaver) { mutableStateOf(emptySet()) }

private val ExpandedSectionsSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)
