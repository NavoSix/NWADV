package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.physics.BikeSpec

/**
 * How finely a bike is modelled: the garage turntable shows every bolt, cable and chain link; on
 * the road a lighter model keeps the frame rate up (it is rarely seen closer than a few metres).
 */
enum class Detail { RIDE, SHOWROOM }

/** Where the rider's hips, feet and hands go on a particular bike (model space, metres). */
class RiderFit(
    val hipX: Float, val hipY: Float,
    val pegX: Float, val pegY: Float,
    val handX: Float, val handY: Float, val handZ: Float,
)

/**
 * The rider's navigation screen (phone or GPS) on a bike, where the moving map is shown in the
 * helmet view. Centre ([x], [y], [z]) and size are the lit display area in metres, facing the
 * rider (-X) and tilted back by [tiltDeg] (top toward the rider). Frame-mounted screens are in
 * model space; [onBars] screens are in steering-head space, like [BikeLook.front], and steer.
 */
class NavScreen(
    val x: Float, val y: Float, val z: Float,
    val width: Float, val height: Float,
    val tiltDeg: Float = 0f,
    val onBars: Boolean = false,
)

/**
 * The bike's lighting at night: where the lamps sit (model space, roughly the headlight lens),
 * how wide the beam spreads (degrees from the centre to the edge), its colour and strength.
 * Auxiliary lights widen and brighten it.
 */
class Headlight(val x: Float, val y: Float, val spreadDeg: Float = 24f, val color: Int = 0xfff2e0, val power: Float = 1f)

/**
 * How one motorcycle looks. Model space: +X forward, +Y up, +Z to the rider's right, origin on
 * the ground below the centre of mass at static sag; front axle at (cgToFront, frontRadius),
 * rear axle at (-cgToRear, rearRadius).
 *
 * Parts are split by how they move: the chassis (frame-mounted), the swingarm (pivots with the
 * rear suspension), the front end that steers
 * (built in steering-head space, origin at the top of the head), the fork lowers that also slide
 * with the suspension (built around the front axle), and the wheels (which also spin).
 */
interface BikeLook {
    /** Top of the steering head (model space) and the rake of the steering axis. */
    val headX: Float
    val headY: Float
    val rakeDeg: Double
    val rider: RiderFit
    /** Lights for riding at night. */
    val headlight: Headlight get() = Headlight(0.64f, 0.95f)
    /** Where the moving map goes in the helmet view, or null to keep it on the HUD. */
    val navScreen: NavScreen? get() = null

    /** Swingarm pivot (model space); the swingarm and rear wheel swing about it together. */
    val pivotX: Float
    val pivotY: Float

