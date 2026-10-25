package com.northwoodsadv.ride.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.physics.Motorcycle
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A motorcycle ([BikeLook]) and its rider as meshes, posed from the physics each frame. Model
 * space: +X forward, +Y up, +Z to the rider's right, origin on the ground below the centre of
 * mass at static sag.
 */
class BikeRenderer(private val spec: BikeSpec, private val look: BikeLook = GenericTwinLook()) : Disposable {
    private val chassisSeated: Mesh
    private val chassisStanding: Mesh
    private val chassisNoLegs: Mesh
    private val chassisBare: Mesh
    private val legL: Mesh
    private val legR: Mesh
    private val front: Mesh
    private val frontLower: Mesh?
    private val glass: Mesh?
    private val wheelF: Mesh
    private val wheelR: Mesh
    private val shadow: Mesh

    private val headX = look.headX; private val headY = look.headY
    private val rake = Math.toRadians(look.rakeDeg).toFloat()
    private val sagF = spec.sagFront.toFloat(); private val sagR = spec.sagRear.toFloat()
    private val fit = look.rider
    /** Helmet-camera eye positions (model space), from where this bike puts its rider. */
    val eyeSeated = Vector3(fit.hipX + 0.26f, fit.hipY + 0.64f, 0f)
    val eyeStanding = Vector3(fit.pegX + 0.08f, fit.pegY + 1.30f, 0f)
    val navScreen = look.navScreen
    /** How far (radians) the helmet camera looks below the bike's horizon from each eye. */
    val lookDownSeated = lookDown(eyeSeated)
    val lookDownStanding = lookDown(eyeStanding)

    /**
     * A little down the road, and further down if needed to keep the nav screen in the lower part
     * of the frame (~24 degrees below centre), wherever this bike mounts it.
     */
    private fun lookDown(eye: Vector3): Float {
        val ns = navScreen ?: return 0.17f
        val sx = ns.x + if (ns.onBars) headX else 0f
        val sy = ns.y + if (ns.onBars) headY else 0f
        val below = kotlin.math.atan2(eye.y - sy, sx - eye.x)
        return (below - 0.42f).coerceIn(0.17f, 0.45f)
    }
    private val hipX = fit.hipX + 0.06f; private val hipY = fit.hipY

    init {
        val a = spec.cgToFront.toFloat()
        val rf = spec.frontRadius.toFloat()
        fun chassis(standing: Boolean, legs: Boolean = true, rider: Boolean = true): Mesh {
            val m = MeshBuilder(4096)
            look.chassis(m, spec)
            if (rider) rider(m, standing, legs)
            return toMesh(m)
        }
        chassisSeated = chassis(false)
        chassisStanding = chassis(true)
        chassisNoLegs = chassis(false, legs = false)
        chassisBare = chassis(false, rider = false)
        // A leg reaching down to the ground, built about the hip so it can swing.
        fun leg(side: Float): Mesh {
            val m = MeshBuilder(256)
            val pants = MeshBuilder.color(0x30343a)
            val hz = side * 0.15f
            val kx = hipX + 0.24f; val ky = hipY * 0.53f
            m.beam(hipX, hipY, hz, kx, ky, side * 0.33f, 0.07f, pants)                       // thigh
            m.beam(kx, ky, side * 0.33f, kx - 0.04f, 0.06f, side * 0.44f, 0.06f, pants)       // shin
            m.box(kx - 0.02f, 0.05f, side * 0.44f, 0.13f, 0.05f, 0.055f, MeshBuilder.color(0x141414))
            return toMesh(m)
        }
        legL = leg(-1f); legR = leg(1f)

        val fm = MeshBuilder(2048)
        val ax = a - headX; val ay = rf - headY
        look.front(fm, spec, ax, ay)
        front = toMesh(fm)
        val lm = MeshBuilder(512)
        val len = sqrt(ax * ax + ay * ay)
        look.frontLower(lm, spec, -ax / len, -ay / len)
        frontLower = if (lm.vertCount > 0) toMesh(lm) else null
        val gm = MeshBuilder(64)
        look.glass(gm, spec)
        glass = if (gm.vertCount > 0) toMesh(gm) else null

        fun wheel(isFront: Boolean): Mesh {
            val w = MeshBuilder(4096)
            look.wheel(w, spec, isFront)
            return toMesh(w)
        }
        wheelF = wheel(true); wheelR = wheel(false)

        val sm = MeshBuilder(64)
        // Soft contact shadow: dark in the middle, fading to nothing at the rim (drawn blended).
        val c0 = sm.vertex(0f, 0f, 0f, 0f, 1f, 0f, MeshBuilder.color(0.02f, 0.02f, 0.02f, 0.62f))
        for (i in 0..16) {
            val t = i / 16f * MeshBuilder.TWO_PI
            sm.vertex(cos(t) * 0.6f, 0f, sin(t) * 0.22f, 0f, 1f, 0f, MeshBuilder.color(0.02f, 0.02f, 0.02f, 0.45f))
        }
        for (i in 0..16) {
            val t = i / 16f * MeshBuilder.TWO_PI
            sm.vertex(cos(t) * 1.15f, 0f, sin(t) * 0.48f, 0f, 1f, 0f, MeshBuilder.color(0.02f, 0.02f, 0.02f, 0f))
        }
        for (i in 1..16) sm.tri(c0, i + 1, i)
        for (i in 1..16) { val a2 = i; val b2 = i + 1; val c = i + 17; val d = i + 18; sm.tri(a2, b2, d); sm.tri(a2, d, c) }
        shadow = toMesh(sm)
    }

