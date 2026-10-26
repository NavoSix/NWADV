package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.geo.GpxWaypoint
import com.northwoodsadv.ride.geo.LocalProjection
import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.OsmData
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Places the [Scenery] along a route's roads, deterministically from the map data:
 *
 * - **Junctions** where the mapped roads meet, with stop signs on the lesser roads and green
 *   street-name blades, so the real intersections a route passes through read as themselves.
 * - **Buildings**: real OpenStreetMap footprints where the map has them (houses, barns, churches,
 *   stores), with lawns and driveways; where the map has no buildings, houses and farms are
 *   placed where the side roads say people live.
 * - **Roadside**: guard rails on high fills and bridges, stone walls and fences along fields
 *   and through the woods, speed limit, curve and moose signs.
 * - **Landmarks**: viewpoints (with the view cleared), named boulders and big glacial erratics,
 *   churches, monuments and historical markers, covered bridges, towers, and your GPX waypoints.
 *
 * Roadside cosmetics are kept off Class VI roads and trails: those stay wild.
 */
class SceneryBuilder(
    private val ground: Ground,
    private val osm: OsmData,
    private val proj: LocalProjection,
    private val route: Polyline,
    private val waypoints: List<GpxWaypoint>,
) {
    private val roads = ground.roads
    private val elevation = ground.elevation
    private val props = ArrayList<Prop>()
    private val runs = ArrayList<Run>()
    private val landmarks = ArrayList<Landmark>()
    private val junctions = ArrayList<Junction>()
    /** New land cover: lawns, fields, cleared views. */
    val clearings = ArrayList<Landcover.Area>()
    /** Driveways to the houses, as roads. */
    val driveways = ArrayList<Road>()
    private val occupied = HashSet<Long>()
    private val p = DoubleArray(3)
    private val hit = SegmentIndex.Hit()
    /** Coarse index reaching well away from the roads (the ground's own only covers the earthworks). */
    private val wide = SegmentIndex(roads.map { it.line.resample(15.0) }, DoubleArray(roads.size) { WIDE }, 60.0)
    private val wideHits = Array(24) { SegmentIndex.Hit() }

    /** Nearest road to (x, y) within [maxDist] (up to [WIDE]) into [hit]: by distance from its edge. */
    private fun nearestRoad(x: Double, y: Double, maxDist: Double): Boolean {
        val cnt = wide.nearestPerLine(x, y, min(maxDist, WIDE), wideHits)
        var best = -1; var bestD = Double.MAX_VALUE
        for (k in 0 until cnt) {
            val d = wideHits[k].dist - roads[wideHits[k].line].halfWidth
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        val h = wideHits[best]
        hit.line = h.line; hit.along = h.along.coerceIn(0.0, roads[h.line].line.length); hit.lateral = h.lateral; hit.dist = h.dist; hit.heading = h.heading
        return true
    }

    fun build(): Scenery {
        val near = BooleanArray(roads.size) { nearRoute(roads[it]) }
        findJunctions()
        gradeJunctions()
        junctionSigns()
        val realBuildings = osm.buildings.isNotEmpty()
        if (realBuildings) osmBuildings()
        landmarks()
        for ((ri, r) in roads.withIndex()) {
            if (!near[ri]) continue
            if (rank(r.spec) >= 1 && !r.spec.isClassVI) guardRails(ri, r)
            if (!cosmetic(r.spec)) continue
            if (!realBuildings) settlement(ri, r)
            fields(ri, r)
            woodsWalls(ri, r)
            signs(ri, r)
        }
        erratics()
        clearRoads()
        return Scenery(props, runs, landmarks, junctions)
    }

    /**
     * Last check: nothing solid may stand on any road (the map's footprints and the roads drawn
     * from it don't always agree), and no driveway may run through someone else's house.
     */
    private fun clearRoads() {
        props.removeAll { it.kind.solid && !footprintClear(it.x, it.y, it.hx, it.hz, it.heading, 0.4) }
        val solids = props.filter { it.kind.solid }
        val sc = Scenery(solids, emptyList(), emptyList(), emptyList())
        driveways.removeAll { d ->
            val keep = d.line.length - 5
            var blocked = false
            var s = 0.0
            while (s < keep && !blocked) { d.line.pointAt(s, p); if (sc.solidAt(p[0], p[1], 1.6) != null) blocked = true; s += 1.5 }
            // ... and none to a house that was taken away.
            d.line.pointAt(d.line.length, p)
            blocked || sc.solidAt(p[0], p[1], 6.0) == null
        }
        // Mailboxes only where a driveway still meets the road.
        val mouthsLeft = driveways.map { doubleArrayOf(it.line.xs[0], it.line.ys[0]) }
        props.removeAll { m -> m.kind == PropKind.MAILBOX && mouthsLeft.none { (it[0] - m.x) * (it[0] - m.x) + (it[1] - m.y) * (it[1] - m.y) < 64.0 } }
    }

    /** True if an oriented footprint keeps [margin] m from every road edge (edges sampled every ~2 m). */
    private fun footprintClear(x: Double, y: Double, hx: Double, hz: Double, heading: Double, margin: Double): Boolean {
        val c = cos(heading); val s = sin(heading)
        val nu = max(1, (hx / 1.0).toInt()); val nv = max(1, (hz / 1.0).toInt())
        for (i in -nu..nu) for (j in -nv..nv) {
            if (i != -nu && i != nu && j != -nv && j != nv && !(i == 0 && j == 0)) continue // edges and centre
            val u = hx * i / nu; val v = hz * j / nv
            if (ground.roadClearance(x + u * c - v * s, y + u * s + v * c) < margin) return false
        }
        return true
    }

    /**
     * Where roads meet, the lesser ones are graded to the main road's level (each road's profile
     * is smoothed on its own, so otherwise they'd meet with a step, a hump or a trench). Roads
     * that end on another are already joined by [Road.connectAll]; this catches crossings.
     */
    private fun gradeJunctions() {
        val o = DoubleArray(2)
        for (j in junctions) {
            val topRank = j.arms.maxOf { it.rank }
            val mains = j.arms.filter { it.rank == topRank }.map { roads[it.road] }.distinct()
            val mainName = mains.first().spec.name
            for (ri in j.arms.map { it.road }.distinct()) {
                val r = roads[ri]
                // The main road's own pieces carry straight through; only side roads are graded.
                if (r in mains && (r.spec.name == mainName || mains.size == 1)) continue
                val sr = projectS(r, j.x, j.y)
                r.line.pointAt(sr, p)
                // Its level where this road meets the main road's surface.
                var h = Double.NaN; var bestD = Double.MAX_VALUE
                for (m in mains) {
                    if (m === r) continue
                    m.line.project(p[0], p[1], 0.0, Double.MAX_VALUE, o)
                    if (o[1] < bestD) { bestD = o[1]; h = m.heightAt(o[0]) }
                }
                if (!h.isNaN()) r.pin(sr, h, 35.0)
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun rank(s: RoadSpec) = when (s.kind) {
        RoadKind.HIGHWAY -> 5; RoadKind.MAJOR -> 4; RoadKind.MINOR -> 3; RoadKind.GRAVEL -> 2
        RoadKind.SERVICE -> 1; RoadKind.CLASS_VI, RoadKind.TRAIL -> 0
    }

    /** Roads that get houses, fences, walls and signs: maintained public roads. */
    private fun cosmetic(s: RoadSpec) = !s.isClassVI && s.kind != RoadKind.SERVICE && !s.bridge && !s.tunnel

    private val routeTmp = DoubleArray(2)
    private fun routeDist(x: Double, y: Double): Double { route.project(x, y, 0.0, Double.MAX_VALUE, routeTmp); return routeTmp[1] }

    private fun nearRoute(r: Road): Boolean {
        val n = r.line.size
        for (k in listOf(0, n / 4, n / 2, 3 * n / 4, n - 1)) if (routeDist(r.line.xs[k], r.line.ys[k]) < 700) return true
        return false
    }

    private fun cellKey(x: Double, y: Double) = Scenery.key(floor(x / OCC).toInt(), floor(y / OCC).toInt())

    /** True if the oriented box is clear of everything placed so far. */
    private fun free(x: Double, y: Double, hx: Double, hz: Double, heading: Double): Boolean {
        var ok = true
        boxCells(x, y, hx, hz, heading) { if (it in occupied) ok = false }
        return ok
    }

    private fun claim(x: Double, y: Double, hx: Double, hz: Double, heading: Double) = boxCells(x, y, hx, hz, heading) { occupied += it }

    private inline fun boxCells(x: Double, y: Double, hx: Double, hz: Double, heading: Double, fn: (Long) -> Unit) {
        val c = cos(heading); val s = sin(heading)
        var u = -hx
        while (u <= hx + 1e-6) {
            var v = -hz
            while (v <= hz + 1e-6) { fn(cellKey(x + u * c - v * s, y + u * s + v * c)); v += OCC * 0.7 }
            u += OCC * 0.7
        }
    }

    /** Roadside point: [lat] m left of road [r] at [s] (negative = right); writes x, y, heading into [out]. */
    private fun at(r: Road, s: Double, lat: Double, out: DoubleArray): DoubleArray {
        r.line.pointAt(s, out)
        val lx = -sin(out[2]); val ly = cos(out[2])
        out[0] += lx * lat; out[1] += ly * lat
        return out
    }

    /** Is (x, y) beside road [ri] (and not nearer another road), with [clear] m to spare from every road edge? */
    private fun besideOwn(ri: Int, x: Double, y: Double, clear: Double): Boolean {
        if (!nearestRoad(x, y, WIDE) || hit.line != ri) return false
        return ground.roadClearance(x, y) >= clear
    }

    private fun flatEnough(x: Double, y: Double, hx: Double, hz: Double, heading: Double, maxDrop: Double): Boolean {
        val c = cos(heading); val s = sin(heading)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in 0 until 4) {
            val u = if (k % 2 == 0) hx else -hx; val v = if (k < 2) hz else -hz
            val h = elevation.height(x + u * c - v * s, y + u * s + v * c)
            lo = min(lo, h); hi = max(hi, h)
        }
        return hi - lo < maxDrop
    }

    private fun dryLand(x: Double, y: Double): Boolean {
        val lc = ground.landcover.at(x, y)
        return lc != Landcover.WATER && lc != Landcover.WETLAND && ground.streamClearance(x, y) > 6
    }

    /** Junctions and driveway mouths by 32 m cell, for the many "is there one nearby" checks. */
    private val junctionCells = HashMap<Long, MutableList<Junction>>()
    private val mouthCells = HashMap<Long, MutableList<DoubleArray>>()
    private fun c32(v: Double) = floor(v / 32).toInt()

    private fun nearJunction(x: Double, y: Double, d: Double): Boolean {
        for (cx in c32(x - d)..c32(x + d)) for (cy in c32(y - d)..c32(y + d))
            if (junctionCells[Scenery.key(cx, cy)]?.any { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < d * d } == true) return true
        return false
    }

    private fun nearMouth(x: Double, y: Double, d: Double): Boolean {
        for (cx in c32(x - d)..c32(x + d)) for (cy in c32(y - d)..c32(y + d))
            if (mouthCells[Scenery.key(cx, cy)]?.any { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) < d * d } == true) return true
        return false
    }

    /** An oriented rectangle as a land cover area. */
    private fun rectArea(code: Byte, x: Double, y: Double, hx: Double, hz: Double, heading: Double) {
        val c = cos(heading); val s = sin(heading)
        val xs = DoubleArray(5); val ys = DoubleArray(5)
        val us = doubleArrayOf(hx, -hx, -hx, hx, hx); val vs = doubleArrayOf(hz, hz, -hz, -hz, hz)
        for (k in 0 until 5) { xs[k] = x + us[k] * c - vs[k] * s; ys[k] = y + us[k] * s + vs[k] * c }
        clearings += Landcover.Area(code, listOf(xs to ys))
    }

    private fun angleDiff(a: Double, b: Double): Double { var d = b - a; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return d }

    // --- junctions ---------------------------------------------------------------------------

    private fun findJunctions() {
        // GPX stretches that cut a corner between mapped roads aren't roads of their own.
        val skip = BooleanArray(roads.size) { val r = roads[it]; r.spec.bridge || r.spec.tunnel || (r.id < 0 && (!r.spec.isClassVI || r.line.length < 150)) }
        val idx = SegmentIndex(roads.map { it.line }, DoubleArray(roads.size) { 10.0 }, 16.0)
        val hits = Array(16) { SegmentIndex.Hit() }
        // Points where one road touches another.
        val pts = ArrayList<DoubleArray>()
        for ((i, r) in roads.withIndex()) {
            if (skip[i]) continue
            val n = r.line.size
            for (k in 0 until n) {
                val x = r.line.xs[k]; val y = r.line.ys[k]
                val cnt = idx.nearestPerLine(x, y, 2.5, hits)
                for (q in 0 until cnt) {
                    val j = hits[q].line
                    if (j == i || skip[j]) continue
                    // Crossings show up as a run of vertices; endpoints are T-junctions.
                    pts += doubleArrayOf(x, y)
                }
            }
        }
        // Cluster within 14 m.
        val used = BooleanArray(pts.size)
        val grid = HashMap<Long, MutableList<Int>>()
        for ((k, q) in pts.withIndex()) grid.getOrPut(Scenery.key(floor(q[0] / 14).toInt(), floor(q[1] / 14).toInt())) { ArrayList() } += k
        for (k in pts.indices) {
            if (used[k]) continue
            var sx = 0.0; var sy = 0.0; var c = 0
            val stack = ArrayDeque<Int>(); stack += k; used[k] = true
            while (stack.isNotEmpty()) {
                val a = stack.removeLast(); sx += pts[a][0]; sy += pts[a][1]; c++
                val gx = floor(pts[a][0] / 14).toInt(); val gy = floor(pts[a][1] / 14).toInt()
                for (dx in -1..1) for (dy in -1..1) grid[Scenery.key(gx + dx, gy + dy)]?.forEach { b ->
                    if (!used[b] && hypot(pts[b][0] - pts[a][0], pts[b][1] - pts[a][1]) < 14 && hypot(pts[b][0] - pts[k][0], pts[b][1] - pts[k][1]) < 30) { used[b] = true; stack += b }
                }
            }
            makeJunction(sx / c, sy / c, idx, hits, skip)
        }
    }

    private fun makeJunction(x: Double, y: Double, idx: SegmentIndex, hits: Array<SegmentIndex.Hit>, skip: BooleanArray) {
        val cnt = idx.nearestPerLine(x, y, 9.0, hits)
        val arms = ArrayList<JunctionArm>()
        for (q in 0 until cnt) {
            val ri = hits[q].line; val r = roads[ri]
            if (skip[ri]) continue
            val s = hits[q].along
            for (dir in intArrayOf(1, -1)) {
                val end = if (dir > 0) r.line.length - s else s
                if (end < 10) continue
                r.line.pointAt(s + dir * 12.0, p)
                val hd = atan2(p[1] - y, p[0] - x)
                if (arms.any { abs(angleDiff(it.heading, hd)) < 0.35 }) continue // the same leg mapped twice
                arms += JunctionArm(ri, hd, r.spec.name, rank(r.spec))
            }
        }
        if (arms.size < 3) return
        val j = Junction(x, y, arms)
        junctions += j
        junctionCells.getOrPut(Scenery.key(c32(x), c32(y))) { ArrayList() } += j
    }

    private fun junctionSigns() {
        for (j in junctions) {
            val top = j.arms.maxOf { it.rank }
            val all = j.arms.all { it.rank == top }
            val names = j.arms.mapNotNull { it.name }.distinct()
            for (a in j.arms) {
                val r = roads[a.road]
                if (a.rank < 2 || r.spec.isClassVI) continue
                // Stop for the lesser road; at a T of equal roads, the road that ends here stops.
                val stops = if (all) {
                    val hitT = j.arms.count { it.road == a.road } == 1
                    hitT && j.arms.size == 3 && names.size > 1
                } else a.rank < top
                if (!stops) continue
                val back = j.arms.filter { it !== a }.maxOf { roads[it.road].halfWidth + 2.5 }
                val sAt = projectS(r, j.x, j.y)
                val dir = if (headingOf(r, sAt + 6) .let { abs(angleDiff(it, a.heading)) < PI / 2 }) 1 else -1
                val s = sAt + dir * back
                if (s < 2 || s > r.line.length - 2) continue
                // Traffic on this arm drives toward the junction (against dir); the stop sign is on its right.
                val travel = headingOf(r, s) + if (dir > 0) PI else 0.0
                val rightLat = (r.halfWidth + ground.shoulder(r) + 0.9) * (if (dir > 0) 1 else -1)
                at(r, s, rightLat, p)
                props += Prop(PropKind.STOP_SIGN, p[0], p[1], travel + PI, 0.05, 0.38, 2.4, r.seed + props.size)
                if (r.spec.surface.isPaved) {
                    at(r, s - dir * 0.8, r.halfWidth / 2 * (if (dir > 0) 1 else -1), p)
                    props += Prop(PropKind.STOP_LINE, p[0], p[1], travel, 0.2, r.halfWidth / 2 - 0.1, 0.0, 0)
                }
            }
            // Street-name blades on the corner between the two most important differently-named roads.
            if (names.size >= 2) {
                val sorted = j.arms.filter { it.name != null && it.rank >= 1 }.sortedByDescending { it.rank }
                val a = sorted.firstOrNull() ?: continue
                val b = sorted.firstOrNull { it.name != a.name } ?: continue
                // On a corner (the bisector between neighbouring arms) clear of every road, nearest
                // the two named roads first.
                val byAngle = j.arms.sortedBy { it.heading }
                val off = roads[a.road].halfWidth + roads[b.road].halfWidth + 3.5
                var x = Double.NaN; var y = Double.NaN
                val corners = byAngle.indices.map { k ->
                    val p0 = byAngle[k]; val p1 = byAngle[(k + 1) % byAngle.size]
                    var gap = p1.heading - p0.heading; if (gap <= 0) gap += 2 * PI
                    Triple(p0.heading + gap / 2, (if (p0 === a || p1 === a) 0 else 1) + (if (p0 === b || p1 === b) 0 else 1), gap)
                }.filter { it.third > 0.5 }.sortedBy { it.second }
                for ((bis, _, _) in corners) {
                    val cx = j.x + cos(bis) * off; val cy = j.y + sin(bis) * off
                    if (ground.roadClearance(cx, cy) >= 0.8) { x = cx; y = cy; break }
                }
                if (x.isNaN()) continue
                props += Prop(PropKind.STREET_SIGN, x, y, a.heading + PI / 2, 0.05, 0.05, 3.0, props.size,
                    listOf(a.name!!, b.name!!), heading2 = b.heading + PI / 2)
            }
        }
    }

    private fun projectS(r: Road, x: Double, y: Double): Double { val o = DoubleArray(2); r.line.project(x, y, 0.0, Double.MAX_VALUE, o); return o[0] }
    private fun headingOf(r: Road, s: Double): Double { r.line.pointAt(s, p); return p[2] }

    // --- buildings ---------------------------------------------------------------------------

    private fun osmBuildings() {
        // Points of interest inside a footprint name the building (a church, a store).
        val poi = osm.nodes.filter { it["name"] != null && (it["amenity"] != null || it["shop"] != null) }
        for (b in osm.buildings) {
            if (b.points.size < 4) continue
            val xs = DoubleArray(b.points.size) { proj.east(b.points[it].lon) }
            val ys = DoubleArray(b.points.size) { proj.north(b.points[it].lat) }
            // Orientation from the longest edge; extents along it and across.
            var best = 0.0; var ang = 0.0
            for (k in 0 until xs.size - 1) {
                val l = hypot(xs[k + 1] - xs[k], ys[k + 1] - ys[k])
                if (l > best) { best = l; ang = atan2(ys[k + 1] - ys[k], xs[k + 1] - xs[k]) }
            }
            val c = cos(ang); val s = sin(ang)
            var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
            for (k in xs.indices) {
                val u = xs[k] * c + ys[k] * s; val v = -xs[k] * s + ys[k] * c
                u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
            }
            val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
            val cx = cu * c - cv * s; val cy = cu * s + cv * c
            var hu = (u1 - u0) / 2; var hv = (v1 - v0) / 2
            if (hu < 1.2 || hv < 1.2 || hu > 60 || hv > 60) continue
            val tags = b.tags
            val bt = tags["building"] ?: "yes"
            val name = tags["name"] ?: poi.firstOrNull { inside(xs, ys, proj.east(it.point.lon), proj.north(it.point.lat)) }?.get("name")
            val church = bt == "church" || bt == "chapel" || tags["amenity"] == "place_of_worship"
            val kind = when {
                church -> PropKind.CHURCH
                bt in setOf("barn", "farm_auxiliary", "stable", "cowshed", "sty") -> PropKind.BARN
                bt == "silo" || tags["man_made"] == "silo" -> PropKind.SILO
                bt in setOf("garage", "garages", "shed", "carport", "hut", "cabin", "boathouse") || hu * hv < 14 -> PropKind.SHED
                bt in setOf("commercial", "retail", "industrial", "warehouse", "school", "supermarket", "office", "hotel", "public", "civic", "fire_station", "town_hall") ||
                    hu * hv * 4 > 450 || tags["shop"] != null || tags["amenity"] != null -> PropKind.STORE
                else -> PropKind.HOUSE
            }
            // Face the nearest road: heading is whichever axis points at it.
            var heading = ang
            if (nearestRoad(cx, cy, 120.0)) {
                val toRoad = atan2(hitY(hit) - cy, hitX(hit) - cx)
                val opts = doubleArrayOf(ang, ang + PI / 2, ang + PI, ang - PI / 2)
                heading = opts.minByOrNull { abs(angleDiff(it, toRoad)) }!!
                if (abs(angleDiff(heading, ang)) > 0.1 && abs(abs(angleDiff(heading, ang)) - PI) > 0.1) { val t = hu; hu = hv; hv = t }
            }
            if (!footprintClear(cx, cy, hu, hv, heading, 0.5)) continue // overlaps a road as drawn here
            val seed = (b.id xor (b.id ushr 32)).toInt()
            val levels = tags["building:levels"]?.toDoubleOrNull()
            val h = when (kind) {
                PropKind.SHED -> 2.6
                PropKind.BARN -> 6.0 + 2 * Noise.rand(seed, 1, 3)
                PropKind.SILO -> 12.0 + 6 * Noise.rand(seed, 1, 3)
                PropKind.CHURCH -> 7.5
                PropKind.STORE -> (levels ?: 1.0) * 3.6 + 0.8
                else -> (levels ?: if (Noise.rand(seed, 1, 4) < 0.45) 1.5 else 2.0) * 2.8
            }
            if (kind == PropKind.SILO) { hu = min(hu, hv); hv = hu }
            props += Prop(kind, cx, cy, heading, hu, hv, h, seed, listOfNotNull(name), buildingColor(kind, seed), roofFor(kind, tags, seed))
            claim(cx, cy, hu + 1, hv + 1, heading)
            if (kind != PropKind.SHED && kind != PropKind.SILO) rectArea(Landcover.OPEN, cx, cy, hu + 9, hv + 9, heading)
            if (name != null && (kind == PropKind.CHURCH || kind == PropKind.STORE)) landmarks += Landmark(name, if (church) "Church" else "Building", cx, cy)
            if (kind == PropKind.HOUSE || kind == PropKind.CHURCH || kind == PropKind.STORE) driveway(cx, cy, heading, hu, null)
        }
    }

    private fun hitX(h: SegmentIndex.Hit): Double { roads[h.line].line.pointAt(h.along, p); return p[0] }
    private fun hitY(h: SegmentIndex.Hit): Double { roads[h.line].line.pointAt(h.along, p); return p[1] }

    private fun inside(xs: DoubleArray, ys: DoubleArray, x: Double, y: Double): Boolean {
        var c = false; var j = xs.size - 1
        for (i in xs.indices) { if ((ys[i] > y) != (ys[j] > y) && x < xs[i] + (y - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])) c = !c; j = i }
        return c
    }

    private fun buildingColor(kind: PropKind, seed: Int): Int {
        val r = Noise.rand(seed, 7, 11)
        return when (kind) {
            PropKind.BARN -> if (r < 0.7) 0x8c2a22 else if (r < 0.85) 0x7a6a58 else 0x9a9a92
            PropKind.CHURCH -> 0xf4f2ea
            PropKind.SILO -> if (r < 0.5) 0xb8b4a8 else 0x8a8478
            PropKind.STORE -> if (r < 0.35) 0xe8e4d8 else if (r < 0.6) 0x8a5a3a else if (r < 0.8) 0xb84a2a else 0x9aa4a8
            PropKind.SHED -> if (r < 0.4) 0x8c2a22 else if (r < 0.7) 0x7a6a58 else 0xd8d4c8
            else -> when {
                r < 0.38 -> 0xf2f0e8   // white clapboard
                r < 0.5 -> 0xe8d9a0    // colonial yellow
                r < 0.62 -> 0x8a9aa8   // grey-blue
                r < 0.72 -> 0x9a3a2e   // red
                r < 0.82 -> 0x8a9a78   // sage
                r < 0.92 -> 0x7a5a3a   // natural wood
                else -> 0xc8c8c0
            }
        }
    }

    private fun roofFor(kind: PropKind, tags: Map<String, String>, seed: Int): Int = when (tags["roof:shape"]) {
        "flat" -> 2; "gambrel" -> 1; "hipped" -> 3; "gabled" -> 0
        else -> when (kind) {
            PropKind.STORE -> if (Noise.rand(seed, 3, 5) < 0.6) 2 else 0
            PropKind.BARN -> if (Noise.rand(seed, 3, 5) < 0.5) 1 else 0
            else -> 0
        }
    }

    /** A gravel driveway from the nearest road to the front of a building, with a mailbox. */
    private fun driveway(cx: Double, cy: Double, heading: Double, hx: Double, forRoad: Int?) {
        if (!nearestRoad(cx, cy, 90.0)) return
        val ri = hit.line
        if (forRoad != null && ri != forRoad) return
        val r = roads[ri]
        if (r.spec.kind == RoadKind.TRAIL) return
        val dist = hit.dist - r.halfWidth
        if (dist < 6 || dist > 80) return
        if (r.spec.kind == RoadKind.SERVICE) return // the map already has its driveway
        val fx = cx + cos(heading) * (hx + 1.5); val fy = cy + sin(heading) * (hx + 1.5)
        val s = hit.along
        if (s < 15 || s > r.line.length - 15 || nearJunction(fx, fy, 25.0)) return
        val side = if (hit.lateral > 0) 1.0 else -1.0
        val start = at(r, s, side * (r.halfWidth - 0.4), DoubleArray(3))
        if (nearMouth(start[0], start[1], 12.0)) return
        // A gentle S from the road edge to the door.
        val lx = -sin(start[2]) * side; val ly = cos(start[2]) * side
        val n = 12
        val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
        val ex = fx; val ey = fy
        for (k in 0..n) {
            val t = k / n.toDouble()
            // Leave the road square to it, then curve to the house.
            val ax = start[0] + lx * dist * 0.5; val ay = start[1] + ly * dist * 0.5
            val bx = (1 - t) * (1 - t) * start[0] + 2 * (1 - t) * t * ax + t * t * ex
            val by = (1 - t) * (1 - t) * start[1] + 2 * (1 - t) * t * ay + t * t * ey
            xs[k] = bx; ys[k] = by
        }
        val line = Polyline(xs, ys).resample(2.0)
        // No driveway up a cliff, or across another road.
        val rise = abs(elevation.height(ex, ey) - r.heightAt(s))
        if (rise > 0.18 * line.length + 0.5) return
        var t = 4.0
        while (t < line.length) {
            line.pointAt(t, p)
            if (!ground.nearestRoadHit(p[0], p[1], 30.0, hit) || hit.line != ri) { if (ground.roadClearance(p[0], p[1]) < 2.5) return }
            t += 2.0
        }
        val paved = r.spec.surface.isPaved && Noise.rand(ri, driveways.size, 41) < 0.35
        val spec = RoadSpec(RoadKind.SERVICE, if (paved) Surface.ASPHALT_WORN else Surface.GRAVEL_PACKED, 3.0, 0.12, null)
        val dw = Road(DRIVEWAY_ID - driveways.size, spec, line, elevation)
        dw.pin(0.0, r.heightAt(s), 20.0) // meets the road level, then follows the yard
        driveways += dw
        mouthCells.getOrPut(Scenery.key(c32(start[0]), c32(start[1]))) { ArrayList() } += doubleArrayOf(start[0], start[1])
        // Mailbox on the right of the mouth as you arrive.
        val mb = at(r, s + 3.2 * side, side * (r.halfWidth + ground.shoulder(r) + 0.6), DoubleArray(3))
        props += Prop(PropKind.MAILBOX, mb[0], mb[1], start[2] + if (side > 0) -PI / 2 else PI / 2, 0.25, 0.12, 1.15, props.size, color = MAILBOX_COLORS[(Noise.rand(ri, props.size, 9) * MAILBOX_COLORS.size).toInt().coerceAtMost(MAILBOX_COLORS.size - 1)])
    }

    // --- procedural settlement (no buildings in the map data) ---------------------------------

    private val sideRoadEnds: List<DoubleArray> by lazy {
        roads.filter { it.spec.kind == RoadKind.MINOR || it.spec.kind == RoadKind.SERVICE || it.spec.kind == RoadKind.GRAVEL }
            .flatMap { listOf(doubleArrayOf(it.line.xs.first(), it.line.ys.first()), doubleArrayOf(it.line.xs.last(), it.line.ys.last())) }
    }

    /** 0..1: how lived-in the country is around (x, y). */
    private fun settled(x: Double, y: Double): Double {
        if (elevation.height(x, y) > 650) return 0.0
        val noise = Noise.value2(x / 1400, y / 1400, 515)
        val haveRoads = roads.any { it.id >= 0 }
        if (!haveRoads) return 0.15 + 0.7 * Noise.smoothstep(0.42, 0.75, noise)
        // Side roads (and their driveways) are where people live.
        val n = sideRoadEnds.count { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) < 700.0 * 700.0 }
        return (n / 8.0).coerceAtMost(1.0) * (0.55 + 0.45 * noise)
    }

    private fun settlement(ri: Int, r: Road) {
        if (rank(r.spec) < 2 || r.line.length < 120) return
        val slot = 55.0
        var k = 0
        var s = 30.0
        while (s < r.line.length - 30) {
            for (side in intArrayOf(1, -1)) {
                val rr = Noise.rand(k, side + 5, r.seed + 300)
                at(r, s, 0.0, p)
                val st = settled(p[0], p[1])
                if (rr > st * 0.55) continue
                if (Noise.rand(k, side + 5, r.seed + 301) < 0.16) farm(ri, r, s, side) else house(ri, r, s, side, k)
            }
            s += slot; k++
        }
    }

    private fun house(ri: Int, r: Road, s: Double, side: Int, k: Int) {
        val seed = r.seed * 31 + k * 7 + side
        val setback = r.halfWidth + ground.shoulder(r) + 12 + 14 * Noise.rand(seed, 1, 2)
        val c = at(r, s, side * setback, DoubleArray(3))
        val heading = c[2] + if (side > 0) -PI / 2 else PI / 2 // facing the road
        val big = Noise.rand(seed, 2, 2)
        val hx = 4.0 + 2.0 * big; val hz = 5.0 + 3.5 * Noise.rand(seed, 3, 2)
        if (!siteOk(ri, c[0], c[1], hx + 3, hz + 3, heading)) return
        val h = (if (big < 0.45) 1.5 else 2.0) * 2.8
        props += Prop(PropKind.HOUSE, c[0], c[1], heading, hx, hz, h, seed, color = buildingColor(PropKind.HOUSE, seed), roof = if (Noise.rand(seed, 4, 2) < 0.15) 3 else 0)
        claim(c[0], c[1], hx + 3, hz + 3, heading)
        rectArea(Landcover.OPEN, c[0] - cos(heading) * 4, c[1] - sin(heading) * 4, hx + 14, hz + 12, heading)
        // A shed or garage out back sometimes.
        if (Noise.rand(seed, 5, 2) < 0.45) {
            val gx = c[0] - cos(heading) * (hx + 6) + cos(heading + PI / 2) * (hz + 2) * side
            val gy = c[1] - sin(heading) * (hx + 6) + sin(heading + PI / 2) * (hz + 2) * side
            if (free(gx, gy, 3.0, 3.5, heading)) {
                props += Prop(PropKind.SHED, gx, gy, heading, 3.0, 3.5, 2.8, seed + 1, color = buildingColor(PropKind.SHED, seed))
                claim(gx, gy, 3.0, 3.5, heading)
            }
        }
        driveway(c[0], c[1], heading, hx, ri)
    }

    private fun farm(ri: Int, r: Road, s: Double, side: Int) {
        val seed = r.seed * 17 + (s * 3).toInt() + side
        val along = 70.0 + 40 * Noise.rand(seed, 1, 3); val deep = 55.0 + 35 * Noise.rand(seed, 2, 3)
        val edge = r.halfWidth + ground.shoulder(r) + 3
        if (s - along < 20 || s + along > r.line.length - 20) return
        val c = at(r, s, side * (edge + deep), DoubleArray(3))
        val heading = c[2] + if (side > 0) -PI / 2 else PI / 2
        if (!siteOk(ri, c[0], c[1], deep, along, heading, maxDrop = 14.0, clear = 4.0)) return
        claim(c[0], c[1], deep, along, heading)
        rectArea(Landcover.OPEN, c[0], c[1], deep, along, heading)
        // Farmhouse and barn near the road, silo beside the barn, bales in the field.
        val hc = at(r, s - along * 0.45, side * (edge + 16), DoubleArray(3))
        props += Prop(PropKind.HOUSE, hc[0], hc[1], heading, 5.0, 7.0, 5.6, seed, color = buildingColor(PropKind.HOUSE, seed + 2))
        driveway(hc[0], hc[1], heading, 5.0, ri)
        val bc = at(r, s - along * 0.05, side * (edge + 30), DoubleArray(3))
        val barnHx = 8.0 + 3 * Noise.rand(seed, 4, 3); val barnHz = 6.0 + 2 * Noise.rand(seed, 5, 3)
        props += Prop(PropKind.BARN, bc[0], bc[1], heading, barnHx, barnHz, 7.0, seed + 1, color = buildingColor(PropKind.BARN, seed), roof = if (Noise.rand(seed, 6, 3) < 0.55) 1 else 0)
        val sc = at(r, s - along * 0.05 + side * 0.0 + barnHz + 4, side * (edge + 30), DoubleArray(3))
        props += Prop(PropKind.SILO, sc[0], sc[1], heading, 2.6, 2.6, 13.0 + 5 * Noise.rand(seed, 7, 3), seed + 2, color = buildingColor(PropKind.SILO, seed))
        for (b in 0 until (3 + 5 * Noise.rand(seed, 8, 3)).toInt()) {
            val bx = at(r, s + along * (0.3 + 0.6 * Noise.rand(seed, 20 + b, 3)), side * (edge + 15 + (deep * 2 - 25) * Noise.rand(seed, 40 + b, 3)), DoubleArray(3))
            props += Prop(PropKind.HAY_BALE, bx[0], bx[1], Noise.rand(seed, 60 + b, 3) * PI, 0.75, 0.75, 1.4, seed + b)
        }
        // Board fence along the road, stone wall at the back.
        fenceAlong(r, s - along, s + along, side * (edge - 0.5), if (Noise.rand(seed, 9, 3) < 0.5) RunKind.BOARD_FENCE else RunKind.RAIL_FENCE, seed)
        wallBack(c, heading, deep, along, seed)
    }

    private fun siteOk(ri: Int, x: Double, y: Double, hx: Double, hz: Double, heading: Double, maxDrop: Double = 3.0, clear: Double = 6.0): Boolean =
        dryLand(x, y) && besideOwn(ri, x, y, clear) && free(x, y, hx, hz, heading) && flatEnough(x, y, hx, hz, heading, maxDrop) &&
            footprintClear(x, y, hx, hz, heading, 2.0) &&
            !nearJunction(x, y, 30.0) && ground.landcover.at(x, y) != Landcover.ROCK

    private fun fenceAlong(r: Road, s0: Double, s1: Double, lat: Double, kind: RunKind, seed: Int) {
        var xs = ArrayList<Double>(); var ys = ArrayList<Double>()
        fun flush() { if (xs.size >= 3) runs += Run(kind, xs.toDoubleArray(), ys.toDoubleArray(), seed + runs.size); xs = ArrayList(); ys = ArrayList() }
        var s = max(0.0, s0)
        while (s <= min(r.line.length, s1)) {
            at(r, s, lat, p)
            if (nearMouth(p[0], p[1], 6.0) || nearJunction(p[0], p[1], 18.0) || ground.roadClearance(p[0], p[1]) < 0.6 || !dryLand(p[0], p[1])) flush()
            else { xs += p[0]; ys += p[1] }
            s += 3.0
        }
        flush()
    }

    private fun wallBack(c: DoubleArray, heading: Double, deep: Double, along: Double, seed: Int) {
        val bx = c[0] - cos(heading) * deep; val by = c[1] - sin(heading) * deep
        val tx = cos(heading + PI / 2); val ty = sin(heading + PI / 2)
        val n = (along * 2 / 3).toInt()
        val xs = DoubleArray(n + 1) { bx + tx * (-along + it * along * 2 / n) }
        val ys = DoubleArray(n + 1) { by + ty * (-along + it * along * 2 / n) }
        runs += Run(RunKind.STONE_WALL, xs, ys, seed)
    }

    // --- roadside along fields and woods -----------------------------------------------------

    /** Fences or stone walls where a mapped (or placed) field meets the road. */
    private fun fields(ri: Int, r: Road) {
        if (rank(r.spec) < 2) return
        for (side in intArrayOf(1, -1)) {
            val lat = side * (r.halfWidth + ground.shoulder(r) + 2.2)
            var s = 0.0; var start = -1.0
            fun close(end: Double) {
                if (start >= 0 && end - start > 30) {
                    val pick = Noise.rand((start / 10).toInt(), side + 3, r.seed + 500)
                    val kind = if (pick < 0.45) RunKind.STONE_WALL else if (pick < 0.8) RunKind.RAIL_FENCE else RunKind.BOARD_FENCE
                    fenceAlong(r, start, end, lat, kind, r.seed + (start * 3).toInt())
                }
                start = -1.0
            }
            while (s < r.line.length) {
                at(r, s, lat + side * 8, p)
                if (ground.landcover.at(p[0], p[1]) == Landcover.OPEN) { if (start < 0) start = s } else close(s)
                s += 6.0
            }
            close(r.line.length)
        }
    }

    /** Old stone walls running through the woods beside back roads (the farms are long gone). */
    private fun woodsWalls(ri: Int, r: Road) {
        if (r.spec.kind != RoadKind.MINOR && r.spec.kind != RoadKind.GRAVEL) return
        for (side in intArrayOf(1, -1)) {
            val lat = side * (r.halfWidth + ground.shoulder(r) + 3.2 + 1.2 * Noise.rand(ri, side + 2, 77))
            var s = 0.0; var start = -1.0
            while (s < r.line.length) {
                val on = Noise.value1(s / 160, r.seed + 600 + side) > 0.68
                at(r, s, lat, p)
                if (on && ground.landcover.at(p[0], p[1]) == Landcover.FOREST) { if (start < 0) start = s }
                else { if (start >= 0 && s - start > 25) fenceAlong(r, start, s, lat, RunKind.STONE_WALL, r.seed + side); start = -1.0 }
                s += 6.0
            }
        }
    }

    /** Guard rails along high fills and over water; railings on bridges. */
    private fun guardRails(ri: Int, r: Road) {
        if (r.spec.bridge) {
            for (side in intArrayOf(1, -1)) {
                val n = (r.line.length / 2).toInt().coerceAtLeast(1)
                val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
                for (k in 0..n) { at(r, r.line.length * k / n, side * (r.halfWidth + ground.shoulder(r) * 0.6), p); xs[k] = p[0]; ys[k] = p[1] }
                if (!r.spec.covered) runs += Run(RunKind.BRIDGE_RAIL, xs, ys, r.seed + side)
            }
            if (r.spec.covered) {
                val n = (r.line.length / 2).toInt().coerceAtLeast(1)
                val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
                for (k in 0..n) { r.line.pointAt(r.line.length * k / n, p); xs[k] = p[0]; ys[k] = p[1] }
                runs += Run(RunKind.COVERED_BRIDGE, xs, ys, r.seed, r.halfWidth + ground.shoulder(r) * 0.5 + 0.4)
                r.line.pointAt(r.line.length / 2, p)
                landmarks += Landmark(r.spec.bridgeName ?: "Covered bridge", "Covered bridge", p[0], p[1])
            }
            return
        }
        if (!r.spec.surface.isPaved) return
        val flat = r.halfWidth + ground.shoulder(r)
        for (side in intArrayOf(1, -1)) {
            val step = 4.0
            val n = (r.line.length / step).toInt()
            val need = BooleanArray(n + 1)
            for (k in 0..n) {
                val s = k * step
                at(r, s, side * (flat + 5.0), p)
                val drop = r.heightAt(s) - elevation.height(p[0], p[1])
                val water = ground.streamClearance(p[0], p[1]) < 4 || ground.landcover.at(p[0], p[1]) == Landcover.WATER
                need[k] = drop > 2.6 || water
            }
            // Runs of at least 24 m, run out 8 m past each end, broken at junctions and driveways.
            var k = 0
            while (k <= n) {
                if (!need[k]) { k++; continue }
                var e = k
                while (e <= n && need[e]) e++
                if ((e - k) * step >= 16) {
                    val s0 = max(0.0, k * step - 8); val s1 = min(r.line.length, (e - 1) * step + 8)
                    val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                    fun flush() { if (xs.size >= 4) runs += Run(RunKind.GUARDRAIL, xs.toDoubleArray(), ys.toDoubleArray(), r.seed + runs.size); xs.clear(); ys.clear() }
                    var s = s0
                    while (s <= s1) {
                        at(r, s, side * (flat - 0.15), p)
                        if (nearJunction(p[0], p[1], 16.0) || nearMouth(p[0], p[1], 5.0)) flush() else { xs += p[0]; ys += p[1] }
                        s += 2.0
                    }
                    flush()
                }
                k = e
            }
        }
    }

    // --- signs ---------------------------------------------------------------------------------

    private fun signs(ri: Int, r: Road) {
        val rk = rank(r.spec)
        if (rk < 2) return
        val paved = r.spec.surface.isPaved
        fun place(kind: PropKind, s: Double, dir: Int, text: List<String> = emptyList(), w: Double = 0.38, h: Double = 2.3) {
            if (s < 20 || s > r.line.length - 20) return
            val lat = -dir * (r.halfWidth + ground.shoulder(r) + 1.0)
            at(r, s, lat, p)
            if (nearJunction(p[0], p[1], 25.0) || nearMouth(p[0], p[1], 6.0) || !besideOwn(ri, p[0], p[1], 0.5)) return
            val travel = p[2] + if (dir > 0) 0.0 else PI
            props += Prop(kind, p[0], p[1], travel + PI, 0.05, w, h, r.seed + props.size, text)
        }
        // Speed limits, one each way every mile or so.
        if (paved && r.line.length > 500) {
            val limit = when (r.spec.kind) { RoadKind.HIGHWAY -> 50; RoadKind.MAJOR -> 45; else -> if (r.spec.width >= 6.0) 40 else 30 }
            var s = 140.0
            while (s < r.line.length) { place(PropKind.SIGN_SPEED, s, 1, listOf("SPEED", "LIMIT", "$limit"), 0.34, 2.4); place(PropKind.SIGN_SPEED, s + 800, -1, listOf("SPEED", "LIMIT", "$limit"), 0.34, 2.4); s += 1600 }
        }
        // Curve warnings ahead of tight bends.
        if (Road.designSpeed(r.spec) > 0 && r.line.length > 200) {
            var s = 30.0
            var lastBend = -1e9
            while (s < r.line.length - 30) {
                val d = angleDiff(headingOf(r, s - 20), headingOf(r, s + 20))
                if (abs(d) > 0.85 && s - lastBend > 150) {
                    lastBend = s
                    val left = d > 0
                    place(if (left) PropKind.SIGN_CURVE_LEFT else PropKind.SIGN_CURVE_RIGHT, s - 85, 1)
                    place(if (left) PropKind.SIGN_CURVE_RIGHT else PropKind.SIGN_CURVE_LEFT, s + 85, -1)
                }
                s += 10.0
            }
        }
        // New Hampshire's moose warnings on the main roads.
        if (rk >= 4 && r.line.length > 3000) {
            var s = 1200.0 + 800 * Noise.rand(ri, 1, 61)
            var dir = 1
            while (s < r.line.length - 200) { place(PropKind.SIGN_MOOSE, s, dir, listOf("BRAKE", "FOR MOOSE", "IT COULD SAVE", "YOUR LIFE"), 0.75, 2.6); dir = -dir; s += 5200 }
        }
    }

    // --- landmarks -----------------------------------------------------------------------------

    private fun landmarks() {
        class Spot(val tags: Map<String, String>, val x: Double, val y: Double, val id: Long)
        val spots = ArrayList<Spot>()
        for (n in osm.nodes) spots += Spot(n.tags, proj.east(n.point.lon), proj.north(n.point.lat), n.id)
        for (w in osm.ways) if (!w.tags.containsKey("highway") && !w.tags.containsKey("waterway") || w.tags["waterway"] == "waterfall")
            spots += Spot(w.tags, proj.east(w.points.map { it.lon }.average()), proj.north(w.points.map { it.lat }.average()), w.id)
        for (a in osm.areas) if (a.tags["natural"] == "stone" || a.tags["natural"] == "rock" || a.tags["tourism"] != null || a.tags["historic"] != null) {
            val ring = a.outers.firstOrNull() ?: continue
            spots += Spot(a.tags, proj.east(ring.map { it.lon }.average()), proj.north(ring.map { it.lat }.average()), a.id)
        }
        for (sp in spots) {
            val t = sp.tags
            val name = t["name"]
            val seed = (sp.id xor (sp.id ushr 32)).toInt()
            when {
                t["tourism"] == "viewpoint" -> viewpoint(sp.x, sp.y, name, seed)
                t["natural"] == "stone" || t["natural"] == "rock" -> {
                    val size = (t["height"]?.removeSuffix("m")?.trim()?.toDoubleOrNull() ?: 5.0).coerceIn(2.0, 12.0)
                    boulder(sp.x, sp.y, size, seed, name)
                }
                t["historic"] in setOf("memorial", "monument") && t["memorial"] !in setOf("plaque", "board", "blue_plaque", "stolperstein") -> {
                    val c = offRoad(sp.x, sp.y, 4.0) ?: continue
                    props += Prop(PropKind.MONUMENT, c[0], c[1], c[2], 0.7, 0.7, if (t["historic"] == "monument") 6.0 else 2.6, seed, listOfNotNull(name))
                    if (name != null) landmarks += Landmark(name, "Monument", c[0], c[1])
                }
                t["historic"] != null && name != null -> {
                    // NH historical highway markers and other historic sites: a marker by the road.
                    val c = roadsideSign(sp.x, sp.y) ?: continue
                    props += Prop(PropKind.HISTORIC_MARKER, c[0], c[1], c[2], 0.05, 0.75, 2.4, seed, wrap(name, 14))
                    landmarks += Landmark(name, "Historic site", sp.x, sp.y)
                }
                t["man_made"] in setOf("tower", "water_tower", "lighthouse", "windmill") -> {
                    val c = offRoad(sp.x, sp.y, 4.0) ?: continue
                    val tall = t["height"]?.removeSuffix("m")?.trim()?.toDoubleOrNull() ?: 18.0
                    props += Prop(PropKind.TOWER, c[0], c[1], c[2], 2.2, 2.2, tall.coerceIn(6.0, 40.0), seed, listOfNotNull(name))
                    landmarks += Landmark(name ?: "Fire tower", "Tower", c[0], c[1])
                }
                t["waterway"] == "waterfall" || t["natural"] == "waterfall" || t["tourism"] in setOf("attraction", "museum", "picnic_site") || t["natural"] == "cave_entrance" -> {
                    if (name == null) continue
                    val c = roadsideSign(sp.x, sp.y)
                    if (c != null) props += Prop(PropKind.BROWN_SIGN, c[0], c[1], c[2], 0.05, 0.9, 2.4, seed, wrap(name, 14))
                    landmarks += Landmark(name, if (t["waterway"] == "waterfall") "Waterfall" else "Attraction", sp.x, sp.y)
                }
                t["amenity"] == "place_of_worship" && osm.buildings.isEmpty() -> {
                    val c = offRoad(sp.x, sp.y, 16.0) ?: continue
                    props += Prop(PropKind.CHURCH, c[0], c[1], c[2], 9.0, 5.5, 7.5, seed, listOfNotNull(name), 0xf4f2ea)
                    claim(c[0], c[1], 10.0, 7.0, c[2])
                    rectArea(Landcover.OPEN, c[0], c[1], 22.0, 18.0, c[2])
                    landmarks += Landmark(name ?: "Church", "Church", c[0], c[1])
                }
                (t["amenity"] != null || t["shop"] != null) && name != null -> landmarks += Landmark(name, "Stop", sp.x, sp.y)
                t["natural"] == "peak" && name != null -> landmarks += Landmark(name + (t["ele"]?.toDoubleOrNull()?.let { " (%,d ft)".format((it * 3.28084).toInt()) } ?: ""), "Peak", sp.x, sp.y)
            }
        }
        // The club's own waypoints from the GPX file.
        for (w in waypoints) {
            val x = proj.east(w.point.lon); val y = proj.north(w.point.lat)
            landmarks += Landmark(w.name, "Waypoint", x, y)
            val c = roadsideSign(x, y) ?: continue
            props += Prop(PropKind.BROWN_SIGN, c[0], c[1], c[2], 0.05, 0.9, 2.4, w.name.hashCode(), wrap(w.name, 14))
        }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in text.uppercase().split(' ')) {
            if (line.isEmpty()) line = w else if (line.length + 1 + w.length <= width) line += " $w" else { out += line; line = w }
        }
        if (line.isNotEmpty()) out += line
        return out.take(4)
    }

    /** (x, y) moved out to at least [clear] m from any road edge, facing the nearest road; x, y, heading. */
    private fun offRoad(x: Double, y: Double, clear: Double): DoubleArray? {
        if (!nearestRoad(x, y, WIDE)) return doubleArrayOf(x, y, 0.0)
        val rx = hitX(hit); val ry = hitY(hit)
        val toRoad = atan2(ry - y, rx - x)
        val c = ground.roadClearance(x, y)
        if (c >= clear) return doubleArrayOf(x, y, toRoad)
        // Push away from the road.
        val away = toRoad + PI
        val push = clear - c
        return doubleArrayOf(x + cos(away) * push, y + sin(away) * push, toRoad)
    }

    /**
     * A sign spot on the nearest road edge to (x, y): on the right as you ride the route past it,
     * facing you (or facing traffic on the near side, off the route); x, y, heading, or null if
     * there is no road within reach.
     */
    private fun roadsideSign(x: Double, y: Double): DoubleArray? {
        if (!nearestRoad(x, y, WIDE)) return null
        val r = roads[hit.line]
        val s = hit.along
        r.line.pointAt(s, p)
        val roadHd = p[2]
        var dir = if (hit.lateral > 0) -1 else 1 // near side: traffic keeping right passes it
        route.project(p[0], p[1], 0.0, Double.MAX_VALUE, routeTmp)
        if (routeTmp[1] < 25) {
            val a = route.pointAt(routeTmp[0] - 2, DoubleArray(3)); val b = route.pointAt(routeTmp[0] + 2, DoubleArray(3))
            dir = if (kotlin.math.cos(atan2(b[1] - a[1], b[0] - a[0]) - roadHd) > 0) 1 else -1
        }
        val c = at(r, s, -dir * (r.halfWidth + ground.shoulder(r) + 1.4), DoubleArray(3))
        c[2] = roadHd + if (dir > 0) PI else 0.0
        return c
    }

    private fun viewpoint(x: Double, y: Double, name: String?, seed: Int) {
        // Clear the view downhill: the lowest direction 200 m out.
        val h0 = elevation.height(x, y)
        var bestA = 0.0; var bestH = Double.MAX_VALUE
        for (k in 0 until 24) {
            val a = k * 2 * PI / 24
            val h = elevation.height(x + cos(a) * 200, y + sin(a) * 200)
            if (h < bestH) { bestH = h; bestA = a }
        }
        if (h0 - bestH > 15) {
            val xs = DoubleArray(8); val ys = DoubleArray(8)
            xs[0] = x; ys[0] = y
            for (k in 0..5) { val a = bestA - 0.5 + k * 0.2; xs[k + 1] = x + cos(a) * 320; ys[k + 1] = y + sin(a) * 320 }
            xs[7] = x; ys[7] = y
            clearings += Landcover.Area(Landcover.CLEARING, listOf(xs to ys))
            // A low wall along the brink.
            val wx = x + cos(bestA) * 6; val wy = y + sin(bestA) * 6
            val tx = cos(bestA + PI / 2); val ty = sin(bestA + PI / 2)
            if (ground.roadClearance(wx, wy) > 1.0) runs += Run(RunKind.STONE_WALL, DoubleArray(7) { wx + tx * (it - 3) * 3.0 }, DoubleArray(7) { wy + ty * (it - 3) * 3.0 }, seed)
        }
        val c = roadsideSign(x, y)
        if (c != null) props += Prop(PropKind.BROWN_SIGN, c[0], c[1], c[2], 0.05, 0.9, 2.4, seed, listOf("SCENIC", "VIEW") + (name?.let { wrap(it, 14).take(2) } ?: emptyList()))
        landmarks += Landmark(name ?: "Scenic view", "Scenic view", x, y)
    }

    private fun boulder(x: Double, y: Double, size: Double, seed: Int, name: String?) {
        val c = offRoad(x, y, size * 0.7 + 2.0) ?: return
        val rad = size * (0.75 + 0.3 * Noise.rand(seed, 1, 13))
        props += Prop(PropKind.ERRATIC, c[0], c[1], Noise.rand(seed, 2, 13) * PI, rad, rad * (0.65 + 0.25 * Noise.rand(seed, 3, 13)), size, seed, listOfNotNull(name))
        if (name != null) {
            landmarks += Landmark(name, "Boulder", c[0], c[1])
            val sgn = roadsideSign(c[0], c[1])
            if (sgn != null && hypot(sgn[0] - c[0], sgn[1] - c[1]) < 120) props += Prop(PropKind.BROWN_SIGN, sgn[0], sgn[1], sgn[2], 0.05, 0.9, 2.4, seed, wrap(name, 14))
        }
    }

    /** A few big glacial erratics a short way into the woods, every few kilometres of road. */
    private fun erratics() {
        var acc = 0.0
        for ((ri, r) in roads.withIndex()) {
            if (r.spec.bridge || r.spec.kind == RoadKind.SERVICE) continue
            var s = 200.0 + 2000 * Noise.rand(ri, 3, 71)
            while (s < r.line.length - 50) {
                val side = if (Noise.rand(ri, (s / 10).toInt(), 72) < 0.5) 1 else -1
                val size = 3.0 + 3.5 * Noise.rand(ri, (s / 10).toInt(), 73)
                val c = at(r, s, side * (r.halfWidth + ground.shoulder(r) + size * 0.9 + 4 + 8 * Noise.rand(ri, (s / 10).toInt(), 74)), DoubleArray(3))
                if (ground.landcover.at(c[0], c[1]) == Landcover.FOREST && dryLand(c[0], c[1]) && ground.roadClearance(c[0], c[1]) > size * 0.8 + 2 &&
                    free(c[0], c[1], size, size, 0.0) && routeDist(c[0], c[1]) < 400) {
                    val seed = r.seed + (s * 7).toInt()
                    props += Prop(PropKind.ERRATIC, c[0], c[1], Noise.rand(seed, 2, 13) * PI, size * 0.8, size * 0.6, size, seed)
                    claim(c[0], c[1], size, size, 0.0)
                }
                s += 2600 + 2400 * Noise.rand(ri, (s / 10).toInt(), 75)
                acc += 1
            }
        }
    }

    companion object {
        private const val OCC = 5.0
        private const val WIDE = 150.0
        const val DRIVEWAY_ID = -2_000_000L
        private val MAILBOX_COLORS = intArrayOf(0x1a1a1a, 0x1a1a1a, 0x8c8c90, 0x2a4a2a, 0x8c2a22)
    }
}
