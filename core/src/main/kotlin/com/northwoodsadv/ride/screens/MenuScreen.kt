package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.physics.Difficulty
import com.northwoodsadv.ride.physics.SteerMode
import com.northwoodsadv.ride.render.Season

class MenuScreen(private val game: NwadvGame, private var message: String? = null) : ScreenAdapter() {
    private var scroll = 0f
    private var picking = false

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val ui = game.ui
        val s = game.settings
        val u = ui.u
        ui.begin()
        ui.text("NORTHWOODS ADV", u * 3, ui.h - u * 2.5f, Color(0.95f, 0.6f, 0.2f, 1f), Align.left, ui.big)
        ui.text("Ride your club's routes on real New Hampshire roads and terrain", u * 3, ui.h - u * 8.5f, Color.LIGHT_GRAY)

        // Routes.
        val colW = ui.w * 0.48f
        var y = ui.h - u * 17f
        val bh = u * 4.6f
        if (ui.button(if (picking) "Waiting for file picker..." else "Load GPX file...", u * 3, y, colW, bh, enabled = !picking)) {
            picking = true
            game.platform.pickGpxFile { name, contents ->
                picking = false
                if (name != null && contents != null) game.openGpx(name, contents)
            }
        }
        y -= bh + u * 1.5f
        ui.text("Rides", u * 3, y, Color.GRAY)
        y -= u * 1.2f
        val entries = game.routes.entries()
        scroll = (scroll - ui.scroll * u * 3).coerceIn(0f, maxOf(0f, entries.size * (bh + u) - (y - u * 6)))
        var ry = y - bh + scroll
        for (e in entries) {
            if (ry < u * 6 || ry > y) { ry -= bh + u; continue }
            // Routes ridden part way say where you'll pick up.
            val save = s.rideSave(com.northwoodsadv.ride.RideSave.key(e.title))
            val label = if (save != null) "${e.title}\nContinue at ${s.distance(save.along)}" else e.title
            if (ui.button(label, u * 3, ry, colW, bh)) {
                try { game.openGpx(e.title + ".gpx", e.load()) } catch (ex: Exception) { message = "Couldn't read ${e.title}: ${ex.message}" }
            }
            ry -= bh + u
        }

        // Settings.
        val sx = ui.w * 0.56f; val sw = ui.w * 0.40f
        var sy = ui.h - u * 17f
        val sh = u * 5.2f
        var col = 0
        val tw = (sw - u) / 2
        fun toggle(label: String, value: String, onClick: () -> Unit) {
            if (ui.button("$label\n$value", sx + col * (tw + u), sy, tw, sh)) { onClick(); s.save() }
            col++
            if (col == 2) { col = 0; sy -= sh + u * 0.8f }
        }
        // Bike: opens the garage.
        val bikeName = com.northwoodsadv.ride.bikes.Bikes.byId(s.bikeId).shortDisplayName
        val bh2 = sh * 0.75f
        if (ui.button("Bike: $bikeName", sx, sy + sh - bh2, sw, bh2)) { game.setScreen(BikeSelectScreen(game)); ui.endFrame(); return }
        sy -= bh2 + u * 0.8f
        val a = s.assists
        toggle("Steering", a.steerMode.label) { a.steerMode = if (a.steerMode == SteerMode.LEAN) SteerMode.COUNTERSTEER else SteerMode.LEAN }
        toggle("Difficulty", s.difficulty.label) { s.difficulty = Difficulty.entries[(s.difficulty.ordinal + 1) % Difficulty.entries.size] }
        toggle("ABS", when { a.absFront && a.absRear -> "On"; a.absFront -> "Front only"; else -> "Off" }) {
            when {
                a.absFront && a.absRear -> a.absRear = false
                a.absFront -> a.absFront = false
                else -> { a.absFront = true; a.absRear = true }
            }
        }
        toggle("Traction control", if (a.tractionControl) "On" else "Off") { a.tractionControl = !a.tractionControl }
        toggle("Wheelie control", if (a.wheelieControl) "On" else "Off") { a.wheelieControl = !a.wheelieControl }
        toggle("Gears", if (a.autoShift) "Auto shift" else "Manual") { a.autoShift = !a.autoShift }
        toggle("Walking pace", if (a.lowSpeedBalance) "Feet down" else "Off") { a.lowSpeedBalance = !a.lowSpeedBalance }
        toggle("Season", if (s.season == Season.FALL) "Fall foliage" else "Summer") { s.season = if (s.season == Season.FALL) Season.SUMMER else Season.FALL }
        toggle("Units", if (s.imperial) "Imperial" else "Metric") { s.imperial = !s.imperial }

        message?.let { ui.text(it, u * 3, u * 5.5f, Color(1f, 0.5f, 0.4f, 1f), Align.left, ui.font, ui.w - u * 6, true) }
        ui.text("Map data (c) OpenStreetMap contributors (ODbL). Elevation: USGS 3DEP via AWS Terrain Tiles.", u * 3, u * 2.6f, Color.DARK_GRAY)
        ui.endFrame()
    }
}
