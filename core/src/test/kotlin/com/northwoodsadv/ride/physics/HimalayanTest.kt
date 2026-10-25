package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.bikes.Bikes
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Surface
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Royal Enfield Himalayan 411 should ride like one: modest pace, low revs, plush and steady. */
class HimalayanTest {
    private val spec = Bikes.HIMALAYAN_411.spec

    private fun bikeOn(surface: Surface, kind: RoadKind = RoadKind.HIGHWAY): Motorcycle {
        val b = Motorcycle(spec, TestWorlds.pad(surface, kind))
        b.reset(0.0, 0.0, 0.0)
        return b
    }

    @Test fun settlesAtAboutAThirdSag() {
        val b = bikeOn(Surface.ASPHALT)
        TestRider(b).run(3.0)
        val weight = spec.mass * Motorcycle.G
        assertEquals(weight, b.fzF + b.fzR, weight * 0.03)
        println("himalayan static: compF=%.3f/%.2f compR=%.3f/%.2f".format(b.compF, spec.frontTravel, b.compR, spec.rearTravel))
        assertTrue(b.compF / spec.frontTravel in 0.25..0.45 && b.compR / spec.rearTravel in 0.25..0.45, "sag about a third of travel")
    }

    @Test fun acceleratesAndTopsOutLikeA411() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        var t60 = -1.0; var t100 = -1.0
        r.run(40.0) {
            it.throttle = 1.0
            if (t60 < 0 && b.forwardSpeed >= 60 / 3.6) t60 = r.time
            if (t100 < 0 && b.forwardSpeed >= 100 / 3.6) t100 = r.time
        }
        val top = b.forwardSpeed * 3.6
        println("himalayan: 0-60 %.1f s, 0-100 %.1f s, after 40 s %.0f km/h in gear %d at %.0f rpm".format(t60, t100, top, b.gear, b.rpm))
        assertNull(b.crash)
        // Magazine tests (lighter rider, stock bike) are 11-12 s; 297 kg with the accessories and a 90 kg rider is slower.
        assertTrue(t100 in 9.0..15.0, "0-100 km/h in 10-15 s, got $t100")
        assertTrue(t60 in 3.5..6.5, "0-60 km/h in 4-6 s, got $t60")
        assertTrue(top in 115.0..140.0, "tops out around 125-130 km/h, got $top")
        assertEquals(5, b.gear, "auto-shifts up through all five gears")
    }

    @Test fun climbsSteepDirt() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.0, TestWorlds.Slope(0.25))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b)
        r.run(1.0) { it.frontBrake = 1.0; it.rearBrake = 1.0; it.throttle = 0.3 }
        r.run(10.0) { r.holdSpeed(8.0, it) }
        println("himalayan 25%% climb: %.1f m/s gear %d crash=%s".format(b.forwardSpeed, b.gear, b.crash))
        assertNull(b.crash)
        assertTrue(b.forwardSpeed > 5.0, "the single lugs up a 25% dirt grade")
    }

    @Test fun ridesRoughClassViOnEasy() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.8, TestWorlds.Slope(0.10))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val easy = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val r = TestRider(b, easy)
        var maxComp = 0.0
        r.run(25.0) {
            r.holdSpeed(6.0, it); it.steer = if ((r.time / 4).toInt() % 2 == 0) 0.25 else -0.25
            maxComp = maxOf(maxComp, b.compF)
        }
        println("himalayan class VI weave: dist=%.0f crash=%s maxCompF=%.3f".format(hypot(b.x, b.y), b.crash, maxComp))
        assertNull(b.crash)
        assertTrue(hypot(b.x, b.y) > 60)
        assertTrue(maxComp < spec.frontTravel + 0.005, "fork stays within its travel")
    }

    @Test fun tracksStraightOverBumps() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.75, TestWorlds.Slope(0.03))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b, Assists().apply { applyDifficulty(Difficulty.EASY) })
        r.run(4.0) { r.holdSpeed(9.0, it) }
        var maxHdg = 0.0
        r.run(20.0) { r.holdSpeed(9.0, it); it.steer = 0.0; maxHdg = maxOf(maxHdg, abs(b.yaw)) }
        println("himalayan bumps: max heading change %.1f deg crash=%s".format(Math.toDegrees(maxHdg), b.crash))
        assertNull(b.crash)
        assertTrue(Math.toDegrees(maxHdg) < 15)
    }
}
