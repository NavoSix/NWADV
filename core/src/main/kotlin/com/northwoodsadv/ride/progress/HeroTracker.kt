package com.northwoodsadv.ride.progress

import com.northwoodsadv.ride.world.HeroSection
import com.northwoodsadv.ride.world.Road
import kotlin.math.abs

/** Milestones for hero sections cleared, on any bike. */
enum class HeroMilestone(val count: Int, val title: String) {
    INTO_THE_ROUGH(1, "Into the Rough"),
    ROCK_GARDEN(5, "Rock Garden"),
    HARD_WAY_HOME(10, "Hard Way Home"),
    GRANITE_STATE_HERO(25, "Granite State Hero"),
    CLASS_VI_LEGEND(50, "Class VI Legend"),
    NORTHWOODS_IMMORTAL(100, "Northwoods Immortal");

    val story get() = if (count == 1) "Your first hero section cleared" else "$count hero sections cleared"

    companion object {
        /** The milestone reached exactly at [cleared], if any. */
        fun at(cleared: Int) = entries.firstOrNull { it.count == cleared }
        /** The next one still to earn after [cleared], or null when they're all done. */
        fun next(cleared: Int) = entries.firstOrNull { it.count > cleared }
    }
}

/**
 * Watches the rider through hero sections. A run starts on riding into either end of a section,
 * and counts once the rider comes out of the far end without going down or leaving the trail
 * (off the road for more than [OFF_GRACE] seconds). Turning back, or handing over to auto-ride,
 * calls the run off without a verdict. Each section counts at most once per ride; the total is
 * kept in [store] for good.
 */
class HeroTracker(private val store: ProgressStore) {
    sealed class Event {
        class Entered(val hero: HeroSection) : Event()
        class Cleared(val hero: HeroSection, val total: Int, val milestone: HeroMilestone?) : Event()
        class Failed(val hero: HeroSection, val reason: String) : Event()
    }

    var cleared = store.number(KEY).toInt(); private set
    /** Section being ridden, or null. */
    var active: HeroSection? = null; private set
    private var activeRoad: Road? = null
    private var dir = 1
    private var offFor = 0.0
    private val done = HashSet<Pair<Long, Int>>()
    /** 0..1 through the active section. */
    var progress = 0.0; private set

    /**
     * One step. [road] and [along]/[lateral] are where the bike is (null road when off every
     * road); [speedAlong] the bike's speed along the road's direction (m/s, negative riding it
     * backward); [crashed] the rider is down; [auto] auto-ride is driving.
     */
    fun update(dt: Double, road: Road?, along: Double, lateral: Double, speedAlong: Double, crashed: Boolean, auto: Boolean): Event? {
        val h = active
        if (h == null) {
            if (road == null || crashed || auto) return null
            for (c in road.heroes) {
                if ((road.id to c.slot) in done) continue
                val d = when {
                    along >= c.s0 && along < c.s0 + ENTRY && speedAlong > 0.3 -> 1
                    along <= c.s1 && along > c.s1 - ENTRY && speedAlong < -0.3 -> -1
                    else -> continue
                }
                active = c; activeRoad = road; dir = d; offFor = 0.0; progress = 0.0
                return Event.Entered(c)
            }
            return null
        }
        val r = activeRoad!!
        if (crashed) return fail(h, "You went down")
        if (auto) { active = null; return null }
        val onTrail = road === r && abs(lateral) <= r.halfWidth + 2.0
        offFor = if (onTrail) 0.0 else offFor + dt
        if (offFor > OFF_GRACE) return fail(h, "You left the trail")
        if (road !== r) return null
        progress = (if (dir > 0) (along - h.s0) else (h.s1 - along)) / h.length
        if (progress >= 1.0) {
            done += r.id to h.slot
            active = null
            cleared++
            store.setNumber(KEY, cleared.toDouble()); store.flush()
            return Event.Cleared(h, cleared, HeroMilestone.at(cleared))
        }
        if (progress < -BACK_OUT / h.length) active = null // turned back out the way they came
        return null
    }

    private fun fail(h: HeroSection, reason: String): Event {
        // A failed section can be tried again (ride back to its start) on the same ride.
        active = null
        return Event.Failed(h, reason)
    }

    companion object {
        const val KEY = "hero.cleared"
        /** How far into a section counts as entering it (m). */
        const val ENTRY = 25.0
        const val OFF_GRACE = 1.5
        const val BACK_OUT = 5.0
    }
}
