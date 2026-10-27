package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.physics.BikeSpec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Franco's BMW R 1250 GS in Style Rallye (racing blue, black tank shrouds with red and white
 * flashes), as set up: the boxer's cylinders out in the wind behind silver upper and lower crash
 * bars, a lighting rig on the bars (yellow square pods either side of the beak, round seven-LED
 * driving lights and small fogs below), the LED headlight behind a mesh guard, a tall
 * sticker-covered screen, a 360 camera on a tall pole at the front, a portrait Garmin on the left
 * of the bars and a CarPlay screen over the TFT, double-take mirrors, silver pannier frames with
 * no boxes and dry bags strapped to the rack. Telelever front, Paralever single-sided swingarm
 * on the left with the shaft drive, cross-spoked 19"/17" wheels on knobbies.
 *
 * [Detail.SHOWROOM] adds the small stuff: LED dots in the lights, fins and bolts, cables, straps,
 * stickers and the shock spring.
 */
class R1250GSLook(private val detail: Detail = Detail.RIDE) : BikeLook {
    // Trail 100.6 mm at 25.7 deg rake: the steering axis meets the ground 0.10 m ahead of the
    // front contact patch, and the head sits 0.98 m up that line.
    override val headX = 0.39f
    override val headY = 0.98f
    override val rakeDeg = 25.7
    override val pivotX = -0.03f
    override val pivotY = 0.42f
    override val rider = RiderFit(-0.18f, 0.92f, 0.0f, 0.38f, 0.30f, 1.14f, 0.42f)
    // The Garmin, portrait on the left of the bars (steers with them), tilted back a little.
    override val navScreen = NavScreen(-0.03f, 0.29f, -0.16f, 0.072f, 0.128f, tiltDeg = 18f, onBars = true)

    private val hi = detail == Detail.SHOWROOM
    private val ls = if (hi) 20 else 12
    private val ts = if (hi) 12 else 7

    private val blue = MeshBuilder.color(0x1f5aa8)
    private val blueLight = MeshBuilder.color(0x2f7fd0)
    private val black = MeshBuilder.color(0x121315)
    private val matte = MeshBuilder.color(0x1c1d20)
    private val red = MeshBuilder.color(0xc81e2a)
    private val white = MeshBuilder.color(0xeeeeec)
    private val grey = MeshBuilder.color(0x5a5d62)
    private val silver = MeshBuilder.color(0xb8bbc0)
    private val alu = MeshBuilder.color(0xc9ccd0)
    private val engineBlack = MeshBuilder.color(0x202124)
    private val head = MeshBuilder.color(0x2a2b2e)
    private val steel = MeshBuilder.color(0xb0a898)
    private val gold = MeshBuilder.color(0xc89a3a)
    private val tyre = MeshBuilder.color(0x1c1c1c)
    private val knob = MeshBuilder.color(0x242424)
    private val spoke = MeshBuilder.color(0xc4c6c8)
    private val disc = MeshBuilder.color(0xcfd2d5)
    private val rubber = MeshBuilder.color(0x202020)
    private val cable = MeshBuilder.color(0x0e0e0e)
    private val bolt = MeshBuilder.color(0x9a9ea2)
    private val amberLens = MeshBuilder.color(0xffbe1c, 0.35f)       // the yellow pods: lit
    private val whiteLens = MeshBuilder.color(0xfaf8ee, 0.4f)        // driving lights and headlight: lit
    private val tail = MeshBuilder.color(0xc0161c, 0.55f)
    private val amber = MeshBuilder.color(0xe8890e)
    private val screenGlass = MeshBuilder.color(0.80f, 0.86f, 0.90f, 0.28f)
    private val bagBlack = MeshBuilder.color(0x1e2124)
    private val bagGrey = MeshBuilder.color(0x3a4046)
    private val bagRed = MeshBuilder.color(0xa8183a)

    private fun st(x: Float, cy: Float, hy: Float, hz: Float) = floatArrayOf(x, cy, hy, hz)

    private fun MeshBuilder.rod(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r0: Float, r1: Float, c: Float, end: Float = c) =
        tube(x0, y0, z0, x1, y1, z1, r0, r1, ts, c, end)

    /** A bent tube through [pts] (x, y, |z|) on the side [s] (+1 right, -1 left). */
    private fun MeshBuilder.bent(pts: Array<FloatArray>, s: Float, t: Float, c: Float) {
        for (k in 0 until pts.size - 1) beam(pts[k][0], pts[k][1], s * pts[k][2], pts[k + 1][0], pts[k + 1][1], s * pts[k + 1][2], t, c)
    }