    fun chassis(m: MeshBuilder, spec: BikeSpec)
    /** Parts that swing with the rear wheel (swingarm, chain, guard), in model space at static sag. */
    fun swingarm(m: MeshBuilder, spec: BikeSpec) {}
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
    override val pivotX = -0.02f
    override val pivotY = 0.42f
    override val rider = RiderFit(-0.28f, 0.98f, -0.03f, 0.40f, 0.42f, 1.12f, 0.36f)
    // A portrait handlebar GPS on the crossbar, tilted back toward the rider.
    override val navScreen = NavScreen(-0.03f, 0.20f, 0f, 0.072f, 0.108f, tiltDeg = 25f, onBars = true)

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
        m.beam(-0.10f, 0.55f, 0f, -0.45f, 0.90f, 0f, 0.04f, dark)           // subframe
        m.box(0.20f, 0.30f, 0f, 0.22f, 0.03f, 0.13f, grey)                  // bash plate
    }

    override fun swingarm(m: MeshBuilder, spec: BikeSpec) {
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        m.beam(pivotX, pivotY, 0.09f, -b, rr, 0.09f, 0.035f, grey)
        m.beam(pivotX, pivotY, -0.09f, -b, rr, -0.09f, 0.035f, grey)
    }

    override fun front(m: MeshBuilder, spec: BikeSpec, ax: Float, ay: Float) {
        val rf = spec.frontRadius.toFloat()
        for (side in listOf(-1f, 1f)) m.beam(0f, 0f, side * 0.1f, ax, ay, side * 0.1f, 0.03f, MeshBuilder.color(0x8a6a2a))
        m.beam(-0.06f, 0.12f, -0.38f, -0.06f, 0.12f, 0.38f, 0.016f, black)                 // handlebar
        m.box(0.10f, -0.05f, 0f, 0.06f, 0.10f, 0.12f, MeshBuilder.color(0xeeeedd))        // headlight
        m.box(0.05f, 0.15f, 0f, 0.015f, 0.08f, 0.15f, MeshBuilder.color(0x8fa4b0))        // flyscreen
        m.box(ax + 0.02f, ay + rf + 0.06f, 0f, 0.20f, 0.012f, 0.07f, red)                   // fender
        // Handlebar GPS: a housing around the [navScreen] display, on a stalk from the bar.
        val ns = navScreen
        val t = Math.toRadians(ns.tiltDeg.toDouble())
        val ux = -kotlin.math.sin(t).toFloat(); val uy = kotlin.math.cos(t).toFloat()      // screen up
        val nx = -kotlin.math.cos(t).toFloat(); val ny = -kotlin.math.sin(t).toFloat()     // toward rider
        val hw = ns.width / 2 + 0.008f; val hh = ns.height / 2 + 0.012f; val back = 0.022f
        val cx = ns.x - nx * 0.002f; val cy = ns.y - ny * 0.002f
        fun corner(sx: Float, sy: Float, d: Float) = floatArrayOf(cx + ux * sy * hh - nx * d, cy + uy * sy * hh - ny * d)
        val housing = MeshBuilder.color(0x1c1c1e)
        val ccx = cx - nx * back / 2; val ccy = cy - ny * back / 2
        val quads = arrayOf(
            arrayOf(floatArrayOf(-1f, -1f, 0f), floatArrayOf(1f, -1f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(-1f, 1f, 0f)),
            arrayOf(floatArrayOf(-1f, -1f, back), floatArrayOf(1f, -1f, back), floatArrayOf(1f, 1f, back), floatArrayOf(-1f, 1f, back)),
            arrayOf(floatArrayOf(-1f, 1f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(1f, 1f, back), floatArrayOf(-1f, 1f, back)),
            arrayOf(floatArrayOf(-1f, -1f, 0f), floatArrayOf(1f, -1f, 0f), floatArrayOf(1f, -1f, back), floatArrayOf(-1f, -1f, back)),
            arrayOf(floatArrayOf(-1f, -1f, 0f), floatArrayOf(-1f, 1f, 0f), floatArrayOf(-1f, 1f, back), floatArrayOf(-1f, -1f, back)),
            arrayOf(floatArrayOf(1f, -1f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(1f, 1f, back), floatArrayOf(1f, -1f, back)),
        )
        for (q in quads) {
            val p = q.map { c -> val xy = corner(c[0], c[1], c[2]); floatArrayOf(xy[0], xy[1], ns.z + c[0] * hw) }
            m.outTri(p[0][0], p[0][1], p[0][2], p[1][0], p[1][1], p[1][2], p[2][0], p[2][1], p[2][2], ccx, ccy, ns.z, housing)
            m.outTri(p[0][0], p[0][1], p[0][2], p[2][0], p[2][1], p[2][2], p[3][0], p[3][1], p[3][2], ccx, ccy, ns.z, housing)
        }
        m.beam(ccx - nx * 0.01f, ccy - ny * 0.01f - hh * 0.5f, 0f, -0.06f, 0.12f, 0f, 0.008f, black)  // mount stalk
    }

    override fun wheel(m: MeshBuilder, spec: BikeSpec, front: Boolean) {
        val r = (if (front) spec.frontRadius else spec.rearRadius).toFloat()
        m.wheel(0f, 0f, 0f, r, 0.065f, 18, MeshBuilder.color(0x3a3a3a), MeshBuilder.color(0x1a1a1a))
        m.wheel(0f, 0f, 0f, r * 0.62f, 0.07f, 12, MeshBuilder.color(0x8a8a8a), MeshBuilder.color(0x6a6a6a))
    }
}
