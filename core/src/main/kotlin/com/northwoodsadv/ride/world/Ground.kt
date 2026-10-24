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
    private val hitsTL = ThreadLocal.withInitial { Array(8) { SegmentIndex.Hit() } }
    private val maxInfluence = roads.maxOfOrNull { influence(it) } ?: 1.0

    fun shoulder(r: Road) = when (r.spec.kind) {
        RoadKind.HIGHWAY -> 2.5; RoadKind.MAJOR -> 1.6; RoadKind.MINOR -> 1.0; RoadKind.SERVICE -> 0.5
        RoadKind.GRAVEL -> 0.8; RoadKind.CLASS_VI -> 0.4; RoadKind.TRAIL -> 0.2
    }

    private fun influence(r: Road) = r.halfWidth + shoulder(r) + BLEND + 0.5

    /** Distance from (e, n) to the edge of the nearest road (negative = on the road). */
    fun roadClearance(e: Double, n: Double): Double {
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxOf(40.0, maxInfluence), hits)
        var best = 40.0
        for (k in 0 until cnt) best = minOf(best, hits[k].dist - roads[hits[k].line].halfWidth)
        return best
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
     * (used for distant terrain meshes).
     */
    fun sample(e: Double, n: Double, out: GroundSample, micro: Boolean = true): GroundSample {
        val terrain = elevation.height(e, n)
        out.height = terrain; out.road = -1; out.onRoad = false; out.roadBlend = 0.0
        out.waterDepth = 0.0; out.grassStrip = false; out.puddle = false
        out.landcover = landcover.at(e, n)

        // --- road grading --------------------------------------------------------------
        val hits = hitsTL.get()
        val cnt = roadIndex.nearestPerLine(e, n, maxInfluence, hits)
        var sumW = 0.0; var sumH = 0.0; var maxW = 0.0; var best = -1; var bestD = Double.MAX_VALUE
        for (k in 0 until cnt) {
            val h = hits[k]; val r = roads[h.line]
            val flat = r.halfWidth + shoulder(r)
            if (h.dist > flat + BLEND) continue
            val w = 1 - Noise.smoothstep(flat, flat + BLEND, h.dist)
            val crown = if (r.spec.surface.isPaved) -0.02 * minOf(h.dist, r.halfWidth) else -0.035 * minOf(h.dist, r.halfWidth)
            sumW += w; sumH += w * (r.heightAt(h.along) + crown)
            if (w > maxW) maxW = w
            if (h.dist - r.halfWidth < bestD) { bestD = h.dist - r.halfWidth; best = k }
        }
        var bestRoad: Road? = null
        if (sumW > 0) {
            out.height = terrain * (1 - maxW) + (sumH / sumW) * maxW
            out.roadBlend = maxW
            val h = hits[best]; bestRoad = roads[h.line]
            out.road = h.line; out.lateral = h.lateral; out.along = h.along; out.roadHeading = h.heading
            val r = bestRoad
            val ad = h.dist
            // Roadside ditch (maintained roads only).
            if (!r.spec.isClassVI && !r.spec.bridge) {
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
                else -> out.surface = landSurface(out.landcover, terrain)
            }
            if (micro && ad <= r.halfWidth + shoulder(r)) RoadFeatures.apply(r, h.along, h.lateral, out)
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
                out.height -= d
                if (hits[k].dist < st.width / 2) {
                    out.waterDepth = maxOf(out.waterDepth, d * 0.8)
                    out.surface = if (!ford && st.river && d > 0.8) Surface.DEEP_WATER else Surface.WATER
                }
            }
        }
        if (out.landcover == Landcover.WATER && out.roadBlend < 0.5) {
            out.surface = Surface.DEEP_WATER; out.waterDepth = 1.5
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
            unmaintained(r, s, lat, out, rough, seed, half)
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
            val hgt = 0.06 + 0.16 * Noise.rand(k, 3, seed + 9) * rough
            out.height += hgt * Noise.smoothstep(0.0, 0.1, u) * (1 - Noise.smoothstep(len - 0.25, len, u))
        }

        // Embedded rocks ("baby heads").
        if (rough > 0.3) {
            val ci = floor(s / 1.3).toInt(); val cj = floor(lat / 0.9).toInt()
            val density = 0.55 * (rough - 0.3) / 0.7 + if (ag > 0.1) 0.1 else 0.0
            var bump = 0.0
            for (di in -1..1) for (dj in -1..1) {
                val i = ci + di; val j = cj + dj
                if (Noise.rand(i, j, seed + 4) >= density) continue
                val rx = (i + Noise.rand(i, j, seed + 5)) * 1.3; val ry = (j + Noise.rand(i, j, seed + 6)) * 0.9
                val rad = 0.12 + 0.30 * rough * Noise.rand(i, j, seed + 7)
                val d2 = ((s - rx) * (s - rx) + (lat - ry) * (lat - ry)) / (rad * rad)
                if (d2 < 1) {
                    val b = rad * 0.75 * (1 - d2).let { it * sqrt(it) }
                    if (b > bump) bump = b
                    if (d2 < 0.6) out.surface = if (wet) Surface.WET_ROCK else Surface.ROCK
                }
            }
            out.height += bump
        }

        // Water bars: diagonal berms built to shed runoff off the road.
        if (ag > 0.035 && rough > 0.3) {
            val spacing = 40.0
            val k = floor(s / spacing).toInt()
            val sk = (k + 0.3 + 0.4 * Noise.rand(k, 9, seed)) * spacing
            val u = s - sk - lat * 0.55
            val up = if (grade > 0) 1.0 else -1.0
            out.height += 0.28 * (0.5 + rough) * gauss(u / 0.55) - 0.18 * gauss((u - up * 0.9) / 0.45)
        }

        // Mud holes in flat, low spots.
        val curvature = r.heightAt(s + 10) + r.heightAt(s - 10) - 2 * r.heightAt(s)
        val wetSpot = Noise.value1(s / 32, seed + 10) * (0.4 + rough * 0.6) +
            0.5 * Noise.smoothstep(0.05, 0.4, curvature) + 0.15 * (1 - Noise.smoothstep(0.02, 0.06, ag)) - 0.78
        if (wetSpot > 0) {
            val m = minOf(1.0, wetSpot / 0.2)
            val lt = (lat / (half + 0.3)).coerceIn(-1.0, 1.0)
            val depth = 0.3 * m * (1 - lt * lt)
            out.height -= depth
            out.surface = Surface.MUD
            if (m > 0.6 && abs(lat) < half * 0.75) { out.puddle = true; out.waterDepth = depth * 0.6 }
        }
    }
}
