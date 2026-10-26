package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.world.GroundSample
import com.northwoodsadv.ride.world.Landcover
import com.northwoodsadv.ride.world.Noise
import com.northwoodsadv.ride.world.Surface

/** Colours for ground surfaces. Alpha < 1 marks forest floor (see [WorldShader]). */
object Palette {
    const val SKY = 0x6a9ad4
    const val HAZE = 0xc4d4e0
    const val CANOPY = 0x2c4426

    fun ground(s: GroundSample, e: Double, n: Double, near: Boolean, light: Double = 1.0): Float {
        val jitter = (Noise.value2(e * 0.08, n * 0.08, 77) - 0.5) * 0.18
        var rgb = when (s.surface) {
            Surface.FOREST -> if (Noise.value2(e * 0.2, n * 0.2, 9) > 0.6) 0x5a5030 else 0x4a4a2a
            Surface.GRASS -> when (s.landcover) { Landcover.OPEN -> 0x7a9a48; Landcover.CLEARING -> 0x66703a; else -> 0x6a8a40 }
            else -> s.surface.color
        }
        if (s.grassStrip) rgb = 0x6a7a38
        if (s.puddle) rgb = 0x5a6e78
        if (s.surface == Surface.ROCK && s.road < 0) rgb = 0x8c8c86
        val forestFloor = s.surface == Surface.FOREST
        return shade(rgb, (1 + jitter) * light, if (forestFloor) 0f else 1f)
    }

    fun shade(rgb: Int, k: Double, alpha: Float = 1f): Float {
        val r = (((rgb shr 16) and 255) / 255.0 * k).coerceIn(0.0, 1.0).toFloat()
        val g = (((rgb shr 8) and 255) / 255.0 * k).coerceIn(0.0, 1.0).toFloat()
        val b = ((rgb and 255) / 255.0 * k).coerceIn(0.0, 1.0).toFloat()
        return MeshBuilder.color(r, g, b, alpha)
    }

    fun rgb(rgb: Int) = floatArrayOf(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
}
