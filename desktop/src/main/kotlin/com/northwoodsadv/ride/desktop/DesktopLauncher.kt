package com.northwoodsadv.ride.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import com.northwoodsadv.ride.NwadvGame
import com.northwoodsadv.ride.Platform
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File

class DesktopPlatform(touch: Boolean = false) : Platform {
    override val mapCacheDir = File(System.getProperty("user.home"), ".nwadv/cache").apply { mkdirs() }
    // In the home folder, so it works wherever the game is installed (Program Files isn't writable).
    override val savedRoutesDir = File(System.getProperty("user.home"), ".nwadv/saved-routes").apply { mkdirs() }
    override val isTouchDevice = touch

    override fun pickGpxFile(onResult: (String?, String?) -> Unit) {
        Thread {
            val path = try {
                val stack = org.lwjgl.system.MemoryStack.stackPush()
                stack.use {
                    val filters = it.mallocPointer(1)
                    filters.put(it.UTF8("*.gpx")); filters.flip()
                    TinyFileDialogs.tinyfd_openFileDialog("Open a GPX route", System.getProperty("user.home") + "/", filters, "GPX files", false)
                }
            } catch (e: Throwable) { null }
            val f = path?.let(::File)
            val text = f?.takeIf { it.isFile }?.readText()
            Gdx.app.postRunnable { onResult(f?.name, text) }
        }.start()
    }
}

/**
 * Usage: desktop [route.gpx] [--auto] [--size 1600x900] [--shots out-dir --shot-times 5,10,20]
 * The screenshot options are for automated visual checks. Development extras: --no-hud,
 * --start-at <route m>, --start-road-at [<road>:]<m>, --dev-streams <route m,...>,
 * --bike <id>, --paint <id>, --unlock-all, --garage, --osm-file <saved Overpass JSON>, --dump-scenery,
 * --start-turn <degrees>, --clean-hud (no route title or load notes).
 */
fun main(args: Array<String>) {
    var gpx: String? = null
    var auto = false
    var w = 1280; var h = 720
    var shotsDir: String? = null
    var shotTimes = listOf<Float>()
    var camera: String? = null
    var touch = false
    var gpsCollapsed: Boolean? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--auto" -> auto = true
            "--size" -> { val p = args[++i].split("x"); w = p[0].toInt(); h = p[1].toInt() }
            "--shots" -> shotsDir = args[++i]
            "--shot-times" -> shotTimes = args[++i].split(",").map { it.toFloat() }
            "--camera" -> camera = args[++i]
            "--touch" -> touch = true
            "--gps-collapsed" -> gpsCollapsed = true
            "--start-at" -> System.setProperty("nwadv.startAt", args[++i])
            "--start-road-at" -> System.setProperty("nwadv.startRoadAt", args[++i])
            "--no-hud" -> System.setProperty("nwadv.noHud", "1")
            "--clean-hud" -> System.setProperty("nwadv.cleanHud", "1")
            "--dev-streams" -> System.setProperty("nwadv.devStreams", args[++i])
            "--bike" -> System.setProperty("nwadv.bike", args[++i])
            "--paint" -> System.setProperty("nwadv.paint", args[++i])
            "--unlock-all" -> System.setProperty("nwadv.unlockAll", "1")
            "--garage" -> System.setProperty("nwadv.garage", "1")
            "--start-turn" -> System.setProperty("nwadv.startTurn", args[++i])
            "--dump-scenery" -> System.setProperty("nwadv.dumpScenery", "1")
            "--osm-file" -> System.setProperty("nwadv.osmFile", File(args[++i]).absolutePath)
            "--garage-angle" -> System.setProperty("nwadv.garageAngle", args[++i])
            "--garage-cam" -> System.setProperty("nwadv.garageCam", args[++i])
            else -> gpx = File(args[i]).absolutePath
        }
        i++
    }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Northwoods ADV")
        setWindowedMode(w, h)
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 24, 0, 4)
    }
    val game = object : NwadvGame(DesktopPlatform(touch), gpx, auto) {
        private var t = 0f
        private var shot = 0
        override fun create() {
            super.create()
            camera?.let { settings.camera = com.northwoodsadv.ride.render.CameraMode.valueOf(it) }
            gpsCollapsed?.let { settings.gpsCollapsed = it }
        }
        override fun render() {
            super.render()
            val dir = shotsDir ?: return
            t += Gdx.graphics.deltaTime
            if (shot < shotTimes.size && t >= shotTimes[shot]) {
                val pm: Pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
                val flipped = Pixmap(pm.width, pm.height, pm.format)
                for (y in 0 until pm.height) flipped.drawPixmap(pm, 0, y, pm.width, 1, 0, pm.height - 1 - y, pm.width, 1)
                PixmapIO.writePNG(Gdx.files.absolute("$dir/shot_${shot}.png"), flipped)
                pm.dispose(); flipped.dispose()
                println("screenshot ${shot} at t=$t")
                shot++
                if (shot == shotTimes.size) Gdx.app.exit()
            }
            @Suppress("unused") val keep = ScreenUtils::class
        }
    }
    Lwjgl3Application(game, config)
}
