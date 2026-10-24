package com.northwoodsadv.ride.terrain

import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.data.Http
import com.northwoodsadv.ride.geo.GeoBounds
import com.northwoodsadv.ride.geo.LocalProjection
import com.northwoodsadv.ride.geo.Polyline
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** Works out which DEM tiles a ride needs and downloads / caches them. */
class DemLoader(private val cache: DiskCache) {
    data class TileId(val z: Int, val x: Int, val y: Int)

    class Result(val loaded: Int, val failed: Int, val firstError: String?)

    /** Tiles at [z] within [radiusM] of the route. */
    fun corridorTiles(route: Polyline, proj: LocalProjection, z: Int, radiusM: Double): Set<TileId> {
        val out = LinkedHashSet<TileId>()
        val step = 150.0
        val n = ceil(route.length / step).toInt().coerceAtLeast(1)
        val tmp = DoubleArray(3)
        for (i in 0..n) {
            route.pointAt(route.length * i / n, tmp)
            val b = GeoBounds(proj.lat(tmp[1]), proj.lon(tmp[0]), proj.lat(tmp[1]), proj.lon(tmp[0])).expandMeters(radiusM)
            out += boundsTiles(b, z)
        }
        return out
    }

    fun boundsTiles(b: GeoBounds, z: Int): Set<TileId> {
        val out = LinkedHashSet<TileId>()
        for (x in TileMath.tileX(b.minLon, z)..TileMath.tileX(b.maxLon, z))
            for (y in TileMath.tileY(b.maxLat, z)..TileMath.tileY(b.minLat, z)) out += TileId(z, x, y)
        return out
    }

    fun load(ids: Collection<TileId>, into: DemElevation, progress: (done: Int, total: Int) -> Unit): Result {
        val pool = Executors.newFixedThreadPool(4)
        val done = AtomicInteger(); val failed = AtomicInteger()
        var firstError: String? = null
        try {
            val futures = ids.filter { !into.has(it.z, it.x, it.y) }.map { id ->
                pool.submit(Callable {
                    try {
                        val png = cache.getOrFetch("dem/${id.z}/${id.x}/${id.y}.png") { Http.get(url(id)) }
                        into.add(DemTile.decodeTerrarium(id.z, id.x, id.y, png))
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        synchronized(this) { if (firstError == null) firstError = e.message ?: e.toString() }
                    }
                    progress(done.incrementAndGet(), ids.size)
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return Result(done.get() - failed.get(), failed.get(), firstError)
    }

    companion object {
        /** Open elevation tiles from the AWS Open Data registry (no API key required). */
        fun url(id: TileId) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${id.z}/${id.x}/${id.y}.png"
    }
}
