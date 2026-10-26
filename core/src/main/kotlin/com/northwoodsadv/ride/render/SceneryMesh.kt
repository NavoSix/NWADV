package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.Noise
import com.northwoodsadv.ride.world.Prop
import com.northwoodsadv.ride.world.PropKind
import com.northwoodsadv.ride.world.Run
import com.northwoodsadv.ride.world.RunKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry for [com.northwoodsadv.ride.world.Scenery] in one chunk: buildings, walls, fences, rails
 * and signs. Close chunks get the full detail (windows, posts, lettering); the next ring keeps
 * only what reads from a distance (buildings, walls, rails, big rocks). Not thread-safe: one per
 * chunk build.
 */
internal class SceneryMesh(private val ground: Ground, private val baseH: Double) {
    /** A prop's local frame: u along its heading, v to its left, y up from [gy]. */
    private inner class Frame {
        var px = 0.0; var py = 0.0; var gy = 0.0; var c = 1.0; var s = 0.0; var e0 = 0.0; var n0 = 0.0
        fun set(x: Double, y: Double, groundY: Double, heading: Double, ce0: Double, cn0: Double) {
            px = x; py = y; gy = groundY; c = cos(heading); s = sin(heading); e0 = ce0; n0 = cn0
        }
        fun rx(u: Double, v: Double) = (px + u * c - v * s - e0).toFloat()
        fun rz(u: Double, v: Double) = (-(py + u * s + v * c - n0)).toFloat()
        fun ry(y: Double) = (gy + y - baseH).toFloat()
    }

    private val f = Frame()

    // --- primitives in the prop frame ----------------------------------------------------------

    /** Quad (corners in order) facing away from the reference point (ru, ry, rv). */
    private fun quad(m: MeshBuilder, a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, ru: Double, ry: Double, rv: Double, col: Float) {
        val qx = f.rx(ru, rv); val qy = f.ry(ry); val qz = f.rz(ru, rv)
        m.outTri(f.rx(a[0], a[2]), f.ry(a[1]), f.rz(a[0], a[2]), f.rx(b[0], b[2]), f.ry(b[1]), f.rz(b[0], b[2]), f.rx(c[0], c[2]), f.ry(c[1]), f.rz(c[0], c[2]), qx, qy, qz, col)
        m.outTri(f.rx(a[0], a[2]), f.ry(a[1]), f.rz(a[0], a[2]), f.rx(c[0], c[2]), f.ry(c[1]), f.rz(c[0], c[2]), f.rx(d[0], d[2]), f.ry(d[1]), f.rz(d[0], d[2]), qx, qy, qz, col)
    }

    private fun tri(m: MeshBuilder, a: DoubleArray, b: DoubleArray, c: DoubleArray, ru: Double, ry: Double, rv: Double, col: Float) {
        m.outTri(f.rx(a[0], a[2]), f.ry(a[1]), f.rz(a[0], a[2]), f.rx(b[0], b[2]), f.ry(b[1]), f.rz(b[0], b[2]), f.rx(c[0], c[2]), f.ry(c[1]), f.rz(c[0], c[2]), f.rx(ru, rv), f.ry(ry), f.rz(ru, rv), col)
    }

    private fun pt(u: Double, y: Double, v: Double) = doubleArrayOf(u, y, v)

    /** Box centred at (u, y, v) with half sizes, in the frame. */
    private fun box(m: MeshBuilder, u: Double, y: Double, v: Double, hu: Double, hy: Double, hv: Double, col: Float) {
        val c = Array(8) { pt(u + if (it and 1 != 0) hu else -hu, y + if (it and 2 != 0) hy else -hy, v + if (it and 4 != 0) hv else -hv) }
        val faces = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
        for (fc in faces) quad(m, c[fc[0]], c[fc[1]], c[fc[2]], c[fc[3]], u, y, v, col)
    }

    /** A flat panel in the u = [u] plane facing +u (or -u when [back]), spanning v0..v1, y0..y1. */
    private fun panel(m: MeshBuilder, u: Double, v0: Double, v1: Double, y0: Double, y1: Double, col: Float, back: Boolean = false) {
        val ref = if (back) u + 1 else u - 1
        quad(m, pt(u, y0, v0), pt(u, y0, v1), pt(u, y1, v1), pt(u, y1, v0), ref, (y0 + y1) / 2, (v0 + v1) / 2, col)
    }

    /** Same, in the v = [v] plane facing +v (or -v), spanning u0..u1. */
    private fun panelV(m: MeshBuilder, v: Double, u0: Double, u1: Double, y0: Double, y1: Double, col: Float, back: Boolean = false) {
        val ref = if (back) v + 1 else v - 1
        quad(m, pt(u0, y0, v), pt(u1, y0, v), pt(u1, y1, v), pt(u0, y1, v), (u0 + u1) / 2, (y0 + y1) / 2, ref, col)
    }

    /** Lettering on the u = [u] face (facing +u), centred on v = [cv], top at [top], [px] metres per pixel. */
    private fun text(m: MeshBuilder, lines: List<String>, u: Double, cv: Double, top: Double, px: Double, col: Float, lineGap: Double = 2.0) {
        var y = top
        for (line in lines) {
            val w = PixelFont.width(line) * px
            val v0 = cv - w / 2
            val base = y - PixelFont.H * px
            // Text reads left to right along +v for someone looking at the face.
            PixelFont.runs(line) { x0, y0, x1, y1 -> panel(m, u, v0 + x0 * px, v0 + x1 * px, base + y0 * px, base + y1 * px, col) }
            y -= (PixelFont.H + lineGap) * px
        }
    }

    // --- chunk -------------------------------------------------------------------------------

