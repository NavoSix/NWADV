package com.northwoodsadv.ride.hud

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.northwoodsadv.ride.Settings
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.route.RouteTracker
import com.northwoodsadv.ride.ui.Ui
import com.northwoodsadv.ride.world.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Handlebar GPS: track-up moving map with the GPX route in magenta (the way a Garmin shows it),
 * mapped roads styled by type, your breadcrumb trail, and the usual data fields.
 */
class GpsUnit(private val world: World, private val tracker: RouteTracker, private val settings: Settings) {
    private class MapLine(val xs: FloatArray, val ys: FloatArray, val kind: RoadKind?, val water: Boolean, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private val lines: List<MapLine>
    private val routeXs: FloatArray
    /** Hero sections, drawn in orange over the roads. */
    private val heroLines: List<MapLine>
    private val routeYs: FloatArray
    private val crumbX = FloatArray(4000); private val crumbY = FloatArray(4000)
    private var crumbs = 0; private var crumbHead = 0
    var zoom = 600f // metres across the map
    private var smoothHeading = 0.0

    init {
        val out = ArrayList<MapLine>()
        for (r in world.roads) out += mapLine(r.line.xs, r.line.ys, r.spec.kind, false)
        for (s in world.ground.streams) out += mapLine(s.line.xs, s.line.ys, null, true)
        lines = out
        val p = DoubleArray(3)
        heroLines = world.roads.flatMap { r ->
            r.heroes.map { h ->
                val n = (h.length / 6).toInt() + 1
                val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
                for (i in 0..n) { r.line.pointAt(h.s0 + h.length * i / n, p); xs[i] = p[0]; ys[i] = p[1] }
                mapLine(xs, ys, null, false)
            }
        }
        val rl = world.route.resample(8.0)
        routeXs = FloatArray(rl.size) { rl.xs[it].toFloat() }; routeYs = FloatArray(rl.size) { rl.ys[it].toFloat() }
    }

    private fun mapLine(xs: DoubleArray, ys: DoubleArray, kind: RoadKind?, water: Boolean): MapLine {
        // Simplify in projected metres (geo simplify expects lat/lon, so do a quick local pass).
        val keep = ArrayList<Int>()
        var last = -1
        for (i in xs.indices) {
            if (last < 0 || i == xs.size - 1 || hypot(xs[i] - xs[last], ys[i] - ys[last]) > 6) { keep += i; last = i }
        }
        val fx = FloatArray(keep.size) { xs[keep[it]].toFloat() }; val fy = FloatArray(keep.size) { ys[keep[it]].toFloat() }
        return MapLine(fx, fy, kind, water, fx.min(), fy.min(), fx.max(), fy.max())
    }

    fun recordCrumb(x: Double, y: Double) {
        val prev = (crumbHead - 1 + crumbX.size) % crumbX.size
        if (crumbs > 0 && hypot(x - crumbX[prev], y - crumbY[prev]) < 10) return
        crumbX[crumbHead] = x.toFloat(); crumbY[crumbHead] = y.toFloat()
        crumbHead = (crumbHead + 1) % crumbX.size
        if (crumbs < crumbX.size) crumbs++
    }

    /** Full map; [compact] keeps one row of data fields (small device screens in the cockpit). */
    fun draw(ui: Ui, bike: Motorcycle, x: Float, y: Float, w: Float, h: Float, dt: Float, compact: Boolean = false) {
        val sh = ui.shapes
        val row = ui.lineHeight() + ui.u * 0.4f
        val fieldsH = row * (if (compact) 1 else 2) + ui.u * 0.8f
        val mapY = y + fieldsH; val mapH = h - fieldsH
        // Body.
        ui.rect(x - ui.u * 0.5f, y - ui.u * 0.5f, w + ui.u, h + ui.u, BEZEL)
        ui.rect(x, mapY, w, mapH, MAP_BG)

        var hd = if (bike.speed > 2) bike.course else bike.yaw
        var dh = hd - smoothHeading
        while (dh > PI) dh -= 2 * PI; while (dh < -PI) dh += 2 * PI
        smoothHeading += dh * (1 - kotlin.math.exp(-dt * 4.0))
        hd = smoothHeading
        val scale = w / zoom
        val cxs = x + w / 2; val cys = mapY + mapH * 0.32f
        // Track-up: rotate world so heading points to screen up.
        val rot = PI / 2 - hd
        val cr = cos(rot).toFloat(); val sr = sin(rot).toFloat()
        val bx = bike.x.toFloat(); val by = bike.y.toFloat()
        fun sx(px: Float, py: Float): Float { val dx = px - bx; val dy = py - by; return cxs + (dx * cr - dy * sr) * scale }
        fun sy(px: Float, py: Float): Float { val dx = px - bx; val dy = py - by; return cys + (dx * sr + dy * cr) * scale }

        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glScissor(x.toInt(), mapY.toInt(), w.toInt(), mapH.toInt())
        val reach = zoom * 1.2f
        sh.begin(ShapeRenderer.ShapeType.Filled)
        for (pass in 0..1) for (l in lines) {
            if (l.maxX < bx - reach || l.minX > bx + reach || l.maxY < by - reach || l.minY > by + reach) continue
            val (col, width) = style(l, pass) ?: continue
            sh.color = col
            val dashed = l.kind == RoadKind.CLASS_VI || l.kind == RoadKind.TRAIL
            var acc = 0f
            for (i in 0 until l.xs.size - 1) {
                val x0 = sx(l.xs[i], l.ys[i]); val y0 = sy(l.xs[i], l.ys[i])
                val x1 = sx(l.xs[i + 1], l.ys[i + 1]); val y1 = sy(l.xs[i + 1], l.ys[i + 1])
                val seg = hypot(x1 - x0, y1 - y0)
                if (dashed && pass == 1) {
                    // Dashes every ~1.5 u along the line.
                    var t = 0f
                    while (t < seg) {
                        val on = ((acc + t) / (ui.u * 1.2f)).toInt() % 2 == 0
                        val t1 = minOf(seg, t + ui.u * 0.6f)
                        if (on) sh.rectLine(x0 + (x1 - x0) * t / seg, y0 + (y1 - y0) * t / seg, x0 + (x1 - x0) * t1 / seg, y0 + (y1 - y0) * t1 / seg, width)
                        t = t1
                    }
                    acc += seg
                } else sh.rectLine(x0, y0, x1, y1, width)
            }
        }
        // The route (magenta), then where you've been (blue).
        sh.color = ROUTE
        for (i in 0 until routeXs.size - 1) {
            val ax = routeXs[i]; val ay = routeYs[i]
            if (abs(ax - bx) > reach && abs(routeXs[i + 1] - bx) > reach) continue
            if (abs(ay - by) > reach && abs(routeYs[i + 1] - by) > reach) continue
            sh.rectLine(sx(ax, ay), sy(ax, ay), sx(routeXs[i + 1], routeYs[i + 1]), sy(routeXs[i + 1], routeYs[i + 1]), ui.u * 0.55f)
        }
        sh.color = HERO
        for (l in heroLines) {
            if (l.maxX < bx - reach || l.minX > bx + reach || l.maxY < by - reach || l.minY > by + reach) continue
            for (i in 0 until l.xs.size - 1)
                sh.rectLine(sx(l.xs[i], l.ys[i]), sy(l.xs[i], l.ys[i]), sx(l.xs[i + 1], l.ys[i + 1]), sy(l.xs[i + 1], l.ys[i + 1]), ui.u * 0.4f)
        }
        sh.color = CRUMB
        for (k in 1 until crumbs) {
            val i0 = (crumbHead - k - 1 + crumbX.size * 2) % crumbX.size; val i1 = (crumbHead - k + crumbX.size * 2) % crumbX.size
            if (abs(crumbX[i1] - bx) > reach) continue
            sh.rectLine(sx(crumbX[i0], crumbY[i0]), sy(crumbX[i0], crumbY[i0]), sx(crumbX[i1], crumbY[i1]), sy(crumbX[i1], crumbY[i1]), ui.u * 0.25f)
        }
        // Landmarks: a small marker each, named once the shapes are done.
        val labels = ArrayList<Triple<Float, Float, String>>()
        for (l in world.ground.scenery.landmarks) {
            val lx = l.x.toFloat(); val ly = l.y.toFloat()
            if (abs(lx - bx) > reach || abs(ly - by) > reach) continue
            val px = sx(lx, ly); val py = sy(lx, ly)
            if (px < x || px > x + w || py < mapY || py > mapY + mapH) continue
            val r = ui.u * 0.55f
            sh.color = Color.WHITE
            sh.rect(px - r - ui.u * 0.15f, py - r - ui.u * 0.15f, (r + ui.u * 0.15f) * 2, (r + ui.u * 0.15f) * 2)
            sh.color = if (l.kind == "Peak") PEAK else POI
            if (l.kind == "Peak") sh.triangle(px - r, py - r, px + r, py - r, px, py + r) else sh.rect(px - r, py - r, r * 2, r * 2)
            if (zoom <= 1300) labels += Triple(px, py, l.name)
        }

        // Position arrow.
        val a = ui.u * 1.4f
        sh.color = Color.BLACK
        sh.triangle(cxs, cys + a * 1.25f, cxs - a * 0.85f, cys - a * 0.8f, cxs + a * 0.85f, cys - a * 0.8f)
        sh.color = ARROW
        sh.triangle(cxs, cys + a, cxs - a * 0.65f, cys - a * 0.6f, cxs, cys - a * 0.25f)
        sh.triangle(cxs, cys + a, cxs, cys - a * 0.25f, cxs + a * 0.65f, cys - a * 0.6f)
        sh.end()
        for ((px, py, name) in labels) ui.text(name, px + ui.u * 1.1f, py + ui.u * 0.6f, POI_TEXT)
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)

        // North pointer and scale.
        val nx = x + w - ui.u * 2.5f; val ny = mapY + mapH - ui.u * 2.5f
        val nr = (PI / 2 - hd + PI / 2).toFloat()
        ui.text("N", nx + cos(nr) * ui.u * 1.2f, ny + sin(nr) * ui.u * 1.2f + ui.u * 0.5f, Color.DARK_GRAY, Align.center)
        ui.text(settings.distance(zoom / 4.0), x + ui.u * 0.8f, mapY + ui.u * 1.8f, Color.DARK_GRAY)

        // Banner: next turn or off-route.
        val bannerH = ui.u * 3.2f
        val t = tracker.nextTurn
        when {
            tracker.isOffRoute -> {
                ui.rect(x, mapY + mapH - bannerH, w, bannerH, OFF_ROUTE)
                ui.text("Off route  ${settings.distance(tracker.offRoute)}", x + ui.u, mapY + mapH - ui.u * 1.0f, Color.WHITE)
            }
            tracker.finished -> {
                ui.rect(x, mapY + mapH - bannerH, w, bannerH, BANNER)
                ui.text("Arrived. Ride complete!", x + ui.u, mapY + mapH - ui.u * 1.0f, Color.WHITE)
            }
            t != null -> {
                ui.rect(x, mapY + mapH - bannerH, w, bannerH, BANNER)
                val arrow = when { abs(t.angleDeg) > 135 -> "U"; abs(t.angleDeg) <= 22 -> "^"; t.left -> "<"; else -> ">" }
                ui.text("$arrow  ${t.label}  ${settings.distance(t.along - tracker.along)}", x + ui.u, mapY + mapH - ui.u * 1.0f, Color.WHITE)
            }
        }

        // Data fields.
        ui.rect(x, y, w, fieldsH, FIELDS_BG)
        val colW = w / 2
        fun field(label: String, value: String, cx: Float, top: Float) {
            ui.text(label, cx + ui.u * 0.6f, top, LABEL)
            ui.text(value, cx + colW - ui.u * 0.6f, top, Color.WHITE, Align.right)
        }
        val elev = bike.z - bike.cgHeight
        val r1 = y + fieldsH - ui.u * 0.4f
        field("To end", settings.distance(tracker.remaining), x, r1)
        field("Elev", settings.elevation(elev), x + colW, r1)
        val deg = ((90 - Math.toDegrees(bike.course)) % 360 + 360) % 360
        if (compact) return
        field("Hdg", "%s %03d".format(compass(deg), deg.toInt()), x, r1 - row)
        field("Odo", settings.distance(bike.odometer), x + colW, r1 - row)
    }

