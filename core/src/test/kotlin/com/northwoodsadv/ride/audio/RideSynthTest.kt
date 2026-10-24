package com.northwoodsadv.ride.audio

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertTrue

class RideSynthTest {
    private fun rms(rpm: Double, thr: Double): Double {
        val s = RideSynth(22050)
        s.rpm = rpm; s.throttle = thr; s.speed = 0.0
        val buf = FloatArray(512)
        repeat(20) { s.fill(buf) } // settle
        val xs = ArrayList<Float>()
        repeat(40) { s.fill(buf); xs.addAll(buf.toList()) }
        val mean = xs.average()
        // AC level only: a constant offset is silence to a speaker.
        val ac = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        val pinned = xs.count { kotlin.math.abs(it) > 0.98f }
        println("rpm=%5.0f thr=%.1f ac=%.4f mean=%.4f pinned=%d".format(rpm, thr, ac, mean, pinned))
        assertTrue(kotlin.math.abs(mean) < 0.05, "no DC offset at $rpm rpm")
        assertTrue(pinned < xs.size / 50, "not clipping at $rpm rpm")
        return ac
    }

    @Test fun engineIsAudibleAcrossTheRevRange() {
        for (thr in listOf(0.0, 0.5, 1.0)) {
            val idle = rms(1300.0, thr)
            for (rpm in listOf(2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7500.0, 9000.0)) {
                val v = rms(rpm, thr)
                assertTrue(v > idle * 0.5, "engine should not go quiet at $rpm rpm (rms $v vs idle $idle)")
            }
        }
    }
}
