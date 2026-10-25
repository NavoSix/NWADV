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
