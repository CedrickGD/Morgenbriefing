package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The neutral surfaces shared by every non-dynamic accent: warm and paper-like in light mode,
 * a deep "Morgenglas" night blue in dark mode (docs/DESIGN.md §1), so menus, sheets, dialogs
 * and the reader belong to the same night sky as the pages. They stay the same no matter which
 * accent is chosen, so the app always feels calm and only the accent changes.
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
            background = Color(0xFF0F1320),
            surfaceContainerLowest = Color(0xFF0B0E18),
            surfaceContainerLow = Color(0xFF151A28),
            surfaceContainer = Color(0xFF1A2133),
            surfaceContainerHigh = Color(0xFF222A3E),
            surfaceContainerHighest = Color(0xFF2B344A),
            surfaceDim = Color(0xFF0F1320),
            surfaceBright = Color(0xFF343E56),
            onSurface = Color(0xFFE9EBF2),
            onSurfaceVariant = Color(0xFFB2B7C6),
            outline = Color(0xFF868C9D),
            outlineVariant = Color(0xFF323A4F),
            inverseSurface = Color(0xFFE3E5EC),
            inverseOnSurface = Color(0xFF2B2F3A),
        )

        fun of(dark: Boolean): WarmNeutrals = if (dark) Dark else Light
    }
}
