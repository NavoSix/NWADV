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
class Road(val id: Long, val spec: RoadSpec, val line: Polyline, elevation: Elevation) {
    val seed = (id xor (id ushr 32)).toInt() * 31 + 7
    val halfWidth = spec.width / 2
    private val profile: FloatArray
    private val bank: FloatArray

    init {
        val n = floor(line.length / STEP).toInt() + 2
        val raw = DoubleArray(n)
        val tmp = DoubleArray(3)
        for (i in 0 until n) { line.pointAt(i * STEP, tmp); raw[i] = elevation.height(tmp[0], tmp[1]) }
        val window = when (spec.kind) {
            RoadKind.HIGHWAY -> 70.0; RoadKind.MAJOR -> 55.0; RoadKind.MINOR -> 40.0
            RoadKind.SERVICE -> 24.0; RoadKind.GRAVEL -> 30.0; RoadKind.CLASS_VI -> 24.0; RoadKind.TRAIL -> 16.0
        }
        var smooth = boxFilter(boxFilter(raw, (window / STEP / 2).toInt()), (window / STEP / 2).toInt())
        if (spec.bridge || spec.tunnel) {
            val a = smooth.first(); val b = smooth.last()
            smooth = DoubleArray(n) { a + (b - a) * it / (n - 1).coerceAtLeast(1) }
        }
        profile = FloatArray(n) { smooth[it].toFloat() }

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

    companion object {
        const val STEP = 4.0
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
