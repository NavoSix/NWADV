package com.northwoodsadv.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideSaveTest {
    @Test fun roundTrips() {
        val s = RideSave(44.0459005, -71.6696471, 0.7831, 12345.6, 13001.2, -165664712)
        val back = RideSave.parse(s.format())!!
        assertEquals(s.lat, back.lat, 1e-7); assertEquals(s.lon, back.lon, 1e-7)
        assertEquals(s.heading, back.heading, 1e-4); assertEquals(s.along, back.along, 0.1)
        assertEquals(s.odometer, back.odometer, 0.1); assertEquals(s.routeHash, back.routeHash)
    }

    @Test fun ignoresGarbage() {
        assertNull(RideSave.parse(null)); assertNull(RideSave.parse("")); assertNull(RideSave.parse("1;x;y;z;1;2;3"))
    }

    @Test fun keysMatchTheRouteList() {
        assertEquals("Sample_ Kancamagus Highway _approx._", RideSave.key("Sample: Kancamagus Highway (approx.).gpx"))
        assertEquals(RideSave.key("Sample: Kancamagus Highway (approx.)"), RideSave.key("Sample: Kancamagus Highway (approx.).gpx"))
    }
}
