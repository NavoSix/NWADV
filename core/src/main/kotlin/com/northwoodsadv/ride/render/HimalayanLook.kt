package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.world.Noise
import kotlin.math.cos
import kotlin.math.sin

/**
 * Royal Enfield's factory paint schemes for the Himalayan 411 (and the Scram's White Flame), as
 * colours for the tank, its stripe and, for Pine Green, a camouflage over tank, side panels and
 * fenders. [id] is what settings store.
 */
enum class HimalayanPaint(val id: String, val label: String, val tankFront: Int, val tankRear: Int, val stripe: Int, val camo: Boolean = false) {
    /** Red over gloss black, the bike as Donovan has it. */
    ROCK_RED("rock-red", "Rock Red", 0xb3121a, 0x121214, 0x86888c),
    /** Two-tone lake blue over white. */
    LAKE_BLUE("lake-blue", "Lake Blue", 0x2c6aa0, 0xe9e9e4, 0x86888c),
    /** The Scram 411's white with red accents. */
    WHITE_FLAME("white-flame", "White Flame", 0xefefea, 0xb81e1c, 0x2a2a2c),
    /** Woodland camouflage on the tank, side panels and fenders. */
    PINE_GREEN("pine-green", "Pine Green", 0x3c5236, 0x3c5236, 0x2c3a28, camo = true);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: ROCK_RED
    }
}

/**
 * Donovan's Royal Enfield Himalayan 411 (Rock Red as Donovan has it; other factory paint
 * schemes via [HimalayanPaint]), as set up: tall clear touring screen over
 * the silver headlight cowl, phone mount above the round speedo, Tusk handguards, braced bars,
 * black tank guards with Royal Enfield plates, engine crash bars and skid plate, black pannier
 * frames and rear rack (no boxes), knobby tyres on black spoked rims, the stock muffler with its
 * stainless heat shield, and a gold chain.
 *
 * Both detail levels share the same solid shapes (engine, airbox, side panels, seat pan) so the
 * bike never looks hollow; [Detail.SHOWROOM] adds the small stuff: cables and hoses, bolts,
 * gaiter ribs, shock spring, chain links, gauge needles, levers and stand, and rounder surfaces.
 */
class HimalayanLook(private val detail: Detail = Detail.RIDE, private val paint: HimalayanPaint = HimalayanPaint.ROCK_RED) : BikeLook {
    // Trail 111 mm at 26.5 deg rake: the steering axis meets the ground 0.111 m ahead of the
    // front contact patch, and the head sits 0.95 m up that line.
    override val headX = 0.43f
    override val headY = 0.95f
    override val rakeDeg = 26.5
    override val pivotX = -0.06f
    override val pivotY = 0.38f
    override val rider = RiderFit(-0.20f, 0.87f, 0.0f, 0.30f, 0.39f, 1.07f, 0.34f)
    // The phone in its Quad Lock mount above the gauges (landscape, upright, frame-mounted):
    // the lit display just in front of the phone body.
    override val navScreen = NavScreen(0.4795f, 1.215f, 0f, 0.150f, 0.080f)
    // The stock round headlight: a warm, fairly narrow beam.
    override val headlight = Headlight(0.645f, 0.96f, 22f, 0xfff0d8, 1.0f)

    private val hi = detail == Detail.SHOWROOM
    /** Facets around lofted shells and round tubes. */
    private val ls = if (hi) 20 else 12
    private val ts = if (hi) 12 else 7

    private val tankFront = MeshBuilder.color(paint.tankFront)
    private val tankRear = MeshBuilder.color(paint.tankRear)
    private val gloss = MeshBuilder.color(0x121214)
    private val frame = MeshBuilder.color(0x1b1b1c)
    private val engine = MeshBuilder.color(0x262628)
    private val fin = MeshBuilder.color(0x3b3b3e)
    private val silver = MeshBuilder.color(0xb4b8bc)
    private val chrome = MeshBuilder.color(0xd8dadd)
    private val header = MeshBuilder.color(0x8c6c42)
    private val muffler = MeshBuilder.color(0x222224)
    private val steel = MeshBuilder.color(0xd2d4d6)
    private val seat = MeshBuilder.color(0x161616)
    private val panel = MeshBuilder.color(0x2c2c2f)
    private val letters = MeshBuilder.color(0xc6c8ca)
    private val amber = MeshBuilder.color(0xe8890e)
    private val tail = MeshBuilder.color(0xb8141a, 0.55f)          // lit tail light
    private val plate = MeshBuilder.color(0xe4ece2)
    private val chain = MeshBuilder.color(0xb48c3c)
    private val chainDark = MeshBuilder.color(0x6a5428)
    private val cowl = MeshBuilder.color(0x9a9ea3)
    private val lens = MeshBuilder.color(0xf2f0e4)
    private val tyre = MeshBuilder.color(0x1c1c1c)
    private val knob = MeshBuilder.color(0x242424)
    private val spoke = MeshBuilder.color(0xc4c6c8)
    private val tusk = MeshBuilder.color(0xc81e1e)
    private val gauge = MeshBuilder.color(0xd8d8d0)
    private val rubber = MeshBuilder.color(0x202020)
    private val cable = MeshBuilder.color(0x0e0e0e)
    private val bolt = MeshBuilder.color(0x9a9ea2)
    private val disc = MeshBuilder.color(0xcfd2d5)
    private val screenGlass = MeshBuilder.color(0.80f, 0.86f, 0.90f, 0.28f)

    private fun st(x: Float, cy: Float, hy: Float, hz: Float) = floatArrayOf(x, cy, hy, hz)

    /**
     * Bodywork: with a camouflage paint, every triangle [block] adds (all built from separate
     * flat triangles) is recoloured from a blotch pattern at its centre.
     */
    private inline fun MeshBuilder.bodywork(block: MeshBuilder.() -> Unit) {
        val from = vertCount
        block()
        if (!paint.camo) return
        val v = verts
        var i = from
        while (i + 2 < vertCount) {
            var cx = 0f; var cy = 0f; var cz = 0f
            for (k in 0..2) { val o = (i + k) * MeshBuilder.STRIDE; cx += v[o]; cy += v[o + 1]; cz += v[o + 2] }
            val c = camo(cx / 3, cy / 3, cz / 3)
            for (k in 0..2) v[(i + k) * MeshBuilder.STRIDE + 6] = c
            i += 3
        }
    }

