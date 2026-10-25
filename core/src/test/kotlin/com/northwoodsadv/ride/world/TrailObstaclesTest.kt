package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.physics.Assists
import com.northwoodsadv.ride.physics.Autopilot
import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.physics.Difficulty
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.physics.RiderInput
import com.northwoodsadv.ride.terrain.FlatElevation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailObstaclesTest {
    private val elev = FlatElevation(100.0)
    private fun road(kind: RoadKind, surface: Surface, rough: Double) =
        Road(7, RoadSpec(kind, surface, if (kind == RoadKind.CLASS_VI) 3.2 else 6.0, rough, "t"),
            Polyline(doubleArrayOf(0.0, 3000.0), doubleArrayOf(0.0, 0.0)).resample(10.0), elev)

    private fun count(r: Road): Pair<Int, Int> {
        val o = TrailObstacles.Obstacle(); var logs = 0; var rocks = 0
        for (k in 0..(r.line.length / TrailObstacles.SLOT).toInt()) if (TrailObstacles.obstacle(r, k, o)) { if (o.log) logs++ else rocks++ }
        return logs to rocks
    }

    @Test fun onlyOnClassVi() {
        assertEquals(0 to 0, count(road(RoadKind.MINOR, Surface.ASPHALT, 0.1)))
        assertEquals(0 to 0, count(road(RoadKind.GRAVEL, Surface.GRAVEL_PACKED, 0.25)))
        val (logs, rocks) = count(road(RoadKind.CLASS_VI, Surface.DIRT, 0.6))
        println("3 km of Class VI: $logs logs, $rocks rocks")
        assertTrue(logs > 3 && rocks > 3 && logs + rocks < 30, "a handful per km")
    }

    @Test fun easyModeRidesThroughLogsAndRocks() {
        val r = road(RoadKind.CLASS_VI, Surface.DIRT, 0.6)
        val g = Ground(elev, listOf(r))
        val bike = Motorcycle(BikeSpec(), g)
        bike.reset(5.0, 0.0, 0.0)
        val assists = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val auto = Autopilot(r.line, g)
        val input = RiderInput()
        var t = 0.0; var crashes = 0; var logHits = 0; var onLog = false
        while (t < 900 && bike.x < 2900) {
            auto.drive(bike, assists, input)
            bike.update(1 / 60.0, input, assists)
            val nowOnLog = bike.surfaceF == Surface.LOG
            if (nowOnLog && !onLog) logHits++
            onLog = nowOnLog
            if (bike.crash != null) { crashes++; bike.reset(bike.x + 3, 0.0, 0.0) }
            t += 1 / 60.0
        }
        println("rode to x=%.0f in %.0f s, crossed %d logs, crashes=%d".format(bike.x, t, logHits, crashes))
        assertTrue(bike.x > 2800, "got through")
        assertTrue(logHits > 2, "actually rode over logs")
        assertTrue(crashes <= 2, "few crashes on easy")
    }
}
