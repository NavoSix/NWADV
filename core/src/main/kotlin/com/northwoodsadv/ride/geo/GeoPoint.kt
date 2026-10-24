package com.northwoodsadv.ride.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position. Elevation is in metres and NaN when unknown. */
data class GeoPoint(val lat: Double, val lon: Double, val ele: Double = Double.NaN) {
    fun distanceTo(o: GeoPoint): Double {
        val dLat = Math.toRadians(o.lat - lat)
        val dLon = Math.toRadians(o.lon - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val EARTH_RADIUS = 6_371_008.8
        const val DEG = PI / 180.0
    }
}

/** Axis aligned lat/lon box. */
data class GeoBounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun expandMeters(m: Double): GeoBounds {
        val dLat = m / 111_320.0
        val dLon = m / (111_320.0 * cos(Math.toRadians((minLat + maxLat) / 2)))
        return GeoBounds(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    val center get() = GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2)

    companion object {
        fun of(points: Iterable<GeoPoint>): GeoBounds {
            var a = 90.0; var b = 180.0; var c = -90.0; var d = -180.0
            for (p in points) {
                if (p.lat < a) a = p.lat; if (p.lat > c) c = p.lat
                if (p.lon < b) b = p.lon; if (p.lon > d) d = p.lon
            }
            return GeoBounds(a, b, c, d)
        }
    }
}
