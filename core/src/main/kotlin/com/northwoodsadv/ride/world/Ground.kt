package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.terrain.Elevation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** What is underfoot at one point. */
class GroundSample {
    var height = 0.0
    var surface = Surface.FOREST
    /** Index into [Ground.roads] of the road this point belongs to, or -1. */
    var road = -1
    var onRoad = false
    var lateral = 0.0
    var along = 0.0
    var roadHeading = 0.0
    /** 0..1: how much the road grading has replaced the natural terrain here. */
    var roadBlend = 0.0
    var waterDepth = 0.0
    var landcover = Landcover.FOREST
    /** Grass growing between the ruts (render hint). */
    var grassStrip = false
    /** Standing water in a mud hole (render hint). */
    var puddle = false
    /** 0..1: on the exposed face of a road cut (render hint). */
    var cutFace = 0.0
    /** Height of the water surface here (streams, fords, lakes), or NaN when dry. */
    var waterLevel = Double.NaN
}

class Stream(val line: Polyline, val width: Double, val river: Boolean)

/**
 * The rideable world surface: real terrain (DEM) with roads graded into it, ditches, streams,
 * lakes and, close up, the small stuff that makes riding interesting: ruts, rocks, ledge,
 * water bars, washouts and mud holes on Class VI roads; frost heaves and potholes on old
 * pavement; washboard on gravel.
 *
 * All small-scale features are a pure function of position, so physics and rendering always
 * agree and the same road is the same every ride.
 */
