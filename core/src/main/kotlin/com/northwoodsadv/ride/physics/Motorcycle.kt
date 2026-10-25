package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.GroundSample
import com.northwoodsadv.ride.world.Noise
import com.northwoodsadv.ride.world.Obstacles
import com.northwoodsadv.ride.world.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

enum class CrashReason(val message: String) {
    LOWSIDE("Lowside! The tyres let go."),
    TIPOVER("Tip-over. Dabbed too late."),
    LOOPED("Looped it. Too much throttle."),
    ENDO("Over the bars!"),
    TREE("Hit a tree."),
    CASED("Cased the landing."),
    DROWNED("Drowned the bike. Too deep."),
}

/**
 * Motorcycle dynamics.
 *
 * Degrees of freedom:
 *  - planar motion (x, y, yaw) of the contact-line reference point beneath the centre of mass
 *  - heave (z) and pitch of the sprung mass on front and rear suspension
 *  - lean (roll) about the contact line
 *  - front and rear wheel spin, engine speed, and clutch slip
 *
 * Tyre forces come from the combined-slip model in [Tire] using the surface under each wheel.
 * Each wheel finds the ground through a 5-point radius envelope, so it rolls *over* rocks and
 * ledges rather than through them, and the contact normal pushes back on square edges. The
 * horizontal part of the contact normal is what makes ruts grab the front wheel, off-camber
 * corners push you downhill, and climbs cost power.
 *
 * Lean follows the moment balance about the contact line:
 *     I * lean'' = h * (Fz * sin(lean) - Flat * cos(lean))
 * so turning means generating lateral force, and generating it requires steering *out* of the
 * turn first. The rider controller below countersteers to reach the lean the player asks for
 * (or, in expert mode, passes bar pressure straight through).
 *
 * World axes are ENU metres: x east, y north, z up. Positive lean, yaw rate and steer are all
 * to the left.
 */
class Motorcycle(val spec: BikeSpec, private val ground: Ground) {
    // --- state -------------------------------------------------------------------------
    var x = 0.0; var y = 0.0
    var vx = 0.0; var vy = 0.0
    var yaw = 0.0; var yawRate = 0.0
    var z = 0.0; var vz = 0.0
    var pitch = 0.0; var pitchRate = 0.0
    var lean = 0.0; var leanRate = 0.0
    var steer = 0.0
    var omegaF = 0.0; var omegaR = 0.0
    var wheelAngleF = 0.0; var wheelAngleR = 0.0
    var engineOmega = 0.0
    var gear = 1
    var clutchLocked = false; private set
    var tanAlphaF = 0.0; private set
    var tanAlphaR = 0.0; private set
    private var shiftCut = 0.0; private var shiftCooldown = 0.0
    private var throttleSmoothed = 0.0
    /** Electronic aids: fraction of throttle / front brake currently allowed (1 = no intervention). */
    var tcFactor = 1.0; private set
    private var tcTrim = 1.0
    private var tcClutchCap = Double.MAX_VALUE
    private var wheelieFactor = 1.0
    private var absLiftFactor = 1.0
    private var absF = 1.0; private var absR = 1.0
    private var lastTerrainPitch = 0.0
    private var leanErrInt = 0.0
    var fOtherLatLast = 0.0; private set
    private var fzFrontFilt = 0.0
    private var fzTotalFilt = 0.0
    private var steerTarget = 0.0
    private var patchLoadF = 0.0; private var patchLoadR = 0.0
    private var rearLoadMemory = 0.0
    private var compPrevF = 0.0; private var compPrevR = 0.0
    private var hubF = 0.0; private var hubR = 0.0
    private var hubVF = 0.0; private var hubVR = 0.0
    var standing = false; private set

    // --- outputs for HUD / audio / camera -----------------------------------------------------
    var fzF = 0.0; private set
    var fzR = 0.0; private set
    var compF = 0.0; private set
    var compR = 0.0; private set
    var surfaceF = Surface.ASPHALT; private set
    var surfaceR = Surface.ASPHALT; private set
    var slipF = 0.0; private set
    var slipR = 0.0; private set
    var kappaR = 0.0; private set
    var kappaF = 0.0; private set
    var absActive = false; private set
    var tcActive = false; private set
    var scraping = false; private set
    var waterDepth = 0.0; private set
    var airborne = false; private set
    var lastImpact = 0.0; private set
    var crash: CrashReason? = null; private set
    /** Paddling backwards with the feet (hold the rear brake at a stop). */
    var paddling = false; private set
    /** Leg swing phase for the paddling animation (radians). */
    var paddlePhase = 0.0; private set
    private var reverseHold = 0.0
    /** Rider has a foot (or both) down: stopped, crawling, or paddling. */
    val feetDown get() = paddling || (abs(forwardSpeed) < 0.7 && throttleSmoothed < 0.15)
    private fun cy0() = cos(yaw)
    private fun sy0() = sin(yaw)

    /** True while a riding buddy is pushing you out of a hole. */
    var buddyPush = false; private set
    private var stuckTime = 0.0
    var odometer = 0.0; private set
    val sampleF = GroundSample()
    val sampleR = GroundSample()

    val forwardSpeed get() = vx * cos(yaw) + vy * sin(yaw)
    val speed get() = hypot(vx, vy)
    val rpm get() = engineOmega * 60 / (2 * PI)
    val cgHeight get() = if (standing) spec.cgHeightStanding else spec.cgHeightSeated

    private val tire = Tire.Result()
    private val tmp = GroundSample()
    private val treeTmp = Obstacles.Tree()
    private var treeCheckTimer = 0.0

