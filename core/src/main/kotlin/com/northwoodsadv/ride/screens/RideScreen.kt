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
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.render.BikeRenderer
import com.northwoodsadv.ride.render.CameraMode
import com.northwoodsadv.ride.render.ChaseCamera
import com.northwoodsadv.ride.render.ChunkBuilder
import com.northwoodsadv.ride.render.ChunkManager
import com.northwoodsadv.ride.render.Palette
import com.northwoodsadv.ride.render.WakeFx
import com.northwoodsadv.ride.render.WorldShader
import com.northwoodsadv.ride.route.RouteTracker
import com.northwoodsadv.ride.ui.Ui
import com.northwoodsadv.ride.world.Canopy
import com.northwoodsadv.ride.world.Landmark
import com.northwoodsadv.ride.world.World
import kotlin.math.abs
import kotlin.math.floor

class RideScreen(private val game: NwadvGame, private val world: World) : ScreenAdapter() {
    private val settings = game.settings
    private val model = com.northwoodsadv.ride.bikes.Bikes.byId(settings.bikeId)
    private val spec = model.spec
    private val bike = Motorcycle(spec, world.ground)
    private val tracker = RouteTracker(world.route).apply { nameTurns(world.ground.scenery.junctions) }
    private val autopilot = Autopilot(world.route, world.ground)
    private var autoRide = game.autoplay
    private val controls = RideControls(settings, game.platform.isTouchDevice)
    private val shader = WorldShader.create()
    private val baseH = world.ground.height(world.spawnX, world.spawnY, false)
    private val chunks = ChunkManager(ChunkBuilder(world.ground, baseH, settings.season), if (Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.Desktop) 3 else 2)
    private val bikeRenderer = BikeRenderer(spec, model.look(com.northwoodsadv.ride.render.Detail.RIDE, settings.activePaint(model)))
    // In the helmet view the moving map is shown on the bike's own phone / GPS screen.
    private val navTex: com.badlogic.gdx.graphics.glutils.FrameBuffer? = bikeRenderer.navScreen?.let { ns ->
        val long = 512f
        val (fw, fh) = if (ns.width >= ns.height) long to long * ns.height / ns.width else long * ns.width / ns.height to long
        com.badlogic.gdx.graphics.glutils.FrameBuffer(com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, fw.toInt(), fh.toInt(), false).apply {
            colorBufferTexture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear)
        }
    }
    private val navRegion = navTex?.let { com.badlogic.gdx.graphics.g2d.TextureRegion(it.colorBufferTexture).apply { flip(false, true) } }
    private val navBatch = com.badlogic.gdx.graphics.g2d.SpriteBatch(8)
    private val navM = Matrix4()
    private val gpsOnBike get() = navTex != null && chase.mode == CameraMode.HELMET
    private val cam = PerspectiveCamera(62f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
    private val chase = ChaseCamera(cam).apply { mode = settings.camera; eyeSeated.set(bikeRenderer.eyeSeated); eyeStanding.set(bikeRenderer.eyeStanding)
        lookDownSeated = bikeRenderer.lookDownSeated; lookDownStanding = bikeRenderer.lookDownStanding }
    private val gps = GpsUnit(world, tracker, settings)
    private val dash = Dashboard(settings)
    private val bikeM = Matrix4()
    private val audio = com.northwoodsadv.ride.audio.RideAudio().apply { engine = model.sound }
    private var originE = 0.0; private var originN = 0.0

    private var paused = false
    /** Development: hide the HUD for clean screenshots (desktop --no-hud). */
    private val noHud = System.getProperty("nwadv.noHud") != null
    private var crashTimer = 0.0
    private var safeTimer = 0.0
    private var safeX = world.spawnX; private var safeY = world.spawnY; private var safeHeading = world.spawnHeading
    /** The landmark being passed, shown for a few seconds, and the ones already announced. */
    private var landmark: Landmark? = null
    private var landmarkTimer = 0.0
    private val announced = HashSet<Landmark>()
    private val achievements = com.northwoodsadv.ride.progress.AchievementTracker(settings, model.id) // only this bike's achievements
    /** Newly unlocked achievements, shown one at a time. */
    private val unlockedQueue = ArrayDeque<com.northwoodsadv.ride.progress.Achievement>()
    private var unlockTimer = 0.0
    /** Started from a saved spot rather than the start of the route. */
    private var resumed = false
    private var banner: String? = null
    private var bannerTimer = 0.0
    private var notesTimer = if (world.notes.isEmpty()) 0.0 else 9.0
    private var wasCrashed = false
    private val sky = Palette.rgb(Palette.SKY)
    private val haze = Palette.rgb(Palette.HAZE)
    private val canopy = Palette.rgb(Palette.CANOPY)
    private val skyShader = WorldShader.createSky()
    private val skyQuad = com.badlogic.gdx.graphics.Mesh(true, 4, 6, com.badlogic.gdx.graphics.VertexAttribute.Position()).apply {
        setVertices(floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f))
        setIndices(shortArrayOf(0, 1, 2, 0, 2, 3))
    }
    private val wake = WakeFx(world.ground)
    private val lightSample = com.northwoodsadv.ride.world.GroundSample()
    private var bikeLight = 1f
    private var time = 0f

    init {
        bike.reset(world.spawnX, world.spawnY, world.spawnHeading)
        // Development: start part-way along the route (desktop --start-at <metres>).
        System.getProperty("nwadv.startAt")?.toDoubleOrNull()?.let { at ->
            val p = DoubleArray(3); world.route.pointAt(at, p); bike.reset(p[0], p[1], p[2])
        }
        // ... or along a road: "<metres>" on the first road, or "<road>:<metres>".
        System.getProperty("nwadv.startRoadAt")?.split(":")?.let { parts ->
            val road = if (parts.size > 1) parts[0].toIntOrNull() ?: 0 else 0
            val at = parts.last().toDoubleOrNull() ?: return@let
            val p = DoubleArray(3); world.roads[road.coerceIn(0, world.roads.size - 1)].line.pointAt(at, p); bike.reset(p[0], p[1], p[2])
        }
        // Otherwise pick up where the rider left off on this route last time.
        if (System.getProperty("nwadv.startAt") == null && System.getProperty("nwadv.startRoadAt") == null) resumeSaved()
        // ... turned by some degrees (to look at the roadside).
        System.getProperty("nwadv.startTurn")?.toDoubleOrNull()?.let { bike.reset(bike.x, bike.y, bike.yaw + Math.toRadians(it)) }
        rebase(true)
        banner = if (resumed) "${world.name}\nPicking up where you left off" else world.name; bannerTimer = 4.0
        // Development: screenshots without the route title and load notes.
        if (System.getProperty("nwadv.cleanHud") != null) { bannerTimer = 0.0; notesTimer = 0.0 }
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
            checkLandmarks(dt.toDouble())
            handleCrash(dt.toDouble())
            trackAchievements(dt.toDouble())
            saveTimer += dt
            if (saveTimer > 15) saveRide()
        }

        audio.rpm = bike.rpm.toFloat()
        audio.throttle = controls.input.throttle.toFloat()
        audio.speed = bike.speed.toFloat()
        audio.slip = maxOf(bike.slipF, bike.slipR).toFloat()
        audio.surface = com.northwoodsadv.ride.audio.SurfaceSound.of(bike.surfaceF, bike.airborne)
        audio.muted = paused

        // --- 3D -----------------------------------------------------------------------------
        rebase(false)
        bikeRenderer.chassisTransform(bike, originE, originN, baseH, bikeM)
        chase.update(dt, bike, world.ground, originE, originN, baseH, bikeM)
        chunks.update(chase.worldE, chase.worldN, 4500.0)

        if (gpsOnBike) drawNavTexture(dt)
        Gdx.gl.glClearColor(sky[0], sky[1], sky[2], 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        time += dt
        if (!paused) wake.update(dt.toDouble(), bike)
        // Shade under the canopy, eased so riding into a tunnel of trees darkens gradually.
        world.ground.sample(bike.x, bike.y, lightSample, false)
        val lightHere = if (lightSample.road >= 0) Canopy.light(world.ground, world.ground.roads[lightSample.road], lightSample.along, lightSample.lateral, bike.x, bike.y) else 1.0
        bikeLight += (lightHere.toFloat() - bikeLight) * (1 - kotlin.math.exp(-dt * 3f))

        // Sky first, behind everything.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        skyShader.bind()
        skyShader.setUniformMatrix("u_invProjView", cam.invProjectionView)
        skyShader.setUniformf("u_sunDir", SUN[0], SUN[1], SUN[2])
        skyShader.setUniformf("u_fogColor", haze[0], haze[1], haze[2])
        skyShader.setUniformf("u_skyTop", sky[0], sky[1], sky[2])
        skyShader.setUniformf("u_time", time)
        skyQuad.render(skyShader, GL20.GL_TRIANGLES)

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL)
        Gdx.gl.glDisable(GL20.GL_BLEND)
        shader.bind()
        shader.setUniformMatrix("u_projView", cam.combined)
        shader.setUniformf("u_useModel", 0f)
        shader.setUniformf("u_sunDir", SUN[0], SUN[1], SUN[2])
        shader.setUniformf("u_camPos", cam.position)
        shader.setUniformf("u_fogColor", haze[0], haze[1], haze[2])
        shader.setUniformf("u_skyTop", sky[0], sky[1], sky[2])
        shader.setUniformf("u_fogDensity", 1f / 2600f)
        shader.setUniformf("u_canopy", canopy[0], canopy[1], canopy[2])
        shader.setUniformf("u_canopyRange", 150f, 330f)
        shader.setUniformf("u_detail", 1f)
        shader.setUniformf("u_time", time)
        shader.setUniformf("u_light", 1f)
        chunks.render(shader, cam, originE, originN)
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        shader.setUniformf("u_light", bikeLight)
        bikeRenderer.render(shader, bike, originE, originN, baseH, world.ground.height(bike.x, bike.y))
        shader.setUniformf("u_light", 1f)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        if (gpsOnBike) drawNavScreen()
        chunks.renderWater(shader, cam, originE, originN)
        wake.render(shader, originE, originN, baseH)
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        // --- HUD ----------------------------------------------------------------------------
        if (!noHud) drawHud(dt)
        ui.endFrame()
    }

    // --- saving where the rider left off ---------------------------------------------------

    private var saveTimer = 0.0

    private fun resumeSaved() {
        val key = game.routeKey ?: return
        val save = settings.rideSave(key) ?: return
        if (save.routeHash != game.routeHash) return // the file changed: start afresh
        val x = world.proj.east(save.lon); val y = world.proj.north(save.lat)
        if (world.ground.scenery.solidAt(x, y, 1.5) != null) return
        bike.reset(x, y, save.heading)
        bike.odometer = save.odometer
        tracker.resumeAt(save.along)
        safeX = x; safeY = y; safeHeading = save.heading
        resumed = true
    }

    /** Remember this spot on the route: the last place the bike was upright and moving. */
    private fun saveRide() {
        val key = game.routeKey ?: return
        if (tracker.finished) { settings.setRideSave(key, null); return }
        settings.setRideSave(key, com.northwoodsadv.ride.RideSave(world.proj.lat(safeY), world.proj.lon(safeX), safeHeading,
            tracker.along, bike.odometer, game.routeHash))
        saveTimer = 0.0
    }

    private fun clearRideSave() { game.routeKey?.let { settings.setRideSave(it, null) } }

    private fun trackAchievements(dt: Double) {
        if (autoRide || bike.crash != null) { achievements.interrupt(); return }
        val gs = bike.sampleF
        val classVI = gs.road >= 0 && gs.roadBlend > 0.5 && world.roads[gs.road].spec.isClassVI
        unlockedQueue.addAll(achievements.update(dt, bike.speed, bike.waterDepth > 0.03, classVI, game.ownRoute))
    }

    private fun checkLandmarks(dt: Double) {
        landmarkTimer -= dt
        if (landmarkTimer > 0) return
        for (l in world.ground.scenery.landmarks) {
            if (l.kind == "Peak" || l in announced) continue
            val reach = if (l.kind == "Scenic view") 160.0 else 110.0
            if ((l.x - bike.x) * (l.x - bike.x) + (l.y - bike.y) * (l.y - bike.y) < reach * reach) {
                announced += l; landmark = l; landmarkTimer = 5.0
                break
            }
        }
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
        val solids = world.ground.scenery
        if (tracker.offRoute < 60) {
            // A few metres back along the route, further if that's inside something solid.
            var at = (tracker.along - 12).coerceAtLeast(0.0)
            world.route.pointAt(at, p)
            while (solids.solidAt(p[0], p[1], 1.5) != null && at > 0 && tracker.along - at < 150) { at = (at - 6).coerceAtLeast(0.0); world.route.pointAt(at, p) }
            val ahead = DoubleArray(3); world.route.pointAt(at + 6, ahead)
            bike.reset(p[0], p[1], kotlin.math.atan2(ahead[1] - p[1], ahead[0] - p[0]))
        } else if (solids.solidAt(safeX, safeY, 1.5) == null) bike.reset(safeX, safeY, safeHeading)
        else {
            world.route.project(bike.x, bike.y, 0.0, Double.MAX_VALUE, p)
            val s0 = p[0]; world.route.pointAt(s0, p)
            val ahead = world.route.pointAt(s0 + 6, DoubleArray(3))
            bike.reset(p[0], p[1], kotlin.math.atan2(ahead[1] - p[1], ahead[0] - p[0]))
        }
        chase.snap()
    }

    private fun cycleCamera() {
        val all = CameraMode.entries
        chase.mode = all[(all.indexOf(chase.mode) + 1) % all.size]
        settings.camera = chase.mode; settings.save()
    }

    /** Draw the moving map into the bike's nav-screen texture, laid out for that screen's size. */
    private fun drawNavTexture(dt: Float) {
        val fb = navTex ?: return
        val ui = game.ui
        val fw = fb.width.toFloat(); val fh = fb.height.toFloat()
        fb.begin()
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.07f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // Big type for a small screen seen from the saddle: ~12 text lines on the short side.
        val tu = minOf(fw, fh) / 15f
        ui.pushTarget(fw, fh, tu)
        gps.draw(ui, bike, tu * 0.5f, tu * 0.5f, fw - tu, fh - tu, dt, compact = true)
        ui.popTarget()
        fb.end()
    }

    /** The map on the phone / GPS, lit by its own backlight, in the 3D scene. */
    private fun drawNavScreen() {
        val region = navRegion ?: return
        val ns = bikeRenderer.navScreen ?: return
        bikeRenderer.navTransform(navM) ?: return
        navBatch.projectionMatrix = cam.combined
        navBatch.transformMatrix = navM
        navBatch.begin()
        navBatch.draw(region, 0f, 0f, ns.width, ns.height)
        navBatch.end()
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glDisable(GL20.GL_BLEND)
        shader.bind()
    }

    private fun drawHud(dt: Float) {
        val ui = game.ui
        val u = ui.u
        ui.begin()
        // GPS on the "handlebar" (top right), collapsible.
        val gw = ui.w * 0.31f; val gh = ui.h * 0.50f
        val gx = ui.w - gw - u * 1.5f
        val zb = u * 4.2f
        if (gpsOnBike) {
            // The map is on the bike's own screen; keep just the zoom buttons.
            if (ui.button("+", ui.w - zb - u * 1.5f, ui.h - u * 1.5f - zb, zb, zb)) gps.zoom = (gps.zoom / 1.6f).coerceAtLeast(150f)
            if (ui.button("-", ui.w - zb - u * 1.5f, ui.h - u * 2.5f - zb * 2, zb, zb)) gps.zoom = (gps.zoom * 1.6f).coerceAtMost(8000f)
        } else if (settings.gpsCollapsed) {
            val cw = ui.w * 0.26f
            gps.drawCompact(ui, bike, ui.w - cw - u * 1.5f, ui.h - u * 1.5f, cw, dt)
            if (ui.button("Map", ui.w - cw - u * 2.7f - zb * 2f, ui.h - u * 1.5f - zb, zb * 2f, zb)) { settings.gpsCollapsed = false; settings.save() }
        } else {
            gps.draw(ui, bike, gx, ui.h - gh - u * 1.5f, gw, gh, dt)
            if (ui.button("Hide", gx - zb * 2f - u * 1.2f, ui.h - u * 1.5f - zb, zb * 2f, zb)) { settings.gpsCollapsed = true; settings.save() }
            if (ui.button("+", gx - zb - u * 1.2f, ui.h - u * 2.5f - zb * 2, zb, zb)) gps.zoom = (gps.zoom / 1.6f).coerceAtLeast(150f)
            if (ui.button("-", gx - zb - u * 1.2f, ui.h - u * 3.5f - zb * 3, zb, zb)) gps.zoom = (gps.zoom * 1.6f).coerceAtMost(8000f)
        }
        // In the helmet view the cockpit is at the bottom of the screen, so the readout moves up.
        if (gpsOnBike) dash.draw(ui, bike, settings.assists, ui.w - u * 27.5f, ui.h - u * 10.7f)
        else dash.draw(ui, bike, settings.assists, if (game.platform.isTouchDevice) ui.w / 2 - u * 6f else ui.w / 2, u * 1.2f)
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
        if (autoRide && System.getProperty("nwadv.cleanHud") == null) { ui.text("AUTO-RIDE demo: press Auto to take over", u * 1.5f, my, Ui2.AUTO, Align.left, ui.font, msgW); my -= u * 3f }
        if (bannerTimer > 0) banner?.let { ui.text(it, 0f, ui.h * 0.66f, Color.WHITE, Align.center, ui.big, ui.w) }
        val lm = landmark
        if (landmarkTimer > 0 && lm != null) {
            // A plaque-style card: what it is, and its name.
            val a = minOf(1f, landmarkTimer.toFloat() / 0.6f, (5f - landmarkTimer.toFloat()) / 0.4f).coerceIn(0f, 1f)
            val cw = minOf(ui.w * 0.5f, u * 46f); val ch = u * 6.4f
            val cx = (ui.w - cw) / 2; val cy = ui.h * 0.27f
            ui.rect(cx, cy, cw, ch, Color(0.12f, 0.24f, 0.16f, 0.85f * a))
            ui.rect(cx + u * 0.35f, cy + u * 0.35f, cw - u * 0.7f, ch - u * 0.7f, Color(0.85f, 0.8f, 0.6f, 0.25f * a))
            ui.text(lm.kind.uppercase(), cx, cy + ch - u * 0.9f, Color(0.9f, 0.85f, 0.6f, a), Align.center, ui.font, cw)
            ui.text(lm.name, cx, cy + ch - u * 3.1f, Color(1f, 1f, 1f, a), Align.center, ui.font, cw)
        }
        notesTimer -= dt
        if (notesTimer > 0) {
            for (n in world.notes) {
                val th = ui.textHeight(n, msgW - u * 1.4f) + u * 1.4f
                ui.rect(u * 1.5f, my - th, msgW, th, Ui.PANEL)
                ui.text(n, u * 2.2f, my - u * 0.6f, Color(1f, 0.85f, 0.5f, 1f), Align.left, ui.font, msgW - u * 1.4f, true)
                my -= th + u * 0.6f
            }
        }
        // Achievement unlocked: a small card at the top, one at a time.
        if (unlockTimer <= 0 && unlockedQueue.isNotEmpty()) { unlockTimer = 6.0; game.platform.vibrate(80) }
        val ach = unlockedQueue.firstOrNull()
        if (ach != null && unlockTimer > 0) {
            unlockTimer -= dt
            val a = minOf(1f, unlockTimer.toFloat() / 0.5f, (6f - unlockTimer.toFloat()) / 0.4f).coerceIn(0f, 1f)
            val body = "${ach.story}. ${ach.paintName} paint unlocked."
            val cw = minOf(ui.w * 0.5f, u * 46f)
            val ch = u * 3.6f + ui.textHeight(body, cw - u * 2) + u * 1.2f
            val cx = (ui.w - cw) / 2; val cy = ui.h - ch - u * 6.5f
            ui.rect(cx, cy, cw, ch, Color(0.08f, 0.08f, 0.1f, 0.88f * a))
            ui.rect(cx, cy + ch - u * 0.45f, cw, u * 0.45f, Color(0.95f, 0.72f, 0.2f, a))
            ui.text("Achievement unlocked: ${ach.title}", cx, cy + ch - u * 1.2f, Color(0.95f, 0.72f, 0.2f, a), Align.center, ui.font, cw)
            ui.text(body, cx + u, cy + ch - u * 3.6f, Color(0.88f, 0.88f, 0.88f, a), Align.center, ui.font, cw - u * 2, true)
            if (unlockTimer <= 0) unlockedQueue.removeFirst()
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
        if (ui.button("Back to start of route", x, y, bw, bh)) {
            bike.reset(world.spawnX, world.spawnY, world.spawnHeading); tracker.resumeAt(0.0); chase.snap(); paused = false
            safeX = world.spawnX; safeY = world.spawnY; safeHeading = world.spawnHeading
            clearRideSave()
        }
        y -= bh + u
        if (ui.button("Main menu", x, y, bw, bh)) game.toMenu()
        y -= bh + u * 2
        ui.text("Keys: W/S throttle/front brake, A/D lean, Space rear brake (hold at a stop to reverse), F stand, Q/E gears, C camera, M map, P auto-ride, Backspace reset",
            u * 4, y, Color.LIGHT_GRAY, Align.center, ui.font, ui.w - u * 8, true)
    }

    override fun pause() { achievements.save(); saveRide() }

    override fun dispose() {
        achievements.save()
        saveRide()
        chunks.dispose(); shader.dispose(); bikeRenderer.dispose(); audio.dispose()
        skyShader.dispose(); skyQuad.dispose(); wake.dispose()
        navTex?.dispose(); navBatch.dispose()
    }

    private object Ui2 { val AUTO = Color(0.6f, 0.9f, 1f, 1f) }

    private companion object {
        /** Mid-morning sun from the south-east (render space: x east, y up, z south). */
        val SUN = floatArrayOf(-0.45f, 0.75f, 0.48f)
    }
}
