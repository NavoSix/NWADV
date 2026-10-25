package com.northwoodsadv.ride.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import com.badlogic.gdx.utils.Disposable
import com.northwoodsadv.ride.world.Surface
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthesised ride sound on its own thread: no samples to ship, and it follows the physics.
 *
 * - Engine: a 270-degree parallel twin fires at 0 and 270 degrees of a 720-degree cycle, giving
 *   the uneven "V-twin" lope of most mid-weight ADV bikes. Each firing is an exhaust pulse through
 *   a resonant muffler; load (throttle) adds bark, the overrun pops a little.
 * - Wind rises with speed squared; tyre scrub rises when the tyres slide.
 * - Tyres on the ground: a soft, low-passed roll that changes with the surface ([SurfaceSound]),
 *   plus filtered "grains" (gravel crunch, rock knocks, splashes). Nothing is full-bandwidth, so
 *   it reads as texture rather than static.
 */
class RideAudio : Disposable {
    @Volatile var rpm = 1300f
    @Volatile var throttle = 0f
    @Volatile var speed = 0f
    @Volatile var slip = 0f
    @Volatile var surface = SurfaceSound.PAVED
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
            synth.slip = slip.toDouble(); synth.surface = surface; synth.volume = if (muted) 0.0 else volume.toDouble()
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

/** How the tyres sound on a surface; levels are per unit of the speed term. */
enum class SurfaceSound(val roll: Double, val rollCut: Double, val grainRate: Double, val grain: Double, val grainCut: Double, val grainDecayMs: Double) {
    PAVED(0.05, 0.05, 0.0, 0.0, 0.2, 10.0),
    GRAVEL(0.06, 0.08, 0.012, 0.07, 0.22, 8.0),
    DIRT(0.08, 0.04, 0.004, 0.08, 0.10, 14.0),
    MUD(0.10, 0.025, 0.003, 0.12, 0.05, 30.0),
    ROCK(0.05, 0.05, 0.002, 0.18, 0.06, 25.0),
    GRASS(0.06, 0.035, 0.002, 0.05, 0.10, 12.0),
    WATER(0.10, 0.06, 0.003, 0.07, 0.12, 45.0),
    AIR(0.0, 0.05, 0.0, 0.0, 0.1, 10.0);

    companion object {
        fun of(s: Surface, airborne: Boolean): SurfaceSound = if (airborne) AIR else when (s) {
            Surface.ASPHALT, Surface.ASPHALT_WORN -> PAVED
            Surface.GRAVEL_PACKED, Surface.GRAVEL_LOOSE, Surface.SAND -> GRAVEL
            Surface.DIRT -> DIRT
            Surface.MUD -> MUD
            Surface.ROCK, Surface.WET_ROCK, Surface.LOG -> ROCK
            Surface.GRASS, Surface.FOREST -> GRASS
            Surface.WATER, Surface.DEEP_WATER -> WATER
        }
    }
}

/** The sound generator itself (no audio device), so it can be tested offline. */
class RideSynth(private val rate: Int) {
    var rpm = 1300.0; var throttle = 0.0; var speed = 0.0; var slip = 0.0; var volume = 0.8
    var surface = SurfaceSound.PAVED
    private val rnd = Random(7)
    private var crank = 0.0
    private var pulse = 0.0
    private var lpA = 0.0; private var bp1 = 0.0; private var bp2 = 0.0
    private var windLp = 0.0
    private var scrub1 = 0.0; private var scrub2 = 0.0; private var scrubHp = 0.0
    private var lastAngle = 0.0
    private var lastExcite = 0.0
    private var dcIn = 0.0; private var dcOut = 0.0
    // Tyre roll and grains, with parameters glided so a surface change isn't a click.
    private var roll1 = 0.0; private var roll2 = 0.0
    private var grainEnv = 0.0; private var grain1 = 0.0; private var grain2 = 0.0
    private var gRoll = 0.0; private var gRollCut = 0.05; private var gRate = 0.0; private var gGrain = 0.0
    private var gGrainCut = 0.1; private var gDecay = 10.0

    fun fill(buf: FloatArray) {
            val r = rpm.coerceIn(600.0, 11000.0)
            val thr = throttle
            val v = speed
            val sl = slip
            val vol = volume
            // Muffler resonance tracks rpm a little (bigger boom at low revs).
            val f0 = 90.0 + r / 60.0 * 0.9
            val w0 = 2 * PI * f0 / rate
            val q = 0.94
            val c1 = 2 * q * kotlin.math.cos(w0); val c2 = -q * q
            val decay = exp(-1.0 / (rate * (0.0035 + 0.004 * (1 - thr))))
            // Glide the surface sound over ~50 ms.
            val sf = surface
            val k = min(1.0, buf.size / (0.05 * rate))
            gRoll += (sf.roll - gRoll) * k; gRollCut += (sf.rollCut - gRollCut) * k
            gRate += (sf.grainRate - gRate) * k; gGrain += (sf.grain - gGrain) * k
            gGrainCut += (sf.grainCut - gGrainCut) * k; gDecay += (sf.grainDecayMs - gDecay) * k
            val vTerm = min(1.0, v / 12.0)
            // Two one-pole low-passes; scale back up so the level doesn't depend on the cutoff.
            val rollGain = gRoll * vTerm * 1.6 / sqrt(gRollCut)
            val grainDecay = exp(-1000.0 / (rate * gDecay))
            val grainP = gRate * min(1.0, v / 6.0) * (22050.0 / rate)
            val grainGain = gGrain / sqrt(gGrainCut)
            val scrubAmt = (sl - 0.9).coerceIn(0.0, 1.5) * min(1.0, v / 4) * 0.12
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
                // Overrun burble: a soft low pop rather than a click.
                if (thr < 0.05 && r > 3500 && rnd.nextDouble() < 0.0008) grainEnv += 0.4 * gGrainCut.coerceAtLeast(0.05)
                // Wind.
                val n = rnd.nextDouble() * 2 - 1
                windLp += (n - windLp) * (0.05 + min(v, 40.0) * 0.004)
                s += windLp * min(1.0, v * v / 1600.0) * 0.25
                // Tyre roll: low rumble whose colour depends on the surface.
                roll1 += (n - roll1) * gRollCut
                roll2 += (roll1 - roll2) * gRollCut
                s += roll2 * rollGain
                // Grains: little decaying bursts through a low-pass (crunch, knocks, splashes).
                if (grainP > 0 && rnd.nextDouble() < grainP) grainEnv += 0.5 + rnd.nextDouble()
                grainEnv *= grainDecay
                val n2 = rnd.nextDouble() * 2 - 1
                grain1 += (n2 * grainEnv - grain1) * gGrainCut
                grain2 += (grain1 - grain2) * gGrainCut
                s += grain2 * grainGain
                // Tyre scrub: a band of noise around 1 kHz, not hiss.
                scrub1 += (n - scrub1) * 0.3
                scrub2 += (scrub1 - scrub2) * 0.3
                scrubHp += (scrub2 - scrubHp) * 0.05
                s += (scrub2 - scrubHp) * scrubAmt * 2.5
                // Soft limiter rather than hard clipping.
                buf[i] = kotlin.math.tanh(s * vol).toFloat()
            }
    }
}
