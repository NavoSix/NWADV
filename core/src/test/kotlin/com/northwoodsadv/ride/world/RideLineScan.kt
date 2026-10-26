package com.northwoodsadv.ride.world

import com.badlogic.gdx.utils.GdxNativesLoader
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxParser
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rides a route's line looking for the things that stop a ride: steps and walls in the ground,
 * gaps where no road is under the track, buildings or boulders in the way, water over the road.
 * Development scan: NWADV_LINESCAN=<gpx path> (and -Dnwadv.osmFile for a saved map).
 */
class RideLineScan {
    @Test fun scan() {
        val path = System.getenv("NWADV_LINESCAN") ?: return
        System.getenv("NWADV_OSM")?.let { System.setProperty("nwadv.osmFile", it) }
        GdxNativesLoader.load()
        val gpx = GpxParser.parse(File(path).readText())
        val world = WorldBuilder(DiskCache(File("build/ride-cache"))).build(gpx)
        val g = world.ground
        val p = DoubleArray(3)
        val gs = GroundSample()
        var steps = 0; var walls = 0; var offRoad = 0; var blocked = 0; var wet = 0
        var s = 0.0
        var prevH = Double.NaN
        while (s < world.route.length) {
            world.route.pointAt(s, p)
            val h = p[2]
            for (lat in listOf(-1.0, 0.0, 1.0)) {
                val e = p[0] - sin(h) * lat; val n = p[1] + cos(h) * lat
                g.sample(e, n, gs, false)
                if (lat == 0.0) {
                    if (!prevH.isNaN()) {
                        val d = abs(gs.height - prevH)
                        if (d > 0.3) { steps++; if (steps <= 10) println("step %.2f m at %.0f".format(d, s)) }
                        if (d / 0.5 > 0.45) { walls++; if (walls <= 10) println("wall %.0f%% at %.0f".format(d / 0.5 * 100, s)) }
                    }
                    prevH = gs.height
                    if (!gs.onRoad && gs.road >= 0 && gs.roadBlend < 0.9) { offRoad++; if (offRoad <= 10) println("off the road surface at %.0f (blend %.2f)".format(s, gs.roadBlend)) }
                    if (gs.road < 0) { offRoad++; if (offRoad <= 10) println("no road at %.0f".format(s)) }
                }
                if (g.scenery.solidAt(e, n, 0.3) != null) { blocked++; if (blocked <= 10) println("${g.scenery.solidAt(e, n, 0.3)!!.kind} in the way at %.0f".format(s)) }
                if (!gs.waterLevel.isNaN() && gs.waterLevel > gs.height + 0.05 && gs.onRoad) { wet++; if (wet <= 10) println("water over the road at %.0f".format(s)) }
            }
            s += 0.5
        }
        System.getenv("NWADV_LINESCAN_AT")?.split("..")?.map { it.toDouble() }?.let { (a, b) ->
            var t = a
            while (t <= b) {
                world.route.pointAt(t, p); g.sample(p[0], p[1], gs, false)
                val r = if (gs.road >= 0) world.roads[gs.road] else null
                println("  at %.1f: h=%.2f road=%s (%s, %.0f m) along=%.1f lat=%.1f blend=%.2f".format(t, gs.height, r?.id, r?.spec?.name, r?.line?.length ?: 0.0, gs.along, gs.lateral, gs.roadBlend))
                val o = DoubleArray(2)
                for (rd in world.roads) {
                    rd.line.project(p[0], p[1], 0.0, Double.MAX_VALUE, o)
                    if (o[1] < 14) println("      road ${rd.id} ${rd.spec.kind} dist %.1f flat %.1f roadH %.2f terrain %.2f".format(o[1], rd.halfWidth + g.shoulder(rd), rd.heightAt(o[0]), g.elevation.height(p[0], p[1])))
                }
                t += 1.0
            }
        }
        // Every road's centreline too (junctions the route doesn't take).
        var roadSteps = 0
        for (rd in world.roads) {
            var t = 0.0; var prev = Double.NaN
            while (t < rd.line.length) {
                rd.line.pointAt(t, p); g.sample(p[0], p[1], gs, false)
                if (!prev.isNaN() && abs(gs.height - prev) > 0.3) { roadSteps++; if (roadSteps <= 10) println("road ${rd.id} (${rd.spec.name}) step %.2f m at %.0f".format(gs.height - prev, t)) }
                prev = gs.height; t += 0.5
            }
        }
        println("road centreline steps: $roadSteps")
        println("route %.0f m: steps $steps, walls $walls, off-road $offRoad, blocked $blocked, wet $wet".format(world.route.length))
        println("scenery: " + g.scenery.props.groupingBy { it.kind }.eachCount())
    }
}
