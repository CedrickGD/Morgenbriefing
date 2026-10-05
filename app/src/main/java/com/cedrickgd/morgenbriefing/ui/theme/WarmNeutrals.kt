package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The warm, paper-like neutral surfaces shared by every non-dynamic accent. They stay the same
 * no matter which accent is chosen, so the app always feels calm and only the accent changes.
 */
internal data class WarmNeutrals(
    val background: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
) {
    companion object {
        val Light = WarmNeutrals(
            background = Color(0xFFFCFCFB),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF6F5F1),
            surfaceContainer = Color(0xFFF1F0EB),
            surfaceContainerHigh = Color(0xFFEBE9E3),
            surfaceContainerHighest = Color(0xFFE4E2DB),
            surfaceDim = Color(0xFFDDDBD4),
            surfaceBright = Color(0xFFFCFCFB),
            onSurface = Color(0xFF2E2C27),
            onSurfaceVariant = Color(0xFF6B6A63),
            outline = Color(0xFF8F8D85),
            outlineVariant = Color(0xFFE4E3DC),
            inverseSurface = Color(0xFF32302C),
            inverseOnSurface = Color(0xFFF4F2EC),
        )

        val Dark = WarmNeutrals(
            background = Color(0xFF151412),
            surfaceContainerLowest = Color(0xFF100F0E),
            surfaceContainerLow = Color(0xFF1B1A18),
            surfaceContainer = Color(0xFF201F1C),
            surfaceContainerHigh = Color(0xFF2A2925),
            surfaceContainerHighest = Color(0xFF353430),
            surfaceDim = Color(0xFF151412),
            surfaceBright = Color(0xFF3B3A35),
            onSurface = Color(0xFFECEAE4),
            onSurfaceVariant = Color(0xFFB3B0A6),
            outline = Color(0xFF8A877E),
            outlineVariant = Color(0xFF3A3833),
            inverseSurface = Color(0xFFE6E3DC),
            inverseOnSurface = Color(0xFF32302C),
        )

        fun of(dark: Boolean): WarmNeutrals = if (dark) Dark else Light
    }
}