    /** A round tube bent through [pts] (x, y, |z|) on side [s]. */
    private fun MeshBuilder.pipe(pts: Array<FloatArray>, s: Float, r: Float, c: Float) {
        for (k in 0 until pts.size - 1) rod(pts[k][0], pts[k][1], s * pts[k][2], pts[k + 1][0], pts[k + 1][1], s * pts[k + 1][2], r, r, c)
    }

    /** A disc facing forward (+X): lights. */
    private fun MeshBuilder.discX(x0: Float, x1: Float, y: Float, z: Float, r: Float, c: Float, face: Float = c, sides: Int = if (hi) 18 else 10) =
        tube(x0, y, z, x1, y, z, r, r, sides, c, face)

    override fun chassis(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat()

        // --- frame: black front frame, silver trellis rear frame ------------------------------
        for (s in listOf(-1f, 1f)) {
            m.beam(headX - 0.02f, headY - 0.06f, s * 0.05f, 0.12f, 0.64f, s * 0.11f, 0.022f, black)     // main frame to the gearbox
            m.beam(0.12f, 0.64f, s * 0.11f, -0.04f, 0.50f, s * 0.11f, 0.022f, black)
            m.beam(-0.04f, 0.50f, s * 0.11f, -0.04f, 0.36f, s * 0.11f, 0.02f, black)                 // to the swingarm pivot
            m.beam(-0.02f, 0.66f, s * 0.11f, -0.86f, 0.87f, s * 0.11f, 0.014f, silver)                // rear frame: top rails
            m.beam(-0.06f, 0.46f, s * 0.11f, -0.62f, 0.84f, s * 0.11f, 0.013f, silver)                // lower rails
            m.beam(-0.30f, 0.57f, s * 0.11f, -0.30f, 0.73f, s * 0.11f, 0.011f, silver)                // trellis bracing
            m.beam(-0.48f, 0.69f, s * 0.11f, -0.46f, 0.80f, s * 0.11f, 0.011f, silver)
        }
        m.beam(-0.86f, 0.87f, -0.11f, -0.86f, 0.87f, 0.11f, 0.012f, silver)

        // --- boxer engine: crankcase, cylinders out each side, heads, gearbox ---------------------
        m.loft(arrayOf(st(0.42f, 0.42f, 0.10f, 0.10f), st(0.30f, 0.44f, 0.16f, 0.13f), st(0.05f, 0.45f, 0.17f, 0.13f), st(-0.08f, 0.50f, 0.12f, 0.11f)), engineBlack, ls, 0.7f)
        for (s in listOf(-1f, 1f)) {
            // Cylinder (slightly ahead and up on the right, as the crank throws are offset).
            val cx = 0.27f + s * 0.015f; val cy = 0.47f
            m.rod(cx, cy, s * 0.11f, cx, cy, s * 0.31f, 0.072f, 0.072f, engineBlack)
            if (hi) for (k in 0 until 6) m.rod(cx, cy, s * (0.14f + k * 0.03f), cx, cy, s * (0.152f + k * 0.03f), 0.084f, 0.084f, grey)   // fins
            // Head with the silver valve cover and the black guard.
            m.rod(cx, cy, s * 0.30f, cx, cy, s * 0.40f, 0.088f, 0.085f, head)
            m.rod(cx + 0.005f, cy + 0.005f, s * 0.40f, cx + 0.005f, cy + 0.005f, s * 0.43f, 0.078f, 0.07f, alu, matte)   // valve cover
            m.box(cx + 0.03f, cy - 0.03f, s * 0.435f, 0.03f, 0.022f, 0.004f, matte)                      // cover guard
            m.box(cx - 0.02f, cy + 0.03f, s * 0.433f, 0.02f, 0.006f, 0.002f, white)                     // BMW lettering
            // Intake from the head up and back to the airbox.
            m.rod(cx - 0.05f, cy + 0.06f, s * 0.33f, 0.02f, 0.70f, s * 0.13f, 0.03f, 0.024f, black)
            // Header from the bottom of the head, forward, down and under.
            m.pipe(arrayOf(floatArrayOf(cx + 0.06f, cy - 0.05f, 0.34f), floatArrayOf(0.43f, 0.32f, 0.30f), floatArrayOf(0.40f, 0.22f, 0.16f), floatArrayOf(0.15f, 0.21f, 0.08f)), s, 0.022f, steel)
        }
        m.pipe(arrayOf(floatArrayOf(0.15f, 0.21f, 0.08f), floatArrayOf(-0.12f, 0.26f, 0.14f), floatArrayOf(-0.30f, 0.40f, 0.20f)), 1f, 0.03f, steel)  // collector to the muffler
        m.box(0.0f, 0.62f, 0f, 0.14f, 0.08f, 0.10f, engineBlack)                                         // airbox / gearbox top
        // Aluminium skid plate.
        m.box(0.24f, 0.205f, 0f, 0.22f, 0.012f, 0.14f, alu)
        m.loft(arrayOf(st(0.46f, 0.22f, 0.012f, 0.14f), st(0.52f, 0.33f, 0.012f, 0.12f)), alu, 6, 0.95f)

        // --- muffler (right), high under the pannier frame ------------------------------------
        m.rod(-0.30f, 0.42f, 0.22f, -0.78f, 0.62f, 0.23f, 0.075f, 0.072f, alu, MeshBuilder.color(0x2a2a2a))
        m.rod(-0.30f, 0.42f, 0.22f, -0.40f, 0.46f, 0.225f, 0.078f, 0.078f, matte)
        m.rod(-0.78f, 0.62f, 0.23f, -0.82f, 0.64f, 0.23f, 0.045f, 0.04f, matte, MeshBuilder.color(0x0a0a0a))

        // --- tank: racing blue on top, black shrouds down the sides with the red/white flash ------
        m.loft(arrayOf(st(0.44f, 0.97f, 0.05f, 0.13f), st(0.34f, 1.0f, 0.09f, 0.19f), st(0.16f, 1.0f, 0.09f, 0.19f), st(0.02f, 0.96f, 0.06f, 0.14f)), blue, ls, 0.6f)
        m.box(0.40f, 1.06f, 0f, 0.03f, 0.008f, 0.07f, black)                                            // tank-top vent
        m.cone(0.20f, 1.085f, 0f, 0.05f, 0.05f, 0.008f, ls, MeshBuilder.color(0x2a2a2a))                  // filler cap
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.205f
            m.loft(arrayOf(st(0.55f, 0.80f, 0.11f, 0.03f), st(0.40f, 0.80f, 0.18f, 0.045f), st(0.16f, 0.80f, 0.17f, 0.045f), st(0.02f, 0.85f, 0.10f, 0.03f)), black, if (hi) 14 else 8, 0.8f, z)
            val o = s * 0.252f
            // Red and white flashes toward the back of the shroud, grey GS lettering ahead.
            m.box(0.10f, 0.76f, o, 0.035f, 0.075f, 0.003f, red)
            m.box(0.16f, 0.76f, o, 0.022f, 0.075f, 0.003f, white)
            m.box(0.28f, 0.86f, o, 0.07f, 0.03f, 0.003f, grey)
            // BMW roundel.
            m.discZ(0.40f, 0.78f, o, s * 0.255f, 0.035f, black)
            m.discZ(0.40f, 0.78f, s * 0.255f, s * 0.257f, 0.026f, white)
            m.box(0.408f, 0.787f, s * 0.258f, 0.009f, 0.009f, 0.001f, blueLight)
            m.box(0.392f, 0.773f, s * 0.258f, 0.009f, 0.009f, 0.001f, blueLight)
            // Radiators either side of the beak.
            m.box(0.50f, 0.68f, s * 0.165f, 0.04f, 0.12f, 0.05f, matte)
            m.box(0.47f, 0.92f, s * 0.15f, 0.06f, 0.05f, 0.05f, blue)                                     // side fairing above them
            m.box(0.52f, 0.93f, s * 0.20f, 0.04f, 0.02f, 0.002f, white)                                 // R1250 decal
        }