    /** Place the bike at rest on the ground at (x, y) facing [heading] (radians from east). */
    fun reset(px: Double, py: Double, heading: Double) {
        x = px; y = py; yaw = heading
        vx = 0.0; vy = 0.0; yawRate = 0.0; vz = 0.0; pitchRate = 0.0
        lean = 0.0; leanRate = 0.0; steer = 0.0; omegaF = 0.0; omegaR = 0.0
        gear = 1; clutchLocked = false; engineOmega = spec.idleRpm * 2 * PI / 60
        paddling = false; reverseHold = 0.0
        tanAlphaF = 0.0; tanAlphaR = 0.0; leanErrInt = 0.0; fOtherLatLast = 0.0; steerTarget = 0.0; crash = null
        fzTotalFilt = spec.mass * G; fzFrontFilt = spec.mass * G * spec.cgToRear / spec.wheelbase
        val gf = ground.height(px + cos(heading) * spec.cgToFront, py + sin(heading) * spec.cgToFront)
        val gr = ground.height(px - cos(heading) * spec.cgToRear, py - sin(heading) * spec.cgToRear)
        pitch = atan((gf - gr) / spec.wheelbase)
        lastTerrainPitch = pitch
        val loadF = spec.mass * G * spec.cgToRear / spec.wheelbase
        val loadR = spec.mass * G * spec.cgToFront / spec.wheelbase
        hubF = gf - loadF / spec.frontTyreStiffness; hubR = gr - loadR / spec.rearTyreStiffness
        hubVF = 0.0; hubVR = 0.0
        z = (hubF * spec.cgToRear + hubR * spec.cgToFront) / spec.wheelbase + cgHeight - 0.005
        compPrevF = loadF / spec.frontSpring; compPrevR = loadR / spec.rearSpring
    }

    /** Advance by [dt] seconds (internally sub-stepped). */
    fun update(dt: Double, input: RiderInput, assists: Assists) {
        if (crash != null) { crashedUpdate(dt); return }
        var remaining = min(dt, 0.1)
        while (remaining > 1e-9) {
            val h = min(STEP, remaining)
            step(h, input, assists)
            remaining -= h
            if (crash != null) break
        }
        treeCheckTimer -= dt
        if (treeCheckTimer <= 0) { treeCheckTimer = 1 / 30.0; checkTrees() }
    }

    private fun crashedUpdate(dt: Double) {
        // Bike lies down and slides to a stop.
        val k = exp(-2.5 * dt)
        vx *= k; vy *= k; yawRate *= k
        x += vx * dt; y += vy * dt; yaw += yawRate * dt
        val side = if (lean >= 0) 1.0 else -1.0
        lean += (side * 1.35 - lean) * min(1.0, dt * 6)
        pitch *= k
        z += ((ground.height(x, y) + 0.35) - z) * min(1.0, dt * 8)
        omegaF *= k; omegaR *= k
        engineOmega += (spec.idleRpm * 2 * PI / 60 - engineOmega) * min(1.0, dt * 3)
    }

    private var stabilityNow = 0.0

