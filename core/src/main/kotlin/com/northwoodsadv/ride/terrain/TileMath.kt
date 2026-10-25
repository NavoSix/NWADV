package com.northwoodsadv.ride.terrain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web-mercator slippy tile maths (256 px tiles). */
object TileMath {
    const val TILE = 256

    fun worldPx(z: Int) = TILE * (1 shl z).toDouble()

    fun lonToPx(lon: Double, z: Int) = (lon + 180.0) / 360.0 * worldPx(z)

    fun latToPx(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldPx(z)
    }

    fun pxToLon(px: Double, z: Int) = px / worldPx(z) * 360.0 - 180.0
    fun pxToLat(py: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * py / worldPx(z)))))

    fun tileX(lon: Double, z: Int) = floor(lonToPx(lon, z) / TILE).toInt()
    fun tileY(lat: Double, z: Int) = floor(latToPx(lat, z) / TILE).toInt()

    /** Ground metres per pixel at a latitude. */
    fun metersPerPx(lat: Double, z: Int) = 2 * PI * 6_378_137.0 * cos(Math.toRadians(lat)) / worldPx(z)

    fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()
}
