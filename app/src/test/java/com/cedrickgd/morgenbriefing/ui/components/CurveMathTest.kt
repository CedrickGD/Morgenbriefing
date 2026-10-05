package com.cedrickgd.morgenbriefing.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class CurveMathTest {

    private val xs = floatArrayOf(0f, 40f, 80f, 120f, 160f, 200f, 240f)

    @Test
    fun curvePassesThroughEveryPoint() {
        val ys = floatArrayOf(120f, 100f, 60f, 55f, 70f, 70f, 90f)
        val m = CurveMath.monotoneTangents(xs, ys)
        for (i in xs.indices) {
            assertEquals(ys[i], CurveMath.valueAt(xs[i], xs, ys, m), 1e-3f)
        }
    }

    @Test
    fun curveNeverOvershootsBetweenPoints() {
        val ys = floatArrayOf(120f, 118f, 40f, 39f, 110f, 110f, 20f)
        val m = CurveMath.monotoneTangents(xs, ys)
        for (i in 0 until xs.size - 1) {
            val lo = min(ys[i], ys[i + 1])
            val hi = max(ys[i], ys[i + 1])
            var x = xs[i]
            while (x <= xs[i + 1]) {
                val y = CurveMath.valueAt(x, xs, ys, m)
                assertTrue("overshoot at x=$x: $y not in [$lo, $hi]", y >= lo - 1e-3f && y <= hi + 1e-3f)
                x += 1f
            }
        }
    }

    @Test
    fun flatStretchStaysFlat() {
        val ys = floatArrayOf(80f, 80f, 80f, 50f, 50f, 50f, 50f)
        val m = CurveMath.monotoneTangents(xs, ys)
        assertEquals(0f, m[0], 0f)
        assertEquals(0f, m[1], 0f)
        for (x in 0..80) assertEquals(80f, CurveMath.valueAt(x.toFloat(), xs, ys, m), 1e-3f)
        for (x in 120..240) assertEquals(50f, CurveMath.valueAt(x.toFloat(), xs, ys, m), 1e-3f)
    }

    @Test
    fun valueAtClampsOutsideTheRange() {
        val ys = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f)
        val m = CurveMath.monotoneTangents(xs, ys)
        assertEquals(10f, CurveMath.valueAt(-50f, xs, ys, m), 0f)
        assertEquals(70f, CurveMath.valueAt(500f, xs, ys, m), 0f)
    }

    @Test
    fun handlesTwoPointsAndSinglePoint() {
        val two = CurveMath.monotoneTangents(floatArrayOf(0f, 10f), floatArrayOf(0f, 5f))
        assertEquals(0.5f, two[0], 1e-6f)
        assertEquals(0.5f, two[1], 1e-6f)
        assertEquals(0f, CurveMath.monotoneTangents(floatArrayOf(3f), floatArrayOf(7f))[0], 0f)
    }
}
