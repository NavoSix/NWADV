package com.northwoodsadv.ride

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences
import com.northwoodsadv.ride.physics.Assists
import com.northwoodsadv.ride.physics.Difficulty
import com.northwoodsadv.ride.physics.SteerMode
import com.northwoodsadv.ride.render.CameraMode
import com.northwoodsadv.ride.render.Season

/** Player preferences, persisted with libGDX Preferences. */
class Settings {
    private val prefs: Preferences = Gdx.app.getPreferences("nwadv")

    var imperial = prefs.getBoolean("imperial", true)
    var difficulty = runCatching { Difficulty.valueOf(prefs.getString("difficulty", Difficulty.EASY.name)) }.getOrDefault(Difficulty.EASY)
        set(v) { field = v; assists.applyDifficulty(v) }
    var gpsCollapsed = prefs.getBoolean("gpsCollapsed", false)
    var season = runCatching { Season.valueOf(prefs.getString("season", Season.SUMMER.name)) }.getOrDefault(Season.SUMMER)
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
