package com.northwoodsadv.ride.physics

import com.northwoodsadv.ride.world.Surface
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Combined-slip tyre model.
 *
 * Longitudinal slip (kappa) and lateral slip (tan alpha, with camber folded in as an
 * equivalent slip) are each normalised by the surface's peak slip, and the resultant force is
 * a single friction curve applied along the combined slip direction. This gives the friction
 * circle for free: braking hard or spinning the rear leaves little grip for cornering.
 *
 * The curve rises parabolically to the peak (s = 1) and falls towards [Surface.slideRatio]
 * beyond it. Sharp drop-off on pavement, nearly flat on gravel and sand.
 */
object Tire {
    /** Normalised force (0..1) for normalised slip magnitude [s]. */
    fun curve(s: Double, slideRatio: Double): Double {
        if (s <= 1.0) return s * (2 - s)
        val d = s - 1
        return slideRatio + (1 - slideRatio) / (1 + 1.6 * d * d)
    }

    class Result { var fx = 0.0; var fy = 0.0; var utilisation = 0.0; var dFxdKappa = 0.0 }

    /**
     * @param fz normal load (N)
     * @param kappa slip ratio (wheel speed - ground speed) / ground speed
     * @param tanAlpha lateral slip, positive gives positive (left) force
     * @param camber lean angle of the wheel (rad, positive left)
     * @param grip multiplier on surface friction (bodywork dragging, tyre wear...)
     */
    fun forces(fz: Double, kappa: Double, tanAlpha: Double, camber: Double, camberStiffness: Double,
               surface: Surface, grip: Double, nominalLoad: Double, out: Result): Result {
        if (fz <= 0) { out.fx = 0.0; out.fy = 0.0; out.utilisation = 0.0; out.dFxdKappa = 0.0; return out }
        val mu = surface.mu * grip * (1 - 0.08 * (fz / nominalLoad - 1)).coerceIn(0.75, 1.1)
        val tanPeak = tan(Math.toRadians(surface.anglePeakDeg))
        // Camber thrust as equivalent slip. On loose surfaces the carcass can't develop as much.
        val corneringPerRad = 2 * surface.mu / tanPeak
        val camberEq = camberStiffness * (surface.mu / 1.05).coerceAtMost(1.0) * kotlin.math.sin(camber) / corneringPerRad
        val sx = kappa / surface.slipPeak
        val sy = (tanAlpha + camberEq) / tanPeak
        val s = sqrt(sx * sx + sy * sy)
        if (s < 1e-9) { out.fx = 0.0; out.fy = 0.0; out.utilisation = 0.0; out.dFxdKappa = 2 * mu * fz / surface.slipPeak; return out }
        val f = mu * fz * curve(s, surface.slideRatio)
        out.fx = f * sx / s
        out.fy = f * sy / s
        out.utilisation = s
        // Slope of Fx with respect to kappa, used for the implicit wheel-spin update.
        val eps = 1e-3
        val sx2 = (kappa + eps) / surface.slipPeak
        val s2 = sqrt(sx2 * sx2 + sy * sy)
        val fx2 = mu * fz * curve(s2, surface.slideRatio) * sx2 / s2
        out.dFxdKappa = ((fx2 - out.fx) / eps).coerceAtLeast(0.0)
        return out
    }
}
