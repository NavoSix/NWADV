package com.northwoodsadv.ride.world

import com.northwoodsadv.ride.osm.RoadKind

/**
 * Stretches where the woods close in over a road and the crowns meet overhead: old hardwoods
 * leaning out over narrow back roads and Class VI tracks. Purely visual (the trunks stay where
 * they always were), and a pure function of the road position like everything else.
 */
object Canopy {
    /** 0..1: how closed the canopy is over [road] at distance [s] along it. */
    fun tunnel(road: Road, s: Double): Double {
        val spec = road.spec
        if (spec.bridge || spec.width > 7.5) return 0.0
        val lo = when (spec.kind) {
            RoadKind.CLASS_VI, RoadKind.TRAIL -> 0.5
            RoadKind.SERVICE, RoadKind.GRAVEL -> 0.6
            RoadKind.MINOR -> 0.7
            else -> return 0.0
        }
        val v = Noise.value1(s / 110, road.seed + 70)
        // Fade in and out over the first and last stretch of a road (junctions are open).
        return Noise.smoothstep(lo, lo + 0.12, v) * Noise.smoothstep(10.0, 40.0, s) * Noise.smoothstep(10.0, 40.0, road.line.length - s)
    }

    /** Light left under the canopy at a point [lateral] m from the centreline (dappled). */
    fun light(ground: Ground, road: Road, s: Double, lateral: Double, e: Double, n: Double): Double {
        val t = tunnel(road, s)
        if (t <= 0) return 1.0
        val under = 1 - Noise.smoothstep(road.halfWidth + 3.0, road.halfWidth + 9.0, kotlin.math.abs(lateral))
        val forest = if (ground.landcover.at(e, n) == Landcover.FOREST || kotlin.math.abs(lateral) < road.halfWidth + 3) 1.0 else 0.4
        val dapple = 0.55 + 0.45 * Noise.value2(e * 0.45, n * 0.45, 404)
        return 1 - 0.5 * t * under * forest * (1.15 - dapple * 0.5)
    }
}