    private fun step(dt: Double, input: RiderInput, a: Assists) {
        val m = spec.mass
        stabilityNow = a.stability
        casedRate = 7.5 + 7.5 * a.stability
        standing = input.standing
        val h = cgHeight
        val cy = cos(yaw); val sy = sin(yaw)
        val u = vx * cy + vy * sy

        // ---- rider: throttle smoothing, shifting, steering -----------------------------
        throttleSmoothed += (input.throttle - throttleSmoothed) * min(1.0, dt / 0.08)

        // Reverse: holding the rear brake at a standstill (no throttle, no front brake) puts the
        // rider's feet down to paddle the bike backwards.
        val wantBack = input.rearBrake > 0.5 && input.throttle < 0.05 && input.frontBrake < 0.2
        val uNow = vx * cy0() + vy * sy0()
        reverseHold = if (wantBack && (abs(uNow) < 0.25 || paddling)) reverseHold + dt else 0.0
        paddling = reverseHold > 0.4
        handleShifting(input, a, dt)
        steer = riderSteer(input, a, u, dt)

        // ---- suspension & ground contact -----------------------------------------------
        // Rider weight shift moves the effective CoM a few cm fore/aft.
        val shift = input.weightShift.coerceIn(-1.0, 1.0) * if (standing) 0.12 else 0.05
        val la = spec.cgToFront - shift
        val lb = spec.cgToRear + shift
        val fxF = x + cy * la; val fyF = y + sy * la
        val fxR = x - cy * lb; val fyR = y - sy * lb
        val wheelYaw = yaw + steer
        val gF = contact(fxF, fyF, cos(wheelYaw), sin(wheelYaw), spec.frontRadius, sampleF, gradF)
        val gR = contact(fxR, fyR, cy, sy, spec.rearRadius, sampleR, gradR)
        surfaceF = sampleF.surface; surfaceR = sampleR.surface
        waterDepth = max(sampleF.waterDepth, sampleR.waterDepth)

        val cp = cos(pitch); val sp = sin(pitch); val cl = cos(lean)
        val extF = h + spec.sagFront
        val extR = h + spec.sagRear
        val bottomF = z + la * sp - extF * cp * cl
        val bottomR = z - lb * sp - extR * cp * cl
        // Suspension: between the chassis and the unsprung wheel (hub position = tyre bottom).
        compF = hubF - bottomF
        compR = hubR - bottomR
        val rateF = (compF - compPrevF) / dt
        val rateR = (compR - compPrevR) / dt
        compPrevF = compF; compPrevR = compR
        val fsF = suspension(compF, rateF, spec.frontTravel, spec.frontSpring, spec.frontCompDamp, spec.frontRebDamp)
        val fsR = suspension(compR, rateR, spec.rearTravel, spec.rearSpring, spec.rearCompDamp, spec.rearRebDamp)
        // Tyre: a stiff spring between the wheel and the ground. This is what lets the wheel
        // skip over washboard and hop on braking bumps.
        fzF = tyreLoad(gF - hubF, -hubVF, spec.frontTyreStiffness)
        fzR = tyreLoad(gR - hubR, -hubVR, spec.rearTyreStiffness)
        hubVF += (fzF - fsF - spec.unsprungFront * G) / spec.unsprungFront * dt
        hubVR += (fzR - fsR - spec.unsprungRear * G) / spec.unsprungRear * dt
        hubF += hubVF * dt; hubR += hubVR * dt
        airborne = fzF <= 0 && fzR <= 0
        val impact = max(if (compF > spec.frontTravel) rateF else 0.0, if (compR > spec.rearTravel) rateR else 0.0)
        if (impact > lastImpact) lastImpact = impact
        lastImpact *= exp(-dt * 4)

        // ---- contact point velocities ---------------------------------------------------
        val vfx = vx - yawRate * (sy * la); val vfy = vy + yawRate * (cy * la)
        val vrx = vx + yawRate * (sy * lb); val vry = vy - yawRate * (cy * lb)
        val cwf = cos(wheelYaw); val swf = sin(wheelYaw)
        val uF = vfx * cwf + vfy * swf; val wF = -vfx * swf + vfy * cwf
        val uR = vrx * cy + vry * sy; val wR = -vrx * sy + vry * cy

        // Lateral slip with tyre relaxation length.
        relaxSlip(uF, wF, dt, true)
        relaxSlip(uR, wR, dt, false)

        // Pegs / crash bars on the ground: rear unloads and drags.
        scraping = abs(lean) > spec.groundClearanceLean && !airborne
        val gripR = if (scraping) 0.55 else 1.0

        // ---- drivetrain and brakes -----------------------------------------------------
        val nominalF = m * G * spec.cgToRear / spec.wheelbase
        val nominalR = m * G * spec.cgToFront / spec.wheelbase

        val nominalR0 = m * G * spec.cgToFront / spec.wheelbase
        // Front wheel (brake only).
        var brakeF = if (paddling) 0.0 else input.frontBrake.coerceIn(0.0, 1.0) * spec.frontBrakeMax
        val denomF = max(abs(uF), 1.5)
        var kF = (omegaF * spec.frontRadius - uF) / denomF
        absActive = false
        if (a.absFront && brakeF > 0 && abs(uF) > 1.5) {
            // Modulate toward a slip just under the peak so the tyre keeps some side grip.
            absF = absModulate(absF, kF, sampleF.surface.slipPeak * 0.85, dt)
            brakeF *= absF
            if (absF < 0.97) absActive = true
            // Rear-lift mitigation: back off the front brake as the rear tyre unloads.
            val liftLimit = Noise.smoothstep(0.08, 0.35, fzR / nominalR0)
            absLiftFactor = if (liftLimit < absLiftFactor) liftLimit else min(1.0, absLiftFactor + dt * 3)
            if (absLiftFactor < 0.98) absActive = true
            brakeF *= 0.55 + 0.45 * absLiftFactor
        }
        // The contact patch needs a relaxation length to build force after a load change too.
        val kzF = 1 - exp(-dt * (abs(uF) + 1.0) / LOAD_RELAX); val kzR = 1 - exp(-dt * (abs(uR) + 1.0) / LOAD_RELAX)
        patchLoadF += (fzF - patchLoadF) * kzF; patchLoadR += (fzR - patchLoadR) * kzR
        if (fzF <= 0) patchLoadF = 0.0
        if (fzR <= 0) patchLoadR = 0.0
        // Assist: the rear patch "remembers" its load through a brief hop off a rock, so it lands
        // still gripping instead of skating sideways (decays over ~1.5 m of travel).
        val kzRs = 1 - exp(-dt * (abs(uR) + 1.0) / 1.5)
        rearLoadMemory += (fzR - rearLoadMemory) * if (fzR > rearLoadMemory) kzR else kzRs
        val rearLoad = if (a.stability > 0) max(patchLoadR, rearLoadMemory * a.stability) else patchLoadR
        Tire.forces(patchLoadF, kF, tanAlphaF, lean, spec.camberStiffness, sampleF.surface, a.grip, nominalF, tire, a.lateralGrip)
        val fxTireF = tire.fx; val fyTireF = tire.fy
        slipF = tire.utilisation
        omegaF = wheelSpin(omegaF, 0.0, brakeF, fxTireF, tire.dFxdKappa, spec.frontRadius, spec.frontWheelInertia, denomF, dt)
        kF = (omegaF * spec.frontRadius - uF) / denomF
        kappaF = kF

        val omegaRBefore = omegaR
        // Rear wheel: engine through clutch and gearbox.
        val ratio = spec.overallRatio(gear)
        var thr = throttleSmoothed
        if (shiftCut > 0) thr = 0.0
        val denomR = max(abs(uR), 1.5)
        var kR = (omegaR * spec.rearRadius - uR) / denomR
        // Traction control: feed-forward throttle limit from the traction the rear tyre can take
        // (grip x load vs engine torque x gearing), trimmed by a slow loop on measured wheelspin.
        // Wheelie control: back off as the front goes light and the nose comes up.
        var limit = 1.0
        val rpmPre = engineOmega * 60 / (2 * PI)
        if (a.tractionControl && ratio > 0) {
            val spin = if (omegaR > 0) (omegaR * spec.rearRadius - uR) / max(abs(uR), 1.5) else 0.0
            val sx = spin / sampleR.surface.slipPeak
            // Lean-sensitive (combined) once moving; plain wheelspin at walking pace.
            val sy = if (uR > 5.0) tanAlphaR / tan(Math.toRadians(sampleR.surface.anglePeakDeg)) else 0.0
            val slip = if (spin > 0) sqrt(sx * sx + sy * sy) else 0.0
            tcTrim = (tcTrim - dt * 6.0 * (slip - 0.95)).coerceIn(0.3, 2.5)
            val grip = sampleR.surface.mu * a.grip * max(patchLoadR, 200.0) * spec.rearRadius
            val drive = max(spec.engineTorque(max(rpmPre, spec.idleRpm)), 1.0) * ratio * spec.drivelineEfficiency
            limit = (grip / drive * tcTrim).coerceIn(0.0, 1.0)
            tcClutchCap = grip * tcTrim / (ratio * spec.drivelineEfficiency)
        } else tcClutchCap = Double.MAX_VALUE
        var wLimit = 1.0
        if (a.wheelieControl) {
            val lift = (pitch - terrainPitch(gF, gR) - 0.10) / 0.08
            val unload = 1 - fzF / (0.5 * nominalF)
            if (lift > 0 && unload > 0) wLimit = 1 - (lift * unload).coerceIn(0.0, 1.0)
        }
        limit = min(limit, wLimit)
        wheelieFactor += (wLimit - wheelieFactor) * min(1.0, dt * 30)
        tcFactor += (limit - tcFactor) * min(1.0, dt * 30)
        tcActive = tcFactor < 0.97 && thr > tcFactor
        // Locked clutch: trim the throttle. Slipping clutch: the revs stay up and the clutch
        // torque is capped instead (what a rider feathering the clutch does).
        if (clutchLocked) thr = min(thr, tcFactor)
        val rpmNow = engineOmega * 60 / (2 * PI)
        var engT = if (rpmNow >= spec.redlineRpm) -spec.engineDrag(rpmNow)
        else thr * spec.engineTorque(rpmNow) - (1 - thr) * spec.engineDrag(rpmNow)
        // Idle governor (only with the clutch slipping / in neutral; lugging the engine below idle
        // in gear makes the auto clutch let go instead).
        if (rpmNow < spec.idleRpm && !clutchLocked) engT = max(engT, (spec.idleRpm - rpmNow) * 0.15)

        var brakeR = if (paddling) 0.0 else input.rearBrake.coerceIn(0.0, 1.0) * spec.rearBrakeMax
        if (a.absRear && brakeR > 0 && abs(uR) > 1.5) {
            absR = absModulate(absR, kR, sampleR.surface.slipPeak * 0.75, dt)
            brakeR *= absR
            if (absR < 0.97) absActive = true
        } else absR = 1.0
        Tire.forces(rearLoad, kR, tanAlphaR, lean, spec.camberStiffness, sampleR.surface, gripR * a.grip, nominalR, tire, a.lateralGrip * (1 + 0.25 * a.stability))
        val fxTireR = tire.fx; val fyTireR = tire.fy
        slipR = tire.utilisation

        if (ratio > 0 && clutchLocked) {
            // Rigid: engine inertia reflected through the gearbox.
            val inertia = spec.rearWheelInertia + spec.engineInertia * ratio * ratio
            omegaR = wheelSpin(omegaR, engT * ratio * spec.drivelineEfficiency, brakeR, fxTireR, tire.dFxdKappa,
                spec.rearRadius, inertia, denomR, dt)
            engineOmega = omegaR * ratio
            if (engineOmega * 60 / (2 * PI) < spec.idleRpm * 0.97) clutchLocked = false
        } else {
            // Slipping clutch (centrifugal-style auto clutch engages with revs). Engine and wheel
            // are coupled through a 16:1 ratio, so the clutch is solved implicitly with the tyre.
            val engage = if (ratio == 0.0) 0.0 else Noise.smoothstep(spec.idleRpm * 1.1, 2900.0, rpmNow)
            val slip0 = engineOmega - omegaR * ratio
            val cmax = min(200.0 * engage, tcClutchCap) * wheelieFactor
            val th = tanh(slip0 / CLUTCH_SLIP)
            val tc0 = cmax * th
            val kc = cmax / CLUTCH_SLIP * (1 - th * th)
            val eta = spec.drivelineEfficiency
            val r = spec.rearRadius
            val j = spec.rearWheelInertia + dt * tire.dFxdKappa * r * r / denomR
            val a11 = spec.engineInertia + dt * kc; val a12 = -dt * kc * ratio
            val a21 = -dt * ratio * eta * kc; val a22 = j + dt * ratio * ratio * eta * kc
            val b1 = dt * (engT - tc0); val b2 = dt * (ratio * eta * tc0 - fxTireR * r)
            val det = a11 * a22 - a12 * a21
            engineOmega += (b1 * a22 - a12 * b2) / det
            omegaR = brakeFriction(omegaR + (a11 * b2 - a21 * b1) / det, brakeR, j, dt)
            engineOmega = max(engineOmega, spec.idleRpm * 0.6 * 2 * PI / 60)
            if (ratio > 0 && engage > 0.98 && abs(engineOmega - omegaR * ratio) < 10) { clutchLocked = true; engineOmega = omegaR * ratio }
        }
        kR = (omegaR * spec.rearRadius - uR) / denomR
        kappaR = kR

        // ---- assemble forces (world frame, horizontal) ------------------------------------
        var fx = 0.0; var fy = 0.0; var mz = 0.0
        var fLong = 0.0

        fun applyWheel(px: Double, py: Double, fz: Double, grad: DoubleArray,
                       tfx: Double, tfy: Double, uw: Double, surf: Surface, depth: Double, cdir: Double, sdir: Double) {
            // Tyre force (wheel frame -> world).
            var ffx = tfx * cdir - tfy * sdir
            var ffy = tfx * sdir + tfy * cdir
            // Horizontal part of the contact normal force (slopes, ruts, rock faces).
            val gx = grad[0].coerceIn(-3.0, 3.0); val gy = grad[1].coerceIn(-3.0, 3.0)
            ffx += -gx * fz; ffy += -gy * fz
            // Rolling resistance and sinkage.
            val rr = surf.rolling * fz * tanh(uw / 0.4)
            ffx -= rr * cdir; ffy -= rr * sdir
            // Water drag on the wheel.
            if (depth > 0) {
                val wd = 260.0 * depth * uw * abs(uw)
                ffx -= wd * cdir; ffy -= wd * sdir
            }
            fx += ffx; fy += ffy
            mz += (px - x) * ffy - (py - y) * ffx
            fLong += ffx * cy + ffy * sy
        }
        applyWheel(fxF, fyF, fzF, gradF, fxTireF, fyTireF, uF, sampleF.surface, sampleF.waterDepth, cwf, swf)
        applyWheel(fxR, fyR, fzR, gradR, fxTireR, fyTireR, uR, sampleR.surface, sampleR.waterDepth, cy, sy)
        // Lateral contact force, for the lean balance.
        val fLat = -fx * sy + fy * cy
        // Everything except the front tyre's own lateral force (for the rider's steering).
        val frontTireLat = -(fxTireF * cwf - fyTireF * swf) * sy + (fxTireF * swf + fyTireF * cwf) * cy
        val kf = 1 - exp(-dt / 0.03)
        fOtherLatLast = fLat - frontTireLat
        fzFrontFilt += (fzF - fzFrontFilt) * kf
        fzTotalFilt += (fzF + fzR - fzTotalFilt) * kf

        // Bodywork dragging on the ground.
        if (scraping) {
            val drag = 0.3 * (fzF + fzR) * tanh(u / 0.5)
            fx -= drag * cy; fy -= drag * sy
        }
        // Aerodynamic drag.
        val v = hypot(vx, vy)
        val cda = spec.dragArea + if (standing) 0.12 else 0.0
        val aero = 0.5 * 1.2 * cda * v
        fx -= aero * vx; fy -= aero * vy

        // Low-speed balance: below ~5 mph the rider's feet and body hold the bike upright.
        val lowW = if (a.lowSpeedBalance) 1 - Noise.smoothstep(UPRIGHT_SPEED * 0.9, UPRIGHT_SPEED * 2.0, abs(u)) else 0.0
        val leanWanted = requestedLean(input, a, u)

        // Paddling backwards with both feet, up to a slow walk.
        if (paddling) {
            val push = ((-REVERSE_SPEED - u) * 700.0).coerceIn(-500.0, 300.0)
            fx += push * cy; fy += push * sy
            paddlePhase += abs(u) * dt * 3.2
        }

        // Buddy push (easy mode): stuck with the throttle open, a riding buddy shoves you free.
        if (a.stability >= 0.5 && throttleSmoothed > 0.25 && abs(u) < 0.6) stuckTime += dt else if (abs(u) > 1.8 || throttleSmoothed < 0.15) stuckTime = 0.0
        buddyPush = stuckTime > 1.2
        if (buddyPush) { fx += 0.4 * m * G * cy; fy += 0.4 * m * G * sy }

        // ---- integrate planar motion ---------------------------------------------------
        // Yaw stability assist: keep the bike turning at the rate its steering asks for, so a
        // rock under one wheel doesn't slew it off line.
        if (a.stability > 0 && abs(u) > 1.5) {
            val rKin = u * tan(steer) / spec.wheelbase
            mz -= (2200.0 * a.stability * (yawRate - rKin)).coerceIn(-2500.0, 2500.0)
            // ...and damp sideways skating of the whole bike.
            val vl = -vx * sy + vy * cy
            val damp = 0.35 * a.stability * m * vl
            fx -= damp * -sy; fy -= damp * cy
        }
        vx += fx / m * dt; vy += fy / m * dt
        yawRate += mz / spec.yawInertia * dt
        // Feet down: kill sideways creep and spinning in place.
        if (lowW > 0 && throttleSmoothed < 0.05 && abs(u) < 0.6) {
            val k = exp(-dt * 6 * lowW)
            val ul = -vx * sy + vy * cy
            vx -= (1 - k) * ul * -sy; vy -= (1 - k) * ul * cy
            yawRate *= k
        }
        x += vx * dt; y += vy * dt
        yaw += yawRate * dt
        if (yaw > PI) yaw -= 2 * PI else if (yaw < -PI) yaw += 2 * PI
        odometer += abs(u) * dt

        // ---- heave & pitch -----------------------------------------------------------
        val sprung = m - spec.unsprungFront - spec.unsprungRear
        vz += ((fsF + fsR) / sprung - G) * dt
        z += vz * dt
        var pitchMoment = fsF * la * cp - fsR * lb * cp + fLong * h * if (standing) 0.85 else 1.0
        // Spinning the rear wheel up pitches the chassis nose-up and braking it pitches it down:
        // the only pitch control you have in the air.
        pitchMoment += spec.rearWheelInertia * (omegaR - omegaRBefore) / dt
        // The rider's arms and legs soak up pitch, far more so when standing.
        val terrainPitchRate = (terrainPitch(gF, gR) - lastTerrainPitch) / dt
        lastTerrainPitch = terrainPitch(gF, gR)
        val pitchDamp = (if (standing) 260.0 else 90.0) + 450.0 * a.stability
        pitchMoment -= pitchDamp * (pitchRate - if (airborne) 0.0 else terrainPitchRate.coerceIn(-3.0, 3.0))
        // Stabiliser also keeps the chassis roughly parallel to the ground (no endos off rocks).
        if (a.stability > 0) pitchMoment -= 900.0 * a.stability * (pitch - terrainPitch(gF, gR)).coerceIn(-0.6, 0.6) * if (airborne) 0.3 else 1.0
        pitchRate += pitchMoment / spec.pitchInertia * dt
        pitchRate *= exp(-dt * 0.5)
        pitch += pitchRate * dt

        // ---- lean -------------------------------------------------------------------
        val fz = fzF + fzR
        val iRoll = spec.rollInertia + m * h * h
        var leanTorque = h * (fz * sin(lean) - fLat * cos(lean))
        if (standing) leanTorque *= 0.9
        if (paddling) leanTorque += (-8000.0 * lean - 1500.0 * leanRate).coerceIn(-3000.0, 3000.0)
        if (lowW > 0) {
            // Balance by paddling / dabbing at walking pace: strong enough to stay upright even
            // while turning at full lock.
            leanTorque += lowW * (-8000.0 * (lean - leanWanted) - 1500.0 * leanRate).coerceIn(-3000.0, 3000.0)
        }
        if (airborne) leanTorque += -40.0 * leanRate
        // Body English: the rider shifts their weight over the bike to balance it. Limited by how
        // far ~90 kg can move (about 15 cm standing), so it only matters at low speed.
        val bodyMax = if (standing) 200.0 else 110.0
        val leanGoal = leanWanted
        val bodyW = 1 - Noise.smoothstep(3.0, 9.0, abs(u))
        leanTorque += bodyW * (-900.0 * (lean - leanGoal) - 260.0 * leanRate).coerceIn(-bodyMax, bodyMax)
        // Stability assist: a stabiliser holding the requested lean (lean mode) or damping lean
        // changes (countersteer mode), strongest at low speed where balancing is hardest.
        if (a.stability > 0) {
            val goal = leanWanted
            val lowBoost = 1 + 1.5 * (1 - Noise.smoothstep(2.0, 10.0, abs(u)))
            val k = 1500.0 * a.stability * lowBoost; val c = 420.0 * a.stability * lowBoost
            val lim = 1800.0 * a.stability * lowBoost
            leanTorque += (-k * (lean - goal) - c * leanRate).coerceIn(-lim, lim)
        }
        leanRate += leanTorque / iRoll * dt
        lean += leanRate * dt

        checkCrash(gF, gR, rateF, rateR, u)
        if (x.isNaN() || lean.isNaN() || z.isNaN()) error("Physics blew up")

        wheelAngleF += omegaF * dt; wheelAngleR += omegaR * dt
        if (shiftCut > 0) shiftCut -= dt
        if (shiftCooldown > 0) shiftCooldown -= dt
    }

