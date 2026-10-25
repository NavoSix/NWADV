package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.GroundSample
import com.northwoodsadv.ride.world.Noise
import com.northwoodsadv.ride.world.Obstacles
import com.northwoodsadv.ride.world.Road
import com.northwoodsadv.ride.world.TrailObstacles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry for one terrain chunk, built off the render thread. */
class ChunkData(val level: Int, val cx: Int, val cy: Int, val e0: Double, val n0: Double, val size: Double) {
    val terrain = MeshBuilder(4400)
    var roads: MeshBuilder? = null
    var trees: MeshBuilder? = null
}

enum class Season { SUMMER, FALL }

/**
 * Builds chunk geometry in chunk-local render coordinates: x = east - e0, y = height - baseH,
 * z = -(north - n0).
 */
class ChunkBuilder(private val ground: Ground, private val baseH: Double, var season: Season = Season.SUMMER) {
    private val roadBoxes = ground.roads.map { r ->
        doubleArrayOf(r.line.xs.min(), r.line.ys.min(), r.line.xs.max(), r.line.ys.max())
    }

    fun build(level: Int, cx: Int, cy: Int): ChunkData {
        val size = BASE * (1 shl level)
        val d = ChunkData(level, cx, cy, cx * size, cy * size, size)
        terrain(d)
        if (level <= 2) roads(d)
        if (level <= 1) trees(d)
        return d
    }