class Ground(
    val elevation: Elevation,
    val roads: List<Road>,
    val streams: List<Stream> = emptyList(),
    val landcover: Landcover = Landcover(emptyList()),
) {
    private val roadIndex = SegmentIndex(roads.map { it.line }, DoubleArray(roads.size) { influence(roads[it]) })
    private val streamIndex = SegmentIndex(streams.map { it.line }, DoubleArray(streams.size) { streams[it].width / 2 + BANK })
    private val hitsTL = ThreadLocal.withInitial { Array(16) { SegmentIndex.Hit() } }
    private val maxInfluence = roads.maxOfOrNull { influence(it) } ?: 1.0

    fun shoulder(r: Road) = when (r.spec.kind) {
        RoadKind.HIGHWAY -> 2.5; RoadKind.MAJOR -> 1.6; RoadKind.MINOR -> 1.0; RoadKind.SERVICE -> 0.5
        RoadKind.GRAVEL -> 0.8; RoadKind.CLASS_VI -> 0.4; RoadKind.TRAIL -> 0.2
    }

    private fun influence(r: Road) = r.halfWidth + shoulder(r) + EARTHWORK + 0.5

    /** Distance from (e, n) to the edge of the nearest road (negative = on the road). */
    fun roadClearance(e: Double, n: Double): Double {
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxOf(40.0, maxInfluence), hits)
        var best = 40.0
        for (k in 0 until cnt) best = minOf(best, hits[k].dist - roads[hits[k].line].halfWidth)
        return best
    }

    /**
     * Nearest road to (e, n) within [maxDist]: writes (clearance from the road edge, road heading)
     * into [out] and returns true, or false if there is none.
     */
    fun nearestRoad(e: Double, n: Double, maxDist: Double, out: DoubleArray): Boolean {
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxOf(maxDist, maxInfluence), hits)
        var best = -1; var bestD = Double.MAX_VALUE
        for (k in 0 until cnt) {
            val d = hits[k].dist - roads[hits[k].line].halfWidth
            if (hits[k].dist <= maxDist && d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        out[0] = bestD; out[1] = hits[best].heading
        return true
    }

    /** Nearest road hit to (e, n) within [maxDist], or false. */
    fun nearestRoadHit(e: Double, n: Double, maxDist: Double, out: SegmentIndex.Hit): Boolean {
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxOf(maxDist, maxInfluence), hits)
        var best = -1; var bestD = Double.MAX_VALUE
        for (k in 0 until cnt) {
            val d = hits[k].dist - roads[hits[k].line].halfWidth
            if (hits[k].dist <= maxDist && d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        val h = hits[best]
        out.line = h.line; out.along = h.along; out.lateral = h.lateral; out.dist = h.dist; out.heading = h.heading
        return true
    }

    fun streamClearance(e: Double, n: Double): Double {
        val hits = hitsTL.get()
        val cnt = streamIndex.nearestPerLine(e, n, 30.0, hits)
        var best = 30.0
        for (k in 0 until cnt) best = minOf(best, hits[k].dist - streams[hits[k].line].width / 2)
        return best
    }

    fun height(e: Double, n: Double, micro: Boolean = true): Double {
        val s = scratch.get(); sample(e, n, s, micro); return s.height
    }

    private val scratch = ThreadLocal.withInitial { GroundSample() }

    /**
     * Fills [out] for the point (e, n). With [micro] false only the large-scale shape is computed
     * (used for distant terrain meshes). [obstacles] false leaves out logs and big rocks (the
     * renderer draws those as their own meshes).
     */
    fun sample(e: Double, n: Double, out: GroundSample, micro: Boolean = true, obstacles: Boolean = true): GroundSample {
        val terrain = elevation.height(e, n)
        out.height = terrain; out.road = -1; out.onRoad = false; out.roadBlend = 0.0
        out.waterDepth = 0.0; out.grassStrip = false; out.puddle = false; out.cutFace = 0.0
        out.waterLevel = Double.NaN
        out.landcover = landcover.at(e, n)

        // --- road grading --------------------------------------------------------------
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxInfluence, hits)
        var sumW = 0.0; var sumH = 0.0; var maxW = 0.0; var best = -1; var bestD = Double.MAX_VALUE
        var cut = 0.0; var rock = 0.0
        for (k in 0 until cnt) {
            val h = hits[k]; val r = roads[h.line]
            val flat = r.halfWidth + shoulder(r)
            if (h.dist > flat + EARTHWORK) continue
            val bank = r.bankAt(h.along)
            // A banked bend is one tilted plane; the crown only shows on the straights.
            val crownK = 1 - minOf(1.0, abs(bank) / 0.03)
            val crown = (if (r.spec.surface.isPaved) -0.02 else -0.035) * minOf(h.dist, r.halfWidth) * crownK
            val roadH = r.heightAt(h.along) + crown - bank * h.lateral.coerceIn(-flat, flat)
            // Earthwork: a straight bank from the shoulder at the cut or fill angle until it
            // meets the natural ground (blending linearly over that width gives that slope).
            val dh = terrain - roadH
            val slope = if (dh > 0) r.cutSlope(h.along) else r.fillSlope
            val width = (abs(dh) / slope).coerceIn(MIN_BANK, EARTHWORK)
            val w = 1 - ((h.dist - flat) / width).coerceIn(0.0, 1.0)
            if (w <= 0) continue
            // Each road's own earthwork surface; where several overlap the most "inside" one wins
            // (sharply weighted), so the other leg of a hairpin metres above or below can't
            // bleed into this road's surface. Roads meeting at a junction (both flat) average.
            val ew = terrain + (roadH - terrain) * w
            val v = (w * w).let { it * it }.let { it * it }
            sumW += v; sumH += v * ew
            if (w > maxW) maxW = w
            if (dh > 0.3 && w < 1 && w > 0) {
                val c = Noise.smoothstep(0.3, 1.2, dh) * Noise.smoothstep(0.0, 0.15, w) * Noise.smoothstep(1.0, 0.9, w)
                if (c > cut) { cut = c; rock = r.rockCut(h.along) }
            }
            if (h.dist - r.halfWidth < bestD) { bestD = h.dist - r.halfWidth; best = k }
        }
        out.cutFace = cut
        var bestRoad: Road? = null
        if (sumW > 0) {
            out.height = sumH / sumW
            out.roadBlend = maxW
            val h = hits[best]; bestRoad = roads[h.line]
            out.road = h.line; out.lateral = h.lateral; out.along = h.along; out.roadHeading = h.heading
            val r = bestRoad
            val ad = h.dist
            // Roadside ditch (maintained roads only).
            if (!r.spec.isClassVI && !r.spec.bridge && terrain > out.height - 0.3) {
                val c = r.halfWidth + shoulder(r) + 1.1
                val depth = if (r.spec.kind == RoadKind.HIGHWAY) 0.7 else 0.45
                out.height -= depth * gauss((ad - c) / 0.8)
            }
            when {
                ad <= r.halfWidth + 0.15 -> { out.onRoad = true; out.surface = r.spec.surface }
                ad <= r.halfWidth + shoulder(r) -> out.surface = when {
                    r.spec.surface.isPaved -> Surface.GRAVEL_PACKED
                    r.spec.kind == RoadKind.GRAVEL -> Surface.GRAVEL_LOOSE
                    else -> Surface.DIRT
                }
                else -> out.surface = if (cut > 0.3) (if (rock > 0.5) Surface.ROCK else Surface.DIRT) else landSurface(out.landcover, terrain)
            }
            if (micro && ad <= r.halfWidth + shoulder(r)) RoadFeatures.apply(r, h.along, h.lateral, out)
            // Fallen trees and big rocks on Class VI (logs reach past the road edges).
            if (micro && obstacles && r.spec.isClassVI) out.height += TrailObstacles.bump(r, h.along, h.lateral, out)
        } else {
            out.surface = landSurface(out.landcover, terrain)
        }

        // --- streams ------------------------------------------------------------------
        if (streams.isNotEmpty()) {
            val sc = streamIndex.nearestPerLine(e, n, 30.0, hits)
            for (k in 0 until sc) {
                val st = streams[hits[k].line]
                val half = st.width / 2 + BANK
                if (hits[k].dist > half) continue
                val r = bestRoad
                if (r != null && out.roadBlend > 0.5 && (r.spec.bridge || !r.spec.isClassVI)) continue // bridge / culvert
                val ford = r != null && out.roadBlend > 0.5
                val depthMax = if (ford) 0.28 else if (st.river) 1.4 else 0.45
                val prof = 1 - (hits[k].dist / half).let { it * it }
                val d = depthMax * prof
                val before = out.height
                out.height -= d
                if (hits[k].dist < st.width / 2) {
                    // Flat across the channel: the bed at the centre plus its depth.
                    val level = before - depthMax * 0.2
                    if (out.waterLevel.isNaN() || level > out.waterLevel) out.waterLevel = level
                    out.waterDepth = maxOf(out.waterDepth, d * 0.8)
                    out.surface = if (!ford && st.river && d > 0.8) Surface.DEEP_WATER else Surface.WATER
                }
            }
        }
        if (out.landcover == Landcover.WATER && out.roadBlend < 0.5) {
            out.surface = Surface.DEEP_WATER; out.waterDepth = 1.5
            out.waterLevel = terrain + 0.05
        }

        // --- off-road micro relief -------------------------------------------------------
        if (micro) {
            val rough = out.surface.roughness
            if (rough > 0) {
                val nz = (Noise.value2(e * 0.9, n * 0.9, 101) - 0.5) * 2 * 0.7 +
                    (Noise.value2(e * 2.3, n * 2.3, 202) - 0.5) * 2 * 0.3
                out.height += rough * nz
            }
            if (out.roadBlend < 0.3 && out.surface == Surface.FOREST) out.height += Obstacles.boulderBump(this, e, n)
            out.height -= out.surface.sink
        }
        return out
    }

    fun landSurface(code: Byte, h: Double): Surface = when {
        code == Landcover.WATER -> Surface.DEEP_WATER
        code == Landcover.WETLAND -> Surface.MUD
        code == Landcover.ROCK -> Surface.ROCK
        code == Landcover.OPEN -> Surface.GRASS
        h > TREELINE -> if (Noise.value1(h * 0.13, 5) > 0.5) Surface.ROCK else Surface.GRASS
        else -> Surface.FOREST
    }

    companion object {
        const val BLEND = 5.0
        /** Widest cut or fill slope (m); beyond it a very deep cut just gets steeper. */
        const val EARTHWORK = 12.0
        /** Narrowest bank: even on flat ground the shoulder rolls off gently. */
        const val MIN_BANK = 1.5
        const val BANK = 1.5
        /** Approximate treeline in the White Mountains. */
        const val TREELINE = 1450.0
        fun gauss(x: Double) = exp(-x * x)
    }
}