    fun build(d: ChunkData, ms: MeshSet, scenery: com.northwoodsadv.ride.world.Scenery) {
        val near = d.level == 0
        scenery.forEachProp(d.e0, d.n0, d.e0 + d.size, d.n0 + d.size) { p ->
            if (!near && !farVisible(p.kind)) return@forEachProp
            val m = ms.room(3000) ?: return@forEachProp
            prop(m, p, d, near)
        }
        for (ri in scenery.runsNear(d.e0, d.n0, d.e0 + d.size, d.n0 + d.size)) {
            val r = scenery.runs[ri]
            if (!near && (r.kind == RunKind.RAIL_FENCE || r.kind == RunKind.BOARD_FENCE)) continue
            val m = ms.room(4000) ?: return
            run(m, r, d, near)
        }
    }

    private fun farVisible(k: PropKind) = when (k) {
        PropKind.HOUSE, PropKind.BARN, PropKind.SILO, PropKind.SHED, PropKind.CHURCH, PropKind.STORE,
        PropKind.ERRATIC, PropKind.TOWER, PropKind.MONUMENT -> true
        else -> false
    }

    private fun groundAt(x: Double, y: Double) = ground.height(x, y, false)

    private fun prop(m: MeshBuilder, p: Prop, d: ChunkData, near: Boolean) {
        when (p.kind) {
            PropKind.HOUSE, PropKind.SHED, PropKind.BARN, PropKind.CHURCH, PropKind.STORE -> building(m, p, d, near)
            PropKind.SILO -> silo(m, p, d)
            PropKind.ERRATIC -> erratic(m, p, d)
            PropKind.MONUMENT -> monument(m, p, d)
            PropKind.TOWER -> tower(m, p, d)
            PropKind.HAY_BALE -> bale(m, p, d)
            PropKind.MAILBOX -> mailbox(m, p, d)
            PropKind.STOP_LINE -> stopLine(m, p, d)
            else -> sign(m, p, d)
        }
    }

    // --- buildings ---------------------------------------------------------------------------

