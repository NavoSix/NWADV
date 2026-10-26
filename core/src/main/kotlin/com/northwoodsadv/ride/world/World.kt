package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GeoBounds
import com.northwoodsadv.ride.geo.GpxFile
import com.northwoodsadv.ride.geo.GpxTrack
import com.northwoodsadv.ride.geo.LocalProjection
import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.OsmData
import com.northwoodsadv.ride.osm.Overpass
import com.northwoodsadv.ride.osm.RoadClassifier
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.DemElevation
import com.northwoodsadv.ride.terrain.DemLoader
import com.northwoodsadv.ride.terrain.Elevation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** Everything needed to ride one GPX route. */
class World(
    val name: String,
    val proj: LocalProjection,
    val route: Polyline,
    val ground: Ground,
    val gpx: GpxFile,
    val notes: List<String>,
    val spawnX: Double,
    val spawnY: Double,
    val spawnHeading: Double,
) {
    val roads get() = ground.roads
}

/**
 * Builds a [World] from a GPX file: downloads (or reads cached) elevation tiles and OpenStreetMap
 * data around the route, then assembles roads, streams and land cover. Runs on a worker thread.
 */
class WorldBuilder(private val cache: DiskCache) {
    fun interface Progress { fun update(stage: String, fraction: Float) }

