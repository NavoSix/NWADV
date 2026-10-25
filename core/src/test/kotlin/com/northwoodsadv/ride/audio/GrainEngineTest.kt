package com.northwoodsadv.ride.audio

import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Himalayan's recorded engine: the bank loads, and plays cleanly across the rev range. */
class GrainEngineTest {
    private val bank = GrainBank.parse(File("../assets/sounds/himalayan411.grains").readBytes())

    @Test fun bankHasTheRecordedRpms() {
        println("banks: " + bank.rpms.indices.joinToString { "%.0f rpm (%d grains)".format(bank.rpms[it], bank.grains[it].size) })
        assertEquals(5, bank.rpms.size)
        assertTrue((1 until bank.rpms.size).all { bank.rpms[it] > bank.rpms[it - 1] }, "banks sorted by rpm")
        assertTrue(bank.rpms.first() in 1200.0..1500.0 && bank.rpms.last() in 4500.0..5100.0)
        assertTrue(bank.grains.all { it.size >= 20 })
    }

    private fun stats(rpm: Double, thr: Double): Pair<Double, Int> {
        val s = RideSynth(22050)
        s.engine = EngineSound.HIMALAYAN_411; s.grains = bank
        s.rpm = rpm; s.throttle = thr
        val buf = FloatArray(512)
        repeat(20) { s.fill(buf) }
        var e = 0.0; var sum = 0.0; var n = 0; var pinned = 0
        repeat(60) { s.fill(buf); for (x in buf) { e += x * x; sum += x; n++; if (abs(x) > 0.98f) pinned++ } }
        assertTrue(abs(sum / n) < 0.05, "no DC at $rpm rpm")
        return sqrt(e / n) to pinned
    }

    @Test fun recordedEnginePlaysAcrossTheRevRange() {
        for (thr in listOf(0.0, 0.5, 1.0)) for (rpm in listOf(1250.0, 1340.0, 1600.0, 2400.0, 3400.0, 4400.0, 5500.0, 6500.0, 7000.0)) {
            val (rms, pinned) = stats(rpm, thr)
            println("himalayan recorded rpm=%5.0f thr=%.1f rms=%.3f pinned=%d".format(rpm, thr, rms, pinned))
            assertTrue(rms > 0.03, "audible at $rpm rpm")
            assertTrue(pinned < 22050 * 60 / 512 / 50, "not clipping at $rpm rpm")
        }
    }

    /** NWADV_WAV=<file>: render a ride-like sweep to listen to. */
    @Test fun renderSweep() {
        val out = System.getenv("NWADV_WAV") ?: return
        val sr = 22050
        val s = RideSynth(sr)
        s.engine = EngineSound.HIMALAYAN_411; s.grains = bank
        val samples = ArrayList<Float>()
        val buf = FloatArray(256)
        val total = 22.0
        var t = 0.0
        while (t < total) {
            // Idle, the recorded holds, a full-throttle pull to the limiter, then roll off.
            val (rpm, thr) = when {
                t < 3 -> 1340.0 to 0.0
                t < 5 -> 1900.0 to 0.2
                t < 7 -> 2850.0 to 0.3
                t < 9 -> 4000.0 to 0.4
                t < 11 -> 4800.0 to 0.5
                t < 12 -> 4800.0 - (t - 11) * 3000 to 0.0
                t < 17 -> 1500.0 + (t - 12) / 5 * 5300 to 1.0
                t < 21 -> 6800.0 - (t - 17) / 4 * 5400 to 0.0
                else -> 1340.0 to 0.0
            }
            s.rpm = rpm; s.throttle = thr; s.speed = 0.0
            s.fill(buf); samples.addAll(buf.toList()); t += buf.size / sr.toDouble()
        }
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            val data = samples.size * 2
            f.write("RIFF".toByteArray()); f.write(le32(36 + data)); f.write("WAVEfmt ".toByteArray())
            f.write(le32(16)); f.write(le16(1)); f.write(le16(1)); f.write(le32(sr)); f.write(le32(sr * 2)); f.write(le16(2)); f.write(le16(16))
            f.write("data".toByteArray()); f.write(le32(data))
            val bytes = ByteArray(data)
            for ((i, x) in samples.withIndex()) { val v = (x.coerceIn(-1f, 1f) * 32767).toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
            f.write(bytes)
        }
        println("wrote $out")
    }
}
