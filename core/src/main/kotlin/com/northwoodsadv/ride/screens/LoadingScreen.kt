package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.geo.GpxFile
import com.northwoodsadv.ride.world.World
import com.northwoodsadv.ride.world.WorldBuilder

/** Downloads / reads cached map data on a worker thread, then starts the ride. */
class LoadingScreen(private val game: NwadvGame, private val gpx: GpxFile) : ScreenAdapter() {
    @Volatile private var stage = "Starting"
    @Volatile private var fraction = 0f
    @Volatile private var result: World? = null
    @Volatile private var error: String? = null

    init {
        Thread({
            try {
                result = WorldBuilder(game.cache).build(gpx) { s, f -> stage = s; fraction = f }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                Gdx.app.error("Loading", "world build failed", e)
            }
        }, "world-builder").apply { isDaemon = true; start() }
    }

    override fun render(delta: Float) {
        result?.let { game.setScreen(RideScreen(game, it)); return }
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val ui = game.ui
        val u = ui.u
        ui.begin()
        ui.text(gpx.name, 0f, ui.h * 0.68f, Color.WHITE, Align.center, ui.font, ui.w)
        val km = gpx.ridePoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        ui.text(game.settings.distance(km), 0f, ui.h * 0.68f - u * 2.5f, Color.GRAY, Align.center, ui.font, ui.w)
        val err = error
        if (err != null) {
            ui.text("Couldn't build this ride: $err", u * 4, ui.h * 0.5f, Color(1f, 0.5f, 0.4f, 1f), Align.center, ui.font, ui.w - u * 8, true)
            if (ui.button("Back", ui.w / 2 - u * 8, ui.h * 0.3f, u * 16, u * 5)) game.toMenu()
        } else {
            val bw = ui.w * 0.6f; val bx = (ui.w - bw) / 2; val by = ui.h * 0.48f
            ui.rect(bx, by, bw, u * 1.6f, Color(0.2f, 0.2f, 0.2f, 1f))
            ui.rect(bx, by, bw * fraction.coerceIn(0f, 1f), u * 1.6f, Color(0.4f, 0.75f, 0.35f, 1f))
            ui.text(stage, 0f, by - u * 1.5f, Color.LIGHT_GRAY, Align.center, ui.font, ui.w)
            ui.text("First load downloads terrain and roads; after that the ride works offline.", 0f, by - u * 5f, Color.DARK_GRAY, Align.center, ui.font, ui.w)
        }
        ui.endFrame()
    }
}