    /** Woodland camouflage: pine green with dark, olive and sage blotches. */
    private fun camo(x: Float, y: Float, z: Float): Float {
        val n = Noise.value2(x * 11.0 + z * 4.0, y * 11.0 - z * 6.0, 31) * 0.7 + Noise.value2(x * 27.0 - z * 9.0, y * 27.0 + z * 7.0, 32) * 0.3
        return when {
            n < 0.36 -> CAMO_DARK
            n < 0.55 -> CAMO_PINE
            n < 0.7 -> CAMO_OLIVE
            else -> CAMO_SAGE
        }
    }

    /** Round tube at this detail level's facet count. */
    private fun MeshBuilder.rod(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r0: Float, r1: Float, c: Float, end: Float = c) =
        tube(x0, y0, z0, x1, y1, z1, r0, r1, ts, c, end)

    /** A bent tube through [pts] (x, y, |z|) on the side [s] (+1 right, -1 left). */
    private fun MeshBuilder.bent(pts: Array<FloatArray>, s: Float, t: Float, c: Float) {
        for (k in 0 until pts.size - 1) beam(pts[k][0], pts[k][1], s * pts[k][2], pts[k + 1][0], pts[k + 1][1], s * pts[k + 1][2], t, c)
    }

    /** A flat disc facing ±Z (logos, covers, horn). */
    private fun MeshBuilder.discZ(x: Float, y: Float, z0: Float, z1: Float, r: Float, c: Float, face: Float = c) =
        tube(x, y, z0, x, y, z1, r, r, if (hi) 20 else 12, c, face)