        // --- beak and headlight -----------------------------------------------------------------
        m.loft(arrayOf(st(0.46f, 0.84f, 0.05f, 0.11f), st(0.62f, 0.76f, 0.045f, 0.085f), st(0.78f, 0.67f, 0.03f, 0.055f)), blue, ls, 0.7f)
        m.loft(arrayOf(st(0.58f, 0.74f, 0.02f, 0.07f), st(0.78f, 0.655f, 0.015f, 0.05f)), black, 8, 0.8f)
        m.box(0.58f, 0.95f, 0f, 0.04f, 0.075f, 0.11f, black)                                           // headlight housing
        m.discX(0.61f, 0.625f, 0.95f, 0f, 0.07f, black, whiteLens, 6)                                 // LED headlight (hexagonal)
        if (hi) for (k in -3..3) m.beam(0.63f, 0.89f, k * 0.02f, 0.63f, 1.01f, k * 0.02f, 0.0025f, black)   // mesh guard
        for (k in -2..2) m.beam(0.632f, 0.95f + k * 0.022f, -0.075f, 0.632f, 0.95f + k * 0.022f, 0.075f, 0.003f, black)
        m.box(0.555f, 1.04f, 0f, 0.03f, 0.03f, 0.13f, black)                                           // screen mount

        // --- cockpit: TFT and the CarPlay screen above it ------------------------------------
        m.box(0.47f, 1.09f, 0f, 0.015f, 0.05f, 0.08f, black)
        m.box(0.461f, 1.09f, 0f, 0.002f, 0.04f, 0.07f, MeshBuilder.color(0x101820, 0.7f))               // TFT, lit
        m.box(0.460f, 1.075f, 0.02f, 0.001f, 0.01f, 0.03f, MeshBuilder.color(0xe8701a, 0.6f))           // rev arc
        m.box(0.475f, 1.19f, 0f, 0.012f, 0.045f, 0.08f, black)
        m.box(0.466f, 1.19f, 0f, 0.002f, 0.038f, 0.072f, MeshBuilder.color(0xd8dce0, 0.6f))             // CarPlay map, lit
        m.box(0.465f, 1.19f, 0.01f, 0.001f, 0.004f, 0.05f, MeshBuilder.color(0x2a7ad8, 0.6f))            // the route
        m.beam(0.47f, 1.12f, 0f, 0.47f, 1.16f, 0f, 0.01f, black)