/** Small-scale road features. See [Ground]. */
object RoadFeatures {
    private fun gauss(x: Double) = exp(-x * x)

    fun apply(r: Road, s: Double, lat: Double, out: GroundSample) {
        val spec = r.spec
        val rough = spec.roughness
        val seed = r.seed
        val half = r.halfWidth
        if (spec.isClassVI || rough >= 0.3) {
            // Ends of a road (junctions, trailheads) are where it gets graded: fade features in.
            val k = Noise.smoothstep(5.0, 40.0, s) * Noise.smoothstep(5.0, 40.0, r.line.length - s)
            if (k <= 0) return
            val h0 = out.height; val s0 = out.surface
            unmaintained(r, s, lat, out, rough, seed, half)
            out.height = h0 + (out.height - h0) * k
            if (k < 0.5) { out.surface = s0; out.puddle = false; out.waterDepth = 0.0 }
        } else if (spec.surface.isPaved) {
            // Frost heaves: a fact of life on New Hampshire back roads.
            if (rough >= 0.08) {
                val k = floor(s / 70).toInt()
                val sk = (k + 0.2 + 0.6 * Noise.rand(k, 11, seed)) * 70
                if (Noise.rand(k, 12, seed) < rough * 3.5) out.height += (0.025 + 0.05 * Noise.rand(k, 13, seed)) * gauss((s - sk) / 1.5)
            }
            if (spec.surface == Surface.ASPHALT_WORN || rough > 0.15) pothole(s, lat, out, seed, 0.04 * rough + 0.01, 0.07)
        } else {
            // Gravel roads: crown, washboard and loose gravel windrows at the edges / centre.
            val wb = Noise.smoothstep(0.55, 0.8, Noise.value1(s / 60, seed + 12)) * 0.006 * (0.5 + rough)
            out.height += wb * sin(s * 2 * PI / 0.75)
            pothole(s, lat, out, seed, 0.03 * rough + 0.004, 0.09)
            if (abs(lat) > half - 0.7 || abs(lat) < 0.35) out.surface = Surface.GRAVEL_LOOSE
        }
    }