    private val gradF = DoubleArray(2)
    private val gradR = DoubleArray(2)
    private val envOffsets = doubleArrayOf(-0.24, -0.12, 0.0, 0.12, 0.24)

    /**
     * Ground height under a wheel using a radius envelope, plus the world-space height gradient
     * of that envelope (for the contact normal). Fills [s] with the centre sample.
     */
    private fun contact(px: Double, py: Double, dx: Double, dy: Double, r: Double, s: GroundSample, grad: DoubleArray): Double {
        ground.sample(px, py, s, true)
        val hc = envelope(px, py, dx, dy, r, s.height)
        val e = 0.15
        val hf = envelope(px + dx * e, py + dy * e, dx, dy, r, null)
        val hb = envelope(px - dx * e, py - dy * e, dx, dy, r, null)
        // Sideways the round tyre profile envelopes sharp edges, so use a wider baseline.
        val el = 0.2
        val hl = ground.height(px - dy * el, py + dx * el)
        val hr = ground.height(px + dy * el, py - dx * el)
        val dLong = (hf - hb) / (2 * e)
        // ...and the carcass flexes sideways, absorbing part of a glancing hit.
        val dLat = ((hl - hr) / (2 * el)).coerceIn(-1.0, 1.0) * 0.6 * (1 - 0.8 * stabilityNow)
        // Convert along/lateral gradient into world x/y gradient.
        grad[0] = dLong * dx - dLat * dy
        grad[1] = dLong * dy + dLat * dx
        return hc
    }

