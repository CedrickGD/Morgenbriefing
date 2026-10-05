package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.ui.graphics.Color
import com.cedrickgd.morgenbriefing.domain.Condition

/** What the sky shows — a coarser grouping of [Condition] for the backdrop. */
internal enum class SkyKind {
    CLEAR, FEW_CLOUDS, CLOUDY, OVERCAST, FOG, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, THUNDER;

    val isRainy: Boolean get() = this == DRIZZLE || this == RAIN || this == HEAVY_RAIN || this == THUNDER

    companion object {
        fun of(condition: Condition): SkyKind = when (condition) {
            Condition.CLEAR -> CLEAR
            Condition.MOSTLY_CLEAR -> FEW_CLOUDS
            Condition.PARTLY_CLOUDY -> CLOUDY
            Condition.OVERCAST -> OVERCAST
            Condition.FOG -> FOG
            Condition.DRIZZLE -> DRIZZLE
            Condition.RAIN, Condition.FREEZING_RAIN, Condition.SHOWERS -> RAIN
            Condition.HEAVY_RAIN -> HEAVY_RAIN
            Condition.SNOW, Condition.SNOW_SHOWERS -> SNOW
            Condition.THUNDER -> THUNDER
        }
    }
}

/**
 * Colors of one sky. [top] → [mid] is the base gradient (diagonal for sunny mornings, vertical
 * otherwise); the backdrop then fades into the theme background. Translucent colors carry their
 * alpha. Light-theme skies are pastel so dark text and dark status-bar icons stay readable on
 * them; dark-theme skies are deep and desaturated.
 */
internal data class SkyPalette(
    val top: Color,
    val mid: Color,
    /** Sun glow (day) or moon halo (night); [Color.Transparent] = none. */
    val glow: Color = Color.Transparent,
    val cloud: Color = Color.Transparent,
    /** Rain streaks, snow flakes or stars. */
    val particle: Color = Color.Transparent,
    val fog: Color = Color.Transparent,
    val moon: Color = Color.Transparent,
    val flash: Color = Color.Transparent,
)

internal fun skyPalette(kind: SkyKind, isDay: Boolean, dark: Boolean): SkyPalette = when {
    !dark && isDay -> lightDay(kind)
    !dark -> lightNight(kind)
    isDay -> darkDay(kind)
    else -> darkNight(kind)
}

private fun c(argb: Long): Color = Color(argb)
private fun c(argb: Long, alpha: Float): Color = Color(argb).copy(alpha = alpha)

private fun lightDay(kind: SkyKind): SkyPalette = when (kind) {
    SkyKind.CLEAR -> SkyPalette(
        top = c(0xFFF7D9A3), mid = c(0xFFCFE3F4),
        glow = c(0xFFFFE2A6, 0.9f), cloud = c(0xFFFFFFFF, 0.8f),
    )
    SkyKind.FEW_CLOUDS -> SkyPalette(
        top = c(0xFFF5DEB3), mid = c(0xFFD3E4F2),
        glow = c(0xFFFFE6B3, 0.75f), cloud = c(0xFFFFFFFF, 0.85f),
    )
    SkyKind.CLOUDY -> SkyPalette(
        top = c(0xFFD4DDE7), mid = c(0xFFE6EAEE),
        glow = c(0xFFFFF0CF, 0.5f), cloud = c(0xFFFFFFFF, 0.75f),
    )
    SkyKind.OVERCAST -> SkyPalette(
        top = c(0xFFD6D7DB), mid = c(0xFFE8E8E9),
        cloud = c(0xFFF7F7F8, 0.7f),
    )
    SkyKind.FOG -> SkyPalette(
        top = c(0xFFDDD9E5), mid = c(0xFFECEAF0),
        fog = c(0xFFFFFFFF, 0.6f),
    )
    SkyKind.DRIZZLE -> SkyPalette(
        top = c(0xFFC3CEDC), mid = c(0xFFDDE3EA),
        cloud = c(0xFFEEF1F5, 0.55f), particle = c(0xFF5A7392, 0.42f),
    )
    SkyKind.RAIN -> SkyPalette(
        top = c(0xFFB9C6D7), mid = c(0xFFD8DFE8),
        cloud = c(0xFFE3E8EE, 0.55f), particle = c(0xFF4F6A8C, 0.5f),
    )
    SkyKind.HEAVY_RAIN -> SkyPalette(
        top = c(0xFFB4C1D2), mid = c(0xFFD2DAE4),
        cloud = c(0xFFDCE2EA, 0.6f), particle = c(0xFF46617F, 0.55f),
    )
    SkyKind.SNOW -> SkyPalette(
        top = c(0xFFC9D5E5), mid = c(0xFFEAEFF5),
        cloud = c(0xFFF4F7FA, 0.6f), particle = c(0xFFFFFFFF, 0.95f),
    )
    SkyKind.THUNDER -> SkyPalette(
        top = c(0xFFC1BCD1), mid = c(0xFFD9D6E2),
        cloud = c(0xFFCFCBDB, 0.6f), particle = c(0xFF5B5873, 0.45f),
        flash = c(0xFFFFFFFF, 0.45f),
    )
}

