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
    private fun ride(t: AchievementTracker, seconds: Double, speed: Double, water: Boolean = false, classVI: Boolean = false, own: Boolean = true): List<Achievement> {
        val out = ArrayList<Achievement>(); var s = 0.0
        while (s < seconds) { out += t.update(0.1, speed, water, classVI, own); s += 0.1 }
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
}