    private fun envelope(px: Double, py: Double, dx: Double, dy: Double, r: Double, centre: Double?): Double {
        var best = -Double.MAX_VALUE
        for (o in envOffsets) {
            val gh = if (o == 0.0 && centre != null) centre else ground.height(px + dx * o, py + dy * o)
            val v = gh - (r - sqrt(r * r - o * o))
            if (v > best) best = v
        }
        return best
    }

    private fun suspension(comp: Double, rate: Double, travel: Double, k: Double, cComp: Double, cReb: Double): Double {
        // Topped out: the fork / shock stops the wheel hanging lower than full extension.
        if (comp <= 0) return 250_000.0 * comp + 1_500.0 * rate
        var f = k * comp + damper(rate, if (rate > 0) cComp else cReb)
        // Hydraulic bottoming cushion over the last 30 mm, then the stop.
        val cushion = comp - (travel - 0.03)
        if (cushion > 0) f += 60_000.0 * cushion + 2_000_000.0 * cushion * cushion + 1_500.0 * max(rate, 0.0) * (cushion / 0.03).coerceAtMost(1.0)
        if (comp > travel) f += 600_000.0 * (comp - travel) + 6_000.0 * max(rate, 0.0)
        return f
    }

    /**
     * Digressive damping: firm at low shaft speed (chassis control), blowing off at high shaft
     * speed so sharp hits like washboard, rocks and roots don't get transmitted to the chassis.
     */
    private fun damper(v: Double, c: Double): Double {
        val av = abs(v)
        val knee = 0.15
        val f = if (av < knee) c * av else c * knee + c * 0.15 * (av - knee)
        return f * sign(v)
    }

