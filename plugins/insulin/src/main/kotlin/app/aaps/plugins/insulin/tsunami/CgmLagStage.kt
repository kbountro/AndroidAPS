package app.aaps.plugins.insulin.tsunami

import kotlin.math.abs
import kotlin.math.exp

/**
 * The CGM lag stage of the Traffic Jam chain (DoubleKey.InsulinTrafficJamCgmLag): one more first-order
 * compartment C after the active stage, D -(k)-> X -(k)-> A -(ke)-> C -(kc)-> out, so that
 *   dC/dt = ke*A - kc*C,   kc = 1 / lag.
 * The insulin's effect as the CGM sees it is kc*C (the active stage's ke*A, low-passed by the delay of
 * glucose reaching the interstitial fluid and of the sensor), and C counts as insulin on board: its effect
 * has happened in the blood but not yet on the CGM. Shared by TsunamiIobEngineImpl and the single-dose
 * preview in InsulinOrefBasePlugin.
 *
 * [propagate] is exact for any rates, coinciding ones included - the crowded depot/transit rate k varies
 * continuously and crosses both ke and kc, and a lag near 1/ke (7.7 min at speed 1) makes kc and ke
 * coincide. Every term is a divided difference of exp(-x) at the scaled rate differences, evaluated by its
 * series where the closed form would cancel, and by its derivative where two rates (nearly) coincide.
 */
internal object CgmLagStage {

    /** Below this |x| the closed forms of [p1], [p1d], [q2] and [q2d] are replaced by their series. */
    private const val SERIES_BELOW = 0.2
    private const val SERIES_TERMS = 24

    /** Below this |v - u| a divided difference over (u, v) becomes the derivative at their midpoint. */
    private const val CLOSE_NODES = 1e-5

    /**
     * C after [dt] minutes from the chain's state ([c0], [d0], [x0], [a0]), with depot and transit both at
     * [k], the active stage at [ke] and C at [kc]. D, X and A themselves evolve as without the lag stage.
     */
    fun propagate(c0: Double, d0: Double, x0: Double, a0: Double, k: Double, ke: Double, kc: Double, dt: Double): Double {
        if (dt <= 0.0) return c0
        val u = (ke - k) * dt
        val v = (kc - k) * dt
        var c = c0 * exp(-kc * dt)
        if (a0 != 0.0) c -= a0 * ke * dt * exp(-ke * dt) * p1((kc - ke) * dt)       // A -> C
        if (x0 != 0.0) c += x0 * k * ke * dt * dt * exp(-k * dt) * dd0(u, v)        // X -> A -> C
        if (d0 != 0.0) c -= d0 * k * k * ke * dt * dt * dt * exp(-k * dt) * dd00(u, v) // D -> X -> A -> C
        return c
    }

    /** E[0, u, v], the second divided difference of exp(-x). */
    private fun dd0(u: Double, v: Double): Double =
        if (abs(v - u) >= CLOSE_NODES) (p1(v) - p1(u)) / (v - u) else p1d(0.5 * (u + v))

    /** E[0, 0, u, v], the third divided difference of exp(-x). */
    private fun dd00(u: Double, v: Double): Double =
        if (abs(v - u) >= CLOSE_NODES) (q2(v) - q2(u)) / (v - u) else q2d(0.5 * (u + v))

    /** E[0, x] = (exp(-x) - 1) / x. */
    private fun p1(x: Double): Double {
        if (abs(x) >= SERIES_BELOW) return (exp(-x) - 1.0) / x
        var term = -1.0 // (-1)^n x^(n-1) / n!, n = 1
        var sum = 0.0
        for (n in 1..SERIES_TERMS) {
            sum += term
            term *= -x / (n + 1)
        }
        return sum
    }

    /** d/dx E[0, x] = (1 - (1 + x) exp(-x)) / x^2. */
    private fun p1d(x: Double): Double {
        if (abs(x) >= SERIES_BELOW) return (1.0 - (1.0 + x) * exp(-x)) / (x * x)
        var term = 0.5 // (-1)^n x^(n-2) / n!, n = 2
        var sum = 0.0
        for (n in 2..SERIES_TERMS + 1) {
            sum += (n - 1) * term
            term *= -x / (n + 1)
        }
        return sum
    }

    /** E[0, 0, x] = (exp(-x) - 1 + x) / x^2. */
    private fun q2(x: Double): Double {
        if (abs(x) >= SERIES_BELOW) return (exp(-x) - 1.0 + x) / (x * x)
        var term = 0.5 // (-1)^n x^(n-2) / n!, n = 2
        var sum = 0.0
        for (n in 2..SERIES_TERMS + 1) {
            sum += term
            term *= -x / (n + 1)
        }
        return sum
    }

    /** d/dx E[0, 0, x] = (2 - x - (x + 2) exp(-x)) / x^3. */
    private fun q2d(x: Double): Double {
        if (abs(x) >= SERIES_BELOW) return (2.0 - x - (x + 2.0) * exp(-x)) / (x * x * x)
        var term = -1.0 / 6.0 // (-1)^n x^(n-3) / n!, n = 3
        var sum = 0.0
        for (n in 3..SERIES_TERMS + 2) {
            sum += (n - 2) * term
            term *= -x / (n + 1)
        }
        return sum
    }
}
