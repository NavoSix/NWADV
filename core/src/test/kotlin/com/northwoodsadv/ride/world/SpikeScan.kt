package com.northwoodsadv.ride.world

import com.badlogic.gdx.utils.GdxNativesLoader
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxParser
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Scans a route's road surface for height discontinuities (needs NWADV_SPIKES=<route>). */
class SpikeScan {
    @Test fun scan() {
        val routeName = System.getenv("NWADV_SPIKES") ?: return
        GdxNativesLoader.load()
        val gpx = GpxParser.parse(File("../assets/routes/$routeName").readText())
        val world = WorldBuilder(DiskCache(File("build/ride-cache"))).build(gpx)
        val g = world.ground
        val tmp = DoubleArray(3)
        var worst = 0.0; var count = 0
        for (road in world.roads) {
            var s = 0.0
            while (s < road.line.length) {
                road.line.pointAt(s, tmp)
                val h = tmp[2]
                for (lat in listOf(-1.4, -0.7, 0.0, 0.7, 1.4)) {
                    val e = tmp[0] - sin(h) * lat; val n = tmp[1] + cos(h) * lat
                    val a = g.height(e, n, false); val b = g.height(e + cos(h) * 0.5, n + sin(h) * 0.5, false)
                    val c = g.height(e - sin(h) * 0.5, n + cos(h) * 0.5, false)
                    val jump = maxOf(abs(b - a), abs(c - a))
                    if (jump > 0.25) { count++; if (count < 15) println("spike at s=%.0f lat=%.1f jump=%.2f m".format(s, lat, jump)) }
                    worst = maxOf(worst, jump)
                }
                s += 0.5
            }
        }
        println("spikes > 25 cm per 50 cm: $count, worst %.2f m".format(worst))
        val o = TrailObstacles.Obstacle()
        for (k in 0..12) if (TrailObstacles.obstacle(world.roads[0], k, o)) println("obstacle at s=%.0f log=%s lat=%.1f".format(o.s, o.log, o.lat))
        run {
            world.roads[0].line.pointAt(303.5, tmp)
            val cx = Math.floorDiv(tmp[0].toInt(), 128); val cy = Math.floorDiv(tmp[1].toInt(), 128)
            val cb = com.northwoodsadv.ride.render.ChunkBuilder(g, 400.0)
            val d = cb.build(0, cx, cy)
            val mb = com.northwoodsadv.ride.render.MeshBuilder(64)
            cb.trailObstacles(d, mb)
            println("obstacle verts in chunk: ${mb.vertCount}")
            println("chunk $cx,$cy trees verts=${d.trees?.vertCount} at e=%.0f n=%.0f".format(tmp[0], tmp[1]))
        }
        println("roads=${world.roads.size} classVI=${world.roads[0].spec.isClassVI} kind=${world.roads[0].spec.kind}")
        val gs2 = GroundSample()
        for (ss in (300 * 20)..(306 * 20) step 2) { world.roads[0].line.pointAt(ss / 20.0, tmp); g.sample(tmp[0], tmp[1], gs2); println("  s=$ss h=%.2f surf=%s along=%.1f".format(gs2.height, gs2.surface, gs2.along)) }
        System.getenv("NWADV_SPIKE_AT")?.toDouble()?.let { at ->
            val road = world.roads[0]
            for (ds in listOf(-6.0, -3.0, 0.0, 3.0, 6.0)) { road.line.pointAt(at + ds, tmp); println("  heading at %.0f: %.1f deg  roadH=%.2f grade=%.0f%%".format(at + ds, Math.toDegrees(tmp[2]), road.heightAt(at + ds), road.gradeAt(at + ds) * 100)) }
            road.line.pointAt(at, tmp)
            val h = tmp[2]; val gs = GroundSample()
            for (k in -30..30 step 2) {
                val lat = k * 0.1
                g.sample(tmp[0] - sin(h) * lat, tmp[1] + cos(h) * lat, gs, false)
                println("  lat %+.1f: h=%.2f along=%.1f roadLat=%.2f blend=%.2f %s".format(lat, gs.height, gs.along, gs.lateral, gs.roadBlend, gs.surface))
            }
        }
    }
}
