package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.bikes.BikeModel
import com.northwoodsadv.ride.bikes.Bikes
import com.northwoodsadv.ride.render.BikeRenderer
import com.northwoodsadv.ride.render.ChunkManager
import com.northwoodsadv.ride.render.Detail
import com.northwoodsadv.ride.render.MeshBuilder
import com.northwoodsadv.ride.render.Palette
import com.northwoodsadv.ride.render.WorldShader
import kotlin.math.cos
import kotlin.math.sin

/**
 * The garage: pick which club member's bike to ride. The chosen bike turns slowly on a gravel
 * pad (drag to spin it yourself) beside its spec sheet.
 */
class BikeSelectScreen(private val game: NwadvGame) : ScreenAdapter() {
    private var selected = Bikes.all.indexOfFirst { it.id == game.settings.bikeId }.coerceAtLeast(0)
    private val renderers = HashMap<String, BikeRenderer>()
    private val shader = WorldShader.create()
    private val cam = PerspectiveCamera(34f, 1f, 1f)
    private val transform = Matrix4()
    private val fixedAngle = System.getProperty("nwadv.garageAngle")?.toFloatOrNull() // development: still shots
    private var angle = fixedAngle ?: 2.3f
    private var dragX = Float.NaN
    private var time = 0f
    private val pad: Mesh = run {
        val m = MeshBuilder(256)
        val c = MeshBuilder.color(0x5a5048)
        val centre = m.vertex(0f, 0f, 0f, 0f, 1f, 0f, c)
        val n = 40
        for (i in 0..n) {
            val a = i * MeshBuilder.TWO_PI / n
            m.vertex(cos(a) * 2.2f, 0f, sin(a) * 2.2f, 0f, 1f, 0f, MeshBuilder.color(0x3e3832))
        }
        for (i in 1..n) m.tri(centre, i + 1, i)
        Mesh(true, m.vertCount, m.idxCount, ChunkManager.ATTRS).apply {
            setVertices(m.verts, 0, m.vertCount * MeshBuilder.STRIDE); setIndices(m.idx, 0, m.idxCount)
        }
    }

    private fun paintOf(b: BikeModel) = game.settings.paint(b.id) ?: b.paints.firstOrNull()?.id
    private fun renderer(b: BikeModel): BikeRenderer {
        val paint = paintOf(b)
        return renderers.getOrPut("${b.id}/$paint") { BikeRenderer(b.spec, b.look(Detail.SHOWROOM, paint)) }
    }

    override fun render(delta: Float) {
        val ui = game.ui
        val u = ui.u
        time += delta
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) { game.toMenu(); return }
        Gdx.gl.glClearColor(0.07f, 0.1f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        val bike = Bikes.all[selected]

        // --- turntable preview ------------------------------------------------------------
        val px = ui.w * 0.40f; val pw = ui.w - px - u * 2
        val ph = ui.h * 0.50f; val py = ui.h - ph - u * 3
        val pointerDown = Gdx.input.isTouched
        val mx = Gdx.input.x.toFloat(); val my = ui.h - Gdx.input.y
        if (pointerDown && mx in px..px + pw && my in py..py + ph) {
            if (!dragX.isNaN()) angle += (mx - dragX) / pw * 4f
            dragX = mx
        } else {
            dragX = Float.NaN
            if (fixedAngle == null) angle += delta * 0.35f
        }
        val bw = Gdx.graphics.backBufferWidth / ui.w
        val bh = Gdx.graphics.backBufferHeight / ui.h
        Gdx.gl.glViewport((px * bw).toInt(), (py * bh).toInt(), (pw * bw).toInt(), (ph * bh).toInt())
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glScissor((px * bw).toInt(), (py * bh).toInt(), (pw * bw).toInt(), (ph * bh).toInt())
        val sky = Palette.rgb(Palette.HAZE)
        Gdx.gl.glClearColor(sky[0] * 0.55f, sky[1] * 0.6f, sky[2] * 0.62f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        cam.viewportWidth = pw; cam.viewportHeight = ph
        val camDist = 3.3f * maxOf(1f, ph / pw * 1.2f)
        // Development: "distance,height,lookX,lookY" for close-up stills.
        val dev = System.getProperty("nwadv.garageCam")?.split(",")?.mapNotNull { it.toFloatOrNull() }
        if (dev != null && dev.size == 4) {
            cam.position.set(cos(0.35f) * dev[0] + dev[2], dev[1], sin(0.35f) * dev[0])
            cam.lookAt(dev[2], dev[3], 0f)
        } else {
            cam.position.set(cos(0.35f) * camDist, 1.3f, sin(0.35f) * camDist)
            cam.lookAt(0f, 0.68f, 0f)
        }
        cam.up.set(0f, 1f, 0f)
        cam.near = 0.1f; cam.far = 50f
        cam.update()
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL)
        shader.bind()
        shader.setUniformMatrix("u_projView", cam.combined)
        shader.setUniformf("u_sunDir", -0.45f, 0.75f, 0.48f)
        shader.setUniformf("u_camPos", cam.position)
        shader.setUniformf("u_fogColor", sky[0], sky[1], sky[2])
        shader.setUniformf("u_skyTop", sky[0], sky[1], sky[2])
        shader.setUniformf("u_fogDensity", 0f)
        shader.setUniformf("u_canopy", 0.2f, 0.3f, 0.2f)
        shader.setUniformf("u_canopyRange", 1000f, 2000f)
        shader.setUniformf("u_detail", 1f)
        shader.setUniformf("u_time", time)
        shader.setUniformf("u_light", 1f)
        shader.setUniformf("u_mode", ChunkManager.MODE_GROUND)
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        shader.setUniformf("u_useModel", 1f)
        transform.idt()
        shader.setUniformMatrix("u_model", transform)
        pad.render(shader, GL20.GL_TRIANGLES)
        shader.setUniformf("u_mode", ChunkManager.MODE_OBJECT)
        transform.idt().rotateRad(0f, 1f, 0f, angle)
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        renderer(bike).renderPose(shader, transform, BikeRenderer.Pose.NO_RIDER)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)

