package com.cedrickgd.morgenbriefing.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.domain.Condition
import com.cedrickgd.morgenbriefing.ui.theme.MorgenbriefingTheme

// Previews use the fixtures in PreviewData. Components that call into the domain layer
// (Fmt, WeatherInsights) render once those are implemented.

@Preview(name = "Hell", showBackground = true, widthDp = 380)
@Preview(name = "Dunkel", showBackground = true, widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ThemePreviews

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    }
}

@ThemePreviews
@Composable
private fun TodayTopPreview() {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box {
                WeatherBackdrop(
                    condition = Condition.FOG,
                    isDay = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 48.dp)) {
                    TodayHeader(
                        date = PreviewData.date,
                        greeting = "Guten Morgen",
                        headline = "Nebel zum Start, dann goldener Oktober mit 21°.",
                    )
                    Spacer(Modifier.height(20.dp))
                    HomeWeatherHero(PreviewData.snapshot)
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SkyGalleryPreview() {
    MorgenbriefingTheme(themeMode = ThemeMode.SYSTEM, accent = AccentId.MORGENROT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(8.dp)) {
                listOf(
                    listOf(Condition.CLEAR to true, Condition.PARTLY_CLOUDY to true, Condition.FOG to true),
                    listOf(Condition.RAIN to true, Condition.SNOW to true, Condition.THUNDER to true),
                    listOf(Condition.CLEAR to false, Condition.OVERCAST to false, Condition.DRIZZLE to false),
                ).forEach { row ->
                    Row {
                        row.forEach { (condition, isDay) ->
                            WeatherBackdrop(condition, isDay, Modifier.size(width = 120.dp, height = 150.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun DayCurveCardPreview() {
    PreviewSurface { DayCurveCard(PreviewData.snapshot) }
}

@ThemePreviews
@Composable
private fun ClothingAndWarningsPreview() {
    PreviewSurface {
        Column {
            WarningBanner(PreviewData.warnings)
            Spacer(Modifier.height(16.dp))
            ClothingCard(PreviewData.clothing)
        }
    }
}

@ThemePreviews
@Composable
private fun NewsPreview() {
    PreviewSurface {
        Column {
            NewsTeaser(teaser = PreviewData.TEASER, generatedAt = PreviewData.GENERATED_AT, stale = true)
            Spacer(Modifier.height(24.dp))
            NewsSectionBlock(
                section = PreviewData.section,
                now = PreviewData.now,
                onOpen = {},
                onShare = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun StatesPreview() {
    PreviewSurface {
        Column {
            SectionLabel("Einrichtung")
            Spacer(Modifier.height(8.dp))
            InfoCard(
                emoji = "🔑",
                title = "Noch kein GitHub-Token",
                text = "Trag deinen Token in den Einstellungen ein, dann kommen hier jeden Morgen frische News an.",
                actionLabel = "Zu den Einstellungen",
                onAction = {},
            )
            Spacer(Modifier.height(24.dp))
            LoadingSkeleton()
        }
    }
}
