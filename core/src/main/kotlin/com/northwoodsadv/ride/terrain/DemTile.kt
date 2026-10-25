package com.northwoodsadv.ride.terrain

import com.badlogic.gdx.graphics.Pixmap

/**
 * One 256x256 elevation tile. Heights are stored as decimetres in a ShortArray (128 KB per
 * tile), which covers -3276..3276 m; New Hampshire tops out at 1917 m on Mt Washington.
 */
class DemTile(val z: Int, val x: Int, val y: Int, private val dm: ShortArray) {
    fun heightAt(px: Int, py: Int): Float = dm[py * TileMath.TILE + px] * 0.1f

    companion object {
        /**
         * Decode a Mapzen/AWS "Terrarium" PNG: height = R*256 + G + B/256 - 32768 metres.
         * The US part of this dataset comes from USGS 3DEP (1/3 arc-second, ~10 m).
         */
        fun decodeTerrarium(z: Int, x: Int, y: Int, png: ByteArray): DemTile {
            val pm = Pixmap(png, 0, png.size)
            try {
                val n = TileMath.TILE
                require(pm.width == n && pm.height == n) { "Unexpected tile size ${pm.width}x${pm.height}" }
                val out = ShortArray(n * n)
                for (py in 0 until n) for (px in 0 until n) {
                    val c = pm.getPixel(px, py) // RGBA8888
                    val r = (c ushr 24) and 0xff
                    val g = (c ushr 16) and 0xff
                    val b = (c ushr 8) and 0xff
                    val h = r * 256.0 + g + b / 256.0 - 32768.0
                    out[py * n + px] = (h * 10).coerceIn(-32760.0, 32760.0).toInt().toShort()
                }
                return DemTile(z, x, y, out)
            } finally {
                pm.dispose()
            }
        }

        /** Builds a tile from a height function (tests / offline fallback). */
        fun synthetic(z: Int, x: Int, y: Int, f: (Int, Int) -> Double): DemTile {
            val n = TileMath.TILE
            return DemTile(z, x, y, ShortArray(n * n) { (f(it % n, it / n) * 10).toInt().toShort() })
        }
    }
}
