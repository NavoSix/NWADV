package com.northwoodsadv.ride.world

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fallen trees and big rocks lying on Class VI roads (never on maintained roads). Placed
 * deterministically along each road in 70 m slots, in road coordinates (distance along, lateral
 * offset), so physics and rendering always agree.
 *
 * - Logs lie across the road at up to ~26 degrees off square, 20-36 cm tall: rideable with
 *   momentum and a square approach, nasty at an angle.
 * - Rocks sit to one side, 0.45-0.75 m across and up to half a metre tall, leaving a line around.
 */
object TrailObstacles {
    const val SLOT = 70.0

    class Obstacle {
        var log = false
        var s = 0.0; var lat = 0.0
        /** Log angle from square-across (radians). */
        var angle = 0.0
        var length = 0.0
        /** Log radius, or rock radius. */
        var r = 0.0
        /** Rock height. */
        var h = 0.0
    }

    fun obstacle(road: Road, k: Int, o: Obstacle): Boolean {
        if (!road.spec.isClassVI || k < 0) return false
        val seed = road.seed + 977
        if (Noise.rand(k, 0, seed) > 0.22 + 0.3 * road.spec.roughness) return false
        o.s = (k + 0.2 + 0.6 * Noise.rand(k, 1, seed)) * SLOT
        if (o.s < 45 || o.s > road.line.length - 45) return false // junctions stay clear
        o.log = Noise.rand(k, 2, seed) < 0.55
        if (o.log) {
            o.angle = (Noise.rand(k, 3, seed) - 0.5) * 0.9
            o.r = 0.10 + 0.08 * Noise.rand(k, 4, seed)
            o.length = road.halfWidth * 2 + 2.0 + 3.0 * Noise.rand(k, 5, seed)
            o.lat = (Noise.rand(k, 6, seed) - 0.5) * 1.2
        } else {
            o.r = 0.45 + 0.3 * Noise.rand(k, 4, seed)
            o.h = o.r * (0.55 + 0.25 * Noise.rand(k, 5, seed))
            val side = if (Noise.rand(k, 6, seed) < 0.5) -1.0 else 1.0
            o.lat = side * (road.halfWidth * 0.5 + o.r * 0.5)
        }
        return true
    }

    private val tl = ThreadLocal.withInitial { Obstacle() }
    private val rockTl = ThreadLocal.withInitial { HeroSections.Rock() }

    /** Height added at (s, lat) on [road]; sets the surface when on top of an obstacle. */
    fun bump(road: Road, s: Double, lat: Double, out: GroundSample): Double {
        if (!road.spec.isClassVI) return 0.0
        val o = tl.get()
        val k0 = floor(s / SLOT).toInt()
        var best = 0.0
        for (k in k0 - 1..k0 + 1) {
            if (!obstacle(road, k, o)) continue
            val ds = s - o.s; val dl = lat - o.lat
            if (o.log) {
                val sa = sin(o.angle); val ca = cos(o.angle)
                val d = abs(ds * ca - dl * sa)          // distance from the log's axis
                val t = ds * sa + dl * ca               // position along the log
                if (d < o.r && abs(t) < o.length / 2) {
                    // Resting on the ground: top at 2r. Thinner toward the crown end.
                    val taper = 1.0 - 0.35 * ((t / (o.length / 2)) + 1) / 2
                    val r = o.r * taper
                    if (d < r) {
                        val hgt = r + sqrt(r * r - d * d)
                        if (hgt > best) { best = hgt; out.surface = Surface.LOG }
                    }
                }
            } else {
                val d2 = (ds * ds + dl * dl) / (o.r * o.r)
                if (d2 < 1) {
                    val hgt = o.h * sqrt(1 - d2)
                    if (hgt > best) { best = hgt; out.surface = Surface.ROCK }
                }
            }
        }
        if (road.heroes.isNotEmpty()) {
            val rk = rockTl.get()
            val j0 = floor(s / HeroSections.ROCK_SLOT).toInt()
            for (j in j0 - 1..j0 + 1) {
                if (!HeroSections.rock(road, j, rk)) continue
                val ds = s - rk.s; val dl = lat - rk.lat
                val d2 = (ds * ds + dl * dl) / (rk.r * rk.r)
                if (d2 < 1) {
                    val hgt = rk.h * sqrt(1 - d2)
                    if (hgt > best) { best = hgt; out.surface = Surface.ROCK }
                }
            }
        }
        return best
    }
}
