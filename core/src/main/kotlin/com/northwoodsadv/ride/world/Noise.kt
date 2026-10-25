package com.northwoodsadv.ride.world

import kotlin.math.floor

/** Deterministic hashing and smooth value noise. Everything procedural is reproducible from position. */
object Noise {
    fun hash(a: Int, b: Int, seed: Int): Int {
        var h = seed * -0x61c88647 + a * 0x27d4eb2d + b * 0x165667b1
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = (h xor (h ushr 12)) * 0x297a2d39
        return h xor (h ushr 15)
    }

    /** Uniform in [0, 1). */
    fun rand(a: Int, b: Int, seed: Int) = (hash(a, b, seed) ushr 8) / 16_777_216.0

    private fun fade(t: Double) = t * t * (3 - 2 * t)

    /** Smooth 1D value noise in [0, 1]. */
    fun value1(x: Double, seed: Int): Double {
        val i = floor(x).toInt(); val t = fade(x - i)
        return rand(i, 0, seed) * (1 - t) + rand(i + 1, 0, seed) * t
    }

    /** Smooth 2D value noise in [0, 1]. */
    fun value2(x: Double, y: Double, seed: Int): Double {
        val i = floor(x).toInt(); val j = floor(y).toInt()
        val tx = fade(x - i); val ty = fade(y - j)
        val a = rand(i, j, seed); val b = rand(i + 1, j, seed)
        val c = rand(i, j + 1, seed); val d = rand(i + 1, j + 1, seed)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }

    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
