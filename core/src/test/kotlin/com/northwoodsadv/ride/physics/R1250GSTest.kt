package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.bikes.Bikes
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Surface
import org.junit.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Franco's R 1250 GS should ride like one: heavy, fast, torquey and settled. */
class R1250GSTest {
    private val spec = Bikes.R1250GS.spec

    private fun bikeOn(surface: Surface, kind: RoadKind = RoadKind.HIGHWAY): Motorcycle {
        val b = Motorcycle(spec, TestWorlds.pad(surface, kind))
        b.reset(0.0, 0.0, 0.0)
        return b
    }

    @Test fun settlesAtAboutAThirdSag() {
        val b = bikeOn(Surface.ASPHALT)
        TestRider(b).run(3.0)
        println("r1250gs static: compF=%.3f/%.2f compR=%.3f/%.2f".format(b.compF, spec.frontTravel, b.compR, spec.rearTravel))
        assertTrue(b.compF / spec.frontTravel in 0.2..0.45 && b.compR / spec.rearTravel in 0.2..0.45, "sag about a third of travel")
    }

    @Test fun pullsHardAndRunsFast() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        var t100 = -1.0
        r.run(45.0) {
            it.throttle = 1.0
            if (t100 < 0 && b.forwardSpeed >= 100 / 3.6) t100 = r.time
        }
        val top = b.forwardSpeed * 3.6
        println("r1250gs: 0-100 %.1f s, after 45 s %.0f km/h in gear %d at %.0f rpm".format(t100, top, b.gear, b.rpm))
        assertNull(b.crash)
        // Magazine 0-100 is ~3.5-4 s; the aids, bags and a 90 kg rider cost a little.
        assertTrue(t100 in 3.0..6.0, "0-100 km/h in about 4 s, got $t100")
        assertTrue(top > 190.0, "well over 190 km/h flat out, got $top")
        assertEquals(6, b.gear)
    }

    @Test fun ridesRoughClassViOnEasy() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.8, TestWorlds.Slope(0.10))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val easy = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val r = TestRider(b, easy)
        r.run(25.0) { r.holdSpeed(6.0, it); it.steer = if ((r.time / 4).toInt() % 2 == 0) 0.25 else -0.25 }
        println("r1250gs class VI weave: dist=%.0f crash=%s".format(hypot(b.x, b.y), b.crash))
        assertNull(b.crash)
        assertTrue(hypot(b.x, b.y) > 60)
    }
}
