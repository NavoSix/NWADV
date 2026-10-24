package com.northwoodsadv.ride.physics

/**
 * Physical parameters of the bike + rider. Defaults describe a typical mid-weight adventure
 * twin (21"/18" wheels, ~230 mm travel, ~100 hp) with a 90 kg rider in gear.
 */
class BikeSpec(
    val name: String = "Mid-weight ADV twin",
    val massBike: Double = 215.0,
    val massRider: Double = 90.0,
    val wheelbase: Double = 1.55,
    /** Horizontal distance CoM -> front axle (47/53 front/rear weight split with rider). */
    val cgToFront: Double = 0.82,
    val cgHeightSeated: Double = 0.78,
    /** Standing on the pegs raises the combined CoM but lets the bike move under the rider. */
    val cgHeightStanding: Double = 0.90,
    val rollInertia: Double = 35.0,
    val pitchInertia: Double = 110.0,
    val yawInertia: Double = 75.0,

    val frontRadius: Double = 0.355,
    val rearRadius: Double = 0.345,
    val frontWheelInertia: Double = 0.55,
    val rearWheelInertia: Double = 0.95,

    /** Wheel, tyre, brake disc and fork lowers / swingarm share (moves with the wheel). */
    val unsprungFront: Double = 17.0,
    val unsprungRear: Double = 22.0,
    val frontTyreStiffness: Double = 140_000.0,
    val rearTyreStiffness: Double = 160_000.0,

    val frontTravel: Double = 0.23,
    val rearTravel: Double = 0.22,
    val frontSpring: Double = 21_500.0,
    val rearSpring: Double = 22_500.0,
    val frontCompDamp: Double = 1_150.0,
    val frontRebDamp: Double = 1_900.0,
    val rearCompDamp: Double = 1_250.0,
    val rearRebDamp: Double = 2_100.0,

    /** Engine torque curve (rpm -> N m at the crank). */
    val torqueRpm: DoubleArray = doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6250.0, 7500.0, 8500.0, 9500.0),
    val torqueNm: DoubleArray = doubleArrayOf(48.0, 68.0, 80.0, 90.0, 97.0, 104.0, 100.0, 90.0, 74.0),
    val idleRpm: Double = 1300.0,
    val redlineRpm: Double = 9500.0,
    val engineInertia: Double = 0.12,
    val primaryRatio: Double = 1.9,
    val gearRatios: DoubleArray = doubleArrayOf(2.85, 1.95, 1.50, 1.22, 1.03, 0.89),
    val finalRatio: Double = 2.94,
    val drivelineEfficiency: Double = 0.92,

    val frontBrakeMax: Double = 1_350.0,
    val rearBrakeMax: Double = 450.0,
    val maxSteer: Double = 0.65,
    val dragArea: Double = 0.65,
    /** Lateral force per unit load per radian of camber (camber thrust). */
    val camberStiffness: Double = 0.9,
    /** Lean at which pegs / crash bars touch down. */
    val groundClearanceLean: Double = Math.toRadians(47.0),
) {
    val mass get() = massBike + massRider
    val cgToRear get() = wheelbase - cgToFront

    fun overallRatio(gear: Int) = if (gear <= 0) 0.0 else primaryRatio * gearRatios[gear - 1] * finalRatio

    fun engineTorque(rpm: Double): Double {
        if (rpm <= torqueRpm[0]) return torqueNm[0] * (rpm / torqueRpm[0]).coerceAtLeast(0.0)
        for (i in 1 until torqueRpm.size) {
            if (rpm <= torqueRpm[i]) {
                val t = (rpm - torqueRpm[i - 1]) / (torqueRpm[i] - torqueRpm[i - 1])
                return torqueNm[i - 1] + (torqueNm[i] - torqueNm[i - 1]) * t
            }
        }
        return torqueNm.last()
    }

    /** Engine braking / pumping losses with the throttle shut. */
    fun engineDrag(rpm: Double) = 12.0 + rpm * 0.0032
}
