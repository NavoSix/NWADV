package com.northwoodsadv.ride

import java.util.Locale

/**
 * A route ridden part way: where the bike was (as latitude/longitude, so it doesn't depend on
 * the map projection), which way it faced, how far along the route and the odometer. [routeHash]
 * identifies the GPX contents, so an edited file starts afresh.
 */
class RideSave(
    val lat: Double, val lon: Double, val heading: Double,
    val along: Double, val odometer: Double, val routeHash: Int,
) {
    fun format() = String.format(Locale.US, "1;%.7f;%.7f;%.4f;%.1f;%.1f;%d", lat, lon, heading, along, odometer, routeHash)

    companion object {
        fun parse(s: String?): RideSave? {
            val f = s?.split(";") ?: return null
            if (f.size != 7 || f[0] != "1") return null
            return try {
                RideSave(f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toInt())
            } catch (_: NumberFormatException) { null }
        }

        /** The name rides are saved under: the route's file name without ".gpx", as the route list stores it. */
        fun key(name: String) = name.removeSuffix(".gpx").replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)
    }
}
