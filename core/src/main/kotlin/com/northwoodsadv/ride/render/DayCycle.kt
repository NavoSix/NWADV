package com.northwoodsadv.ride.render

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The time of day and what it does to the light: the sun's path over northern New Hampshire
 * (44 degrees north) for the season, a full moon opposite it at night, and the sky, haze and
 * ambient colours from noon through golden hour and dusk to night. [hours] runs at [speed] times
 * real time.
 */
class DayCycle(var hours: Double, var speed: Double = 1.0, dayOfYear: Int = 190) {
    /** Direction toward the light (sun, or the moon at night), render space: x east, y up, z south. */
    val lightDir = FloatArray(3)
    val lightColor = FloatArray(3)
    /** Multiplier for the sky and ground ambient light. */
    val ambient = FloatArray(3)
    val skyTop = FloatArray(3)
    val haze = FloatArray(3)
    val sunDir = FloatArray(3)
    val moonDir = FloatArray(3)
    /** 0 by day, 1 on a clear night. */
    var stars = 0f; private set
    /** Sun's altitude (sine): below zero after sunset. */
    var sunAltitude = 0.0; private set
    /** 0..1: how dark it is, for deciding when lights matter. */
    val darkness get() = (1 - smooth(-0.12, 0.10, sunAltitude)).toFloat()

    private val declination = Math.toRadians(23.44) * sin(2 * PI * (284 + dayOfYear) / 365.0)
    private val lat = Math.toRadians(44.1)

    init { update(0.0) }

    /** Advance the clock by [dt] real seconds and recompute the light. */
    fun update(dt: Double) {
        hours = (hours + dt * speed / 3600.0) % 24.0
        if (hours < 0) hours += 24.0
        val h = Math.toRadians(15.0 * (hours - 12.0))
        val sinAlt = sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(h)
        val alt = Math.asin(sinAlt.coerceIn(-1.0, 1.0))
        var az = acos(((sin(declination) - sinAlt * sin(lat)) / (cos(alt) * cos(lat))).coerceIn(-1.0, 1.0))
        if (h > 0) az = 2 * PI - az
        val east = sin(az) * cos(alt); val north = cos(az) * cos(alt)
        sunAltitude = sinAlt
        sunDir[0] = east.toFloat(); sunDir[1] = sinAlt.toFloat(); sunDir[2] = (-north).toFloat()
        // Full moon: opposite the sun, a little higher so it clears the trees.
        moonDir[0] = -sunDir[0]; moonDir[1] = (-sunDir[1] + 0.15f); moonDir[2] = -sunDir[2]
        norm(moonDir)

        val day = smooth(-0.04, 0.22, sinAlt)        // 0 at night .. 1 in full day
        val low = 1 - smooth(0.04, 0.45, sinAlt)      // warm light near the horizon
        val dusk = smooth(-0.16, 0.0, sinAlt) * (1 - smooth(0.0, 0.18, sinAlt)) // the glow around sunset
        val night = 1 - smooth(-0.16, -0.02, sinAlt)
        stars = night.toFloat()

        if (sinAlt > -0.03) {
            for (k in 0..2) lightDir[k] = sunDir[k]
            lightDir[1] = maxOf(lightDir[1], 0.06f); norm(lightDir)
            val warm = floatArrayOf(1.0f, 0.62f, 0.36f); val white = floatArrayOf(1.0f, 0.95f, 0.85f)
            val k = smooth(-0.03, 0.12, sinAlt).toFloat()
            for (i in 0..2) lightColor[i] = (warm[i] + (white[i] - warm[i]) * (1 - low.toFloat())) * k
        } else {
            for (k in 0..2) lightDir[k] = moonDir[k]
            val m = (smooth(-0.03, -0.15, sinAlt) * 0.9).toFloat()
            lightColor[0] = 0.13f * m; lightColor[1] = 0.16f * m; lightColor[2] = 0.24f * m
        }
        // Ambient: full by day, a deep blue at night.
        val nightAmb = floatArrayOf(0.14f, 0.16f, 0.26f)
        for (i in 0..2) ambient[i] = (nightAmb[i] + (1f - nightAmb[i]) * day.toFloat()) * (1f + 0.15f * dusk.toFloat() * if (i == 0) 1f else -0.3f)

        // Sky and haze: day blue, golden and rose at sunset, navy at night.
        mix3(skyTop, DAY_SKY, NIGHT_SKY, (1 - day).toFloat()); mixInto(skyTop, DUSK_SKY, (dusk * 0.6).toFloat())
        mix3(haze, DAY_HAZE, NIGHT_HAZE, (1 - day).toFloat()); mixInto(haze, DUSK_HAZE, (dusk * 0.85 + low * day * 0.25).toFloat())
    }

    /** "6:42 PM" or "18:42". */
    fun clock(twelveHour: Boolean): String {
        val m = ((hours * 60).toInt()) % (24 * 60)
        val hh = m / 60; val mm = m % 60
        if (!twelveHour) return "%02d:%02d".format(hh, mm)
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        return "%d:%02d %s".format(h12, mm, if (hh < 12) "AM" else "PM")
    }

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun norm(v: FloatArray) { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f); for (i in 0..2) v[i] /= l }
    private fun mix3(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t }
    private fun mixInto(out: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) out[i] = out[i] + (b[i] - out[i]) * t.coerceIn(0f, 1f) }

    companion object {
        private val DAY_SKY = Palette.rgb(Palette.SKY)
        private val DAY_HAZE = Palette.rgb(Palette.HAZE)
        private val NIGHT_SKY = floatArrayOf(0.015f, 0.025f, 0.07f)
        private val NIGHT_HAZE = floatArrayOf(0.035f, 0.045f, 0.08f)
        private val DUSK_SKY = floatArrayOf(0.28f, 0.30f, 0.52f)
        private val DUSK_HAZE = floatArrayOf(0.96f, 0.58f, 0.36f)

        /** Day of the year the sun follows: midsummer, or early October for the foliage. */
        fun dayOfYear(season: Season) = if (season == Season.FALL) 280 else 190
    }
}
