package com.northwoodsadv.ride.hud

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.Settings
import com.northwoodsadv.ride.physics.RiderInput
import com.northwoodsadv.ride.ui.Ui
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Turns keyboard and touch into [RiderInput].
 *
 * Touch layout (landscape): a floating joystick on the left (left/right = lean, up/down = shift
 * your weight forward/back); throttle slider at the right edge with the front brake slider and
 * rear brake button beside it.
 */
class RideControls(@Suppress("unused") private val settings: Settings, private val touch: Boolean) {
    val input = RiderInput()
    private var keySteer = 0.0
    var standToggle = false
    private var stickPointer = -1
    private var stickX = 0f; private var stickY = 0f
    private var knobX = 0f; private var knobY = 0f

    private class Layout(ui: Ui) {
        val u = ui.u
        val sw = u * 6.5f
        val sh = ui.h * 0.40f
        val throttleX = ui.w - sw - u * 1.5f
        val brakeX = throttleX - sw - u * 1.2f
        val y = u * 1.5f
        val rearX = brakeX - sw - u * 1.2f
        val rearY = y
        val rearH = ui.h * 0.22f
        val stickR = u * 7.5f
        val stickZoneW = ui.w * 0.42f
        val stickZoneH = ui.h * 0.72f
        val stickHomeX = u * 12f
        val stickHomeY = u * 12f
    }

    fun update(ui: Ui, dt: Float) {
        val i = input
        // --- keyboard (desktop, or a Bluetooth keyboard / controller mapped to keys) ---------
        val left = Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)
        val right = Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)
        val target = (if (right) 1.0 else 0.0) - (if (left) 1.0 else 0.0)
        // Ramp like moving your body/bars, not a digital snap.
        keySteer += (target - keySteer).coerceIn(-dt * 2.5, dt * 2.5)
        var steer = keySteer
        var throttle = if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) 1.0 else 0.0
        var front = if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) 1.0 else 0.0
        var rear = if (Gdx.input.isKeyPressed(Input.Keys.SPACE)) 1.0 else 0.0
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)) throttle *= 0.4 // gentle throttle modifier
        i.shiftUp = Gdx.input.isKeyJustPressed(Input.Keys.E)
        i.shiftDown = Gdx.input.isKeyJustPressed(Input.Keys.Q)
        if (Gdx.input.isKeyJustPressed(Input.Keys.F)) standToggle = !standToggle
        var weight = (if (Gdx.input.isKeyPressed(Input.Keys.R)) 1.0 else 0.0) - (if (Gdx.input.isKeyPressed(Input.Keys.V)) 1.0 else 0.0)

        // --- touch ------------------------------------------------------------------------
        if (touch) {
            val l = Layout(ui)
            val t = ui.slider(l.throttleX, l.y, l.sw, l.sh)
            if (t >= 0) throttle = t.toDouble()
            val fb = ui.slider(l.brakeX, l.y, l.sw, l.sh)
            if (fb >= 0) front = fb.toDouble()
            if (ui.pointerIn(l.rearX, l.rearY, l.sw, l.rearH)) rear = 1.0

            // Floating joystick: centred wherever your thumb lands in the left zone.
            val p = ui.pointerStartedIn(0f, 0f, l.stickZoneW, l.stickZoneH)
            if (p >= 0) {
                if (p != stickPointer) { stickPointer = p; val d = ui.downPos(p); stickX = d[0]; stickY = d[1] }
                val pos = ui.pointerPos(p)
                var dx = pos[0] - stickX; var dy = pos[1] - stickY
                val len = hypot(dx, dy)
                if (len > l.stickR) { dx *= l.stickR / len; dy *= l.stickR / len }
                knobX = stickX + dx; knobY = stickY + dy
                steer = shape(dx / l.stickR)
                weight = (dy / l.stickR).toDouble().let { if (abs(it) < 0.35) 0.0 else (it - sign(it) * 0.35) / 0.65 }
            } else {
                stickPointer = -1
                stickX = l.stickHomeX; stickY = l.stickHomeY; knobX = stickX; knobY = stickY
            }
        }
        i.steer = steer
        i.throttle = throttle
        i.frontBrake = front
        i.rearBrake = rear
        i.standing = standToggle
        i.weightShift = weight
    }

    /** Dead zone and a soft centre so small corrections are easy. */
    private fun shape(x: Float): Double {
        val a = abs(x)
        if (a < 0.06f) return 0.0
        val v = (a - 0.06f) / 0.94f
        return (sign(x) * (0.45f * v + 0.55f * v * v)).toDouble()
    }

    fun draw(ui: Ui) {
        if (!touch) return
        val l = Layout(ui)
        slider(ui, l.throttleX, l.y, l.sw, l.sh, input.throttle.toFloat(), "GAS", Color(0.3f, 0.75f, 0.35f, 0.75f))
        slider(ui, l.brakeX, l.y, l.sw, l.sh, input.frontBrake.toFloat(), "FRONT\nBRAKE", Color(0.85f, 0.3f, 0.25f, 0.75f))
        ui.rect(l.rearX, l.rearY, l.sw, l.rearH, if (input.rearBrake > 0) Color(0.85f, 0.3f, 0.25f, 0.8f) else Ui.BUTTON)
        ui.text("REAR", l.rearX, l.rearY + l.rearH / 2 + ui.u * 0.5f, Color.WHITE, Align.center, ui.font, l.sw)

        // Joystick.
        val active = stickPointer >= 0
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND)
        ui.shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.shapes.color = Color(1f, 1f, 1f, if (active) 0.12f else 0.07f)
        ui.shapes.circle(stickX, stickY, l.stickR, 40)
        ui.shapes.color = Color(1f, 1f, 1f, if (active) 0.45f else 0.25f)
        ui.shapes.circle(knobX, knobY, l.stickR * 0.42f, 30)
        ui.shapes.end()
        if (!active) ui.text("LEAN", stickX - l.stickR, stickY + ui.u * 0.6f, Color(1f, 1f, 1f, 0.5f), Align.center, ui.font, l.stickR * 2)
    }

    private fun slider(ui: Ui, x: Float, y: Float, w: Float, h: Float, v: Float, label: String, c: Color) {
        ui.rect(x, y, w, h, Ui.BUTTON)
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND)
        ui.shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.shapes.color = c; ui.shapes.rect(x, y, w, h * v)
        ui.shapes.end()
        ui.text(label, x, y + h - ui.u, Color.WHITE, Align.center, ui.font, w)
    }
}