    private fun pothole(s: Double, lat: Double, out: GroundSample, seed: Int, density: Double, depth: Double) {
        val ci = floor(s / 2.5).toInt(); val cj = floor(lat / 1.5).toInt()
        for (di in -1..1) for (dj in -1..1) {
            val i = ci + di; val j = cj + dj
            if (Noise.rand(i, j, seed + 40) >= density) continue
            val px = (i + Noise.rand(i, j, seed + 41)) * 2.5; val py = (j + Noise.rand(i, j, seed + 42)) * 1.5
            val rad = 0.3 + 0.35 * Noise.rand(i, j, seed + 43)
            val d2 = ((s - px) * (s - px) + (lat - py) * (lat - py)) / (rad * rad)
            if (d2 < 1) out.height -= depth * (1 - d2 * d2)
        }
    }

    private fun unmaintained(r: Road, s: Double, lat: Double, out: GroundSample, rough: Double, seed: Int, half: Double) {
        val grade = r.gradeAt(s)
        val ag = abs(grade)
        val wet = Noise.value1(s / 70, seed + 30) > 0.55

        // Two worn tyre tracks with a grassy crown between them.
        if (rough > 0.25 && half > 1.1) {
            val wander = (Noise.value1(s / 18, seed) - 0.5) * 0.5
            val depth = (0.03 + 0.16 * rough) * (0.5 + Noise.value1(s / 27, seed + 1))
            val x = lat - wander
            out.height -= depth * (gauss((x - 0.78) / 0.22) + gauss((x + 0.78) / 0.22))
            if (rough > 0.4 && abs(x) < 0.4) { out.height += 0.04 * rough; out.grassStrip = true }
        }

        // Steep pitches wash out: a gully wandering down the road, with loose rock in it.
        if (ag > 0.07 && rough > 0.45) {
            val k = Noise.smoothstep(0.07, 0.16, ag) * rough
            val c = (Noise.value1(s / 22, seed + 2) - 0.5) * 1.2 * half
            val gw = 0.35 + 0.3 * Noise.value1(s / 9, seed + 3)
            val g = gauss((lat - c) / gw)
            out.height -= 0.45 * k * g
            if (g > 0.35) out.surface = if (Noise.rand(floor(s).toInt(), 0, seed + 31) < 0.4) Surface.ROCK else Surface.GRAVEL_LOOSE
        }
        if (ag > 0.10 && rough > 0.4 && out.surface == Surface.DIRT) out.surface = Surface.GRAVEL_LOOSE

        // Exposed ledge, often wet from seeps.
        if (rough > 0.55 && Noise.value1(s / 45, seed + 8) > 0.68) {
            out.surface = if (wet) Surface.WET_ROCK else Surface.ROCK
            val k = floor(s / 3.2).toInt()
            val u = s - (k + 0.2 * Noise.rand(k, 1, seed + 9)) * 3.2 - lat * 0.25
            val len = 1.2 + 1.6 * Noise.rand(k, 2, seed + 9)
            val hgt = 0.04 + 0.11 * Noise.rand(k, 3, seed + 9) * rough
            // Slabs with weathered, ramped edges rather than square steps.
            out.height += hgt * Noise.smoothstep(0.0, 0.35, u) * (1 - Noise.smoothstep(len - 0.35, len, u))
        }

        // Embedded rocks ("baby heads").
        if (rough > 0.3) {
            val ci = floor(s / 1.3).toInt(); val cj = floor(lat / 0.9).toInt()
            val density = 0.4 * (rough - 0.3) / 0.7 + if (ag > 0.1) 0.06 else 0.0
            var bump = 0.0
            for (di in -1..1) for (dj in -1..1) {
                val i = ci + di; val j = cj + dj
                if (Noise.rand(i, j, seed + 4) >= density) continue
                val rx = (i + Noise.rand(i, j, seed + 5)) * 1.3; val ry = (j + Noise.rand(i, j, seed + 6)) * 0.9
                val rad = 0.10 + 0.24 * rough * Noise.rand(i, j, seed + 7) * Noise.rand(i, j, seed + 8)
                val d2 = ((s - rx) * (s - rx) + (lat - ry) * (lat - ry)) / (rad * rad)
                if (d2 < 1) {
                    val b = rad * 0.75 * (1 - d2).let { it * sqrt(it) }
                    if (b > bump) bump = b
                    if (d2 < 0.6) out.surface = if (wet) Surface.WET_ROCK else Surface.ROCK
                }
            }
            out.height += bump
        }

        // Mud holes in flat, low spots.
        val curvature = r.heightAt(s + 10) + r.heightAt(s - 10) - 2 * r.heightAt(s)
        val wetSpot = Noise.value1(s / 32, seed + 10) * (0.4 + rough * 0.6) +
            0.5 * Noise.smoothstep(0.05, 0.4, curvature) + 0.15 * (1 - Noise.smoothstep(0.02, 0.06, ag)) - 0.78
        // Water drains off steep pitches, so mud only collects on gentle grades.
        if (wetSpot > 0 && ag < 0.10) {
            val m = minOf(1.0, wetSpot / 0.2) * (1 - Noise.smoothstep(0.06, 0.10, ag))
            val lt = (lat / (half + 0.3)).coerceIn(-1.0, 1.0)
            val depth = 0.14 * m * (1 - lt * lt)
            out.height -= depth
            out.surface = Surface.MUD
            if (m > 0.6 && abs(lat) < half * 0.75) { out.puddle = true; out.waterDepth = depth * 0.6 }
        }

        // Water bars: diagonal berms built to shed runoff off the road.
        if (ag > 0.035 && rough > 0.3 && wetSpot <= -0.05) {
            val spacing = 40.0
            val k = floor(s / spacing).toInt()
            val sk = (k + 0.3 + 0.4 * Noise.rand(k, 9, seed)) * spacing
            val u = s - sk - lat * 0.55
            val up = if (grade > 0) 1.0 else -1.0
            out.height += 0.2 * (0.4 + rough) * gauss(u / 0.6) - 0.12 * gauss((u - up * 0.9) / 0.45)
        }
    }
}
