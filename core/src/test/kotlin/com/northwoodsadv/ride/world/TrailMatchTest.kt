package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.FlatElevation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailMatchTest {
    private val elev = FlatElevation(100.0)
    private fun road(id: Long, x0: Double, y0: Double, x1: Double, y1: Double) =
        Road(id, RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.1, "r$id"), Polyline(doubleArrayOf(x0, x1), doubleArrayOf(y0, y1)).resample(10.0), elev)

    /** GPX line along x from 0 to 2000 m. */
    private val gpx = Polyline(doubleArrayOf(0.0, 2000.0), doubleArrayOf(0.0, 0.0)).resample(10.0)

    @Test fun crossingARoadDoesNotBreakTheTrail() {
        // A road crossing at right angles at x = 1000, and one running parallel 15 m away.
        val g = Ground(elev, listOf(road(1, 1000.0, -500.0, 1000.0, 500.0), road(2, 300.0, 15.0, 700.0, 15.0)))
        val runs = WorldBuilder.offRoadRuns(gpx, g)
        assertEquals(1, runs.size, "one continuous trail: $runs")
        assertEquals(0, runs[0].first); assertEquals(gpx.size - 1, runs[0].last)
    }

    @Test fun ridingAlongARoadUsesTheRoad() {
        // The GPX follows a mapped road from x = 600 to 1400, then leaves it.
        val g = Ground(elev, listOf(road(1, 600.0, 2.0, 1400.0, 2.0)))
        val runs = WorldBuilder.offRoadRuns(gpx, g)
        assertEquals(2, runs.size, "trail before and after the road: $runs")
        // Trail pieces overlap into the road so there's no gap at the junctions.
        assertTrue(gpx.xs[runs[0].last] > 600.0, "first trail reaches onto the road")
        assertTrue(gpx.xs[runs[1].first] < 1400.0, "second trail starts on the road")
    }
}
