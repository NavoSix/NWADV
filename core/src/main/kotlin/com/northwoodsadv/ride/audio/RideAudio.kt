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
 * How an engine sounds: where in the 720-degree four-stroke cycle its cylinders fire, and the
 * muffler that shapes each exhaust pulse.
 */
class EngineSound(
    /** Firing angles (degrees of the 720-degree cycle). */
    val firingDeg: DoubleArray,
    /** Muffler resonance: base frequency (Hz) and how far it rises per Hz of crank speed. */
    val boomHz: Double,
    val boomPerRev: Double,
    /** Size of each exhaust pulse. */
    val pulse: Double,
    /** How long a pulse rings (s) at closed and open throttle. */
    val ringClosed: Double,
    val ringOpen: Double,
    /** Recorded firing cycles ([GrainBank] asset) to play instead of the synthesised pulse. */
    val grainAsset: String? = null,
    /** How much header/muffler resonance ("throat") is added to recordings, 0..1. */
    val throat: Double = 0.0,
    /** How readily the pipe pops on the overrun, 0..1. */
    val pops: Double = 0.5,
) {
    companion object {
        /** 270-degree crank parallel twin: the uneven lope of most mid-weight ADV twins. */
        val TWIN_270 = EngineSound(doubleArrayOf(0.0, 270.0), 90.0, 0.9, 1.0, 0.0075, 0.0035)
        /** Big air-cooled single: one deep thump every other revolution. */
        val SINGLE = EngineSound(doubleArrayOf(0.0), 68.0, 0.75, 1.45, 0.010, 0.005)
        /** Donovan's Himalayan 411, recorded holding idle, 1,900, 2,850, 4,000 and 4,800 rpm. */
        val HIMALAYAN_411 = EngineSound(doubleArrayOf(0.0), 68.0, 0.75, 1.45, 0.010, 0.005, "sounds/himalayan411.grains", throat = 0.65, pops = 1.0)
    }
}

