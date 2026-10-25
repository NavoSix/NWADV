package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.physics.BikeSpec

/** Where the rider's hips, feet and hands go on a particular bike (model space, metres). */
class RiderFit(
    val hipX: Float, val hipY: Float,
    val pegX: Float, val pegY: Float,
    val handX: Float, val handY: Float, val handZ: Float,
)

/**
 * How one motorcycle looks. Model space: +X forward, +Y up, +Z to the rider's right, origin on
 * the ground below the centre of mass at static sag; front axle at (cgToFront, frontRadius),
 * rear axle at (-cgToRear, rearRadius).
 *
 * Parts are split by how they move: the chassis (frame-mounted), the front end that steers
 * (built in steering-head space, origin at the top of the head), the fork lowers that also slide
 * with the suspension (built around the front axle), and the wheels (which also spin).
 */
interface BikeLook {
    /** Top of the steering head (model space) and the rake of the steering axis. */
    val headX: Float
    val headY: Float
    val rakeDeg: Double
    val rider: RiderFit

    fun chassis(m: MeshBuilder, spec: BikeSpec)
    /** Translucent frame-mounted parts (windscreen), drawn blended. */
    fun glass(m: MeshBuilder, spec: BikeSpec) {}
    /** Steering parts in head space; (ax, ay) is the front axle in head space at static sag. */
    fun front(m: MeshBuilder, spec: BikeSpec, ax: Float, ay: Float)
    /** Fork lowers, fender and caliper around the front axle; (ux, uy) points up the fork. */
    fun frontLower(m: MeshBuilder, spec: BikeSpec, ux: Float, uy: Float) {}
    fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean)
}

/** The original stand-in: a generic mid-weight adventure twin with aluminium panniers. */
class GenericTwinLook : BikeLook {
    override val headX = 0.52f
    override val headY = 1.0f
    override val rakeDeg = 27.0
    override val rider = RiderFit(-0.28f, 0.98f, -0.03f, 0.40f, 0.42f, 1.12f, 0.36f)

    private val red = MeshBuilder.color(0xd8282e)
    private val dark = MeshBuilder.color(0x4a4a50)
    private val black = MeshBuilder.color(0x141414)
    private val alu = MeshBuilder.color(0xc8cace)
    private val grey = MeshBuilder.color(0x76787c)

    override fun chassis(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        m.box(0.05f, 0.48f, 0f, 0.30f, 0.17f, 0.15f, dark)                  // engine
        m.box(0.22f, 0.88f, 0f, 0.22f, 0.10f, 0.17f, red)                   // tank
        m.box(-0.28f, 0.93f, 0f, 0.32f, 0.04f, 0.13f, black)                // seat
        m.box(-0.62f, 0.95f, 0f, 0.12f, 0.03f, 0.14f, grey)                 // rack
        m.box(-0.55f, 0.66f, 0.33f, 0.24f, 0.17f, 0.085f, alu)              // panniers
        m.box(-0.55f, 0.66f, -0.33f, 0.24f, 0.17f, 0.085f, alu)
        m.beam(0.48f, 0.95f, 0f, -0.05f, 0.62f, 0f, 0.05f, dark)            // frame spine
        m.beam(-0.02f, 0.42f, 0.09f, -b, rr, 0.09f, 0.035f, grey)            // swingarm
        m.beam(-0.02f, 0.42f, -0.09f, -b, rr, -0.09f, 0.035f, grey)
        m.beam(-0.10f, 0.55f, 0f, -0.45f, 0.90f, 0f, 0.04f, dark)           // subframe
        m.box(0.20f, 0.30f, 0f, 0.22f, 0.03f, 0.13f, grey)                  // bash plate
    }

    override fun front(m: MeshBuilder, spec: BikeSpec, ax: Float, ay: Float) {
        val rf = spec.frontRadius.toFloat()
        for (side in listOf(-1f, 1f)) m.beam(0f, 0f, side * 0.1f, ax, ay, side * 0.1f, 0.03f, MeshBuilder.color(0x8a6a2a))
        m.beam(-0.06f, 0.12f, -0.38f, -0.06f, 0.12f, 0.38f, 0.016f, black)                 // handlebar
        m.box(0.10f, -0.05f, 0f, 0.06f, 0.10f, 0.12f, MeshBuilder.color(0xeeeedd))        // headlight
        m.box(0.04f, 0.20f, 0f, 0.02f, 0.18f, 0.16f, MeshBuilder.color(0x8fa4b0))         // screen
        m.box(ax + 0.02f, ay + rf + 0.06f, 0f, 0.20f, 0.012f, 0.07f, red)                   // fender
    }

