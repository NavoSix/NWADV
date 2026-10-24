package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.world.Surface
import org.junit.Test

class DebugTrace {
    private fun line(r: TestRider, b: Motorcycle) = "t=%.2f x=%.2f u=%.2f y=%.2f yaw=%.3f r=%.3f lean=%.3f lr=%.3f steer=%.3f pitch=%.3f fzF=%.0f fzR=%.0f cF=%.3f cR=%.3f kF=%.2f kR=%.2f sF=%.2f sR=%.2f g=%d rpm=%.0f tc=%.2f".format(
        r.time, b.x, b.forwardSpeed, b.y, b.yaw, b.yawRate, b.lean, b.leanRate, b.steer, b.pitch, b.fzF, b.fzR, b.compF, b.compR, b.kappaF, b.kappaR, b.slipF, b.slipR, b.gear, b.rpm, b.tcFactor)

    private fun trace(name: String, phases: List<Pair<Double, (TestRider, Motorcycle, RiderInput) -> Unit>>, surface: Surface = Surface.ASPHALT,
                      ground: com.northwoodsadv.ride.world.Ground = TestWorlds.pad(surface)) {
        if (System.getenv("TRACE") != name) return
        val b = Motorcycle(BikeSpec(), ground)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b)
        var next = 0.0
        val fine = System.getenv("FINE")?.split(",")?.map { it.toDouble() }
        for ((dur, f) in phases) r.run(dur, 1 / 120.0) {
            f(r, b, it)
            if (fine != null && r.time in fine[0]..fine[1]) println(line(r, b) + " taF=%.3f taR=%.3f oth=%.0f".format(b.tanAlphaF, b.tanAlphaR, b.fOtherLatLast))
            else if (fine == null && r.time >= next) { next += 0.1; println(line(r, b)) }
        }
        println("crash=${b.crash}")
    }

    @Test fun launch() = trace("launch", listOf(8.0 to { _, _, i -> i.throttle = 1.0 }))
    @Test fun brake() = trace("brake", listOf(12.0 to { r, _, i -> r.holdSpeed(27.0, i) }, 6.0 to { _, _, i -> i.throttle = 0.0; i.frontBrake = 1.0; i.rearBrake = 1.0 }))
    @Test fun gbrake() = trace("gbrake", listOf(14.0 to { r, _, i -> r.holdSpeed(16.7, i) }, 6.0 to { _, b, i -> i.throttle = 0.0; i.frontBrake = 1.0; i.rearBrake = 1.0; if (b.forwardSpeed < 0.3) { i.frontBrake = 0.0; i.rearBrake = 0.0 } }), Surface.GRAVEL_LOOSE)
    @Test fun climb() = trace("climb", listOf(10.0 to { r, _, i -> r.holdSpeed(8.0, i) }), ground = TestWorlds.pad(Surface.DIRT, com.northwoodsadv.ride.osm.RoadKind.CLASS_VI, 0.0, TestWorlds.Slope(0.25)))
    @Test fun gturn() = trace("gturn", listOf(8.0 to { r, _, i -> r.holdSpeed(12.0, i) }, 6.0 to { r, _, i -> r.holdSpeed(12.0, i); i.steer = -20.0 / 52 }),
        ground = TestWorlds.pad(Surface.GRAVEL_PACKED, com.northwoodsadv.ride.osm.RoadKind.GRAVEL))
    @Test fun c6() = trace("c6", listOf(20.0 to { r, _, i -> r.holdSpeed(5.0, i); r.holdHeading(0.0, i); i.standing = true }),
        ground = TestWorlds.pad(Surface.DIRT, com.northwoodsadv.ride.osm.RoadKind.CLASS_VI, 0.75, TestWorlds.Slope(0.09)))
    @Test fun slow() = trace("slow", listOf(12.0 to { r, _, i -> r.holdSpeed(4.5, i); r.holdHeading(if (r.time > 6) 0.3 else 0.0, i) }),
        ground = TestWorlds.pad(Surface.DIRT, com.northwoodsadv.ride.osm.RoadKind.CLASS_VI, 0.0, TestWorlds.Slope(0.0)))
    @Test fun turn() = trace("turn", listOf(6.0 to { r, _, i -> r.holdSpeed(15.0, i) }, 6.0 to { r, _, i -> r.holdSpeed(15.0, i); i.steer = -30.0 / 52 }))
}

class DebugCased {
    @Test fun findCase() {
        if (System.getenv("TRACE") != "cased") return
        val g = TestWorlds.pad(Surface.ASPHALT)
        val b = Motorcycle(BikeSpec(), g)
        b.reset(0.0, 0.0, 0.0)
        val r = TestRider(b)
        r.run(6.0, 1 / 120.0) { r.holdSpeed(15.0, it) }
        var last = ""
        r.run(8.0, 1 / 120.0) { r.holdSpeed(15.0, it); it.steer = -30.0 / 52; last = "x=%.2f y=%.2f z=%.3f cF=%.3f cR=%.3f g=%.3f".format(b.x, b.y, b.z, b.compF, b.compR, g.height(b.x, b.y)) }
        println(last + " crash=" + b.crash)
        for (dx in -10..10) println("h(%.1f)=%.3f".format(b.x + dx * 0.2, g.height(b.x + dx * 0.2, b.y)))
    }
}

class DebugLoadVariation {
    @Test fun loads() {
        if (System.getenv("TRACE") != "loads") return
        for (surf in listOf(Surface.ASPHALT, Surface.GRAVEL_PACKED, Surface.GRAVEL_LOOSE, Surface.DIRT)) {
            val kind = if (surf.isPaved) com.northwoodsadv.ride.osm.RoadKind.HIGHWAY else com.northwoodsadv.ride.osm.RoadKind.GRAVEL
            val g = TestWorlds.pad(surf, kind)
            val b = Motorcycle(BikeSpec(), g)
            b.reset(0.0, 0.0, 0.0)
            val r = TestRider(b)
            r.run(8.0, 1 / 120.0) { r.holdSpeed(11.5, it) }
            val xs = ArrayList<Double>()
            r.run(3.0, 1 / 300.0) { r.holdSpeed(11.5, it); xs += b.fzF }
            val mean = xs.average(); val sd = Math.sqrt(xs.map { (it - mean) * (it - mean) }.average())
            val zero = xs.count { it < 100 } * 100.0 / xs.size
            val hs = (0 until 300).map { g.height(b.x + it * 0.01, 0.0) }
            println("%s: fzF mean=%.0f sd=%.0f  <100N %.1f%%  heights p-p over 3 m=%.4f".format(surf, mean, sd, zero, hs.max() - hs.min()))
        }
    }
}