    /** ABS pressure modulation: dump fast when slip exceeds the target, re-apply more slowly. */
    private fun absModulate(f: Double, kappa: Double, target: Double, dt: Double): Double {
        val over = -kappa / target - 1
        return if (over > 0) max(0.05, f - dt * (20 + 60 * over)) else min(1.0, f + dt * 6)
    }

    private fun tyreLoad(deflection: Double, rate: Double, k: Double): Double {
        if (deflection <= 0) return 0.0
        // Progressive once the carcass is squashed (pinch flat territory).
        var f = k * deflection + spec.tyreDamping * rate
        if (deflection > 0.04) f += 2_000_000.0 * (deflection - 0.04) * (deflection - 0.04)
        return max(f, 0.0)
    }

    private fun relaxSlip(uw: Double, ww: Double, dt: Double, front: Boolean) {
        val denom = max(abs(uw), 1.5)
        val target = -ww / denom
        val rate = (abs(uw) + 1.5) / RELAX_LENGTH
        val k = 1 - exp(-rate * dt)
        if (front) tanAlphaF += (target - tanAlphaF) * k else tanAlphaR += (target - tanAlphaR) * k
    }

    /** Implicit wheel spin update (tyre force linearised in slip) with brake as friction. */
    private fun wheelSpin(omega: Double, drive: Double, brake: Double, fxTire: Double, dFdK: Double, r: Double,
                          inertia: Double, denom: Double, dt: Double): Double {
        val j = inertia + dt * dFdK * r * r / denom
        return brakeFriction(omega + dt * (drive - fxTire * r) / j, brake, j, dt)
    }

