package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * A "hero section": a stretch of a Class VI road where it gets properly hard. The road drops
 * steeply into a hollow over rock steps and climbs out the other side, often with a long ford
 * along the bottom; oversized boulders crowd the line, and the rock is wet and the mud deeper.
 *
 * Positions are along the road ([s0]..[s1]); everything is a pure function of the road, so
 * physics, rendering and the tracker agree and the same road has the same sections every ride.
 */
class HeroSection(
    /** Which slot of its road (stable id together with the road). */
    val slot: Int,
    val s0: Double, val s1: Double,
    /** The flat bottom of the hollow. */
    val bottomA: Double, val bottomB: Double,
    /** Length of the steep pitch either side of the bottom. */
    val ramp: Double,
    /** How far below the road's own grade the bottom lies (before limiting the pitch). */
    val depth: Double,
    /** Along-road extent of the ford across the bottom, or null for a dry hollow. */
    val waterA: Double, val waterB: Double,
) {
    val length get() = s1 - s0
    val hasWater get() = waterB > waterA
    val dipStart get() = bottomA - ramp
    val dipEnd get() = bottomB + ramp

    operator fun contains(s: Double) = s in s0..s1

    /** 0..1: how hard the trail is here, fading in and out over the section's first and last 15 m. */
    fun intensity(s: Double): Double =
        Noise.smoothstep(s0, s0 + 15, s) * (1 - Noise.smoothstep(s1 - 15, s1, s))

    /** 0..1 shape of the hollow: 0 outside, 1 on the bottom, a half cosine down each pitch. */
    fun dipShape(s: Double): Double = when {
        s <= dipStart || s >= dipEnd -> 0.0
        s < bottomA -> (1 - cos(PI * (s - dipStart) / ramp)) / 2
        s <= bottomB -> 1.0
        else -> (1 + cos(PI * (s - bottomB) / ramp)) / 2
    }

    /** On one of the steep pitches in or out of the hollow. */
    fun onPitch(s: Double) = (s > dipStart && s < bottomA) || (s > bottomB && s < dipEnd)

    fun inWater(s: Double) = hasWater && s in waterA..waterB
}

object HeroSections {
    /** About one chance at a section per this many metres of Class VI road. */
    const val SLOT = 1100.0
    const val CHANCE = 0.55
    /** Sections keep clear of the road's ends (junctions, trailheads). */
    const val MARGIN = 60.0
    /** Steepest the pitch in or out may get, natural grade included. */
    const val MAX_GRADE = 0.30

    /** Wider than any real Class VI road (test pads): no sections. */
    const val MAX_WIDTH = 8.0

    /** Spacing of the oversized boulders through a section. */
    const val ROCK_SLOT = 13.0

    fun place(spec: RoadSpec, length: Double, seed: Int): List<HeroSection> {
        if (spec.kind != RoadKind.CLASS_VI || spec.width > MAX_WIDTH || spec.bridge || spec.tunnel || length < 2 * MARGIN + 200) return emptyList()
        val hs = seed + 4242
        val n = maxOf(1, Math.round(length / SLOT).toInt())
        val span = length / n
        val out = ArrayList<HeroSection>()
        for (k in 0 until n) {
            if (Noise.rand(k, 0, hs) > CHANCE) continue
            val len = 190.0 + 130.0 * Noise.rand(k, 1, hs)
            val lo = k * span + MARGIN; val hi = (k + 1) * span - MARGIN - len
            if (hi < lo && !(n == 1 && length - 2 * MARGIN >= len)) continue
            val s0 = if (hi >= lo) lo + (hi - lo) * Noise.rand(k, 2, hs) else (length - len) / 2
            val s1 = s0 + len
            val water = Noise.rand(k, 3, hs) < 0.65
            val ramp = 24.0 + 10.0 * Noise.rand(k, 4, hs)
            val bottom = if (water) 16.0 + 22.0 * Noise.rand(k, 5, hs) else 4.0 + 6.0 * Noise.rand(k, 5, hs)
            val dip = 2 * ramp + bottom
            // The hollow sits somewhere in the middle, leaving rough trail before and after it.
            val room = len - 2 * 25.0 - dip
            if (room < 0) continue
            val a = s0 + 25.0 + room * (0.25 + 0.5 * Noise.rand(k, 6, hs)) + ramp
            val depth = 2.6 + 1.6 * Noise.rand(k, 7, hs)
            out += HeroSection(k, s0, s1, a, a + bottom, ramp, depth,
                if (water) a + 1.5 else 0.0, if (water) a + bottom - 1.5 else 0.0)
        }
        return out
    }

    /** A boulder in a section, in road coordinates. */
    class Rock {
        var s = 0.0; var lat = 0.0; var r = 0.0; var h = 0.0
    }

    /**
     * The oversized boulder in slot [j] (every [ROCK_SLOT] m along [road]), if there is one: they
     * crowd alternate sides of the trail, leaving a line between them, and keep out of the ford
     * and the first and last stretch of the section.
     */
    fun rock(road: Road, j: Int, o: Rock): Boolean {
        if (road.heroes.isEmpty() || j < 0) return false
        val seed = road.seed + 5151
        val s = (j + 0.25 + 0.5 * Noise.rand(j, 0, seed)) * ROCK_SLOT
        val h = road.heroAt(s) ?: return false
        if (s < h.s0 + 20 || s > h.s1 - 20 || (h.hasWater && s > h.waterA - 3 && s < h.waterB + 3)) return false
        if (Noise.rand(j, 1, seed) > 0.8) return false
        o.s = s
        o.r = 0.62 + 0.4 * Noise.rand(j, 2, seed)
        o.h = o.r * (0.62 + 0.25 * Noise.rand(j, 3, seed))
        val side = if ((j % 2 == 0) != (Noise.rand(j, 4, seed) < 0.2)) 1.0 else -1.0
        o.lat = side * (road.halfWidth * 0.5 + o.r * 0.4)
        return true
    }

    /** Slots of [rock] that can fall inside the sections of [road]. */
    fun rockSlots(road: Road): Sequence<Int> = road.heroes.asSequence().flatMap {
        (floor(it.s0 / ROCK_SLOT).toInt()..floor(it.s1 / ROCK_SLOT).toInt()).asSequence()
    }

    /**
     * Synthetic streams for the fords: a stretch of water running down the bottom of the hollow
     * (the road is the stream bed there), a little wider than the road.
     */
    fun streams(roads: List<Road>): List<Stream> {
        val out = ArrayList<Stream>()
        val p = DoubleArray(3)
        for (r in roads) for (h in r.heroes) {
            if (!h.hasWater) continue
            val n = maxOf(2, ((h.waterB - h.waterA) / 2.0).toInt() + 1)
            val xs = DoubleArray(n); val ys = DoubleArray(n)
            for (i in 0 until n) {
                r.line.pointAt(h.waterA + (h.waterB - h.waterA) * i / (n - 1), p)
                xs[i] = p[0]; ys[i] = p[1]
            }
            out += Stream(com.northwoodsadv.ride.geo.Polyline(xs, ys), r.spec.width + 1.6, false, FORD_DEPTH)
        }
        return out
    }

    /** Deepest water along a hero ford (m): over the axles, short of drowning the intake. */
    const val FORD_DEPTH = 0.42
}