    override fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean) {
        val r = (if (front) spec.frontRadius else spec.rearRadius).toFloat()
        m.wheel(0f, 0f, 0f, r, 0.065f, 18, MeshBuilder.color(0x3a3a3a), MeshBuilder.color(0x1a1a1a))
        m.wheel(0f, 0f, 0f, r * 0.62f, 0.07f, 12, MeshBuilder.color(0x8a8a8a), MeshBuilder.color(0x6a6a6a))
    }
}

/**
 * Royal Enfield Himalayan 411 in Rock Red, as the owner's bike is set up: tall clear touring
 * screen over the silver headlight cowl, phone mount above the round speedo, Tusk handguards,
 * braced bars, black tank guards with Royal Enfield plates, engine crash bars and skid plate,
 * black pannier frames and rear rack (no boxes), knobby tyres on black spoked rims, the stock
 * muffler with its stainless heat shield, and a gold chain.
 */
class HimalayanLook : BikeLook {
    // Trail 111 mm at 26.5 deg rake: the steering axis meets the ground 0.111 m ahead of the
    // front contact patch, and the head sits 0.95 m up that line.
    override val headX = 0.43f
    override val headY = 0.95f
    override val rakeDeg = 26.5
    override val rider = RiderFit(-0.20f, 0.85f, 0.0f, 0.30f, 0.39f, 1.07f, 0.34f)

    private val red = MeshBuilder.color(0xb3121a)
    private val gloss = MeshBuilder.color(0x121214)
    private val frame = MeshBuilder.color(0x1b1b1c)
    private val engine = MeshBuilder.color(0x252527)
    private val fin = MeshBuilder.color(0x3a3a3d)
    private val silver = MeshBuilder.color(0xb4b8bc)
    private val chrome = MeshBuilder.color(0xd8dadd)
    private val header = MeshBuilder.color(0x8c6c42)
    private val muffler = MeshBuilder.color(0x222224)
    private val steel = MeshBuilder.color(0xd2d4d6)
    private val seat = MeshBuilder.color(0x171717)
    private val panel = MeshBuilder.color(0x2e2e31)
    private val letters = MeshBuilder.color(0xc6c8ca)
    private val amber = MeshBuilder.color(0xe8890e)
    private val tail = MeshBuilder.color(0xa80e10)
    private val plate = MeshBuilder.color(0xe4ece2)
    private val chain = MeshBuilder.color(0xb48c3c)
    private val cowl = MeshBuilder.color(0x9a9ea3)
    private val lens = MeshBuilder.color(0xf2f0e4)
    private val tyre = MeshBuilder.color(0x1c1c1c)
    private val knob = MeshBuilder.color(0x232323)
    private val spoke = MeshBuilder.color(0xc4c6c8)
    private val tusk = MeshBuilder.color(0xc81e1e)
    private val gauge = MeshBuilder.color(0xd8d8d0)
    private val disc = MeshBuilder.color(0xcfd2d5)
    private val screenGlass = MeshBuilder.color(0.80f, 0.86f, 0.90f, 0.28f)

    private fun st(x: Float, cy: Float, hy: Float, hz: Float) = floatArrayOf(x, cy, hy, hz)