    fun build(gpx: GpxFile, progress: Progress = Progress { _, _ -> }): World {
        val notes = ArrayList<String>()
        val points = gpx.ridePoints
        val bounds = GeoBounds.of(points)
        val proj = LocalProjection(bounds.center)
        val route = Polyline.from(points, proj).rounded(5.0)

        // --- elevation ---------------------------------------------------------------------
        val knownEle = points.map { it.ele }.filter { !it.isNaN() }
        val elevation = DemElevation(proj, fallbackHeight = if (knownEle.isEmpty()) 200.0 else knownEle.average())
        val dem = DemLoader(cache)
        val coarse = dem.boundsTiles(bounds.expandMeters(8000.0), elevation.coarseZoom)
        val fine = dem.corridorTiles(route, proj, elevation.fineZoom, 1600.0)
        val total = coarse.size + fine.size
        progress.update("Downloading terrain", 0f)
        val r1 = dem.load(coarse, elevation) { d, _ -> progress.update("Downloading terrain", d.toFloat() / total) }
        val r2 = dem.load(fine, elevation) { d, _ -> progress.update("Downloading terrain", (coarse.size + d).toFloat() / total) }
        if (r1.failed + r2.failed > 0) {
            notes += "${r1.failed + r2.failed} of $total terrain tiles unavailable (${r2.firstError ?: r1.firstError})" +
                if (r1.loaded + r2.loaded == 0) "; terrain is flat." else "."
        }

        // --- OpenStreetMap -------------------------------------------------------------------
        progress.update("Downloading roads (OpenStreetMap)", 0f)
        val osm = try {
            Overpass(cache).fetch(points)
        } catch (e: Exception) {
            notes += "Couldn't download OpenStreetMap roads (${e.message ?: e.javaClass.simpleName}). Your GPX track is used as the road."
            OsmData.EMPTY
        }

        progress.update("Building roads", 0.2f)
        val roads = ArrayList<Road>()
        val pieces = ArrayList<Triple<Long, RoadSpec, Polyline>>()
        for (w in osm.ways) {
            val spec = RoadClassifier.classify(w.tags) ?: continue
            val xs = DoubleArray(w.points.size) { proj.east(w.points[it].lon) }
            val ys = DoubleArray(w.points.size) { proj.north(w.points[it].lat) }
            val rounded = Polyline(xs, ys).rounded()
            for (line in Road.splitLong(rounded.xs, rounded.ys)) if (line.length > 1.0) pieces += Triple(w.id, spec, line)
        }
        val carryOn = continuations(pieces.map { it.third })
        for ((k, pc) in pieces.withIndex()) roads += Road(pc.first, pc.second, pc.third, elevation, carryOn[k].first, carryOn[k].second)
        val osmRoadCount = roads.size

        // GPX sections with no mapped road nearby become trails (unmapped Class VI, logging roads...).
        progress.update("Matching your track to roads", 0.5f)
        val probe = Ground(elevation, roads)
        var trailId = -1000L
        for (track in gpx.tracks) {
            val line = Polyline.from(track.points, proj).rounded(5.0)
            val spec = trackSpec(track, osmRoadCount > 0)
            if (osmRoadCount == 0) {
                for (piece in Road.splitLong(line.xs, line.ys)) roads += Road(trailId--, spec, piece, elevation)
                continue
            }
            for (run in offRoadRuns(line, probe)) {
                for (piece in Road.splitLong(line.xs.copyOfRange(run.first, run.last + 1), line.ys.copyOfRange(run.first, run.last + 1)))
                    roads += Road(trailId--, spec, piece, elevation)
            }
        }
        if (roads.size > osmRoadCount && osmRoadCount > 0) {
            notes += "Parts of your track aren't on any mapped road; they're ridden as unmaintained trail."
        }

        Road.connectAll(roads)
        Road.flushWithMapped(roads) { probe.shoulder(it) }

        // --- water and land cover ---------------------------------------------------------------
        progress.update("Streams, lakes and fields", 0.8f)
        val streams = osm.ways.mapNotNull { w ->
            val ww = w["waterway"] ?: return@mapNotNull null
            val width = w["width"]?.toDoubleOrNull() ?: when (ww) { "river" -> 12.0; "canal" -> 6.0; "stream" -> 2.5; else -> 1.8 }
            val xs = DoubleArray(w.points.size) { proj.east(w.points[it].lon) }
            val ys = DoubleArray(w.points.size) { proj.north(w.points[it].lat) }
            Road.splitLong(xs, ys).map { Stream(it, width, ww == "river") }
        }.flatten().toMutableList()
        devStreams(route, streams)
        val areas = osm.areas.mapNotNull { a ->
            val code = Landcover.codeFor(a.tags) ?: return@mapNotNull null
            val rings = (a.outers + a.inners).map { ring ->
                DoubleArray(ring.size) { proj.east(ring[it].lon) } to DoubleArray(ring.size) { proj.north(ring[it].lat) }
            }
            Landcover.Area(code, rings)
        }
        // Buildings, walls, fences, signs and landmarks beside the roads; their lawns, fields and
        // driveways then become part of the ground itself.
        progress.update("Houses, walls and landmarks", 0.9f)
        val sceneryBuilder = SceneryBuilder(Ground(elevation, roads, streams, Landcover(areas)), osm, proj, route, gpx.waypoints)
        val scenery = sceneryBuilder.build()
        roads += sceneryBuilder.driveways
        val ground = Ground(elevation, roads, streams, Landcover(areas + sceneryBuilder.clearings))
        ground.scenery = scenery
        if (System.getProperty("nwadv.dumpScenery") != null) {
            println("scenery: " + scenery.props.groupingBy { it.kind }.eachCount() + " runs " + scenery.runs.groupingBy { it.kind }.eachCount() +
                " junctions ${scenery.junctions.size} landmarks ${scenery.landmarks.size} driveways ${sceneryBuilder.driveways.size}")
            val o = DoubleArray(2)
            for (j in scenery.junctions) { route.project(j.x, j.y, 0.0, Double.MAX_VALUE, o); println("  junction at route %.0f m, %.0f m off: ".format(o[0], o[1]) + j.arms.joinToString { "${it.name}(${it.rank}) %.0f".format(Math.toDegrees(it.heading)) }) }
            for (k in PropKind.values()) scenery.props.filter { it.kind == k }.take(4).forEach { p ->
                route.project(p.x, p.y, 0.0, Double.MAX_VALUE, o); println("  $k at route %.0f m, %.0f m off".format(o[0], o[1]))
            }
        }

        val tmp = DoubleArray(3)
        route.pointAt(0.0, tmp)
        val ahead = route.pointAt(minOf(25.0, route.length), DoubleArray(3))
        val heading = atan2(ahead[1] - tmp[1], ahead[0] - tmp[0])
        progress.update("Ready", 1f)
        return World(gpx.name, proj, route, ground, gpx, notes, tmp[0], tmp[1], heading)
    }

