package com.northwoodsadv.ride.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayCycleTest {
    @Test fun sunRisesAndSetsAtNewHampshireTimes() {
        val noon = DayCycle(12.0)
        assertTrue(noon.sunAltitude > 0.85 && noon.sunDir[2] > 0, "summer noon sun high in the south")
        assertTrue(noon.darkness < 0.01)
        // July, solar time: sunrise about 4:30, sunset about 19:30.
        assertTrue(DayCycle(5.5).sunAltitude > 0 && DayCycle(3.8).sunAltitude < 0)
        assertTrue(DayCycle(19.0).sunAltitude > 0 && DayCycle(20.2).sunAltitude < 0)
        val night = DayCycle(23.5)
        assertTrue(night.darkness > 0.99 && night.stars > 0.99 && night.ambient[0] < 0.2)
    }

    @Test fun timePassesAtTheChosenSpeed() {
        val d = DayCycle(23.5, speed = 60.0)
        d.update(60.0) // a real minute at 60x: an hour
        assertEquals(0.5, d.hours, 1e-6)
        assertEquals("12:30 AM", d.clock(true)); assertEquals("00:30", d.clock(false))
        assertEquals("6:42 PM", DayCycle(18.7).clock(true))
    }
}
