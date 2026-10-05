package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.domain.ClothingAdvice
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.domain.WeatherWarning
import java.time.LocalDate
import java.time.ZonedDateTime

// Signatures are the contract between agent C (implements) and agent D (uses).
// Agent C may split these into several files in this package and delete this stub.

/** Overline date + greeting + serif headline. */
@Composable
fun TodayHeader(date: LocalDate, greeting: String, headline: String, modifier: Modifier = Modifier) { TODO("agent C") }

/** Animated sky behind the top of the screen (edge-to-edge, under the status bar). */
@Composable
fun WeatherBackdrop(condition: Condition, isDay: Boolean, modifier: Modifier = Modifier, animate: Boolean = true) { TODO("agent C") }

/** "Vor der Tür · Bad Düben": big temperature, feels-like, condition, chips, home note. */
@Composable
fun HomeWeatherHero(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) { TODO("agent C") }

/** "Leipzig · 10–18 Uhr": hand-drawn temperature curve + rain bars + day summary. */
@Composable
fun DayCurveCard(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) { TODO("agent C") }

@Composable
fun ClothingCard(advice: ClothingAdvice, modifier: Modifier = Modifier) { TODO("agent C") }

/** Renders nothing for an empty list. */
@Composable
fun WarningBanner(warnings: List<WeatherWarning>, modifier: Modifier = Modifier) { TODO("agent C") }

/** The brief's teaser sentence + "Stand 05:07" / stale hint. */
@Composable
fun NewsTeaser(teaser: String, generatedAt: String?, stale: Boolean, modifier: Modifier = Modifier) { TODO("agent C") }

@Composable
fun NewsSectionBlock(
    section: NewsSection,
    now: ZonedDateTime,
    onOpen: (NewsItem) -> Unit,
    onShare: (NewsItem) -> Unit,
    modifier: Modifier = Modifier,
) { TODO("agent C") }

/** Generic friendly card for empty/error/setup states. */
@Composable
fun InfoCard(
    emoji: String,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) { TODO("agent C") }

/** Shimmering placeholder shaped like hero + curve + news while the first load runs. */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) { TODO("agent C") }

/** Small uppercase label used above cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) { TODO("agent C") }