    override fun chassis(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        val t = 0.016f

        // --- frame (half-duplex split cradle, black) ---------------------------------------
        for (s in listOf(-1f, 1f)) {
            m.beam(headX, headY - 0.02f, 0f, -0.03f, 0.74f, s * 0.08f, t, frame)          // top rails
            m.beam(headX + 0.05f, headY - 0.12f, 0f, 0.37f, 0.40f, s * 0.07f, t, frame)  // down tubes
            m.beam(0.37f, 0.40f, s * 0.07f, 0.34f, 0.22f, s * 0.08f, t, frame)
            m.beam(0.34f, 0.22f, s * 0.08f, -0.03f, 0.24f, s * 0.09f, t, frame)          // cradle
            m.beam(-0.03f, 0.24f, s * 0.09f, -0.04f, 0.76f, s * 0.09f, t, frame)         // seat posts
            m.beam(-0.04f, 0.76f, s * 0.09f, -0.66f, 0.82f, s * 0.10f, t * 0.85f, frame) // subframe
            m.beam(-0.04f, 0.44f, s * 0.10f, -0.52f, 0.80f, s * 0.10f, t * 0.85f, frame)
            // Swingarm (box section) and pivot.
            m.beam(-0.06f, 0.38f, s * 0.10f, -b, rr, s * 0.10f, 0.024f, frame)
        }
        m.beam(-0.06f, 0.38f, -0.12f, -0.06f, 0.38f, 0.12f, 0.018f, silver)              // pivot bolt

        // --- engine: 411 cc air-cooled single ----------------------------------------------
        m.loft(arrayOf(st(0.33f, 0.33f, 0.10f, 0.10f), st(0.29f, 0.32f, 0.13f, 0.125f), st(0.03f, 0.32f, 0.13f, 0.125f), st(-0.03f, 0.34f, 0.09f, 0.10f)), engine, 12, 0.65f)
        m.box(0.22f, 0.56f, 0f, 0.065f, 0.12f, 0.07f, engine)                             // barrel
        for (k in 0..6) {
            val y = 0.47f + k * 0.032f
            m.box(0.20f + (y - 0.45f) * 0.25f, y, 0f, 0.088f, 0.008f, 0.098f, if (k % 2 == 0) fin else engine)
        }
        m.box(0.26f, 0.70f, 0f, 0.085f, 0.03f, 0.092f, fin)                               // head
        m.tube(0.12f, 0.31f, -0.126f, 0.12f, 0.31f, -0.142f, 0.065f, 0.065f, 14, silver, silver)  // RE logo cover
        m.tube(0.12f, 0.31f, -0.142f, 0.12f, 0.31f, -0.146f, 0.045f, 0.045f, 14, engine, engine)
        m.tube(0.16f, 0.30f, 0.126f, 0.16f, 0.30f, 0.140f, 0.07f, 0.07f, 14, engine, engine)     // clutch cover
        m.box(0.38f, 0.60f, 0f, 0.014f, 0.055f, 0.085f, MeshBuilder.color(0x2c2c2c))      // oil cooler
        m.box(0.0f, 0.42f, 0f, 0.05f, 0.08f, 0.09f, engine)                               // airbox / gearbox top

        // --- tank: red front, black rear with a silver stripe between ------------------------
        m.loft(arrayOf(st(0.50f, 0.85f, 0.05f, 0.08f), st(0.46f, 0.86f, 0.10f, 0.15f), st(0.38f, 0.865f, 0.115f, 0.18f), st(0.29f, 0.87f, 0.11f, 0.18f)), red, 14, 0.45f)
        m.loft(arrayOf(st(0.30f, 0.87f, 0.115f, 0.185f), st(0.284f, 0.87f, 0.115f, 0.185f)), MeshBuilder.color(0x86888c), 14, 0.45f)
        m.loft(arrayOf(st(0.285f, 0.87f, 0.11f, 0.181f), st(0.18f, 0.875f, 0.105f, 0.17f), st(0.08f, 0.875f, 0.09f, 0.15f), st(0.02f, 0.87f, 0.06f, 0.12f)), gloss, 14, 0.45f)
        m.cone(0.24f, 0.975f, 0f, 0.048f, 0.044f, 0.012f, 12, MeshBuilder.color(0x2a2a2a))  // fuel cap

        // --- seat: low rider seat, raised pillion ------------------------------------------
        m.loft(arrayOf(st(0.03f, 0.78f, 0.035f, 0.11f), st(-0.06f, 0.765f, 0.04f, 0.15f), st(-0.30f, 0.765f, 0.04f, 0.155f), st(-0.40f, 0.78f, 0.045f, 0.13f)), seat, 12, 0.6f)
        m.loft(arrayOf(st(-0.40f, 0.81f, 0.04f, 0.12f), st(-0.48f, 0.825f, 0.035f, 0.12f), st(-0.64f, 0.83f, 0.035f, 0.11f), st(-0.68f, 0.82f, 0.03f, 0.09f)), seat, 12, 0.6f)

        // --- side panels with HIMALAYAN lettering ------------------------------------------
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.13f
            m.twoSidedTri(-0.05f, 0.72f, z, -0.38f, 0.74f, z, -0.10f, 0.44f, z, panel)
            m.box(-0.14f, 0.64f, s * 0.133f, 0.085f, 0.011f, 0.002f, letters)
        }

