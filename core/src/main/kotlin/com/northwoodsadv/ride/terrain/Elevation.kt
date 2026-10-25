package com.northwoodsadv.ride.terrain

import com.northwoodsadv.ride.geo.LocalProjection
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Terrain height in local ENU coordinates. */
interface Elevation {
    fun height(e: Double, n: Double): Double
}

class FlatElevation(private val h: Double = 0.0) : Elevation {
    override fun height(e: Double, n: Double) = h
}

/**
 * Real-world elevation from DEM tiles: a detailed zoom (14, ~7 m pixels in NH) along the route
 * corridor, and a coarse zoom (11, ~55 m) covering the wider area so riding off the corridor
 * still has believable hills. Detailed tiles are sampled with Catmull-Rom interpolation so slopes
 * are continuous (no suspension "ticks" at DEM pixel boundaries).
 */
class DemElevation(
    private val proj: LocalProjection,
    val fineZoom: Int = 14,
    val coarseZoom: Int = 11,
    private val fallbackHeight: Double = 0.0,
) : Elevation {
    private val tiles = ConcurrentHashMap<Long, DemTile>()

    fun add(tile: DemTile) { tiles[TileMath.key(tile.z, tile.x, tile.y)] = tile }
    fun has(z: Int, x: Int, y: Int) = tiles.containsKey(TileMath.key(z, x, y))
    val tileCount get() = tiles.size

    override fun height(e: Double, n: Double): Double {
        val lat = proj.lat(n); val lon = proj.lon(e)
        sampleCubic(fineZoom, lat, lon)?.let { return it }
        sampleLinear(coarseZoom, lat, lon)?.let { return it }
        return fallbackHeight
    }

    private fun px(z: Int, gx: Int, gy: Int): Float? {
        val t = tiles[TileMath.key(z, Math.floorDiv(gx, TileMath.TILE), Math.floorDiv(gy, TileMath.TILE))] ?: return null
        return t.heightAt(Math.floorMod(gx, TileMath.TILE), Math.floorMod(gy, TileMath.TILE))
    }

    private fun sampleLinear(z: Int, lat: Double, lon: Double): Double? {
        val fx = TileMath.lonToPx(lon, z) - 0.5
        val fy = TileMath.latToPx(lat, z) - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val a = px(z, x0, y0) ?: return null
        val b = px(z, x0 + 1, y0) ?: a
        val c = px(z, x0, y0 + 1) ?: a
        val d = px(z, x0 + 1, y0 + 1) ?: a
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    private val row = ThreadLocal.withInitial { DoubleArray(4) }

    private fun sampleCubic(z: Int, lat: Double, lon: Double): Double? {
        val fx = TileMath.lonToPx(lon, z) - 0.5
        val fy = TileMath.latToPx(lat, z) - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val centre = px(z, x0, y0) ?: return null
        val r = row.get()
        for (j in -1..2) {
            val p0 = px(z, x0 - 1, y0 + j) ?: centre
            val p1 = px(z, x0, y0 + j) ?: centre
            val p2 = px(z, x0 + 1, y0 + j) ?: centre
            val p3 = px(z, x0 + 2, y0 + j) ?: centre
            r[j + 1] = catmull(p0.toDouble(), p1.toDouble(), p2.toDouble(), p3.toDouble(), tx)
        }
        return catmull(r[0], r[1], r[2], r[3], ty)
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t; val t3 = t2 * t
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }
}
