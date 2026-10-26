package com.northwoodsadv.ride.progress

/** What an [Achievement] measures. */
enum class Goal {
    /** Total distance (m) ridden with the wheels in water. */
    WATER_DISTANCE,
    /** Seconds in a row at or above a speed (m/s). */
    SUSTAINED_SPEED,
    /** Total distance (m) on Class VI roads and trails of routes the rider loaded. */
    CLASS_VI_OWN_ROUTES,
}

/**
 * Things to earn on the road, each on one bike and unlocking one of that bike's paint schemes.
 * Only riding that bike counts. What it takes stays secret until it's done: the garage only shows
 * a locked swatch, and the story is told when it unlocks. Future bikes add their own here.
 */
enum class Achievement(
    val id: String,
    val bikeId: String,
    val title: String,
    val story: String,
    val paint: String,
    val paintName: String,
    val goal: Goal,
    /** Metres for distances; seconds for [Goal.SUSTAINED_SPEED]. */
    val amount: Double,
    /** Speed (m/s) for [Goal.SUSTAINED_SPEED]. */
    val speed: Double = 0.0,
) {
    WATERBORNE("waterborne", "himalayan411", "Waterborne", "A mile of riding through water", "lake-blue", "Lake Blue",
        Goal.WATER_DISTANCE, 1 * AchievementTracker.MILE),
    FLAT_OUT("flat-out", "himalayan411", "Flat Out", "80 mph held for 30 seconds", "white-flame", "White Flame",
        Goal.SUSTAINED_SPEED, 30.0, 80 * AchievementTracker.MILE / 3600),
    LOST_IN_THE_WOODS("lost-in-the-woods", "himalayan411", "Lost in the Woods", "500 miles of Class VI on your own routes", "pine-green", "Pine Green",
        Goal.CLASS_VI_OWN_ROUTES, 500 * AchievementTracker.MILE);

    companion object {
        /** The achievement that unlocks [paintId] on [bikeId], or null if it's free. */
        fun forPaint(bikeId: String, paintId: String?) = entries.firstOrNull { it.bikeId == bikeId && it.paint == paintId }
        fun forBike(bikeId: String) = entries.filter { it.bikeId == bikeId }
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
 * Counts up the riding on bike [bikeId] that earns its [Achievement]s. Only the rider's own
 * riding counts: not the auto-ride demo, not while crashed or paused (the caller skips those).
 */
class AchievementTracker(private val store: ProgressStore, private val bikeId: String) {
    private val goals = Achievement.forBike(bikeId)
    private val waterKey = "progress.$bikeId.waterM"
    private val classVIKey = "progress.$bikeId.classVIM"
    private var waterM = store.number(waterKey)
    private var classVIM = store.number(classVIKey)
    private val heldFor = DoubleArray(goals.size)
    private var sinceSave = 0.0

    /**
     * One step of riding: [speed] in m/s; [inWater] wheels in water; [onClassVI] on a Class VI
     * road or trail; [ownRoute] the route is one the rider loaded (not a bundled sample).
     * Returns anything newly unlocked.
     */
    fun update(dt: Double, speed: Double, inWater: Boolean, onClassVI: Boolean, ownRoute: Boolean): List<Achievement> {
        if (goals.isEmpty()) return emptyList()
        val out = ArrayList<Achievement>(0)
        val d = speed * dt
        if (inWater) waterM += d
        if (onClassVI && ownRoute) classVIM += d
        for ((k, a) in goals.withIndex()) {
            val done = when (a.goal) {
                Goal.WATER_DISTANCE -> waterM >= a.amount
                Goal.CLASS_VI_OWN_ROUTES -> classVIM >= a.amount
                Goal.SUSTAINED_SPEED -> {
                    heldFor[k] = if (speed >= a.speed) heldFor[k] + dt else 0.0
                    heldFor[k] >= a.amount
                }
            }
            if (done && !store.isUnlocked(a)) { store.unlock(a); out += a }
        }
        sinceSave += dt
        if (out.isNotEmpty() || sinceSave > 10) save()
        return out
    }

    fun save() {
        if (goals.isEmpty()) return
        store.setNumber(waterKey, waterM); store.setNumber(classVIKey, classVIM)
        store.flush()
        sinceSave = 0.0
    }

    /** Crashing or handing over to auto-ride ends any run held at speed. */
    fun interrupt() { heldFor.fill(0.0) }

    companion object {
        const val MILE = 1609.344
    }
}
