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
 * [propagate] advances the whole chain, exactly for any rates, coinciding ones included - the crowded
 * depot/transit rate k varies continuously and crosses both ke and kc, and a lag near 1/ke (7.7 min at
 * speed 1) makes kc and ke coincide. Every term is a divided difference of exp(-x) at the scaled rate
 * differences, evaluated by its series where the closed form would cancel, and by its derivative where two
 * rates (nearly) coincide.
 *
 * It runs for every pool at every simulated minute, so it is kept cheap: the four stages share exp(-k dt) and
 * the divided differences at (ke - k) dt, and the series are evaluated by Horner's rule on precomputed
 * coefficients, with no division. A step with the lag stage then costs about as much as one of the three-stage
 * chain alone; summing the series term by term, with a division at every term, made it cost more than twice as much.
 */
internal object CgmLagStage {

    /** Below this |x| the closed forms of [p1], [p1d], [q2] and [q2d] are replaced by their series. */
    private const val SERIES_BELOW = 0.2

    /** Terms kept of each series: below [SERIES_BELOW], the first one left out is under 1e-17 of the sum. */
    private const val SERIES_TERMS = 12

    /** Below this |v - u| a divided difference over (u, v) becomes the derivative at their midpoint. */
    private const val CLOSE_NODES = 1e-5

    // Taylor coefficients at 0 of the functions below, lowest power first.
    private val P1 = coefficients { j -> sign(j + 1) / factorial(j + 1) }
    private val P1D = coefficients { j -> (j + 1) * sign(j) / factorial(j + 2) }
    private val Q2 = coefficients { j -> sign(j) / factorial(j + 2) }
    private val Q2D = coefficients { j -> (j + 1) * sign(j + 1) / factorial(j + 3) }

    /**
     * The chain after [dt] minutes from ([d0], [x0], [a0], [c0]), with depot and transit both at [k], the active
     * stage at [ke] and C at [kc], written into [out] as (D, X, A, C). D, X and A evolve as without the lag stage.
     * exp() of the scaled rate differences has to stay finite, so callers split long intervals
     * (TsunamiIobEngineImpl's PdModel.propagateWithLag steps by an hour at most).
     */
    fun propagate(d0: Double, x0: Double, a0: Double, c0: Double, k: Double, ke: Double, kc: Double, dt: Double, out: DoubleArray) {
        if (dt <= 0.0) {
            out[0] = d0; out[1] = x0; out[2] = a0; out[3] = c0
            return
        }
        val ek = exp(-k * dt)
        val ee = exp(-ke * dt)
        val kt = k * dt
        val ket = ke * dt
        val u = (ke - k) * dt
        val v = (kc - k) * dt
        val p1u = p1(u)   // E[0, u]
        val q2u = q2(u)   // E[0, 0, u]
        val close = abs(v - u) < CLOSE_NODES
        val dd0 = if (close) p1d(0.5 * (u + v)) else (p1(v) - p1u) / (v - u)   // E[0, u, v]
        val dd00 = if (close) q2d(0.5 * (u + v)) else (q2(v) - q2u) / (v - u)  // E[0, 0, u, v]
        out[0] = d0 * ek
        out[1] = x0 * ek + d0 * k * dt * ek                                    // X -> X, D -> X
        out[2] = a0 * ee - x0 * kt * ek * p1u + d0 * kt * kt * ek * q2u        // A -> A, X -> A, D -> X -> A
        out[3] = c0 * exp(-kc * dt) - a0 * ket * ee * p1((kc - ke) * dt) +     // C -> C, A -> C
            x0 * kt * ket * ek * dd0 - d0 * kt * kt * ket * ek * dd00          // X -> A -> C, D -> X -> A -> C
    }

    /** E[0, x] = (exp(-x) - 1) / x, the first divided difference of exp(-x). */
    private fun p1(x: Double): Double = if (abs(x) >= SERIES_BELOW) (exp(-x) - 1.0) / x else series(P1, x)

    /** d/dx E[0, x] = (1 - (1 + x) exp(-x)) / x^2. */
    private fun p1d(x: Double): Double = if (abs(x) >= SERIES_BELOW) (1.0 - (1.0 + x) * exp(-x)) / (x * x) else series(P1D, x)

    /** E[0, 0, x] = (exp(-x) - 1 + x) / x^2. */
    private fun q2(x: Double): Double = if (abs(x) >= SERIES_BELOW) (exp(-x) - 1.0 + x) / (x * x) else series(Q2, x)

    /** d/dx E[0, 0, x] = (2 - x - (x + 2) exp(-x)) / x^3. */
    private fun q2d(x: Double): Double = if (abs(x) >= SERIES_BELOW) (2.0 - x - (x + 2.0) * exp(-x)) / (x * x * x) else series(Q2D, x)

    /** The polynomial with coefficients [c] (lowest power first) at [x], by Horner's rule. */
    private fun series(c: DoubleArray, x: Double): Double {
        var sum = c[c.size - 1]
        for (j in c.size - 2 downTo 0) sum = sum * x + c[j]
        return sum
    }

    private fun coefficients(term: (Int) -> Double) = DoubleArray(SERIES_TERMS, term)
    private fun sign(n: Int): Double = if (n % 2 == 0) 1.0 else -1.0
    private fun factorial(n: Int): Double = (2..n).fold(1.0) { f, i -> f * i }
}
