package com.northwoodsadv.ride.world

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What a [Prop] is; [solid] ones stop the bike. */
enum class PropKind(val solid: Boolean) {
    HOUSE(true), BARN(true), SILO(true), SHED(true), CHURCH(true), STORE(true),
    MAILBOX(false), HAY_BALE(true),
    SIGN_SPEED(false), SIGN_CURVE_LEFT(false), SIGN_CURVE_RIGHT(false), SIGN_MOOSE(false),
    STOP_SIGN(false), STOP_LINE(false), STREET_SIGN(false),
    /** Brown recreation/attraction sign with a name ("SCENIC VIEW", a waterfall, a waypoint). */
    BROWN_SIGN(false),
    /** New Hampshire's green historical highway marker. */
    HISTORIC_MARKER(false),
    ERRATIC(true), MONUMENT(true), TOWER(true),
}

/**
 * One placed object. (x, y) is its centre on the ground in world metres; it faces [heading]
 * (radians, east = 0, counter-clockwise), which for buildings and signs points at the road.
 * Half length along the heading [hx], half width across it [hz], height [h].
 */
class Prop(
    val kind: PropKind,
    val x: Double, val y: Double,
    val heading: Double,
    val hx: Double, val hz: Double, val h: Double,
    val seed: Int,
    /** Lettering for signs (lines top to bottom); for a street sign, the two road names. */
    val text: List<String> = emptyList(),
    /** Main colour (0xRRGGBB) where it varies: siding, barn paint. */
    val color: Int = 0xffffff,
    /** Roof: 0 gable, 1 gambrel (barns), 2 flat (commercial), 3 hip. */
    val roof: Int = 0,
    /** Second heading, for the cross blade of a street sign. */
    val heading2: Double = 0.0,
)

/** A linear feature drawn along a polyline. */
enum class RunKind { GUARDRAIL, BRIDGE_RAIL, STONE_WALL, RAIL_FENCE, BOARD_FENCE, COVERED_BRIDGE }

class Run(val kind: RunKind, val xs: DoubleArray, val ys: DoubleArray, val seed: Int, val width: Double = 0.0)

/** Something worth knowing about on the ride: named on the GPS and when you pass it. */
class Landmark(val name: String, val kind: String, val x: Double, val y: Double)

/** One road leaving a junction. */
class JunctionArm(val road: Int, val heading: Double, val name: String?, val rank: Int)

class Junction(val x: Double, val y: Double, val arms: List<JunctionArm>)

/**
 * Everything beside the road that isn't terrain, road or forest: buildings, fences, stone
 * walls, guard rails, signs, landmarks, and the junctions they were placed around. Built once
 * per world by [SceneryBuilder]; a pure lookup structure afterwards (shared by physics and the
 * renderer, so a house you can see is a house you can hit).
 */
class Scenery(
    val props: List<Prop>,
    val runs: List<Run>,
    val landmarks: List<Landmark>,
    val junctions: List<Junction>,
) {
    private val propGrid = HashMap<Long, IntArray>()
    private val runGrid = HashMap<Long, IntArray>()
    private val solidReach: Double

    init {
        val pg = HashMap<Long, MutableList<Int>>()
        var reach = 0.0
        for ((i, p) in props.withIndex()) {
            pg.getOrPut(key(cell(p.x), cell(p.y))) { ArrayList() } += i
            if (p.kind.solid) reach = maxOf(reach, kotlin.math.hypot(p.hx, p.hz))
        }
        solidReach = reach
        for ((k, v) in pg) propGrid[k] = v.toIntArray()
        val rg = HashMap<Long, MutableSet<Int>>()
        for ((i, r) in runs.withIndex()) for (k in r.xs.indices) rg.getOrPut(key(cell(r.xs[k]), cell(r.ys[k]))) { HashSet() } += i
        for ((k, v) in rg) runGrid[k] = v.toIntArray()
    }

    /** Props whose centre lies in [x0, x1) x [y0, y1). */
    inline fun forEachProp(x0: Double, y0: Double, x1: Double, y1: Double, fn: (Prop) -> Unit) {
        for (cx in cell(x0)..cell(x1 - 1e-6)) for (cy in cell(y0)..cell(y1 - 1e-6)) {
            val ids = propsIn(cx, cy) ?: continue
            for (i in ids) { val p = props[i]; if (p.x >= x0 && p.x < x1 && p.y >= y0 && p.y < y1) fn(p) }
        }
    }

    fun propsIn(cx: Int, cy: Int): IntArray? = propGrid[key(cx, cy)]

    /** Runs with a vertex within the cells covering the box. */
    fun runsNear(x0: Double, y0: Double, x1: Double, y1: Double): Set<Int> {
        val out = HashSet<Int>()
        for (cx in cell(x0)..cell(x1)) for (cy in cell(y0)..cell(y1)) runGrid[key(cx, cy)]?.let { for (i in it) out += i }
        return out
    }

    /** The solid prop whose footprint (grown by [r]) contains (x, y), or null. */
    fun solidAt(x: Double, y: Double, r: Double): Prop? {
        if (solidReach == 0.0) return null
        val reach = solidReach + r
        for (cx in cell(x - reach)..cell(x + reach)) for (cy in cell(y - reach)..cell(y + reach)) {
            val ids = propsIn(cx, cy) ?: continue
            for (i in ids) {
                val p = props[i]
                if (!p.kind.solid) continue
                val dx = x - p.x; val dy = y - p.y
                val c = cos(p.heading); val s = sin(p.heading)
                val u = dx * c + dy * s; val v = -dx * s + dy * c
                val round = p.kind == PropKind.SILO || p.kind == PropKind.ERRATIC || p.kind == PropKind.HAY_BALE || p.kind == PropKind.TOWER
                if (round) { if (u * u / ((p.hx + r) * (p.hx + r)) + v * v / ((p.hz + r) * (p.hz + r)) < 1) return p }
                else if (abs(u) < p.hx + r && abs(v) < p.hz + r) return p
            }
        }
        return null
    }

    /** The junction nearest (x, y) within [maxDist], or null. */
    fun junctionNear(x: Double, y: Double, maxDist: Double): Junction? =
        junctions.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
            ?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < maxDist * maxDist }

    companion object {
        const val CELL = 64.0
        val EMPTY = Scenery(emptyList(), emptyList(), emptyList(), emptyList())
        fun cell(v: Double) = floor(v / CELL).toInt()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
    }
}
