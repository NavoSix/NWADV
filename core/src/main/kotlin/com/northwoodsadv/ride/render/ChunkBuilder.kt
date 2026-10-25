package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.world.Canopy
import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.GroundSample
import com.northwoodsadv.ride.world.Noise
import com.northwoodsadv.ride.world.Obstacles
import com.northwoodsadv.ride.world.Road
import com.northwoodsadv.ride.world.SegmentIndex
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
    val trees = MeshSet()
    /** Water surfaces (streams, fords, lakes), drawn last with blending. */
    var water: MeshBuilder? = null
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
        if (level <= 2) water(d)
        if (level <= 2) roads(d)
        if (level <= 1) trees(d)
        return d
    }

    private fun terrain(d: ChunkData) {
        val n = if (d.level == 0) GRID_NEAR else GRID
        val step = d.size / n
        val gs = GroundSample()
        val hit = SegmentIndex.Hit()
        val h = FloatArray((n + 1) * (n + 1))
        val col = FloatArray((n + 1) * (n + 1))
        for (j in 0..n) for (i in 0..n) {
            val e = d.e0 + i * step; val nn = d.n0 + j * step
            ground.sample(e, nn, gs, false)
            var y = gs.height - baseH
            // Tuck the terrain under road ribbons so ruts and rocks aren't poked through.
            if (d.level <= 1 && gs.road >= 0) {
                val r = ground.roads[gs.road]
                val edge = r.halfWidth + ground.shoulder(r)
                val reach = (if (d.level == 0) BANK_LANES0.last() else BANK_LANES1.last()) - 1.0
                if (abs(gs.lateral) < edge + reach) {
                    // Under the ribbon: keep the coarse grid below the true surface, which in a
                    // hollow (the foot of a fill, the toe of a cut) means the lowest point nearby.
                    val hs = step * 0.6
                    var lo = y
                    for (k in 0 until 4) {
                        val ox = if (k == 0) hs else if (k == 1) -hs else 0.0
                        val oy = if (k == 2) hs else if (k == 3) -hs else 0.0
                        lo = minOf(lo, ground.height(e + ox, nn + oy, false) - baseH)
                    }
                    y = lo - if (abs(gs.lateral) < edge - 0.2) 0.35 else 0.15
                }
            }
            h[j * (n + 1) + i] = y.toFloat()
            val light = when {
                d.level > 1 -> 1.0
                gs.road >= 0 -> Canopy.light(ground, ground.roads[gs.road], gs.along, gs.lateral, e, nn)
                ground.nearestRoadHit(e, nn, 16.0, hit) -> Canopy.light(ground, ground.roads[hit.line], hit.along, hit.lateral, e, nn)
                else -> 1.0
            }
            col[j * (n + 1) + i] = Palette.ground(gs, e, nn, d.level == 0, light)
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

    private val streamBoxes = ground.streams.map { st ->
        doubleArrayOf(st.line.xs.min(), st.line.ys.min(), st.line.xs.max(), st.line.ys.max())
    }

    /**
     * Water surfaces: a flat ribbon along each stream at its water level (the banks rise through
     * it, which draws the shoreline), and the terrain grid's lake cells at lake level.
     */
    private fun water(d: ChunkData) {
        val m = MeshBuilder(512)
        val gs = GroundSample()
        val a = DoubleArray(3); val b = DoubleArray(3)
        val pad = 10.0
        val col = MeshBuilder.color(0x2a3a34)
        val ds = when (d.level) { 0 -> 1.5; 1 -> 4.0; else -> 10.0 }
        for ((si, st) in ground.streams.withIndex()) {
            val bb = streamBoxes[si]
            if (bb[2] < d.e0 - pad || bb[0] > d.e0 + d.size + pad || bb[3] < d.n0 - pad || bb[1] > d.n0 + d.size + pad) continue
            if (d.level == 2 && !st.river) continue
            val half = (st.width / 2 + Ground.BANK * 0.7).toFloat()
            val steps = (st.line.length / ds).toInt().coerceAtLeast(1)
            var prev = -1
            for (k in 0..steps) {
                val s = st.line.length * k / steps
                st.line.pointAt(s, a)
                val inside = a[0] >= d.e0 - ds && a[0] <= d.e0 + d.size + ds && a[1] >= d.n0 - ds && a[1] <= d.n0 + d.size + ds
                ground.sample(a[0], a[1], gs, false)
                val level = gs.waterLevel
                if (!inside || level.isNaN()) { prev = -1; continue }
                st.line.pointAt(s + 1.0, b)
                val hdg = atan2(b[1] - a[1], b[0] - a[0]).let { if (s + 1.0 > st.line.length) a[2] else it }
                val lx = -sin(hdg); val ly = cos(hdg)
                val first = m.vertCount
                for (l in 0 until WATER_LANES) {
                    val off = half * (2.0 * l / (WATER_LANES - 1) - 1)
                    val e = a[0] + lx * off; val n = a[1] + ly * off
                    // Flat at the water level, except where a bank falls away below it (a stream
                    // crossing a slope): there the edge follows the ground instead of floating.
                    ground.sample(e, n, gs, false)
                    val y = minOf(level, gs.height + gs.waterDepth - 0.03)
                    m.vertex((e - d.e0).toFloat(), (y - baseH).toFloat(), (-(n - d.n0)).toFloat(), 0f, 1f, 0f, col)
                }
                if (prev >= 0) for (l in 0 until WATER_LANES - 1) { m.tri(prev + l, first + l, first + l + 1); m.tri(prev + l, first + l + 1, prev + l + 1) }
                prev = first
            }
        }
        // Lakes and ponds: cells of the chunk grid with a lake corner.
        val n = if (d.level == 0) GRID / 2 else GRID / 4
        val step = d.size / n
        val lvl = FloatArray((n + 1) * (n + 1)) { Float.NaN }
        for (j in 0..n) for (i in 0..n) {
            ground.sample(d.e0 + i * step, d.n0 + j * step, gs, false)
            if (gs.surface == com.northwoodsadv.ride.world.Surface.DEEP_WATER && gs.road < 0) lvl[j * (n + 1) + i] = (gs.waterLevel.takeIf { !it.isNaN() } ?: gs.height + 0.05).toFloat()
        }
        for (j in 0 until n) for (i in 0 until n) {
            val c = intArrayOf(j * (n + 1) + i, j * (n + 1) + i + 1, (j + 1) * (n + 1) + i + 1, (j + 1) * (n + 1) + i)
            var sum = 0f; var cnt = 0
            for (q in c) if (!lvl[q].isNaN()) { sum += lvl[q]; cnt++ }
            if (cnt == 0) continue
            val y = sum / cnt - baseH.toFloat()
            val x0 = (i * step).toFloat(); val x1 = ((i + 1) * step).toFloat()
            val z0 = (-j * step).toFloat(); val z1 = (-(j + 1) * step).toFloat()
            val v0 = m.vertex(x0, y, z0, 0f, 1f, 0f, col); val v1 = m.vertex(x1, y, z0, 0f, 1f, 0f, col)
            val v2 = m.vertex(x1, y, z1, 0f, 1f, 0f, col); val v3 = m.vertex(x0, y, z1, 0f, 1f, 0f, col)
            m.tri(v0, v1, v2); m.tri(v0, v2, v3)
        }
        if (m.vertCount > 0) d.water = m
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
        // Even lanes across the road, then a few out each side so cut banks and fill slopes
        // get crisp edges instead of the terrain grid's 2 m facets.
        val banks = when (d.level) { 0 -> BANK_LANES0; 1 -> BANK_LANES1; else -> DoubleArray(0) }
        val offs = DoubleArray(lanes + 2 * banks.size)
        for (k in banks.indices) { offs[banks.size - 1 - k] = -w - banks[k]; offs[banks.size + lanes + k] = w + banks[k] }
        for (l in 0 until lanes) offs[banks.size + l] = -w + 2 * w * l / (lanes - 1)
        val cols = offs.size
        val steps = ((s1 - s0) / ds).toInt().coerceAtLeast(1)
        val first = m.vertCount
        for (k in 0..steps) {
            val s = s0 + (s1 - s0) * k / steps
            road.line.pointAt(s - 1.5, a); road.line.pointAt(s + 1.5, b)
            val hdg = atan2(b[1] - a[1], b[0] - a[0])
            val turn = turnRadius(road, s)
            road.line.pointAt(s, a)
            val lx = -sin(hdg); val ly = cos(hdg)
            for (l in 0 until cols) {
                val off = clampInside(offs[l], turn)
                val e = a[0] + lx * off; val n = a[1] + ly * off
                ground.sample(e, n, gs, d.level == 0, obstacles = false)
                val y = (gs.height - baseH + lift).toFloat()
                val light = if (d.level <= 1) Canopy.light(ground, road, s, off, e, n) else 1.0
                m.vertex((e - d.e0).toFloat(), y, (-(n - d.n0)).toFloat(), 0f, 1f, 0f, Palette.ground(gs, e, n, d.level == 0, light))
            }
        }
        // Normals from the grid, then triangles.
        val v = m.verts
        for (k in 0..steps) for (l in 0 until cols) {
            val i = first + k * cols + l
            val ia = first + max(k - 1, 0) * cols + l; val ib = first + min(k + 1, steps) * cols + l
            val il = first + k * cols + max(l - 1, 0); val ir = first + k * cols + min(l + 1, cols - 1)
            val ax = v[ib * 7] - v[ia * 7]; val ay = v[ib * 7 + 1] - v[ia * 7 + 1]; val az = v[ib * 7 + 2] - v[ia * 7 + 2]
            val bx = v[ir * 7] - v[il * 7]; val by = v[ir * 7 + 1] - v[il * 7 + 1]; val bz = v[ir * 7 + 2] - v[il * 7 + 2]
            var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
            if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            v[i * 7 + 3] = nx / len; v[i * 7 + 4] = ny / len; v[i * 7 + 5] = nz / len
        }
        for (k in 0 until steps) for (l in 0 until cols - 1) {
            val p = first + k * cols + l
            val q = p + cols
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
        val ms = d.trees
        val t = Obstacles.Tree()
        val detailed = d.level == 0
        val hit = SegmentIndex.Hit()
        Obstacles.forEachTree(ground, d.e0, d.n0, d.e0 + d.size - 0.001, d.n0 + d.size - 0.001, t) { tr ->
            val m = ms.room() ?: return@forEachTree
            val by = (ground.height(tr.x, tr.y, false) - baseH).toFloat() - 0.2f
            val x = (tr.x - d.e0).toFloat(); val z = (-(tr.y - d.n0)).toFloat()
            val h = tr.height.toFloat()
            // Where the canopy closes over a road, roadside trees lean out over it.
            var lx = 0f; var lz = 0f
            if (ground.nearestRoadHit(tr.x, tr.y, 14.0, hit)) {
                val road = ground.roads[hit.line]
                val k = Canopy.tunnel(road, hit.along) * (1 - Noise.smoothstep(4.0, 12.0, hit.dist - road.halfWidth))
                if (k > 0) {
                    // Toward the centreline: the left normal times -sign(lateral), in render space.
                    val sgn = if (hit.lateral > 0) -1.0 else 1.0
                    val nx = -kotlin.math.sin(hit.heading) * sgn; val ny = kotlin.math.cos(hit.heading) * sgn
                    val reach = min(hit.dist * 0.55 + road.halfWidth * 0.5, tr.height * 0.45) * k * if (tr.conifer) 0.3 else 1.0
                    lx = (nx * reach).toFloat(); lz = (-ny * reach).toFloat()
                }
            }
            val trunkCol = if (!tr.conifer && tr.tint > 0.8) MeshBuilder.color(0xd8d4c8) else MeshBuilder.color(0x4a3a2a)
            val leaning = lx != 0f || lz != 0f
            if (detailed) {
                if (leaning) m.tube(x, by, z, x + lx * 0.75f, by + h * 0.62f, z + lz * 0.75f, tr.trunkR.toFloat(), tr.trunkR.toFloat() * 0.6f, 5, trunkCol, trunkCol)
                else m.cone(x, by, z, tr.trunkR.toFloat(), tr.trunkR.toFloat() * 0.7f, h * 0.45f, 5, trunkCol, tr.tint.toFloat())
            }
            if (tr.conifer) {
                val c = Palette.shade(0x23402a, 0.8 + tr.tint * 0.4)
                val r = h * 0.2f
                // Tiers shift along the lean.
                if (detailed) {
                    m.cone(x + lx * 0.3f, by + h * 0.18f, z + lz * 0.3f, r, 0f, h * 0.5f, 7, c, tr.tint.toFloat())
                    m.cone(x + lx * 0.6f, by + h * 0.42f, z + lz * 0.6f, r * 0.75f, 0f, h * 0.42f, 7, Palette.shade(0x23402a, 0.88 + tr.tint * 0.4), tr.tint.toFloat() + 0.4f)
                    m.cone(x + lx * 0.9f, by + h * 0.65f, z + lz * 0.9f, r * 0.5f, 0f, h * 0.35f, 6, Palette.shade(0x23402a, 0.96 + tr.tint * 0.4), tr.tint.toFloat() + 0.8f)
                } else m.cone(x + lx * 0.5f, by + h * 0.15f, z + lz * 0.5f, r, 0f, h * 0.85f, 5, c, tr.tint.toFloat())
            } else {
                val c = leafColor(tr.tint)
                val r = h * 0.28f * if (leaning) 1.25f else 1f
                if (detailed) {
                    val sd = (tr.x * 0.37 + tr.y * 0.11).toFloat()
                    m.crown(x + lx, by + h * 0.66f, z + lz, r, h * 0.26f, r * 0.9f, c, leafColor(tr.tint, 1.15), sd)
                    m.crown(x + lx * 1.1f + r * 0.35f, by + h * 0.84f, z + lz * 1.1f - r * 0.25f, r * 0.68f, h * 0.17f, r * 0.68f, leafColor(tr.tint, 1.05), leafColor(tr.tint, 1.22), sd + 1.7f, 6)
                    if (h > 14f) m.crown(x + lx * 0.9f - r * 0.4f, by + h * 0.76f, z + lz * 0.9f + r * 0.3f, r * 0.6f, h * 0.15f, r * 0.6f, leafColor(tr.tint, 0.95), leafColor(tr.tint, 1.12), sd + 3.1f, 6)
                } else m.blob(x + lx, by + h * 0.68f, z + lz, r, h * 0.3f, r, c, tr.tint.toFloat() * 3)
            }
        }
        canopy(d, ms)
        if (detailed) {
            val b = Obstacles.Boulder()
            Obstacles.forEachBoulder(ground, d.e0, d.n0, d.e0 + d.size - 0.001, d.n0 + d.size - 0.001, b) { bo ->
                val m = ms.room() ?: return@forEachBoulder
                val by = (ground.height(bo.x, bo.y, false) - baseH).toFloat()
                m.blob((bo.x - d.e0).toFloat(), by, (-(bo.y - d.n0)).toFloat(), bo.r.toFloat(), bo.h.toFloat(), bo.r.toFloat() * 0.85f,
                    Palette.shade(0x8a8a82, 0.85 + Noise.rand(bo.x.toInt(), bo.y.toInt(), 3) * 0.3), bo.x.toFloat())
            }
        }
        ms.room()?.let { trailObstacles(d, it) }
        roadsideGrass(d, ms)
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

    private fun leafColor(t: Double, k: Double = 1.0): Float = when (season) {
        Season.SUMMER -> Palette.shade(if (t < 0.5) 0x3c6428 else 0x4c7430, (0.85 + t * 0.3) * k)
        // New Hampshire in October: sugar maple red/orange, birch and beech yellow.
        Season.FALL -> when {
            t < 0.25 -> Palette.shade(0xc8341c, (0.9 + t) * k)
            t < 0.5 -> Palette.shade(0xe07a1a, (0.9 + t * 0.3) * k)
            t < 0.75 -> Palette.shade(0xd8b02a, (0.9 + t * 0.2) * k)
            else -> Palette.shade(0x6a7a2a, k)
        }
    }

    /** Tufts of grass and weeds along the road edges, close up only. */
    private fun roadsideGrass(d: ChunkData, ms: MeshSet) {
        if (d.level != 0) return
        val p = DoubleArray(3)
        val gs = GroundSample()
        val (c0, c1) = when (season) {
            Season.SUMMER -> 0x2c4018 to 0x56702c
            Season.FALL -> 0x4a4a24 to 0x8c8040
        }
        for ((ri, road) in ground.roads.withIndex()) {
            val bb = roadBoxes[ri]
            if (bb[2] < d.e0 || bb[0] > d.e0 + d.size || bb[3] < d.n0 || bb[1] > d.n0 + d.size) continue
            val edge = road.halfWidth + ground.shoulder(road)
            var s = 0.0; var k = 0
            while (s < road.line.length) {
                road.line.pointAt(s, p)
                if (p[0] >= d.e0 - 3 && p[0] < d.e0 + d.size + 3 && p[1] >= d.n0 - 3 && p[1] < d.n0 + d.size + 3) {
                    val m = ms.room() ?: return
                    val lx = -sin(p[2]); val ly = cos(p[2])
                    for (side in 0..1) {
                        val r = Noise.rand(k, side, road.seed + 91)
                        if (r < 0.3) continue
                        val sg = if (side == 0) 1.0 else -1.0
                        val lat = sg * (edge - 0.2 + 1.8 * Noise.rand(k, side, road.seed + 92))
                        val e = p[0] + lx * lat; val n = p[1] + ly * lat
                        if (e < d.e0 || e >= d.e0 + d.size || n < d.n0 || n >= d.n0 + d.size) continue
                        ground.sample(e, n, gs, true, obstacles = false)
                        if (gs.onRoad || gs.cutFace > 0.3 || !gs.waterLevel.isNaN()) continue
                        if (gs.surface != com.northwoodsadv.ride.world.Surface.GRASS && gs.surface != com.northwoodsadv.ride.world.Surface.FOREST &&
                            gs.surface != com.northwoodsadv.ride.world.Surface.DIRT && gs.surface != com.northwoodsadv.ride.world.Surface.GRAVEL_PACKED) continue
                        val hgt = (0.2 + 0.35 * r).toFloat()
                        val w = (0.10 + 0.12 * Noise.rand(k, side, road.seed + 93)).toFloat()
                        val x = (e - d.e0).toFloat(); val y = (gs.height - baseH).toFloat() - 0.03f; val z = (-(n - d.n0)).toFloat()
                        val shade = 0.85 + 0.3 * Noise.rand(k, side, road.seed + 94)
                        val lo = Palette.shade(c0, shade); val hi = Palette.shade(c1, shade)
                        val a0 = (Noise.rand(k, side, road.seed + 95) * Math.PI).toFloat()
                        for (q in 0..2) {
                            val ang = a0 + q * 1.05f
                            val dx = kotlin.math.cos(ang) * w; val dz = kotlin.math.sin(ang) * w
                            val lean = (q - 1) * 0.08f
                            val v0 = m.vertex(x - dx, y, z - dz, 0f, 1f, 0f, lo)
                            val v1 = m.vertex(x + dx, y, z + dz, 0f, 1f, 0f, lo)
                            val v2 = m.vertex(x + dx * 0.3f + lean, y + hgt, z + dz * 0.3f, 0f, 1f, 0f, hi)
                            val v3 = m.vertex(x - dx * 0.3f + lean, y + hgt * 0.85f, z - dz * 0.3f, 0f, 1f, 0f, hi)
                            // Both windings: grass is seen from either side.
                            m.quad(v0, v1, v2, v3); m.quad(v0, v3, v2, v1)
                        }
                    }
                }
                s += 0.6; k++
            }
        }
    }

    /** Foliage meeting over the road where the canopy closes in ([Canopy]). */
    private fun canopy(d: ChunkData, ms: MeshSet) {
        val step = if (d.level == 0) 3.5 else 7.0
        val p = DoubleArray(3)
        for ((ri, road) in ground.roads.withIndex()) {
            val bb = roadBoxes[ri]
            if (bb[2] < d.e0 || bb[0] > d.e0 + d.size || bb[3] < d.n0 || bb[1] > d.n0 + d.size) continue
            var s = 0.0
            var k = 0
            while (s < road.line.length) {
                val m = ms.room() ?: return
                val t = Canopy.tunnel(road, s)
                if (t > 0.15) {
                    road.line.pointAt(s, p)
                    if (p[0] >= d.e0 && p[0] < d.e0 + d.size && p[1] >= d.n0 && p[1] < d.n0 + d.size) {
                        val lx = -sin(p[2]); val ly = cos(p[2])
                        for (side in 0..1) {
                            val sg = if (side == 0) 1.0 else -1.0
                            val r1 = Noise.rand(k, side, road.seed + 81); val r2 = Noise.rand(k, side, road.seed + 82)
                            if (r1 > 0.35 + 0.65 * t) continue // gaps where the light gets through
                            val lat = sg * road.halfWidth * (0.1 + 0.6 * r2)
                            val e = p[0] + lx * lat; val n = p[1] + ly * lat
                            if (ground.landcover.at(e + lx * sg * 6, n + ly * sg * 6) != com.northwoodsadv.ride.world.Landcover.FOREST) continue
                            val y = road.heightAt(s) - baseH + 7.0 + 3.0 * Noise.rand(k, side, road.seed + 83)
                            val rx = (2.6 + 1.6 * r2) * (0.7 + 0.3 * t)
                            m.blob((e - d.e0).toFloat(), y.toFloat(), (-(n - d.n0)).toFloat(), rx.toFloat(), (1.2 + 0.8 * r1).toFloat(), (rx * 1.2).toFloat(),
                                leafColor(Noise.rand(k, side, road.seed + 84), 0.85 + 0.2 * r1), (s + side).toFloat())
                        }
                    }
                }
                s += step; k++
            }
        }
    }

    companion object {
        /** Extra ribbon lanes beyond the shoulder (m) at detail levels 0 and 1. */
        private val BANK_LANES0 = doubleArrayOf(0.7, 1.6, 2.8, 4.3, 6.2, 8.5)
        private val BANK_LANES1 = doubleArrayOf(1.5, 4.0, 7.5)
        private const val WATER_LANES = 5
        const val BASE = 128.0
        const val GRID = 64
        /** Finer grid (1.33 m) for the chunks right around the rider. */
        const val GRID_NEAR = 96
    }
}
