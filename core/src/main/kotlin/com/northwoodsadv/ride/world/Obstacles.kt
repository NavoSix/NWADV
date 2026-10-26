package com.northwoodsadv.ride.world

import kotlin.math.floor
import kotlin.math.sqrt

/** Trees and glacial boulders, placed deterministically so physics and rendering agree. */
object Obstacles {
    const val TREE_CELL = 5.0
    const val BOULDER_CELL = 9.0
    private const val TREE_SEED = 7331
    private const val BOULDER_SEED = 9113

    class Tree {
        var x = 0.0; var y = 0.0; var trunkR = 0.0; var height = 0.0; var conifer = true; var tint = 0.0
    }

    private fun density(ground: Ground, code: Byte, h: Double) = when (code) {
        Landcover.FOREST -> if (h > Ground.TREELINE) 0.0 else if (h > Ground.TREELINE - 150) 0.25 else 0.62
        Landcover.SCRUB -> 0.25
        Landcover.WETLAND -> 0.12
        Landcover.OPEN -> 0.03
        else -> 0.0
    }

    /** Fills [t] with the tree in cell (i, j), if there is one. */
    fun tree(ground: Ground, i: Int, j: Int, t: Tree): Boolean {
        val p = Noise.rand(i, j, TREE_SEED)
        if (p > 0.62) return false
        t.x = (i + 0.1 + 0.8 * Noise.rand(i, j, TREE_SEED + 1)) * TREE_CELL
        t.y = (j + 0.1 + 0.8 * Noise.rand(i, j, TREE_SEED + 2)) * TREE_CELL
        val h = ground.elevation.height(t.x, t.y)
        if (p >= density(ground, ground.landcover.at(t.x, t.y), h)) return false
        if (ground.roadClearance(t.x, t.y) < 3.0) return false
        if (ground.streamClearance(t.x, t.y) < 1.0) return false
        if (ground.scenery.solidAt(t.x, t.y, 2.0) != null) return false
        val q = Noise.rand(i, j, TREE_SEED + 3)
        t.height = 9 + 15 * q * if (h > Ground.TREELINE - 150) 0.35 else 1.0
        t.trunkR = 0.10 + 0.22 * q
        // More spruce and fir up high, hardwoods (maple, birch, beech) in the valleys.
        t.conifer = Noise.rand(i, j, TREE_SEED + 4) < 0.35 + 0.5 * Noise.smoothstep(300.0, 900.0, h)
        t.tint = Noise.rand(i, j, TREE_SEED + 5)
        return true
    }

    inline fun forEachTree(ground: Ground, x0: Double, y0: Double, x1: Double, y1: Double, t: Tree, fn: (Tree) -> Unit) {
        for (i in floor(x0 / TREE_CELL).toInt()..floor(x1 / TREE_CELL).toInt())
            for (j in floor(y0 / TREE_CELL).toInt()..floor(y1 / TREE_CELL).toInt())
                if (tree(ground, i, j, t)) fn(t)
    }

    class Boulder { var x = 0.0; var y = 0.0; var r = 0.0; var h = 0.0 }

    fun boulder(ground: Ground, i: Int, j: Int, b: Boulder): Boolean {
        if (Noise.rand(i, j, BOULDER_SEED) > 0.035) return false
        b.x = (i + 0.2 + 0.6 * Noise.rand(i, j, BOULDER_SEED + 1)) * BOULDER_CELL
        b.y = (j + 0.2 + 0.6 * Noise.rand(i, j, BOULDER_SEED + 2)) * BOULDER_CELL
        b.r = 0.45 + 0.9 * Noise.rand(i, j, BOULDER_SEED + 3)
        b.h = b.r * (0.45 + 0.4 * Noise.rand(i, j, BOULDER_SEED + 4))
        if (ground.landcover.at(b.x, b.y) != Landcover.FOREST) return false
        if (ground.roadClearance(b.x, b.y) < b.r + 1.5) return false
        if (ground.streamClearance(b.x, b.y) < b.r) return false
        if (ground.scenery.solidAt(b.x, b.y, b.r + 1) != null) return false
        return true
    }

    private val tlBoulder = ThreadLocal.withInitial { Boulder() }

    /** Height a boulder adds at (e, n). */
    fun boulderBump(ground: Ground, e: Double, n: Double): Double {
        val ci = floor(e / BOULDER_CELL).toInt(); val cj = floor(n / BOULDER_CELL).toInt()
        val b = tlBoulder.get()
        var best = 0.0
        for (di in -1..1) for (dj in -1..1) {
            val i = ci + di; val j = cj + dj
            if (Noise.rand(i, j, BOULDER_SEED) > 0.035) continue // cheap reject before the full test
            if (!boulder(ground, i, j, b)) continue
            val d2 = ((e - b.x) * (e - b.x) + (n - b.y) * (n - b.y)) / (b.r * b.r)
            if (d2 < 1) best = maxOf(best, b.h * sqrt(1 - d2))
        }
        return best
    }

    inline fun forEachBoulder(ground: Ground, x0: Double, y0: Double, x1: Double, y1: Double, b: Boulder, fn: (Boulder) -> Unit) {
        for (i in floor(x0 / BOULDER_CELL).toInt()..floor(x1 / BOULDER_CELL).toInt())
            for (j in floor(y0 / BOULDER_CELL).toInt()..floor(y1 / BOULDER_CELL).toInt())
                if (boulder(ground, i, j, b)) fn(b)
    }
}
