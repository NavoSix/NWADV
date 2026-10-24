package com.northwoodsadv.ride.render

import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.world.Ground
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class CameraMode(val label: String) { CHASE("Chase"), HELMET("Helmet"), FAR("Far chase"), SIDE("Trackside") }

/** Follows the bike. Works in world ENU and writes render-space values into the libGDX camera. */
class ChaseCamera(val cam: PerspectiveCamera) {
    var mode = CameraMode.CHASE
    private var yaw = 0.0
    private var camE = 0.0; private var camN = 0.0; private var camH = 0.0
    private var init = false
    private val tmpM = Matrix4()
    private val v = Vector3()

    val worldE get() = camE
    val worldN get() = camN

    fun snap() { init = false }

    fun update(dt: Float, bike: Motorcycle, ground: Ground, originE: Double, originN: Double, baseH: Double, bikeTransform: Matrix4) {
        if (!init) { yaw = bike.yaw; init = true }
        // Follow the direction of travel when moving, the bike's heading when stopped.
        var target = if (bike.speed > 3) bike.course else bike.yaw
        if (bike.forwardSpeed < -0.5) target = bike.yaw
        var d = target - yaw
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        yaw += d * (1 - kotlin.math.exp(-dt * 3.0))

        val comE = bike.x; val comN = bike.y; val comH = bike.z
        when (mode) {
            CameraMode.CHASE, CameraMode.FAR -> {
                val dist = if (mode == CameraMode.FAR) 9.0 else 5.2
                val up = if (mode == CameraMode.FAR) 3.2 else 1.8
                camE = comE - cos(yaw) * dist
                camN = comN - sin(yaw) * dist
                camH = comH + up
                val g = ground.height(camE, camN, false)
                camH = max(camH, g + 0.8)
                cam.position.set((camE - originE).toFloat(), (camH - baseH).toFloat(), (-(camN - originN)).toFloat())
                cam.up.set(0f, 1f, 0f)
                cam.lookAt((comE - originE).toFloat(), (comH - baseH + 0.5).toFloat(), (-(comN - originN)).toFloat())
                cam.fieldOfView = 62f
            }
            CameraMode.HELMET -> {
                // Eyes in the helmet; the rider holds their head ~70% level against the lean.
                tmpM.set(bikeTransform)
                val head = if (bike.standing) v.set(0.25f, 1.80f, 0f) else v.set(0.0f, 1.66f, 0f)
                head.mul(tmpM)
                cam.position.set(head)
                camE = head.x + originE; camN = -head.z + originN; camH = head.y + baseH
                val fx = cos(bike.yaw).toFloat(); val fz = -sin(bike.yaw).toFloat()
                cam.direction.set(fx, (-0.12 + 0.6 * bike.pitch).toFloat(), fz).nor()
                val roll = -bike.lean * 0.3
                cam.up.set((sin(roll) * -fz).toFloat(), cos(roll).toFloat(), (sin(roll) * fx).toFloat()).nor()
                cam.fieldOfView = 75f
            }
            CameraMode.SIDE -> {
                camE = comE - sin(yaw) * 7 - cos(yaw) * 2
                camN = comN + cos(yaw) * 7 - sin(yaw) * 2
                camH = max(comH + 1.2, ground.height(camE, camN, false) + 1.0)
                cam.position.set((camE - originE).toFloat(), (camH - baseH).toFloat(), (-(camN - originN)).toFloat())
                cam.up.set(0f, 1f, 0f)
                cam.lookAt((comE - originE).toFloat(), (comH - baseH).toFloat(), (-(comN - originN)).toFloat())
                cam.fieldOfView = 55f
            }
        }
        cam.near = 0.25f
        cam.far = 6000f
        cam.update()
    }
}