        // --- crash bars: upper around the shrouds, lower around the cylinders --------------------
        for (s in listOf(-1f, 1f)) {
            m.pipe(arrayOf(floatArrayOf(0.36f, 0.96f, 0.10f), floatArrayOf(0.54f, 0.88f, 0.22f), floatArrayOf(0.56f, 0.62f, 0.26f),
                floatArrayOf(0.40f, 0.56f, 0.28f), floatArrayOf(0.12f, 0.62f, 0.25f), floatArrayOf(0.06f, 0.72f, 0.15f)), s, 0.012f, silver)
            m.pipe(arrayOf(floatArrayOf(0.45f, 0.70f, 0.14f), floatArrayOf(0.48f, 0.56f, 0.36f), floatArrayOf(0.40f, 0.34f, 0.40f),
                floatArrayOf(0.16f, 0.32f, 0.40f), floatArrayOf(0.06f, 0.42f, 0.34f), floatArrayOf(0.02f, 0.50f, 0.13f)), s, 0.012f, silver)
            m.pipe(arrayOf(floatArrayOf(0.48f, 0.56f, 0.36f), floatArrayOf(0.56f, 0.62f, 0.26f)), s, 0.01f, silver)
        }

        // --- the lighting rig: yellow pods up high, round drivers and fogs below ---------------
        for (s in listOf(-1f, 1f)) {
            // Square yellow pods beside the beak, on the upper bars.
            m.box(0.585f, 0.80f, s * 0.215f, 0.035f, 0.05f, 0.05f, black)
            m.box(0.622f, 0.80f, s * 0.215f, 0.003f, 0.042f, 0.042f, amberLens)
            if (hi) for (i in 0..1) for (j in 0..1) m.discX(0.624f, 0.627f, 0.785f + i * 0.03f, s * 0.215f - 0.015f + j * 0.03f, 0.009f, amberLens, MeshBuilder.color(0xfff0b0, 0.3f), 8)
            // Round seven-LED driving lights on the lower bars.
            m.discX(0.53f, 0.60f, 0.58f, s * 0.19f, 0.055f, black, black)
            m.discX(0.60f, 0.605f, 0.58f, s * 0.19f, 0.048f, whiteLens, whiteLens)
            if (hi) for (k in 0 until 7) {
                val a = k * MeshBuilder.TWO_PI / 6
                val r = if (k == 6) 0f else 0.026f
                m.discX(0.605f, 0.608f, 0.58f + sin(a) * r, s * 0.19f + cos(a) * r, 0.009f, MeshBuilder.color(0xffffff, 0.25f), MeshBuilder.color(0xffffff, 0.25f), 8)
            }
            // Small fogs low on the bars.
            m.discX(0.50f, 0.55f, 0.44f, s * 0.17f, 0.028f, black, MeshBuilder.color(0xfff4d8, 0.35f))
            m.beam(0.50f, 0.44f, s * 0.17f, 0.46f, 0.48f, s * 0.30f, 0.006f, black)                    // brackets
            m.beam(0.55f, 0.58f, s * 0.19f, 0.52f, 0.58f, s * 0.30f, 0.008f, black)
        }

        // --- camera pole: a 360 camera on a tall pole from the front of the bars, and a brace ----
        m.rod(0.60f, 0.84f, 0.06f, 0.86f, 1.72f, 0.03f, 0.011f, 0.009f, black)
        m.box(0.865f, 1.76f, 0.03f, 0.02f, 0.04f, 0.022f, matte)
        m.discZ(0.865f, 1.785f, 0.03f - 0.024f, 0.03f + 0.024f, 0.012f, MeshBuilder.color(0x30343a), MeshBuilder.color(0x5a7090))   // lenses
        m.rod(0.52f, 0.60f, 0.27f, 0.74f, 1.30f, 0.05f, 0.007f, 0.007f, black)
        m.box(0.73f, 1.29f, 0.05f, 0.02f, 0.02f, 0.02f, matte)

