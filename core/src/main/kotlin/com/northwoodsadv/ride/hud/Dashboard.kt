package com.northwoodsadv.ride.hud

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.Settings
import com.northwoodsadv.ride.physics.Assists
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.ui.Ui
import kotlin.math.abs

/** Speed, gear, revs and what the tyres and electronics are doing. */
class Dashboard(private val settings: Settings) {
    fun draw(ui: Ui, bike: Motorcycle, assists: Assists, cx: Float, y: Float, clock: String? = null, lights: Boolean = false) {
        val u = ui.u
        val row = ui.lineHeight() + u * 0.3f
        val h = u * 1.6f + row * 2 + u * 0.6f
        val w = u * 40f
        val x = cx - w / 2
        ui.rect(x, y, w, h, Ui.PANEL)
        // Speed and gear on the left.
        val speed = settings.speed(abs(bike.forwardSpeed))
        val top = y + h - u * 0.5f
        ui.text("%d".format(speed.toInt()), x + u * 9.5f, top, Color.WHITE, Align.right, ui.big)
        ui.text(settings.speedUnit, x + u * 10.2f, y + u * 1.0f + ui.lineHeight(), Color.LIGHT_GRAY)
        ui.text(if (bike.gear == 0) "N" else bike.gear.toString(), x + u * 17f, top, Ui.ACCENT, Align.center, ui.big)

        // Right block: tach bar, then two text rows.
        val tx = x + u * 20f; val tw = w - u * 21f
        val barTop = top - u * 0.2f
        val red = bike.spec.redlineRpm
        val frac = (bike.rpm / (red * 1.05)).toFloat().coerceIn(0f, 1f)
        ui.shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.shapes.color = Color(0.2f, 0.2f, 0.2f, 1f); ui.shapes.rect(tx, barTop - u * 1.2f, tw, u * 1.2f)
        ui.shapes.color = if (bike.rpm > red * 0.9) Color.RED else if (bike.rpm > red * 0.74) Color.ORANGE else Color(0.4f, 0.85f, 0.4f, 1f)
        ui.shapes.rect(tx, barTop - u * 1.2f, tw * frac, u * 1.2f)
        ui.shapes.end()
        val r1 = barTop - u * 1.6f
        // Long surface names ("Water crossing") squeeze the revs down to "4.2k".
        val label = bike.surfaceF.label
        val rpm = "%,d rpm".format(bike.rpm.toInt()).let { if (ui.textWidth(it) + ui.textWidth(label) + u > tw) "%.1fk".format(bike.rpm / 1000) else it }
        ui.text(rpm, tx, r1, Color.LIGHT_GRAY)
        ui.text(label, tx + tw, r1, Color.WHITE, Align.right)
        val r2 = r1 - row
        val tags = ArrayList<Pair<String, Color>>()
        tags += (if (assists.tractionControl) "TC" else "TC off") to if (bike.tcActive) Ui.ACCENT else DIM
        tags += (if (assists.absFront) (if (assists.absRear) "ABS" else "ABS F") else "ABS off") to if (bike.absActive) Ui.ACCENT else DIM
        if (bike.standing) tags += "STAND" to Color(0.6f, 0.9f, 1f, 1f)
        if (bike.scraping) tags += "PEGS" to Color.RED
        if (bike.airborne) tags += "AIR" to Color.CYAN
        if (bike.buddyPush) tags += "PUSH!" to Color.YELLOW
        if (bike.paddling) tags += "REVERSE" to Color.YELLOW
        if (bike.slipR > 1.05 || bike.slipF > 1.05) tags += "SLIDE" to Color.ORANGE
        if (lights) tags.add(0, "LIGHTS" to Color(0.4f, 0.8f, 0.4f, 1f))
        var lx = tx
        for ((t, c) in tags) { ui.text(t, lx, r2, c); lx += ui.textWidth(t) + u * 1.0f }
        // Time of day on a tab off the right end of the panel: above it, or below when the panel is at the top.
        clock?.let {
            val cw = ui.textWidth(it) + u * 1.6f
            val th = ui.lineHeight() + u * 0.6f
            val ty = if (y + h + u * 0.3f + th <= ui.h) y + h + u * 0.3f else y - u * 0.3f - th
            ui.rect(x + w - cw, ty, cw, th, Ui.PANEL)
            ui.text(it, x + w - u * 0.8f, ty + u * 0.3f + ui.lineHeight(), Color.LIGHT_GRAY, Align.right)
        }
    }

    companion object { private val DIM = Color(0.55f, 0.6f, 0.55f, 1f) }
}