        // --- list and spec sheet ------------------------------------------------------------
        ui.begin()
        ui.text("GARAGE", u * 3, ui.h - u * 2.5f, Color(0.95f, 0.6f, 0.2f, 1f), Align.left, ui.big)
        ui.text("Choose whose bike to ride", u * 3, ui.h - u * 8.5f, Color.LIGHT_GRAY)
        val lw = px - u * 6
        val lh = u * 6.2f
        var ly = ui.h - u * 17f
        for ((i, b) in Bikes.all.withIndex()) {
            if (ui.button(b.listLabel, u * 3, ly, lw, lh, active = i == selected)) selected = i
            ly -= lh + u
        }

        ui.text("More club bikes coming", u * 3, ly - u * 0.5f, Color.GRAY)
        // Spec sheet under the preview.
        val s = bike.spec
        val imperial = game.settings.imperial
        fun mass(kg: Double) = if (imperial) "%.0f lb".format(kg * 2.20462) else "%.0f kg".format(kg)
        fun len(mm: Double) = if (imperial) "%.1f in".format(mm / 25.4) else "%.0f mm".format(mm)
        val lines = listOf(
            "Engine" to bike.engine,
            "Output" to bike.power,
            "Gears" to "${s.gearRatios.size}-speed",
            "Redline" to "%,d rpm".format(s.redlineRpm.toInt()),
            "Weight" to "${mass(s.massBike)} as set up",
            "Travel" to "${len(s.frontTravel * 1000)} / ${len(s.rearTravel * 1000)}",
            "Wheels" to "%.0f\" / %.0f\"".format(wheelInches(s.frontRadius, true), wheelInches(s.rearRadius, false)),
        )
        var ty = py - u * 1.5f
        for ((k, v) in lines) {
            ui.text(k, px, ty, Color.GRAY)
            ui.text(v, px + pw * 0.16f, ty, Color.LIGHT_GRAY)
            ty -= u * 2.3f
        }
        ui.text(bike.blurb, px + pw * 0.54f, py - u * 1.5f, Color(0.8f, 0.8f, 0.7f, 1f), Align.left, ui.font, pw * 0.46f, true)
        ui.text("Drag to turn", px + u, py + u * 2.5f, Color(1f, 1f, 1f, 0.6f))

        // Paint schemes along the top of the preview: a swatch of the two tank colours and the name.
        if (bike.paints.size > 1) {
            val current = paintOf(bike)
            val gap = u * 0.6f
            val bh = u * 4.2f
            // One row if every name fits beside its swatch, else two.
            val need = bike.paints.maxOf { ui.textWidth(it.label) } + bh * 0.55f + u * 2f
            val perRow = if ((pw - u * 2 - gap * (bike.paints.size - 1)) / bike.paints.size >= need) bike.paints.size else (bike.paints.size + 1) / 2
            val bw = minOf(maxOf(need, u * 12f), (pw - u * 2 - gap * (perRow - 1)) / perRow)
            for ((k, pt) in bike.paints.withIndex()) {
                val bx = px + u + (k % perRow) * (bw + gap)
                val by = py + ph - u - bh - (k / perRow) * (bh + gap)
                if (ui.button("", bx, by, bw, bh, active = pt.id == current)) game.settings.setPaint(bike.id, pt.id)
                val sw = bh * 0.55f
                val sx = bx + u * 0.7f; val sy = by + (bh - sw) / 2
                ui.text(pt.label, sx + sw + u * 0.6f, by + bh / 2 + ui.lineHeight() / 2, Color.WHITE, Align.left, ui.font, bw - sw - u * 1.8f)
                ui.rect(sx - 2, sy - 2, sw + 4, sw + 4, Color.BLACK)
                ui.rect(sx, sy + sw / 2, sw, sw / 2, swatch(pt.swatch))
                ui.rect(sx, sy, sw, sw / 2, swatch(pt.swatch2))
            }
        }

        val chosen = bike.id == game.settings.bikeId
        val btnW = pw * 0.34f; val btnH = u * 4.6f
        if (ui.button(if (chosen) "Riding this bike" else "Ride this bike", px + pw - btnW, u * 3, btnW, btnH, active = chosen)) {
            game.settings.bikeId = bike.id; game.settings.save(); game.toMenu()
        }
        if (ui.button("Back", u * 3, u * 3, lw * 0.5f, btnH)) game.toMenu()
        ui.endFrame()
    }

    private fun swatch(rgb: Int) = Color(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f, 1f)

    /** Nominal rim size from the tyre's outer radius (dual-sport tyres add ~8-11 cm to the rim). */
    private fun wheelInches(r: Double, front: Boolean) = (r * 2 - if (front) 0.162 else 0.216) / 0.0254

    override fun dispose() {
        renderers.values.forEach { it.dispose() }
        shader.dispose(); pad.dispose()
    }
}