    /** Brake as Coulomb friction: it can stop the wheel but never spin it backwards. */
    private fun brakeFriction(next: Double, brake: Double, inertia: Double, dt: Double): Double {
        if (brake <= 0) return next
        val brakeDelta = dt * brake / inertia
        return if (abs(next) <= brakeDelta) 0.0 else next - sign(next) * brakeDelta
    }

    private fun handleShifting(input: RiderInput, a: Assists, dt: Double) {
        if (shiftCooldown > 0) return
        // Shift on road speed (like a real auto-shifter), not on revs from a spinning wheel.
        val rpmNow = min(rpm, max(forwardSpeed, 0.0) / spec.rearRadius * spec.overallRatio(gear) * 60 / (2 * PI) * 1.05)
        var want = 0
        if (input.shiftUp) want = 1 else if (input.shiftDown) want = -1
        else if (a.autoShift) {
            val upAt = 4600 + 3800 * throttleSmoothed
            if (rpmNow > upAt && gear < spec.gearRatios.size && tcFactor > 0.9) want = 1
            else if (rpmNow < 2600 + 1600 * throttleSmoothed && gear > 1 && throttleSmoothed > 0.3) want = -1
            else if (rpmNow < 2200 && gear > 1) want = -1
        }
        if (want == 0) return
        val ng = (gear + want).coerceIn(1, spec.gearRatios.size)
        if (ng == gear) return
        // Don't let a downshift over-rev the engine.
        if (want < 0 && clutchLocked && omegaR * spec.overallRatio(ng) * 60 / (2 * PI) > spec.redlineRpm) return
        gear = ng
        shiftCut = 0.07; shiftCooldown = 0.35
        if (clutchLocked) engineOmega = omegaR * spec.overallRatio(gear)
    }

    /**
     * The virtual rider's hands on the bars.
     *
     * Outer loop: a lateral-force command that holds m*g*tan(lean) plus a PD + leaky-integral
     * correction toward the requested lean (lean mode), or bar pressure (countersteer mode).
     * Inner loop: invert the front tyre curve to find the slip angle that produces the front
     * share of that force, then add the flow angle at the front contact patch to get a steer
     * angle. To lean in you need less lateral force than gravity's share, so the bars turn
     * *away* from the turn first: countersteering emerges, it isn't scripted. If the request
     * needs more grip than the surface has, the command saturates and physics takes over.
     */
    private fun riderSteer(input: RiderInput, a: Assists, u: Double, dt: Double): Double {
        val m = spec.mass
        var falling = false
        val tanL = tan(lean.coerceIn(-1.3, 1.3))
        val k2 = if (standing) 8.5 else 7.1
        val ay = when (a.steerMode) {
            SteerMode.LEAN -> {
                val target = requestedLean(input, a, u)
                val err = lean - target
                leanErrInt = (leanErrInt + err * dt) * exp(-dt * 0.6)
                leanErrInt = leanErrInt.coerceIn(-0.5, 0.5)
                // A smooth rider doesn't ask more of the front tyre than the surface offers.
                // Falling further from the requested lean: use whatever grip it takes to save it.
                falling = err * lean > 0 && abs(err) > 0.08
                val corrMax = (if (falling) 0.9 else 0.45) * sampleF.surface.mu * a.grip * G
                G * tanL + (18.7 * err + 8.0 * leanErrInt).coerceIn(-corrMax, corrMax) +
                    (k2 * leanRate).coerceIn(-1.6 * corrMax, 1.6 * corrMax)
            }
            SteerMode.COUNTERSTEER -> G * tanL + 0.8 * lean + k2 * 0.7 * leanRate + 12.0 * input.steer.coerceIn(-1.0, 1.0)
        }
        val uc = max(abs(u), 1.0)
        // Front tyre's share of the command (rear and terrain forces are what they are).
        val fFrontCmd = m * ay * fzTotalFilt.coerceAtLeast(1.0) / (m * G) - fOtherLatLast
        val surf = sampleF.surface
        val tanPeak = tan(Math.toRadians(surf.anglePeakDeg))
        val cap = max(surf.mu * a.grip * max(fzFrontFilt, 0.35 * m * G * spec.cgToRear / spec.wheelbase), 1.0)
        // Deliberately using more than ~80% of the front is what makes riders crash; the virtual
        // rider won't (the player can still exceed it by asking for more lean than grip allows).
        val yN = (abs(fFrontCmd) / cap).coerceAtMost(if (falling || abs(G * tanL) > 0.75 * surf.mu * a.grip * G) 0.97 else 0.8)
        val sN = 1 - sqrt(1 - yN)
        val corneringPerRad = 2 * surf.mu / tanPeak
        val camberEq = spec.camberStiffness * (surf.mu / 1.05).coerceAtMost(1.0) * sin(lean) / corneringPerRad
        val tanAlphaNeeded = sign(fFrontCmd) * sN * tanPeak - camberEq
        val cy = cos(yaw); val sy = sin(yaw)
        val vLat = -vx * sy + vy * cy
        val flow = atan((vLat + yawRate * spec.cgToFront) / uc)
        var hs = (atan(tanAlphaNeeded) + flow) * sign(u + 1e-9)
        if (fzFrontFilt < 80) hs = steer // front in the air: bars stay put
        // At walking pace the rider just points the bars where they want to go (bike upright).
        val lowW = 1 - Noise.smoothstep(UPRIGHT_SPEED, UPRIGHT_SPEED * 2.0, abs(u))
        val ls = -input.steer.coerceIn(-1.0, 1.0) * spec.maxSteer
        val target = (hs * (1 - lowW) + ls * lowW).coerceIn(-spec.maxSteer, spec.maxSteer)
        steerTarget = target
        val maxRate = 6.0 * dt
        return steer + (steerTarget - steer).coerceIn(-maxRate, maxRate)
    }

