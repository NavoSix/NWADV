package com.northwoodsadv.ride.route

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.world.Junction
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
    var turns: List<Turn> = findTurns(); private set
    private val tmp = DoubleArray(2)
    private var globalSearchTimer = 0.0

    class Turn(val along: Double, val angleDeg: Double) {
        /** The road the route turns onto, when the map names it. */
        var onto: String? = null
        val left get() = angleDeg > 0
        val action get() = when {
            abs(angleDeg) > 135 -> "U-turn"
            abs(angleDeg) > 70 -> if (left) "Left" else "Right"
            abs(angleDeg) > 22 -> if (left) "Bear left" else "Bear right"
            else -> "Continue"
        }
        val label get() = onto?.let { "$action onto $it" } ?: action
    }

    /**
     * Names the turns after the real intersections the route passes through: a turn at a junction
     * says which road it turns onto, and where the route changes road at a junction without much
     * of a bend (a fork, a road changing name), that becomes a turn too.
     */
    fun nameTurns(junctions: List<Junction>) {
        val out = turns.toMutableList()
        val tmp = DoubleArray(2)
        fun headingAt(s: Double): Double {
            val a = route.pointAt(s - 1, DoubleArray(3)); val b = route.pointAt(s + 1, DoubleArray(3))
            return atan2(b[1] - a[1], b[0] - a[0])
        }
        fun diff(a: Double, b: Double): Double { var d = b - a; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return d }
        for (j in junctions) {
            route.project(j.x, j.y, 0.0, Double.MAX_VALUE, tmp)
            if (tmp[1] > 18) continue
            val s = tmp[0]
            if (s < 20 || s > route.length - 20) continue
            // Which arm the route leaves on, and which it came in on.
            val outHd = atan2(route.pointAt(s + 30, DoubleArray(3))[1] - j.y, route.pointAt(s + 30, DoubleArray(3))[0] - j.x)
            val inHd = atan2(route.pointAt(s - 30, DoubleArray(3))[1] - j.y, route.pointAt(s - 30, DoubleArray(3))[0] - j.x)
            val exit = j.arms.minByOrNull { abs(diff(it.heading, outHd)) } ?: continue
            val entry = j.arms.minByOrNull { abs(diff(it.heading, inHd)) } ?: continue
            if (exit === entry || abs(diff(exit.heading, outHd)) > 0.6) continue
            val name = exit.name ?: continue
            val existing = out.minByOrNull { abs(it.along - s) }?.takeIf { abs(it.along - s) < 45 }
            if (existing != null) { if (existing.onto == null) existing.onto = name; continue }
            if (entry.name == name) continue // straight through on the same road
            val angle = Math.toDegrees(diff(headingAt(s - 25), headingAt(s + 25)))
            out += Turn(s, angle).also { it.onto = name }
        }
        turns = out.sortedBy { it.along }
    }

    /** Pick up part way along (a saved ride), so the next update searches around there. */
    fun resumeAt(s: Double) { along = s.coerceIn(0.0, route.length) }

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