        // --- seat, tail, rack and bags ----------------------------------------------------------
        m.loft(arrayOf(st(0.05f, 0.92f, 0.03f, 0.12f), st(-0.12f, 0.88f, 0.05f, 0.17f), st(-0.40f, 0.88f, 0.05f, 0.16f), st(-0.62f, 0.92f, 0.04f, 0.12f)), black, ls, 0.75f)
        if (hi) for (k in 0 until 6) m.box(-0.08f - k * 0.08f, 0.935f, 0f, 0.002f, 0.002f, 0.15f, MeshBuilder.color(0x2a2a2a))   // grippy cover stitching
        m.loft(arrayOf(st(-0.60f, 0.88f, 0.05f, 0.10f), st(-0.86f, 0.88f, 0.05f, 0.09f), st(-0.98f, 0.82f, 0.04f, 0.07f)), black, 10, 0.7f)
        m.box(-0.975f, 0.86f, 0f, 0.012f, 0.025f, 0.06f, tail)
        m.box(-0.99f, 0.62f, 0f, 0.004f, 0.055f, 0.10f, MeshBuilder.color(0xe8ece8))                 // plate
        m.box(-0.995f, 0.66f, 0f, 0.002f, 0.008f, 0.08f, MeshBuilder.color(0x2a4a8a))
        m.beam(-0.92f, 0.82f, 0f, -0.96f, 0.64f, 0f, 0.012f, black)                                      // plate hanger
        for (s in listOf(-1f, 1f)) {
            m.beam(-0.90f, 0.80f, s * 0.08f, -0.92f, 0.80f, s * 0.15f, 0.006f, black)                   // signals
            m.blob(-0.93f, 0.80f, s * 0.16f, 0.02f, 0.018f, 0.018f, amber)
        }
        m.box(-0.78f, 0.95f, 0f, 0.20f, 0.008f, 0.14f, alu)                                              // rack plate
        // Dry bags: a long grey/black roll on the rack, a red roll across the back, a small pack.
        m.loft(arrayOf(st(-0.58f, 1.06f, 0.09f, 0.17f), st(-0.66f, 1.08f, 0.11f, 0.20f), st(-0.90f, 1.08f, 0.11f, 0.20f), st(-0.98f, 1.06f, 0.09f, 0.17f)), bagGrey, ls, 0.6f)
        m.tube(-0.97f, 1.0f, -0.24f, -0.97f, 1.0f, 0.24f, 0.075f, 0.075f, ls, bagRed, bagBlack)
        m.box(-0.70f, 1.22f, 0.04f, 0.10f, 0.05f, 0.09f, bagBlack)
        for (k in 0 until 3) m.box(-0.66f - k * 0.12f, 1.07f, 0f, 0.012f, 0.122f, 0.205f, bagBlack)     // straps
        // Under the seat: the black side covers, the battery and electronics block behind them,
        // and the undertray running back to the tail, so the bike has the GS's bulk there.
        m.box(-0.24f, 0.70f, 0f, 0.24f, 0.12f, 0.10f, engineBlack)
        m.loft(arrayOf(st(-0.50f, 0.80f, 0.06f, 0.10f), st(-0.75f, 0.81f, 0.05f, 0.095f), st(-0.95f, 0.80f, 0.04f, 0.075f)), matte, 10, 0.85f)
        for (s in listOf(-1f, 1f)) {
            m.loft(arrayOf(st(0.04f, 0.73f, 0.12f, 0.022f), st(-0.18f, 0.73f, 0.14f, 0.026f), st(-0.42f, 0.78f, 0.10f, 0.026f), st(-0.60f, 0.83f, 0.05f, 0.02f)), black, if (hi) 14 else 8, 0.9f, s * 0.135f)
            m.box(-0.20f, 0.70f, s * 0.162f, 0.10f, 0.004f, 0.002f, grey)                                 // panel crease
            if (hi) for (k in 0 until 3) m.cone(-0.06f - k * 0.16f, 0.80f - k * 0.02f, s * 0.162f, 0.006f, 0.006f, 0.003f, 6, bolt)
        }