/**
 * Ride sound on its own thread, following the physics.
 *
 * - Engine: on each cylinder firing ([EngineSound]) either one recorded firing cycle of the real
 *   bike is played ([GrainBank], blended between the recordings either side of the current rpm)
 *   or, for bikes without recordings, a synthesised exhaust pulse rings a resonant muffler. Load
 *   (throttle) adds bark, the overrun pops a little.
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
    @Volatile var engine = EngineSound.TWIN_270
        set(v) { field = v; grains = v.grainAsset?.let(::loadGrains) }
    @Volatile private var grains: GrainBank? = null
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
            synth.slip = slip.toDouble(); synth.surface = surface; synth.engine = engine; synth.grains = grains; synth.volume = if (muted) 0.0 else volume.toDouble()
            synth.fill(buf)
            try { dev.writeSamples(buf, 0, buf.size) } catch (e: Throwable) { break }
        }
    }

    private fun loadGrains(path: String): GrainBank? = try {
        GrainBank.parse(Gdx.files.internal(path).readBytes())
    } catch (e: Throwable) {
        Gdx.app?.error("RideAudio", "no engine recordings ($path), using the synthesised engine", e); null
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
    var engine = EngineSound.TWIN_270
    /** Recorded firing cycles; null plays the synthesised engine. */
    var grains: GrainBank? = null
    private val rnd = Random(7)

    /** One playing grain (a recorded firing cycle). */
    private class Voice { var g: FloatArray? = null; var pos = 0.0; var rate = 1.0; var gain = 0.0 }
    private val voices = Array(10) { Voice() }
    private val lastPick = IntArray(16) { -1 }

    private fun fireGrains(bank: GrainBank, r: Double, thr: Double) {
        val n = bank.rpms.size
        var lo = 0
        while (lo < n - 2 && r > bank.rpms[lo + 1]) lo++
        val w = if (n == 1) 0.0 else ((r - bank.rpms[lo]) / (bank.rpms[lo + 1] - bank.rpms[lo])).coerceIn(0.0, 1.0)
        // Neutral recordings are no-load: open throttle adds punch, the overrun goes softer.
        val load = if (thr < 0.05 && r > 2500) 0.6 else 0.75 + 0.6 * thr
        for ((b, weight) in listOf(lo to 1 - w, (lo + 1).coerceAtMost(n - 1) to w)) {
            if (weight < 0.02) continue
            val list = bank.grains[b]
            var pick = rnd.nextInt(list.size)
            if (pick == lastPick[b] && list.size > 1) pick = (pick + 1) % list.size
            lastPick[b] = pick
            // Above a bank's rpm its grains play a little faster (and overlap); below it they're
            // stretched to fill the longer cycle, so there's never a gap between firings.
            val ratio = r / bank.rpms[b]
            // The cycle's wander stretches its grain with it, so the pulse train has no gaps.
            val rate = (if (ratio >= 1) Math.pow(ratio, 0.6) else ratio) * cycleJitter * (1 + 0.03 * thr) * bank.sampleRate / this.rate
            // A free voice, or else the one nearest the end of its grain.
            val slot = voices.firstOrNull { it.g == null } ?: voices.maxByOrNull { it.pos / it.g!!.size }!!
            slot.g = list[pick]; slot.pos = 0.0; slot.rate = rate; slot.gain = weight * load
        }
    }

    private fun grainSample(): Double {
        var s = 0.0
        for (v in voices) {
            val g = v.g ?: continue
            val i = v.pos.toInt()
            if (i >= g.size - 2) { v.g = null; continue }
            // 4-point cubic (Catmull-Rom): linear interpolation would dull the exhaust crackle.
            val f = v.pos - i
            val y0 = if (i > 0) g[i - 1] else g[i]; val y1 = g[i]; val y2 = g[i + 1]; val y3 = g[i + 2]
            val c1 = 0.5 * (y2 - y0); val c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3; val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
            s += (((c3 * f + c2) * f + c1) * f + y1) * v.gain
            v.pos += v.rate
        }
        return s
    }
    private var crank = 0.0
    private var cycleJitter = 1.0
    // Exhaust body: header and muffler resonances, and a little high cut, for the recordings.
    private val bodyLow = Biquad(); private val bodyMid = Biquad()
    private var bodyLp = 0.0
    // Overrun pops: armed by a spell on the throttle, likeliest just after it closes.
    private var armed = 0.0
    private var overrunT = 10.0
    private var crackEnv = 0.0; private var crackLp = 0.0
    private var thumpEnv = 0.0; private var thumpPhase = 0.0; private var thumpHz = 120.0
    private var popDelay = -1; private var popDelayAmp = 0.0

    private fun pop(amp: Double) {
        crackEnv += amp
        thumpEnv += amp * 0.8
        thumpPhase = 0.0
        thumpHz = 100.0 + rnd.nextDouble() * 70.0
    }
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
            val eng = engine
            val bank = grains
            val f0 = eng.boomHz + r / 60.0 * eng.boomPerRev
            val w0 = 2 * PI * f0 / rate
            val q = 0.94
            val c1 = 2 * q * kotlin.math.cos(w0); val c2 = -q * q
            val decay = exp(-1.0 / (rate * (eng.ringOpen + (eng.ringClosed - eng.ringOpen) * (1 - thr))))
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
            // Exhaust body for recordings: a chesty header mode and a muffler mode that rises a
            // little with revs, both stronger under load.
            val throat = if (bank != null) eng.throat else 0.0
            if (throat > 0) {
                bodyLow.setPeak(rate, 95.0, 0.9, throat * (4.0 + 3.0 * thr))
                bodyMid.setPeak(rate, 200.0 + r / 60.0 * 0.8, 1.4, throat * (5.0 + 3.0 * thr))
            }
            // Pops: after some throttle, closing it above ~2,200 rpm lets unburnt fuel light off
            // in the pipe; most often right after the throttle shuts, dying away over seconds.
            val dt = buf.size.toDouble() / rate
            if (thr > 0.25) { armed = 1.0; overrunT = 0.0 } else if (thr < 0.06) overrunT += dt
            if (thr < 0.06) armed = (armed - dt * 0.12).coerceAtLeast(0.0)
            val popChance = if (thr < 0.06) eng.pops * 0.3 * armed * exp(-overrunT / 1.3) *
                ((r - 2200) / 1400).coerceIn(0.0, 1.0) else 0.0
            val crackDecay = exp(-1.0 / (rate * 0.0035))
            val thumpDecay = exp(-1.0 / (rate * 0.028))
            for (i in buf.indices) {
                crank += r / 60.0 * 360.0 / rate * cycleJitter // degrees of the 720-degree (two-revolution) cycle
                if (crank >= 720) crank -= 720
                val a = crank
                // Firing events.
                for (f in eng.firingDeg) {
                    val fired = if (f == 0.0) lastAngle > a else lastAngle < f && a >= f
                    if (fired) {
                        // Real engines don't fire like a metronome: each cycle runs a few percent
                        // fast or slow (most at idle), which is the single's lope.
                        if (f == eng.firingDeg[0]) cycleJitter = 1 + (rnd.nextDouble() * 2 - 1) * (0.03 - 0.02 * thr)
                        if (bank != null) fireGrains(bank, r, thr)
                        else pulse += (0.6 + 0.9 * thr + rnd.nextDouble() * 0.15) * eng.pulse
                        if (popChance > 0 && rnd.nextDouble() < popChance) {
                            pop(0.35 + 0.55 * rnd.nextDouble())
                            // Now and then a second crack right behind it: the crackle.
                            if (rnd.nextDouble() < 0.3) { popDelay = (rate * (0.008 + 0.02 * rnd.nextDouble())).toInt(); popDelayAmp = 0.2 + 0.4 * rnd.nextDouble() }
                        }
                    }
                }
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
                var s = if (bank != null) grainSample() * GRAIN_LEVEL else dcOut * 0.09 * (0.9 + 0.5 * thr)
                // Pops: a sharp crack (lightly filtered noise) on a short low thump.
                if (popDelay >= 0 && popDelay-- == 0) pop(popDelayAmp)
                if (crackEnv > 1e-4 || thumpEnv > 1e-4) {
                    crackLp += ((rnd.nextDouble() * 2 - 1) - crackLp) * 0.55
                    thumpPhase += 2 * PI * thumpHz / rate
                    s += crackLp * crackEnv * 0.9 + kotlin.math.sin(thumpPhase) * thumpEnv * 0.55
                    crackEnv *= crackDecay; thumpEnv *= thumpDecay
                }
                if (throat > 0) {
                    s = bodyMid.process(bodyLow.process(s))
                    // Take a little fizz off the top, then a touch of saturation for growl.
                    bodyLp += (s - bodyLp) * 0.35
                    s = bodyLp + (s - bodyLp) * (1 - 0.3 * throat)
                    val drive = 1 + 0.8 * throat
                    s = kotlin.math.tanh(s * drive) / drive * (1 - THROAT_TRIM * throat)
                }
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

    companion object {
        /** Recorded engine level in the mix (the recordings peak near 0.9). */
        const val GRAIN_LEVEL = 1.1
        /** Level taken back per unit of throat, so adding resonance doesn't just make it louder. */
        const val THROAT_TRIM = 0.28
    }
}
