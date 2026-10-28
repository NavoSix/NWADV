package com.northwoodsadv.ride.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementTest {
    private class MapStore : ProgressStore {
        val numbers = HashMap<String, Double>(); val unlocked = HashSet<Achievement>()
        override fun number(key: String) = numbers[key] ?: 0.0
        override fun setNumber(key: String, v: Double) { numbers[key] = v }
        override fun isUnlocked(a: Achievement) = a in unlocked
        override fun unlock(a: Achievement) { unlocked += a }
        override fun flush() {}
    }

    /** Ride [seconds] at [speed]; returns everything unlocked on the way. */
    private fun ride(t: AchievementTracker, seconds: Double, speed: Double, water: Boolean = false, classVI: Boolean = false, own: Boolean = true,
                     night: Boolean = false, air: Boolean = false): List<Achievement> {
        val out = ArrayList<Achievement>(); var s = 0.0
        while (s < seconds) { out += t.update(0.1, speed, water, classVI, own, night, air); s += 0.1 }
        return out
    }

    @Test fun aMileOfWaterUnlocksLakeBlue() {
        val store = MapStore(); val t = AchievementTracker(store, "himalayan411")
        assertTrue(ride(t, 300.0, 5.0, water = true).isEmpty(), "1.5 km isn't a mile yet")
        assertEquals(listOf(Achievement.WATERBORNE), ride(t, 30.0, 5.0, water = true))
        assertTrue(ride(t, 100.0, 5.0, water = true).isEmpty(), "only once")
    }

    @Test fun progressSurvivesBetweenRides() {
        val store = MapStore()
        ride(AchievementTracker(store, "himalayan411"), 200.0, 5.0, water = true)
        AchievementTracker(store, "himalayan411").save().let {}
        assertEquals(listOf(Achievement.WATERBORNE), ride(AchievementTracker(store, "himalayan411"), 130.0, 5.0, water = true))
    }

    @Test fun flatOutNeedsThirtySecondsInARow() {
        val store = MapStore(); val t = AchievementTracker(store, "himalayan411")
        val fast = Achievement.FLAT_OUT.speed + 0.1
        assertTrue(ride(t, 25.0, fast).isEmpty())
        ride(t, 1.0, 30.0) // backed off: the run starts again
        assertTrue(ride(t, 25.0, fast).isEmpty())
        assertEquals(listOf(Achievement.FLAT_OUT), ride(t, 6.0, fast))
    }

    @Test fun classSixCountsOnlyOnYourOwnRoutes() {
        val store = MapStore(); val t = AchievementTracker(store, "himalayan411")
        val miles500 = 500 * AchievementTracker.MILE
        assertTrue(ride(t, miles500 / 20 + 100, 20.0, classVI = true, own = false).isEmpty(), "sample routes don't count")
        assertEquals(listOf(Achievement.LOST_IN_THE_WOODS), ride(t, miles500 / 20 + 100, 20.0, classVI = true, own = true))
    }

    @Test fun otherBikesDontEarnTheHimalayansPaints() {
        val store = MapStore(); val t = AchievementTracker(store, "generic-twin")
        val fast = Achievement.FLAT_OUT.speed + 5
        assertTrue(ride(t, 60.0, fast, water = true).isEmpty(), "the twin earns nothing for the Himalayan")
        assertTrue(store.numbers.isEmpty(), "and keeps no Himalayan progress")
        assertEquals(listOf(Achievement.FLAT_OUT), ride(AchievementTracker(store, "himalayan411"), 31.0, fast))
    }

    @Test fun paintsBelongToTheirBike() {
        assertEquals(Achievement.WATERBORNE, Achievement.forPaint("himalayan411", "lake-blue"))
        assertEquals(null, Achievement.forPaint("himalayan411", "rock-red"))
        assertEquals(null, Achievement.forPaint("generic-twin", "lake-blue"))
    }

    @Test fun shadowRiderIsFiftyMilesAfterDark() {
        val store = MapStore(); val gs = AchievementTracker(store, "r1250gs")
        val miles50 = 50 * AchievementTracker.MILE
        assertTrue(ride(gs, miles50 / 25 + 50, 25.0).isEmpty(), "daylight doesn't count")
        assertTrue(ride(gs, miles50 / 25 - 50, 25.0, night = true).isEmpty())
        assertEquals(listOf(Achievement.SHADOW_RIDER), ride(gs, 100.0, 25.0, night = true))
        assertTrue(ride(AchievementTracker(store, "himalayan411"), miles50 / 25 + 50, 25.0, night = true).isEmpty(), "GS only")
    }

    @Test fun flightTimeIsOneHundredFootJump() {
        val store = MapStore(); val gs = AchievementTracker(store, "r1250gs")
        // 20 m/s: 1.4 s of air is 28 m, short of 100 ft (30.5 m); two of them with a landing between don't add up.
        assertTrue(ride(gs, 1.4, 20.0, air = true).isEmpty())
        ride(gs, 0.2, 20.0)
        assertTrue(ride(gs, 1.4, 20.0, air = true).isEmpty(), "two short jumps aren't one long one")
        ride(gs, 0.2, 20.0)
        assertEquals(listOf(Achievement.FLIGHT_TIME), ride(gs, 1.7, 20.0, air = true))
    }

    @Test fun legendaryHeritageIsTenHeroSectionsInARow() {
        val store = MapStore(); val gs = AchievementTracker(store, "r1250gs")
        repeat(9) { assertTrue(gs.heroResult(cleared = true).isEmpty()) }
        assertTrue(gs.heroResult(cleared = false).isEmpty(), "a failure starts the streak over")
        assertEquals(0, gs.heroStreak)
        // The streak carries over between rides.
        repeat(5) { gs.heroResult(cleared = true) }
        val next = AchievementTracker(store, "r1250gs")
        assertEquals(5, next.heroStreak)
        repeat(4) { assertTrue(next.heroResult(cleared = true).isEmpty()) }
        assertEquals(listOf(Achievement.LEGENDARY_HERITAGE), next.heroResult(cleared = true))
        assertTrue(next.heroResult(cleared = true).isEmpty(), "only once")
        assertTrue(AchievementTracker(store, "himalayan411").let { h -> (1..10).flatMap { h.heroResult(true) } }.isEmpty(), "GS only")
    }

    @Test fun gsPaintsBelongToTheGS() {
        assertEquals(Achievement.SHADOW_RIDER, Achievement.forPaint("r1250gs", "triple-black"))
        assertEquals(Achievement.FLIGHT_TIME, Achievement.forPaint("r1250gs", "adventure-racing-red"))
        assertEquals(Achievement.LEGENDARY_HERITAGE, Achievement.forPaint("r1250gs", "40-years-gs"))
        assertEquals(null, Achievement.forPaint("r1250gs", "rallye"))
        val ids = com.northwoodsadv.ride.bikes.Bikes.R1250GS.paints.map { it.id }
        for (a in Achievement.forBike("r1250gs")) assertTrue(a.paint in ids, "${a.paint} is one of the GS's paints")
        assertEquals("rallye", ids.first(), "Franco's own paint comes first")
    }
}
