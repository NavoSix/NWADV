package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.RideSave
import com.northwoodsadv.ride.geo.GpxFile
import com.northwoodsadv.ride.physics.Difficulty
import com.northwoodsadv.ride.physics.SteerMode
import com.northwoodsadv.ride.render.Season

/**
 * Between choosing a route and riding it: the riding assists and the conditions (season, time of
 * day, how fast it passes) on two tabs, then *Confirm and start*. Choices are remembered for
 * the next ride.
 */
class RideSetupScreen(
    private val game: NwadvGame,
    private val name: String,
    private val contents: String,
    private val gpx: GpxFile,
) : ScreenAdapter() {
    private enum class Tab(val label: String) { ASSISTS("Riding assists"), CONDITIONS("Conditions") }
    private var tab = Tab.ASSISTS

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) { game.toMenu(); return }
        val ui = game.ui
        val s = game.settings
        val u = ui.u
        ui.begin()
        ui.text("RIDE SETUP", u * 3, ui.h - u * 2.5f, ORANGE, Align.left, ui.big)
        val bike = com.northwoodsadv.ride.bikes.Bikes.byId(s.bikeId)
        val save = s.rideSave(RideSave.key(name))?.takeIf { it.routeHash == contents.hashCode() }
        val where = if (save != null) "continuing at ${s.distance(save.along)}" else "from the start"
        ui.text("${gpx.name} on ${bike.shortDisplayName}, $where", u * 3, ui.h - u * 8.5f, Color.LIGHT_GRAY, Align.left, ui.font, ui.w - u * 6)

        // Tabs.
        val x0 = u * 3; val w = ui.w - u * 6
        val tabH = u * 4.2f
        var y = ui.h - u * 16f
        val tabW = (w - u) / Tab.entries.size
        for ((k, t) in Tab.entries.withIndex())
            if (ui.button(t.label, x0 + k * (tabW + u), y, tabW, tabH, active = t == tab)) tab = t
        ui.rect(x0, y - u * 0.5f, w, u * 0.25f, ORANGE)

        // Toggles, two to a row, below the tabs and above the buttons at the bottom.
        val btnH = u * 4.6f
        val rows = 4
        val sh = minOf(u * 5.2f, (y - u * 1.6f - (u * 3 + btnH + u * 1.5f) - (rows - 1) * u * 0.8f) / rows)
        var sy = y - u * 1.6f - sh
        var col = 0
        val tw = (w - u) / 2
        fun toggle(label: String, value: String, onClick: () -> Unit) {
            if (ui.button("$label\n$value", x0 + col * (tw + u), sy, tw, sh)) { onClick(); s.save() }
            col++
            if (col == 2) { col = 0; sy -= sh + u * 0.8f }
        }
        when (tab) {
            Tab.ASSISTS -> {
                val a = s.assists
                toggle("Difficulty", s.difficulty.label) { s.difficulty = Difficulty.entries[(s.difficulty.ordinal + 1) % Difficulty.entries.size] }
                toggle("Steering", a.steerMode.label) { a.steerMode = if (a.steerMode == SteerMode.LEAN) SteerMode.COUNTERSTEER else SteerMode.LEAN }
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
            }
            Tab.CONDITIONS -> {
                toggle("Season", if (s.season == Season.FALL) "Fall foliage" else "Summer") { s.season = if (s.season == Season.FALL) Season.SUMMER else Season.FALL }
                toggle("Start time", START_TIMES.firstOrNull { it.first == s.startHour }?.second ?: "Morning") {
                    val i = START_TIMES.indexOfFirst { it.first == s.startHour }
                    s.startHour = START_TIMES[(i + 1) % START_TIMES.size].first
                }
                toggle("Time speed", TIME_SPEEDS.firstOrNull { it.first == s.timeSpeed }?.second ?: "30x") {
                    val i = TIME_SPEEDS.indexOfFirst { it.first == s.timeSpeed }
                    s.timeSpeed = TIME_SPEEDS[(i + 1) % TIME_SPEEDS.size].first
                }
            }
        }

        if (ui.button("Back", x0, u * 3, w * 0.25f, btnH)) { game.toMenu(); ui.endFrame(); return }
        if (ui.button("Confirm and start", x0 + w * 0.6f, u * 3, w * 0.4f, btnH, active = true)) {
            ui.endFrame(); game.beginRide(name, contents, gpx); return
        }
        ui.endFrame()
    }

    companion object {
        private val ORANGE = Color(0.95f, 0.6f, 0.2f, 1f)
        /** Start-of-ride times: hour of day, or -1 for the real clock. */
        val START_TIMES = listOf(9.0 to "Morning", 13.0 to "Afternoon", 17.75 to "Golden hour", 21.5 to "Night", 5.5 to "Dawn", -1.0 to "Real clock")
        /** How fast the day passes (1 = real time; 60x = a day in 24 minutes). */
        val TIME_SPEEDS = listOf(1.0 to "Real time", 10.0 to "10x", 30.0 to "30x", 60.0 to "60x", 120.0 to "120x")
    }
}
