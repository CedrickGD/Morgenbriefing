package com.cedrickgd.morgenbriefing.ui.components

import androidx.compose.ui.graphics.Path
import kotlin.math.sqrt

/**
 * Monotone cubic interpolation (Fritsch–Carlson). Unlike a plain Catmull-Rom/Bezier smoothing,
 * the curve never overshoots the data: between two equal temperatures it stays flat, and a
 * peak in the data is a peak in the line — important for an honest temperature chart.
 */
internal object CurveMath {

    /** Tangent (dy/dx) at every point; [xs] must be strictly increasing. */
    fun monotoneTangents(xs: FloatArray, ys: FloatArray): FloatArray {
        require(xs.size == ys.size) { "xs and ys differ in size" }
        val n = xs.size
        val m = FloatArray(n)
        if (n < 2) return m

        val secants = FloatArray(n - 1) { i -> (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) }
        m[0] = secants[0]
        m[n - 1] = secants[n - 2]
        for (i in 1 until n - 1) {
            val a = secants[i - 1]
            val b = secants[i]
            m[i] = if (a * b <= 0f) 0f else (a + b) / 2f
        }
        for (i in 0 until n - 1) {
            val d = secants[i]
            if (d == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val alpha = m[i] / d
            val beta = m[i + 1] / d
            val s = alpha * alpha + beta * beta
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                m[i] = tau * alpha * d
                m[i + 1] = tau * beta * d
            }
        }
        return m
    }

    /**
     * Cubic Bezier path through all points. Control points sit at ⅓ of each segment's width, so
     * x grows linearly along every segment and [valueAt] gives the exact y of the drawn line.
     */
    fun smoothPath(xs: FloatArray, ys: FloatArray, tangents: FloatArray, path: Path = Path()): Path {
        if (xs.isEmpty()) return path
        path.moveTo(xs[0], ys[0])
        for (i in 0 until xs.size - 1) {
            val h = xs[i + 1] - xs[i]
            path.cubicTo(
                xs[i] + h / 3f, ys[i] + tangents[i] * h / 3f,
                xs[i + 1] - h / 3f, ys[i + 1] - tangents[i + 1] * h / 3f,
                xs[i + 1], ys[i + 1],
            )
        }
        return path
    }

    /** y of the smooth curve at [x] (cubic Hermite form of [smoothPath]); clamps outside. */
    fun valueAt(x: Float, xs: FloatArray, ys: FloatArray, tangents: FloatArray): Float {
        if (xs.isEmpty()) return 0f
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var i = 0
        while (i < xs.size - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[i] + h10 * h * tangents[i] + h01 * ys[i + 1] + h11 * h * tangents[i + 1]
    }
}
