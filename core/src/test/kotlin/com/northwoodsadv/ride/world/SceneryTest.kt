package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.GeoPoint
import com.northwoodsadv.ride.geo.LocalProjection
import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.OsmData
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.render.ChunkData
import com.northwoodsadv.ride.render.MeshSet
import com.northwoodsadv.ride.render.PixelFont
import com.northwoodsadv.ride.render.SceneryMesh
import com.northwoodsadv.ride.route.RouteTracker
import com.northwoodsadv.ride.terrain.FlatElevation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneryTest {
    private val elevation = FlatElevation(150.0)
    private val proj = LocalProjection(GeoPoint(44.0, -71.5))

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, step: Double = 2.5): Polyline {
        val n = (kotlin.math.hypot(x1 - x0, y1 - y0) / step).toInt()
        return Polyline(DoubleArray(n + 1) { x0 + (x1 - x0) * it / n }, DoubleArray(n + 1) { y0 + (y1 - y0) * it / n })
    }

    /** A paved main road along x with a named side road teeing in from the north at x = 1000. */
    private fun tee(): List<Road> = listOf(
        Road(1, RoadSpec(RoadKind.MAJOR, Surface.ASPHALT, 7.0, 0.05, "Main Street"), line(0.0, 0.0, 1000.0, 0.0), elevation),
        Road(2, RoadSpec(RoadKind.MAJOR, Surface.ASPHALT, 7.0, 0.05, "Main Street"), line(1000.0, 0.0, 2500.0, 0.0), elevation),
        Road(3, RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.1, "Bear Notch Road"), line(1000.0, 0.0, 1000.0, 1200.0), elevation),
    )

    private fun build(roads: List<Road>, route: Polyline): Pair<Scenery, SceneryBuilder> {
        val b = SceneryBuilder(Ground(elevation, roads), OsmData.EMPTY, proj, route, emptyList())
        return b.build() to b
    }

    @Test
    fun junctionGetsStopSignOnTheLesserRoadAndStreetBlades() {
        val roads = tee()
        val (sc, _) = build(roads, line(0.0, 0.0, 2500.0, 0.0, 10.0))
        assertEquals(1, sc.junctions.size)
        val j = sc.junctions[0]
        assertEquals(3, j.arms.size)
        assertTrue(kotlin.math.hypot(j.x - 1000, j.y) < 8)
        val stops = sc.props.filter { it.kind == PropKind.STOP_SIGN }
        assertEquals(1, stops.size, "one stop sign, on Bear Notch Road")
        val stop = stops[0]
        // On the side road, on the right of traffic heading south down to Main Street (the west side), facing them.
        assertTrue(stop.y > 4 && stop.x < 1000, "at ${stop.x}, ${stop.y}")
        assertTrue(kotlin.math.abs(kotlin.math.sin(stop.heading) - 1) < 0.05, "faces oncoming traffic")
        val blades = sc.props.single { it.kind == PropKind.STREET_SIGN }
        assertEquals(setOf("Main Street", "Bear Notch Road"), blades.text.toSet())
    }

    @Test
    fun routeTurnIsNamedAfterTheRoadItTurnsOnto() {
        val roads = tee()
        val xs = DoubleArray(141) { if (it <= 100) it * 10.0 else 1000.0 }
        val ys = DoubleArray(141) { if (it <= 100) 0.0 else (it - 100) * 10.0 }
        val route = Polyline(xs, ys)
        val (sc, _) = build(roads, route)
        val tracker = RouteTracker(route)
        tracker.nameTurns(sc.junctions)
        val t = tracker.turns.single()
        assertEquals("Left onto Bear Notch Road", t.label)
    }

    @Test
    fun classSixRoadsStayWild() {
        val road = Road(5, RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.6, "Old Mast Road"), line(0.0, 0.0, 6000.0, 0.0), elevation)
        val (sc, b) = build(listOf(road), road.line)
        val cosmetic = sc.props.filter { it.kind != PropKind.ERRATIC && it.kind != PropKind.HERO_SIGN }
        assertTrue(cosmetic.isEmpty(), "no houses, signs or mailboxes: $cosmetic")
        // Only the boards at each end of its hero sections.
        assertEquals(road.heroes.size * 2, sc.props.count { it.kind == PropKind.HERO_SIGN })
        assertTrue(sc.runs.isEmpty(), "no walls, fences or rails")
        assertTrue(b.driveways.isEmpty())
    }

    @Test
    fun pavedCountryRoadGetsHousesWallsAndSigns() {
        val roads = (0 until 6).map { k ->
            Road(10L + k, RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.1, "River Road"), line(k * 2000.0, 0.0, (k + 1) * 2000.0, 0.0), elevation)
        }
        val route = line(0.0, 0.0, 12000.0, 0.0, 10.0)
        val (sc, b) = build(roads, route)
        val kinds = sc.props.groupingBy { it.kind }.eachCount()
        assertTrue((kinds[PropKind.HOUSE] ?: 0) > 3, "houses: $kinds")
        assertTrue((kinds[PropKind.SIGN_SPEED] ?: 0) > 3, "speed limits: $kinds")
        assertTrue(b.driveways.isNotEmpty())
        // Every building stands clear of the road and of its neighbours' footprints.
        val ground = Ground(elevation, roads)
        for (p in sc.props.filter { it.kind == PropKind.HOUSE || it.kind == PropKind.BARN }) {
            assertTrue(ground.roadClearance(p.x, p.y) > 5, "house on the road at ${p.x}, ${p.y}")
        }
        // Solid: the bike can hit a house.
        val h = sc.props.first { it.kind == PropKind.HOUSE }
        assertNotNull(sc.solidAt(h.x, h.y, 0.3))
        assertNull(sc.solidAt(h.x, h.y + 300, 0.3))
    }

    @Test
    fun meshesAreBuiltForProps() {
        val props = listOf(
            Prop(PropKind.ERRATIC, 30.0, 30.0, 0.3, 4.0, 3.0, 5.0, 123456789),
            Prop(PropKind.HOUSE, 60.0, 60.0, 0.0, 5.0, 7.0, 5.6, 42, color = 0xf2f0e8),
            Prop(PropKind.STREET_SIGN, 80.0, 80.0, 0.0, 0.05, 0.05, 3.0, 1, listOf("Main Street", "Bear Notch Road"), heading2 = 1.57),
        )
        val ground = Ground(elevation, emptyList())
        ground.scenery = Scenery(props, emptyList(), emptyList(), emptyList())
        val d = ChunkData(0, 0, 0, 0.0, 0.0, 128.0)
        val ms = MeshSet()
        SceneryMesh(ground, 0.0).build(d, ms, ground.scenery)
        val m = ms.parts.single()
        assertTrue(m.vertCount > 300)
        // The boulder is a big lump, not a sliver: its vertices span metres in x and z.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        for (i in 0 until m.vertCount) { val x = m.verts[i * 7]; if (x < 40) { minX = minOf(minX, x); maxX = maxOf(maxX, x) } }
        assertTrue(maxX - minX > 5, "boulder width ${maxX - minX}")
    }

    @Test
    fun streetNamesAbbreviateLikeTheBlades() {
        assertEquals("BEAR NOTCH RD", PixelFont.signName("Bear Notch Road"))
        assertEquals("KANCAMAGUS HWY", PixelFont.signName("Kancamagus Highway"))
        assertEquals("N MAIN ST", PixelFont.signName("North Main Street"))
    }
}
