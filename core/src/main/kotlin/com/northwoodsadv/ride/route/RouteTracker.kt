package com.northwoodsadv.ride.route

import com.northwoodsadv.ride.geo.Polyline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** Turn-by-turn style tracking of the rider's progress along the GPX route. */
class RouteTracker(original: Polyline) {
    val route = original.resample(5.0)
    var along = 0.0; private set
    var offRoute = 0.0; private set
    var isOffRoute = false; private set
    var nextTurn: Turn? = null; private set
    val turns: List<Turn> = findTurns()
    private val tmp = DoubleArray(2)
    private var globalSearchTimer = 0.0

    class Turn(val along: Double, val angleDeg: Double) {
        val left get() = angleDeg > 0
        val label get() = when {
            abs(angleDeg) > 135 -> "U-turn"
            abs(angleDeg) > 70 -> if (left) "Left" else "Right"
            else -> if (left) "Bear left" else "Bear right"
        }
    }

    val remaining get() = (route.length - along).coerceAtLeast(0.0)
    val finished get() = remaining < 25 && !isOffRoute

    fun update(x: Double, y: Double, dt: Double) {
        route.project(x, y, along - 150, along + 400, tmp)
        globalSearchTimer -= dt
        if (tmp[1] > 80 && globalSearchTimer <= 0) {
            // Lost the route (shortcut, wrong turn): search all of it.
            globalSearchTimer = 2.0
            route.project(x, y, 0.0, Double.MAX_VALUE, tmp)
        }
        if (tmp[1] < 80 || tmp[1] < offRoute) along = tmp[0]
        offRoute = tmp[1]
        isOffRoute = offRoute > 40
        nextTurn = turns.firstOrNull { it.along > along + 3 }
    }

    private fun findTurns(): List<Turn> {
        val out = ArrayList<Turn>()
        val n = route.size
        if (n < 10) return out
        val heading = DoubleArray(n - 1) { atan2(route.ys[it + 1] - route.ys[it], route.xs[it + 1] - route.xs[it]) }
        fun diff(a: Double, b: Double): Double { var d = b - a; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return d }
        val w = 4 // 20 m either side
        var i = w
        while (i < heading.size - w) {
            val change = Math.toDegrees(diff(heading[i - w], heading[i + w]))
            if (abs(change) > 40) {
                // Take the sharpest point of this bend.
                var best = i; var bestC = abs(change); var j = i
                while (j < heading.size - w && abs(Math.toDegrees(diff(heading[j - w], heading[j + w]))) > 40) {
                    val c = abs(Math.toDegrees(diff(heading[j - w], heading[j + w])))
                    if (c > bestC) { bestC = c; best = j }
                    j++
                }
                out += Turn(route.cum[best], Math.toDegrees(diff(heading[best - w], heading[best + w])))
                i = j + w
            } else i++
        }
        return out
    }
}