    override fun chassis(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        val t = 0.016f

        // --- frame (half-duplex split cradle, black) ---------------------------------------
        for (s in listOf(-1f, 1f)) {
            m.beam(headX, headY - 0.02f, 0f, -0.03f, 0.75f, s * 0.08f, t, frame)           // top rails
            m.beam(headX + 0.05f, headY - 0.12f, 0f, 0.39f, 0.42f, s * 0.07f, t, frame)    // down tubes
            m.beam(0.39f, 0.42f, s * 0.07f, 0.36f, 0.19f, s * 0.08f, t, frame)
            m.beam(0.36f, 0.19f, s * 0.08f, -0.05f, 0.21f, s * 0.09f, t, frame)           // cradle
            m.beam(-0.05f, 0.21f, s * 0.09f, -0.05f, 0.77f, s * 0.09f, t, frame)          // seat posts
            m.beam(-0.05f, 0.77f, s * 0.09f, -0.68f, 0.83f, s * 0.10f, t * 0.85f, frame)  // subframe
            m.beam(-0.05f, 0.44f, s * 0.10f, -0.52f, 0.81f, s * 0.10f, t * 0.85f, frame)
            m.box(-0.06f, 0.39f, s * 0.112f, 0.055f, 0.065f, 0.006f, frame)               // pivot plates
        }
        m.beam(-0.06f, 0.38f, -0.125f, -0.06f, 0.38f, 0.125f, 0.018f, silver)              // pivot bolt
        m.discZ(0.0f, 0.36f, -0.123f, -0.108f, 0.045f, chain)                                  // front sprocket

        // --- engine: 411 cc air/oil-cooled single -------------------------------------------
        // Crankcase and gearbox from the down tubes back to the swingarm pivot.
        m.loft(arrayOf(st(0.37f, 0.33f, 0.10f, 0.11f), st(0.33f, 0.33f, 0.15f, 0.135f), st(0.06f, 0.32f, 0.15f, 0.14f),
            st(-0.06f, 0.35f, 0.12f, 0.13f), st(-0.09f, 0.38f, 0.08f, 0.10f)), engine, ls, 0.65f)
        m.box(0.02f, 0.50f, 0f, 0.08f, 0.05f, 0.11f, engine)                                // gearbox hump
        // Barrel and head finned all the way up to the tank: deep fins, wider than the
        // barrel and closely stacked, as on the real air/oil-cooled 411.
        m.box(0.235f, 0.60f, 0f, 0.07f, 0.15f, 0.075f, engine)
        val fins = if (hi) 15 else 8
        for (k in 0 until fins) {
            val y = 0.465f + k * 0.29f / (fins - 1)
            val head = y > 0.70f
            val c = if (hi) fin else if (k % 2 == 0) fin else engine
            m.box(0.215f + (y - 0.45f) * 0.25f, y, 0f, if (head) 0.10f else 0.112f, if (hi) 0.0055f else 0.009f, if (head) 0.118f else 0.128f, c)
        }
        m.box(0.29f, 0.77f, 0f, 0.075f, 0.018f, 0.085f, engine)                             // cam cover
        // Left: big generator cover with the winged-R logo (silver disc, black ring, silver R).
        m.discZ(0.12f, 0.31f, -0.14f, -0.156f, 0.115f, engine)
        m.discZ(0.12f, 0.31f, -0.156f, -0.160f, 0.062f, silver)
        m.discZ(0.12f, 0.31f, -0.160f, -0.162f, 0.054f, MeshBuilder.color(0x141414))
        m.discZ(0.12f, 0.31f, -0.162f, -0.164f, 0.046f, MeshBuilder.color(0x8e9296))
        if (hi) {
            // The "R" and its wings, raised in silver.
            m.box(0.104f, 0.31f, -0.166f, 0.006f, 0.026f, 0.002f, chrome)
            m.box(0.122f, 0.326f, -0.166f, 0.016f, 0.006f, 0.002f, chrome)
            m.box(0.122f, 0.31f, -0.166f, 0.016f, 0.005f, 0.002f, chrome)
            m.beam(0.118f, 0.31f, -0.166f, 0.136f, 0.284f, -0.166f, 0.003f, chrome)
            for (w in listOf(-1f, 1f)) for (k in 0 until 3) m.box(0.12f + w * (0.034f + k * 0.008f), 0.322f - k * 0.008f, -0.166f, 0.006f, 0.0025f, 0.002f, chrome)
        }
        // Right: clutch cover, oil filler cap, and the sprocket cover / pivot badge on the left.
        m.discZ(0.15f, 0.31f, 0.14f, 0.156f, 0.10f, engine)
        m.discZ(0.20f, 0.42f, 0.13f, 0.152f, 0.022f, MeshBuilder.color(0x1a1a1a))
        m.discZ(0.0f, 0.36f, -0.135f, -0.148f, 0.055f, engine)                                // sprocket cover
        m.discZ(-0.06f, 0.39f, -0.118f, -0.128f, 0.036f, MeshBuilder.color(0x141414))         // pivot cover
        m.discZ(-0.06f, 0.39f, -0.128f, -0.131f, 0.028f, MeshBuilder.color(0x1c3ca8))          // blue Royal Enfield badge
        // Intake, airbox and battery under the seat.
        m.rod(0.16f, 0.66f, 0f, 0.02f, 0.68f, 0f, 0.032f, 0.032f, MeshBuilder.color(0x3a3a3a))
        m.box(-0.13f, 0.60f, 0f, 0.12f, 0.13f, 0.11f, engine)
        m.box(-0.31f, 0.67f, 0f, 0.07f, 0.08f, 0.09f, MeshBuilder.color(0x202022))
        // Electrics and the frame space under the tank.
        m.loft(arrayOf(st(0.42f, 0.80f, 0.03f, 0.07f), st(0.30f, 0.79f, 0.04f, 0.11f), st(0.05f, 0.79f, 0.04f, 0.11f)), frame, 8, 0.8f)
        m.box(0.12f, 0.745f, 0.06f, 0.05f, 0.025f, 0.03f, MeshBuilder.color(0x2a2a2a))     // coil
        // Tall oil cooler standing ahead of the barrel (left of centre), round horn on the right.
        m.box(0.40f, 0.60f, -0.05f, 0.012f, 0.105f, 0.065f, MeshBuilder.color(0x222222))
        m.discZ(0.40f, 0.56f, 0.125f, 0.15f, 0.045f, MeshBuilder.color(0x2a2a2a), silver)
        // Rear brake reservoir (white with a black cap) on the right behind the engine.
        m.rod(-0.02f, 0.45f, 0.13f, -0.02f, 0.50f, 0.13f, 0.018f, 0.018f, MeshBuilder.color(0xe8e8e4))
        m.rod(-0.02f, 0.50f, 0.13f, -0.02f, 0.52f, 0.13f, 0.02f, 0.02f, MeshBuilder.color(0x161616))
        // Rear monoshock (behind the airbox, visible through the frame).
        m.rod(-0.10f, 0.36f, 0f, -0.24f, 0.70f, 0f, 0.026f, 0.026f, MeshBuilder.color(0x303030))
        if (hi) for (k in 0..9) {
            val f = 0.15f + k * 0.07f
            val x = -0.10f - 0.14f * f; val y = 0.36f + 0.34f * f
            m.rod(x, y, 0f, x - 0.004f, y + 0.012f, 0f, 0.045f, 0.045f, MeshBuilder.color(0x4a4a4a))
        } else m.rod(-0.12f, 0.41f, 0f, -0.21f, 0.63f, 0f, 0.042f, 0.042f, MeshBuilder.color(0x4a4a4a))

        // --- tank: two-tone front and rear with a stripe between, round badges -----------------
        m.bodywork { loft(arrayOf(st(0.50f, 0.85f, 0.05f, 0.08f), st(0.46f, 0.86f, 0.10f, 0.15f), st(0.38f, 0.865f, 0.115f, 0.18f), st(0.29f, 0.87f, 0.11f, 0.18f)), tankFront, ls, 0.55f) }
        m.loft(arrayOf(st(0.30f, 0.87f, 0.115f, 0.185f), st(0.284f, 0.87f, 0.115f, 0.185f)), MeshBuilder.color(paint.stripe), ls, 0.55f)
        m.bodywork { loft(arrayOf(st(0.285f, 0.87f, 0.11f, 0.181f), st(0.18f, 0.875f, 0.105f, 0.17f), st(0.08f, 0.875f, 0.09f, 0.15f), st(0.02f, 0.87f, 0.06f, 0.12f)), tankRear, ls, 0.55f) }
        for (s in listOf(-1f, 1f)) {
            // Holographic North East Adventure Rally sticker.
            m.discZ(0.40f, 0.89f, s * 0.176f, s * 0.186f, 0.042f, MeshBuilder.color(0xc8c6d8))
            if (hi) {
                m.discZ(0.40f, 0.89f, s * 0.186f, s * 0.187f, 0.031f, MeshBuilder.color(0x9a9ab4))
                m.discZ(0.40f, 0.89f, s * 0.187f, s * 0.188f, 0.024f, MeshBuilder.color(0xd8d0e4))
            }
        }
        m.cone(0.24f, 0.975f, 0f, 0.048f, 0.044f, 0.012f, ls, MeshBuilder.color(0x2a2a2a)) // fuel cap
        if (hi) for (k in 0 until 6) {
            val a = k * MeshBuilder.TWO_PI / 6
            m.cone(0.24f + cos(a) * 0.036f, 0.987f, sin(a) * 0.036f, 0.005f, 0.005f, 0.004f, 6, bolt)
        }

        // --- seat: rider seat tucked under the tank, raised pillion, black pan below ----------
        m.loft(arrayOf(st(0.15f, 0.805f, 0.025f, 0.09f), st(0.05f, 0.81f, 0.042f, 0.13f), st(-0.06f, 0.805f, 0.048f, 0.155f),
            st(-0.30f, 0.805f, 0.048f, 0.16f), st(-0.40f, 0.82f, 0.048f, 0.135f)), seat, ls, 0.6f)
        m.loft(arrayOf(st(-0.39f, 0.85f, 0.045f, 0.12f), st(-0.48f, 0.865f, 0.04f, 0.12f), st(-0.64f, 0.87f, 0.04f, 0.11f), st(-0.69f, 0.86f, 0.035f, 0.09f)), seat, ls, 0.6f)
        m.loft(arrayOf(st(0.10f, 0.77f, 0.02f, 0.10f), st(-0.40f, 0.775f, 0.025f, 0.12f), st(-0.68f, 0.81f, 0.02f, 0.10f)), frame, 8, 0.9f)

        // --- side panels with HIMALAYAN lettering (cover the airbox triangle) ---------------
        for (s in listOf(-1f, 1f)) {
            val z = s * 0.136f
            m.bodywork {
                twoSidedTri(0.0f, 0.77f, z, -0.40f, 0.79f, z, -0.28f, 0.58f, z, panel)
                twoSidedTri(0.0f, 0.77f, z, -0.28f, 0.58f, z, -0.06f, 0.43f, z, panel)
            }
            m.box(-0.15f, 0.66f, s * 0.139f, 0.09f, 0.012f, 0.002f, letters)
        }

        // --- exhaust (right side): heat-coloured header, black muffler, steel shield ---------
        val hp = arrayOf(floatArrayOf(0.32f, 0.70f, 0.05f), floatArrayOf(0.41f, 0.50f, 0.08f), floatArrayOf(0.38f, 0.23f, 0.10f),
            floatArrayOf(0.02f, 0.20f, 0.13f), floatArrayOf(-0.20f, 0.30f, 0.18f), floatArrayOf(-0.30f, 0.38f, 0.20f))
        for (k in 0 until hp.size - 1) m.rod(hp[k][0], hp[k][1], hp[k][2], hp[k + 1][0], hp[k + 1][1], hp[k + 1][2], 0.022f, 0.022f, header)
        m.rod(-0.28f, 0.39f, 0.21f, -0.74f, 0.55f, 0.21f, 0.078f, 0.072f, muffler)
        m.rod(-0.74f, 0.55f, 0.21f, -0.79f, 0.567f, 0.21f, 0.055f, 0.05f, steel, MeshBuilder.color(0x2a2a2a))
        m.rod(-0.55f, 0.49f, 0.215f, -0.75f, 0.56f, 0.215f, 0.086f, 0.082f, steel)          // perforated heat shield
        m.rod(-0.32f, 0.405f, 0.21f, -0.28f, 0.39f, 0.21f, 0.06f, 0.079f, steel)            // inlet cone
        m.beam(-0.42f, 0.47f, 0.18f, -0.44f, 0.62f, 0.13f, 0.01f, frame)                    // hanger
        if (hi) for (k in 0 until 4) for (j in 0 until 3) {                                  // shield perforations
            val f = 0.2f + k * 0.18f
            m.box(-0.57f - 0.16f * f, 0.53f + 0.06f * f + j * 0.02f, 0.30f, 0.006f, 0.004f, 0.002f, MeshBuilder.color(0x4a4a4a))
        }

        // --- crash bars, skid plate, tank guards, controls ----------------------------------
        for (s in listOf(-1f, 1f)) {
            // Modern Tech crash bars: a cage from the frame out around the front of the engine,
            // with a mid bar, a diagonal across the cases and a slider stub.
            val ct = 0.013f
            m.bent(arrayOf(floatArrayOf(0.33f, 0.66f, 0.10f), floatArrayOf(0.42f, 0.64f, 0.20f), floatArrayOf(0.46f, 0.58f, 0.245f),
                floatArrayOf(0.47f, 0.34f, 0.245f), floatArrayOf(0.42f, 0.24f, 0.235f), floatArrayOf(0.20f, 0.22f, 0.22f),
                floatArrayOf(0.02f, 0.26f, 0.16f), floatArrayOf(-0.03f, 0.28f, 0.10f)), s, ct, frame)
            m.bent(arrayOf(floatArrayOf(0.46f, 0.58f, 0.245f), floatArrayOf(0.20f, 0.58f, 0.225f), floatArrayOf(0.05f, 0.52f, 0.16f), floatArrayOf(-0.03f, 0.54f, 0.10f)), s, ct, frame)
            m.bent(arrayOf(floatArrayOf(0.20f, 0.58f, 0.225f), floatArrayOf(0.08f, 0.27f, 0.20f)), s, ct, frame)
            m.bent(arrayOf(floatArrayOf(0.47f, 0.42f, 0.245f), floatArrayOf(0.25f, 0.42f, 0.232f), floatArrayOf(0.15f, 0.44f, 0.215f)), s, ct, frame)
            m.rod(0.40f, 0.48f, s * 0.24f, 0.40f, 0.48f, s * 0.29f, 0.017f, 0.017f, frame, MeshBuilder.color(0x0c0c0c))
            // Tank guards: a rounded tube loop beside the front of the tank, the embossed
            // Royal Enfield plate bridging it, braced to the frame.
            m.bent(arrayOf(floatArrayOf(headX + 0.01f, headY - 0.03f, 0.07f), floatArrayOf(0.46f, 0.935f, 0.17f), floatArrayOf(0.50f, 0.93f, 0.205f),
                floatArrayOf(0.515f, 0.88f, 0.21f), floatArrayOf(0.505f, 0.70f, 0.21f), floatArrayOf(0.48f, 0.65f, 0.205f),
                floatArrayOf(0.36f, 0.64f, 0.20f), floatArrayOf(0.32f, 0.67f, 0.195f), floatArrayOf(0.31f, 0.86f, 0.19f),
                floatArrayOf(0.34f, 0.92f, 0.18f), floatArrayOf(0.46f, 0.935f, 0.17f)), s, 0.012f, frame)
            m.beam(0.36f, 0.64f, s * 0.20f, 0.30f, 0.66f, s * 0.11f, 0.011f, frame)
            m.box(0.41f, 0.79f, s * 0.205f, 0.097f, 0.03f, 0.005f, frame)                 // Royal Enfield plate
            if (hi) {
                // Embossed letters and the two slots either end.
                for (k in 0 until 12) m.box(0.355f + k * 0.0105f, 0.79f, s * 0.211f, 0.0035f, 0.012f, 0.0015f, MeshBuilder.color(0x2e2e2e))
                for (e in listOf(0.325f, 0.495f)) m.box(e, 0.79f, s * 0.211f, 0.004f, 0.018f, 0.0015f, MeshBuilder.color(0x070707))
            } else m.box(0.41f, 0.79f, s * 0.211f, 0.065f, 0.011f, 0.0015f, MeshBuilder.color(0x2e2e2e))
            // Turn signals: black ball housings with amber lenses, off the headlight frame.
            m.beam(0.55f, 0.93f, s * 0.10f, 0.55f, 0.93f, s * 0.205f, 0.009f, frame)
            m.blob(0.56f, 0.93f, s * 0.235f, 0.036f, 0.034f, 0.034f, MeshBuilder.color(0x161616))
            m.rod(0.585f, 0.93f, s * 0.24f, 0.594f, 0.93f, s * 0.245f, 0.022f, 0.02f, amber)
            // Wide pegs on brackets.
            m.box(rider.pegX, rider.pegY - 0.02f, s * 0.17f, 0.05f, 0.012f, 0.05f, MeshBuilder.color(0x3a3a3a))
            m.beam(rider.pegX - 0.06f, rider.pegY + 0.02f, s * 0.11f, rider.pegX, rider.pegY - 0.02f, s * 0.13f, 0.014f, frame)
        }
        m.beam(rider.pegX + 0.01f, rider.pegY + 0.01f, -0.15f, rider.pegX + 0.13f, rider.pegY + 0.04f, -0.16f, 0.008f, frame) // gear lever
        m.beam(rider.pegX - 0.02f, rider.pegY + 0.0f, 0.15f, rider.pegX + 0.13f, rider.pegY - 0.04f, 0.155f, 0.008f, frame)  // brake pedal
        m.loft(arrayOf(st(0.43f, 0.20f, 0.012f, 0.09f), st(0.38f, 0.165f, 0.014f, 0.13f), st(0.03f, 0.165f, 0.014f, 0.13f), st(-0.03f, 0.19f, 0.012f, 0.11f)), MeshBuilder.color(0x1e1e1e), 8, 0.85f)
        if (hi) {
            for (k in 0 until 5) m.box(0.34f - k * 0.07f, 0.165f, 0.13f, 0.02f, 0.004f, 0.002f, MeshBuilder.color(0x0a0a0a))    // skid plate slots
            // Centre stand, folded up.
            for (s in listOf(-1f, 1f)) m.beam(-0.03f, 0.22f, s * 0.10f, -0.22f, 0.26f, s * 0.11f, 0.011f, frame)
            m.beam(-0.22f, 0.26f, -0.11f, -0.22f, 0.26f, 0.11f, 0.01f, frame)
            // Throttle and clutch cables, brake line, wiring.
            m.bent(arrayOf(floatArrayOf(headX - 0.02f, headY + 0.06f, 0.06f), floatArrayOf(0.36f, 0.84f, 0.11f), floatArrayOf(0.20f, 0.70f, 0.12f), floatArrayOf(0.12f, 0.66f, 0.05f)), 1f, 0.004f, cable)
            m.bent(arrayOf(floatArrayOf(headX - 0.02f, headY + 0.06f, 0.06f), floatArrayOf(0.38f, 0.80f, 0.12f), floatArrayOf(0.22f, 0.52f, 0.15f), floatArrayOf(0.16f, 0.42f, 0.15f)), -1f, 0.004f, cable)
            m.bent(arrayOf(floatArrayOf(0.30f, 0.78f, 0.06f), floatArrayOf(0.10f, 0.76f, 0.08f), floatArrayOf(-0.10f, 0.74f, 0.08f)), -1f, 0.006f, cable)
            m.bent(arrayOf(floatArrayOf(rider.pegX + 0.06f, rider.pegY + 0.02f, 0.13f), floatArrayOf(-0.20f, 0.35f, 0.13f), floatArrayOf(-b + 0.05f, rr + 0.07f, 0.09f)), 1f, 0.004f, cable)
            // Bolts on the cases.
            for (k in 0 until 8) {
                val a = k * MeshBuilder.TWO_PI / 8
                m.discZ(0.12f + cos(a) * 0.098f, 0.31f + sin(a) * 0.098f, -0.156f, -0.161f, 0.006f, bolt)
                m.discZ(0.15f + cos(a) * 0.088f, 0.31f + sin(a) * 0.088f, 0.156f, 0.161f, 0.006f, bolt)
            }
            // Oil cooler fins and hoses.
            for (k in 0 until 9) m.box(0.413f, 0.505f + k * 0.023f, -0.05f, 0.002f, 0.003f, 0.06f, MeshBuilder.color(0x3c3c3c))
            for (s in listOf(-1f, 1f)) m.bent(arrayOf(floatArrayOf(0.39f, 0.50f, 0.05f), floatArrayOf(0.36f, 0.44f, 0.10f), floatArrayOf(0.30f, 0.42f, 0.12f)), s, 0.008f, rubber)
            // Clutch actuating arm (silver) and its cable on the right case.
            m.beam(0.06f, 0.45f, 0.152f, 0.02f, 0.48f, 0.156f, 0.006f, chrome)
            m.rod(0.02f, 0.48f, 0.156f, 0.02f, 0.49f, 0.156f, 0.008f, 0.008f, chrome)
            m.bent(arrayOf(floatArrayOf(0.06f, 0.45f, 0.152f), floatArrayOf(0.14f, 0.50f, 0.15f), floatArrayOf(0.22f, 0.52f, 0.15f)), 1f, 0.004f, cable)
            // Peg studs.
            for (s in listOf(-1f, 1f)) for (k in 0 until 4) m.cone(rider.pegX - 0.035f + k * 0.023f, rider.pegY - 0.008f, s * 0.17f, 0.004f, 0.002f, 0.008f, 5, bolt)
            m.cone(0.30f, 0.79f, 0.03f, 0.008f, 0.006f, 0.04f, 6, MeshBuilder.color(0x5a5a5a))  // spark plug cap
        }

        // --- headlight, cowl, instruments (frame-mounted on the Himalayan) ------------------
        m.rod(0.56f, 0.96f, 0f, 0.62f, 0.96f, 0f, 0.088f, 0.09f, gloss)                     // bucket
        m.tube(0.618f, 0.96f, 0f, 0.634f, 0.96f, 0f, 0.097f, 0.095f, ls, MeshBuilder.color(0x161616), MeshBuilder.color(0x161616))  // black bezel
        m.tube(0.630f, 0.96f, 0f, 0.641f, 0.96f, 0f, 0.085f, 0.085f, ls, MeshBuilder.color(0xc8d0d4), MeshBuilder.color(0xf2f4f0, 0.35f))  // glass lens (lamp on) over the chrome reflector
        if (hi) {
            m.tube(0.641f, 0.96f, 0f, 0.643f, 0.96f, 0f, 0.06f, 0.06f, ls, MeshBuilder.color(0xffffff, 0.2f), MeshBuilder.color(0xffffff, 0.2f))  // reflector
            m.tube(0.643f, 0.955f, 0f, 0.646f, 0.955f, 0f, 0.016f, 0.016f, 10, MeshBuilder.color(0xfffbe8, 0f), MeshBuilder.color(0xfffbe8, 0f))
            for (a in listOf(0.6f, 2.5f, 4.4f)) m.cone(0.64f, 0.96f + sin(a) * 0.09f, cos(a) * 0.09f, 0.005f, 0.005f, 0.004f, 6, bolt)
        }
        m.loft(arrayOf(st(0.50f, 1.04f, 0.12f, 0.16f), st(0.58f, 1.05f, 0.10f, 0.15f), st(0.64f, 1.06f, 0.06f, 0.12f)), cowl, ls, 0.5f)
        // Headlight frame: black tubes from the steering head, with the Tusk-stickered plates.
        for (s in listOf(-1f, 1f)) {
            m.bent(arrayOf(floatArrayOf(headX + 0.04f, headY - 0.05f, 0.06f), floatArrayOf(0.55f, 0.88f, 0.10f), floatArrayOf(0.555f, 1.06f, 0.11f),
                floatArrayOf(0.47f, 1.05f, 0.08f)), s, 0.011f, frame)
            m.box(0.545f, 0.925f, s * 0.118f, 0.022f, 0.05f, 0.004f, frame)
            m.box(0.545f, 0.92f, s * 0.123f, 0.014f, 0.022f, 0.0015f, tusk)
        }

        // Instruments, as seen from the saddle: big round speedo (chrome ring, black face,
        // LCD), small tacho above right, fuel/temperature lozenge below it, warning lights.
        m.box(0.515f, 1.05f, 0.01f, 0.015f, 0.065f, 0.105f, gloss)                           // cluster housing
        m.tube(0.506f, 1.05f, -0.03f, 0.498f, 1.05f, -0.03f, 0.06f, 0.06f, ls, chrome, chrome)
        m.tube(0.499f, 1.05f, -0.03f, 0.495f, 1.05f, -0.03f, 0.054f, 0.054f, ls, gloss, gloss)
        m.box(0.494f, 1.034f, -0.03f, 0.001f, 0.012f, 0.027f, MeshBuilder.color(0xc8d2b0))  // LCD
        m.tube(0.506f, 1.088f, 0.058f, 0.498f, 1.088f, 0.058f, 0.031f, 0.031f, ls, chrome, chrome)
        m.tube(0.499f, 1.088f, 0.058f, 0.495f, 1.088f, 0.058f, 0.027f, 0.027f, ls, gloss, gloss)
        m.box(0.499f, 1.025f, 0.058f, 0.004f, 0.02f, 0.036f, chrome)                          // fuel / temp lozenge
        m.box(0.496f, 1.025f, 0.058f, 0.002f, 0.016f, 0.031f, gloss)
        // Crossbar mount above the gauges, round Quad Lock mount, phone in landscape.
        m.rod(0.505f, 1.135f, -0.10f, 0.505f, 1.135f, 0.10f, 0.011f, 0.011f, gloss)
        m.tube(0.505f, 1.16f, 0.055f, 0.495f, 1.16f, 0.055f, 0.032f, 0.032f, ls, gloss, MeshBuilder.color(0x1e1e1e))
        m.box(0.488f, 1.215f, 0f, 0.006f, 0.046f, 0.083f, MeshBuilder.color(0x0c0e12))     // phone
        if (hi) {
            m.beam(0.494f, 1.05f, -0.03f, 0.494f, 1.052f, -0.075f, 0.003f, MeshBuilder.color(0xe03020))   // speedo needle (at rest)
            m.beam(0.494f, 1.088f, 0.058f, 0.494f, 1.068f, 0.05f, 0.002f, MeshBuilder.color(0xe03020))    // tacho needle
            for (k in 0..10) {                                                                              // speedo ticks
                val a = 3.6f - k * 0.38f
                m.box(0.494f, 1.05f + sin(a) * 0.046f, -0.03f + cos(a) * 0.046f, 0.001f, 0.003f, 0.003f, MeshBuilder.color(0xe8e8e8))
            }
            for (r in 0 until 4) for (c in 0 until 2)                                                       // warning lights
                m.box(0.496f, 1.075f - r * 0.013f, 0.012f + c * 0.013f, 0.002f, 0.0045f, 0.0045f, MeshBuilder.color(0x1c1c1c))
            m.box(0.494f, 1.022f, 0.072f, 0.001f, 0.008f, 0.009f, MeshBuilder.color(0xb8c8a0))             // fuel LCD
            m.tube(0.495f, 1.16f, 0.055f, 0.492f, 1.16f, 0.055f, 0.012f, 0.012f, 10, silver, silver)        // mount centre
            m.box(0.481f, 1.215f, 0f, 0.001f, 0.04f, 0.076f, MeshBuilder.color(0x1a2a3e))                   // phone screen
            for (s in listOf(-1f, 1f)) m.cone(0.62f, 1.07f, s * 0.11f, 0.006f, 0.006f, 0.006f, 6, bolt)    // cowl screws
            m.box(0.595f, 1.14f, -0.07f, 0.002f, 0.024f, 0.026f, MeshBuilder.color(0x38b8ac))               // Northwoods ADV sticker
        }

        // --- rear: pannier frames, rack, tail, plate ------------------------------------------
        for (s in listOf(-1f, 1f)) {
            val z = 0.21f
            m.bent(arrayOf(floatArrayOf(-0.24f, 0.44f, z), floatArrayOf(-0.62f, 0.44f, z), floatArrayOf(-0.66f, 0.78f, z), floatArrayOf(-0.27f, 0.78f, z), floatArrayOf(-0.24f, 0.44f, z)), s, 0.011f, frame)
            m.beam(-0.44f, 0.44f, s * z, -0.46f, 0.78f, s * z, 0.01f, frame)
            m.beam(-0.27f, 0.78f, s * z, -0.20f, 0.78f, s * 0.10f, 0.01f, frame)
            m.beam(-0.64f, 0.70f, s * z, -0.62f, 0.80f, s * 0.10f, 0.01f, frame)
            m.beam(-0.58f, 0.86f, s * 0.10f, -0.92f, 0.915f, s * 0.10f, 0.012f, frame)      // rack rails
            m.beam(-0.86f, 0.80f, s * 0.08f, -0.84f, 0.80f, s * 0.15f, 0.007f, frame)      // signal stalks
            m.blob(-0.84f, 0.80f, s * 0.165f, 0.028f, 0.024f, 0.024f, amber)
            m.tube(-0.985f, 0.66f, s * 0.08f, -0.995f, 0.66f, s * 0.08f, 0.025f, 0.025f, 10, tail, tail)  // reflectors
            if (hi) for (k in 0 until 3) m.beam(-0.30f - k * 0.12f, 0.44f, s * z, -0.30f - k * 0.12f + 0.02f, 0.78f, s * z, 0.0125f, MeshBuilder.color(0x262626)) // wrapped tube
        }
        m.box(-0.79f, 0.92f, 0f, 0.13f, 0.007f, 0.12f, frame)                              // rack plate
        if (hi) for (k in 0 until 4) m.box(-0.70f - k * 0.06f, 0.928f, 0f, 0.012f, 0.002f, 0.08f, MeshBuilder.color(0x0a0a0a))
        m.bodywork { loft(arrayOf(st(-0.64f, 0.80f, 0.018f, 0.10f), st(-0.86f, 0.79f, 0.018f, 0.095f), st(-0.96f, 0.70f, 0.015f, 0.075f), st(-1.0f, 0.60f, 0.012f, 0.06f)), gloss, 10, 0.7f) }
        m.box(-0.89f, 0.855f, 0f, 0.035f, 0.03f, 0.075f, tail)                             // tail light
        m.box(-1.005f, 0.60f, 0f, 0.004f, 0.055f, 0.09f, plate)                            // NH plate
        m.box(-1.009f, 0.60f, 0f, 0.002f, 0.018f, 0.06f, MeshBuilder.color(0x2a7a4a))
    }

