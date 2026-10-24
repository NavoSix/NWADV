package com.northwoodsadv.ride.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * Synthesised ride sound on its own thread: no samples to ship, and it follows the physics.
 *
 * - Engine: a 270-degree parallel twin fires at 0 and 270 degrees of a 720-degree cycle, giving
 *   the uneven "V-twin" lope of most mid-weight ADV bikes. Each firing is an exhaust pulse through
 *   a resonant muffler; load (throttle) adds bark, the overrun pops a little.
 * - Wind rises with speed squared; tyre scrub rises when the tyres slide; gravel crunch rattles
 *   on loose surfaces.
 */
class RideAudio : Disposable {
    @Volatile var rpm = 1300f
    @Volatile var throttle = 0f
    @Volatile var speed = 0f
    @Volatile var slip = 0f
    @Volatile var loose = 0f
    @Volatile var volume = 0.8f
    @Volatile var muted = false

    private val rate = 22050
    private var device: AudioDevice? = null
    @Volatile private var running = true
    private val thread: Thread?

    init {
        device = try { Gdx.audio.newAudioDevice(rate, true) } catch (e: Throwable) { null }
        thread = if (device == null) null else Thread(::loop, "ride-audio").apply { isDaemon = true; start() }
    }

    private fun loop() {
        val buf = FloatArray(512)
        val synth = RideSynth(rate)
        val dev = device ?: return
        while (running) {
            synth.rpm = rpm.toDouble(); synth.throttle = throttle.toDouble(); synth.speed = speed.toDouble()
            synth.slip = slip.toDouble(); synth.loose = loose.toDouble(); synth.volume = if (muted) 0.0 else volume.toDouble()
            synth.fill(buf)
            try { dev.writeSamples(buf, 0, buf.size) } catch (e: Throwable) { break }
        }
    }

    override fun dispose() {
        running = false
        thread?.join(200)
        try { device?.dispose() } catch (_: Throwable) {}
    }

}

/** The sound generator itself (no audio device), so it can be tested offline. */
class RideSynth(private val rate: Int) {
    var rpm = 1300.0; var throttle = 0.0; var speed = 0.0; var slip = 0.0; var loose = 0.0; var volume = 0.8
    private val rnd = Random(7)
    private var crank = 0.0
    private var pulse = 0.0
    private var lpA = 0.0; private var bp1 = 0.0; private var bp2 = 0.0
    private var windLp = 0.0; private var scrubLp = 0.0
    private var lastAngle = 0.0
    private var lastExcite = 0.0
    private var dcIn = 0.0; private var dcOut = 0.0

    fun fill(buf: FloatArray) {
            val r = rpm.coerceIn(600.0, 11000.0)
            val thr = throttle
            val v = speed
            val sl = slip
            val lo = loose
            val vol = volume
            // Muffler resonance tracks rpm a little (bigger boom at low revs).
            val f0 = 90.0 + r / 60.0 * 0.9
            val w0 = 2 * PI * f0 / rate
            val q = 0.94
            val c1 = 2 * q * kotlin.math.cos(w0); val c2 = -q * q
            val decay = exp(-1.0 / (rate * (0.0035 + 0.004 * (1 - thr))))
            for (i in buf.indices) {
                crank += r / 60.0 * 720.0 / rate // degrees of the 720-degree cycle
                if (crank >= 720) crank -= 720
                val a = crank
                // Firing events at 0 and 270 degrees.
                if ((lastAngle > a) || (lastAngle < 270 && a >= 270)) pulse += 0.6 + 0.9 * thr + rnd.nextDouble() * 0.15
                lastAngle = a
                pulse *= decay
                val excite = pulse * (0.7 + 0.3 * rnd.nextDouble())
                // Resonant body, driven by the pressure *change* so it has no DC build-up.
                val y = (excite - lastExcite) + c1 * bp1 + c2 * bp2
                lastExcite = excite
                bp2 = bp1; bp1 = y
                lpA += (y - lpA) * 0.35
                // DC blocker, then level.
                dcOut = lpA - dcIn + 0.995 * dcOut; dcIn = lpA
                var s = dcOut * 0.09 * (0.9 + 0.5 * thr)
                // Overrun crackle.
                if (thr < 0.05 && r > 3500 && rnd.nextDouble() < 0.0008) s += (rnd.nextDouble() - 0.5) * 0.6
                // Wind.
                val n = rnd.nextDouble() * 2 - 1
                windLp += (n - windLp) * (0.05 + min(v, 40.0) * 0.004)
                s += windLp * min(1.0, v * v / 1600.0) * 0.25
                // Tyre scrub (filtered noise) and gravel crunch (clicks).
                scrubLp += (n - scrubLp) * 0.25
                s += scrubLp * (sl - 0.9).coerceIn(0.0, 1.5) * min(1.0, v / 4) * 0.25
                if (lo > 0 && rnd.nextDouble() < min(v, 25.0) * 0.003 * lo) s += (rnd.nextDouble() - 0.5) * 0.25
                // Soft limiter rather than hard clipping.
                buf[i] = kotlin.math.tanh(s * vol).toFloat()
            }
    }
}
