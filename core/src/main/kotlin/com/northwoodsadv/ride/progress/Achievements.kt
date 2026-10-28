package com.northwoodsadv.ride.progress

/** What an [Achievement] measures. */
enum class Goal {
    /** Total distance (m) ridden with the wheels in water. */
    WATER_DISTANCE,
    /** Seconds in a row at or above a speed (m/s). */
    SUSTAINED_SPEED,
    /** Total distance (m) on Class VI roads and trails of routes the rider loaded. */
    CLASS_VI_OWN_ROUTES,
    /** Total distance (m) ridden after dark. */
    NIGHT_DISTANCE,
    /** Distance (m) covered in the air in a single jump. */
    JUMP_DISTANCE,
    /** Hero sections cleared in a row without failing one. */
    HERO_STREAK,
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
    /** Metres for distances; seconds for [Goal.SUSTAINED_SPEED]; sections for [Goal.HERO_STREAK]. */
    val amount: Double,
    /** Speed (m/s) for [Goal.SUSTAINED_SPEED]. */
    val speed: Double = 0.0,
) {
    WATERBORNE("waterborne", "himalayan411", "Waterborne", "A mile of riding through water", "lake-blue", "Lake Blue",
        Goal.WATER_DISTANCE, 1 * AchievementTracker.MILE),
    FLAT_OUT("flat-out", "himalayan411", "Flat Out", "80 mph held for 30 seconds", "white-flame", "White Flame",
        Goal.SUSTAINED_SPEED, 30.0, 80 * AchievementTracker.MILE / 3600),
    LOST_IN_THE_WOODS("lost-in-the-woods", "himalayan411", "Lost in the Woods", "500 miles of Class VI on your own routes", "pine-green", "Pine Green",
        Goal.CLASS_VI_OWN_ROUTES, 500 * AchievementTracker.MILE),
    SHADOW_RIDER("shadow-rider", "r1250gs", "Shadow Rider", "50 miles ridden at night", "triple-black", "Triple Black",
        Goal.NIGHT_DISTANCE, 50 * AchievementTracker.MILE),
    FLIGHT_TIME("flight-time", "r1250gs", "Flight Time", "100 feet through the air in one jump", "adventure-racing-red", "Adventure Racing Red",
        Goal.JUMP_DISTANCE, 100 * AchievementTracker.FOOT),
    LEGENDARY_HERITAGE("legendary-heritage", "r1250gs", "Legendary Heritage", "10 hero sections cleared in a row", "40-years-gs", "40 Years GS Edition",
        Goal.HERO_STREAK, 10.0);

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
    private val nightKey = "progress.$bikeId.nightM"
    private val streakKey = "progress.$bikeId.heroStreak"
    private var waterM = store.number(waterKey)
    private var classVIM = store.number(classVIKey)
    private var nightM = store.number(nightKey)
    /** Hero sections cleared in a row on this bike (kept between rides; a failure resets it). */
    var heroStreak = store.number(streakKey).toInt(); private set
    /** Distance covered since the wheels left the ground (0 on the ground). */
    private var jumpM = 0.0
    private val heldFor = DoubleArray(goals.size)
    private var sinceSave = 0.0

    /**
     * One step of riding: [speed] in m/s; [inWater] wheels in water; [onClassVI] on a Class VI
     * road or trail; [ownRoute] the route is one the rider loaded (not a bundled sample); [night]
     * it's dark; [airborne] both wheels are off the ground. Returns anything newly unlocked.
     */
    fun update(dt: Double, speed: Double, inWater: Boolean, onClassVI: Boolean, ownRoute: Boolean,
               night: Boolean = false, airborne: Boolean = false): List<Achievement> {
        if (goals.isEmpty()) return emptyList()
        val out = ArrayList<Achievement>(0)
        val d = speed * dt
        if (inWater) waterM += d
        if (onClassVI && ownRoute) classVIM += d
        if (night) nightM += d
        jumpM = if (airborne) jumpM + d else 0.0
        for ((k, a) in goals.withIndex()) {
            val done = when (a.goal) {
                Goal.WATER_DISTANCE -> waterM >= a.amount
                Goal.CLASS_VI_OWN_ROUTES -> classVIM >= a.amount
                Goal.NIGHT_DISTANCE -> nightM >= a.amount
                Goal.JUMP_DISTANCE -> jumpM >= a.amount
                Goal.HERO_STREAK -> heroStreak >= a.amount
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

    /**
     * A hero section ridden end to end ([cleared]) or failed: clears add to the streak, a failure
     * starts it over. Returns anything newly unlocked.
     */
    fun heroResult(cleared: Boolean): List<Achievement> {
        heroStreak = if (cleared) heroStreak + 1 else 0
        store.setNumber(streakKey, heroStreak.toDouble())
        val out = goals.filter { it.goal == Goal.HERO_STREAK && heroStreak >= it.amount && !store.isUnlocked(it) }
        for (a in out) store.unlock(a)
        store.flush()
        return out
    }

    fun save() {
        if (goals.isEmpty()) return
        store.setNumber(waterKey, waterM); store.setNumber(classVIKey, classVIM); store.setNumber(nightKey, nightM)
        store.flush()
        sinceSave = 0.0
    }

    /** Crashing or handing over to auto-ride ends any run held at speed, and any jump. */
    fun interrupt() { heldFor.fill(0.0); jumpM = 0.0 }

    companion object {
        const val MILE = 1609.344
        const val FOOT = 0.3048
    }
}
