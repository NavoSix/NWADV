package com.northwoodsadv.ride.render

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.northwoodsadv.ride.physics.Motorcycle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-poly adventure bike and rider. Model space: +X forward, +Y up, +Z to the rider's right,
 * origin on the ground below the centre of mass at static sag.
 */
class BikeRenderer(private val spec: com.northwoodsadv.ride.physics.BikeSpec) : Disposable {
    private val chassisSeated: Mesh
    private val chassisStanding: Mesh
    private val chassisNoLegs: Mesh
    private val legL: Mesh
    private val legR: Mesh
    private val front: Mesh
    private val wheelF: Mesh
    private val wheelR: Mesh
    private val shadow: Mesh

    // Steering head and rake.
    private val headX = 0.52f; private val headY = 1.0f
    private val rake = Math.toRadians(27.0).toFloat()
    private val sagF = spec.sagFront.toFloat(); private val sagR = spec.sagRear.toFloat()

    init {
        val a = spec.cgToFront.toFloat(); val b = spec.cgToRear.toFloat()
        val rf = spec.frontRadius.toFloat(); val rr = spec.rearRadius.toFloat()
        val red = MeshBuilder.color(0xd8282e); val dark = MeshBuilder.color(0x4a4a50); val black = MeshBuilder.color(0x141414)
        val alu = MeshBuilder.color(0xc8cace); val grey = MeshBuilder.color(0x76787c)
        fun chassis(standing: Boolean, legs: Boolean = true): Mesh {
            val m = MeshBuilder(2048)
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
            // Rider in hi-vis jacket.
            val jacket = MeshBuilder.color(0xd8d23a); val pants = MeshBuilder.color(0x30343a); val boot = black
            val helmet = MeshBuilder.color(0xf0f0f0)
            if (standing) {
                for (side in listOf(-1f, 1f)) {
                    m.beam(0.0f, 0.42f, side * 0.17f, -0.02f, 0.82f, side * 0.15f, 0.06f, pants)   // shin
                    m.beam(-0.02f, 0.82f, side * 0.15f, -0.08f, 1.22f, side * 0.12f, 0.07f, pants) // thigh
                    m.box(0.02f, 0.40f, side * 0.17f, 0.12f, 0.05f, 0.05f, boot)
                }
                m.beam(-0.08f, 1.22f, 0f, 0.10f, 1.62f, 0f, 0.15f, jacket)                     // torso
                for (side in listOf(-1f, 1f)) m.beam(0.10f, 1.55f, side * 0.2f, 0.42f, 1.12f, side * 0.36f, 0.045f, jacket)
                m.blob(0.16f, 1.78f, 0f, 0.14f, 0.15f, 0.13f, helmet)
            } else {
                if (legs) for (side in listOf(-1f, 1f)) {
                    m.beam(-0.05f, 0.42f, side * 0.17f, 0.05f, 0.75f, side * 0.18f, 0.06f, pants)
                    m.beam(0.05f, 0.75f, side * 0.18f, -0.28f, 0.98f, side * 0.14f, 0.07f, pants)
                    m.box(-0.03f, 0.40f, side * 0.17f, 0.12f, 0.05f, 0.05f, boot)
                }
                m.beam(-0.28f, 0.98f, 0f, -0.12f, 1.48f, 0f, 0.16f, jacket)
                for (side in listOf(-1f, 1f)) m.beam(-0.12f, 1.42f, side * 0.2f, 0.42f, 1.12f, side * 0.36f, 0.045f, jacket)
                m.blob(-0.08f, 1.64f, 0f, 0.14f, 0.15f, 0.13f, helmet)
            }
            return toMesh(m)
        }
        chassisSeated = chassis(false)
        chassisStanding = chassis(true)
        chassisNoLegs = chassis(false, legs = false)
        // A leg reaching down to the ground, built about the hip so it can swing.
        fun leg(side: Float): Mesh {
            val m = MeshBuilder(256)
            val pants = MeshBuilder.color(0x30343a)
            val hx = HIP_X; val hy = HIP_Y; val hz = side * 0.15f
            m.beam(hx, hy, hz, 0.02f, 0.52f, side * 0.33f, 0.07f, pants)          // thigh
            m.beam(0.02f, 0.52f, side * 0.33f, -0.02f, 0.06f, side * 0.44f, 0.06f, pants) // shin
            m.box(0.04f, 0.05f, side * 0.44f, 0.13f, 0.05f, 0.055f, MeshBuilder.color(0x141414))
            return toMesh(m)
        }
        legL = leg(-1f); legR = leg(1f)

        // Front assembly in steering-head space (origin at the head, before rotation).
        val fm = MeshBuilder(512)
        val ax = a - headX; val ay = rf - headY
        for (side in listOf(-1f, 1f)) fm.beam(0f, 0f, side * 0.1f, ax, ay, side * 0.1f, 0.03f, MeshBuilder.color(0x8a6a2a))
        fm.beam(-0.06f, 0.12f, -0.38f, -0.06f, 0.12f, 0.38f, 0.016f, black)                 // handlebar
        fm.box(0.10f, -0.05f, 0f, 0.06f, 0.10f, 0.12f, MeshBuilder.color(0xeeeedd))        // headlight
        fm.box(0.04f, 0.20f, 0f, 0.02f, 0.18f, 0.16f, MeshBuilder.color(0x8fa4b0))         // screen
        fm.box(ax + 0.02f, ay + rf + 0.06f, 0f, 0.20f, 0.012f, 0.07f, red)                   // fender
        front = toMesh(fm)

        fun wheel(r: Float): Mesh {
            val w = MeshBuilder(512)
            w.wheel(0f, 0f, 0f, r, 0.065f, 18, MeshBuilder.color(0x3a3a3a), MeshBuilder.color(0x1a1a1a))
            w.wheel(0f, 0f, 0f, r * 0.62f, 0.07f, 12, MeshBuilder.color(0x8a8a8a), MeshBuilder.color(0x6a6a6a))
            return toMesh(w)
        }
        wheelF = wheel(rf); wheelR = wheel(rr)

        val sm = MeshBuilder(64)
        val sc = MeshBuilder.color(0.16f, 0.15f, 0.12f)
        val c0 = sm.vertex(0f, 0f, 0f, 0f, 1f, 0f, sc)
        for (i in 0..16) {
            val t = i / 16f * MeshBuilder.TWO_PI
            sm.vertex(cos(t) * 0.95f, 0f, sin(t) * 0.32f, 0f, 1f, 0f, MeshBuilder.color(0.3f, 0.28f, 0.22f))
        }
        for (i in 1..16) sm.tri(c0, i + 1, i)
        shadow = toMesh(sm)
    }

