package com.northwoodsadv.ride.audio

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertTrue

class RideSynthTest {
    private fun rms(rpm: Double, thr: Double, engine: EngineSound = EngineSound.TWIN_270): Double {
        val s = RideSynth(22050)
        s.engine = engine
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

    @Test fun singleIsAudibleAcrossItsRevRange() {
        for (thr in listOf(0.0, 0.5, 1.0)) {
            val idle = rms(1250.0, thr, EngineSound.SINGLE)
            for (rpm in listOf(2000.0, 3000.0, 4500.0, 6000.0, 7000.0)) {
                val v = rms(rpm, thr, EngineSound.SINGLE)
                assertTrue(v > idle * 0.5, "single should not go quiet at $rpm rpm (rms $v vs idle $idle)")
            }
        }
    }

    /** Surface noise should be soft texture: most of its energy well below the hiss band. */
    @Test fun surfaceSoundsAreNotHarsh() {
        for (sf in SurfaceSound.entries) {
            val s = RideSynth(22050)
            s.rpm = 600.0; s.throttle = 0.0; s.speed = 15.0; s.surface = sf
            val buf = FloatArray(512)
            repeat(20) { s.fill(buf) }
            var e = 0.0; var d = 0.0; var last = 0f; var n = 0
            repeat(80) { s.fill(buf); for (x in buf) { e += x * x; d += (x - last) * (x - last); last = x; n++ } }
            val rms = sqrt(e / n); val hf = sqrt(d / e)
            println("%-7s rms=%.4f hf=%.3f".format(sf, rms, hf))
            assertTrue(hf < 0.6, "$sf surface sound is too hissy ($hf)")
            assertTrue(rms < 0.25, "$sf surface sound is too loud ($rms)")
        }
    }
}