        // --- exhaust (right side): heat-coloured header, black muffler, steel shield ---------
        val hp = arrayOf(floatArrayOf(0.30f, 0.66f, 0.05f), floatArrayOf(0.39f, 0.48f, 0.08f), floatArrayOf(0.37f, 0.25f, 0.10f),
            floatArrayOf(0.02f, 0.22f, 0.12f), floatArrayOf(-0.20f, 0.30f, 0.17f), floatArrayOf(-0.30f, 0.38f, 0.195f))
        for (k in 0 until hp.size - 1) m.tube(hp[k][0], hp[k][1], hp[k][2], hp[k + 1][0], hp[k + 1][1], hp[k + 1][2], 0.022f, 0.022f, 8, header, header)
        m.tube(-0.28f, 0.39f, 0.21f, -0.74f, 0.55f, 0.21f, 0.078f, 0.072f, 14, muffler, muffler)
        m.tube(-0.74f, 0.55f, 0.21f, -0.79f, 0.567f, 0.21f, 0.055f, 0.05f, 14, steel, MeshBuilder.color(0x2a2a2a))
        m.tube(-0.55f, 0.49f, 0.215f, -0.75f, 0.56f, 0.215f, 0.086f, 0.082f, 14, steel, steel)    // perforated heat shield
        m.tube(-0.32f, 0.405f, 0.21f, -0.28f, 0.39f, 0.21f, 0.06f, 0.079f, 12, steel, steel)   // inlet cone

        // --- crash bars, skid plate, tank guards -------------------------------------------
        for (s in listOf(-1f, 1f)) {
            val cb = arrayOf(floatArrayOf(0.40f, 0.66f, 0.09f), floatArrayOf(0.43f, 0.50f, 0.23f), floatArrayOf(0.39f, 0.27f, 0.22f),
                floatArrayOf(0.12f, 0.24f, 0.21f), floatArrayOf(0.0f, 0.40f, 0.16f), floatArrayOf(-0.03f, 0.50f, 0.10f))
            for (k in 0 until cb.size - 1) m.beam(cb[k][0], cb[k][1], s * cb[k][2], cb[k + 1][0], cb[k + 1][1], s * cb[k + 1][2], 0.013f, frame)
            m.beam(0.42f, 0.46f, s * 0.225f, 0.10f, 0.44f, s * 0.21f, 0.012f, frame)
            // Tank guards: a tube frame around the front of the tank (jerry-can mounts).
            val tg = arrayOf(floatArrayOf(headX + 0.01f, headY - 0.03f, 0.08f), floatArrayOf(0.47f, 0.90f, 0.21f), floatArrayOf(0.46f, 0.64f, 0.225f),
                floatArrayOf(0.30f, 0.60f, 0.20f), floatArrayOf(0.27f, 0.74f, 0.12f))
            for (k in 0 until tg.size - 1) m.beam(tg[k][0], tg[k][1], s * tg[k][2], tg[k + 1][0], tg[k + 1][1], s * tg[k + 1][2], 0.012f, frame)
            m.beam(0.46f, 0.78f, s * 0.22f, 0.29f, 0.70f, s * 0.20f, 0.011f, frame)
            m.box(0.40f, 0.80f, s * 0.22f, 0.075f, 0.026f, 0.004f, frame)                 // Royal Enfield plate
            m.box(0.40f, 0.80f, s * 0.225f, 0.06f, 0.008f, 0.002f, letters)
            // Front turn signals on the guards.
            m.beam(0.47f, 0.90f, s * 0.21f, 0.50f, 0.90f, s * 0.25f, 0.008f, frame)
            m.blob(0.52f, 0.90f, s * 0.26f, 0.032f, 0.026f, 0.026f, amber)
            // Footpegs.
            m.box(rider.pegX, rider.pegY - 0.02f, s * 0.17f, 0.05f, 0.012f, 0.05f, MeshBuilder.color(0x3a3a3a))
        }
        m.loft(arrayOf(st(0.42f, 0.22f, 0.012f, 0.09f), st(0.37f, 0.18f, 0.014f, 0.13f), st(0.03f, 0.18f, 0.014f, 0.13f), st(-0.02f, 0.20f, 0.012f, 0.11f)), MeshBuilder.color(0x1e1e1e), 8, 0.85f)

