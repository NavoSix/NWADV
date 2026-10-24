package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Uniform-grid spatial index over the segments of many polylines. Each segment is stored in
 * every cell within [pad] metres, so a point query only has to look in its own cell.
 */
class SegmentIndex(private val lines: List<Polyline>, pads: DoubleArray, private val cell: Double = 24.0) {
    private val grid = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for ((li, line) in lines.withIndex()) {
            val pad = pads[li]
            for (si in 0 until line.size - 1) {
                val x0 = min(line.xs[si], line.xs[si + 1]) - pad; val x1 = max(line.xs[si], line.xs[si + 1]) + pad
                val y0 = min(line.ys[si], line.ys[si + 1]) - pad; val y1 = max(line.ys[si], line.ys[si + 1]) + pad
                val code = (li shl 12) or si.coerceAtMost(0xfff)
                if (si > 0xfff) error("Polyline too long for index; split it first")
                for (cx in floor(x0 / cell).toInt()..floor(x1 / cell).toInt())
                    for (cy in floor(y0 / cell).toInt()..floor(y1 / cell).toInt())
                        tmp.getOrPut(key(cx, cy)) { ArrayList(4) }.add(code)
            }
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /** Result of [nearestPerLine]: one entry per nearby line. */
    class Hit {
        var line = 0; var along = 0.0; var lateral = 0.0; var dist = 0.0; var heading = 0.0
    }

    /**
     * For every line with a segment in the query cell, the closest point within [maxDist] of
     * (x, y). Lateral distance is signed: positive means left of the line direction.
     * Returns the number of hits written to [out].
     */
    fun nearestPerLine(x: Double, y: Double, maxDist: Double, out: Array<Hit>): Int {
        val codes = grid[key(floor(x / cell).toInt(), floor(y / cell).toInt())] ?: return 0
        var n = 0
        for (code in codes) {
            val li = code ushr 12; val si = code and 0xfff
            val line = lines[li]
            val ax = line.xs[si]; val ay = line.ys[si]
            val dx = line.xs[si + 1] - ax; val dy = line.ys[si + 1] - ay
            val l2 = dx * dx + dy * dy
            if (l2 < 1e-12) continue
            val t = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val px = x - (ax + dx * t); val py = y - (ay + dy * t)
            val d = sqrt(px * px + py * py)
            if (d > maxDist) continue
            val len = sqrt(l2)
            val side = if (dx * py - dy * px >= 0) 1.0 else -1.0
            var slot = -1
            for (k in 0 until n) if (out[k].line == li) { slot = k; break }
            if (slot >= 0 && out[slot].dist <= d) continue
            if (slot < 0) { if (n == out.size) continue; slot = n++ }
            val h = out[slot]
            h.line = li; h.dist = d; h.lateral = d * side; h.along = line.cum[si] + len * t
            h.heading = kotlin.math.atan2(dy, dx)
        }
        return n
    }
}