    override fun swingarm(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        for (s in listOf(-1f, 1f)) {
            m.beam(pivotX, pivotY, s * 0.10f, -b, rr, s * 0.10f, 0.026f, frame)            // box-section arms
            m.box(-b, rr, s * 0.10f, 0.03f, 0.022f, 0.012f, frame)                          // axle adjusters
        }
        m.beam(-b + 0.12f, rr + 0.02f, -0.10f, -b + 0.12f, rr + 0.02f, 0.10f, 0.018f, frame) // brace

        // --- chain (left) and guard ---------------------------------------------------------------
        val z = -0.115f
        val runs = arrayOf(floatArrayOf(0.0f, 0.36f + 0.045f, -b, rr + 0.105f), floatArrayOf(0.0f, 0.36f - 0.045f, -b, rr - 0.105f))
        for (rn in runs) {
            if (hi) {
                val n = 28
                for (k in 0 until n) {
                    val f0 = k.toFloat() / n; val f1 = (k + 0.85f) / n
                    m.beam(rn[0] + (rn[2] - rn[0]) * f0, rn[1] + (rn[3] - rn[1]) * f0, z, rn[0] + (rn[2] - rn[0]) * f1, rn[1] + (rn[3] - rn[1]) * f1, z, 0.006f,
                        if (k % 2 == 0) chain else chainDark)
                }
            } else m.beam(rn[0], rn[1], z, rn[2], rn[3], z, 0.006f, chain)
        }
        m.beam(-0.02f, 0.44f, z - 0.012f, -b + 0.12f, rr + 0.13f, z - 0.012f, 0.012f, frame)  // chain guard
    }

