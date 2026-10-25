package com.northwoodsadv.ride.world

import com.badlogic.gdx.utils.GdxNativesLoader
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxParser
import org.junit.Test
import java.io.File

/** Debug: lists the deepest road cuts and strongest banking on a route (NWADV_CUTS=<route file>). */
class CutScan {
    @Test fun scan() {
        val routeName = System.getenv("NWADV_CUTS") ?: return
        GdxNativesLoader.load()
        val world = WorldBuilder(DiskCache(File("build/ride-cache"))).build(GpxParser.parse(File("../assets/routes/$routeName").readText()))
        val g = world.ground
        val p = DoubleArray(3)
        if (System.getenv("NWADV_CHUNKS") != null) {
            val cb = com.northwoodsadv.ride.render.ChunkBuilder(g, 0.0)
            for (at in listOf(500.0, 2600.0, 4800.0, 7000.0)) {
                world.route.pointAt(at, p)
                for (level in 0..1) {
                    val size = com.northwoodsadv.ride.render.ChunkBuilder.BASE * (1 shl level)
                    val t0 = System.nanoTime()
                    val d = cb.build(level, kotlin.math.floor(p[0] / size).toInt(), kotlin.math.floor(p[1] / size).toInt())
                    println("  chunk L$level at $at: %.0f ms terrain=%d roads=%d trees=%d (%d parts) water=%d".format((System.nanoTime() - t0) / 1e6,
                        d.terrain.vertCount, d.roads?.vertCount ?: 0, d.trees.vertCount, d.trees.parts.size, d.water?.vertCount ?: 0))
                }
            }
            return
        }
        System.getenv("NWADV_ALONG")?.split(":")?.let { (ri, a, b) ->
            val r = world.roads[ri.toInt()]
            val gs = GroundSample()
            var t = a.toDouble()
            while (t <= b.toDouble()) {
                r.line.pointAt(t, p)
                g.sample(p[0], p[1], gs, System.getenv("NWADV_MICRO") != null)
                println("  s=%6.1f h=%8.2f roadH=%8.2f bank=%.3f road=%d blend=%.2f %s".format(t, gs.height, r.heightAt(t), r.bankAt(t), gs.road, gs.roadBlend, gs.surface))
                t += (System.getenv("NWADV_STEP") ?: "2").toDouble()
            }
            return
        }
        System.getenv("NWADV_XSEC")?.split(":")?.let { (ri, sAt) ->
            val r = world.roads[ri.toInt()]; r.line.pointAt(sAt.toDouble(), p)
            val lx = -kotlin.math.sin(p[2]); val ly = kotlin.math.cos(p[2])
            val gs = GroundSample()
            for (k in -40..40) {
                val off = k * 0.5
                g.sample(p[0] + lx * off, p[1] + ly * off, gs, false)
                val terr = g.elevation.height(p[0] + lx * off, p[1] + ly * off)
                println("  x=%5.1f h=%7.2f terr=%7.2f %s cut=%.2f blend=%.2f road=%d light=%.2f".format(off, gs.height, terr, gs.surface, gs.cutFace, gs.roadBlend, gs.road,
                    if (gs.road >= 0) Canopy.light(g, g.roads[gs.road], gs.along, gs.lateral, p[0] + lx * off, p[1] + ly * off) else 1.0))
            }
            return
        }
        for ((ri, r) in world.roads.withIndex()) {
            val rows = ArrayList<DoubleArray>()
            var s = 20.0
            while (s < r.line.length - 20) {
                r.line.pointAt(s, p)
                val lx = -kotlin.math.sin(p[2]); val ly = kotlin.math.cos(p[2])
                val off = r.halfWidth + g.shoulder(r) + 6
                val hl = g.elevation.height(p[0] + lx * off, p[1] + ly * off) - r.heightAt(s)
                val hr = g.elevation.height(p[0] - lx * off, p[1] - ly * off) - r.heightAt(s)
                rows += doubleArrayOf(s, maxOf(hl, hr), r.bankAt(s), r.rockCut(s))
                s += 10
            }
            println("road $ri ${r.spec.kind} len=%.0f".format(r.line.length))
            println("  tunnels: " + (0 until (r.line.length / 50).toInt()).filter { Canopy.tunnel(r, it * 50.0) > 0.7 }.map { it * 50 }.joinToString())
            for (row in rows.sortedByDescending { it[1] }.take(8)) println("  cut s=%.0f h=%.1f bank=%.3f rock=%.2f".format(*row.toTypedArray()))
            for (row in rows.sortedByDescending { kotlin.math.abs(it[2]) }.take(5)) println("  bank s=%.0f h=%.1f bank=%.3f rock=%.2f".format(*row.toTypedArray()))
        }
    }
}
