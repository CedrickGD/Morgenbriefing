package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.cedrickgd.morgenbriefing.data.model.AccentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccentColorSchemeTest {

    private val modes = listOf(false, true)

    private fun forEveryScheme(block: (name: String, scheme: ColorScheme, dark: Boolean) -> Unit) {
        for (accent in AccentId.entries) {
            for (dark in modes) {
                val name = "${accent.name}/${if (dark) "dark" else "light"}"
                block(name, accentColorScheme(Color(accent.seed), dark), dark)
            }
        }
    }

    private fun assertContrast(name: String, role: String, a: Color, b: Color, min: Double) {
        val ratio = ColorMath.contrastRatio(a, b)
        assertTrue("$name: $role contrast ${"%.2f".format(ratio)} < $min", ratio >= min)
    }

    @Test
    fun primaryIsReadableWithItsOnColorAndOnTheBackground() = forEveryScheme { name, s, _ ->
        assertContrast(name, "primary/onPrimary", s.primary, s.onPrimary, MIN_TEXT_CONTRAST)
        assertContrast(name, "primary/background", s.primary, s.background, MIN_TEXT_CONTRAST)
        assertContrast(name, "primary/surfaceContainer", s.primary, s.surfaceContainer, MIN_TEXT_CONTRAST)
    }

    @Test
    fun secondaryAndTertiaryAreReadable() = forEveryScheme { name, s, _ ->
        assertContrast(name, "secondary/onSecondary", s.secondary, s.onSecondary, MIN_TEXT_CONTRAST)
        assertContrast(name, "tertiary/onTertiary", s.tertiary, s.onTertiary, MIN_TEXT_CONTRAST)
        assertContrast(name, "secondary/background", s.secondary, s.background, MIN_TEXT_CONTRAST)
        assertContrast(name, "tertiary/background", s.tertiary, s.background, MIN_TEXT_CONTRAST)
    }

    @Test
    fun containersCarryStrongText() = forEveryScheme { name, s, _ ->
        assertContrast(name, "primaryContainer", s.onPrimaryContainer, s.primaryContainer, 7.0)
        assertContrast(name, "secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 7.0)
        assertContrast(name, "tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 7.0)
        assertContrast(name, "errorContainer", s.onErrorContainer, s.errorContainer, MIN_TEXT_CONTRAST)
        assertContrast(name, "fixed", s.onPrimaryFixed, s.primaryFixed, 7.0)
    }

    @Test
    fun surfacesAreTheWarmNeutrals() = forEveryScheme { name, s, dark ->
        val n = WarmNeutrals.of(dark)
        assertEquals(name, n.background, s.background)
        assertEquals(name, n.surfaceContainerLowest, s.surfaceContainerLowest)
        assertEquals(name, n.surfaceContainerLow, s.surfaceContainerLow)
        assertEquals(name, n.surfaceContainer, s.surfaceContainer)
        assertEquals(name, n.surfaceContainerHigh, s.surfaceContainerHigh)
        assertEquals(name, n.surfaceContainerHighest, s.surfaceContainerHighest)
        assertEquals(name, n.onSurface, s.onSurface)
        assertEquals(name, n.onSurfaceVariant, s.onSurfaceVariant)
        assertEquals(name, n.outline, s.outline)
        assertEquals(name, n.outlineVariant, s.outlineVariant)
        assertEquals(name, s.primary, s.surfaceTint)
        assertContrast(name, "onSurface/background", s.onSurface, s.background, 7.0)
        assertContrast(name, "onSurfaceVariant/surfaceContainerLow", s.onSurfaceVariant, s.surfaceContainerLow, MIN_TEXT_CONTRAST)
    }

    @Test
    fun accentKeepsTheSeedHue() {
        for (accent in AccentId.entries) {
            val seed = ColorMath.toOkLch(Color(accent.seed))
            if (seed.c < 0.05) continue // near-grey seeds have no meaningful hue
            for (dark in modes) {
                val primary = ColorMath.toOkLch(accentColorScheme(Color(accent.seed), dark).primary)
                val diff = abs(((primary.h - seed.h + 540.0) % 360.0) - 180.0)
                assertTrue("${accent.name}: hue drifted by $diff°", diff < 12.0)
            }
        }
    }

    @Test
    fun darkAndLightSchemesDiffer() {
        val seed = Color(AccentId.OZEAN.seed)
        val light = accentColorScheme(seed, dark = false)
        val dark = accentColorScheme(seed, dark = true)
        assertTrue(ColorMath.relativeLuminance(dark.primary) > ColorMath.relativeLuminance(light.primary))
        assertTrue(ColorMath.relativeLuminance(dark.background) < ColorMath.relativeLuminance(light.background))
    }
}
