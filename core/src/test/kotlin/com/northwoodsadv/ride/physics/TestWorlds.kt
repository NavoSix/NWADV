package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.Elevation
import com.northwoodsadv.ride.terrain.FlatElevation
import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.Road
import com.northwoodsadv.ride.world.Surface

object TestWorlds {
    /** A huge flat pad of one surface centred on the origin (a 400 m wide "road" along x). */
    fun pad(surface: Surface, kind: RoadKind = RoadKind.HIGHWAY, roughness: Double = 0.0, elevation: Elevation = FlatElevation(100.0)): Ground {
        val line = Polyline(doubleArrayOf(-3000.0, 3000.0), doubleArrayOf(0.0, 0.0))
        val road = Road(1, RoadSpec(kind, surface, 400.0, roughness, "Test pad"), line, elevation)
        return Ground(elevation, listOf(road))
    }

    /** Plane rising along +x with the given grade. */
    class Slope(private val grade: Double, private val base: Double = 100.0) : Elevation {
        override fun height(e: Double, n: Double) = base + grade * e
    }
}

/** Simple speed/lean holding rider for tests. */
class TestRider(private val bike: Motorcycle, private val assists: Assists = Assists()) {
    val input = RiderInput()
    var time = 0.0

    fun run(seconds: Double, dt: Double = 1 / 60.0, control: (RiderInput) -> Unit = {}) {
        var t = 0.0
        while (t < seconds && bike.crash == null) {
            control(input)
            bike.update(dt, input, assists)
            t += dt; time += dt
        }
    }

    /** Steer (via lean requests) to keep a heading, like a rider picking a line up the trail. */
    fun holdHeading(heading: Double, i: RiderInput) {
        var err = heading - bike.yaw
        while (err > Math.PI) err -= 2 * Math.PI
        while (err < -Math.PI) err += 2 * Math.PI
        val rDes = (err * 0.8).coerceIn(-0.6, 0.6)
        val leanDes = kotlin.math.atan(bike.forwardSpeed * rDes / 9.81)
        i.steer = (-leanDes / Math.toRadians(52.0)).coerceIn(-0.5, 0.5)
    }

    fun holdSpeed(target: Double, i: RiderInput) {
        val err = target - bike.forwardSpeed
        i.throttle = (0.15 + err * 0.25).coerceIn(0.0, 1.0)
        i.frontBrake = if (err < -2) ((-err - 2) * 0.1).coerceIn(0.0, 0.5) else 0.0
        i.rearBrake = 0.0
    }
}
