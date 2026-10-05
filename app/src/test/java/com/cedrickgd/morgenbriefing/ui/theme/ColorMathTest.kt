package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    @Test
    fun contrastOfBlackAndWhiteIs21() {
        assertEquals(21.0, ColorMath.contrastRatio(Color.Black, Color.White), 1e-6)
        assertEquals(1.0, ColorMath.contrastRatio(Color.White, Color.White), 1e-9)
        // Order does not matter.
        assertEquals(
            ColorMath.contrastRatio(Color(0xFF2F6FDE), Color.White),
            ColorMath.contrastRatio(Color.White, Color(0xFF2F6FDE)),
            1e-12,
        )
    }

    @Test
    fun knownContrastValue() {
        // #767676 on white is the classic "just passes AA" grey (≈ 4.54:1).
        assertEquals(4.54, ColorMath.contrastRatio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun okLchRoundTripsWithin8BitPrecision() {
        val samples = listOf(
            0xFFC6613F, 0xFFE0A100, 0xFF2F6FDE, 0xFF2E8B57, 0xFF7B61FF, 0xFFC2185B, 0xFF5F6B7A,
            0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFFCFCFB, 0xFF151412,
        )
        for (argb in samples) {
            val original = Color(argb)
            val back = ColorMath.fromOkLch(ColorMath.toOkLch(original))
            val maxDiff = maxOf(
                abs(original.red - back.red),
                abs(original.green - back.green),
                abs(original.blue - back.blue),
            )
            assertTrue("0x${argb.toString(16)} round trip off by $maxDiff", maxDiff <= 1.5f / 255f)
        }
    }

    @Test
    fun whiteHasFullLightnessAndNoChroma() {
        val white = ColorMath.toOkLch(Color.White)
        assertEquals(1.0, white.l, 1e-3)
        assertEquals(0.0, white.c, 1e-3)
    }

    @Test
    fun outOfGamutColorsAreMappedNotClipped() {
        // Absurd chroma: must still produce a valid color with the requested lightness.
        val mapped = ColorMath.fromOkLch(0.7, 0.5, 145.0)
        val back = ColorMath.toOkLch(mapped)
        assertEquals(0.7, back.l, 0.01)
        assertTrue(back.c < 0.5)
        assertEquals(145.0, back.h, 4.0)
    }

    @Test
    fun lightnessSearchStopsAtFirstAcceptableColor() {
        val found = ColorMath.searchLightness(hue = 40.0, chroma = 0.1, start = 0.9, step = -0.01) {
            ColorMath.contrastRatio(it, Color.White) >= 4.5
        }
        assertTrue(ColorMath.contrastRatio(found, Color.White) >= 4.5)
        // A slightly lighter tone of the same color must fail, i.e. we did not overshoot.
        val lighter = ColorMath.fromOkLch(ColorMath.toOkLch(found).l + 0.02, 0.1, 40.0)
        assertTrue(ColorMath.contrastRatio(lighter, Color.White) < 4.5)
    }
}