    override fun glass(m: MeshBuilder, spec: BikeSpec) {
        // Tall clear touring screen, gently curved, rising above the cowl.
        val rows = if (hi) arrayOf(floatArrayOf(0.60f, 1.10f, 0.15f), floatArrayOf(0.578f, 1.19f, 0.168f), floatArrayOf(0.555f, 1.28f, 0.18f),
            floatArrayOf(0.53f, 1.37f, 0.188f), floatArrayOf(0.505f, 1.46f, 0.19f))
        else arrayOf(floatArrayOf(0.60f, 1.10f, 0.15f), floatArrayOf(0.555f, 1.28f, 0.18f), floatArrayOf(0.505f, 1.46f, 0.19f))
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
            m.rod(a[0], a[1], z, c[0], c[1], z, 0.019f, 0.019f, chrome)                          // stanchions
            m.rod(c[0], c[1], z, d[0], d[1], z, 0.027f, 0.027f, frame)                           // gaiters
            if (hi) for (k in 0 until 7) {
                val e = p(0.34f + k * 0.03f)
                val f = p(0.35f + k * 0.03f)
                m.rod(e[0], e[1], z, f[0], f[1], z, 0.031f, 0.031f, rubber)                       // gaiter ribs
            }
        }
        val lc = p(0.17f)
        m.beam(0f, 0.01f, -0.12f, 0f, 0.01f, 0.12f, 0.022f, frame)                            // top clamp
        m.beam(lc[0], lc[1], -0.12f, lc[0], lc[1], 0.12f, 0.022f, frame)                      // lower clamp
        if (hi) {
            m.cone(0f, 0.032f, 0f, 0.022f, 0.022f, 0.012f, 10, chrome)                         // stem nut
            for (s in listOf(-1f, 1f)) m.cone(0f, 0.032f, s * 0.09f, 0.014f, 0.014f, 0.008f, 8, bolt)
        }
        // Upper fender: glossy black, curving over the wheel from the lower clamp (it steers
        // with the fork but doesn't move with the suspension).
        val fr = spec.frontRadius.toFloat() + 0.15f
        m.bodywork {
            arc(ax, ay, fr, 1.22f, 1.92f, 0.07f, 0.008f, gloss, if (hi) 12 else 6)
            arc(ax, ay, fr - 0.012f, 1.27f, 1.88f, 0.05f, 0.012f, gloss, if (hi) 8 else 4)          // stiffening rib
        }
        m.beam(lc[0], lc[1] - 0.02f, -0.05f, lc[0] + 0.02f, lc[1] - 0.06f, 0f, 0.012f, frame)  // mount
        m.beam(lc[0], lc[1] - 0.02f, 0.05f, lc[0] + 0.02f, lc[1] - 0.06f, 0f, 0.012f, frame)
        // Bars on risers, with the chrome crossbar brace.
        for (s in listOf(-1f, 1f)) m.beam(-0.02f, 0.02f, s * 0.05f, -0.04f, 0.11f, s * 0.05f, 0.016f, frame)
        val by = 0.12f; val bx = rider.handX - headX
        m.beam(bx, by, -0.42f, bx, by, 0.42f, 0.012f, frame)
        m.beam(bx + 0.02f, by + 0.05f, -0.19f, bx + 0.02f, by + 0.05f, 0.19f, 0.007f, chrome)
        for (s in listOf(-1f, 1f)) {
            m.beam(bx + 0.01f, by + 0.005f, s * 0.19f, bx + 0.02f, by + 0.05f, s * 0.19f, 0.007f, chrome)
            m.rod(bx, by, s * 0.31f, bx, by, s * 0.42f, 0.019f, 0.019f, rubber)                // grips
            m.box(bx + 0.01f, by + 0.01f, s * 0.26f, 0.03f, 0.025f, 0.03f, MeshBuilder.color(0x1a1a1a))  // switchgear
            m.beam(bx + 0.04f, by + 0.01f, s * 0.26f, bx + 0.07f, by - 0.01f, s * 0.39f, 0.006f, MeshBuilder.color(0x555555)) // levers
            // Tusk handguards: a black shell wrapping the lever with the red logo.
            m.loft(arrayOf(floatArrayOf(bx - 0.02f, by + 0.01f, 0.04f, 0.012f), floatArrayOf(bx + 0.06f, by + 0.0f, 0.05f, 0.012f),
                floatArrayOf(bx + 0.11f, by - 0.02f, 0.035f, 0.012f)), MeshBuilder.color(0x101010), 8, 0.8f, s * 0.38f)
            m.box(bx + 0.06f, by + 0.01f, s * 0.395f, 0.022f, 0.008f, 0.002f, tusk)
            // Round mirrors on stalks.
            m.beam(bx - 0.01f, by + 0.02f, s * 0.28f, bx - 0.03f, by + 0.21f, s * 0.36f, 0.006f, frame)
            m.tube(bx - 0.02f, by + 0.24f, s * 0.37f, bx - 0.04f, by + 0.24f, s * 0.37f, 0.045f, 0.045f, ls, frame, MeshBuilder.color(0x6e7a86))
            if (hi) m.rod(bx, by, s * 0.42f, bx, by, s * 0.432f, 0.021f, 0.021f, bolt)          // bar-end weights
        }
        m.box(bx - 0.01f, by + 0.03f, 0.22f, 0.025f, 0.02f, 0.03f, MeshBuilder.color(0x1a1a1a))     // master cylinder
        m.box(bx - 0.02f, by + 0.03f, -0.13f, 0.02f, 0.025f, 0.02f, MeshBuilder.color(0x111111))    // action camera
        if (hi) {
            m.box(bx - 0.02f, by + 0.06f, 0.22f, 0.02f, 0.012f, 0.022f, MeshBuilder.color(0x2a2a2a))   // reservoir
            m.box(bx - 0.005f, by + 0.03f, -0.13f, 0.002f, 0.012f, 0.012f, MeshBuilder.color(0x1a2a3e)) // camera screen
            // Cables looping from the bars down to the head.
            for (s in listOf(-1f, 1f)) m.bent(arrayOf(floatArrayOf(bx + 0.01f, by, 0.24f), floatArrayOf(bx + 0.06f, by - 0.07f, 0.18f), floatArrayOf(0.02f, -0.06f, 0.08f)), s, 0.004f, cable)
        }
    }

    override fun frontLower(m: MeshBuilder, spec: BikeSpec, ux: Float, uy: Float) {
        val rf = spec.frontRadius.toFloat()
        for (s in listOf(-1f, 1f)) m.rod(0f, 0f, s * 0.09f, ux * 0.31f, uy * 0.31f, s * 0.09f, 0.026f, 0.026f, frame)  // lowers
        m.beam(0f, 0f, -0.11f, 0f, 0f, 0.11f, 0.012f, silver)                                // axle
        m.box(-0.10f, 0.10f, -0.105f, 0.032f, 0.048f, 0.02f, MeshBuilder.color(0xb09a6a))  // gold Bybre caliper (disc on the left)
        // Low fender hugging the tyre.
        m.bodywork { arc(0f, 0f, rf + 0.035f, 0.95f, 2.05f, 0.06f, 0.008f, gloss, if (hi) 12 else 6) }
        m.tube(-0.02f, rf + 0.04f, -0.09f, -0.02f, rf + 0.04f, 0.09f, 0.008f, 0.008f, 6, frame, frame)  // fender brace
        m.box(0.0f, -0.02f, 0.092f, 0.012f, 0.03f, 0.004f, amber)                            // fork reflector
        if (hi) {
            m.bent(arrayOf(floatArrayOf(-0.10f, 0.14f, 0.11f), floatArrayOf(ux * 0.2f - 0.03f, uy * 0.2f, 0.12f), floatArrayOf(ux * 0.31f, uy * 0.31f, 0.115f)), -1f, 0.004f, cable)
            for (k in 0 until 2) m.cone(-0.10f, 0.07f + k * 0.06f, -0.124f, 0.006f, 0.006f, 0.004f, 6, bolt)
        }
    }

    override fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean) {
        val spokes = if (hi) 36 else 18
        val knobs = if (hi) 34 else 18
        if (front) {
            // 90/90-21 on a black rim, 300 mm disc on the left.
            m.spokedWheel(spec.frontRadius.toFloat(), 0.081f, 0.046f, spokes, tyre, knob, frame, spoke, silver, knobs)
            m.arc(0f, 0f, 0.128f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.044f, disc, if (hi) 30 else 16, -0.071f)
            for (k in 0 until 6) {
                val a = k * MeshBuilder.TWO_PI / 6
                m.beam(cos(a) * 0.04f, sin(a) * 0.04f, -0.071f, cos(a) * 0.108f, sin(a) * 0.108f, -0.071f, 0.006f, MeshBuilder.color(0x303030))
            }
        } else {
            // 120/90-17, 240 mm disc on the right, sprocket on the left.
            m.spokedWheel(spec.rearRadius.toFloat(), 0.108f, 0.062f, spokes, tyre, knob, frame, spoke, silver, knobs)
            m.arc(0f, 0f, 0.102f, 0f, MeshBuilder.TWO_PI, 0.003f, 0.036f, disc, if (hi) 26 else 14, 0.071f)
            for (k in 0 until 5) {
                val a = k * MeshBuilder.TWO_PI / 5
                m.beam(cos(a) * 0.04f, sin(a) * 0.04f, 0.071f, cos(a) * 0.086f, sin(a) * 0.086f, 0.071f, 0.006f, MeshBuilder.color(0x303030))
            }
            m.tube(0f, 0f, -0.112f, 0f, 0f, -0.104f, 0.105f, 0.105f, if (hi) 30 else 16, chain, MeshBuilder.color(0x2a2a2a))
            if (hi) for (k in 0 until 42) {                                                          // sprocket teeth
                val a = k * MeshBuilder.TWO_PI / 42
                m.box(cos(a) * 0.109f, sin(a) * 0.109f, -0.108f, 0.004f, 0.004f, 0.004f, chain)
            }
        }
    }

    private companion object {
        val CAMO_DARK = MeshBuilder.color(0x222c1e)
        val CAMO_PINE = MeshBuilder.color(0x3c5236)
        val CAMO_OLIVE = MeshBuilder.color(0x5a5e3a)
        val CAMO_SAGE = MeshBuilder.color(0x7a8462)
    }
}
