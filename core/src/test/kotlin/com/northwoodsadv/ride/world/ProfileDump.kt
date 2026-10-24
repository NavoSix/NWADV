package com.northwoodsadv.ride.world

import com.badlogic.gdx.utils.GdxNativesLoader
import com.northwoodsadv.ride.data.DiskCache
import com.northwoodsadv.ride.geo.GpxParser
import org.junit.Test
import java.io.File

class ProfileDump {
    @Test fun dump() {
        val routeName = System.getenv("NWADV_PROFILE") ?: return
        GdxNativesLoader.load()
        val gpx = GpxParser.parse(File("../assets/routes/$routeName").readText())
        val world = WorldBuilder(DiskCache(File("build/ride-cache"))).build(gpx)
        val road = world.roads[0]
        val gs = GroundSample()
        val tmp = DoubleArray(3)
        var s = 0.0
        val lim = (System.getenv("NWADV_PROFILE_LEN") ?: "200").toDouble()
        while (s < lim) {
            road.line.pointAt(s, tmp)
            world.ground.sample(tmp[0], tmp[1], gs)
            println("s=%5.0f dem=%7.2f road=%7.2f grade=%5.1f%% ground=%7.2f %s".format(s, world.ground.elevation.height(tmp[0], tmp[1]), road.heightAt(s), road.gradeAt(s) * 100, gs.height, gs.surface))
            s += 5
        }
        var maxG = 0.0; var n10 = 0; var n20 = 0; s = 0.0
        while (s < road.line.length) { val g = kotlin.math.abs(road.gradeAt(s)); maxG = maxOf(maxG, g); if (g > 0.1) n10++; if (g > 0.2) n20++; s += 10 }
        println("length=%.0f max grade=%.0f%% >10%%: %d0 m, >20%%: %d0 m".format(road.line.length, maxG * 100, n10, n20))
    }
}