private fun lightNight(kind: SkyKind): SkyPalette = when (kind) {
    SkyKind.CLEAR, SkyKind.FEW_CLOUDS -> SkyPalette(
        top = c(0xFFB8C0E0), mid = c(0xFFD9DDEF),
        glow = c(0xFFFFFFFF, 0.55f), cloud = c(0xFFE9ECF6, 0.55f),
        particle = c(0xFFFFFFFF, 0.95f), moon = c(0xFFFFFFFF, 0.95f),
    )
    SkyKind.CLOUDY -> SkyPalette(
        top = c(0xFFC2C8DA), mid = c(0xFFDDE0EA),
        cloud = c(0xFFE7EAF2, 0.6f), particle = c(0xFFFFFFFF, 0.8f),
    )
    SkyKind.OVERCAST -> SkyPalette(
        top = c(0xFFC8CAD4), mid = c(0xFFDFE0E6),
        cloud = c(0xFFE8E9EE, 0.6f),
    )
    SkyKind.FOG -> SkyPalette(
        top = c(0xFFCFCCDC), mid = c(0xFFE4E2EC),
        fog = c(0xFFFFFFFF, 0.55f),
    )
    SkyKind.DRIZZLE, SkyKind.RAIN, SkyKind.HEAVY_RAIN -> SkyPalette(
        top = c(0xFFB6C0D4), mid = c(0xFFD4DAE6),
        cloud = c(0xFFDDE2EC, 0.55f), particle = c(0xFF4C5F80, 0.5f),
    )
    SkyKind.SNOW -> SkyPalette(
        top = c(0xFFC4CEE0), mid = c(0xFFE4E9F2),
        cloud = c(0xFFEEF1F7, 0.55f), particle = c(0xFFFFFFFF, 0.95f),
    )
    SkyKind.THUNDER -> SkyPalette(
        top = c(0xFFC4BFD4), mid = c(0xFFD8D5E2),
        cloud = c(0xFFCBC7D8, 0.6f), particle = c(0xFF57546F, 0.45f),
        flash = c(0xFFFFFFFF, 0.45f),
    )
}

