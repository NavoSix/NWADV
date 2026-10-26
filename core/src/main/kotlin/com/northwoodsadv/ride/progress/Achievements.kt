package com.northwoodsadv.ride.progress

/**
 * Things to earn on the road; each unlocks a paint scheme. What it takes stays secret until it's
 * done: the garage only shows a locked swatch, and the story is told when it unlocks.
 */
enum class Achievement(val id: String, val title: String, val story: String, val paint: String, val paintName: String) {
    WATERBORNE("waterborne", "Waterborne", "A mile of riding through water", "lake-blue", "Lake Blue"),
    FLAT_OUT("flat-out", "Flat Out", "80 mph held for 30 seconds", "white-flame", "White Flame"),
    LOST_IN_THE_WOODS("lost-in-the-woods", "Lost in the Woods", "500 miles of Class VI on your own routes", "pine-green", "Pine Green");

    companion object {
        fun forPaint(paintId: String?) = entries.firstOrNull { it.paint == paintId }
    }
}

/** Where progress is kept (the game's preferences; a map in tests). */
interface ProgressStore {
    fun number(key: String): Double
    fun setNumber(key: String, v: Double)
    fun isUnlocked(a: Achievement): Boolean
    fun unlock(a: Achievement)
    fun flush()
}

/**
 * Counts up the riding that earns [Achievement]s. Only the rider's own riding counts: not the
 * auto-ride demo, not while crashed or paused.
 */
class AchievementTracker(private val store: ProgressStore) {
    private var waterM = store.number(WATER_KEY)
    private var classVIM = store.number(CLASS_VI_KEY)
    private var flatOutT = 0.0
    private var sinceSave = 0.0

    /**
     * One step of riding: [speed] in m/s; [inWater] wheels in water; [onClassVI] on a Class VI
     * road or trail; [ownRoute] the route is one the rider loaded (not a bundled sample).
     * Returns anything newly unlocked.
     */
    fun update(dt: Double, speed: Double, inWater: Boolean, onClassVI: Boolean, ownRoute: Boolean): List<Achievement> {
        val out = ArrayList<Achievement>(0)
        val d = speed * dt
        if (inWater) waterM += d
        if (onClassVI && ownRoute) classVIM += d
        flatOutT = if (speed >= FLAT_OUT_SPEED) flatOutT + dt else 0.0
        fun check(a: Achievement, done: Boolean) { if (done && !store.isUnlocked(a)) { store.unlock(a); out += a } }
        check(Achievement.WATERBORNE, waterM >= WATER_M)
        check(Achievement.FLAT_OUT, flatOutT >= FLAT_OUT_S)
        check(Achievement.LOST_IN_THE_WOODS, classVIM >= CLASS_VI_M)
        sinceSave += dt
        if (out.isNotEmpty() || sinceSave > 10) save()
        return out
    }

    fun save() {
        store.setNumber(WATER_KEY, waterM); store.setNumber(CLASS_VI_KEY, classVIM)
        store.flush()
        sinceSave = 0.0
    }

    /** A rider who stops riding loses the flat-out run. */
    fun interrupt() { flatOutT = 0.0 }

    companion object {
        private const val WATER_KEY = "progress.waterM"
        private const val CLASS_VI_KEY = "progress.classVIM"
        const val MILE = 1609.344
        const val WATER_M = 1 * MILE
        const val FLAT_OUT_SPEED = 80 * MILE / 3600
        const val FLAT_OUT_S = 30.0
        const val CLASS_VI_M = 500 * MILE
    }
}
