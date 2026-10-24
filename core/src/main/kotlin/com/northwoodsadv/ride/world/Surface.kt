package com.northwoodsadv.ride.world

/**
 * Ground surface types and their tyre / chassis properties.
 *
 * - [mu]: peak friction coefficient for an ADV dual-sport tyre (50/50 tread).
 * - [slipPeak]: longitudinal slip ratio at peak force. Loose surfaces peak late because the
 *   tread digs in and bulldozes material.
 * - [anglePeakDeg]: slip angle at peak lateral force.
 * - [slideRatio]: force left once fully sliding, relative to the peak. Pavement drops off sharply
 *   (that is what makes a lowside sudden); gravel and sand barely drop, which is why you can
 *   steer a gravel slide with the throttle.
 * - [rolling]: rolling-resistance coefficient (mud and sand are draggy).
 * - [roughness]: amplitude (m) of small-scale bumps fed into the suspension.
 * - [sink]: how far the tyre sinks (m), shown visually and adding drag.
 */
enum class Surface(
    val label: String,
    val mu: Double,
    val slipPeak: Double,
    val anglePeakDeg: Double,
    val slideRatio: Double,
    val rolling: Double,
    val roughness: Double,
    val sink: Double,
    val color: Int,
) {
    ASPHALT("Asphalt", 1.05, 0.10, 6.0, 0.72, 0.015, 0.001, 0.0, 0x4a4c50),
    ASPHALT_WORN("Broken asphalt", 0.92, 0.11, 6.5, 0.75, 0.018, 0.006, 0.0, 0x5d5e60),
    GRAVEL_PACKED("Packed gravel", 0.70, 0.18, 10.0, 0.88, 0.025, 0.006, 0.005, 0x9a8a72),
    GRAVEL_LOOSE("Loose gravel", 0.56, 0.28, 14.0, 0.96, 0.040, 0.01, 0.02, 0xa89a84),
    DIRT("Dirt", 0.66, 0.20, 11.0, 0.90, 0.030, 0.01, 0.01, 0x7b6040),
    MUD("Mud", 0.32, 0.35, 16.0, 0.95, 0.120, 0.015, 0.10, 0x4a3a28),
    SAND("Sand", 0.42, 0.40, 18.0, 1.00, 0.140, 0.008, 0.08, 0xc9b98a),
    ROCK("Rock / ledge", 0.85, 0.10, 7.0, 0.70, 0.030, 0.025, 0.0, 0x8a8a86),
    WET_ROCK("Wet ledge", 0.38, 0.10, 7.0, 0.65, 0.030, 0.025, 0.0, 0x6a6c6c),
    GRASS("Grass", 0.52, 0.20, 12.0, 0.85, 0.050, 0.012, 0.01, 0x5e7a3a),
    FOREST("Forest floor", 0.50, 0.25, 13.0, 0.90, 0.060, 0.035, 0.03, 0x4a4228),
    WATER("Water crossing", 0.40, 0.25, 12.0, 0.90, 0.090, 0.03, 0.0, 0x3d5a6a),
    DEEP_WATER("Deep water", 0.20, 0.30, 15.0, 1.00, 0.300, 0.0, 0.0, 0x2a4a6a);

    val isPaved get() = this == ASPHALT || this == ASPHALT_WORN
    val isLoose get() = !isPaved && this != ROCK && this != WET_ROCK
}
