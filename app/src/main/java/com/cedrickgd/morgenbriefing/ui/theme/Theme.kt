package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.ThemeMode

/**
 * App theme: [ThemeMode] decides light/dark, [AccentId] the palette (DYNAMIC = Material You on
 * API 31+, else MORGENROT). Warm neutral surfaces (light bg #FCFCFB / dark bg #151412),
 * serif display typography for headlines, sets status/navigation bar icon contrast.
 */
@Composable
fun MorgenbriefingTheme(
    themeMode: ThemeMode,
    accent: AccentId,
    content: @Composable () -> Unit,
) { TODO("agent C") }

/** Full M3 scheme derived from one seed color (used for every non-dynamic accent). */
fun accentColorScheme(seed: Color, dark: Boolean): ColorScheme = TODO("agent C")

/** True when the system "remove animations" setting is on — skip decorative motion. */
@Composable
fun rememberReduceMotion(): Boolean = TODO("agent C")
