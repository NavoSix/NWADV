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
    fun draw(ui: Ui, bike: Motorcycle, assists: Assists, cx: Float, y: Float) {
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
        val frac = (bike.rpm / 10_000).toFloat().coerceIn(0f, 1f)
        ui.shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.shapes.color = Color(0.2f, 0.2f, 0.2f, 1f); ui.shapes.rect(tx, barTop - u * 1.2f, tw, u * 1.2f)
        ui.shapes.color = if (bike.rpm > 8500) Color.RED else if (bike.rpm > 7000) Color.ORANGE else Color(0.4f, 0.85f, 0.4f, 1f)
        ui.shapes.rect(tx, barTop - u * 1.2f, tw * frac, u * 1.2f)
        ui.shapes.end()
        val r1 = barTop - u * 1.6f
        ui.text("%,d rpm".format(bike.rpm.toInt()), tx, r1, Color.LIGHT_GRAY)
        ui.text(bike.surfaceF.label, tx + tw, r1, Color.WHITE, Align.right)
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
        var lx = tx
        for ((t, c) in tags) { ui.text(t, lx, r2, c); lx += ui.textWidth(t) + u * 1.0f }
    }

    companion object { private val DIM = Color(0.55f, 0.6f, 0.55f, 1f) }
}
