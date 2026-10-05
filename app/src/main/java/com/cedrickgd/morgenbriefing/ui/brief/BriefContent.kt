package com.cedrickgd.morgenbriefing.ui.brief

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.ui.components.NewsSectionBlock
import com.cedrickgd.morgenbriefing.ui.components.NewsTeaser
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import java.time.LocalDate
import java.time.ZonedDateTime

/** Sections that actually contain news; empty ones are not worth a card. */
internal fun Brief.visibleSections(): List<NewsSection> = sections.filter { it.items.isNotEmpty() }

/** True when [brief] belongs to [day] (by its Berlin date). Malformed dates count as "not". */
internal fun isBriefFor(brief: Brief, day: LocalDate): Boolean = parseDateOrNull(brief.date) == day

/** Teaser card for a brief; [stale] shows the "not today's" hint. */
internal fun LazyListScope.briefTeaserItem(brief: Brief, stale: Boolean, keyPrefix: String) {
    item(key = "$keyPrefix-teaser", contentType = "teaser") {
        NewsTeaser(
            teaser = brief.teaser,
            generatedAt = brief.generatedAt,
            stale = stale,
            modifier = Modifier.animateItem(),
        )
    }
}

/** One [NewsSectionBlock] per non-empty section of [brief]. */
internal fun LazyListScope.briefSectionItems(
    brief: Brief,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    keyPrefix: String,
) {
    brief.sections.forEachIndexed { index, section ->
        if (section.items.isEmpty()) return@forEachIndexed
        item(key = "$keyPrefix-section-$index-${section.id}", contentType = "section") {
            NewsSectionBlock(
                section = section,
                now = now,
                onOpen = onOpen,
                onShare = onShare,
                modifier = Modifier.animateItem(),
            )
        }
    }
}
