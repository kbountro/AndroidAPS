package app.aaps.plugins.insulin.tsunami

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

class CgmLagStageTest {

    private val ke = 0.1297809

    @Test
    fun matchesNumericalIntegrationForAnyRates() {
        // Coinciding rates are the hard cases: the crowded rate k crosses ke and kc as a depot empties,
        // and a lag of 1/ke (7.7 min at speed 1) makes kc and ke coincide.
        val rateTriples = listOf(
            Triple(0.02, ke, 1.0 / 8.0),          // ordinary
            Triple(0.02, ke, ke),                 // kc = ke
            Triple(0.02, ke, ke * (1 + 2e-9)),    // kc within rounding of ke
            Triple(0.02, ke, ke * (1 + 2e-7)),    // kc just past the near-equal switch
            Triple(ke, ke, 1.0 / 5.0),            // k = ke
            Triple(0.2, ke, 0.2),                 // k = kc
            Triple(ke, ke, ke),                   // all equal
            Triple(ke * (1 + 2e-6), ke, ke * (1 + 3e-6)), // all within a few 1e-7
            Triple(ke - 0.04 * (1 - 1e-9), ke, 1.0 / 8.0), // (ke - k) * 5 min just below the series/closed-form switch
            Triple(ke - 0.04 * (1 + 1e-9), ke, 1.0 / 8.0), // and just above it
            Triple(2.0, 0.026, 1.0)               // far apart (speed 5 depot, speed 0.2 active stage)
        )
        val state = doubleArrayOf(1.3, 0.7, 0.4, 0.2) // D, X, A, C
        val out = DoubleArray(4)
        for ((k, kActive, kc) in rateTriples) for (dt in listOf(0.3, 1.0, 5.0, 60.0)) {
            CgmLagStage.propagate(state[0], state[1], state[2], state[3], k, kActive, kc, dt, out)
            val numerical = rk4(state, k, kActive, kc, dt)
            for (i in 0..3) assertThat(abs(out[i] - numerical[i])).isLessThan(1e-9 * max(1.0, abs(numerical[i])))
        }
    }

    @Test
    fun depotAndTransitAreAsWithoutTheLagStage() {
        val out = DoubleArray(4)
        val k = 0.02
        val t = 7.0
        CgmLagStage.propagate(1.3, 0.7, 0.4, 0.2, k, ke, 1.0 / 8.0, t, out)
        assertThat(out[0]).isWithin(1e-15).of(1.3 * exp(-k * t))
        assertThat(out[1]).isWithin(1e-15).of((0.7 + 1.3 * k * t) * exp(-k * t))
    }

    @Test
    fun allRatesEqualGiveTheErlangCurve() {
        // A depot impulse through four equal stages: d0 (k t)^n / n! exp(-k t) in the n-th stage after the depot.
        val t = 7.0
        val out = DoubleArray(4)
        CgmLagStage.propagate(1.0, 0.0, 0.0, 0.0, ke, ke, ke, t, out)
        val kt = ke * t
        assertThat(out[1]).isWithin(1e-14).of(kt * exp(-kt))
        assertThat(out[2]).isWithin(1e-14).of(kt * kt / 2.0 * exp(-kt))
        assertThat(out[3]).isWithin(1e-14).of(kt * kt * kt / 6.0 * exp(-kt))
    }

    @Test
    fun noTimeNoChange() {
        val out = DoubleArray(4)
        CgmLagStage.propagate(1.0, 0.5, 0.2, 0.3, 0.02, ke, 0.125, 0.0, out)
        assertThat(out.toList()).containsExactly(1.0, 0.5, 0.2, 0.3).inOrder()
    }

    @Test
    fun lagDelaysTheEffectButKeepsItsTotal() {
        // One dose through a chain with a fixed depot rate: the lagged activity kc*C peaks later than ke*A,
        // and integrates to the same dose.
        val k = 0.02
        val kc = 1.0 / 8.0
        val out = DoubleArray(4)
        var lagPeak = 0.0
        var lagPeakAt = 0
        var activePeak = 0.0
        var activePeakAt = 0
        var total = 0.0
        for (m in 1..3000) {
            CgmLagStage.propagate(1.0, 0.0, 0.0, 0.0, k, ke, kc, m.toDouble(), out)
            val active = ke * out[2]
            val lagged = kc * out[3]
            if (active > activePeak) { activePeak = active; activePeakAt = m }
            if (lagged > lagPeak) { lagPeak = lagged; lagPeakAt = m }
            total += lagged
        }
        assertThat(lagPeakAt).isGreaterThan(activePeakAt)
        assertThat(total).isWithin(1e-3).of(1.0)
    }

    /** D -> X -> A -> C with depot and transit at [k], integrated numerically. */
    private fun rk4(s0: DoubleArray, k: Double, kActive: Double, kc: Double, t: Double, steps: Int = 20000): DoubleArray {
        val h = t / steps
        var s = s0.copyOf()
        fun f(y: DoubleArray) = doubleArrayOf(-k * y[0], k * y[0] - k * y[1], k * y[1] - kActive * y[2], kActive * y[2] - kc * y[3])
        repeat(steps) {
            val a = f(s)
            val b = f(DoubleArray(4) { s[it] + h / 2 * a[it] })
            val c = f(DoubleArray(4) { s[it] + h / 2 * b[it] })
            val d = f(DoubleArray(4) { s[it] + h * c[it] })
            s = DoubleArray(4) { s[it] + h / 6 * (a[it] + 2 * b[it] + 2 * c[it] + d[it]) }
        }
        return s
    }
}
