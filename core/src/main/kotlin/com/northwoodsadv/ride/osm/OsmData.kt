package com.northwoodsadv.ride.osm

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.northwoodsadv.ride.geo.GeoPoint

class OsmWay(val id: Long, val tags: Map<String, String>, val points: List<GeoPoint>) {
    operator fun get(k: String) = tags[k]
    val isClosed get() = points.size > 3 && points.first().lat == points.last().lat && points.first().lon == points.last().lon
}

/** An area: outer rings (closed) and holes. */
class OsmArea(val id: Long, val tags: Map<String, String>, val outers: List<List<GeoPoint>>, val inners: List<List<GeoPoint>>)

/** A tagged point: a viewpoint, a named boulder, a church, a historical marker... */
class OsmNode(val id: Long, val tags: Map<String, String>, val point: GeoPoint) {
    operator fun get(k: String) = tags[k]
}

class OsmData(
    val ways: List<OsmWay>,
    val areas: List<OsmArea>,
    val nodes: List<OsmNode> = emptyList(),
    /** Building footprints (closed ways). */
    val buildings: List<OsmWay> = emptyList(),
) {
    companion object {
        val EMPTY = OsmData(emptyList(), emptyList())

        /** Parses an Overpass API `[out:json]` response produced with `out geom;`. */
        fun parseOverpass(json: String): OsmData {
            val root = JsonReader().parse(json)
            val ways = ArrayList<OsmWay>()
            val areas = ArrayList<OsmArea>()
            val nodes = ArrayList<OsmNode>()
            val buildings = ArrayList<OsmWay>()
            var el = root.get("elements")?.child
            while (el != null) {
                val type = el.getString("type", "")
                val tags = tags(el.get("tags"))
                when (type) {
                    "node" -> if (tags.isNotEmpty() && el.has("lat")) {
                        nodes += OsmNode(el.getLong("id"), tags, GeoPoint(el.getDouble("lat"), el.getDouble("lon")))
                    }
                    "way" -> {
                        val pts = geometry(el.get("geometry"))
                        if (pts.size >= 2) {
                            val w = OsmWay(el.getLong("id"), tags, pts)
                            if (tags.containsKey("building") && !tags.containsKey("highway") && w.isClosed) buildings += w
                            else if (isAreaTagged(tags) && w.isClosed) areas += OsmArea(w.id, tags, listOf(pts), emptyList())
                            else ways += w
                        }
                    }
                    "relation" -> if (isAreaTagged(tags)) {
                        val outer = ArrayList<List<GeoPoint>>(); val inner = ArrayList<List<GeoPoint>>()
                        var m = el.get("members")?.child
                        while (m != null) {
                            if (m.getString("type", "") == "way") {
                                val g = geometry(m.get("geometry"))
                                if (g.size >= 2) (if (m.getString("role", "outer") == "inner") inner else outer) += g
                            }
                            m = m.next
                        }
                        val outers = stitchRings(outer); val inners = stitchRings(inner)
                        if (outers.isNotEmpty()) areas += OsmArea(el.getLong("id"), tags, outers, inners)
                    }
                }
                el = el.next
            }
            return OsmData(ways, areas, nodes, buildings)
        }

        private fun isAreaTagged(t: Map<String, String>) =
            !t.containsKey("highway") && !t.containsKey("waterway") &&
                (t.containsKey("natural") || t.containsKey("landuse") || t["area"] == "yes")

        private fun tags(v: JsonValue?): Map<String, String> {
            if (v == null) return emptyMap()
            val m = HashMap<String, String>()
            var c = v.child
            while (c != null) { m[c.name] = c.asString(); c = c.next }
            return m
        }

        private fun geometry(v: JsonValue?): List<GeoPoint> {
            if (v == null) return emptyList()
            val out = ArrayList<GeoPoint>(v.size)
            var c = v.child
            while (c != null) {
                if (c.has("lat")) out += GeoPoint(c.getDouble("lat"), c.getDouble("lon"))
                c = c.next
            }
            return out
        }

        /** Joins multipolygon member ways end-to-end into closed rings. */
        fun stitchRings(parts: List<List<GeoPoint>>): List<List<GeoPoint>> {
            val pending = parts.map { it.toMutableList() }.toMutableList()
            val rings = ArrayList<List<GeoPoint>>()
            fun same(a: GeoPoint, b: GeoPoint) = a.lat == b.lat && a.lon == b.lon
            while (pending.isNotEmpty()) {
                val ring = pending.removeAt(0)
                var grew = true
                while (!same(ring.first(), ring.last()) && grew) {
                    grew = false
                    val it = pending.iterator()
                    while (it.hasNext()) {
                        val p = it.next()
                        when {
                            same(ring.last(), p.first()) -> { ring.addAll(p.drop(1)); it.remove(); grew = true }
                            same(ring.last(), p.last()) -> { ring.addAll(p.reversed().drop(1)); it.remove(); grew = true }
                            same(ring.first(), p.last()) -> { ring.addAll(0, p.dropLast(1)); it.remove(); grew = true }
                            same(ring.first(), p.first()) -> { ring.addAll(0, p.reversed().dropLast(1)); it.remove(); grew = true }
                        }
                        if (grew) break
                    }
                }
                if (ring.size >= 4 && same(ring.first(), ring.last())) rings += ring
            }
            return rings
        }
    }
}