    /**
     * Lean the rider is asking for. Below [UPRIGHT_SPEED] (5 mph) the bike stays upright and the
     * bars steer directly; lean then fades in by [LEAN_IN_SPEED] (10 mph) and grows with speed,
     * because full stick asks for the lean that full lock (tightest turn) would need, capped by
     * the lean setting and, with assists, by the grip underneath.
     */
    fun requestedLean(input: RiderInput, a: Assists, u: Double): Double {
        if (a.steerMode != SteerMode.LEAN) return lean
        return -input.steer.coerceIn(-1.0, 1.0) * leanAuthority(a, u)
    }

    /** Lean (rad) that full stick asks for at speed [u]. */
    fun leanAuthority(a: Assists, u: Double): Double {
        val au = abs(u)
        val ramp = Noise.smoothstep(UPRIGHT_SPEED, LEAN_IN_SPEED, au)
        val ayMax = au * au * tan(spec.maxSteer) / spec.wheelbase
        var cap = min(Math.toRadians(a.maxLeanDeg), atan(ayMax / G) * 0.85) * ramp
        // With assists, full stick never asks for more lean than the surface under you can hold.
        if (a.stability > 0) {
            val gripLean = atan(0.85 * min(sampleF.surface.mu, sampleR.surface.mu) * a.grip * a.lateralGrip.coerceAtMost(1.2))
            cap = min(cap, cap + (gripLean - cap) * a.stability)
        }
        return cap
    }

    private fun terrainPitch(gF: Double, gR: Double) = atan((gF - gR) / spec.wheelbase)

    /** Bottoming speed (m/s) that counts as casing it; easy mode forgives a lot more. */
    private var casedRate = 7.5

    private fun checkCrash(gF: Double, gR: Double, rateF: Double, rateR: Double, u: Double) {
        val relPitch = pitch - terrainPitch(gF, gR)
        crash = when {
            abs(lean) > 1.08 -> if (abs(u) < 2.5) CrashReason.TIPOVER else CrashReason.LOWSIDE
            relPitch > 1.05 -> CrashReason.LOOPED
            relPitch < -0.75 -> CrashReason.ENDO
            (compF > spec.frontTravel + 0.06 && rateF > casedRate) || (compR > spec.rearTravel + 0.06 && rateR > casedRate) -> CrashReason.CASED
            waterDepth > 0.8 || (sampleF.surface == Surface.DEEP_WATER && sampleR.surface == Surface.DEEP_WATER) -> CrashReason.DROWNED
            else -> null
        }
    }

    private fun checkTrees() {
        val cy = cos(yaw); val sy = sin(yaw)
        val fxp = x + cy * (spec.cgToFront + spec.frontRadius); val fyp = y + sy * (spec.cgToFront + spec.frontRadius)
        val rxp = x - cy * spec.cgToRear; val ryp = y - sy * spec.cgToRear
        val minX = min(fxp, rxp) - 1; val maxX = max(fxp, rxp) + 1
        val minY = min(fyp, ryp) - 1; val maxY = max(fyp, ryp) + 1
        Obstacles.forEachTree(ground, minX, minY, maxX, maxY, treeTmp) { t ->
            // Distance from trunk to the bike's centre line segment.
            val dx = fxp - rxp; val dy = fyp - ryp
            val l2 = dx * dx + dy * dy
            val tt = (((t.x - rxp) * dx + (t.y - ryp) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(t.x - (rxp + dx * tt), t.y - (ryp + dy * tt))
            if (d < t.trunkR + 0.35) {
                if (speed > 2.5) crash = CrashReason.TREE
                else {
                    // Bumped it at walking pace: just stop.
                    vx = 0.0; vy = 0.0; yawRate = 0.0; omegaF = 0.0; omegaR = 0.0
                    val nx = (rxp + dx * tt) - t.x; val ny = (ryp + dy * tt) - t.y
                    val nl = max(hypot(nx, ny), 1e-6)
                    x += nx / nl * 0.05; y += ny / nl * 0.05
                }
            }
        }
    }

    /** Heading of the velocity vector (for the GPS course-over-ground display). */
    val course get() = atan2(vy, vx)

    companion object {
        const val G = 9.81
        const val STEP = 1.0 / 300.0
        const val RELAX_LENGTH = 0.25
        const val CLUTCH_SLIP = 12.0
        /** 5 mph: below this the bike is held upright and steered directly. */
        const val UPRIGHT_SPEED = 2.235
        /** Walking pace while paddling backwards (m/s). */
        const val REVERSE_SPEED = 1.1
        /** 10 mph: lean has fully faded in by here (it still grows with speed after). */
        const val LEAN_IN_SPEED = 4.47
        const val LOAD_RELAX = 0.35
    }
}
