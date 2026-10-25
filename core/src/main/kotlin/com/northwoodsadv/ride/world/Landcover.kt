package com.northwoodsadv.ride.world

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Land cover from OSM areas, rasterised lazily into 5 m cells in 1.28 km tiles. Anything not
 * mapped as something else is forest, which is right for most of New Hampshire.
 */
class Landcover(private val areas: List<Area>) {
    class Area(val code: Byte, val rings: List<Pair<DoubleArray, DoubleArray>>) {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        init {
            for ((xs, ys) in rings) for (i in xs.indices) {
                if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
                if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
            }
        }
    }

    private val tiles = ConcurrentHashMap<Long, ByteArray>()
    private val ordered = areas.sortedBy { PRIORITY.indexOf(it.code) }

    fun at(e: Double, n: Double): Byte {
        if (areas.isEmpty()) return FOREST
        val tx = floor(e / TILE_M).toInt(); val ty = floor(n / TILE_M).toInt()
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
        val tile = tiles.computeIfAbsent(key) { rasterise(tx, ty) }
        val cx = ((e - tx * TILE_M) / CELL).toInt().coerceIn(0, N - 1)
        val cy = ((n - ty * TILE_M) / CELL).toInt().coerceIn(0, N - 1)
        return tile[cy * N + cx]
    }

    private fun rasterise(tx: Int, ty: Int): ByteArray {
        val out = ByteArray(N * N)
        val x0 = tx * TILE_M; val y0 = ty * TILE_M
        val xings = DoubleArray(4096)
        for (a in ordered) {
            if (a.maxX < x0 || a.minX > x0 + TILE_M || a.maxY < y0 || a.minY > y0 + TILE_M) continue
            for (row in 0 until N) {
                val y = y0 + (row + 0.5) * CELL
                if (y < a.minY || y > a.maxY) continue
                var nx = 0
                for ((xs, ys) in a.rings) {
                    var j = xs.size - 1
                    for (i in xs.indices) {
                        if ((ys[i] > y) != (ys[j] > y) && nx < xings.size) {
                            xings[nx++] = xs[i] + (y - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
                        }
                        j = i
                    }
                }
                if (nx < 2) continue
                java.util.Arrays.sort(xings, 0, nx)
                var k = 0
                while (k + 1 < nx) {
                    val c0 = ((xings[k] - x0) / CELL - 0.5).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(0)
                    val c1 = ((xings[k + 1] - x0) / CELL - 0.5).let { floor(it).toInt() }.coerceAtMost(N - 1)
                    for (c in c0..c1) out[row * N + c] = a.code
                    k += 2
                }
            }
        }
        return out
    }

    companion object {
        const val FOREST: Byte = 0
        const val OPEN: Byte = 1
        const val WATER: Byte = 2
        const val WETLAND: Byte = 3
        const val ROCK: Byte = 4
        const val SCRUB: Byte = 5
        private val PRIORITY = listOf(FOREST, SCRUB, OPEN, WETLAND, ROCK, WATER) // later wins
        const val CELL = 5.0
        const val N = 256
        const val TILE_M = CELL * N

        fun codeFor(tags: Map<String, String>): Byte? = when {
            tags["natural"] == "water" -> WATER
            tags["natural"] == "wetland" -> WETLAND
            tags["natural"] == "bare_rock" || tags["natural"] == "scree" -> ROCK
            tags["natural"] == "scrub" || tags["natural"] == "heath" -> SCRUB
            tags["natural"] == "grassland" -> OPEN
            tags["landuse"] in setOf("meadow", "farmland", "grass", "farmyard", "orchard", "recreation_ground") -> OPEN
            else -> null
        }
    }
}
