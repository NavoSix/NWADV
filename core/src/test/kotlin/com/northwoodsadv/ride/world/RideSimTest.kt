package com.northwoodsadv.ride.world

import com.badlogic.gdx.utils.GdxNativesLoader
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxParser
import com.northwoodsadv.ride.physics.Assists
import com.northwoodsadv.ride.physics.Autopilot
import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.physics.RiderInput
import com.northwoodsadv.ride.route.RouteTracker
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Builds the world for a bundled route (downloading real elevation data) and lets the autopilot
 * ride it. Needs network access the first time, so it only runs with NWADV_RIDE_TEST=<route file>.
 */
class RideSimTest {
    @Test fun autopilotRidesRoute() {
        val routeName = System.getenv("NWADV_RIDE_TEST") ?: return
        GdxNativesLoader.load()
        val file = File("../assets/routes/$routeName")
        val gpx = GpxParser.parse(file.readText())
        val t0 = System.nanoTime()
        val world = WorldBuilder(DiskCache(File("build/ride-cache"))).build(gpx) { stage, f -> if (f == 0f) println("  $stage") }
        println("world built in %.1f s: %d roads, notes=%s".format((System.nanoTime() - t0) / 1e9, world.roads.size, world.notes))

        val bike = Motorcycle(com.northwoodsadv.ride.bikes.Bikes.byId(System.getenv("NWADV_BIKE")).spec, world.ground)
        bike.reset(world.spawnX, world.spawnY, world.spawnHeading)
        val auto = Autopilot(world.route, world.ground)
        val tracker = RouteTracker(world.route)
        val assists = Assists()
        System.getenv("NWADV_DIFFICULTY")?.let { assists.applyDifficulty(com.northwoodsadv.ride.physics.Difficulty.valueOf(it)) }
        val input = RiderInput()
        val dt = 1 / 60.0
        var t = 0.0; var crashes = 0; var crashTimer = 0.0
        var safeX = bike.x; var safeY = bike.y; var safeH = bike.yaw; var safeTimer = 0.0
        val surfaces = HashMap<Surface, Double>()
        val maxT = (System.getenv("NWADV_RIDE_SECONDS") ?: "1800").toDouble()
        val t1 = System.nanoTime()
        var nextLog = 0.0
        while (t < maxT && !tracker.finished) {
            auto.drive(bike, assists, input)
            bike.update(dt, input, assists)
            tracker.update(bike.x, bike.y, dt)
            surfaces.merge(bike.surfaceF, dt, Double::plus)
            if (bike.crash != null) {
                crashTimer += dt
                if (crashTimer > 2) {
                    crashes++
                    println("  crash #%d at %.0f m: %s (%s)".format(crashes, tracker.along, bike.crash, bike.surfaceF))
                    val p = DoubleArray(3); val q = DoubleArray(3)
                    world.route.pointAt((tracker.along - 12).coerceAtLeast(0.0), p); world.route.pointAt((tracker.along - 6).coerceAtLeast(0.0), q)
                    if (tracker.offRoute < 60) bike.reset(p[0], p[1], kotlin.math.atan2(q[1] - p[1], q[0] - p[0])) else bike.reset(safeX, safeY, safeH)
                    crashTimer = 0.0
                }
            } else {
                safeTimer += dt
                if (safeTimer > 3 && kotlin.math.abs(bike.lean) < 0.4 && bike.speed > 2) {
                    safeTimer = 0.0; safeX = bike.x; safeY = bike.y; safeH = bike.yaw
                }
            }
            val dbgR = System.getenv("NWADV_DEBUG")?.split(",")?.map { it.toDouble() }
            if (dbgR != null && t >= dbgR[0] && t < dbgR[1] && (t * 60).toInt() % 6 == 0) {
                println("    dbg t=%.2f u=%.2f thr=%.2f fb=%.2f in=%.2f lean=%.3f steer=%.3f yaw=%.2f r=%.2f lat=%.2f fzF=%.0f fzR=%.0f sF=%.2f sR=%.2f pitch=%.3f g=%d rpm=%.0f tc=%.2f cF=%.2f cR=%.2f kR=%.2f %s %s".format(
                    t, bike.forwardSpeed, input.throttle, input.frontBrake, input.steer, bike.lean, bike.steer, bike.yaw, bike.yawRate, bike.sampleF.lateral, bike.fzF, bike.fzR, bike.slipF, bike.slipR, bike.pitch, bike.gear, bike.rpm, bike.tcFactor, bike.compF, bike.compR, bike.kappaR, bike.surfaceF, bike.crash ?: ""))
            }
            if (t >= nextLog) {
                nextLog += 60
                println("  t=%4.0fs along=%6.0f/%6.0f m  speed=%5.1f km/h target=%5.1f  surface=%s off=%.0f".format(
                    t, tracker.along, world.route.length, bike.forwardSpeed * 3.6, auto.targetSpeed * 3.6, bike.surfaceF.label, tracker.offRoute))
            }
            t += dt
        }
        if (System.getenv("NWADV_PROFILE_END") != null) {
            val cy = kotlin.math.cos(bike.yaw); val sy = kotlin.math.sin(bike.yaw)
            val gsx = GroundSample()
            for (k in -20..20) {
                val d = k * 0.1
                world.ground.sample(bike.x + cy * d, bike.y + sy * d, gsx)
                println("  ahead %.1f m: h=%.3f %s lat=%.2f".format(d, gsx.height, gsx.surface, gsx.lateral))
            }
        }
        val wall = (System.nanoTime() - t1) / 1e9
        println("rode %.0f of %.0f m in %.0f s sim (%.1f s wall, %.2f ms/frame), crashes=%d, avg %.1f km/h".format(
            tracker.along, world.route.length, t, wall, wall / (t / dt) * 1000, crashes, tracker.along / t * 3.6))
        println("surfaces: " + surfaces.entries.sortedByDescending { it.value }.joinToString { "%s %.0fs".format(it.key.label, it.value) })
        assertTrue(tracker.along > world.route.length * 0.5 || t >= maxT, "made progress")
    }
}