        // --- headlight, cowl, instruments (frame-mounted on the Himalayan) ------------------
        m.tube(0.56f, 0.96f, 0f, 0.62f, 0.96f, 0f, 0.088f, 0.09f, 16, gloss, gloss)        // bucket
        m.tube(0.62f, 0.96f, 0f, 0.632f, 0.96f, 0f, 0.094f, 0.094f, 16, chrome, chrome)    // bezel
        m.tube(0.632f, 0.96f, 0f, 0.637f, 0.96f, 0f, 0.08f, 0.08f, 16, lens, lens)         // lens
        m.loft(arrayOf(st(0.50f, 1.04f, 0.12f, 0.16f), st(0.58f, 1.05f, 0.10f, 0.15f), st(0.64f, 1.06f, 0.06f, 0.12f)), cowl, 12, 0.5f)
        m.box(0.52f, 1.04f, 0f, 0.03f, 0.05f, 0.10f, gloss)                               // instrument pod
        m.tube(0.505f, 1.06f, -0.035f, 0.495f, 1.06f, -0.035f, 0.05f, 0.05f, 14, gloss, gauge)   // speedo
        m.tube(0.505f, 1.07f, 0.055f, 0.495f, 1.07f, 0.055f, 0.03f, 0.03f, 12, gloss, gauge)     // tacho
        m.box(0.51f, 1.16f, 0f, 0.006f, 0.05f, 0.085f, MeshBuilder.color(0x0c0e12))       // phone
        m.beam(0.52f, 1.09f, 0f, 0.51f, 1.12f, 0f, 0.01f, gloss)

