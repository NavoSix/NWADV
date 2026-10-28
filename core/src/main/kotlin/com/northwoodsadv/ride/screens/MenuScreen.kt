package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame

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
        val loadW = colW * 0.6f
        if (ui.button(if (picking) "Waiting for file picker..." else "Load GPX file...", u * 3, y, loadW, bh, enabled = !picking)) {
            picking = true
            game.platform.pickGpxFile { name, contents ->
                picking = false
                if (name != null && contents != null) game.openGpx(name, contents)
            }
        }
        if (ui.button("Achievements", u * 4 + loadW, y, colW - loadW - u, bh)) { game.setScreen(AchievementsScreen(game)); ui.endFrame(); return }
        y -= bh + u * 1.5f
        ui.text("Rides", u * 3, y, Color.GRAY)
        y -= u * 2.2f
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

        // The bike, and units. Assists and conditions are set when a route is chosen.
        val sx = ui.w * 0.56f; val sw = ui.w * 0.40f
        var sy = ui.h - u * 17f
        val bikeName = com.northwoodsadv.ride.bikes.Bikes.byId(s.bikeId).shortDisplayName
        if (ui.button("Bike\n$bikeName", sx, sy, sw, u * 5.2f)) { game.setScreen(BikeSelectScreen(game)); ui.endFrame(); return }
        sy -= u * 5.2f + u
        if (ui.button("Units\n${if (s.imperial) "Imperial" else "Metric"}", sx, sy - u * 0.6f, sw, u * 5.2f)) { s.imperial = !s.imperial; s.save() }
        ui.text("Pick a ride to set the assists, season and time of day.", sx, sy - u * 8f, Color.GRAY, Align.left, ui.font, sw, true)

        message?.let { ui.text(it, u * 3, u * 5.5f, Color(1f, 0.5f, 0.4f, 1f), Align.left, ui.font, ui.w - u * 6, true) }
        ui.text("Map data (c) OpenStreetMap contributors (ODbL). Elevation: USGS 3DEP via AWS Terrain Tiles.", u * 3, u * 2.6f, Color.DARK_GRAY)
        ui.endFrame()
    }
}
