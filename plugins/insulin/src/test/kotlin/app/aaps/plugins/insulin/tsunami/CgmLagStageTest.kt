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
            Triple(2.0, 0.026, 1.0)               // far apart (speed 5 depot, speed 0.2 active stage)
        )
        val state = doubleArrayOf(1.3, 0.7, 0.4, 0.2) // D, X, A, C
        for ((k, kActive, kc) in rateTriples) for (dt in listOf(0.3, 1.0, 5.0, 60.0)) {
            val exact = CgmLagStage.propagate(state[3], state[0], state[1], state[2], k, kActive, kc, dt)
            val numerical = rk4(state, k, kActive, kc, dt)[3]
            assertThat(abs(exact - numerical)).isLessThan(1e-9 * max(1.0, abs(numerical)))
        }
    }

    @Test
    fun allRatesEqualGiveTheErlangCurve() {
        // A depot impulse through four equal stages: d0 k^3 t^3 / 6 exp(-k t).
        val t = 7.0
        assertThat(CgmLagStage.propagate(0.0, 1.0, 0.0, 0.0, ke, ke, ke, t))
            .isWithin(1e-14).of(ke * ke * ke * t * t * t / 6.0 * exp(-ke * t))
    }

    @Test
    fun noTimeNoChange() {
        assertThat(CgmLagStage.propagate(0.3, 1.0, 0.5, 0.2, 0.02, ke, 0.125, 0.0)).isEqualTo(0.3)
    }

    @Test
    fun lagDelaysTheEffectButKeepsItsTotal() {
        // One dose through a chain with a fixed depot rate: the lagged activity kc*C peaks later than ke*A,
        // and integrates to the same dose.
        val k = 0.02
        val kc = 1.0 / 8.0
        var lagPeak = 0.0
        var lagPeakAt = 0
        var activePeakAt = 0
        var activePeak = 0.0
        var total = 0.0
        var s = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        for (m in 1..3000) {
            val lagged = kc * CgmLagStage.propagate(0.0, 1.0, 0.0, 0.0, k, ke, kc, m.toDouble())
            if (lagged > lagPeak) { lagPeak = lagged; lagPeakAt = m }
            total += lagged
            if (m <= 600) {
                s = rk4(s, k, ke, kc, 1.0, steps = 50)
                val active = ke * s[2]
                if (active > activePeak) { activePeak = active; activePeakAt = m }
            }
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
