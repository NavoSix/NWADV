package com.northwoodsadv.ride.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Recorded engine firing cycles, one bank per steady rpm the bike was recorded at (built by
 * tools/engine-samples/make_grains.py). The synth plays one grain per firing, blending the two
 * banks either side of the current rpm.
 */
class GrainBank(
    val sampleRate: Int,
    /** Bank rpms, ascending. */
    val rpms: DoubleArray,
    /** Recorded loudness of each bank (after the bank's overall gain). */
    val rms: DoubleArray,
    /** grains[bank][i] = samples of one firing cycle, -1..1. */
    val grains: Array<Array<FloatArray>>,
) {
    companion object {
        fun parse(bytes: ByteArray): GrainBank {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "NWGR") { "not a grain bank" }
            require(b.int == 1) { "unsupported grain bank version" }
            val sr = b.int
            val n = b.int
            val rpms = DoubleArray(n); val rms = DoubleArray(n)
            val grains = Array(n) { bank ->
                rpms[bank] = b.float.toDouble(); rms[bank] = b.float.toDouble()
                Array(b.int) {
                    val len = b.int
                    FloatArray(len) { b.short / 32767f }
                }
            }
            return GrainBank(sr, rpms, rms, grains)
        }
    }
}