    companion object {
        private const val HIP_X = -0.22f
        private const val HIP_Y = 0.98f
    }

    private fun toMesh(m: MeshBuilder): Mesh {
        val mesh = Mesh(true, m.vertCount, m.idxCount, ChunkManager.ATTRS)
        mesh.setVertices(m.verts, 0, m.vertCount * MeshBuilder.STRIDE)
        mesh.setIndices(m.idx, 0, m.idxCount)
        return mesh
    }

    private val base = Matrix4()
    private val m = Matrix4()
    private val axis = Vector3()

    /** Chassis transform in render space (relative to the render origin). */
    fun chassisTransform(bike: Motorcycle, originE: Double, originN: Double, baseH: Double, out: Matrix4): Matrix4 {
        val h = bike.cgHeight
        // Ground point under the centre of mass when upright; lean rotates about the contact line.
        val groundZ = bike.z - h * cos(bike.lean) * cos(bike.pitch)
        out.idt()
        out.translate((bike.x - originE).toFloat(), (groundZ - baseH).toFloat(), (-(bike.y - originN)).toFloat())
        out.rotateRad(0f, 1f, 0f, bike.yaw.toFloat())
        out.rotateRad(1f, 0f, 0f, (-bike.lean).toFloat())
        out.rotateRad(0f, 0f, 1f, bike.pitch.toFloat())
        return out
    }

    fun render(shader: ShaderProgram, bike: Motorcycle, originE: Double, originN: Double, baseH: Double, groundHere: Double) {
        chassisTransform(bike, originE, originN, baseH, base)
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        shader.setUniformf("u_useModel", 1f)

        // Shadow on the ground (no lean / pitch).
        m.idt().translate((bike.x - originE).toFloat(), (groundHere - baseH + 0.04).toFloat(), (-(bike.y - originN)).toFloat())
            .rotateRad(0f, 1f, 0f, bike.yaw.toFloat())
        shader.setUniformMatrix("u_model", m)
        shadow.render(shader, GL20.GL_TRIANGLES)

        shader.setUniformMatrix("u_model", base)
        if (bike.feetDown && !bike.standing) {
            chassisNoLegs.render(shader, GL20.GL_TRIANGLES)
            // Feet on the ground; paddling swings them alternately like walking the bike back.
            val swing = if (bike.paddling) (sin(bike.paddlePhase) * 0.35).toFloat() else 0f
            for ((mesh, sgn) in listOf(legL to 1f, legR to -1f)) {
                m.set(base).translate(HIP_X, HIP_Y, 0f).rotateRad(0f, 0f, 1f, swing * sgn).translate(-HIP_X, -HIP_Y, 0f)
                shader.setUniformMatrix("u_model", m)
                mesh.render(shader, GL20.GL_TRIANGLES)
            }
            shader.setUniformMatrix("u_model", base)
        } else (if (bike.standing) chassisStanding else chassisSeated).render(shader, GL20.GL_TRIANGLES)

        // Rear wheel moves up with the shock.
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        m.set(base).translate(-b, rr + (bike.compR.toFloat() - sagR).coerceIn(-0.05f, 0.3f), 0f)
            .rotateRad(0f, 0f, 1f, (-bike.wheelAngleR).toFloat())
        shader.setUniformMatrix("u_model", m)
        wheelR.render(shader, GL20.GL_TRIANGLES)

        // Front: steer about the raked steering axis, wheel slides up the fork.
        axis.set(-sin(rake), cos(rake), 0f)
        val steerVis = bike.steer.toFloat()
        m.set(base).translate(headX, headY, 0f).rotateRad(axis, steerVis)
        shader.setUniformMatrix("u_model", m)
        front.render(shader, GL20.GL_TRIANGLES)
        val a = spec.cgToFront.toFloat(); val rf = spec.frontRadius.toFloat()
        val comp = (bike.compF.toFloat() - sagF).coerceIn(-0.05f, 0.3f)
        m.translate(a - headX + axis.x * comp, rf - headY + axis.y * comp, 0f)
            .rotateRad(0f, 0f, 1f, (-bike.wheelAngleF).toFloat())
        shader.setUniformMatrix("u_model", m)
        wheelF.render(shader, GL20.GL_TRIANGLES)

        shader.setUniformf("u_useModel", 0f)
    }

    override fun dispose() {
        chassisSeated.dispose(); chassisStanding.dispose(); chassisNoLegs.dispose(); legL.dispose(); legR.dispose(); front.dispose(); wheelF.dispose(); wheelR.dispose(); shadow.dispose()
    }
}
