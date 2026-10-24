package com.northwoodsadv.ride.geo

import kotlin.math.cos

/**
 * Local east/north/up tangent-plane projection around an origin.
 *
 * The game world uses metres in ENU: x = east, y = north, z = up. Club rides span at most a
 * couple hundred kilometres, where an equirectangular projection about the route centre keeps
 * distance errors well under 0.5%, which is far below anything a rider can perceive.
 */
class LocalProjection(val origin: GeoPoint) {
    private val mPerDegLat = 111_132.954 - 559.822 * cos(2 * origin.lat * GeoPoint.DEG) +
        1.175 * cos(4 * origin.lat * GeoPoint.DEG)
    private val mPerDegLon = 111_412.84 * cos(origin.lat * GeoPoint.DEG) -
        93.5 * cos(3 * origin.lat * GeoPoint.DEG)

    fun east(lon: Double) = (lon - origin.lon) * mPerDegLon
    fun north(lat: Double) = (lat - origin.lat) * mPerDegLat
    fun lon(east: Double) = origin.lon + east / mPerDegLon
    fun lat(north: Double) = origin.lat + north / mPerDegLat

    fun toLocal(p: GeoPoint, out: DoubleArray = DoubleArray(2)): DoubleArray {
        out[0] = east(p.lon); out[1] = north(p.lat); return out
    }

    fun toGeo(east: Double, north: Double) = GeoPoint(lat(north), lon(east))
}
