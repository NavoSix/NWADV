package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.Elevation
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Roads split into pieces by the map (at junctions, bridges) must meet without steps. */
class RoadJoinTest {
    /** Lumpy hillside: a grade plus bumps of a few metres. */
    private val hills = object : Elevation {
        override fun height(e: Double, n: Double) = 200 + 0.06 * e + 3 * sin(e / 23.0) + 2 * sin(n / 17.0 + e / 41.0)
    }

    private fun line(x0: Double, x1: Double, y: Double = 0.0) = Polyline(doubleArrayOf(x0, x1), doubleArrayOf(y, y)).resample(2.5)

    private fun worstStep(ground: Ground, x0: Double, x1: Double, y: Double = 0.0): Double {
        var worst = 0.0; var prev = Double.NaN; var x = x0
        while (x < x1) {
            val h = ground.height(x, y, false)
            if (!prev.isNaN()) worst = maxOf(worst, abs(h - prev))
            prev = h; x += 0.5
        }
        return worst
    }

    @Test
    fun piecesOfOneRoadMeetFlush() {
        val spec = RoadSpec(RoadKind.HIGHWAY, Surface.ASPHALT, 7.6, 0.0, "Kancamagus Highway")
        // A long way, a 10 m piece (as at a junction), another long way.
        val lines = listOf(line(0.0, 1500.0), line(1500.0, 1510.0), line(1510.0, 2700.0))
        val carry = WorldBuilder.continuations(lines)
        val roads = lines.mapIndexed { i, l -> Road(i.toLong() + 1, spec, l, hills, carry[i].first, carry[i].second) }
        Road.connectAll(roads)
        val worst = worstStep(Ground(hills, roads), 1400.0, 1600.0)
        assertTrue(worst < 0.1, "worst step %.2f m per 50 cm".format(worst))
    }

    @Test
    fun gpxCornerCutMeetsTheRoadsItJoins() {
        val main = Road(1, RoadSpec(RoadKind.HIGHWAY, Surface.ASPHALT, 7.6, 0.0, "Main"), line(0.0, 2000.0), hills)
        val side = Road(2, RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.1, "Side"),
            Polyline(doubleArrayOf(1000.0, 1000.0), doubleArrayOf(0.0, 800.0)).resample(2.5), hills)
        // The track runs 30 m along the main road, then cuts the corner onto the side road.
        val stub = Road(-1000, RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.12, "Track"),
            Polyline(doubleArrayOf(960.0, 990.0, 1000.0, 1000.0), doubleArrayOf(0.0, 0.0, 10.0, 40.0)).resample(2.5), hills)
        val roads = listOf(main, side, stub)
        Road.connectAll(roads)
        val g0 = Ground(hills, roads)
        Road.flushWithMapped(roads) { g0.shoulder(it) }
        val ground = Ground(hills, roads)
        val p = DoubleArray(3)
        var worst = 0.0; var prev = Double.NaN; var s = 0.0
        while (s < stub.line.length) {
            stub.line.pointAt(s, p)
            val h = ground.height(p[0], p[1], false)
            if (!prev.isNaN()) worst = maxOf(worst, abs(h - prev))
            prev = h; s += 0.5
        }
        assertTrue(worst < 0.15, "worst step along the track %.2f m per 50 cm".format(worst))
    }
}
