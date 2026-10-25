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
        for (w in osm.ways) {
            val spec = RoadClassifier.classify(w.tags) ?: continue
            val xs = DoubleArray(w.points.size) { proj.east(w.points[it].lon) }
            val ys = DoubleArray(w.points.size) { proj.north(w.points[it].lat) }
            val rounded = Polyline(xs, ys).rounded()
            for (line in Road.splitLong(rounded.xs, rounded.ys)) if (line.length > 1.0) roads += Road(w.id, spec, line, elevation)
        }
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
        val ground = Ground(elevation, roads, streams, Landcover(areas))

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
