package com.northwoodsadv.ride.geo

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** A polyline in local ENU metres with cumulative distances. */
class Polyline(val xs: DoubleArray, val ys: DoubleArray) {
    val size get() = xs.size
    val cum = DoubleArray(xs.size)
    init {
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val length get() = if (cum.isEmpty()) 0.0 else cum.last()

    /** Point at distance [s] along the line, written into [out] (x, y, heading). */
    fun pointAt(s: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val d = s.coerceIn(0.0, length)
        var lo = 0; var hi = size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (cum[m] <= d) lo = m else hi = m }
        val segLen = cum[hi] - cum[lo]
        val t = if (segLen > 1e-9) (d - cum[lo]) / segLen else 0.0
        out[0] = xs[lo] + (xs[hi] - xs[lo]) * t
        out[1] = ys[lo] + (ys[hi] - ys[lo]) * t
        out[2] = atan2(ys[hi] - ys[lo], xs[hi] - xs[lo])
        return out
    }

    /**
     * Closest point on the polyline to (x, y), searching segments whose start lies within
     * [s0, s1] of distance. Returns (distanceAlong, lateralDistance) in [out].
     */
    fun project(x: Double, y: Double, s0: Double = 0.0, s1: Double = Double.MAX_VALUE, out: DoubleArray = DoubleArray(2)): DoubleArray {
        var best = Double.MAX_VALUE; var bestS = 0.0
        for (i in 0 until size - 1) {
            if (cum[i + 1] < s0 || cum[i] > s1) continue
            val ax = xs[i]; val ay = ys[i]
            val dx = xs[i + 1] - ax; val dy = ys[i + 1] - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + dx * t - x; val py = ay + dy * t - y
            val d2 = px * px + py * py
            if (d2 < best) { best = d2; bestS = cum[i] + sqrt(l2) * t }
        }
        out[0] = bestS; out[1] = sqrt(best)
        return out
    }

    /** Resampled copy with points every [step] metres. */
    fun resample(step: Double): Polyline {
        val n = (length / step).toInt().coerceAtLeast(1) + 1
        val nx = DoubleArray(n); val ny = DoubleArray(n)
        val tmp = DoubleArray(3)
        for (i in 0 until n) { pointAt(length * i / (n - 1), tmp); nx[i] = tmp[0]; ny[i] = tmp[1] }
        return Polyline(nx, ny)
    }

    companion object {
        fun from(points: List<GeoPoint>, proj: LocalProjection): Polyline {
            val xs = DoubleArray(points.size); val ys = DoubleArray(points.size)
            points.forEachIndexed { i, p -> xs[i] = proj.east(p.lon); ys[i] = proj.north(p.lat) }
            return Polyline(xs, ys)
        }
    }
}

/** Douglas-Peucker simplification on lat/lon (tolerance in metres). */
fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (points.size < 3) return points
    val proj = LocalProjection(points[0])
    val xs = DoubleArray(points.size) { proj.east(points[it].lon) }
    val ys = DoubleArray(points.size) { proj.north(points[it].lat) }
    val keep = BooleanArray(points.size)
    keep[0] = true; keep[points.size - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, points.size - 1))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var maxD = 0.0; var idx = -1
        val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
        val len = hypot(dx, dy)
        for (i in a + 1 until b) {
            val d = if (len < 1e-9) hypot(xs[i] - xs[a], ys[i] - ys[a])
            else kotlin.math.abs(dx * (ys[a] - ys[i]) - dy * (xs[a] - xs[i])) / len
            if (d > maxD) { maxD = d; idx = i }
        }
        if (idx >= 0 && maxD > toleranceM) {
            keep[idx] = true
            stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}
