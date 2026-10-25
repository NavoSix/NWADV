package com.northwoodsadv.ride.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Peaking EQ (RBJ cookbook): boosts a band around [setPeak]'s frequency, for exhaust resonances. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun setPeak(sampleRate: Int, hz: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * hz / sampleRate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0; b1 = -2 * cos(w) / a0; b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w) / a0; a2 = (1 - alpha / a) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}