    private fun terrain(d: ChunkData) {
        val n = GRID
        val step = d.size / n
        val gs = GroundSample()
        val h = FloatArray((n + 1) * (n + 1))
        val col = FloatArray((n + 1) * (n + 1))
        for (j in 0..n) for (i in 0..n) {
            val e = d.e0 + i * step; val nn = d.n0 + j * step
            ground.sample(e, nn, gs, false)
            var y = gs.height - baseH
            // Tuck the terrain under road ribbons so ruts and rocks aren't poked through.
            if (d.level <= 1 && gs.road >= 0) {
                val r = ground.roads[gs.road]
                if (abs(gs.lateral) < r.halfWidth + ground.shoulder(r) - 0.2) y -= 0.35
            }
            h[j * (n + 1) + i] = y.toFloat()
            col[j * (n + 1) + i] = Palette.ground(gs, e, nn, d.level == 0)
        }
        val m = d.terrain
        val s = step.toFloat()
        for (j in 0..n) for (i in 0..n) {
            val hl = h[j * (n + 1) + max(i - 1, 0)]; val hr = h[j * (n + 1) + min(i + 1, n)]
            val hd = h[max(j - 1, 0) * (n + 1) + i]; val hu = h[min(j + 1, n) * (n + 1) + i]
            val dx = (hr - hl) / (s * (min(i + 1, n) - max(i - 1, 0)))
            val dn = (hu - hd) / (s * (min(j + 1, n) - max(j - 1, 0)))
            // Normal in render space: x east, y up, z south.
            var nx = -dx; var ny = 1f; var nz = dn
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            m.vertex(i * s, h[j * (n + 1) + i], -j * s, nx, ny, nz, col[j * (n + 1) + i])
        }
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i; val b = a + 1; val c = a + n + 1; val e = c + 1
            // Counter-clockwise seen from above (render z points south).
            if ((i + j) % 2 == 0) { m.tri(a, b, e); m.tri(a, e, c) } else { m.tri(a, b, c); m.tri(b, e, c) }
        }
        // Skirts hide cracks between neighbouring detail levels.
        val drop = (1.5 + step * 0.4).toFloat()
        fun skirt(idx: IntArray) {
            for (k in 0 until idx.size - 1) {
                val a = idx[k]; val b = idx[k + 1]
                val ao = a * MeshBuilder.STRIDE; val bo = b * MeshBuilder.STRIDE
                val v = m.verts
                val a2 = m.vertex(v[ao], v[ao + 1] - drop, v[ao + 2], v[ao + 3], v[ao + 4], v[ao + 5], v[ao + 6])
                val b2 = m.vertex(v[bo], v[bo + 1] - drop, v[bo + 2], v[bo + 3], v[bo + 4], v[bo + 5], v[bo + 6])
                m.tri(a, a2, b2); m.tri(a, b2, b); m.tri(a, b2, a2); m.tri(a, b, b2)
            }
        }
        skirt(IntArray(n + 1) { it })
        skirt(IntArray(n + 1) { n * (n + 1) + it })
        skirt(IntArray(n + 1) { it * (n + 1) })
        skirt(IntArray(n + 1) { it * (n + 1) + n })
    }

    private fun roads(d: ChunkData) {
        val pad = 12.0
        val x0 = d.e0 - pad; val y0 = d.n0 - pad; val x1 = d.e0 + d.size + pad; val y1 = d.n0 + d.size + pad
        val m = MeshBuilder(2048)
        val gs = GroundSample()
        val tmp = DoubleArray(3); val tmp2 = DoubleArray(3)
        for ((ri, road) in ground.roads.withIndex()) {
            val bb = roadBoxes[ri]
            if (bb[2] < x0 || bb[0] > x1 || bb[3] < y0 || bb[1] > y1) continue
            if (d.level == 2 && (road.spec.kind == RoadKind.SERVICE || road.spec.kind == RoadKind.TRAIL)) continue
            // Distance intervals of this road inside the chunk.
            val line = road.line
            var s0 = -1.0; var s1 = -1.0
            fun flush() { if (s0 >= 0) ribbon(d, m, road, s0, s1, gs, tmp, tmp2); s0 = -1.0 }
            for (i in 0 until line.size - 1) {
                val inside = max(line.xs[i], line.xs[i + 1]) >= x0 && min(line.xs[i], line.xs[i + 1]) <= x1 &&
                    max(line.ys[i], line.ys[i + 1]) >= y0 && min(line.ys[i], line.ys[i + 1]) <= y1
                if (inside) { if (s0 < 0) s0 = line.cum[i]; s1 = line.cum[i + 1] } else flush()
                if (m.full) break
            }
            flush()
            if (m.full) break
        }
        if (m.vertCount > 0) d.roads = m
    }

    private fun ribbon(d: ChunkData, m: MeshBuilder, road: Road, s0: Double, s1: Double, gs: GroundSample, a: DoubleArray, b: DoubleArray) {
        val rough = road.spec.isClassVI || road.spec.roughness >= 0.3
        val ds = when (d.level) { 0 -> if (rough) 0.5 else 1.5; 1 -> 3.0; else -> 9.0 }
        val lanes = when (d.level) { 0 -> if (rough) 13 else 7; 1 -> 5; else -> 3 }
        val lift = when (d.level) { 0 -> 0.03; 1 -> 0.06; else -> 0.25 }
        val w = road.halfWidth + ground.shoulder(road) + if (d.level == 0) 0.5 else 0.2
        val steps = ((s1 - s0) / ds).toInt().coerceAtLeast(1)
        val first = m.vertCount
        for (k in 0..steps) {
            val s = s0 + (s1 - s0) * k / steps
            road.line.pointAt(s - 1.5, a); road.line.pointAt(s + 1.5, b)
            val hdg = atan2(b[1] - a[1], b[0] - a[0])
            val turn = turnRadius(road, s)
            road.line.pointAt(s, a)
            val lx = -sin(hdg); val ly = cos(hdg)
            for (l in 0 until lanes) {
                val off = clampInside(-w + 2 * w * l / (lanes - 1), turn)
                val e = a[0] + lx * off; val n = a[1] + ly * off
                ground.sample(e, n, gs, d.level == 0, obstacles = false)
                val y = (gs.height - baseH + lift).toFloat()
                m.vertex((e - d.e0).toFloat(), y, (-(n - d.n0)).toFloat(), 0f, 1f, 0f, Palette.ground(gs, e, n, d.level == 0))
            }
        }
        // Normals from the grid, then triangles.
        val v = m.verts
        for (k in 0..steps) for (l in 0 until lanes) {
            val i = first + k * lanes + l
            val ia = first + max(k - 1, 0) * lanes + l; val ib = first + min(k + 1, steps) * lanes + l
            val il = first + k * lanes + max(l - 1, 0); val ir = first + k * lanes + min(l + 1, lanes - 1)
            val ax = v[ib * 7] - v[ia * 7]; val ay = v[ib * 7 + 1] - v[ia * 7 + 1]; val az = v[ib * 7 + 2] - v[ia * 7 + 2]
            val bx = v[ir * 7] - v[il * 7]; val by = v[ir * 7 + 1] - v[il * 7 + 1]; val bz = v[ir * 7 + 2] - v[il * 7 + 2]
            var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
            if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            v[i * 7 + 3] = nx / len; v[i * 7 + 4] = ny / len; v[i * 7 + 5] = nz / len
        }
        for (k in 0 until steps) for (l in 0 until lanes - 1) {
            val p = first + k * lanes + l
            val q = p + lanes
            // Order so the faces point up regardless of direction (cull disabled for ribbons anyway).
            m.tri(p, p + 1, q + 1); m.tri(p, q + 1, q)
        }
        if (d.level <= 1 && road.spec.surface.isPaved) markings(d, m, road, s0, s1, gs, a, b, lift)
    }

    /**
     * Signed radius of the road's curve at s (positive = turning left), from the heading change
     * over 6 m. Large when straight.
     */
    private fun turnRadius(road: Road, s: Double): Double {
        val p = DoubleArray(3); road.line.pointAt(s - 3, p); val h0 = p[2]; road.line.pointAt(s + 3, p)
        var dh = p[2] - h0
        while (dh > Math.PI) dh -= 2 * Math.PI; while (dh < -Math.PI) dh += 2 * Math.PI
        return if (abs(dh) < 1e-4) 1e9 else 6.0 / dh
    }

    /** Keeps ribbon vertices on the inside of a tight bend from folding past its centre. */
    private fun clampInside(off: Double, radius: Double): Double {
        if (radius > 0 && off > 0) return minOf(off, radius * 0.8)
        if (radius < 0 && off < 0) return maxOf(off, radius * 0.8)
        return off
    }

    /** Painted lines: double yellow centre on main roads, white fog lines on roads 6 m+. */
    private fun markings(d: ChunkData, m: MeshBuilder, road: Road, s0: Double, s1: Double, gs: GroundSample, a: DoubleArray, b: DoubleArray, lift: Double) {
        val lines = ArrayList<DoubleArray>() // offset, half width, rgb
        val kind = road.spec.kind
        if (kind == RoadKind.HIGHWAY || kind == RoadKind.MAJOR || (kind == RoadKind.MINOR && road.spec.width >= 6.0)) {
            val edge = road.halfWidth - 0.25
            lines += doubleArrayOf(edge, 0.06, 0xe8e8e0.toDouble()); lines += doubleArrayOf(-edge, 0.06, 0xe8e8e0.toDouble())
        }
        if (kind == RoadKind.HIGHWAY || kind == RoadKind.MAJOR) {
            lines += doubleArrayOf(0.1, 0.05, 0xe0b020.toDouble()); lines += doubleArrayOf(-0.1, 0.05, 0xe0b020.toDouble())
        }
        if (lines.isEmpty()) return
        val ds = if (d.level == 0) 2.0 else 5.0
        val steps = ((s1 - s0) / ds).toInt().coerceAtLeast(1)
        for (ln in lines) {
            val col = Palette.shade(ln[2].toInt(), 1.0)
            var prevL = -1; var prevR = -1
            for (k in 0..steps) {
                val s = s0 + (s1 - s0) * k / steps
                road.line.pointAt(s - 1.5, a); road.line.pointAt(s + 1.5, b)
                val hdg = atan2(b[1] - a[1], b[0] - a[0])
                road.line.pointAt(s, a)
                val lx = -sin(hdg); val ly = cos(hdg)
                var vl = -1; var vr = -1
                for (side in 0..1) {
                    val off = ln[0] + if (side == 0) -ln[1] else ln[1]
                    val e = a[0] + lx * off; val n = a[1] + ly * off
                    ground.sample(e, n, gs, d.level == 0, obstacles = false)
                    val v = m.vertex((e - d.e0).toFloat(), (gs.height - baseH + lift + 0.012).toFloat(), (-(n - d.n0)).toFloat(), 0f, 1f, 0f, col)
                    if (side == 0) vl = v else vr = v
                }
                if (prevL >= 0) { m.tri(prevL, vl, vr); m.tri(prevL, vr, prevR) }
                prevL = vl; prevR = vr
            }
        }
    }

    private fun trees(d: ChunkData) {
        val m = MeshBuilder(8192)
        val t = Obstacles.Tree()
        val detailed = d.level == 0
        Obstacles.forEachTree(ground, d.e0, d.n0, d.e0 + d.size - 0.001, d.n0 + d.size - 0.001, t) { tr ->
            if (m.full) return@forEachTree
            val by = (ground.height(tr.x, tr.y, false) - baseH).toFloat() - 0.2f
            val x = (tr.x - d.e0).toFloat(); val z = (-(tr.y - d.n0)).toFloat()
            val h = tr.height.toFloat()
            val trunkCol = if (!tr.conifer && tr.tint > 0.8) MeshBuilder.color(0xd8d4c8) else MeshBuilder.color(0x4a3a2a)
            if (detailed) m.cone(x, by, z, tr.trunkR.toFloat(), tr.trunkR.toFloat() * 0.7f, h * 0.45f, 5, trunkCol, tr.tint.toFloat())
            if (tr.conifer) {
                val c = Palette.shade(0x23402a, 0.8 + tr.tint * 0.4)
                val r = h * 0.2f
                if (detailed) {
                    m.cone(x, by + h * 0.18f, z, r, 0f, h * 0.5f, 7, c, tr.tint.toFloat())
                    m.cone(x, by + h * 0.42f, z, r * 0.75f, 0f, h * 0.42f, 7, c, tr.tint.toFloat() + 0.4f)
                    m.cone(x, by + h * 0.65f, z, r * 0.5f, 0f, h * 0.35f, 6, c, tr.tint.toFloat() + 0.8f)
                } else m.cone(x, by + h * 0.15f, z, r, 0f, h * 0.85f, 5, c, tr.tint.toFloat())
            } else {
                val c = leafColor(tr.tint)
                val r = h * 0.28f
                if (detailed) {
                    m.blob(x, by + h * 0.68f, z, r, h * 0.28f, r * 0.9f, c, tr.tint.toFloat() * 3)
                    m.blob(x + r * 0.3f, by + h * 0.85f, z - r * 0.2f, r * 0.7f, h * 0.18f, r * 0.7f, c, tr.tint.toFloat() * 5)
                } else m.blob(x, by + h * 0.68f, z, r, h * 0.3f, r, c, tr.tint.toFloat() * 3)
            }
        }
        if (detailed) {
            val b = Obstacles.Boulder()
            Obstacles.forEachBoulder(ground, d.e0, d.n0, d.e0 + d.size - 0.001, d.n0 + d.size - 0.001, b) { bo ->
                val by = (ground.height(bo.x, bo.y, false) - baseH).toFloat()
                m.blob((bo.x - d.e0).toFloat(), by, (-(bo.y - d.n0)).toFloat(), bo.r.toFloat(), bo.h.toFloat(), bo.r.toFloat() * 0.85f,
                    Palette.shade(0x8a8a82, 0.85 + Noise.rand(bo.x.toInt(), bo.y.toInt(), 3) * 0.3), bo.x.toFloat())
            }
        }
        trailObstacles(d, m)
        if (m.vertCount > 0) d.trees = m
    }

    /** Logs and rocks on Class VI roads whose position falls inside this chunk. */
    internal fun trailObstacles(d: ChunkData, m: MeshBuilder) {
        val o = TrailObstacles.Obstacle()
        val p = DoubleArray(3)
        val bark = Palette.shade(0x7a6248, 1.0); val cut = Palette.shade(0xd8b888, 1.0)
        for ((ri, road) in ground.roads.withIndex()) {
            if (!road.spec.isClassVI) continue
            val bb = roadBoxes[ri]
            if (bb[2] < d.e0 - 20 || bb[0] > d.e0 + d.size + 20 || bb[3] < d.n0 - 20 || bb[1] > d.n0 + d.size + 20) continue
            val slots = (road.line.length / TrailObstacles.SLOT).toInt() + 1
            for (k in 0..slots) {
                if (!TrailObstacles.obstacle(road, k, o)) continue
                road.line.pointAt(o.s, p)
                val hd = p[2]
                val fx = kotlin.math.cos(hd); val fy = kotlin.math.sin(hd)  // along road
                val lx = -fy; val ly = fx                                        // left
                val cx = p[0] + lx * o.lat; val cy = p[1] + ly * o.lat
                if (cx < d.e0 || cx >= d.e0 + d.size || cy < d.n0 || cy >= d.n0 + d.size) continue
                if (o.log) {
                    // Axis: mostly across the road, angled by o.angle.
                    val sa = kotlin.math.sin(o.angle); val ca = kotlin.math.cos(o.angle)
                    val axX = fx * sa + lx * ca; val axY = fy * sa + ly * ca
                    val half = o.length / 2
                    val ax0 = cx - axX * half; val ay0 = cy - axY * half
                    val ax1 = cx + axX * half; val ay1 = cy + axY * half
                    val r0 = o.r; val r1 = o.r * 0.65
                    val h0 = ground.height(ax0, ay0, false) - baseH + r0
                    val h1 = ground.height(ax1, ay1, false) - baseH + r1
                    m.tube((ax0 - d.e0).toFloat(), h0.toFloat(), (-(ay0 - d.n0)).toFloat(),
                        (ax1 - d.e0).toFloat(), h1.toFloat(), (-(ay1 - d.n0)).toFloat(), r0.toFloat(), r1.toFloat(), 7, bark, cut)
                    // A couple of snapped-off branch stubs.
                    for (b in listOf(0.3, 0.62)) {
                        val bx = ax0 + (ax1 - ax0) * b; val by = ay0 + (ay1 - ay0) * b
                        val bh = h0 + (h1 - h0) * b
                        m.beam((bx - d.e0).toFloat(), bh.toFloat(), (-(by - d.n0)).toFloat(),
                            (bx - d.e0 + fx * 0.5).toFloat(), (bh + 0.45).toFloat(), (-(by - d.n0 + fy * 0.5)).toFloat(), 0.03f, bark)
                    }
                } else {
                    val gy = ground.height(cx, cy, false) - baseH
                    m.blob((cx - d.e0).toFloat(), gy.toFloat(), (-(cy - d.n0)).toFloat(), o.r.toFloat(), o.h.toFloat(), (o.r * 0.9).toFloat(),
                        Palette.shade(0x84847c, 0.9 + 0.2 * Noise.rand(k, ri, 5)), o.s.toFloat())
                }
            }
        }
    }

    private fun leafColor(t: Double): Float = when (season) {
        Season.SUMMER -> Palette.shade(if (t < 0.5) 0x3c6428 else 0x4c7430, 0.85 + t * 0.3)
        // New Hampshire in October: sugar maple red/orange, birch and beech yellow.
        Season.FALL -> when {
            t < 0.25 -> Palette.shade(0xc8341c, 0.9 + t)
            t < 0.5 -> Palette.shade(0xe07a1a, 0.9 + t * 0.3)
            t < 0.75 -> Palette.shade(0xd8b02a, 0.9 + t * 0.2)
            else -> Palette.shade(0x6a7a2a, 1.0)
        }
    }

    companion object {
        const val BASE = 128.0
        const val GRID = 64
    }
}
