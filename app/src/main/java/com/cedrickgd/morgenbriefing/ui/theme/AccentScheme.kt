package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Builds a complete Material 3 [ColorScheme] from a single seed color.
 *
 * Works in OkLCH so lightness steps look even across hues (a yellow and a blue at the same `l`
 * really appear equally light). Accent roles keep the seed's hue; every text-bearing pair is
 * *searched* for contrast instead of being picked from a fixed tone, so even awkward seeds such
 * as a bright yellow end up readable:
 *
 * * `primary` ≥ 4.5:1 against `onPrimary`, the background and card surfaces,
 * * `on*Container` ≥ 7:1 against their containers,
 * * `secondary` = desaturated seed, `tertiary` = seed hue rotated by +60°.
 *
 * Surfaces are the shared [WarmNeutrals]; error roles are the Material 3 defaults.
 */
internal object AccentScheme {

    /** A little headroom above 4.5:1 so 8-bit rounding can never push a pair below the limit. */
    private const val TEXT = MIN_TEXT_CONTRAST + 0.1
    private const val STRONG_TEXT = 7.0
    private const val STEP = 0.005
    private const val TERTIARY_HUE_SHIFT = 60.0

    fun build(seed: Color, dark: Boolean): ColorScheme {
        val lch = ColorMath.toOkLch(seed)
        return if (dark) dark(lch) else light(lch)
    }

    // ---------------------------------------------------------------------------------------
    // Light
    // ---------------------------------------------------------------------------------------

