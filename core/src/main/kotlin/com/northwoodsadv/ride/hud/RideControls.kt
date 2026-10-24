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
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Turns keyboard, touch and phone tilt into [RiderInput].
 *
 * Touch layout (landscape): front brake slider bottom-left with the rear brake beside it;
 * throttle slider bottom-right. Tilt the phone like a handlebar to lean, or use the on-screen
 * steering strip if tilt is off.
 */
class RideControls(private val settings: Settings, private val touch: Boolean) {
    val input = RiderInput()
    private var tiltZero = Float.NaN
    private var keySteer = 0.0
    var standToggle = false
    var lastTilt = 0f; private set

    fun recenterTilt() { tiltZero = Float.NaN }

    /** Phone roll angle in degrees in the current landscape orientation. */
    private fun tiltDegrees(): Float {
        val ay = Gdx.input.accelerometerY; val ax = Gdx.input.accelerometerX; val az = Gdx.input.accelerometerZ
        // Native portrait device held in landscape: roll is around the long axis.
        val roll = when (Gdx.input.rotation) {
            90 -> atan2(ay, az)
            270 -> -atan2(ay, az)
            180 -> -atan2(ax, az)
            else -> atan2(ax, az)
        }
        return Math.toDegrees(roll.toDouble()).toFloat()
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
        i.weightShift = (if (Gdx.input.isKeyPressed(Input.Keys.R)) 1.0 else 0.0) - (if (Gdx.input.isKeyPressed(Input.Keys.V)) 1.0 else 0.0)

        // --- touch ------------------------------------------------------------------------
        if (touch) {
            val u = ui.u
            val sw = u * 7f; val sh = ui.h * 0.52f
            val t = ui.slider(ui.w - sw - u * 1.5f, u * 1.5f, sw, sh)
            if (t >= 0) throttle = t.toDouble()
            val fb = ui.slider(u * 1.5f, u * 1.5f, sw, sh)
            if (fb >= 0) front = fb.toDouble()
            if (ui.pointerIn(u * 2.5f + sw, u * 1.5f, u * 6f, u * 9f)) rear = 1.0
            if (settings.tiltSteering) {
                val tilt = tiltDegrees()
                if (tiltZero.isNaN()) tiltZero = tilt
                var d = tilt - tiltZero
                lastTilt = d
                d = if (abs(d) < 1.5f) 0f else d - sign(d) * 1.5f
                steer = (d / settings.tiltRange).toDouble().coerceIn(-1.0, 1.0)
            } else {
                val hs = ui.hslider(ui.w * 0.3f, u * 10f, ui.w * 0.4f, u * 8f)
                if (!hs.isNaN()) steer = hs.toDouble()
            }
        }
        i.steer = steer
        i.throttle = throttle
        i.frontBrake = front
        i.rearBrake = rear
        i.standing = standToggle
    }

    /** Draws the touch controls. Returns nothing; buttons are handled by the ride screen. */
    fun draw(ui: Ui) {
        if (!touch) return
        val u = ui.u
        val sw = u * 7f; val sh = ui.h * 0.52f
        slider(ui, ui.w - sw - u * 1.5f, u * 1.5f, sw, sh, input.throttle.toFloat(), "THROTTLE", Color(0.3f, 0.75f, 0.35f, 0.75f))
        slider(ui, u * 1.5f, u * 1.5f, sw, sh, input.frontBrake.toFloat(), "FRONT\nBRAKE", Color(0.85f, 0.3f, 0.25f, 0.75f))
        val rx = u * 2.5f + sw; val rw = u * 6f; val rh = u * 9f
        ui.rect(rx, u * 1.5f, rw, rh, if (input.rearBrake > 0) Color(0.85f, 0.3f, 0.25f, 0.8f) else Ui.BUTTON)
        ui.text("REAR", rx, u * 1.5f + rh / 2 + u * 0.5f, Color.WHITE, Align.center, ui.font, rw)
        if (!settings.tiltSteering) {
            ui.rect(ui.w * 0.3f, u * 10f, ui.w * 0.4f, u * 8f, Color(1f, 1f, 1f, 0.08f))
            ui.text("drag to steer", ui.w * 0.3f, u * 14.5f, Color(1f, 1f, 1f, 0.5f), Align.center, ui.font, ui.w * 0.4f)
        }
    }

    private fun slider(ui: Ui, x: Float, y: Float, w: Float, h: Float, v: Float, label: String, c: Color) {
        ui.rect(x, y, w, h, Ui.BUTTON)
        ui.shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.shapes.color = c; ui.shapes.rect(x, y, w, h * v)
        ui.shapes.end()
        ui.text(label, x, y + h - ui.u, Color.WHITE, Align.center, ui.font, w)
    }
}