    private fun building(m: MeshBuilder, p: Prop, d: ChunkData, near: Boolean) {
        // Sit on the highest corner, with a foundation down to the lowest.
        val c = cos(p.heading); val s = sin(p.heading)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in 0 until 4) {
            val u = if (k % 2 == 0) p.hx else -p.hx; val v = if (k < 2) p.hz else -p.hz
            val h = groundAt(p.x + u * c - v * s, p.y + u * s + v * c)
            lo = min(lo, h); hi = max(hi, h)
        }
        f.set(p.x, p.y, hi + 0.35, p.heading, d.e0, d.n0)
        val wall = Palette.shade(p.color, 0.92 + 0.12 * Noise.rand(p.seed, 1, 21))
        val roofCol = MeshBuilder.color(roofColor(p))
        val trim = MeshBuilder.color(0xf4f2ec)
        val dark = MeshBuilder.color(0x1e2428)
        val foundation = MeshBuilder.color(0x7a766e)
        val drop = hi + 0.35 - lo + 0.3
        box(m, 0.0, -drop / 2, 0.0, p.hx + 0.05, drop / 2, p.hz + 0.05, foundation)
        val h = p.h
        box(m, 0.0, h / 2, 0.0, p.hx, h / 2, p.hz, wall)
        // Ridge along the longer side.
        val alongU = p.hx >= p.hz
        val span = if (alongU) p.hz else p.hx      // half span across the ridge
        val len = if (alongU) p.hx else p.hz       // half length along it
        val o = 0.45                               // overhang
        when (p.roof) {
            2 -> { // flat with a parapet
                box(m, 0.0, h + 0.25, 0.0, p.hx + 0.1, 0.25, p.hz + 0.1, MeshBuilder.color(0x4a4a4a))
            }
            1 -> gambrel(m, alongU, span, len, h, o, wall, roofCol)
            3 -> hip(m, alongU, span, len, h, o, roofCol)
            else -> gable(m, alongU, span, len, h, o, span * if (p.kind == PropKind.CHURCH) 0.8 else 0.62, wall, roofCol)
        }
        if (p.kind == PropKind.CHURCH) steeple(m, p, h, alongU, len, near)
        if (p.kind == PropKind.HOUSE && near && Noise.rand(p.seed, 2, 21) < 0.8) {
            // Brick chimney through the roof.
            val cu = if (alongU) len * 0.55 else 0.0; val cv = if (alongU) 0.0 else len * 0.55
            box(m, cu, h + span * 0.62 * 0.6, cv, 0.4, span * 0.62 * 0.6 + 0.6, 0.4, MeshBuilder.color(0x8a4a3a))
        }
        if (!near) return
        // Windows and a door on the front (+u) and the long sides.
        val winH = 1.3; val winW = 0.55
        val floors = if (p.kind == PropKind.SHED || p.kind == PropKind.BARN) 1 else max(1, (h / 2.8).toInt())
        fun windowsU(u: Double, back: Boolean) {
            val n = max(1, ((p.hz * 2 - 2) / 2.6).toInt())
            for (fl in 0 until floors) for (k in 0 until n) {
                val v = -p.hz + (k + 0.5) * p.hz * 2 / n
                val y0 = 0.9 + fl * 2.8
                if (!back && fl == 0 && k == n / 2 && p.kind != PropKind.BARN) {
                    panel(m, u + 0.02, v - 0.55, v + 0.55, 0.0, 2.2, trim); panel(m, u + 0.03, v - 0.45, v + 0.45, 0.0, 2.1, MeshBuilder.color(0x3a2a22)); continue
                }
                if (p.kind == PropKind.BARN) continue
                panel(m, if (back) u - 0.02 else u + 0.02, v - winW - 0.08, v + winW + 0.08, y0 - 0.08, y0 + winH + 0.08, trim, back)
                panel(m, if (back) u - 0.03 else u + 0.03, v - winW, v + winW, y0, y0 + winH, dark, back)
            }
        }
        fun windowsV(v: Double, back: Boolean) {
            if (p.kind == PropKind.BARN) return
            val n = max(1, ((p.hx * 2 - 2) / 2.6).toInt())
            for (fl in 0 until floors) for (k in 0 until n) {
                val u = -p.hx + (k + 0.5) * p.hx * 2 / n
                val y0 = 0.9 + fl * 2.8
                panelV(m, if (back) v - 0.02 else v + 0.02, u - winW - 0.08, u + winW + 0.08, y0 - 0.08, y0 + winH + 0.08, trim, back)
                panelV(m, if (back) v - 0.03 else v + 0.03, u - winW, u + winW, y0, y0 + winH, dark, back)
            }
        }
        if (p.kind == PropKind.BARN) {
            // Big sliding door on the gable end facing the road, white X trim.
            val dh = min(h - 0.4, 4.2); val dw = min(p.hz * 0.6, 2.6)
            panel(m, p.hx + 0.02, -dw, dw, 0.0, dh, Palette.shade(p.color, 0.75))
            panel(m, p.hx + 0.03, -dw, dw, dh - 0.15, dh, trim)
            panel(m, p.hx + 0.03, -dw, -dw + 0.15, 0.0, dh, trim); panel(m, p.hx + 0.03, dw - 0.15, dw, 0.0, dh, trim)
        } else if (p.kind == PropKind.CHURCH) {
            // Double doors under the steeple (the tall windows go down the sides).
            val front = if (alongU) p.hx else p.hz
            if (alongU) { panel(m, front + 0.02, -0.9, 0.9, 0.0, 2.8, trim); panel(m, front + 0.03, -0.75, 0.75, 0.0, 2.6, MeshBuilder.color(0x3a2a22)) }
            else { panelV(m, front + 0.02, -0.9, 0.9, 0.0, 2.8, trim); panelV(m, front + 0.03, -0.75, 0.75, 0.0, 2.6, MeshBuilder.color(0x3a2a22)) }
        } else {
            windowsU(p.hx, false)
            windowsU(-p.hx, true)
            windowsV(p.hz, false)
            windowsV(-p.hz, true)
        }
        // A name across the front of stores and churches.
        if (p.text.isNotEmpty() && (p.kind == PropKind.STORE)) {
            val name = PixelFont.signName(p.text[0]).take(24)
            val px = min(0.09, (p.hz * 1.6) / max(1, PixelFont.width(name)))
            val bw = PixelFont.width(name) * px / 2 + 0.3
            panel(m, p.hx + 0.04, -bw, bw, h - 1.0, h - 0.1, MeshBuilder.color(0x2a3a2a))
            text(m, listOf(name), p.hx + 0.05, 0.0, h - 0.25, px, MeshBuilder.color(0xf0e8c8))
        }
    }

    private fun roofColor(p: Prop): Int {
        val r = Noise.rand(p.seed, 9, 21)
        return when (p.kind) {
            PropKind.BARN -> if (r < 0.5) 0x5a5e62 else 0x6a2a24
            PropKind.CHURCH -> 0x3a3a3c
            else -> when { r < 0.55 -> 0x3a3a3c; r < 0.7 -> 0x2e4a34; r < 0.82 -> 0x5a2a24; r < 0.92 -> 0x4a3a30; else -> 0x6a6e72 }
        }
    }

    /** Point in the frame from (along ridge, y, across) coordinates. */
    private fun rp(alongU: Boolean, a: Double, y: Double, x: Double) = if (alongU) pt(a, y, x) else pt(x, y, a)

    private fun gable(m: MeshBuilder, alongU: Boolean, span: Double, len: Double, h: Double, o: Double, rise: Double, wall: Float, roof: Float) {
        val top = h + rise
        for (side in intArrayOf(1, -1)) {
            // Slope from the eave (overhanging) to the ridge; reference point under the roof.
            quad(m, rp(alongU, -len - o * 0.6, h - o * rise / span, side * (span + o)), rp(alongU, len + o * 0.6, h - o * rise / span, side * (span + o)),
                rp(alongU, len + o * 0.6, top, 0.0), rp(alongU, -len - o * 0.6, top, 0.0), 0.0, h, 0.0, roof)
            // Gable triangle.
            val ref = rp(alongU, 0.0, h, 0.0)
            tri(m, rp(alongU, side * len, h, -span), rp(alongU, side * len, h, span), rp(alongU, side * len, top, 0.0), ref[0], ref[1], ref[2], wall)
        }
    }

    private fun hip(m: MeshBuilder, alongU: Boolean, span: Double, len: Double, h: Double, o: Double, roof: Float) {
        val rise = span * 0.55
        val top = h + rise
        val r0 = max(0.0, len - span)
        for (side in intArrayOf(1, -1)) {
            quad(m, rp(alongU, -len - o, h - 0.2, side * (span + o)), rp(alongU, len + o, h - 0.2, side * (span + o)), rp(alongU, r0, top, 0.0), rp(alongU, -r0, top, 0.0), 0.0, h, 0.0, roof)
            tri(m, rp(alongU, side * (len + o), h - 0.2, -span - o), rp(alongU, side * (len + o), h - 0.2, span + o), rp(alongU, side * r0, top, 0.0), 0.0, h, 0.0, roof)
        }
    }

    private fun gambrel(m: MeshBuilder, alongU: Boolean, span: Double, len: Double, h: Double, o: Double, wall: Float, roof: Float) {
        // Lower steep pitch to the knee, upper shallow pitch to the ridge.
        val knee = span * 0.62; val kneeY = h + span * 0.75; val top = h + span * 1.05
        for (side in intArrayOf(1, -1)) {
            quad(m, rp(alongU, -len - 0.3, h - 0.2, side * (span + o * 0.6)), rp(alongU, len + 0.3, h - 0.2, side * (span + o * 0.6)),
                rp(alongU, len + 0.3, kneeY, side * knee), rp(alongU, -len - 0.3, kneeY, side * knee), 0.0, h, 0.0, roof)
            quad(m, rp(alongU, -len - 0.3, kneeY, side * knee), rp(alongU, len + 0.3, kneeY, side * knee),
                rp(alongU, len + 0.3, top, 0.0), rp(alongU, -len - 0.3, top, 0.0), 0.0, h, 0.0, roof)
            val ref = rp(alongU, 0.0, h, 0.0)
            for (end in intArrayOf(1, -1)) {
                val a = side * len
                tri(m, rp(alongU, a, h, end * span), rp(alongU, a, kneeY, end * knee), rp(alongU, a, h, 0.0), ref[0], ref[1], ref[2], wall)
                tri(m, rp(alongU, a, kneeY, end * knee), rp(alongU, a, top, 0.0), rp(alongU, a, h, 0.0), ref[0], ref[1], ref[2], wall)
            }
        }
    }

    /** White New England steeple at the front gable end: square tower, belfry, spire. */
    private fun steeple(m: MeshBuilder, p: Prop, h: Double, alongU: Boolean, len: Double, near: Boolean) {
        val white = MeshBuilder.color(0xf6f4ee)
        val end = if (alongU) pt(len - 1.4, 0.0, 0.0) else pt(0.0, 0.0, len - 1.4)
        val w = 1.4
        box(m, end[0], (h + 3.5) / 2, end[2], w, (h + 3.5) / 2, w, white)
        box(m, end[0], h + 4.6, end[2], w * 0.8, 1.1, w * 0.8, if (near) MeshBuilder.color(0xd8d4ca) else white)
        box(m, end[0], h + 5.8, end[2], w * 0.9, 0.12, w * 0.9, white)
        // Spire (four-sided).
        val base = h + 5.9; val tip = base + 7.5
        val q = w * 0.75
        val corners = arrayOf(pt(end[0] + q, base, end[2] + q), pt(end[0] - q, base, end[2] + q), pt(end[0] - q, base, end[2] - q), pt(end[0] + q, base, end[2] - q))
        for (k in 0 until 4) tri(m, corners[k], corners[(k + 1) % 4], pt(end[0], tip, end[2]), end[0], base, end[2], white)
        if (near) for (side in intArrayOf(1, -1)) {
            // Tall arched windows down the sides.
            val n = max(2, (len * 2 / 3.2).toInt())
            for (k in 0 until n) {
                val a = -len + (k + 0.5) * len * 2 / n
                if (alongU) panelV(m, side * (p.hz + 0.03), a - 0.45, a + 0.45, 1.4, 4.6, MeshBuilder.color(0x2a3036), side < 0)
                else panel(m, side * (p.hx + 0.03), a - 0.45, a + 0.45, 1.4, 4.6, MeshBuilder.color(0x2a3036), side < 0)
            }
        }
    }

    private fun silo(m: MeshBuilder, p: Prop, d: ChunkData) {
        val gy = groundAt(p.x, p.y)
        val x = (p.x - d.e0).toFloat(); val z = (-(p.y - d.n0)).toFloat(); val y = (gy - baseH - 0.3).toFloat()
        val r = p.hx.toFloat()
        m.cone(x, y, z, r, r, p.h.toFloat(), 12, Palette.shade(p.color, 1.0))
        for (k in 1..3) m.cone(x, y + p.h.toFloat() * k / 4, z, r + 0.05f, r + 0.05f, 0.15f, 12, MeshBuilder.color(0x5a5650))
        m.blob(x, y + p.h.toFloat(), z, r * 1.02f, r * 0.7f, r * 1.02f, MeshBuilder.color(0x8a8e92))
    }

    // --- rocks, monuments, towers ------------------------------------------------------------

    private fun erratic(m: MeshBuilder, p: Prop, d: ChunkData) {
        val gy = groundAt(p.x, p.y)
        val x = (p.x - d.e0).toFloat(); val z = (-(p.y - d.n0)).toFloat(); val y = (gy - baseH).toFloat()
        val col = Palette.shade(0x7e7c74, 0.85 + 0.2 * Noise.rand(p.seed, 1, 31))
        val h = p.h.toFloat(); val rx = p.hx.toFloat(); val rz = p.hz.toFloat()
        val tw = p.heading.toFloat()
        // A main mass sunk into the ground and a couple of lumps, lichen-dark on one side.
        m.crown(x, y + h * 0.42f, z, rx, h * 0.62f, rz, col, Palette.shade(0x8e8c84, 0.95), (p.seed and 1023) * 0.01f, 9)
        m.crown(x + cos(tw) * rx * 0.45f, y + h * 0.3f, z + sin(tw) * rz * 0.45f, rx * 0.65f, h * 0.45f, rz * 0.7f,
            Palette.shade(0x6e6c62, 0.95), col, (p.seed and 1023) * 0.02f + 1f, 7)
        m.blob(x - cos(tw) * rx * 0.6f, y + h * 0.12f, z - sin(tw) * rz * 0.5f, rx * 0.35f, h * 0.18f, rz * 0.3f, Palette.shade(0x7a786e, 1.0), tw)
    }

    private fun monument(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        val granite = MeshBuilder.color(0xa8a6a0)
        box(m, 0.0, 0.2, 0.0, 1.1, 0.4, 1.1, MeshBuilder.color(0x8a8882))
        if (p.h > 4) {
            val base = 0.6; val tip = p.h
            val q = 0.55; val t = 0.28
            val b = arrayOf(pt(q, base, q), pt(-q, base, q), pt(-q, base, -q), pt(q, base, -q))
            val tp = arrayOf(pt(t, tip - 0.6, t), pt(-t, tip - 0.6, t), pt(-t, tip - 0.6, -t), pt(t, tip - 0.6, -t))
            for (k in 0 until 4) {
                quad(m, b[k], b[(k + 1) % 4], tp[(k + 1) % 4], tp[k], 0.0, (base + tip) / 2, 0.0, granite)
                tri(m, tp[k], tp[(k + 1) % 4], pt(0.0, tip, 0.0), 0.0, tip - 1, 0.0, granite)
            }
        } else box(m, 0.0, 0.6 + p.h / 2, 0.0, 0.18, p.h / 2, 0.7, granite)
        if (p.text.isNotEmpty()) {
            val line = PixelFont.signName(p.text[0]).take(16)
            val px = min(0.05, 1.2 / max(1, PixelFont.width(line)))
            text(m, listOf(line), 1.12, 0.0, 0.5, px, MeshBuilder.color(0x2a2a28))
        }
    }

    private fun tower(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        val steel = MeshBuilder.color(0x8a8e92)
        val h = p.h; val b = p.hx; val t = 1.2
        fun leg(su: Double, sv: Double, y: Double) = pt(su * (b + (t - b) * y / h), y, sv * (b + (t - b) * y / h))
        fun beam(a: DoubleArray, c: DoubleArray, w: Float) = m.beam(f.rx(a[0], a[2]), f.ry(a[1]), f.rz(a[0], a[2]), f.rx(c[0], c[2]), f.ry(c[1]), f.rz(c[0], c[2]), w, steel)
        val sides = arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(-1.0, 1.0), doubleArrayOf(-1.0, -1.0), doubleArrayOf(1.0, -1.0))
        for (k in 0 until 4) {
            val a = sides[k]; val c = sides[(k + 1) % 4]
            beam(leg(a[0], a[1], 0.0), leg(a[0], a[1], h), 0.09f)
            var y = 0.0
            while (y < h - 1) {
                val y1 = min(h, y + 3.0)
                beam(leg(a[0], a[1], y), leg(c[0], c[1], y1), 0.04f)
                beam(leg(c[0], c[1], y), leg(a[0], a[1], y1), 0.04f)
                y = y1
            }
        }
        // The cab on top, with windows all round and a pyramid roof.
        box(m, 0.0, h + 1.2, 0.0, t + 0.2, 1.2, t + 0.2, MeshBuilder.color(0x5a4a3a))
        for (k in 0 until 4) {
            val a = sides[k]; val c = sides[(k + 1) % 4]
            tri(m, pt(a[0] * (t + 0.5), h + 2.4, a[1] * (t + 0.5)), pt(c[0] * (t + 0.5), h + 2.4, c[1] * (t + 0.5)), pt(0.0, h + 3.6, 0.0), 0.0, h + 2.0, 0.0, MeshBuilder.color(0x3a3a3c))
        }
        panel(m, t + 0.21, -t, t, h + 0.8, h + 2.0, MeshBuilder.color(0x2a3036))
        panel(m, -t - 0.21, -t, t, h + 0.8, h + 2.0, MeshBuilder.color(0x2a3036), back = true)
    }

    // --- small props ------------------------------------------------------------------------

    private fun bale(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        val wrapped = Noise.rand(p.seed, 1, 41) < 0.3
        val col = if (wrapped) MeshBuilder.color(0xe8e8e0) else Palette.shade(0xc8a860, 0.9 + 0.15 * Noise.rand(p.seed, 2, 41))
        val r = p.h / 2
        m.tube(f.rx(-0.7, 0.0), f.ry(r - 0.05), f.rz(-0.7, 0.0), f.rx(0.7, 0.0), f.ry(r - 0.05), f.rz(0.7, 0.0), r.toFloat(), r.toFloat(), 10, col,
            if (wrapped) col else Palette.shade(0xb09850, 1.0))
    }

    private fun mailbox(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        val post = MeshBuilder.color(0x5a4a3a)
        m.beam(f.rx(0.0, 0.0), f.ry(-0.1), f.rz(0.0, 0.0), f.rx(0.0, 0.0), f.ry(1.0), f.rz(0.0, 0.0), 0.05f, post)
        box(m, 0.0, 1.12, 0.0, 0.25, 0.12, 0.11, MeshBuilder.color(p.color))
        box(m, -0.05, 1.3, -0.13, 0.03, 0.08, 0.01, MeshBuilder.color(0xc82a22)) // flag
    }

    private fun stopLine(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        // A white bar across the lane, on the road surface.
        val y = 0.045
        val col = MeshBuilder.color(0xeeeeea)
        val pts = arrayOf(pt(-p.hx, y, -p.hz), pt(p.hx, y, -p.hz), pt(p.hx, y, p.hz), pt(-p.hx, y, p.hz))
        for (k in pts) k[1] = groundAt(p.x + k[0] * cos(p.heading) - k[2] * sin(p.heading), p.y + k[0] * sin(p.heading) + k[2] * cos(p.heading)) - f.gy + 0.04
        quad(m, pts[0], pts[1], pts[2], pts[3], 0.0, -1.0, 0.0, col)
    }

    private fun sign(m: MeshBuilder, p: Prop, d: ChunkData) {
        f.set(p.x, p.y, groundAt(p.x, p.y), p.heading, d.e0, d.n0)
        val post = MeshBuilder.color(0x8a8c90)
        val back = MeshBuilder.color(0x8a8c90)
        fun postAt(v: Double, top: Double, col: Float = post, w: Float = 0.035f) =
            m.beam(f.rx(-0.04, v), f.ry(-0.2), f.rz(-0.04, v), f.rx(-0.04, v), f.ry(top), f.rz(-0.04, v), w, col)
        /** Two-faced board: [front] on +u, plain [back] behind. */
        fun board(v0: Double, v1: Double, y0: Double, y1: Double, front: Float) {
            panel(m, 0.0, v0, v1, y0, y1, front)
            panel(m, -0.02, v0, v1, y0, y1, back, back = true)
        }
        val black = MeshBuilder.color(0x141414)
        val white = MeshBuilder.color(0xf2f2ee)
        val yellow = MeshBuilder.color(0xf0c418)
        when (p.kind) {
            PropKind.SIGN_SPEED -> {
                postAt(0.0, 2.1)
                board(-0.31, 0.31, 1.45, 2.2, white)
                panel(m, 0.004, -0.29, 0.29, 1.47, 2.18, black); panel(m, 0.006, -0.27, 0.27, 1.49, 2.16, white)
                text(m, p.text.take(2), 0.008, 0.0, 2.1, 0.028, black, 1.5)
                text(m, p.text.drop(2), 0.008, 0.0, 1.85, 0.05, black)
            }
            PropKind.SIGN_CURVE_LEFT, PropKind.SIGN_CURVE_RIGHT -> {
                postAt(0.0, 2.0)
                val cy = 1.85; val r = 0.45
                fun diamond(u: Double, rr: Double, col: Float, back: Boolean = false) =
                    quad(m, pt(u, cy - rr, 0.0), pt(u, cy, rr), pt(u, cy + rr, 0.0), pt(u, cy, -rr), if (back) u + 1 else u - 1, cy, 0.0, col)
                diamond(0.0, r, black); diamond(-0.02, r, back, back = true); diamond(0.004, r - 0.03, yellow)
                // Arrow: stem up from the bottom, bending toward the curve, with a head.
                val dir = if (p.kind == PropKind.SIGN_CURVE_LEFT) -1.0 else 1.0 // +v is the viewer's right
                panel(m, 0.008, -0.05 - dir * 0.08, 0.05 - dir * 0.08, cy - 0.28, cy + 0.02, black)
                quad(m, pt(0.008, cy - 0.02, -0.05 - dir * 0.08), pt(0.008, cy - 0.02, 0.05 - dir * 0.08), pt(0.008, cy + 0.12, 0.05 + dir * 0.1), pt(0.008, cy + 0.12, -0.05 + dir * 0.1), -1.0, cy, 0.0, black)
                tri(m, pt(0.009, cy + 0.04, dir * 0.03), pt(0.009, cy + 0.26, dir * 0.2), pt(0.009, cy + 0.06, dir * 0.27), -1.0, cy, 0.0, black)
            }
            PropKind.SIGN_MOOSE -> {
                postAt(-0.55, 2.4); postAt(0.55, 2.4)
                board(-0.78, 0.78, 1.5, 2.55, yellow)
                panel(m, 0.004, -0.74, 0.74, 1.54, 2.51, black); panel(m, 0.006, -0.72, 0.72, 1.56, 2.49, yellow)
                text(m, p.text.take(2), 0.008, 0.0, 2.44, 0.05, black, 1.4)
                text(m, p.text.drop(2), 0.008, 0.0, 1.88, 0.028, black, 1.5)
            }
            PropKind.STOP_SIGN -> {
                postAt(0.0, 2.1)
                val cy = 2.05
                fun oct(u: Double, r: Double, col: Float, back: Boolean = false) {
                    val ref = if (back) u + 1 else u - 1
                    for (k in 0 until 8) {
                        val a0 = PI / 8 + k * PI / 4; val a1 = a0 + PI / 4
                        tri(m, pt(u, cy, 0.0), pt(u, cy + sin(a0) * r, cos(a0) * r), pt(u, cy + sin(a1) * r, cos(a1) * r), ref, cy, 0.0, col)
                    }
                }
                oct(0.0, 0.4, white); oct(-0.02, 0.4, back, back = true); oct(0.004, 0.365, MeshBuilder.color(0xc41e1a))
                text(m, listOf("STOP"), 0.008, 0.0, cy + 0.09, 0.03, white)
            }
            PropKind.STREET_SIGN -> streetSign(m, p, d)
            PropKind.BROWN_SIGN, PropKind.HISTORIC_MARKER -> {
                val lines = p.text.map { PixelFont.signName(it) }
                val widest = lines.maxOfOrNull { PixelFont.width(it) } ?: 10
                val px = if (p.kind == PropKind.HISTORIC_MARKER) 0.04 else 0.045
                val hw = max(0.5, widest * px / 2 + 0.15)
                val hh = lines.size * (PixelFont.H + 2) * px / 2 + 0.12
                val cy = 1.4 + hh
                val face = if (p.kind == PropKind.HISTORIC_MARKER) MeshBuilder.color(0x1e4a2e) else MeshBuilder.color(0x5a3a22)
                val ink = if (p.kind == PropKind.HISTORIC_MARKER) MeshBuilder.color(0xece0b0) else MeshBuilder.color(0xf0ece0)
                val wood = MeshBuilder.color(0x5a4a3a)
                postAt(-hw * 0.7, cy + hh - 0.1, if (p.kind == PropKind.HISTORIC_MARKER) MeshBuilder.color(0x1e3a26) else wood, 0.05f)
                postAt(hw * 0.7, cy + hh - 0.1, if (p.kind == PropKind.HISTORIC_MARKER) MeshBuilder.color(0x1e3a26) else wood, 0.05f)
                board(-hw, hw, cy - hh, cy + hh, face)
                panel(m, 0.004, -hw + 0.05, hw - 0.05, cy - hh + 0.05, cy + hh - 0.05, ink)
                panel(m, 0.006, -hw + 0.08, hw - 0.08, cy - hh + 0.08, cy + hh - 0.08, face)
                if (p.kind == PropKind.HISTORIC_MARKER) {
                    // The marker's arched crest.
                    quad(m, pt(0.0, cy + hh, -hw), pt(0.0, cy + hh, hw), pt(0.0, cy + hh + 0.2, hw * 0.6), pt(0.0, cy + hh + 0.2, -hw * 0.6), -1.0, cy, 0.0, face)
                }
                text(m, lines, 0.008, 0.0, cy + hh - 0.14, px, ink)
            }
            else -> {}
        }
    }

    /** Green street-name blades on one post, one parallel to each road, lettered both sides. */
    private fun streetSign(m: MeshBuilder, p: Prop, d: ChunkData) {
        val gy = groundAt(p.x, p.y)
        f.set(p.x, p.y, gy, 0.0, d.e0, d.n0)
        m.beam(f.rx(0.0, 0.0), f.ry(-0.2), f.rz(0.0, 0.0), f.rx(0.0, 0.0), f.ry(3.05), f.rz(0.0, 0.0), 0.035f, MeshBuilder.color(0x8a8c90))
        val green = MeshBuilder.color(0x1a6a3a)
        val white = MeshBuilder.color(0xf4f4f0)
        for ((i, name) in p.text.take(2).withIndex()) {
            val label = PixelFont.signName(name).take(22)
            val px = 0.026
            val hw = PixelFont.width(label) * px / 2 + 0.08
            val y = if (i == 0) 2.85 else 2.62
            f.set(p.x, p.y, gy, if (i == 0) p.heading else p.heading2, d.e0, d.n0)
            for (face in intArrayOf(1, -1)) {
                val u = face * 0.012
                panel(m, u, -hw, hw, y - 0.1, y + 0.1, green, back = face < 0)
                // Lettering reads left to right from each side: mirror the run for the back.
                val lw = PixelFont.width(label) * px
                val base = y - PixelFont.H * px / 2
                PixelFont.runs(label) { x0, y0, x1, y1 ->
                    val va = -lw / 2 + x0 * px; val vb = -lw / 2 + x1 * px
                    if (face > 0) panel(m, u + 0.003, va, vb, base + y0 * px, base + y1 * px, white)
                    else panel(m, u - 0.003, -vb, -va, base + y0 * px, base + y1 * px, white, back = true)
                }
            }
        }
    }

    // --- runs ----------------------------------------------------------------------------------

    private fun run(m: MeshBuilder, r: Run, d: ChunkData, near: Boolean) {
        val n = r.xs.size
        // Only the vertices in (or just past) this chunk; neighbours draw the rest.
        var k0 = -1; var k1 = -1
        for (k in 0 until n) {
            val inside = r.xs[k] >= d.e0 && r.xs[k] < d.e0 + d.size && r.ys[k] >= d.n0 && r.ys[k] < d.n0 + d.size
            if (inside) { if (k0 < 0) k0 = k; k1 = k }
        }
        if (k0 < 0) return
        val a = k0; val b = min(n - 1, k1 + 1)
        if (b <= a) return
        when (r.kind) {
            RunKind.GUARDRAIL -> guardRail(m, r, a, b, d, near)
            RunKind.BRIDGE_RAIL -> parapet(m, r, a, b, d)
            RunKind.STONE_WALL -> stoneWall(m, r, a, b, d, near)
            RunKind.RAIL_FENCE, RunKind.BOARD_FENCE -> fence(m, r, a, b, d)
            RunKind.COVERED_BRIDGE -> coveredBridge(m, r, a, b, d)
        }
    }

    private fun rx(d: ChunkData, e: Double) = (e - d.e0).toFloat()
    private fun rz(d: ChunkData, n: Double) = (-(n - d.n0)).toFloat()
    private fun ryOf(h: Double) = (h - baseH).toFloat()

    /** Vertical strip between two ground points, both faces. */
    private fun strip(m: MeshBuilder, d: ChunkData, x0: Double, y0: Double, h0: Double, x1: Double, y1: Double, h1: Double, lo: Double, hi: Double, col: Float) {
        val ax = rx(d, x0); val az = rz(d, y0); val bx = rx(d, x1); val bz = rz(d, y1)
        val a0 = ryOf(h0 + lo); val a1 = ryOf(h0 + hi); val b0 = ryOf(h1 + lo); val b1 = ryOf(h1 + hi)
        m.twoSidedTri(ax, a0, az, bx, b0, bz, bx, b1, bz, col)
        m.twoSidedTri(ax, a0, az, bx, b1, bz, ax, a1, az, col)
    }

    private fun guardRail(m: MeshBuilder, r: Run, a: Int, b: Int, d: ChunkData, near: Boolean) {
        val steel = MeshBuilder.color(0xa8acb0)
        val postCol = MeshBuilder.color(0x6a6c70)
        val n = r.xs.size
        for (k in a until b) {
            val h0 = groundAt(r.xs[k], r.ys[k]); val h1 = groundAt(r.xs[k + 1], r.ys[k + 1])
            // Ends turn down into the ground.
            fun top(i: Int) = when (i) { 0, n - 1 -> 0.25; 1, n - 2 -> 0.55; else -> 0.78 }
            val t0 = top(k); val t1 = top(k + 1)
            strip(m, d, r.xs[k], r.ys[k], h0 + t0 - 0.78, r.xs[k + 1], r.ys[k + 1], h1 + t1 - 0.78, 0.5, 0.82, steel)
            if (near && k % 1 == 0) m.beam(rx(d, r.xs[k]), ryOf(h0 - 0.1), rz(d, r.ys[k]), rx(d, r.xs[k]), ryOf(h0 + t0 - 0.02), rz(d, r.ys[k]), 0.05f, postCol)
        }
    }

    private fun parapet(m: MeshBuilder, r: Run, a: Int, b: Int, d: ChunkData) {
        val conc = MeshBuilder.color(0xb8b6ae)
        for (k in a until b) {
            val x0 = r.xs[k]; val y0 = r.ys[k]; val x1 = r.xs[k + 1]; val y1 = r.ys[k + 1]
            val h0 = groundAt(x0, y0); val h1 = groundAt(x1, y1)
            m.beam(rx(d, x0), ryOf(h0 + 0.45), rz(d, y0), rx(d, x1), ryOf(h1 + 0.45), rz(d, y1), 0.25f, conc)
            m.beam(rx(d, x0), ryOf(h0 + 0.9), rz(d, y0), rx(d, x1), ryOf(h1 + 0.9), rz(d, y1), 0.08f, MeshBuilder.color(0x7a7c80))
        }
    }

    private fun stoneWall(m: MeshBuilder, r: Run, a: Int, b: Int, d: ChunkData, near: Boolean) {
        for (k in a until b) {
            val x0 = r.xs[k]; val y0 = r.ys[k]; val x1 = r.xs[k + 1]; val y1 = r.ys[k + 1]
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 0.1) continue
            val hgt = 0.6 + 0.3 * Noise.value1(k * 0.6, r.seed)
            if (!near) {
                val h0 = groundAt(x0, y0); val h1 = groundAt(x1, y1)
                m.beam(rx(d, x0), ryOf(h0 + hgt * 0.35), rz(d, y0), rx(d, x1), ryOf(h1 + hgt * 0.35), rz(d, y1), (hgt * 0.42).toFloat(), Palette.shade(0x8a867a, 0.9 + 0.15 * Noise.rand(k, r.seed, 51)))
                continue
            }
            // Fieldstone laid in two rough courses on a continuous core, grey to brown with
            // lichen, the top uneven where stones have tumbled.
            val h0 = groundAt(x0, y0); val h1 = groundAt(x1, y1)
            m.beam(rx(d, x0), ryOf(h0 + hgt * 0.3), rz(d, y0), rx(d, x1), ryOf(h1 + hgt * 0.3), rz(d, y1), (hgt * 0.36).toFloat(), Palette.shade(0x7c7970, 0.95))
            val hd = kotlin.math.atan2(y1 - y0, x1 - x0)
            val n = max(2, (len / 0.9).toInt())
            for (q in 0 until n) {
                for (course in 0..1) {
                    val rr = Noise.rand(k * 16 + q, course, r.seed + 53)
                    if (course == 1 && rr < 0.3) continue
                    val t = (q + 0.5 + course * 0.5) / n
                    if (t > 1) continue
                    val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t
                    val g = groundAt(x, y)
                    val tone = Noise.rand(k * 16 + q, course, r.seed + 54)
                    val base = if (tone < 0.55) 0x8e8c84 else if (tone < 0.8) 0x8a7e6a else if (tone < 0.92) 0x9a9890 else 0x6e7a62
                    val su = (if (course == 0) 0.42 else 0.32) * (0.8 + 0.4 * rr)
                    val sy = (if (course == 0) 0.2 else 0.15) * (0.8 + 0.5 * tone)
                    val sv = (if (course == 0) 0.36 else 0.28) * (0.85 + 0.3 * rr)
                    val y0c = if (course == 0) sy else hgt * 0.55 + sy * 0.8
                    f.set(x, y, g, hd + (rr - 0.5) * 0.7, d.e0, d.n0)
                    box(m, 0.0, y0c, (tone - 0.5) * 0.12, su, sy, sv, Palette.shade(base, 0.78 + 0.3 * rr))
                }
            }
        }
    }

    private fun fence(m: MeshBuilder, r: Run, a: Int, b: Int, d: ChunkData) {
        val board = r.kind == RunKind.BOARD_FENCE
        val col = if (board) MeshBuilder.color(0xeeeee8) else Palette.shade(0x8a7a64, 0.9)
        val postCol = if (board) col else Palette.shade(0x6a5a48, 1.0)
        for (k in a until b) {
            val x0 = r.xs[k]; val y0 = r.ys[k]; val x1 = r.xs[k + 1]; val y1 = r.ys[k + 1]
            val h0 = groundAt(x0, y0); val h1 = groundAt(x1, y1)
            m.beam(rx(d, x0), ryOf(h0 - 0.1), rz(d, y0), rx(d, x0), ryOf(h0 + 1.25), rz(d, y0), 0.06f, postCol)
            if (board) for (y in doubleArrayOf(0.4, 0.75, 1.1)) strip(m, d, x0, y0, h0, x1, y1, h1, y, y + 0.14, col)
            else for (y in doubleArrayOf(0.5, 1.0)) m.beam(rx(d, x0), ryOf(h0 + y), rz(d, y0), rx(d, x1), ryOf(h1 + y + 0.05), rz(d, y1), 0.05f, col)
        }
        val e = b
        m.beam(rx(d, r.xs[e]), ryOf(groundAt(r.xs[e], r.ys[e]) - 0.1), rz(d, r.ys[e]), rx(d, r.xs[e]), ryOf(groundAt(r.xs[e], r.ys[e]) + 1.25), rz(d, r.ys[e]), 0.06f, postCol)
    }

    private fun coveredBridge(m: MeshBuilder, r: Run, a: Int, b: Int, d: ChunkData) {
        val wall = MeshBuilder.color(0x7a2a22)
        val roof = MeshBuilder.color(0x4a4a4c)
        val w = r.width
        for (k in a until b) {
            val x0 = r.xs[k]; val y0 = r.ys[k]; val x1 = r.xs[k + 1]; val y1 = r.ys[k + 1]
            val hd = kotlin.math.atan2(y1 - y0, x1 - x0)
            val lx = -sin(hd); val ly = cos(hd)
            val h0 = groundAt(x0, y0); val h1 = groundAt(x1, y1)
            for (side in intArrayOf(1, -1)) {
                val ax = x0 + lx * w * side; val ay = y0 + ly * w * side; val bx = x1 + lx * w * side; val by = y1 + ly * w * side
                strip(m, d, ax, ay, h0, bx, by, h1, -0.6, 3.6, wall)
                // Roof slope from the eave to the ridge.
                val ex0 = x0 + lx * (w + 0.6) * side; val ey0 = y0 + ly * (w + 0.6) * side
                val ex1 = x1 + lx * (w + 0.6) * side; val ey1 = y1 + ly * (w + 0.6) * side
                m.twoSidedTri(rx(d, ex0), ryOf(h0 + 3.5), rz(d, ey0), rx(d, ex1), ryOf(h1 + 3.5), rz(d, ey1), rx(d, x1), ryOf(h1 + 3.6 + w * 0.7), rz(d, y1), roof)
                m.twoSidedTri(rx(d, ex0), ryOf(h0 + 3.5), rz(d, ey0), rx(d, x1), ryOf(h1 + 3.6 + w * 0.7), rz(d, y1), rx(d, x0), ryOf(h0 + 3.6 + w * 0.7), rz(d, y0), roof)
            }
        }
        // Portal gables at the ends.
        for (k in intArrayOf(0, r.xs.size - 1)) {
            if (k < a || k > b) continue
            val k2 = if (k == 0) 1 else k - 1
            val hd = kotlin.math.atan2(r.ys[k] - r.ys[k2], r.xs[k] - r.xs[k2])
            val lx = -sin(hd); val ly = cos(hd)
            val x = r.xs[k]; val y = r.ys[k]; val h = groundAt(x, y)
            m.twoSidedTri(rx(d, x + lx * w), ryOf(h + 3.6), rz(d, y + ly * w), rx(d, x - lx * w), ryOf(h + 3.6), rz(d, y - ly * w), rx(d, x), ryOf(h + 3.6 + w * 0.7), rz(d, y), wall)
            strip(m, d, x + lx * w, y + ly * w, h, x - lx * w, y - ly * w, h, 3.0, 3.6, wall)
        }
    }
}