    private fun light(seed: OkLch): ColorScheme {
        val n = WarmNeutrals.Light
        val h = seed.h
        val c = seed.c
        val secondaryChroma = (c * 0.35).coerceIn(0.012, 0.05)
        val tertiaryHue = (h + TERTIARY_HUE_SHIFT) % 360.0
        val tertiaryChroma = (c * 0.8).coerceIn(0.04, 0.14)

        val primary = lightAccent(h, c.coerceAtMost(0.2), start = seed.l.coerceAtMost(0.62), n)
        val primaryContainer = ColorMath.fromOkLch(0.91, (c * 0.5).coerceAtMost(0.075), h)
        val onPrimaryContainer = onLightContainer(h, c.coerceAtMost(0.12), primaryContainer)

        val secondary = lightAccent(h, secondaryChroma, start = 0.5, n)
        val secondaryContainer = ColorMath.fromOkLch(0.925, secondaryChroma.coerceAtMost(0.03), h)
        val onSecondaryContainer = onLightContainer(h, secondaryChroma, secondaryContainer)

        val tertiary = lightAccent(tertiaryHue, tertiaryChroma, start = 0.52, n)
        val tertiaryContainer = ColorMath.fromOkLch(0.91, (tertiaryChroma * 0.55).coerceAtMost(0.07), tertiaryHue)
        val onTertiaryContainer = onLightContainer(tertiaryHue, tertiaryChroma, tertiaryContainer)

        val defaults = lightColorScheme()
        val fixed = FixedRoles.of(seed, secondaryChroma, tertiaryHue, tertiaryChroma)
        return lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = darkAccent(h, c.coerceAtMost(0.14), start = 0.8, WarmNeutrals.Dark),
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = n.background,
            onBackground = n.onSurface,
            surface = n.background,
            onSurface = n.onSurface,
            surfaceVariant = n.surfaceContainerHighest,
            onSurfaceVariant = n.onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = n.inverseSurface,
            inverseOnSurface = n.inverseOnSurface,
            error = defaults.error,
            onError = defaults.onError,
            errorContainer = defaults.errorContainer,
            onErrorContainer = defaults.onErrorContainer,
            outline = n.outline,
            outlineVariant = n.outlineVariant,
            scrim = Color.Black,
            surfaceBright = n.surfaceBright,
            surfaceContainer = n.surfaceContainer,
            surfaceContainerHigh = n.surfaceContainerHigh,
            surfaceContainerHighest = n.surfaceContainerHighest,
            surfaceContainerLow = n.surfaceContainerLow,
            surfaceContainerLowest = n.surfaceContainerLowest,
            surfaceDim = n.surfaceDim,
            primaryFixed = fixed.primaryFixed,
            primaryFixedDim = fixed.primaryFixedDim,
            onPrimaryFixed = fixed.onPrimaryFixed,
            onPrimaryFixedVariant = fixed.onPrimaryFixedVariant,
            secondaryFixed = fixed.secondaryFixed,
            secondaryFixedDim = fixed.secondaryFixedDim,
            onSecondaryFixed = fixed.onSecondaryFixed,
            onSecondaryFixedVariant = fixed.onSecondaryFixedVariant,
            tertiaryFixed = fixed.tertiaryFixed,
            tertiaryFixedDim = fixed.tertiaryFixedDim,
            onTertiaryFixed = fixed.onTertiaryFixed,
            onTertiaryFixedVariant = fixed.onTertiaryFixedVariant,
        )
    }

    /** Darkens from [start] until white text works on it and it reads as text on the surfaces. */
    private fun lightAccent(hue: Double, chroma: Double, start: Double, n: WarmNeutrals): Color =
        ColorMath.searchLightness(hue, chroma, start, -STEP) {
            it.contrastAgainst(Color.White) >= TEXT &&
                it.contrastAgainst(n.background) >= TEXT &&
                it.contrastAgainst(n.surfaceContainer) >= TEXT
        }

    private fun onLightContainer(hue: Double, chroma: Double, container: Color): Color =
        ColorMath.searchLightness(hue, chroma, start = 0.36, step = -STEP) {
            it.contrastAgainst(container) >= STRONG_TEXT
        }

    // ---------------------------------------------------------------------------------------
    // Dark
    // ---------------------------------------------------------------------------------------

    private fun dark(seed: OkLch): ColorScheme {
        val n = WarmNeutrals.Dark
        val h = seed.h
        val c = seed.c
        val secondaryChroma = (c * 0.35).coerceIn(0.012, 0.05)
        val tertiaryHue = (h + TERTIARY_HUE_SHIFT) % 360.0
        val tertiaryChroma = (c * 0.8).coerceIn(0.04, 0.14)

        val primary = darkAccent(h, c.coerceAtMost(0.14), start = seed.l.coerceAtLeast(0.78), n)
        val onPrimary = onDarkAccent(h, (c * 0.55).coerceAtMost(0.08), primary)
        val primaryContainer = ColorMath.fromOkLch(0.36, (c * 0.7).coerceAtMost(0.1), h)
        val onPrimaryContainer = onDarkContainer(h, (c * 0.4).coerceAtMost(0.06), primaryContainer)

        val secondary = darkAccent(h, secondaryChroma, start = 0.8, n)
        val onSecondary = onDarkAccent(h, secondaryChroma, secondary)
        val secondaryContainer = ColorMath.fromOkLch(0.34, secondaryChroma.coerceAtMost(0.04), h)
        val onSecondaryContainer = onDarkContainer(h, secondaryChroma.coerceAtMost(0.03), secondaryContainer)

        val tertiary = darkAccent(tertiaryHue, tertiaryChroma.coerceAtMost(0.12), start = 0.8, n)
        val onTertiary = onDarkAccent(tertiaryHue, (tertiaryChroma * 0.55).coerceAtMost(0.07), tertiary)
        val tertiaryContainer = ColorMath.fromOkLch(0.36, (tertiaryChroma * 0.7).coerceAtMost(0.09), tertiaryHue)
        val onTertiaryContainer = onDarkContainer(tertiaryHue, (tertiaryChroma * 0.4).coerceAtMost(0.06), tertiaryContainer)

        val defaults = darkColorScheme()
        val fixed = FixedRoles.of(seed, secondaryChroma, tertiaryHue, tertiaryChroma)
        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = lightAccent(h, c.coerceAtMost(0.2), start = seed.l.coerceAtMost(0.62), WarmNeutrals.Light),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = n.background,
            onBackground = n.onSurface,
            surface = n.background,
            onSurface = n.onSurface,
            surfaceVariant = n.surfaceContainerHighest,
            onSurfaceVariant = n.onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = n.inverseSurface,
            inverseOnSurface = n.inverseOnSurface,
            error = defaults.error,
            onError = defaults.onError,
            errorContainer = defaults.errorContainer,
            onErrorContainer = defaults.onErrorContainer,
            outline = n.outline,
            outlineVariant = n.outlineVariant,
            scrim = Color.Black,
            surfaceBright = n.surfaceBright,
            surfaceContainer = n.surfaceContainer,
            surfaceContainerHigh = n.surfaceContainerHigh,
            surfaceContainerHighest = n.surfaceContainerHighest,
            surfaceContainerLow = n.surfaceContainerLow,
            surfaceContainerLowest = n.surfaceContainerLowest,
            surfaceDim = n.surfaceDim,
            primaryFixed = fixed.primaryFixed,
            primaryFixedDim = fixed.primaryFixedDim,
            onPrimaryFixed = fixed.onPrimaryFixed,
            onPrimaryFixedVariant = fixed.onPrimaryFixedVariant,
            secondaryFixed = fixed.secondaryFixed,
            secondaryFixedDim = fixed.secondaryFixedDim,
            onSecondaryFixed = fixed.onSecondaryFixed,
            onSecondaryFixedVariant = fixed.onSecondaryFixedVariant,
            tertiaryFixed = fixed.tertiaryFixed,
            tertiaryFixedDim = fixed.tertiaryFixedDim,
            onTertiaryFixed = fixed.onTertiaryFixed,
            onTertiaryFixedVariant = fixed.onTertiaryFixedVariant,
        )
    }

    /** Lightens from [start] until it reads as text on the dark background and cards. */
    private fun darkAccent(hue: Double, chroma: Double, start: Double, n: WarmNeutrals): Color =
        ColorMath.searchLightness(hue, chroma, start, STEP) {
            it.contrastAgainst(n.background) >= STRONG_TEXT &&
                it.contrastAgainst(n.surfaceContainerHigh) >= TEXT
        }

    private fun onDarkAccent(hue: Double, chroma: Double, accent: Color): Color =
        ColorMath.searchLightness(hue, chroma, start = 0.28, step = -STEP) {
            it.contrastAgainst(accent) >= TEXT
        }

    private fun onDarkContainer(hue: Double, chroma: Double, container: Color): Color =
        ColorMath.searchLightness(hue, chroma, start = 0.9, step = STEP) {
            it.contrastAgainst(container) >= STRONG_TEXT
        }

    /** M3 "fixed" roles keep the same tone in light and dark themes. */
    private class FixedRoles(
        val primaryFixed: Color,
        val primaryFixedDim: Color,
        val onPrimaryFixed: Color,
        val onPrimaryFixedVariant: Color,
        val secondaryFixed: Color,
        val secondaryFixedDim: Color,
        val onSecondaryFixed: Color,
        val onSecondaryFixedVariant: Color,
        val tertiaryFixed: Color,
        val tertiaryFixedDim: Color,
        val onTertiaryFixed: Color,
        val onTertiaryFixedVariant: Color,
    ) {
        companion object {
            fun of(seed: OkLch, secondaryChroma: Double, tertiaryHue: Double, tertiaryChroma: Double): FixedRoles {
                val h = seed.h
                val c = seed.c
                return FixedRoles(
                    primaryFixed = ColorMath.fromOkLch(0.9, (c * 0.5).coerceAtMost(0.08), h),
                    primaryFixedDim = ColorMath.fromOkLch(0.8, c.coerceAtMost(0.12), h),
                    onPrimaryFixed = ColorMath.fromOkLch(0.22, c.coerceAtMost(0.08), h),
                    onPrimaryFixedVariant = ColorMath.fromOkLch(0.36, c.coerceAtMost(0.12), h),
                    secondaryFixed = ColorMath.fromOkLch(0.9, secondaryChroma.coerceAtMost(0.03), h),
                    secondaryFixedDim = ColorMath.fromOkLch(0.8, secondaryChroma, h),
                    onSecondaryFixed = ColorMath.fromOkLch(0.22, secondaryChroma, h),
                    onSecondaryFixedVariant = ColorMath.fromOkLch(0.36, secondaryChroma, h),
                    tertiaryFixed = ColorMath.fromOkLch(0.9, (tertiaryChroma * 0.55).coerceAtMost(0.07), tertiaryHue),
                    tertiaryFixedDim = ColorMath.fromOkLch(0.8, tertiaryChroma.coerceAtMost(0.12), tertiaryHue),
                    onTertiaryFixed = ColorMath.fromOkLch(0.22, tertiaryChroma.coerceAtMost(0.08), tertiaryHue),
                    onTertiaryFixedVariant = ColorMath.fromOkLch(0.36, tertiaryChroma.coerceAtMost(0.12), tertiaryHue),
                )
            }
        }
    }
}
