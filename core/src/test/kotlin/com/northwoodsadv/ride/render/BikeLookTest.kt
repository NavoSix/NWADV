package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.bikes.Bikes
import org.junit.Test
import kotlin.test.assertTrue

/** The garage model is richer than the riding model, and both fit 16-bit mesh indices. */
class BikeLookTest {
    private fun verts(detail: Detail, paint: HimalayanPaint = HimalayanPaint.ROCK_RED): IntArray {
        val spec = Bikes.HIMALAYAN_411.spec
        val look = HimalayanLook(detail, paint)
        val parts = Array(6) { MeshBuilder() }
        look.chassis(parts[0], spec)
        look.front(parts[1], spec, spec.cgToFront.toFloat() - look.headX, spec.frontRadius.toFloat() - look.headY)
        look.frontLower(parts[2], spec, -0.5f, 0.86f)
        look.wheel(parts[3], spec, true)
        look.wheel(parts[4], spec, false)
        look.glass(parts[5], spec)
        return IntArray(parts.size) { parts[it].vertCount }
    }

    @Test fun showroomIsMoreDetailedThanRide() {
        val ride = verts(Detail.RIDE); val show = verts(Detail.SHOWROOM)
        println("himalayan verts ride=${ride.toList()} (${ride.sum()}) showroom=${show.toList()} (${show.sum()})")
        assertTrue(show.sum() > ride.sum() * 1.5, "showroom model adds detail")
        assertTrue(ride.sum() < 40_000, "riding model stays light")
        for (v in show) assertTrue(v < 60_000, "each part fits one mesh")
    }

    @Test fun paintSchemesShareTheModel() {
        val base = verts(Detail.RIDE).toList()
        for (p in HimalayanPaint.entries) kotlin.test.assertEquals(base, verts(Detail.RIDE, p).toList(), "${p.label} is a repaint, not a different model")
    }

    @Test fun pineGreenIsCamouflaged() {
        val spec = Bikes.HIMALAYAN_411.spec
        fun colours(paint: HimalayanPaint): Set<Float> {
            val m = MeshBuilder()
            HimalayanLook(Detail.RIDE, paint).chassis(m, spec)
            return (0 until m.vertCount).map { m.verts[it * MeshBuilder.STRIDE + 6] }.toSet()
        }
        val camo = listOf(0x222c1e, 0x3c5236, 0x5a5e3a, 0x7a8462).map { MeshBuilder.color(it) }
        val green = colours(HimalayanPaint.PINE_GREEN)
        assertTrue(camo.all { it in green }, "all four camo tones on the bodywork")
        assertTrue(camo.none { it in colours(HimalayanPaint.LAKE_BLUE) }, "no camo on Lake Blue")
    }
}
