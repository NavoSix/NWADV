package com.northwoodsadv.ride.render

import com.northwoodsadv.ride.bikes.Bikes
import org.junit.Test
import kotlin.test.assertTrue

/** The garage model is richer than the riding model, and both fit 16-bit mesh indices. */
class BikeLookTest {
    private fun verts(detail: Detail): IntArray {
        val spec = Bikes.HIMALAYAN_411.spec
        val look = HimalayanLook(detail)
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
}