        // Aluminium tool box carried inside the left pannier frame, with a black graphic and latches.
        run {
            val x = -0.66f; val y = 0.63f; val z = -0.245f
            m.box(x, y, z, 0.18f, 0.13f, 0.045f, alu)
            m.box(x, y + 0.135f, z, 0.185f, 0.008f, 0.05f, MeshBuilder.color(0xa4a8ac))               // lid rim
            val face = z - 0.047f
            m.box(x - 0.08f, y + 0.02f, face, 0.06f, 0.07f, 0.002f, matte)
            m.box(x + 0.04f, y - 0.05f, face, 0.08f, 0.04f, 0.002f, matte)
            m.box(x + 0.11f, y + 0.06f, face, 0.04f, 0.035f, 0.002f, matte)
            for (k in listOf(-1f, 1f)) m.box(x + k * 0.10f, y + 0.12f, face - 0.004f, 0.015f, 0.012f, 0.003f, MeshBuilder.color(0x2a2a2a))
            m.beam(x - 0.16f, y - 0.12f, -0.27f, x - 0.16f, y - 0.12f, z + 0.03f, 0.006f, black)          // mounting straps
            m.beam(x + 0.16f, y - 0.12f, -0.27f, x + 0.16f, y - 0.12f, z + 0.03f, 0.006f, black)
        }

        // Silver pannier frames, no boxes.
        for (s in listOf(-1f, 1f)) {
            val z = 0.27f
            m.bent(arrayOf(floatArrayOf(-0.32f, 0.46f, z), floatArrayOf(-0.86f, 0.46f, z), floatArrayOf(-0.90f, 0.84f, z), floatArrayOf(-0.36f, 0.86f, z), floatArrayOf(-0.32f, 0.46f, z)), s, 0.01f, silver)
            m.beam(-0.36f, 0.86f, s * z, -0.30f, 0.80f, s * 0.11f, 0.009f, silver)
            m.beam(-0.86f, 0.46f, s * z, -0.62f, 0.62f, s * 0.11f, 0.009f, silver)
        }

        // --- rear shock (gold) on the right, and the reservoir ------------------------------------
        m.rod(-0.16f, 0.40f, 0.09f, -0.08f, 0.78f, 0.09f, 0.022f, 0.022f, gold)
        if (hi) for (k in 0..8) {
            val f = 0.2f + k * 0.07f
            m.rod(-0.16f + 0.08f * f, 0.40f + 0.38f * f, 0.09f, -0.155f + 0.08f * f, 0.41f + 0.38f * f, 0.09f, 0.04f, 0.04f, gold)
        } else m.rod(-0.145f, 0.47f, 0.09f, -0.10f, 0.70f, 0.09f, 0.038f, 0.038f, gold)