private fun darkDay(kind: SkyKind): SkyPalette = when (kind) {
    SkyKind.CLEAR -> SkyPalette(
        top = c(0xFF4A3B26), mid = c(0xFF1D2633),
        glow = c(0xFFF2B866, 0.11f), cloud = c(0xFFFFFFFF, 0.1f),
    )
    SkyKind.FEW_CLOUDS -> SkyPalette(
        top = c(0xFF463A28), mid = c(0xFF1E2733),
        glow = c(0xFFF2B866, 0.1f), cloud = c(0xFFDDE3EC, 0.12f),
    )
    SkyKind.CLOUDY -> SkyPalette(
        top = c(0xFF2C3440), mid = c(0xFF1E2228),
        glow = c(0xFFF2C27A, 0.08f), cloud = c(0xFFA9B3C2, 0.14f),
    )
    SkyKind.OVERCAST -> SkyPalette(
        top = c(0xFF2E2F33), mid = c(0xFF202124),
        cloud = c(0xFF9DA1A8, 0.13f),
    )
    SkyKind.FOG -> SkyPalette(
        top = c(0xFF35323D), mid = c(0xFF24222A),
        fog = c(0xFFB8B2C8, 0.16f),
    )
    SkyKind.DRIZZLE -> SkyPalette(
        top = c(0xFF26323F), mid = c(0xFF1B2028),
        cloud = c(0xFF8D9AAD, 0.12f), particle = c(0xFF9DB3CF, 0.32f),
    )
    SkyKind.RAIN -> SkyPalette(
        top = c(0xFF233142), mid = c(0xFF1A2029),
        cloud = c(0xFF8796AB, 0.13f), particle = c(0xFF94ABC9, 0.38f),
    )
    SkyKind.HEAVY_RAIN -> SkyPalette(
        top = c(0xFF1F2C3C), mid = c(0xFF181E27),
        cloud = c(0xFF8796AB, 0.14f), particle = c(0xFF8EA6C6, 0.45f),
    )
    SkyKind.SNOW -> SkyPalette(
        top = c(0xFF2B3442), mid = c(0xFF1E232B),
        cloud = c(0xFFB4BFCF, 0.12f), particle = c(0xFFF2F5FA, 0.85f),
    )
    SkyKind.THUNDER -> SkyPalette(
        top = c(0xFF2B2638), mid = c(0xFF1C1A23),
        cloud = c(0xFF8A84A0, 0.14f), particle = c(0xFF9C97B8, 0.35f),
        flash = c(0xFFCFC8FF, 0.16f),
    )
}

private fun darkNight(kind: SkyKind): SkyPalette = when (kind) {
    SkyKind.CLEAR, SkyKind.FEW_CLOUDS -> SkyPalette(
        top = c(0xFF0F1A36), mid = c(0xFF151B2B),
        glow = c(0xFFDDE6FF, 0.14f), cloud = c(0xFF8C97B5, 0.12f),
        particle = c(0xFFFFFFFF, 0.9f), moon = c(0xFFF2EEDC, 0.92f),
    )
    SkyKind.CLOUDY -> SkyPalette(
        top = c(0xFF1A2132), mid = c(0xFF171A22),
        cloud = c(0xFF7D879C, 0.14f), particle = c(0xFFFFFFFF, 0.6f),
    )
    SkyKind.OVERCAST -> SkyPalette(
        top = c(0xFF1E2027), mid = c(0xFF18191D),
        cloud = c(0xFF80848E, 0.12f),
    )
    SkyKind.FOG -> SkyPalette(
        top = c(0xFF24222D), mid = c(0xFF1A1920),
        fog = c(0xFFA8A2BA, 0.13f),
    )
    SkyKind.DRIZZLE, SkyKind.RAIN, SkyKind.HEAVY_RAIN -> SkyPalette(
        top = c(0xFF182232), mid = c(0xFF151A22),
        cloud = c(0xFF76839A, 0.12f), particle = c(0xFF8AA0C0, 0.38f),
    )
    SkyKind.SNOW -> SkyPalette(
        top = c(0xFF1D2533), mid = c(0xFF171B22),
        cloud = c(0xFF9AA5B8, 0.12f), particle = c(0xFFE8EDF5, 0.8f),
    )
    SkyKind.THUNDER -> SkyPalette(
        top = c(0xFF1E1A2A), mid = c(0xFF17151D),
        cloud = c(0xFF7A7590, 0.14f), particle = c(0xFF8F8AAA, 0.35f),
        flash = c(0xFFCFC8FF, 0.14f),
    )
}