        // --- rear: pannier frames, rack, tail, plate ------------------------------------------
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.21f
            val pr = arrayOf(floatArrayOf(-0.24f, 0.44f), floatArrayOf(-0.62f, 0.44f), floatArrayOf(-0.66f, 0.78f), floatArrayOf(-0.27f, 0.78f), floatArrayOf(-0.24f, 0.44f))
            for (k in 0 until pr.size - 1) m.beam(pr[k][0], pr[k][1], z, pr[k + 1][0], pr[k + 1][1], z, 0.011f, frame)
            m.beam(-0.44f, 0.44f, z, -0.46f, 0.78f, z, 0.01f, frame)
            m.beam(-0.27f, 0.78f, z, -0.20f, 0.78f, s * 0.10f, 0.01f, frame)
            m.beam(-0.64f, 0.70f, z, -0.62f, 0.80f, s * 0.10f, 0.01f, frame)
            m.beam(-0.55f, 0.84f, s * 0.10f, -0.86f, 0.88f, s * 0.10f, 0.012f, frame)      // rack rails
            m.beam(-0.86f, 0.80f, s * 0.08f, -0.84f, 0.80f, s * 0.15f, 0.007f, frame)      // signal stalks
            m.blob(-0.84f, 0.80f, s * 0.165f, 0.028f, 0.024f, 0.024f, amber)
            m.tube(-0.985f, 0.66f, s * 0.08f, -0.995f, 0.66f, s * 0.08f, 0.025f, 0.025f, 10, tail, tail)  // reflectors
        }
        m.box(-0.74f, 0.885f, 0f, 0.16f, 0.007f, 0.12f, frame)                            // rack plate
        m.loft(arrayOf(st(-0.62f, 0.79f, 0.018f, 0.10f), st(-0.84f, 0.78f, 0.018f, 0.095f), st(-0.96f, 0.70f, 0.015f, 0.075f), st(-1.0f, 0.60f, 0.012f, 0.06f)), gloss, 10, 0.7f)
        m.box(-0.87f, 0.84f, 0f, 0.035f, 0.03f, 0.075f, tail)                              // tail light
        m.box(-1.005f, 0.60f, 0f, 0.004f, 0.055f, 0.09f, plate)                            // NH plate
        m.box(-1.009f, 0.60f, 0f, 0.002f, 0.018f, 0.06f, MeshBuilder.color(0x2a7a4a))

        // --- chain (left), guard ---------------------------------------------------------------
        val z = -0.115f
        m.beam(0.02f, 0.38f + 0.045f, z, -b, rr + 0.105f, z, 0.006f, chain)
        m.beam(0.02f, 0.38f - 0.045f, z, -b, rr - 0.105f, z, 0.006f, chain)
        m.beam(0.0f, 0.44f, z - 0.01f, -b + 0.12f, rr + 0.13f, z - 0.01f, 0.01f, frame)
        m.tube(0.02f, 0.38f, -0.12f, 0.02f, 0.38f, -0.105f, 0.045f, 0.045f, 10, chain, chain) // front sprocket
    }

    override fun glass(m: MeshBuilder, spec: BikeSpec) {
        // Tall clear touring screen, gently curved, rising above the cowl.
        val rows = arrayOf(floatArrayOf(0.60f, 1.10f, 0.15f), floatArrayOf(0.555f, 1.28f, 0.18f), floatArrayOf(0.505f, 1.46f, 0.19f))
        for (k in 0 until rows.size - 1) {
            val a = rows[k]; val c = rows[k + 1]
            for (side in listOf(-1f, 1f)) {
                // Centre column bows forward a little.
                m.twoSidedTri(a[0] + 0.015f, a[1], 0f, a[0], a[1], side * a[2], c[0], c[1], side * c[2], screenGlass)
                m.twoSidedTri(a[0] + 0.015f, a[1], 0f, c[0], c[1], side * c[2], c[0] + 0.015f, c[1], 0f, screenGlass)
            }
        }
    }

    override fun front(m: MeshBuilder, spec: BikeSpec, ax: Float, ay: Float) {
        fun p(t: Float) = floatArrayOf(ax * t, ay * t)
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.09f
            val a = p(0f); val c = p(0.32f); val d = p(0.56f)
            m.beam(a[0], a[1], z, c[0], c[1], z, 0.019f, chrome)                              // stanchions
            m.beam(c[0], c[1], z, d[0], d[1], z, 0.027f, frame)                               // gaiters
        }
        val lc = p(0.17f)
        m.beam(0f, 0.01f, -0.12f, 0f, 0.01f, 0.12f, 0.022f, frame)                            // top clamp
        m.beam(lc[0], lc[1], -0.12f, lc[0], lc[1], 0.12f, 0.022f, frame)                      // lower clamp
        // High "beak" fender off the lower clamp.
        m.loft(arrayOf(floatArrayOf(lc[0] - 0.05f, lc[1] - 0.04f, 0.012f, 0.07f), floatArrayOf(lc[0] + 0.12f, lc[1] - 0.05f, 0.012f, 0.08f),
            floatArrayOf(lc[0] + 0.30f, lc[1] - 0.09f, 0.01f, 0.065f)), gloss, 8, 0.8f)
        // Bars on risers, with the chrome crossbar brace.
        for (s in listOf(-1f, 1f)) m.beam(-0.02f, 0.02f, s * 0.05f, -0.04f, 0.11f, s * 0.05f, 0.016f, frame)
        val by = 0.12f; val bx = rider.handX - headX
        m.beam(bx, by, -0.42f, bx, by, 0.42f, 0.012f, frame)
        m.beam(bx + 0.02f, by + 0.05f, -0.19f, bx + 0.02f, by + 0.05f, 0.19f, 0.007f, chrome)
        for (s in listOf(-1f, 1f)) {
            m.beam(bx + 0.01f, by + 0.005f, s * 0.19f, bx + 0.02f, by + 0.05f, s * 0.19f, 0.007f, chrome)
            m.tube(bx, by, s * 0.31f, bx, by, s * 0.42f, 0.019f, 0.019f, 8, MeshBuilder.color(0x202020), MeshBuilder.color(0x202020)) // grips
            m.box(bx + 0.01f, by + 0.01f, s * 0.26f, 0.03f, 0.025f, 0.03f, MeshBuilder.color(0x1a1a1a))  // switchgear
            m.beam(bx + 0.04f, by + 0.01f, s * 0.26f, bx + 0.07f, by - 0.01f, s * 0.39f, 0.006f, MeshBuilder.color(0x555555)) // levers
            // Tusk handguards: a black shell wrapping the lever with the red logo.
            m.loft(arrayOf(floatArrayOf(bx - 0.02f, by + 0.01f, 0.04f, 0.012f), floatArrayOf(bx + 0.06f, by + 0.0f, 0.05f, 0.012f),
                floatArrayOf(bx + 0.11f, by - 0.02f, 0.035f, 0.012f)), MeshBuilder.color(0x101010), 8, 0.8f, s * 0.38f)
            m.box(bx + 0.06f, by + 0.01f, s * 0.395f, 0.022f, 0.008f, 0.002f, tusk)
            // Round mirrors on tall stalks.
            m.beam(bx - 0.01f, by + 0.02f, s * 0.28f, bx - 0.03f, by + 0.21f, s * 0.36f, 0.006f, frame)
            m.tube(bx - 0.02f, by + 0.24f, s * 0.37f, bx - 0.04f, by + 0.24f, s * 0.37f, 0.045f, 0.045f, 14, frame, MeshBuilder.color(0x6e7a86))
        }
        m.box(bx - 0.01f, by + 0.03f, 0.22f, 0.025f, 0.02f, 0.03f, MeshBuilder.color(0x1a1a1a))     // master cylinder
        m.box(bx - 0.02f, by + 0.03f, -0.13f, 0.02f, 0.025f, 0.02f, MeshBuilder.color(0x111111))    // action camera
    }

    override fun frontLower(m: MeshBuilder, spec: BikeSpec, ux: Float, uy: Float) {
        val rf = spec.frontRadius.toFloat()
        for (s in listOf(-1f, 1f)) m.beam(0f, 0f, s * 0.09f, ux * 0.31f, uy * 0.31f, s * 0.09f, 0.026f, frame)  // lowers
        m.beam(0f, 0f, -0.11f, 0f, 0f, 0.11f, 0.012f, silver)                                // axle
        m.box(-0.10f, 0.10f, -0.105f, 0.03f, 0.045f, 0.018f, MeshBuilder.color(0x2a2a2a))  // caliper (disc on the left)
        // Low fender hugging the tyre.
        m.arc(0f, 0f, rf + 0.035f, 0.95f, 2.05f, 0.06f, 0.008f, gloss, 6)
        m.tube(-0.02f, rf + 0.04f, -0.09f, -0.02f, rf + 0.04f, 0.09f, 0.008f, 0.008f, 6, frame, frame)  // fender brace
        m.box(0.0f, -0.02f, 0.092f, 0.012f, 0.03f, 0.004f, amber)                            // fork reflector
    }

    override fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean) {
        if (front) {
            // 90/90-21 on a black rim, 300 mm disc on the left.
            m.spokedWheel(spec.frontRadius.toFloat(), 0.081f, 0.046f, 36, tyre, knob, frame, spoke, silver)
            m.arc(0f, 0f, 0.128f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.044f, disc, 20, -0.071f)     // disc
            for (k in 0 until 6) {                                                                  // carrier arms
                val a = k * MeshBuilder.TWO_PI / 6
                m.beam(kotlin.math.cos(a) * 0.04f, kotlin.math.sin(a) * 0.04f, -0.071f, kotlin.math.cos(a) * 0.108f, kotlin.math.sin(a) * 0.108f, -0.071f, 0.006f, MeshBuilder.color(0x303030))
            }
        } else {
            // 120/90-17, 240 mm disc on the right, sprocket on the left.
            m.spokedWheel(spec.rearRadius.toFloat(), 0.108f, 0.062f, 36, tyre, knob, frame, spoke, silver)
            m.arc(0f, 0f, 0.102f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.036f, disc, 18, 0.071f)
            for (k in 0 until 5) {
                val a = k * MeshBuilder.TWO_PI / 5
                m.beam(kotlin.math.cos(a) * 0.04f, kotlin.math.sin(a) * 0.04f, 0.071f, kotlin.math.cos(a) * 0.086f, kotlin.math.sin(a) * 0.086f, 0.071f, 0.006f, MeshBuilder.color(0x303030))
            }
            m.tube(0f, 0f, -0.112f, 0f, 0f, -0.104f, 0.105f, 0.105f, 22, chain, MeshBuilder.color(0x2a2a2a))
        }
    }
}