        // --- pegs, levers, side stand ---------------------------------------------------------
        for (s in listOf(-1f, 1f)) {
            m.box(rider.pegX, rider.pegY, s * 0.20f, 0.05f, 0.012f, 0.05f, alu)
            m.beam(-0.02f, 0.40f, s * 0.13f, rider.pegX, rider.pegY, s * 0.17f, 0.012f, black)
        }
        m.beam(-0.02f, 0.30f, -0.14f, -0.20f, 0.06f, -0.20f, 0.012f, black)                              // side stand, up
        if (hi) {
            m.bent(arrayOf(floatArrayOf(headX - 0.04f, headY + 0.08f, 0.08f), floatArrayOf(0.30f, 0.92f, 0.13f), floatArrayOf(0.12f, 0.72f, 0.14f)), 1f, 0.004f, cable)
            m.bent(arrayOf(floatArrayOf(headX - 0.04f, headY + 0.08f, 0.08f), floatArrayOf(0.32f, 0.90f, 0.13f), floatArrayOf(0.20f, 0.66f, 0.15f)), -1f, 0.004f, cable)
            for (k in 0 until 4) m.cone(0.27f + 0.06f * (k % 2), 0.43f + 0.06f * (k / 2), 0.44f, 0.006f, 0.006f, 0.004f, 6, bolt)
        }
    }

    override fun swingarm(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        // Paralever: a single-sided arm on the left carrying the shaft, the torque strut below.
        m.loft(arrayOf(st(pivotX, pivotY, 0.045f, 0.03f), st(-b * 0.5f, (pivotY + rr) / 2, 0.05f, 0.032f), st(-b + 0.08f, rr, 0.05f, 0.032f)), black, 10, 0.8f, -0.115f)
        m.beam(pivotX - 0.02f, pivotY - 0.09f, -0.10f, -b + 0.10f, rr - 0.08f, -0.10f, 0.012f, silver)   // torque strut
        m.tube(-b, rr, -0.075f, -b, rr, -0.16f, 0.085f, 0.07f, ls, black, alu)                          // final drive
        m.beam(pivotX, pivotY, -0.12f, pivotX, pivotY, 0.12f, 0.018f, silver)                            // pivot
        // Hugger over the rear tyre, riding with the swingarm.
        m.arc(-b, rr, rr + 0.04f, 1.25f, 2.2f, 0.075f, 0.006f, matte, if (hi) 10 else 6)
        m.beam(-b * 0.45f, (pivotY + rr) / 2 + 0.03f, -0.10f, -b + 0.05f, rr + 0.33f, -0.07f, 0.008f, matte)
    }

    override fun glass(m: MeshBuilder, spec: BikeSpec) {
        // Tall clear screen with the rally stickers, rising from the beak.
        val rows = if (hi) arrayOf(floatArrayOf(0.57f, 1.06f, 0.13f), floatArrayOf(0.555f, 1.16f, 0.16f), floatArrayOf(0.54f, 1.26f, 0.19f), floatArrayOf(0.525f, 1.36f, 0.205f))
        else arrayOf(floatArrayOf(0.57f, 1.06f, 0.13f), floatArrayOf(0.54f, 1.26f, 0.19f), floatArrayOf(0.525f, 1.36f, 0.205f))
        for (k in 0 until rows.size - 1) {
            val a = rows[k]; val c = rows[k + 1]
            for (side in listOf(-1f, 1f)) {
                m.twoSidedTri(a[0] + 0.012f, a[1], 0f, a[0], a[1], side * a[2], c[0], c[1], side * c[2], screenGlass)
                m.twoSidedTri(a[0] + 0.012f, a[1], 0f, c[0], c[1], side * c[2], c[0] + 0.012f, c[1], 0f, screenGlass)
            }
        }
    }

    override fun front(m: MeshBuilder, spec: BikeSpec, ax: Float, ay: Float) {
        fun p(t: Float) = floatArrayOf(ax * t, ay * t)
        // Telelever: stanchions in a bridge under the bars.
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.10f
            val a = p(0.05f); val c = p(0.55f)
            m.rod(a[0], a[1], z, c[0], c[1], z, 0.021f, 0.021f, silver)
        }
        val br = p(0.12f)
        m.beam(br[0], br[1], -0.12f, br[0], br[1], 0.12f, 0.025f, black)                                 // fork bridge
        m.beam(0f, 0.02f, -0.11f, 0f, 0.02f, 0.11f, 0.02f, black)                                        // top clamp
        // Wide bars on risers, handguards, double-take mirrors on long arms.
        val by = 0.14f; val bx = rider.handX - headX
        for (s in listOf(-1f, 1f)) m.beam(-0.02f, 0.03f, s * 0.05f, bx, by - 0.01f, s * 0.05f, 0.016f, alu)
        m.beam(bx, by, -0.44f, bx, by, 0.44f, 0.013f, black)
        for (s in listOf(-1f, 1f)) {
            m.rod(bx, by, s * 0.32f, bx, by, s * 0.44f, 0.02f, 0.02f, rubber)                         // grips
            m.box(bx + 0.01f, by + 0.015f, s * 0.27f, 0.032f, 0.028f, 0.035f, MeshBuilder.color(0x18181a))   // switchgear
            m.beam(bx + 0.04f, by + 0.01f, s * 0.28f, bx + 0.08f, by - 0.01f, s * 0.40f, 0.006f, grey)    // levers
            m.loft(arrayOf(floatArrayOf(bx - 0.02f, by + 0.01f, 0.045f, 0.012f), floatArrayOf(bx + 0.07f, by + 0.0f, 0.06f, 0.012f),
                floatArrayOf(bx + 0.13f, by - 0.02f, 0.04f, 0.012f)), MeshBuilder.color(0x0e0e10), 8, 0.85f, s * 0.40f)   // handguards
            // Mirror arm with a ball joint, then the angular double-take head.
            m.beam(bx, by + 0.02f, s * 0.24f, bx - 0.04f, by + 0.15f, s * 0.31f, 0.009f, black)
            m.blob(bx - 0.04f, by + 0.15f, s * 0.31f, 0.014f, 0.014f, 0.014f, black)
            m.beam(bx - 0.04f, by + 0.15f, s * 0.31f, bx - 0.05f, by + 0.20f, s * 0.37f, 0.008f, black)
            m.box(bx - 0.06f, by + 0.23f, s * 0.41f, 0.012f, 0.045f, 0.08f, black)
            m.box(bx - 0.073f, by + 0.23f, s * 0.41f, 0.002f, 0.036f, 0.068f, MeshBuilder.color(0x6e7a86))   // glass
            if (hi) m.rod(bx, by, s * 0.44f, bx, by, s * 0.455f, 0.022f, 0.022f, alu)                     // bar ends
        }
        // The Garmin on the left, in its cradle on a ball mount ([navScreen] is its display).
        val ns = navScreen
        val t = Math.toRadians(ns.tiltDeg.toDouble())
        val ux = -sin(t).toFloat(); val uy = cos(t).toFloat()
        val nx = -cos(t).toFloat(); val ny = -sin(t).toFloat()
        val cx = ns.x - nx * 0.012f; val cy = ns.y - ny * 0.012f
        val hw = ns.width / 2 + 0.008f; val hh = ns.height / 2 + 0.01f
        for (k in 0..1) {
            val d = k * 0.02f
            val x0 = cx - ux * hh - nx * d; val y0 = cy - uy * hh - ny * d
            val x1 = cx + ux * hh - nx * d; val y1 = cy + uy * hh - ny * d
            m.twoSidedTri(x0, y0, ns.z - hw, x1, y1, ns.z - hw, x1, y1, ns.z + hw, matte)
            m.twoSidedTri(x0, y0, ns.z - hw, x1, y1, ns.z + hw, x0, y0, ns.z + hw, matte)
        }
        m.beam(cx - nx * 0.02f, cy - ny * 0.02f - hh * 0.4f, ns.z, bx, by + 0.01f, ns.z, 0.008f, black)   // ball arm
        m.box(bx - 0.01f, by + 0.035f, 0.21f, 0.022f, 0.022f, 0.025f, MeshBuilder.color(0x1a1a1a))    // brake reservoir
        m.box(bx - 0.01f, by + 0.04f, -0.21f, 0.02f, 0.02f, 0.025f, MeshBuilder.color(0x1a1a1a))      // clutch reservoir
        if (hi) for (s in listOf(-1f, 1f)) m.bent(arrayOf(floatArrayOf(bx + 0.01f, by, 0.25f), floatArrayOf(bx + 0.06f, by - 0.08f, 0.18f), floatArrayOf(0.02f, -0.06f, 0.09f)), s, 0.004f, cable)
    }

    override fun frontLower(m: MeshBuilder, spec: BikeSpec, ux: Float, uy: Float) {
        val rf = spec.frontRadius.toFloat()
        for (s in listOf(-1f, 1f)) {
            m.rod(0f, 0f, s * 0.10f, ux * 0.27f, uy * 0.27f, s * 0.10f, 0.03f, 0.03f, black)          // sliders
            m.box(-0.08f, 0.09f, s * 0.10f, 0.035f, 0.05f, 0.02f, MeshBuilder.color(0x2e3034))         // twin calipers
        }
        m.beam(0f, 0f, -0.12f, 0f, 0f, 0.12f, 0.013f, alu)                                               // axle
        m.arc(0f, 0f, rf + 0.03f, 0.95f, 2.1f, 0.065f, 0.008f, black, if (hi) 12 else 6)                 // low fender
        m.box(0.0f, -0.02f, 0.105f, 0.012f, 0.03f, 0.004f, amber)                                        // fork reflectors
        m.box(0.0f, -0.02f, -0.105f, 0.012f, 0.03f, 0.004f, amber)
    }

    override fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean) {
        val spokes = if (hi) 40 else 20
        val knobs = if (hi) 36 else 18
        val rimC = MeshBuilder.color(0x1a1a1c)
        if (front) {
            // 120/70-19 on cross-spoked rims, twin 305 mm discs.
            m.spokedWheel(spec.frontRadius.toFloat(), 0.084f, 0.062f, spokes, tyre, knob, rimC, spoke, alu, knobs)
            for (s in listOf(-1f, 1f)) {
                m.arc(0f, 0f, 0.135f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.04f, disc, if (hi) 30 else 16, s * 0.07f)
                for (k in 0 until 6) {
                    val a = k * MeshBuilder.TWO_PI / 6
                    m.beam(cos(a) * 0.045f, sin(a) * 0.045f, s * 0.07f, cos(a) * 0.115f, sin(a) * 0.115f, s * 0.07f, 0.006f, MeshBuilder.color(0x303030))
                }
            }
        } else {
            // 170/60-17, 276 mm disc on the right; the shaft drive is on the left.
            m.spokedWheel(spec.rearRadius.toFloat(), 0.102f, 0.085f, spokes, tyre, knob, rimC, spoke, alu, knobs)
            m.arc(0f, 0f, 0.122f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.036f, disc, if (hi) 26 else 14, 0.08f)
            m.box(-0.10f, 0.08f, 0.09f, 0.03f, 0.04f, 0.018f, MeshBuilder.color(0x2e3034))              // rear caliper
        }
    }

    /** A flat disc facing ±Z (logos, caps). */
    private fun MeshBuilder.discZ(x: Float, y: Float, z0: Float, z1: Float, r: Float, c: Float, face: Float = c) =
        tube(x, y, z0, x, y, z1, r, r, if (hi) 20 else 12, c, face)
}
