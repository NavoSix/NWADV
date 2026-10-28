package com.northwoodsadv.ride

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences
import com.northwoodsadv.ride.physics.Assists
import com.northwoodsadv.ride.physics.Difficulty
import com.northwoodsadv.ride.physics.SteerMode
import com.northwoodsadv.ride.render.CameraMode
import com.northwoodsadv.ride.render.Season

/** Player preferences (and achievement progress), persisted with libGDX Preferences. */
class Settings : com.northwoodsadv.ride.progress.ProgressStore {
    private val prefs: Preferences = Gdx.app.getPreferences("nwadv")

    var imperial = prefs.getBoolean("imperial", true)
    var difficulty = runCatching { Difficulty.valueOf(prefs.getString("difficulty", Difficulty.EASY.name)) }.getOrDefault(Difficulty.EASY)
        set(v) { field = v; assists.applyDifficulty(v) }
    var gpsCollapsed = prefs.getBoolean("gpsCollapsed", false)
    var season = runCatching { Season.valueOf(prefs.getString("season", Season.SUMMER.name)) }.getOrDefault(Season.SUMMER)
    /** Which bike from [com.northwoodsadv.ride.bikes.Bikes] to ride. */
    var bikeId = prefs.getString("bike", com.northwoodsadv.ride.bikes.Bikes.all.first().id)
    /** Chosen paint scheme for a bike (by bike id), or null for its first. */
    fun paint(bikeId: String): String? = prefs.getString("paint.$bikeId", null)
    fun setPaint(bikeId: String, paintId: String) { prefs.putString("paint.$bikeId", paintId); prefs.flush() }

    /** A paint of [bikeId] that's free or already earned on that bike. */
    fun paintUnlocked(bikeId: String, paintId: String?): Boolean =
        com.northwoodsadv.ride.progress.Achievement.forPaint(bikeId, paintId)?.let { isUnlocked(it) } ?: true

    /** The paint to show: the chosen one if it's unlocked, else the bike's first. */
    fun activePaint(bike: com.northwoodsadv.ride.bikes.BikeModel): String? =
        paint(bike.id)?.takeIf { p -> paintUnlocked(bike.id, p) && bike.paints.any { it.id == p } } ?: bike.paints.firstOrNull()?.id

    /**
     * Where the rider left off on a route (by route name): a [RideSave] line, or null. Kept in
     * its own preferences file so a long list of routes doesn't slow the settings.
     */
    fun rideSave(route: String): RideSave? = RideSave.parse(rides.getString(route, null))
    fun setRideSave(route: String, save: RideSave?) {
        if (save == null) rides.remove(route) else rides.putString(route, save.format())
        rides.flush()
    }
    private val rides: Preferences = Gdx.app.getPreferences("nwadv-rides")

    override fun number(key: String) = prefs.getString(key, "0").toDoubleOrNull() ?: 0.0
    override fun setNumber(key: String, v: Double) { prefs.putString(key, v.toString()) }
    override fun isUnlocked(a: com.northwoodsadv.ride.progress.Achievement) = prefs.getBoolean("unlocked.${a.id}", false)
    override fun unlock(a: com.northwoodsadv.ride.progress.Achievement) { prefs.putBoolean("unlocked.${a.id}", true) }
    override fun flush() { prefs.flush() }
    /** How fast the day goes by: 1 = real time. */
    var timeSpeed = prefs.getFloat("timeSpeed", 30f).toDouble()
    /** Time of day a ride starts at (hours), or -1 for the clock on the wall. */
    var startHour = prefs.getFloat("startHour", 9f).toDouble()
    var headlights = prefs.getBoolean("headlights", true)
    var camera = runCatching { CameraMode.valueOf(prefs.getString("camera", CameraMode.CHASE.name)) }.getOrDefault(CameraMode.CHASE)
    val assists = Assists(
        steerMode = runCatching { SteerMode.valueOf(prefs.getString("steerMode", SteerMode.LEAN.name)) }.getOrDefault(SteerMode.LEAN),
        absFront = prefs.getBoolean("absFront", true),
        absRear = prefs.getBoolean("absRear", true),
        tractionControl = prefs.getBoolean("tc", true),
        wheelieControl = prefs.getBoolean("wheelie", true),
        autoShift = prefs.getBoolean("autoShift", true),
        lowSpeedBalance = prefs.getBoolean("balance", true),
    ).apply { applyDifficulty(difficulty) }

    fun save() {
        prefs.putBoolean("imperial", imperial)
        prefs.putString("difficulty", difficulty.name)
        prefs.putBoolean("gpsCollapsed", gpsCollapsed)
        prefs.putString("season", season.name)
        prefs.putString("camera", camera.name)
        prefs.putFloat("timeSpeed", timeSpeed.toFloat())
        prefs.putFloat("startHour", startHour.toFloat())
        prefs.putBoolean("headlights", headlights)
        prefs.putString("bike", bikeId)
        prefs.putString("steerMode", assists.steerMode.name)
        prefs.putBoolean("absFront", assists.absFront)
        prefs.putBoolean("absRear", assists.absRear)
        prefs.putBoolean("tc", assists.tractionControl)
        prefs.putBoolean("wheelie", assists.wheelieControl)
        prefs.putBoolean("autoShift", assists.autoShift)
        prefs.putBoolean("balance", assists.lowSpeedBalance)
        prefs.flush()
    }

    // --- units -------------------------------------------------------------------------
    fun speed(ms: Double) = if (imperial) ms * 2.23694 else ms * 3.6
    val speedUnit get() = if (imperial) "mph" else "km/h"
    fun distance(m: Double): String = if (imperial) {
        val mi = m / 1609.344
        if (mi < 0.1) "%d ft".format((m * 3.28084).toInt() / 10 * 10) else "%.1f mi".format(mi)
    } else {
        if (m < 1000) "%d m".format(m.toInt() / 10 * 10) else "%.1f km".format(m / 1000)
    }
    fun elevation(m: Double): String = if (imperial) "%,d ft".format((m * 3.28084).toInt()) else "%,d m".format(m.toInt())
}