    /** Collapsed GPS: just the next instruction and distance to go, hanging from the top edge. */
    fun drawCompact(ui: Ui, bike: Motorcycle, x: Float, top: Float, w: Float, dt: Float) {
        val row = ui.lineHeight() + ui.u * 0.6f
        val h = row * 2 + ui.u * 0.6f
        val y = top - h
        recordHeading(bike, dt)
        ui.rect(x, y, w, h, BEZEL)
        val t = tracker.nextTurn
        val (msg, col) = when {
            tracker.isOffRoute -> "Off route ${settings.distance(tracker.offRoute)}" to OFF_ROUTE
            tracker.finished -> "Ride complete!" to BANNER
            t != null -> "${if (abs(t.angleDeg) > 135) "U" else if (abs(t.angleDeg) <= 22) "^" else if (t.left) "<" else ">"} ${t.label} ${settings.distance(t.along - tracker.along)}" to BANNER
            else -> "Follow the route" to BANNER
        }
        ui.rect(x + ui.u * 0.3f, y + row + ui.u * 0.3f, w - ui.u * 0.6f, row, col)
        ui.text(msg, x + ui.u * 0.8f, y + h - ui.u * 0.6f, Color.WHITE)
        ui.text("To end", x + ui.u * 0.8f, y + row - ui.u * 0.1f, LABEL)
        ui.text(settings.distance(tracker.remaining), x + w - ui.u * 0.8f, y + row - ui.u * 0.1f, Color.WHITE, Align.right)
    }

