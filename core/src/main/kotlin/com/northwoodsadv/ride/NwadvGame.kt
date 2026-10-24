package com.northwoodsadv.ride

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxFile
import com.northwoodsadv.ride.screens.LoadingScreen
import com.northwoodsadv.ride.screens.MenuScreen
import com.northwoodsadv.ride.ui.Ui

/** Northwoods ADV: ride your club's GPX routes on real New Hampshire terrain. */
open class NwadvGame(val platform: Platform, private val startGpx: String? = null, val autoplay: Boolean = false) : Game() {
    lateinit var ui: Ui; private set
    lateinit var settings: Settings; private set
    lateinit var cache: DiskCache; private set
    val routes by lazy { RouteLibrary(this) }

    override fun create() {
        settings = Settings()
        ui = Ui()
        Gdx.input.inputProcessor = ui
        cache = DiskCache(platform.mapCacheDir)
        ui.resize(Gdx.graphics.width, Gdx.graphics.height)
        val pending = platform.takePendingGpx()
        when {
            pending != null -> openGpx(pending.first, pending.second)
            startGpx != null -> openGpx(startGpx.substringAfterLast('/'), Gdx.files.absolute(startGpx).readString())
            else -> setScreen(MenuScreen(this))
        }
    }

    override fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        super.resize(width, height)
    }

    override fun resume() {
        super.resume()
        platform.takePendingGpx()?.let { openGpx(it.first, it.second) }
    }

    fun openGpx(name: String, contents: String) {
        val gpx: GpxFile = try {
            com.northwoodsadv.ride.geo.GpxParser.parse(contents, name.removeSuffix(".gpx"))
        } catch (e: Exception) {
            setScreen(MenuScreen(this, "Couldn't open $name: ${e.message}"))
            return
        }
        routes.remember(name, contents)
        startRide(gpx)
    }

    fun startRide(gpx: GpxFile) = setScreen(LoadingScreen(this, gpx))

    fun toMenu(message: String? = null) = setScreen(MenuScreen(this, message))

    override fun setScreen(screen: com.badlogic.gdx.Screen?) {
        val old = getScreen()
        super.setScreen(screen)
        old?.dispose()
    }

    override fun dispose() {
        getScreen()?.dispose()
        ui.dispose()
    }
}

/** Bundled sample routes plus GPX files the rider has opened before. */
class RouteLibrary(private val game: NwadvGame) {
    class Entry(val title: String, val load: () -> String, val bundled: Boolean)

    private val dir get() = Gdx.files.local("routes")

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        val saved = if (dir.exists()) dir.list(".gpx").sortedByDescending { it.lastModified() } else emptyList()
        for (f in saved) out += Entry(f.nameWithoutExtension(), { f.readString("UTF-8") }, false)
        for (n in BUNDLED) out += Entry(n.second, { Gdx.files.internal("routes/${n.first}").readString("UTF-8") }, true)
        return out
    }

    fun remember(name: String, contents: String) {
        if (BUNDLED.any { contents.length > 0 && it.first == name }) return
        val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").removeSuffix(".gpx").take(60)
        try { dir.child("$safe.gpx").writeString(contents, false, "UTF-8") } catch (_: Exception) {}
    }

    companion object {
        val BUNDLED = listOf(
            "kancamagus_approx.gpx" to "Sample: Kancamagus Highway (approx.)",
            "classvi_practice.gpx" to "Sample: Class VI practice trail",
        )
    }
}
