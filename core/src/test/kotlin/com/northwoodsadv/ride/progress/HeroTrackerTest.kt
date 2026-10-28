package com.northwoodsadv.ride.progress

import com.northwoodsadv.ride.geo.Polyline
import com.northwoodsadv.ride.osm.RoadKind
import com.northwoodsadv.ride.osm.RoadSpec
import com.northwoodsadv.ride.terrain.FlatElevation
import com.northwoodsadv.ride.world.Road
import com.northwoodsadv.ride.world.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroTrackerTest {
    private class MapStore : ProgressStore {
        val numbers = HashMap<String, Double>()
        override fun number(key: String) = numbers[key] ?: 0.0
        override fun setNumber(key: String, v: Double) { numbers[key] = v }
        override fun isUnlocked(a: Achievement) = false
        override fun unlock(a: Achievement) {}
        override fun flush() {}
    }

    private val road = (1L..40L).map {
        Road(it, RoadSpec(RoadKind.CLASS_VI, Surface.DIRT, 3.2, 0.65, "Trail"), Polyline(doubleArrayOf(0.0, 3000.0), doubleArrayOf(0.0, 0.0)).resample(2.5), FlatElevation(200.0))
    }.first { it.heroes.isNotEmpty() }
    private val hero = road.heroes.first()

    /** Ride from [from] to [to] along the road at 5 m/s; [lateral] and [crashAt] as given. */
    private fun ride(t: HeroTracker, from: Double, to: Double, lateral: (Double) -> Double = { 0.0 }, crashAt: Double = Double.NaN,
                     auto: Boolean = false): List<HeroTracker.Event> {
        val out = ArrayList<HeroTracker.Event>()
        val dir = if (to > from) 1.0 else -1.0
        var s = from
        while ((to - s) * dir > 0) {
            val crashed = !crashAt.isNaN() && (s - crashAt) * dir >= 0
            t.update(0.1, road, s, lateral(s), 5.0 * dir, crashed, auto)?.let { out += it }
            if (crashed) break
            s += 0.5 * dir
        }
        return out
    }

    @Test fun clearingCountsOnceAndAwardsTheFirstMilestone() {
        val store = MapStore(); val t = HeroTracker(store)
        val ev = ride(t, hero.s0 - 50, hero.s1 + 30)
        assertIs<HeroTracker.Event.Entered>(ev.first())
        val c = assertIs<HeroTracker.Event.Cleared>(ev.last())
        assertEquals(1, c.total); assertEquals(HeroMilestone.INTO_THE_ROUGH, c.milestone)
        assertEquals(1.0, store.numbers[HeroTracker.KEY])
        // Riding back through it on the same ride doesn't count again.
        assertTrue(ride(t, hero.s1 + 30, hero.s0 - 50).isEmpty())
        // A new ride does, and remembers the total.
        val t2 = HeroTracker(store)
        assertEquals(2, assertIs<HeroTracker.Event.Cleared>(ride(t2, hero.s1 + 30, hero.s0 - 50).last()).total)
        assertNull(HeroMilestone.at(2)); assertEquals(HeroMilestone.ROCK_GARDEN, HeroMilestone.next(2))
    }

    @Test fun fallingOrLeavingTheTrailFails() {
        val t = HeroTracker(MapStore())
        val crash = ride(t, hero.s0 - 20, hero.s1 + 20, crashAt = hero.s0 + 60)
        assertEquals("You went down", assertIs<HeroTracker.Event.Failed>(crash.last()).reason)
        assertEquals(0, t.cleared)
        // A short wide line round a boulder is fine; 10 m (2 s) off the trail isn't.
        val ok = ride(t, hero.s0 - 20, hero.s1 + 20, lateral = { s -> if (s > hero.s0 + 80 && s < hero.s0 + 85) 4.0 else 0.0 })
        assertIs<HeroTracker.Event.Cleared>(ok.last())
        val t2 = HeroTracker(MapStore())
        val off = ride(t2, hero.s0 - 20, hero.s1 + 20, lateral = { s -> if (s > hero.s0 + 80 && s < hero.s0 + 90) 4.0 else 0.0 })
        assertEquals("You left the trail", assertIs<HeroTracker.Event.Failed>(off.last()).reason)
    }

    @Test fun turningBackOrAutoRideDoesNotCount() {
        val t = HeroTracker(MapStore())
        val ev = ride(t, hero.s0 - 20, hero.s0 + 40) + ride(t, hero.s0 + 40, hero.s0 - 40)
        assertEquals(1, ev.size); assertNull(t.active)
        assertTrue(ride(t, hero.s0 - 20, hero.s1 + 20, auto = true).isEmpty())
        assertEquals(0, t.cleared)
        // Riding out of the far end isn't going in.
        val t2 = HeroTracker(MapStore())
        assertTrue(ride(t2, hero.s1 - 10, hero.s1 + 30).isEmpty())
        assertNotNull(HeroMilestone.next(0))
    }
}