    /** Rider in hi-vis jacket, fitted to the bike's seat, pegs and bars. */
    private fun rider(m: MeshBuilder, standing: Boolean, legs: Boolean) {
        val jacket = MeshBuilder.color(0xd8d23a); val pants = MeshBuilder.color(0x30343a); val boot = MeshBuilder.color(0x141414)
        val helmet = MeshBuilder.color(0xf0f0f0)
        val px = fit.pegX; val py = fit.pegY
        val hx = fit.handX; val hy = fit.handY; val hz = fit.handZ
        if (standing) {
            for (side in listOf(-1f, 1f)) {
                m.beam(px - 0.02f, py + 0.02f, side * 0.17f, px - 0.04f, py + 0.42f, side * 0.15f, 0.06f, pants)   // shin
                m.beam(px - 0.04f, py + 0.42f, side * 0.15f, px - 0.10f, py + 0.82f, side * 0.12f, 0.07f, pants)  // thigh
                m.box(px, py, side * 0.17f, 0.12f, 0.05f, 0.05f, boot)
            }
            m.beam(px - 0.10f, py + 0.82f, 0f, px + 0.08f, py + 1.22f, 0f, 0.15f, jacket)                        // torso
            for (side in listOf(-1f, 1f)) m.beam(px + 0.08f, py + 1.15f, side * 0.2f, hx, hy, side * hz, 0.045f, jacket)
            m.blob(px + 0.14f, py + 1.38f, 0f, 0.14f, 0.15f, 0.13f, helmet)
        } else {
            val ix = fit.hipX; val iy = fit.hipY
            if (legs) for (side in listOf(-1f, 1f)) {
                val kx = px + 0.08f; val ky = iy - 0.23f
                m.beam(px - 0.02f, py + 0.02f, side * 0.17f, kx, ky, side * 0.18f, 0.06f, pants)
                m.beam(kx, ky, side * 0.18f, ix, iy, side * 0.14f, 0.07f, pants)
                m.box(px, py, side * 0.17f, 0.12f, 0.05f, 0.05f, boot)
            }
            m.beam(ix, iy, 0f, ix + 0.16f, iy + 0.50f, 0f, 0.16f, jacket)
            for (side in listOf(-1f, 1f)) m.beam(ix + 0.16f, iy + 0.44f, side * 0.2f, hx, hy, side * hz, 0.045f, jacket)
            m.blob(ix + 0.20f, iy + 0.66f, 0f, 0.14f, 0.15f, 0.13f, helmet)
        }
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

    /** Rider pose for [renderPose]. */
    enum class Pose { SEATED, STANDING, FEET_DOWN, NO_RIDER }

    fun render(shader: ShaderProgram, bike: Motorcycle, originE: Double, originN: Double, baseH: Double, groundHere: Double) {
        chassisTransform(bike, originE, originN, baseH, base)
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        shader.setUniformf("u_useModel", 1f)

        // Shadow on the ground (no lean / pitch).
        m.idt().translate((bike.x - originE).toFloat(), (groundHere - baseH + 0.04).toFloat(), (-(bike.y - originN)).toFloat())
            .rotateRad(0f, 1f, 0f, bike.yaw.toFloat())
        drawShadow(shader, m)

        val pose = when {
            bike.feetDown && !bike.standing -> Pose.FEET_DOWN
            bike.standing -> Pose.STANDING
            else -> Pose.SEATED
        }
        val swing = if (bike.paddling) (sin(bike.paddlePhase) * 0.35).toFloat() else 0f
        drawBike(shader, base, pose, swing, bike.steer.toFloat(), bike.compF.toFloat(), bike.compR.toFloat(),
            bike.wheelAngleF.toFloat(), bike.wheelAngleR.toFloat())
        shader.setUniformf("u_useModel", 0f)
    }

    /** Draw the bike at rest at [transform] (e.g. on the bike selection turntable). */
    fun renderPose(shader: ShaderProgram, transform: Matrix4, pose: Pose, steer: Float = 0f) {
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        shader.setUniformf("u_useModel", 1f)
        m.set(transform).translate(0f, 0.01f, 0f)
        drawShadow(shader, m)
        drawBike(shader, transform, pose, 0f, steer, sagF, sagR, 0f, 0f)
        shader.setUniformf("u_useModel", 0f)
    }

    private fun drawShadow(shader: ShaderProgram, at: Matrix4) {
        shader.setUniformMatrix("u_model", at)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDepthMask(false)
        shader.setUniformf("u_mode", ChunkManager.MODE_FX)
        shadow.render(shader, GL20.GL_TRIANGLES)
        shader.setUniformf("u_mode", ChunkManager.MODE_OBJECT)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    private val lastBase = Matrix4()
    private var lastSteer = 0f

    /**
     * Render-space transform of the [navScreen] display for the pose last drawn, mapping 2D
     * (0..width, 0..height) metres onto the screen with x to the rider's right and y up the
     * display; null if this bike has none.
     */
    fun navTransform(out: Matrix4): Matrix4? {
        val ns = navScreen ?: return null
        out.set(lastBase)
        if (ns.onBars) out.translate(headX, headY, 0f).rotateRad(axis.set(-sin(rake), cos(rake), 0f), lastSteer)
        val t = Math.toRadians(ns.tiltDeg.toDouble())
        val up = Vector3(-sin(t).toFloat(), cos(t).toFloat(), 0f)
        val right = Vector3(0f, 0f, 1f)
        val normal = Vector3(right).crs(up)
        val o = Vector3(ns.x, ns.y, ns.z).mulAdd(right, -ns.width / 2).mulAdd(up, -ns.height / 2).mulAdd(normal, 0.0005f)
        // Columns are the screen's axes (Matrix4.set(Vector3...) would put them in rows).
        return out.mul(Matrix4(floatArrayOf(right.x, right.y, right.z, 0f, up.x, up.y, up.z, 0f, normal.x, normal.y, normal.z, 0f, o.x, o.y, o.z, 1f)))
    }

    private fun drawBike(shader: ShaderProgram, base: Matrix4, pose: Pose, swing: Float, steer: Float, compF: Float, compR: Float, angF: Float, angR: Float) {
        lastBase.set(base); lastSteer = steer
        shader.setUniformMatrix("u_model", base)
        when (pose) {
            Pose.FEET_DOWN -> {
                chassisNoLegs.render(shader, GL20.GL_TRIANGLES)
                // Feet on the ground; paddling swings them alternately like walking the bike back.
                for ((mesh, sgn) in listOf(legL to 1f, legR to -1f)) {
                    m.set(base).translate(hipX, hipY, 0f).rotateRad(0f, 0f, 1f, swing * sgn).translate(-hipX, -hipY, 0f)
                    shader.setUniformMatrix("u_model", m)
                    mesh.render(shader, GL20.GL_TRIANGLES)
                }
                shader.setUniformMatrix("u_model", base)
            }
            Pose.STANDING -> chassisStanding.render(shader, GL20.GL_TRIANGLES)
            Pose.SEATED -> chassisSeated.render(shader, GL20.GL_TRIANGLES)
            Pose.NO_RIDER -> chassisBare.render(shader, GL20.GL_TRIANGLES)
        }

        // Rear wheel moves up with the shock.
        val b = spec.cgToRear.toFloat(); val rr = spec.rearRadius.toFloat()
        m.set(base).translate(-b, rr + (compR - sagR).coerceIn(-0.05f, 0.3f), 0f).rotateRad(0f, 0f, 1f, -angR)
        shader.setUniformMatrix("u_model", m)
        wheelR.render(shader, GL20.GL_TRIANGLES)

        // Front: steer about the raked steering axis, wheel and lowers slide up the fork.
        axis.set(-sin(rake), cos(rake), 0f)
        m.set(base).translate(headX, headY, 0f).rotateRad(axis, steer)
        shader.setUniformMatrix("u_model", m)
        front.render(shader, GL20.GL_TRIANGLES)
        val a = spec.cgToFront.toFloat(); val rf = spec.frontRadius.toFloat()
        val comp = (compF - sagF).coerceIn(-0.05f, 0.3f)
        m.translate(a - headX + axis.x * comp, rf - headY + axis.y * comp, 0f)
        shader.setUniformMatrix("u_model", m)
        frontLower?.render(shader, GL20.GL_TRIANGLES)
        m.rotateRad(0f, 0f, 1f, -angF)
        shader.setUniformMatrix("u_model", m)
        wheelF.render(shader, GL20.GL_TRIANGLES)

        // Windscreen last, blended over everything behind it.
        val g = glass ?: return
        shader.setUniformMatrix("u_model", base)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        shader.setUniformf("u_mode", ChunkManager.MODE_FX)
        g.render(shader, GL20.GL_TRIANGLES)
        shader.setUniformf("u_mode", ChunkManager.MODE_OBJECT)
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dispose() {
        chassisSeated.dispose(); chassisStanding.dispose(); chassisNoLegs.dispose(); chassisBare.dispose(); legL.dispose(); legR.dispose()
        front.dispose(); frontLower?.dispose(); glass?.dispose(); wheelF.dispose(); wheelR.dispose(); shadow.dispose()
    }
}
