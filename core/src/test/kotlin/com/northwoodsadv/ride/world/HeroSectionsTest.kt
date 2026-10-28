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
import com.northwoodsadv.ride.terrain.Elevation
import com.northwoodsadv.ride.terrain.FlatElevation
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeroSectionsTest {
    private val hills = object : Elevation {
        override fun height(e: Double, n: Double) = 300 + 0.07 * e + 6 * sin(e / 140.0) + 2 * sin(n / 17.0 + e / 41.0)
    }
    private val classVI = RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.65, "Old Notch Road")

    private fun road(id: Long, spec: RoadSpec = classVI, length: Double = 8000.0) =
        Road(id, spec, Polyline(doubleArrayOf(0.0, length), doubleArrayOf(0.0, 0.0)).resample(2.5), hills)

    /** Several long Class VI roads, so the test sees plenty of sections. */
    private val roads = (1L..6L).map { road(it) }
    private val heroes = roads.flatMap { r -> r.heroes.map { r to it } }

    @Test fun onlyOnClassVIAndTheSameEveryTime() {
        assertTrue(heroes.size >= 10, "only ${heroes.size} sections on 48 km of Class VI")
        for (k in listOf(RoadKind.GRAVEL, RoadKind.MINOR, RoadKind.TRAIL))
            assertTrue(road(1, RoadSpec(k, Surface.DIRT, 3.2, 0.65, "Other")).heroes.isEmpty(), "$k has hero sections")
        val again = road(3).heroes
        assertEquals(roads[2].heroes.map { it.s0 to it.s1 }, again.map { it.s0 to it.s1 })
        for ((r, h) in heroes) {
            assertTrue(h.s0 >= HeroSections.MARGIN && h.s1 <= r.line.length - HeroSections.MARGIN)
            assertTrue(h.dipStart > h.s0 && h.dipEnd < h.s1, "hollow outside its section")
        }
    }

    @Test fun hollowsAreSteepButClimbable() {
        for ((r, h) in heroes) {
            var steepest = 0.0; var s = h.dipStart
            while (s < h.dipEnd) { steepest = maxOf(steepest, abs(r.gradeAt(s))); s += 1.0 }
            assertTrue(steepest > 0.12, "section at %.0f only %.2f steep".format(h.s0, steepest))
            assertTrue(steepest <= HeroSections.MAX_GRADE + 0.02, "section at %.0f is %.2f steep".format(h.s0, steepest))
        }
    }

    @Test fun fordsAreDeepAndBouldersLeaveALine() {
        val ground = Ground(hills, roads, HeroSections.streams(roads))
        val withWater = heroes.filter { it.second.hasWater }
        assertTrue(withWater.isNotEmpty())
        val gs = GroundSample(); val p = DoubleArray(3)
        for ((r, h) in withWater) {
            r.line.pointAt((h.waterA + h.waterB) / 2, p)
            ground.sample(p[0], p[1], gs, true)
            assertTrue(gs.waterDepth > 0.25, "ford only %.2f deep".format(gs.waterDepth))
            assertEquals(Surface.WATER, gs.surface)
        }
        // Boulders: big ones, and every slot along a section has a gap at least a bike wide.
        val rock = HeroSections.Rock(); var count = 0
        for (r in roads) for (j in HeroSections.rockSlots(r)) {
            if (!HeroSections.rock(r, j, rock)) continue
            count++
            assertTrue(rock.r >= 0.6 && rock.h >= 0.35)
            val h = r.heroAt(rock.s)!!
            assertTrue(!h.inWater(rock.s))
            val gap = r.halfWidth - (rock.r - abs(rock.lat)).coerceAtLeast(-r.halfWidth)
            assertTrue(gap > 1.2, "only %.2f m to get by".format(gap))
        }
        assertTrue(count > heroes.size * 5, "only $count boulders")
    }

    /** Hard, not impossible: the auto-rider on easy gets through a dry hollow and a ford. */
    @Test fun easyModeGetsThroughHeroSections() {
        val elev = FlatElevation(100.0)
        val r = Road(7, RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.6, "t"), Polyline(doubleArrayOf(0.0, 3000.0), doubleArrayOf(0.0, 0.0)).resample(10.0), elev)
        assertTrue(r.heroes.any { it.hasWater } && r.heroes.any { !it.hasWater })
        val g = Ground(elev, listOf(r), HeroSections.streams(listOf(r)))
        val bike = Motorcycle(BikeSpec(), g)
        bike.reset(5.0, 0.0, 0.0)
        val assists = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val auto = Autopilot(r.line, g)
        val input = RiderInput()
        var t = 0.0; var crashes = 0
        while (t < 900 && bike.x < 2900) {
            auto.drive(bike, assists, input)
            bike.update(1 / 60.0, input, assists)
            if (bike.crash != null) { crashes++; bike.reset(bike.x + 3, 0.0, 0.0) }
            t += 1 / 60.0
        }
        assertTrue(bike.x > 2850, "stuck at x=%.0f".format(bike.x))
        assertTrue(crashes <= 2, "$crashes crashes")
    }
}