    /** Surface for GPX sections without OSM roads, from the GPX <type> if the file has one. */
    private fun trackSpec(t: GpxTrack, haveOsm: Boolean): RoadSpec {
        val type = (t.type ?: "").lowercase().replace(" ", "").replace("_", "")
        return when {
            "paved" in type || "asphalt" in type -> RoadSpec(RoadKind.MINOR, Surface.ASPHALT, 6.0, 0.12, t.name)
            "gravel" in type || "dirtroad" in type -> RoadSpec(RoadKind.GRAVEL, Surface.GRAVEL_PACKED, 5.0, 0.25, t.name)
            "classvi" in type || "class6" in type || "unmaintained" in type || "jeep" in type ->
                RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.65, t.name)
            "trail" in type || "singletrack" in type || "ohrv" in type -> RoadSpec(RoadKind.TRAIL, Surface.DIRT, 1.8, 0.7, t.name)
            haveOsm -> RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.6, t.name)
            else -> RoadSpec(RoadKind.GRAVEL, Surface.GRAVEL_PACKED, 5.0, 0.3, t.name)
        }
    }

    companion object {
        /**
         * For each line, where its road carries on past its start and its end: the line that
         * continues from that end (an endpoint within 1.5 m, heading on within 50 degrees),
         * followed for up to ~90 m across further pieces, as a polyline leading away from the end.
         */
        fun continuations(lines: List<Polyline>): List<Pair<Polyline?, Polyline?>> {
            val cell = 4.0
            val ends = HashMap<Long, MutableList<Int>>() // code = line * 2 + end
            fun key(x: Double, y: Double) = (kotlin.math.floor(x / cell).toLong() shl 32) or (kotlin.math.floor(y / cell).toLong() and 0xffffffffL)
            fun endPt(code: Int): DoubleArray { val l = lines[code / 2]; val k = if (code % 2 == 0) 0 else l.size - 1; return doubleArrayOf(l.xs[k], l.ys[k]) }
            // Heading pointing away from the line at that end (outward).
            fun outward(code: Int): Double {
                val l = lines[code / 2]
                val a = l.pointAt(if (code % 2 == 0) 0.0 else l.length, DoubleArray(3))
                val b = l.pointAt(if (code % 2 == 0) minOf(8.0, l.length) else maxOf(0.0, l.length - 8.0), DoubleArray(3))
                return atan2(a[1] - b[1], a[0] - b[0])
            }
            for (c in 0 until lines.size * 2) { val e = endPt(c); ends.getOrPut(key(e[0], e[1])) { ArrayList() } += c }
            fun next(code: Int): Int? {
                val e = endPt(code); val out = outward(code)
                var best: Int? = null; var bestA = Math.toRadians(50.0)
                for (dx in -1..1) for (dy in -1..1) {
                    val list = ends[key(e[0] + dx * cell, e[1] + dy * cell)] ?: continue
                    for (c in list) {
                        if (c / 2 == code / 2) continue
                        val o = endPt(c)
                        if (kotlin.math.hypot(o[0] - e[0], o[1] - e[1]) > 1.5) continue
                        // Going into the other line from its end is the reverse of its outward heading.
                        var d = (outward(c) + PI) - out
                        while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
                        if (abs(d) < bestA) { bestA = abs(d); best = c }
                    }
                }
                return best
            }
            fun walk(code: Int): Polyline? {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                val e = endPt(code); xs += e[0]; ys += e[1]
                var at = next(code) ?: return null
                var len = 0.0; var hops = 0
                while (hops < 4 && len < 90.0) {
                    val l = lines[at / 2]
                    val idx = if (at % 2 == 0) (0 until l.size) else (l.size - 1 downTo 0)
                    for (k in idx) {
                        val dl = kotlin.math.hypot(l.xs[k] - xs.last(), l.ys[k] - ys.last())
                        if (dl < 1e-6) continue
                        xs += l.xs[k]; ys += l.ys[k]; len += dl
                        if (len > 90.0) break
                    }
                    hops++
                    at = next(at xor 1) ?: break
                }
                return if (xs.size >= 2) Polyline(xs.toDoubleArray(), ys.toDoubleArray()) else null
            }
            return lines.indices.map { walk(it * 2) to walk(it * 2 + 1) }
        }

        /**
         * Index ranges of a GPX line (evenly resampled) that are NOT riding along a mapped
         * road. Being near a road isn't enough: the track has to run alongside it (within ~10 m
         * and heading the same way) for at least 80 m. So crossing a road, or passing close to
         * one, keeps the trail continuous. Ranges overlap 30 m into the roads they join.
         */
        fun offRoadRuns(line: Polyline, ground: Ground): List<IntRange> {
            val n = line.size
            if (n < 2) return emptyList()
            val spacing = (line.length / (n - 1)).coerceAtLeast(0.5)
            val follows = BooleanArray(n)
            val hit = DoubleArray(2)
            for (i in 0 until n) {
                val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
                val hdg = atan2(line.ys[b] - line.ys[a], line.xs[b] - line.xs[a])
                if (ground.nearestRoad(line.xs[i], line.ys[i], 25.0, hit) && hit[0] < 10.0) {
                    var d = abs(hdg - hit[1]) % PI
                    if (d > PI / 2) d = PI - d
                    follows[i] = d < Math.toRadians(30.0)
                }
            }
            // Short "following" stretches are just crossings or brushes past a road.
            var i = 0
            while (i < n) {
                if (!follows[i]) { i++; continue }
                var j = i
                while (j < n && follows[j]) j++
                if (j - i < (80.0 / spacing).toInt()) for (k in i until j) follows[k] = false
                i = j
            }
            val out = ArrayList<IntRange>()
            i = 0
            while (i < n) {
                if (follows[i]) { i++; continue }
                var j = i
                while (j < n && !follows[j]) j++
                val pad = (30.0 / spacing).toInt()
                val a = maxOf(0, i - pad); val b = minOf(n - 1, j - 1 + pad)
                if (b > a) out += a..b
                i = j
            }
            return out
        }

        /**
         * Development: system property nwadv.devStreams = comma-separated route distances, each
         * gets a straight 3 m stream across the route (for checking fords without map data).
         */
        fun devStreams(route: Polyline, out: MutableList<Stream>) {
            val at = System.getProperty("nwadv.devStreams") ?: return
            val p = DoubleArray(3)
            for (s in at.split(",").mapNotNull { it.trim().toDoubleOrNull() }) {
                route.pointAt(s, p)
                val lx = -kotlin.math.sin(p[2]); val ly = kotlin.math.cos(p[2])
                val xs = DoubleArray(25) { p[0] + lx * (it - 12) * 5.0 }; val ys = DoubleArray(25) { p[1] + ly * (it - 12) * 5.0 }
                out += Stream(Polyline(xs, ys), 3.0, false)
            }
        }

        /** A world without any downloads, for tests and the offline demo. */
        fun offline(gpx: GpxFile, elevation: (LocalProjection) -> Elevation): World {
            val points = gpx.ridePoints
            val proj = LocalProjection(GeoBounds.of(points).center)
            val route = Polyline.from(points, proj).rounded(5.0)
            val elev = elevation(proj)
            val roads = gpx.tracks.flatMapIndexed { i, t ->
                val line = Polyline.from(t.points, proj).rounded(5.0)
                Road.splitLong(line.xs, line.ys).map { Road(-1000L - i, WorldBuilder(DiskCache(java.io.File("."))).trackSpec(t, false), it, elev) }
            }
            val tmp = DoubleArray(3); route.pointAt(0.0, tmp)
            val ahead = route.pointAt(minOf(25.0, route.length), DoubleArray(3))
            return World(gpx.name, proj, route, Ground(elev, roads), gpx, emptyList(), tmp[0], tmp[1], atan2(ahead[1] - tmp[1], ahead[0] - tmp[0]))
        }
    }
}
