package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.Elevation
import kotlin.math.abs
import kotlin.math.floor

/**
 * A road (or trail) in the world with an elevation profile along it.
 *
 * Real roads are graded: the profile is the DEM sampled along the centreline and smoothed over a
 * distance that depends on how engineered the road is (highways are smooth over ~60 m, Class VI
 * roads follow the land closely). Bridges span straight between their ends.
 */
class Road(val id: Long, val spec: RoadSpec, val line: Polyline, elevation: Elevation) {
    val seed = (id xor (id ushr 32)).toInt() * 31 + 7
    val halfWidth = spec.width / 2
    private val profile: FloatArray

    init {
        val n = floor(line.length / STEP).toInt() + 2
        val raw = DoubleArray(n)
        val tmp = DoubleArray(3)
        for (i in 0 until n) { line.pointAt(i * STEP, tmp); raw[i] = elevation.height(tmp[0], tmp[1]) }
        val window = when (spec.kind) {
            RoadKind.HIGHWAY -> 70.0; RoadKind.MAJOR -> 55.0; RoadKind.MINOR -> 40.0
            RoadKind.SERVICE -> 24.0; RoadKind.GRAVEL -> 28.0; RoadKind.CLASS_VI -> 14.0; RoadKind.TRAIL -> 10.0
        }
        var smooth = boxFilter(boxFilter(raw, (window / STEP / 2).toInt()), (window / STEP / 2).toInt())
        if (spec.bridge || spec.tunnel) {
            val a = smooth.first(); val b = smooth.last()
            smooth = DoubleArray(n) { a + (b - a) * it / (n - 1).coerceAtLeast(1) }
        }
        profile = FloatArray(n) { smooth[it].toFloat() }
    }

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
