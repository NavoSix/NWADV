package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.bikes.Bikes
import com.northwoodsadv.ride.progress.Achievement
import com.northwoodsadv.ride.progress.HeroMilestone
import com.northwoodsadv.ride.progress.HeroTracker

/**
 * Hero sections cleared and the milestones for them; each bike's paint achievements, which stay
 * secret until they're earned.
 */
class AchievementsScreen(private val game: NwadvGame) : ScreenAdapter() {
    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) { game.toMenu(); return }
        val ui = game.ui
        val s = game.settings
        val u = ui.u
        ui.begin()
        ui.text("ACHIEVEMENTS", u * 3, ui.h - u * 2.5f, ORANGE, Align.left, ui.big)

        // Hero sections, left column.
        val colW = ui.w * 0.44f
        val cleared = s.number(HeroTracker.KEY).toInt()
        var y = ui.h - u * 9f
        ui.text("Hero sections cleared: $cleared", u * 3, y, Color.WHITE)
        y -= u * 2.2f
        val next = HeroMilestone.next(cleared)
        ui.text(next?.let { "Next: ${it.title} at ${it.count}" } ?: "Every milestone earned.", u * 3, y, Color.LIGHT_GRAY)
        y -= u * 2.2f
        val rowH = minOf(u * 3.2f, (y - u * 9f) / HeroMilestone.entries.size)
        for (m in HeroMilestone.entries) {
            val got = cleared >= m.count
            ui.rect(u * 3, y - rowH + u * 0.3f, colW, rowH - u * 0.3f, if (got) EARNED else LOCKED)
            ui.text(m.title, u * 4, y - u * 0.5f, if (got) ORANGE else Color.GRAY)
            ui.text(if (got) "Earned" else "$cleared / ${m.count}", u * 2 + colW, y - u * 0.5f, if (got) Color.LIGHT_GRAY else Color.GRAY, Align.right)
            y -= rowH
        }

        // Paint achievements by bike, right column: secret until earned.
        val rx = ui.w * 0.52f; val rw = ui.w * 0.45f
        var ry = ui.h - u * 9f
        ui.text("Paint schemes", rx, ry, Color.WHITE)
        ry -= u * 2.4f
        // Two lines a row (title, then what it took) if they all fit above the Back button,
        // otherwise one (title, and the paint on the right).
        val withPaints = Bikes.all.filter { Achievement.forBike(it.id).isNotEmpty() }
        val paintCount = withPaints.sumOf { Achievement.forBike(it.id).size }
        val twoLine = u * 2.4f + ui.lineHeight() * 2
        val fit = (ry - u * 8f - withPaints.size * u * 2.8f) / paintCount.coerceAtLeast(1)
        val paintRowH = minOf(twoLine, fit)
        val two = paintRowH >= twoLine * 0.98f
        for (bike in withPaints) {
            ui.text(bike.displayName, rx, ry, Color.LIGHT_GRAY)
            ry -= u * 1.8f
            for (a in Achievement.forBike(bike.id)) {
                val got = s.isUnlocked(a)
                ui.rect(rx, ry - paintRowH + u * 0.3f, rw, paintRowH - u * 0.3f, if (got) EARNED else LOCKED)
                val ty = if (two) ry - u * 0.5f else ry - (paintRowH - u * 0.3f - ui.lineHeight()) / 2
                ui.text(if (got) a.title else "Secret", rx + u, ty, if (got) GOLD else Color.GRAY)
                if (two) ui.text(if (got) "${a.story}: ${a.paintName} paint" else "Keep riding to find out", rx + u, ry - u * 0.9f - ui.lineHeight(),
                    if (got) Color.LIGHT_GRAY else Color.DARK_GRAY)
                else ui.text(if (got) a.paintName else "Keep riding", rx + rw - u, ty, if (got) Color.LIGHT_GRAY else Color.DARK_GRAY, Align.right)
                ry -= paintRowH
            }
            ry -= u
        }

        if (ui.button("Back", u * 3, u * 2.5f, ui.w * 0.2f, u * 4.2f)) game.toMenu()
        ui.endFrame()
    }

    private companion object {
        val ORANGE = Color(0.95f, 0.6f, 0.2f, 1f)
        val GOLD = Color(0.95f, 0.72f, 0.2f, 1f)
        val EARNED = Color(0.22f, 0.2f, 0.12f, 1f)
        val LOCKED = Color(0.14f, 0.16f, 0.15f, 1f)
    }
}
