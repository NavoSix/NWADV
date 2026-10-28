package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.Elevation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * A road (or trail) in the world with an elevation profile along it.
 *
 * Real roads are graded: the profile is the DEM sampled along the centreline and smoothed over a
 * distance that depends on how engineered the road is (highways are smooth over ~60 m, Class VI
 * roads follow the land closely). Bridges span straight between their ends.
 *
 * Faster roads are banked (superelevated) through their bends, and every road has an earthwork
 * cross-section: a cut bank where it was dug into the hillside, a fill slope where it was built
 * up, with stretches blasted through ledge as near-vertical rock cuts.
 */
class Road(
    val id: Long, val spec: RoadSpec, val line: Polyline, elevation: Elevation,
    /**
     * Where the road carries on beyond its ends (the next way of the same road in the map), as
     * lines leading away from its start and its end: the profile is smoothed over that ground
     * too, so pieces of one road meet at the same height.
     */
    before: Polyline? = null, after: Polyline? = null,
) {
    val seed = (id xor (id ushr 32)).toInt() * 31 + 7
    val halfWidth = spec.width / 2
    private val profile: FloatArray
    private val bank: FloatArray
    /** The hard stretches of a Class VI road (see [HeroSections]). */
    val heroes: List<HeroSection> = HeroSections.place(spec, line.length, seed)

    init {
        val n = floor(line.length / STEP).toInt() + 2
        val tmp = DoubleArray(3)
        val window = when (spec.kind) {
            RoadKind.HIGHWAY -> 70.0; RoadKind.MAJOR -> 55.0; RoadKind.MINOR -> 40.0
            RoadKind.SERVICE -> 24.0; RoadKind.GRAVEL -> 30.0; RoadKind.CLASS_VI -> 24.0; RoadKind.TRAIL -> 16.0
        }
        // Samples run on past the ends along the continuing road, then are cropped after smoothing.
        val n0 = before?.let { floor(minOf(it.length, window * 1.2) / STEP).toInt() } ?: 0
        val n1 = after?.let { floor(minOf(it.length, window * 1.2) / STEP).toInt() } ?: 0
        val raw = DoubleArray(n0 + n + n1)
        for (i in raw.indices) {
            val sPos = (i - n0) * STEP
            when {
                sPos < 0 -> before!!.pointAt(-sPos, tmp)
                i >= n0 + n -> after!!.pointAt(sPos - line.length, tmp)
                else -> line.pointAt(sPos, tmp)
            }
            raw[i] = elevation.height(tmp[0], tmp[1])
        }
        var smooth = boxFilter(boxFilter(raw, (window / STEP / 2).toInt()), (window / STEP / 2).toInt())
        smooth = smooth.copyOfRange(n0, n0 + n)
        if (spec.bridge || spec.tunnel) {
            val a = smooth.first(); val b = smooth.last()
            smooth = DoubleArray(n) { a + (b - a) * it / (n - 1).coerceAtLeast(1) }
        }
        profile = FloatArray(n) { smooth[it].toFloat() }
        for (h in heroes) carveHollow(h)

        // Superelevation: e = v^2 / (g R), shared with side friction (about 55% carried by the
        // bank) and capped like a state DOT would. Smoothed so it runs in and out of the bend.
        val v = designSpeed(spec)
        bank = if (v <= 0 || spec.bridge || line.length < 30) FloatArray(1) else {
            val p = DoubleArray(3)
            val e = DoubleArray(n) { i ->
                val s = i * STEP
                line.pointAt(s - 6, p); val h0 = p[2]; line.pointAt(s + 6, p)
                var dh = p[2] - h0
                while (dh > PI) dh -= 2 * PI; while (dh < -PI) dh += 2 * PI
                val k = dh / 12.0
                (v * v * k / 9.81 * 0.55).coerceIn(-MAX_BANK, MAX_BANK)
            }
            val r = (24.0 / STEP / 2).toInt()
            val sm = boxFilter(boxFilter(e, r), r)
            FloatArray(n) { sm[it].toFloat() }
        }
    }

    /**
     * Cross slope (rise per metre) toward the outside of the bend at s, signed like the
     * curvature (positive in a left-hand bend, where the left edge is lower).
     */
    fun bankAt(s: Double): Double {
        if (bank.size == 1) return 0.0
        val f = (s / STEP).coerceIn(0.0, (bank.size - 1).toDouble())
        val i = floor(f).toInt().coerceAtMost(bank.size - 2)
        val t = f - i
        return bank[i] * (1 - t) + bank[i + 1] * t
    }

    /**
     * Grades the road to height [h] at [s], easing back into its own profile over [reach] metres
     * either side: where a side road or driveway meets another road they are built to meet at the
     * same level, not with a step or a hump between their two smoothed profiles.
     */
    fun pin(s: Double, h: Double, reach: Double = 30.0) {
        if (spec.bridge || spec.tunnel) return
        for (i in profile.indices) {
            val d = abs(i * STEP - s)
            if (d >= reach) continue
            val k = 1 - Noise.smoothstep(reach * 0.25, reach, d)
            profile[i] = (profile[i] + (h - profile[i]) * k).toFloat()
        }
    }

    /** The hero section at s, or null. */
    fun heroAt(s: Double): HeroSection? {
        for (h in heroes) if (s in h) return h
        return null
    }

    /**
     * Drops the road into a hollow with a level bottom (the stream bed, when there's a ford),
     * going in and out over steep pitches. If that makes a pitch steeper than
     * [HeroSections.MAX_GRADE] (a hollow on a hillside), the bottom tilts with the hill part
     * way, then the hollow gets shallower, until it isn't.
     */
    private fun carveHollow(h: HeroSection) {
        val i0 = floor(h.dipStart / STEP).toInt().coerceAtLeast(1); val i1 = (floor(h.dipEnd / STEP).toInt() + 1).coerceAtMost(profile.size - 2)
        if (i1 <= i0) return
        val orig = profile.copyOfRange(i0 - 1, i1 + 2)
        val ha = heightAt(h.dipStart); val hb = heightAt(h.dipEnd)
        var depth = h.depth; var level = 1.0
        while (true) {
            val flat = minOf(ha, hb) - depth
            var worst = 0.0
            for (i in i0..i1) {
                val s = i * STEP
                // Below the straight line between the hollow's two rims, or level below the lower one.
                val chord = ha + (hb - ha) * ((s - h.dipStart) / (h.dipEnd - h.dipStart)).coerceIn(0.0, 1.0) - depth
                val bottom = flat * level + chord * (1 - level)
                val o = orig[i - i0 + 1].toDouble()
                profile[i] = (o + (bottom - o) * h.dipShape(s)).toFloat()
            }
            for (i in i0 - 1..i1) worst = maxOf(worst, abs(profile[i + 1] - profile[i]) / STEP)
            if (worst <= HeroSections.MAX_GRADE || depth < 0.5) break
            for (i in i0..i1) profile[i] = orig[i - i0 + 1]
            if (level > 0) level = maxOf(0.0, level - 0.25) else depth -= 0.3
        }
    }

    /** 0..1: how much of the cut face at s is blasted ledge rather than soil (maintained roads only). */
    fun rockCut(s: Double): Double =
        if (spec.isClassVI) 0.0 else Noise.smoothstep(0.66, 0.76, Noise.value1(s / 90, seed + 60))

    /** Steepness (rise per metre) of the cut bank at s. */
    fun cutSlope(s: Double): Double {
        val soil = if (spec.isClassVI) 0.9 + 0.5 * Noise.value1(s / 40, seed + 61) else 1.3 + 0.4 * Noise.value1(s / 40, seed + 61)
        return soil + (5.0 - soil) * rockCut(s)
    }

    /** Steepness of the fill slope built up on the downhill side. */
    val fillSlope = if (spec.isClassVI) 0.6 else 0.75

    fun heightAt(s: Double): Double {
        val f = (s / STEP).coerceIn(0.0, (profile.size - 1).toDouble())
        val i = floor(f).toInt().coerceAtMost(profile.size - 2).coerceAtLeast(0)
        if (profile.size == 1) return profile[0].toDouble()
        val t = f - i
        return profile[i] * (1 - t) + profile[i + 1] * t
    }

    /** Grade (rise/run) at s, positive uphill in the line direction. */
    fun gradeAt(s: Double) = (heightAt(s + 4) - heightAt(s - 4)) / 8.0

    val maxGrade: Double by lazy {
        var m = 0.0; var s = 0.0
        while (s < line.length) { m = maxOf(m, abs(gradeAt(s))); s += 10 }
        m
    }

    /** Short pieces (bridges, the bit of road between two junctions) run straight between the heights at their ends. */
    private fun spanTo(h0: Double?, h1: Double?) {
        val n = profile.size
        val a = h0 ?: profile[0].toDouble(); val b = h1 ?: profile[n - 1].toDouble()
        for (i in 0 until n) profile[i] = (a + (b - a) * i / (n - 1).coerceAtLeast(1)).toFloat()
    }

    companion object {
        const val STEP = 4.0

        /**
         * Roads that join end to end, or end on another road, are graded to meet at the same
         * height. OpenStreetMap splits a road into separate ways at junctions, bridges and
         * changes of surface, and each way's profile is smoothed on its own, so without this
         * they would meet with steps and humps. The longer road wins (it's smoothed over more
         * ground); pieces shorter than [SHORT] are re-graded straight between their ends.
         */
        fun connectAll(roads: List<Road>) {
            if (roads.size < 2) return
            val idx = SegmentIndex(roads.map { it.line }, DoubleArray(roads.size) { 3.0 }, 16.0)
            val hits = Array(16) { SegmentIndex.Hit() }
            for (r in roads.sortedByDescending { it.line.length }) {
                val ends = arrayOfNulls<Double>(2)
                for (end in 0..1) {
                    val k = if (end == 0) 0 else r.line.size - 1
                    val cnt = idx.nearestPerLine(r.line.xs[k], r.line.ys[k], 2.5, hits)
                    var best: Road? = null; var bestS = 0.0
                    for (q in 0 until cnt) {
                        val o = roads[hits[q].line]
                        if (o === r || o.line.length <= r.line.length) continue
                        if (best == null || o.line.length > best.line.length) { best = o; bestS = hits[q].along }
                    }
                    if (best != null) ends[end] = best.heightAt(bestS)
                }
                if (ends[0] == null && ends[1] == null) continue
                if (r.line.length < SHORT || r.spec.bridge) r.spanTo(ends[0], ends[1])
                else {
                    ends[0]?.let { r.pin(0.0, it, 30.0) }
                    ends[1]?.let { r.pin(r.line.length, it, 30.0) }
                }
            }
        }

        private const val SHORT = 70.0

        /**
         * GPX-only stretches (ids below zero) that run along or across mapped roads take those
         * roads' level where they overlap them, and run straight between: the track overlaps the
         * roads it joins and cuts junction corners, and must meet them flush.
         */
        fun flushWithMapped(roads: List<Road>, shoulder: (Road) -> Double) {
            val mapped = roads.filter { it.id >= 0 }
            if (mapped.isEmpty()) return
            val idx = SegmentIndex(mapped.map { it.line }, DoubleArray(mapped.size) { mapped[it].halfWidth + shoulder(mapped[it]) + 3.0 }, 24.0)
            val hits = Array(16) { SegmentIndex.Hit() }
            val p = DoubleArray(3)
            for (r in roads) {
                if (r.id >= 0 || r.spec.bridge) continue
                val n = r.profile.size
                val target = DoubleArray(n) { Double.NaN }
                for (i in 0 until n) {
                    r.line.pointAt(i * STEP, p)
                    val cnt = idx.nearestPerLine(p[0], p[1], 30.0, hits)
                    var bestD = Double.MAX_VALUE
                    for (q in 0 until cnt) {
                        val m = mapped[hits[q].line]
                        val over = hits[q].dist - (m.halfWidth + shoulder(m) + 1.5)
                        if (over <= 0 && over < bestD) { bestD = over; target[i] = m.heightAt(hits[q].along) }
                    }
                }
                var any = false
                for (i in 0 until n) if (!target[i].isNaN()) { r.profile[i] = target[i].toFloat(); any = true }
                if (!any) continue
                // Straight across the gaps between overlaps (a corner cut); free ends keep their own.
                var i = 0
                while (i < n) {
                    if (!target[i].isNaN()) { i++; continue }
                    var j = i
                    while (j < n && target[j].isNaN()) j++
                    if (i > 0 && j < n) {
                        val a = target[i - 1]; val b = target[j]
                        for (k in i until j) r.profile[k] = (a + (b - a) * (k - i + 1) / (j - i + 1)).toFloat()
                    }
                    i = j
                }
            }
        }
        /** Steepest bank on a public road (8%) and a little extra on old built-up curves. */
        const val MAX_BANK = 0.08

        /** Speed (m/s) a road's curves were built for, or 0 for unbanked roads. */
        fun designSpeed(spec: RoadSpec) = when (spec.kind) {
            RoadKind.HIGHWAY -> 25.0; RoadKind.MAJOR -> 22.0; RoadKind.MINOR -> 17.0; RoadKind.GRAVEL -> 13.0
            else -> 0.0
        }

        private fun boxFilter(a: DoubleArray, r: Int): DoubleArray {
            if (r < 1 || a.size < 3) return a
            val out = DoubleArray(a.size)
            for (i in a.indices) {
                var sum = 0.0; var c = 0
                for (k in maxOf(0, i - r)..minOf(a.size - 1, i + r)) { sum += a[k]; c++ }
                out[i] = sum / c
            }
            return out
        }

        /** Long OSM ways are split so that each piece fits the segment index. */
        fun splitLong(xs: DoubleArray, ys: DoubleArray, maxPts: Int = 4000): List<Polyline> {
            if (xs.size <= maxPts) return listOf(Polyline(xs, ys))
            val out = ArrayList<Polyline>()
            var start = 0
            while (start < xs.size - 1) {
                val end = minOf(xs.size, start + maxPts)
                out += Polyline(xs.copyOfRange(start, end), ys.copyOfRange(start, end))
                start = end - 1
            }
            return out
        }
    }
}
