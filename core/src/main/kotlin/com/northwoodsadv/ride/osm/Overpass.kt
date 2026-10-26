package com.northwoodsadv.ride.osm

import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.data.Http
import com.northwoodsadv.ride.geo.GeoPoint
import com.northwoodsadv.ride.geo.simplify
import java.util.Locale

/**
 * Fetches roads, streams, lakes and open land along a route from the OpenStreetMap Overpass API,
 * plus the buildings beside it and the landmarks worth a look (viewpoints, named boulders,
 * churches, historical markers, covered bridges come with the roads).
 */
class Overpass(private val cache: DiskCache) {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    fun buildQuery(route: List<GeoPoint>, radiusM: Int): String {
        // Overpass "around" accepts a polyline; keep it to a few hundred vertices.
        var tol = 25.0
        var line = simplify(route, tol)
        while (line.size > 350) { tol *= 1.6; line = simplify(route, tol) }
        val coords = line.joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        val near = "(around:$radiusM,$coords)"
        val wide = "(around:${radiusM * 2},$coords)"
        // Buildings and shops only matter where you can see them from the road.
        val close = "(around:${minOf(radiusM, 250)},$coords)"
        return """
            [out:json][timeout:180];
            (
              way["highway"]["highway"!~"^(footway|steps|cycleway|pedestrian|corridor|platform|construction|proposed|elevator|bus_stop|raceway|abandoned|razed|services|rest_area)$"]$near;
              way["waterway"~"^(river|stream|brook|canal)$"]$near;
              way["natural"="water"]$wide;
              relation["natural"="water"]$wide;
              way["landuse"~"^(meadow|farmland|grass|farmyard|orchard|recreation_ground)$"]$near;
              way["natural"~"^(grassland|heath|scrub|bare_rock|wetland|scree)$"]$near;
              way["building"]$close;
              nwr["tourism"~"^(viewpoint|attraction|museum|picnic_site)$"]$near;
              nwr["historic"]$near;
              nwr["natural"~"^(stone|rock|cave_entrance)$"]$near;
              nwr["waterway"="waterfall"]$near;
              nwr["natural"="waterfall"]$near;
              nwr["amenity"~"^(place_of_worship|townhall|library|post_office|fuel|cafe|restaurant|fast_food|pub|ice_cream)$"]$close;
              nwr["shop"~"^(general|convenience|country_store|farm)$"]$close;
              nwr["man_made"~"^(tower|lighthouse|water_tower|windmill)$"]$near;
              node["natural"="peak"]$wide;
            );
            out geom;
        """.trimIndent()
    }

    /** Returns parsed data; throws if every mirror fails and nothing is cached. */
    fun fetch(route: List<GeoPoint>, radiusM: Int = 900): OsmData {
        // Development: a saved Overpass response instead of the network.
        System.getProperty("nwadv.osmFile")?.let { return OsmData.parseOverpass(java.io.File(it).readText()) }
        val query = buildQuery(route, radiusM)
        val key = "osm/" + Integer.toHexString(query.hashCode()) + "_" + query.length + ".json"
        cache.read(key)?.let { return OsmData.parseOverpass(String(it, Charsets.UTF_8)) }
        var last: Exception? = null
        for (url in endpoints) {
            try {
                val bytes = Http.postForm(url, mapOf("data" to query), timeoutMs = 200_000)
                val data = OsmData.parseOverpass(String(bytes, Charsets.UTF_8))
                cache.write(key, bytes)
                return data
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No Overpass endpoint")
    }
}
