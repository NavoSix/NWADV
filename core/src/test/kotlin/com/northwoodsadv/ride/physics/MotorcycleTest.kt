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
        assertTrue(b.compF in 0.05..0.12 && b.compR in 0.05..0.12, "sag 25-45% of travel")
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
        assertTrue(t100 in 2.8..6.5, "0-100 in a plausible time, got $t100")
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
        val assists = Assists(absFront = abs, absRear = abs, stability = 0.0, grip = 1.0, lateralGrip = 1.0)
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

    @Test fun easyModeHoldsLeanOnLooseGravel() {
        val b = bikeOn(Surface.GRAVEL_LOOSE, RoadKind.GRAVEL)
        val easy = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val r = TestRider(b, easy)
        r.run(8.0) { r.holdSpeed(12.0, it) }
        r.run(8.0) { r.holdSpeed(12.0, it); it.steer = -30.0 / 52.0 }
        println("easy gravel 30 deg: crash=${b.crash} lean=%.1f".format(Math.toDegrees(b.lean)))
        assertNull(b.crash, "easy mode keeps the bike up at 30 deg on loose gravel")
    }

    @Test fun easyModeRidesRoughClassViStraightAndTurning() {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, 0.8, TestWorlds.Slope(0.10))
        val b = Motorcycle(spec, ground)
        b.reset(0.0, 0.0, 0.0)
        val easy = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val r = TestRider(b, easy)
        r.run(25.0) { r.holdSpeed(6.0, it); it.steer = if ((r.time / 4).toInt() % 2 == 0) 0.25 else -0.25 }
        println("easy class VI weave: dist=%.0f crash=%s".format(hypot(b.x, b.y), b.crash))
        assertNull(b.crash)
        assertTrue(hypot(b.x, b.y) > 60)
    }
}

class BumpStabilityTest {
    /** Straight-ahead input on rough Class VI: how much do bumps knock the bike off line? */
    @Test fun bumpsDontKnockTheBikeOffLine() {
        for ((speed, rough) in listOf(7.0 to 0.75, 11.0 to 0.75, 11.0 to 0.95)) ride(speed, rough)
    }

    private fun ride(speed: Double, rough: Double) {
        val ground = TestWorlds.pad(Surface.DIRT, RoadKind.CLASS_VI, rough, TestWorlds.Slope(0.03))
        val b = Motorcycle(BikeSpec(), ground)
        b.reset(0.0, 0.0, 0.0)
        val easy = Assists().apply { applyDifficulty(Difficulty.EASY) }
        val r = TestRider(b, easy)
        r.run(4.0) { r.holdSpeed(speed, it) }
        var sumYawRate = 0.0; var n = 0; var maxHdg = 0.0; var maxSlip = 0.0
        r.run(20.0) {
            r.holdSpeed(speed, it); it.steer = 0.0
            sumYawRate += b.yawRate * b.yawRate; n++
            maxHdg = maxOf(maxHdg, abs(b.yaw)); maxSlip = maxOf(maxSlip, abs(b.tanAlphaR))
        }
        val rmsYaw = kotlin.math.sqrt(sumYawRate / n)
        println("bumps v=$speed rough=$rough: rms yaw rate=%.3f rad/s, max heading change=%.1f deg, max rear slip=%.3f, y drift=%.1f m, crash=%s".format(
            rmsYaw, Math.toDegrees(maxHdg), maxSlip, b.y, b.crash))
        assertNull(b.crash)
        assertTrue(rmsYaw < 0.12, "bike tracks straight over bumps (rms yaw rate $rmsYaw)")
        assertTrue(Math.toDegrees(maxHdg) < 15, "heading stays within 15 deg")
    }
}
