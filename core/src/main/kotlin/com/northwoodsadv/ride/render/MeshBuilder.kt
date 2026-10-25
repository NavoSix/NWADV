package com.northwoodsadv.ride.render

import com.badlogic.gdx.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Growable vertex/index buffers in the world vertex format:
 * position (3 floats), normal (3 floats), packed colour (1 float). Thread-safe to fill off the
 * render thread; only turning it into a Mesh needs GL.
 */
class MeshBuilder(initialVerts: Int = 1024) {
    var verts = FloatArray(initialVerts * STRIDE); private set
    var idx = ShortArray(initialVerts * 2); private set
    var vertCount = 0; private set
    var idxCount = 0; private set
    var minY = Float.MAX_VALUE; private set
    var maxY = -Float.MAX_VALUE; private set

    val full get() = vertCount > 60_000

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Float): Int {
        if ((vertCount + 1) * STRIDE > verts.size) verts = verts.copyOf(verts.size * 2)
        val o = vertCount * STRIDE
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = z
        verts[o + 3] = nx; verts[o + 4] = ny; verts[o + 5] = nz
        verts[o + 6] = color
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        return vertCount++
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (idxCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[idxCount++] = a.toShort(); idx[idxCount++] = b.toShort(); idx[idxCount++] = c.toShort()
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Flat-shaded triangle (its own vertices, face normal). */
    fun flatTri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Float) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= l; ny /= l; nz /= l
        val a = vertex(ax, ay, az, nx, ny, nz, color)
        val b = vertex(bx, by, bz, nx, ny, nz, color)
        val c = vertex(cx, cy, cz, nx, ny, nz, color)
        tri(a, b, c)
    }

    /** A cone (or cylinder when r1 > 0) along +Y, flat shaded sides. */
    fun cone(x: Float, y: Float, z: Float, r0: Float, r1: Float, h: Float, sides: Int, color: Float, phase: Float = 0f) {
        for (i in 0 until sides) {
            val a0 = phase + i * TWO_PI / sides; val a1 = phase + (i + 1) * TWO_PI / sides
            val x0 = cos(a0); val z0 = sin(a0); val x1 = cos(a1); val z1 = sin(a1)
            if (r1 <= 0f) {
                flatTri(x + x0 * r0, y, z + z0 * r0, x, y + h, z, x + x1 * r0, y, z + z1 * r0, color)
            } else {
                flatTri(x + x0 * r0, y, z + z0 * r0, x + x0 * r1, y + h, z + z0 * r1, x + x1 * r0, y, z + z1 * r0, color)
                flatTri(x + x1 * r0, y, z + z1 * r0, x + x0 * r1, y + h, z + z0 * r1, x + x1 * r1, y + h, z + z1 * r1, color)
            }
        }
    }

    /** Low-poly blob (octahedron stretched), for hardwood crowns and boulders. */
    fun blob(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, color: Float, twist: Float = 0f) {
        val c = cos(twist); val s = sin(twist)
        fun px(dx: Float, dz: Float) = x + dx * c - dz * s
        fun pz(dx: Float, dz: Float) = z + dx * s + dz * c
        val top = floatArrayOf(x, y + ry, z); val bot = floatArrayOf(x, y - ry, z)
        val ring = Array(6) { i ->
            val a = i * TWO_PI / 6
            val dx = cos(a) * rx; val dz = sin(a) * rz
            floatArrayOf(px(dx, dz), y + if (i % 2 == 0) ry * 0.15f else -ry * 0.15f, pz(dx, dz))
        }
        for (i in 0 until 6) {
            val a = ring[i]; val b = ring[(i + 1) % 6]
            flatTri(a[0], a[1], a[2], top[0], top[1], top[2], b[0], b[1], b[2], color)
            flatTri(b[0], b[1], b[2], bot[0], bot[1], bot[2], a[0], a[1], a[2], color)
        }
    }

    /**
     * Rounder, lumpy crown: two rings of [sides] vertices between a top and bottom point, each
     * vertex pushed in or out a little (seeded by [seed]), lighter on top where the sun hits.
     */
    fun crown(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, color: Float, top: Float, seed: Float, sides: Int = 7) {
        fun lump(i: Int, j: Int) = 0.82f + 0.36f * (((sin(seed * 12.9898f + i * 78.233f + j * 37.719f) * 43758.547f) % 1f + 1f) % 1f)
        val rings = arrayOf(0.42f, -0.38f)
        val radius = arrayOf(0.88f, 0.92f)
        val pts = Array(2) { j ->
            Array(sides) { i ->
                val a = seed + (i + 0.5f * j) * TWO_PI / sides
                val k = lump(i, j) * radius[j]
                floatArrayOf(x + cos(a) * rx * k, y + ry * rings[j] * lump(i + 7, j), z + sin(a) * rz * k)
            }
        }
        val tx = x; val ty = y + ry * lump(0, 5); val tz = z
        val bx = x; val by = y - ry * 0.75f; val bz = z
        for (i in 0 until sides) {
            val i1 = (i + 1) % sides
            val a = pts[0][i]; val b = pts[0][i1]; val c = pts[1][i]; val d = pts[1][i1]
            flatTri(a[0], a[1], a[2], tx, ty, tz, b[0], b[1], b[2], top)
            flatTri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], color)
            flatTri(b[0], b[1], b[2], d[0], d[1], d[2], c[0], c[1], c[2], color)
            flatTri(c[0], c[1], c[2], d[0], d[1], d[2], bx, by, bz, color)
        }
    }

    /** Flat triangle wound so it faces away from the point (cx, cy, cz). */
    fun outTri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, qx: Float, qy: Float, qz: Float,
               cx: Float, cy: Float, cz: Float, color: Float) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = qx - ax; val vy = qy - ay; val vz = qz - az
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val ox = (ax + bx + qx) / 3 - cx; val oy = (ay + by + qy) / 3 - cy; val oz = (az + bz + qz) / 3 - cz
        if (nx * ox + ny * oy + nz * oz >= 0) flatTri(ax, ay, az, bx, by, bz, qx, qy, qz, color)
        else flatTri(ax, ay, az, qx, qy, qz, bx, by, bz, color)
    }

    /** Thin parts (plates, panels, glass) seen from both sides. */
    fun twoSidedTri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Float) {
        flatTri(ax, ay, az, bx, by, bz, cx, cy, cz, color)
        flatTri(ax, ay, az, cx, cy, cz, bx, by, bz, color)
    }

    /**
     * A shell lofted along X through [stations] of (x, centre y, half height, half width): each
     * cross-section a rounded rectangle ([square] 0 = ellipse .. 1 = nearly a box), ends capped.
     * Tanks, seats, fenders, crankcases.
     */
    fun loft(stations: Array<FloatArray>, color: Float, sides: Int = 12, square: Float = 0.5f, z: Float = 0f) {
        val e = 1f - 0.8f * square
        fun pt(st: FloatArray, i: Int): FloatArray {
            val a = i * TWO_PI / sides
            val c = cos(a); val sn = sin(a)
            val py = st[1] + st[2] * kotlin.math.sign(sn) * kotlin.math.abs(sn).pow(e)
            val pz = z + st[3] * kotlin.math.sign(c) * kotlin.math.abs(c).pow(e)
            return floatArrayOf(st[0], py, pz)
        }
        for (k in 0 until stations.size - 1) {
            val s0 = stations[k]; val s1 = stations[k + 1]
            val cx = (s0[0] + s1[0]) / 2; val cy = (s0[1] + s1[1]) / 2
            for (i in 0 until sides) {
                val a = pt(s0, i); val b = pt(s0, i + 1); val c = pt(s1, i + 1); val d = pt(s1, i)
                outTri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], cx, cy, z, color)
                outTri(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], cx, cy, z, color)
            }
        }
        for (end in listOf(0, stations.size - 1)) {
            val st = stations[end]
            val inward = if (end == 0) 1f else -1f
            val ix = st[0] + inward * 0.05f
            for (i in 0 until sides) {
                val a = pt(st, i); val b = pt(st, i + 1)
                outTri(st[0], st[1], z, a[0], a[1], a[2], b[0], b[1], b[2], ix, st[1], z, color)
            }
        }
    }

    /**
     * Curved strip around the Z axis through (cx, cy): radius [r] (to the middle of its
     * thickness [t]), from angle [a0] to [a1] (radians, 0 = +X, increasing toward +Y), [hw] half
     * width in Z centred on [z]. Fenders, rims, tyres and knobs.
     */
    fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, hw: Float, t: Float, color: Float, segs: Int = 8, z: Float = 0f) {
        val ri = r - t / 2; val ro = r + t / 2
        for (k in 0 until segs) {
            val b0 = a0 + (a1 - a0) * k / segs; val b1 = a0 + (a1 - a0) * (k + 1) / segs
            val c0 = cos(b0); val s0 = sin(b0); val c1 = cos(b1); val s1 = sin(b1)
            val mid = (b0 + b1) / 2
            val mx = cx + cos(mid) * r; val my = cy + sin(mid) * r
            // Corners: o/i = outer/inner radius, 0/1 = start/end angle, n/f = -z/+z side.
            val o0 = floatArrayOf(cx + c0 * ro, cy + s0 * ro); val o1 = floatArrayOf(cx + c1 * ro, cy + s1 * ro)
            val i0 = floatArrayOf(cx + c0 * ri, cy + s0 * ri); val i1 = floatArrayOf(cx + c1 * ri, cy + s1 * ri)
            val zn = z - hw; val zf = z + hw
            fun q(p: FloatArray, pz: Float, qq: FloatArray, qz: Float, w: FloatArray, wz: Float, v: FloatArray, vz: Float) {
                outTri(p[0], p[1], pz, qq[0], qq[1], qz, w[0], w[1], wz, mx, my, z, color)
                outTri(p[0], p[1], pz, w[0], w[1], wz, v[0], v[1], vz, mx, my, z, color)
            }
            q(o0, zn, o1, zn, o1, zf, o0, zf)       // outer face
            q(i0, zn, i1, zn, i1, zf, i0, zf)       // inner face
            q(o0, zn, o1, zn, i1, zn, i0, zn)       // -z side
            q(o0, zf, o1, zf, i1, zf, i0, zf)       // +z side
            if (k == 0) q(o0, zn, o0, zf, i0, zf, i0, zn)
            if (k == segs - 1) q(o1, zn, o1, zf, i1, zf, i1, zn)
        }
    }

    /**
     * Spoked dual-sport wheel around the Z axis at the origin: knobby tyre of outer radius [r]
     * and section height [tyreH], rim, [spokes] crossed spokes and a hub.
     */
    fun spokedWheel(r: Float, tyreH: Float, halfW: Float, spokes: Int, tyre: Float, knob: Float, rim: Float, spoke: Float, hub: Float, knobs: Int = 26) {
        val segs = if (knobs > 24) 32 else 18
        arc(0f, 0f, r - tyreH / 2 - 0.004f, 0f, TWO_PI, halfW, tyreH - 0.008f, tyre, segs)
        // Knobs in two staggered rows plus a centre row.
        val n = knobs
        for (k in 0 until n) {
            val a = k * TWO_PI / n
            val zOff = if (k % 2 == 0) halfW * 0.5f else -halfW * 0.5f
            arc(0f, 0f, r - 0.006f, a, a + 0.11f, halfW * 0.38f, 0.014f, knob, 1, zOff)
            if (k % 2 == 1) arc(0f, 0f, r - 0.006f, a + 0.12f, a + 0.2f, halfW * 0.22f, 0.014f, knob, 1, 0f)
        }
        val rimR = r - tyreH - 0.008f
        arc(0f, 0f, rimR, 0f, TWO_PI, 0.02f, 0.022f, rim, segs)
        for (k in 0 until spokes) {
            val a = k * TWO_PI / spokes
            val side = if (k % 2 == 0) 1f else -1f
            // Tangential lacing: each spoke leaves the hub flange ahead of where it meets the rim.
            val ha = a + side * 0.5f
            beam(cos(ha) * 0.045f, sin(ha) * 0.045f, side * 0.045f, cos(a) * (rimR - 0.012f), sin(a) * (rimR - 0.012f), side * 0.008f, 0.0028f, spoke)
        }
        tube(0f, 0f, -0.065f, 0f, 0f, 0.065f, 0.042f, 0.042f, if (knobs > 24) 14 else 8, hub, hub)
    }

    /** Axis-aligned box centred at (x, y, z). */
    fun box(x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float, color: Float) {
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
        )
        for (n in faces) {
            // Two tangent axes for this face.
            val t = if (n[1] != 0f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val b = floatArrayOf(n[1] * t[2] - n[2] * t[1], n[2] * t[0] - n[0] * t[2], n[0] * t[1] - n[1] * t[0])
            val cx = x + n[0] * hx; val cy = y + n[1] * hy; val cz = z + n[2] * hz
            val tx = t[0] * hx; val ty = t[1] * hy; val tz = t[2] * hz
            val bx = b[0] * hx; val by = b[1] * hy; val bz = b[2] * hz
            val v0 = vertex(cx - tx - bx, cy - ty - by, cz - tz - bz, n[0], n[1], n[2], color)
            val v1 = vertex(cx + tx - bx, cy + ty - by, cz + tz - bz, n[0], n[1], n[2], color)
            val v2 = vertex(cx + tx + bx, cy + ty + by, cz + tz + bz, n[0], n[1], n[2], color)
            val v3 = vertex(cx - tx + bx, cy - ty + by, cz - tz + bz, n[0], n[1], n[2], color)
            // Wind counter-clockwise when seen from outside.
            val cross = (t[1] * b[2] - t[2] * b[1]) * n[0] + (t[2] * b[0] - t[0] * b[2]) * n[1] + (t[0] * b[1] - t[1] * b[0]) * n[2]
            if (cross > 0) quad(v0, v1, v2, v3) else quad(v0, v3, v2, v1)
        }
    }

    /** Wheel: cylinder along the Z axis centred at (x, y, z), spokes shaded so spin is visible. */
    fun wheel(x: Float, y: Float, z: Float, r: Float, halfWidth: Float, sides: Int, side: Float, tread: Float) {
        for (i in 0 until sides) {
            val a0 = i * TWO_PI / sides; val a1 = (i + 1) * TWO_PI / sides
            val x0 = cos(a0) * r; val y0 = sin(a0) * r; val x1 = cos(a1) * r; val y1 = sin(a1) * r
            val nm = (a0 + a1) / 2
            val nx = cos(nm); val ny = sin(nm)
            val q0 = vertex(x + x0, y + y0, z - halfWidth, nx, ny, 0f, tread)
            val q1 = vertex(x + x0, y + y0, z + halfWidth, nx, ny, 0f, tread)
            val q2 = vertex(x + x1, y + y1, z + halfWidth, nx, ny, 0f, tread)
            val q3 = vertex(x + x1, y + y1, z - halfWidth, nx, ny, 0f, tread)
            quad(q0, q1, q2, q3)
            val sc = if (i % 4 == 0) tread else side
            flatTri(x, y, z + halfWidth, x + x1, y + y1, z + halfWidth, x + x0, y + y0, z + halfWidth, sc)
            flatTri(x, y, z - halfWidth, x + x0, y + y0, z - halfWidth, x + x1, y + y1, z - halfWidth, sc)
        }
    }

    /** Round bar (log) between two points, flat-shaded, with lighter cut ends. */
    fun tube(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r0: Float, r1: Float, sides: Int, color: Float, endColor: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-5f)
        val fx = dx / len; val fy = dy / len; val fz = dz / len
        val (ax, ay, az) = if (kotlin.math.abs(fy) < 0.9f) Triple(0f, 1f, 0f) else Triple(1f, 0f, 0f)
        var ux = fy * az - fz * ay; var uy = fz * ax - fx * az; var uz = fx * ay - fy * ax
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val vx = fy * uz - fz * uy; val vy = fz * ux - fx * uz; val vz = fx * uy - fy * ux
        for (i in 0 until sides) {
            val a0 = i * TWO_PI / sides; val a1 = (i + 1) * TWO_PI / sides
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val p0x = x0 + (ux * c0 + vx * s0) * r0; val p0y = y0 + (uy * c0 + vy * s0) * r0; val p0z = z0 + (uz * c0 + vz * s0) * r0
            val p1x = x0 + (ux * c1 + vx * s1) * r0; val p1y = y0 + (uy * c1 + vy * s1) * r0; val p1z = z0 + (uz * c1 + vz * s1) * r0
            val q0x = x1 + (ux * c0 + vx * s0) * r1; val q0y = y1 + (uy * c0 + vy * s0) * r1; val q0z = z1 + (uz * c0 + vz * s0) * r1
            val q1x = x1 + (ux * c1 + vx * s1) * r1; val q1y = y1 + (uy * c1 + vy * s1) * r1; val q1z = z1 + (uz * c1 + vz * s1) * r1
            flatTri(p0x, p0y, p0z, q0x, q0y, q0z, p1x, p1y, p1z, color)
            flatTri(p1x, p1y, p1z, q0x, q0y, q0z, q1x, q1y, q1z, color)
            flatTri(x0, y0, z0, p1x, p1y, p1z, p0x, p0y, p0z, endColor)
            flatTri(x1, y1, z1, q0x, q0y, q0z, q1x, q1y, q1z, endColor)
        }
    }

    /** A square-section bar between two points. */
    fun beam(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, t: Float, color: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-5f)
        val fx = dx / len; val fy = dy / len; val fz = dz / len
        // A vector not parallel to the beam.
        val (ax, ay, az) = if (kotlin.math.abs(fy) < 0.9f) Triple(0f, 1f, 0f) else Triple(1f, 0f, 0f)
        var ux = fy * az - fz * ay; var uy = fz * ax - fx * az; var uz = fx * ay - fy * ax
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux = ux / ul * t; uy = uy / ul * t; uz = uz / ul * t
        val vx = (fy * uz - fz * uy); val vy = (fz * ux - fx * uz); val vz = (fx * uy - fy * ux)
        val c = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        for (k in 0 until 4) {
            val a = c[k]; val b = c[(k + 1) % 4]
            val pax = x0 + ux * a[0] + vx * a[1]; val pay = y0 + uy * a[0] + vy * a[1]; val paz = z0 + uz * a[0] + vz * a[1]
            val pbx = x0 + ux * b[0] + vx * b[1]; val pby = y0 + uy * b[0] + vy * b[1]; val pbz = z0 + uz * b[0] + vz * b[1]
            flatTri(pax, pay, paz, pbx, pby, pbz, pbx + dx, pby + dy, pbz + dz, color)
            flatTri(pax, pay, paz, pbx + dx, pby + dy, pbz + dz, pax + dx, pay + dy, paz + dz, color)
        }
    }

    fun clear() { vertCount = 0; idxCount = 0; minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE }

    companion object {
        const val STRIDE = 7
        const val TWO_PI = (Math.PI * 2).toFloat()
        fun color(rgb: Int, alpha: Float = 1f) = Color.toFloatBits((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, (alpha * 255).toInt())
        fun color(r: Float, g: Float, b: Float, a: Float = 1f) = Color.toFloatBits(r, g, b, a)
    }
}

/**
 * Several [MeshBuilder]s for one batch of geometry that may outgrow 16-bit indices (dense
 * forest chunks). [room] hands out the current builder, starting a new one when it is nearly
 * full, or null once [maxParts] are full.
 */
class MeshSet(private val initialVerts: Int = 8192, private val maxParts: Int = 4) {
    val parts = ArrayList<MeshBuilder>()

    fun room(verts: Int = 1500): MeshBuilder? {
        val c = parts.lastOrNull()
        if (c != null && c.vertCount + verts <= LIMIT) return c
        if (parts.size >= maxParts) return null
        return MeshBuilder(initialVerts).also { parts += it }
    }

    val vertCount get() = parts.sumOf { it.vertCount }

    companion object { const val LIMIT = 60_000 }
}