    private fun recordHeading(bike: Motorcycle, dt: Float) {
        val hd = if (bike.speed > 2) bike.course else bike.yaw
        var dh = hd - smoothHeading
        while (dh > PI) dh -= 2 * PI; while (dh < -PI) dh += 2 * PI
        smoothHeading += dh * (1 - kotlin.math.exp(-dt * 4.0))
    }

    private fun compass(deg: Double) = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    private fun style(l: MapLine, pass: Int): Pair<Color, Float>? {
        val px = zoom.let { 600f / it }.coerceIn(0.4f, 2.5f)
        if (l.water) return if (pass == 1) WATER to 2f * px else null
        return when (l.kind) {
            RoadKind.HIGHWAY -> if (pass == 0) CASING to 9f * px else HIGHWAY to 6.5f * px
            RoadKind.MAJOR -> if (pass == 0) CASING to 8f * px else MAJOR to 5.5f * px
            RoadKind.MINOR -> if (pass == 0) CASING to 6.5f * px else Color.WHITE to 4.5f * px
            RoadKind.SERVICE -> if (pass == 0) null else SERVICE to 2.5f * px
            RoadKind.GRAVEL -> if (pass == 0) CASING to 5.5f * px else GRAVEL to 3.8f * px
            RoadKind.CLASS_VI -> if (pass == 0) null else CLASS_VI to 3.2f * px
            RoadKind.TRAIL -> if (pass == 0) null else TRAIL to 2.4f * px
            null -> null
        }
    }

