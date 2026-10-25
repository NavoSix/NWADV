package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.GroundSample
import com.northwoodsadv.ride.world.Noise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sensible rider that follows the route. Used for the "ride it for me" demo mode and to test
 * the physics on real routes: it only uses the same controls the player has.
 */
class Autopilot(private val route: Polyline, private val ground: Ground) {
    private var along = 0.0
    private val tmp = DoubleArray(3)
    private val tmp2 = DoubleArray(2)
    private val gs = GroundSample()
    var targetSpeed = 0.0; private set

    fun drive(bike: Motorcycle, assists: Assists, input: RiderInput) {
        route.project(bike.x, bike.y, along - 50, along + 150, tmp2)
        along = tmp2[0]
        val u = bike.forwardSpeed

        // Pure pursuit on the route.
        val look = (4.0 + 1.6 * u).coerceIn(7.0, 35.0)
        route.pointAt(along + look, tmp)
        val ang = wrap(atan2(tmp[1] - bike.y, tmp[0] - bike.x) - bike.yaw)
        val curvature = 2 * sin(ang) / look
        val maxLean = atan(0.6 * bike.sampleF.surface.mu)
        val lean = atan(u * u * curvature / Motorcycle.G).coerceIn(-maxLean, maxLean)
        // Same blend the bike uses: lean at speed, bars pointed directly at walking pace.
        val direct = -atan(curvature * bike.spec.wheelbase) / bike.spec.maxSteer
        val leaned = -lean / bike.leanAuthority(assists, u).coerceAtLeast(0.02)
        val w = 1 - Noise.smoothstep(Motorcycle.UPRIGHT_SPEED, Motorcycle.UPRIGHT_SPEED * 2, abs(u))
        input.steer = (leaned * (1 - w) + direct * w).coerceIn(-1.0, 1.0)

        // Speed: road type limit, then curvature and grip ahead with room to brake.
        var v = 30.0
        var d = 0.0
        while (d < 160) {
            val s = along + d
            route.pointAt(s, tmp)
            ground.sample(tmp[0], tmp[1], gs, false)
            val road = if (gs.road >= 0) ground.roads[gs.road] else null
            val mu = gs.surface.mu
            val kindMax = when (road?.spec?.kind) {
                RoadKind.HIGHWAY -> 27.0; RoadKind.MAJOR -> 23.0; RoadKind.MINOR -> 19.0; RoadKind.SERVICE -> 8.0
                RoadKind.GRAVEL -> 15.0; RoadKind.CLASS_VI -> 3.5 + 4.0 * (1 - road.spec.roughness); RoadKind.TRAIL -> 4.0
                null -> 6.0
            }
            val k = curvatureAt(s)
            val corner = if (k > 1e-4) sqrt(0.3 * mu * Motorcycle.G / k) else 99.0
            val limit = min(kindMax, corner)
            val brake = 0.25 * mu * Motorcycle.G
            v = min(v, sqrt(limit * limit + 2 * brake * d))
            d += 10
        }
        targetSpeed = max(v, 2.5)
        val err = targetSpeed - u
        input.throttle = (0.2 + err * 0.25).coerceIn(0.0, 1.0)
        input.frontBrake = if (err < -1.5) ((-err - 1.5) * 0.12).coerceIn(0.0, 0.7) else 0.0
        input.rearBrake = input.frontBrake * 0.5
        val rough = ground.roads.getOrNull(bike.sampleF.road)?.spec?.isClassVI == true || !bike.sampleF.onRoad
        input.standing = rough
    }

    private val pa = DoubleArray(3); private val pb = DoubleArray(3); private val pc = DoubleArray(3)

    /** Curvature (1/m) of the circle through route points 8 m either side of s. */
    private fun curvatureAt(s: Double): Double {
        route.pointAt(s - 8, pa); route.pointAt(s, pb); route.pointAt(s + 8, pc)
        val ax = pb[0] - pa[0]; val ay = pb[1] - pa[1]
        val bx = pc[0] - pb[0]; val by = pc[1] - pb[1]
        val cx = pc[0] - pa[0]; val cy = pc[1] - pa[1]
        val cross = abs(ax * by - ay * bx)
        val den = sqrt((ax * ax + ay * ay) * (bx * bx + by * by) * (cx * cx + cy * cy))
        return if (den < 1e-9) 0.0 else 2 * cross / den
    }

    private fun wrap(a: Double): Double { var d = a; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return d }

}
