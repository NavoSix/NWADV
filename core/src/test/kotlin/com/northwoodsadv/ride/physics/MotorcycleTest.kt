package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Surface
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotorcycleTest {
    private val spec = BikeSpec()

    private fun bikeOn(surface: Surface, kind: RoadKind = RoadKind.HIGHWAY): Motorcycle {
        val b = Motorcycle(spec, TestWorlds.pad(surface, kind))
        b.reset(0.0, 0.0, 0.0)
        return b
    }

    @Test fun settlesOnSuspensionAtStaticSag() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        r.run(3.0)
        assertNull(b.crash)
        val weight = spec.mass * Motorcycle.G
        assertEquals(weight, b.fzF + b.fzR, weight * 0.03, "suspension carries the bike")
        println("static: fzF=%.0f fzR=%.0f compF=%.3f compR=%.3f lean=%.3f".format(b.fzF, b.fzR, b.compF, b.compR, b.lean))
        assertTrue(b.compF in 0.04..0.10 && b.compR in 0.04..0.10, "sag ~30% of travel")
        assertTrue(abs(b.lean) < 0.05, "stays upright when stopped")
    }

    @Test fun acceleratesLikeAnAdvTwin() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        var t100 = -1.0
        r.run(12.0) {
            it.throttle = 1.0
            if (t100 < 0 && b.forwardSpeed >= 100 / 3.6) t100 = r.time
        }
        println("0-100 km/h: %.2f s, gear=%d, rpm=%.0f, speed after 12 s %.1f km/h".format(t100, b.gear, b.rpm, b.forwardSpeed * 3.6))
        assertNull(b.crash, "no crash on a straight launch with wheelie control")
        assertTrue(t100 in 2.8..6.0, "0-100 in a plausible time, got $t100")
        assertTrue(abs(b.y) < 2.0, "goes straight")
    }

    @Test fun steadyTurnMatchesLeanPhysics() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        r.run(6.0) { r.holdSpeed(15.0, it); it.steer = 0.0 }
        val wantLean = Math.toRadians(30.0)
        r.run(8.0) { r.holdSpeed(15.0, it); it.steer = -30.0 / Assists().maxLeanDeg }
        assertNull(b.crash)
        val u = b.forwardSpeed
        val radius = u / abs(b.yawRate)
        val expected = u * u / (Motorcycle.G * kotlin.math.tan(abs(b.lean)))
        println("turn: lean=%.1f deg speed=%.1f radius=%.1f m (ideal %.1f) steer=%.2f deg".format(Math.toDegrees(b.lean), u, radius, expected, Math.toDegrees(b.steer)))
        assertEquals(wantLean, b.lean, Math.toRadians(4.0), "reaches requested lean")
        assertTrue(b.yawRate > 0, "left lean turns left")
        assertEquals(expected, radius, expected * 0.2, "turn radius consistent with lean")
    }

    @Test fun countersteersToInitiateTurn() {
        val b = bikeOn(Surface.ASPHALT)
        val r = TestRider(b)
        r.run(6.0) { r.holdSpeed(20.0, it) }
        var minSteer = 0.0
        r.run(0.4) { r.holdSpeed(20.0, it); it.steer = -0.6; minSteer = minOf(minSteer, b.steer) }
        println("initial steer when asking for left lean: %.2f deg".format(Math.toDegrees(minSteer)))
        assertTrue(minSteer < -0.002, "bars first turn right to lean left")
    }

    private fun brakingDistance(surface: Surface, kind: RoadKind, abs: Boolean): Double {
        val b = bikeOn(surface, kind)
        val assists = Assists(absFront = abs, absRear = abs)
        val r = TestRider(b, assists)
        val v0 = if (surface.isPaved) 100 / 3.6 else 60 / 3.6
        r.run(14.0) { r.holdSpeed(v0, it) }
        val x0 = b.x
        r.run(15.0) { it.throttle = 0.0; it.frontBrake = 1.0; it.rearBrake = 1.0; if (b.forwardSpeed < 0.3) { it.frontBrake = 0.0; it.rearBrake = 0.0 } }
        assertNull(b.crash, "straight-line braking on $surface")
        return b.x - x0
    }

    @Test fun absBrakingDistances() {
        val asphalt = brakingDistance(Surface.ASPHALT, RoadKind.HIGHWAY, true)
        val gravel = brakingDistance(Surface.GRAVEL_LOOSE, RoadKind.GRAVEL, true)
        println("100-0 asphalt (ABS): %.1f m; 60-0 loose gravel (ABS): %.1f m".format(asphalt, gravel))
        assertTrue(asphalt in 36.0..55.0, "asphalt braking distance $asphalt")
        // 60 km/h on asphalt would be ~ asphalt * 0.36; loose gravel should be much longer.
        assertTrue(gravel > asphalt * 0.36 * 1.4, "gravel is longer: $gravel")
    }

    @Test fun lowsidesWhenLeaningTooFarOnGravel() {
        val b = bikeOn(Surface.GRAVEL_LOOSE, RoadKind.GRAVEL)
        val r = TestRider(b)
        r.run(8.0) { r.holdSpeed(14.0, it) }
        r.run(6.0) { r.holdSpeed(14.0, it); it.steer = -1.0 }
        println("gravel full lean: crash=${b.crash} lean=%.1f".format(Math.toDegrees(b.lean)))
        assertNotNull(b.crash, "52 deg on loose gravel should not hold")
    }

    @Test fun holdsModestLeanOnGravel() {
        val b = bikeOn(Surface.GRAVEL_PACKED, RoadKind.GRAVEL)
        val r = TestRider(b)
        r.run(8.0) { r.holdSpeed(12.0, it) }
        r.run(6.0) { r.holdSpeed(12.0, it); it.steer = -20.0 / 52.0 }
        println("gravel 20 deg: crash=${b.crash} lean=%.1f slipF=%.2f slipR=%.2f".format(Math.toDegrees(b.lean), b.slipF, b.slipR))
        assertNull(b.crash)
    }

    @Test fun climbsSteepDirtAndHoldsOnBrakes() {
        val elev = TestWorlds.Slope(0.25)
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.0, elev)
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b)
        r.run(1.0) { it.frontBrake = 1.0; it.rearBrake = 1.0; it.throttle = 0.3 }
        r.run(10.0) { r.holdSpeed(8.0, it) }
        println("25%% climb: speed=%.1f m/s gear=%d crash=%s".format(b.forwardSpeed, b.gear, b.crash))
        assertNull(b.crash)
        assertTrue(b.forwardSpeed > 5.0, "can climb a 25% dirt grade")
        r.run(6.0) { it.throttle = 0.0; it.frontBrake = 0.8; it.rearBrake = 0.8 }
        val x0 = b.x
        r.run(3.0) { it.throttle = 0.0; it.frontBrake = 0.8; it.rearBrake = 0.8 }
        println("held on slope: drift=%.2f m".format(abs(b.x - x0)))
        assertTrue(abs(b.x - x0) < 0.3, "brakes hold the bike on the hill")
    }

    @Test fun ridesClassViRoughnessWithoutBlowingUp() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.75, TestWorlds.Slope(0.09))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b)
        var maxComp = 0.0
        r.run(20.0) { r.holdSpeed(5.0, it); r.holdHeading(0.0, it); it.standing = true; maxComp = maxOf(maxComp, b.compF) }
        println("class VI: dist=%.0f m crash=%s maxCompF=%.3f".format(hypot(b.x, b.y), b.crash, maxComp))
        assertTrue(b.x > 40, "makes progress through rough terrain")
        assertTrue(maxComp > 0.1, "suspension is working")
    }
}
