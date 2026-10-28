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
    /**
     * Unit tangent of the corner bisector at each vertex of each line (the mean of the segment
     * directions either side). A point belongs to the segment whose two end bisectors it lies
     * between, and its distance along is measured between them. Assigned to the nearest
     * segment and measured square to it instead, the distance along jumps where the nearest
     * segment changes (on the inside of a bend, or round the outside of a sharp vertex), and on
     * a steep grade the road's height jumps with it: a 30 cm step inside a switchback on a 20%
     * climb.
     */
    private val bisectors: List<DoubleArray> = lines.map { line ->
        val n = line.size
        val out = DoubleArray(2 * n)
        for (i in 0 until n) {
            var tx = 0.0; var ty = 0.0
            if (i > 0) { val dx = line.xs[i] - line.xs[i - 1]; val dy = line.ys[i] - line.ys[i - 1]; val l = sqrt(dx * dx + dy * dy); if (l > 1e-9) { tx += dx / l; ty += dy / l } }
            if (i < n - 1) { val dx = line.xs[i + 1] - line.xs[i]; val dy = line.ys[i + 1] - line.ys[i]; val l = sqrt(dx * dx + dy * dy); if (l > 1e-9) { tx += dx / l; ty += dy / l } }
            val l = sqrt(tx * tx + ty * ty)
            if (l > 1e-9) { out[2 * i] = tx / l; out[2 * i + 1] = ty / l }
        }
        out
    }

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

    companion object {
        /** Hits on the same line further apart than this along it are separate sections. */
        const val SECTION = 25.0
        /** How much closer (m) a segment must be to win over one whose bisector region the point is in. */
        private const val OUTSIDE_RANK = 1.0
    }

    /**
     * Where (x, y) falls between segment [si]'s two end bisectors: 0..1 between them, outside that
     * beyond them, NaN where they cross before reaching it (far inside a very tight bend).
     */
    private fun bisectorT(li: Int, si: Int, x: Double, y: Double): Double {
        val line = lines[li]; val b = bisectors[li]
        val dA = (x - line.xs[si]) * b[2 * si] + (y - line.ys[si]) * b[2 * si + 1]
        val dB = (x - line.xs[si + 1]) * b[2 * si + 2] + (y - line.ys[si + 1]) * b[2 * si + 3]
        val den = dA - dB
        return if (den < 1e-9) Double.NaN else dA / den
    }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /** Result of [nearestPerLine]: one entry per nearby line. */
    class Hit {
        var line = 0; var along = 0.0; var lateral = 0.0; var dist = 0.0; var heading = 0.0
        internal var rank = 0.0
    }

    /**
     * For every line (and every separate section of a line that doubles back, like a hairpin)
     * with a segment in the query cell, the closest point within [maxDist] of (x, y). Lateral distance is signed: positive means left of the line direction.
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
            val len = sqrt(l2)
            val cross = dx * (y - ay) - dy * (x - ax)
            val tb = bisectorT(li, si, x, y)
            val mine = !tb.isNaN() && tb >= -1e-9 && tb <= 1 + 1e-9
            // In its own region: to the point that far along the segment (continuous across
            // vertices, and never a short cut to the far leg of a hairpin through the segment's
            // extended line). Otherwise (round the outside of a vertex, beyond the ends of the
            // line): to the nearest point, ranked behind a segment whose region it's in.
            val d = if (mine) { val qx = x - (ax + dx * tb); val qy = y - (ay + dy * tb); sqrt(qx * qx + qy * qy) } else sqrt(px * px + py * py)
            if (d > maxDist) continue
            val rank = if (mine) d else d + OUTSIDE_RANK
            val side = if (cross >= 0) 1.0 else -1.0
            // One hit per line *section*: the two legs of a hairpin are separate hits, so
            // callers can blend them instead of jumping between their heights.
            val along = line.cum[si] + len * (if (tb.isNaN()) t else tb.coerceIn(0.0, 1.0))
            var slot = -1
            for (k in 0 until n) if (out[k].line == li && kotlin.math.abs(out[k].along - along) < SECTION) { slot = k; break }
            if (slot >= 0 && out[slot].rank <= rank) continue
            if (slot < 0) { if (n == out.size) continue; slot = n++ }
            val h = out[slot]
            h.line = li; h.dist = d; h.rank = rank; h.lateral = d * side; h.along = along
            h.heading = kotlin.math.atan2(dy, dx)
        }
        return n
    }
}
