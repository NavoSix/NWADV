package com.northwoodsadv.ride.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.hud.Dashboard
import com.northwoodsadv.ride.hud.GpsUnit
import com.northwoodsadv.ride.hud.RideControls
import com.northwoodsadv.ride.physics.Autopilot
import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.render.BikeRenderer
import com.northwoodsadv.ride.render.CameraMode
import com.northwoodsadv.ride.render.ChaseCamera
import com.northwoodsadv.ride.render.ChunkBuilder
import com.northwoodsadv.ride.render.ChunkManager
import com.northwoodsadv.ride.render.Palette
import com.northwoodsadv.ride.render.WorldShader
import com.northwoodsadv.ride.route.RouteTracker
import com.northwoodsadv.ride.ui.Ui
import com.northwoodsadv.ride.world.World
import kotlin.math.abs
import kotlin.math.floor

class RideScreen(private val game: NwadvGame, private val world: World) : ScreenAdapter() {
    private val settings = game.settings
    private val spec = BikeSpec()
    private val bike = Motorcycle(spec, world.ground)
    private val tracker = RouteTracker(world.route)
    private val autopilot = Autopilot(world.route, world.ground)
    private var autoRide = game.autoplay
    private val controls = RideControls(settings, game.platform.isTouchDevice)
    private val shader = WorldShader.create()
    private val baseH = world.ground.height(world.spawnX, world.spawnY, false)
    private val chunks = ChunkManager(ChunkBuilder(world.ground, baseH, settings.season), if (Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.Desktop) 3 else 2)
    private val bikeRenderer = BikeRenderer(spec)
    private val cam = PerspectiveCamera(62f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
    private val chase = ChaseCamera(cam).apply { mode = settings.camera }
    private val gps = GpsUnit(world, tracker, settings)
    private val dash = Dashboard(settings)
    private val bikeM = Matrix4()
    private val audio = com.northwoodsadv.ride.audio.RideAudio()
    private var originE = 0.0; private var originN = 0.0

    private var paused = false
    private var crashTimer = 0.0
    private var safeTimer = 0.0
    private var safeX = world.spawnX; private var safeY = world.spawnY; private var safeHeading = world.spawnHeading
    private var banner: String? = null
    private var bannerTimer = 0.0
    private var notesTimer = if (world.notes.isEmpty()) 0.0 else 9.0
    private var wasCrashed = false
    private val sky = Palette.rgb(Palette.SKY)
    private val haze = Palette.rgb(Palette.HAZE)
    private val canopy = Palette.rgb(Palette.CANOPY)

    init {
        bike.reset(world.spawnX, world.spawnY, world.spawnHeading)
        // Development: start part-way along the route (desktop --start-at <metres>).
        System.getProperty("nwadv.startAt")?.toDoubleOrNull()?.let { at ->
            val p = DoubleArray(3); world.route.pointAt(at, p); bike.reset(p[0], p[1], p[2])
        }
        System.getProperty("nwadv.startRoadAt")?.toDoubleOrNull()?.let { at ->
            val p = DoubleArray(3); world.roads[0].line.pointAt(at, p); bike.reset(p[0], p[1], p[2])
        }
        rebase(true)
        banner = world.name; bannerTimer = 4.0
    }

    private fun rebase(force: Boolean) {
        val e = bike.x; val n = bike.y
        if (force || abs(e - originE) > 512 || abs(n - originN) > 512) {
            originE = floor(e / 256) * 256; originN = floor(n / 256) * 256
        }
    }

    override fun resize(width: Int, height: Int) {
        cam.viewportWidth = width.toFloat(); cam.viewportHeight = height.toFloat()
    }

    override fun render(delta: Float) {
        val dt = delta.coerceAtMost(0.05f)
        val ui = game.ui
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) paused = !paused
        if (Gdx.input.isKeyJustPressed(Input.Keys.C)) cycleCamera()
        if (Gdx.input.isKeyJustPressed(Input.Keys.P)) autoRide = !autoRide
        if (Gdx.input.isKeyJustPressed(Input.Keys.M)) gps.zoom = if (gps.zoom > 2000) 300f else gps.zoom * 2
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE)) respawn()

        // --- simulate ---------------------------------------------------------------------
        controls.update(ui, dt)
        if (!paused) {
            if (autoRide) autopilot.drive(bike, settings.assists, controls.input)
            bike.update(dt.toDouble(), controls.input, settings.assists)
            tracker.update(bike.x, bike.y, dt.toDouble())
            gps.recordCrumb(bike.x, bike.y)
            handleCrash(dt.toDouble())
        }

        audio.rpm = bike.rpm.toFloat()
        audio.throttle = controls.input.throttle.toFloat()
        audio.speed = bike.speed.toFloat()
        audio.slip = maxOf(bike.slipF, bike.slipR).toFloat()
        audio.loose = if (bike.surfaceF.isLoose && !bike.airborne) 1f else 0f
        audio.muted = paused

        // --- 3D -----------------------------------------------------------------------------
        rebase(false)
        bikeRenderer.chassisTransform(bike, originE, originN, baseH, bikeM)
        chase.update(dt, bike, world.ground, originE, originN, baseH, bikeM)
        chunks.update(chase.worldE, chase.worldN, 4500.0)

        Gdx.gl.glClearColor(sky[0], sky[1], sky[2], 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL)
        Gdx.gl.glDisable(GL20.GL_BLEND)
        shader.bind()
        shader.setUniformMatrix("u_projView", cam.combined)
        shader.setUniformf("u_useModel", 0f)
        shader.setUniformf("u_sunDir", -0.45f, 0.75f, 0.48f)
        shader.setUniformf("u_camPos", cam.position)
        shader.setUniformf("u_fogColor", haze[0], haze[1], haze[2])
        shader.setUniformf("u_fogDensity", 1f / 2600f)
        shader.setUniformf("u_canopy", canopy[0], canopy[1], canopy[2])
        shader.setUniformf("u_canopyRange", 150f, 330f)
        shader.setUniformf("u_detail", 1f)
        chunks.render(shader, cam, originE, originN)
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        bikeRenderer.render(shader, bike, originE, originN, baseH, world.ground.height(bike.x, bike.y))
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        // --- HUD ----------------------------------------------------------------------------
        drawHud(dt)
        ui.endFrame()
    }

    private fun handleCrash(dt: Double) {
        val crash = bike.crash
        if (crash != null) {
            if (!wasCrashed) { game.platform.vibrate(350); banner = crash.message; bannerTimer = 3.0 }
            wasCrashed = true
            crashTimer += dt
            if (crashTimer > 2.6) respawn()
            return
        }
        wasCrashed = false
        if (bike.lastImpact > 6) game.platform.vibrate(40)
        safeTimer += dt
        if (safeTimer > 2 && abs(bike.lean) < 0.45 && bike.speed > 1.5 && !bike.airborne) {
            safeTimer = 0.0; safeX = bike.x; safeY = bike.y; safeHeading = bike.yaw
        }
    }

    /** Back on the route a little behind where you went down (or the last safe spot if lost). */
    private fun respawn() {
        crashTimer = 0.0
        val p = DoubleArray(3)
        if (tracker.offRoute < 60) {
            world.route.resample(5.0).pointAt((tracker.along - 12).coerceAtLeast(0.0), p)
            val ahead = DoubleArray(3); world.route.pointAt((tracker.along - 6).coerceAtLeast(0.0) + 6, ahead)
            bike.reset(p[0], p[1], kotlin.math.atan2(ahead[1] - p[1], ahead[0] - p[0]))
        } else bike.reset(safeX, safeY, safeHeading)
        chase.snap()
    }

    private fun cycleCamera() {
        val all = CameraMode.entries
        chase.mode = all[(all.indexOf(chase.mode) + 1) % all.size]
        settings.camera = chase.mode; settings.save()
    }

    private fun drawHud(dt: Float) {
        val ui = game.ui
        val u = ui.u
        ui.begin()
        // GPS on the "handlebar" (top right), collapsible.
        val gw = ui.w * 0.31f; val gh = ui.h * 0.50f
        val gx = ui.w - gw - u * 1.5f
        val zb = u * 4.2f
        if (settings.gpsCollapsed) {
            val cw = ui.w * 0.26f
            gps.drawCompact(ui, bike, ui.w - cw - u * 1.5f, ui.h - u * 1.5f, cw, dt)
            if (ui.button("Map", ui.w - cw - u * 2.7f - zb * 2f, ui.h - u * 1.5f - zb, zb * 2f, zb)) { settings.gpsCollapsed = false; settings.save() }
        } else {
            gps.draw(ui, bike, gx, ui.h - gh - u * 1.5f, gw, gh, dt)
            if (ui.button("Hide", gx - zb * 2f - u * 1.2f, ui.h - u * 1.5f - zb, zb * 2f, zb)) { settings.gpsCollapsed = true; settings.save() }
            if (ui.button("+", gx - zb - u * 1.2f, ui.h - u * 2.5f - zb * 2, zb, zb)) gps.zoom = (gps.zoom / 1.6f).coerceAtLeast(150f)
            if (ui.button("-", gx - zb - u * 1.2f, ui.h - u * 3.5f - zb * 3, zb, zb)) gps.zoom = (gps.zoom * 1.6f).coerceAtMost(8000f)
        }
        dash.draw(ui, bike, settings.assists, if (game.platform.isTouchDevice) ui.w / 2 - u * 6f else ui.w / 2, u * 1.2f)
        controls.draw(ui)

        // Buttons along the top left.
        val bw = u * 8.5f; val bh = u * 4.2f
        var bx = u * 1.5f; val by = ui.h - bh - u * 1.5f
        if (ui.button("Menu", bx, by, bw, bh)) paused = !paused
        bx += bw + u
        if (ui.button(chase.mode.label, bx, by, bw, bh)) cycleCamera()
        bx += bw + u
        if (ui.button("Stand", bx, by, bw, bh, active = controls.standToggle)) controls.standToggle = !controls.standToggle
        bx += bw + u
        if (ui.button("Auto", bx, by, bw, bh, active = autoRide)) autoRide = !autoRide
        if (!settings.assists.autoShift) {
            val sx = ui.w - u * 8f
            if (ui.button("Gear +", sx, ui.h * 0.48f + u * 9f, u * 6.5f, u * 4.2f)) controls.input.shiftUp = true
            if (ui.button("Gear -", sx - u * 7.7f, ui.h * 0.48f + u * 9f, u * 6.5f, u * 4.2f)) controls.input.shiftDown = true
        }

        // Messages.
        bannerTimer -= dt
        val msgW = ui.w - gw - u * 12f
        var my = ui.h - u * 8.5f
        if (autoRide) { ui.text("AUTO-RIDE demo: press Auto to take over", u * 1.5f, my, Ui2.AUTO, Align.left, ui.font, msgW); my -= u * 3f }
        if (bannerTimer > 0) banner?.let { ui.text(it, 0f, ui.h * 0.66f, Color.WHITE, Align.center, ui.big, ui.w) }
        notesTimer -= dt
        if (notesTimer > 0) {
            for (n in world.notes) {
                val th = ui.textHeight(n, msgW - u * 1.4f) + u * 1.4f
                ui.rect(u * 1.5f, my - th, msgW, th, Ui.PANEL)
                ui.text(n, u * 2.2f, my - u * 0.6f, Color(1f, 0.85f, 0.5f, 1f), Align.left, ui.font, msgW - u * 1.4f, true)
                my -= th + u * 0.6f
            }
        }
        if (paused) drawPause()
    }

    private fun drawPause() {
        val ui = game.ui
        val u = ui.u
        ui.rect(0f, 0f, ui.w, ui.h, Color(0f, 0f, 0f, 0.55f))
        val bw = u * 22f; val bh = u * 5f; val x = (ui.w - bw) / 2
        var y = ui.h * 0.66f
        ui.text("Paused", 0f, y + u * 6, Color.WHITE, Align.center, ui.big, ui.w)
        if (ui.button("Resume", x, y, bw, bh)) paused = false
        y -= bh + u
        // Same as after a fall: back on the route a little behind where you are.
        if (ui.button("Reset position", x, y, bw, bh)) { respawn(); paused = false }
        y -= bh + u
        if (ui.button("Back to start of route", x, y, bw, bh)) { bike.reset(world.spawnX, world.spawnY, world.spawnHeading); chase.snap(); paused = false }
        y -= bh + u
        if (ui.button("Main menu", x, y, bw, bh)) game.toMenu()
        y -= bh + u * 2
        ui.text("Keys: W/S throttle/front brake, A/D lean, Space rear brake (hold at a stop to reverse), F stand, Q/E gears, C camera, M map, P auto-ride, Backspace reset",
            u * 4, y, Color.LIGHT_GRAY, Align.center, ui.font, ui.w - u * 8, true)
    }

    override fun dispose() {
        chunks.dispose(); shader.dispose(); bikeRenderer.dispose(); audio.dispose()
    }

    private object Ui2 { val AUTO = Color(0.6f, 0.9f, 1f, 1f) }
}
