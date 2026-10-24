package com.northwoodsadv.ride.physics

/** What the rider is doing with the controls this frame. */
class RiderInput {
    /** -1 (full left) .. 1 (full right). In LEAN mode this is the lean you ask for; in COUNTERSTEER mode bar pressure. */
    var steer = 0.0
    var throttle = 0.0
    var frontBrake = 0.0
    var rearBrake = 0.0
    var shiftUp = false
    var shiftDown = false
    var standing = false
    /** Rider weight shift fore (+) / aft (-), -1..1. Helps climbs, descents and wheelies. */
    var weightShift = 0.0

    fun clear() { steer = 0.0; throttle = 0.0; frontBrake = 0.0; rearBrake = 0.0; shiftUp = false; shiftDown = false; weightShift = 0.0 }
}

enum class SteerMode(val label: String) {
    /** You choose a lean angle; a virtual rider countersteers to get there (natural with phone tilt). */
    LEAN("Lean assist"),
    /** You push on the bars directly. Push right to go right. No help holding a lean. */
    COUNTERSTEER("Countersteer (expert)"),
}

class Assists(
    var steerMode: SteerMode = SteerMode.LEAN,
    var absFront: Boolean = true,
    var absRear: Boolean = true,
    var tractionControl: Boolean = true,
    var wheelieControl: Boolean = true,
    var autoShift: Boolean = true,
    /** Rider dabs / paddles at walking pace so you don't topple over in the rocks. */
    var lowSpeedBalance: Boolean = true,
    var maxLeanDeg: Double = 52.0,
)
