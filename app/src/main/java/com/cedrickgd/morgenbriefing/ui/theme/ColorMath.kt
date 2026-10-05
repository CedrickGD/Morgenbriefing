package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** WCAG 2.x minimum contrast for normal-size text. */
internal const val MIN_TEXT_CONTRAST = 4.5

/**
 * A color in OkLCH (Björn Ottosson's perceptual Oklab in polar form).
 *
 * @property l perceptual lightness, 0 (black) … 1 (white)
 * @property c chroma, 0 (grey) … roughly 0.37 for the most saturated sRGB colors
 * @property h hue angle in degrees, 0 … 360
 */
internal data class OkLch(val l: Double, val c: Double, val h: Double)

/**
 * Small, dependency-free color toolkit: sRGB ⇄ OkLCH conversion with gamut mapping and the WCAG
 * contrast ratio. Pure math, safe to use from unit tests.
 */
internal object ColorMath {

    /** Converts an (opaque, sRGB) Compose color to OkLCH. Alpha is ignored. */
    fun toOkLch(color: Color): OkLch {
        val r = srgbToLinear(color.red.toDouble())
        val g = srgbToLinear(color.green.toDouble())
        val b = srgbToLinear(color.blue.toDouble())

        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)

        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s

        val chroma = hypot(okA, okB)
        val hue = if (chroma < 1e-6) 0.0 else (Math.toDegrees(atan2(okB, okA)) + 360.0) % 360.0
        return OkLch(okL, chroma, hue)
    }

    /**
     * Converts OkLCH to an opaque sRGB color. Colors outside the sRGB gamut keep their lightness
     * and hue while chroma is reduced (binary search) until they fit — no hue shifts, no clipping.
     */
    fun fromOkLch(l: Double, c: Double, h: Double): Color {
        val lightness = l.coerceIn(0.0, 1.0)
        val chroma = max(0.0, c)
        val direct = okLchToLinearRgb(lightness, chroma, h)
        if (direct.isInGamut()) return direct.toColor()

        var lo = 0.0
        var hi = chroma
        repeat(GAMUT_SEARCH_STEPS) {
            val mid = (lo + hi) / 2.0
            if (okLchToLinearRgb(lightness, mid, h).isInGamut()) lo = mid else hi = mid
        }
        return okLchToLinearRgb(lightness, lo, h).toColor()
    }

    fun fromOkLch(lch: OkLch): Color = fromOkLch(lch.l, lch.c, lch.h)

    /** WCAG relative luminance (0 = black, 1 = white) of an opaque sRGB color. */
    fun relativeLuminance(color: Color): Double {
        val r = srgbToLinear(color.red.toDouble())
        val g = srgbToLinear(color.green.toDouble())
        val b = srgbToLinear(color.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two opaque colors, 1.0 … 21.0 (order does not matter). */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * Walks OkLCH lightness from [start] in steps of [step] (negative = darker) at fixed hue and
     * chroma until [accept] is satisfied. Because pure black/white always end the walk, the
     * search terminates and returns the first acceptable — i.e. closest to [start] — color.
     */
    fun searchLightness(
        hue: Double,
        chroma: Double,
        start: Double,
        step: Double,
        accept: (Color) -> Boolean,
    ): Color {
        require(step != 0.0) { "step must not be 0" }
        var l = start.coerceIn(0.0, 1.0)
        while (true) {
            val candidate = fromOkLch(l, chroma, hue)
            if (accept(candidate)) return candidate
            val next = l + step
            if (next < 0.0 || next > 1.0) return fromOkLch(if (step < 0) 0.0 else 1.0, 0.0, hue)
            l = next
        }
    }

    private const val GAMUT_SEARCH_STEPS = 24
    private const val GAMUT_EPSILON = 1e-5

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double =
        if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055

    private fun okLchToLinearRgb(l: Double, c: Double, h: Double): DoubleArray {
        val rad = Math.toRadians(h)
        val a = c * cos(rad)
        val b = c * sin(rad)

        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b

        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1

        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
    }

    private fun DoubleArray.isInGamut(): Boolean =
        all { it >= -GAMUT_EPSILON && it <= 1.0 + GAMUT_EPSILON }

    private fun DoubleArray.toColor(): Color = Color(
        red = linearToSrgb(this[0].coerceIn(0.0, 1.0)).toFloat().coerceIn(0f, 1f),
        green = linearToSrgb(this[1].coerceIn(0.0, 1.0)).toFloat().coerceIn(0f, 1f),
        blue = linearToSrgb(this[2].coerceIn(0.0, 1.0)).toFloat().coerceIn(0f, 1f),
    )
}

/** Shorthand for [ColorMath.contrastRatio]. */
internal fun Color.contrastAgainst(other: Color): Double = ColorMath.contrastRatio(this, other)