    companion object {
        private val BEZEL = Color(0.08f, 0.08f, 0.09f, 0.95f)
        private val MAP_BG = Color(0.93f, 0.91f, 0.85f, 1f)
        private val FIELDS_BG = Color(0.12f, 0.13f, 0.15f, 1f)
        private val LABEL = Color(0.65f, 0.7f, 0.75f, 1f)
        private val ROUTE = Color(0.85f, 0.1f, 0.75f, 0.9f)
        private val POI = Color(0.45f, 0.28f, 0.12f, 1f)
        private val PEAK = Color(0.35f, 0.3f, 0.25f, 1f)
        private val POI_TEXT = Color(0.3f, 0.2f, 0.1f, 1f)
        private val CRUMB = Color(0.15f, 0.45f, 0.95f, 0.85f)
        private val HERO = Color(1f, 0.55f, 0.1f, 0.95f)
        private val ARROW = Color(0.95f, 0.25f, 0.15f, 1f)
        private val CASING = Color(0.55f, 0.55f, 0.55f, 1f)
        private val HIGHWAY = Color(0.98f, 0.8f, 0.35f, 1f)
        private val MAJOR = Color(0.99f, 0.93f, 0.6f, 1f)
        private val SERVICE = Color(0.75f, 0.75f, 0.75f, 1f)
        private val GRAVEL = Color(0.85f, 0.75f, 0.55f, 1f)
        private val CLASS_VI = Color(0.5f, 0.32f, 0.15f, 1f)
        private val TRAIL = Color(0.35f, 0.5f, 0.2f, 1f)
        private val WATER = Color(0.35f, 0.6f, 0.85f, 1f)
        private val BANNER = Color(0.1f, 0.42f, 0.22f, 0.95f)
        private val OFF_ROUTE = Color(0.75f, 0.15f, 0.12f, 0.95f)
    }
}
