package app.aaps.plugins.insulin.tsunami

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** The Traffic Jam chain D -(k1)-> X -(plasma)-> A -(effect)-> C -(kc)-> out ([TrafficJamChain]). */
class TrafficJamChainTest {

    private val kPlasma = TrafficJamChain.K_PLASMA
    private val kEffect = TrafficJamChain.K_EFFECT

    @Test
    fun matchesA60DigitMatrixExponentialForAnyRates() {
        // Coinciding rates are the hard cases: the depot rate varies continuously and crosses the plasma, effect and CGM
        // rates, and a CGM lag of 1/K_EFFECT (3.6 min) puts the CGM rate on the effect rate.
        val depotRates = listOf(
            1e-4, 0.02, kPlasma, kPlasma * (1 + 1e-9), kPlasma * (1 + 1e-6), kPlasma * (1 + 0.004), kEffect, kEffect * (1 - 1e-8),
            kEffect * (1 + 3e-3), 0.125, 0.2, 2.99
        )
        val lagRates = listOf(0.0, 1.0 / 8.0, kEffect, kEffect * (1 + 1e-7), kEffect * (1 + 0.03), 1.0 / 30.0, 1.0)
        val out = DoubleArray(4)
        for (k1 in depotRates) for (kc in lagRates) for (dt in listOf(1e-3, 0.37, 1.0, 7.5, 60.0, 600.0)) {
            val rates = if (kc > 0.0) doubleArrayOf(k1, kPlasma, kEffect, kc) else doubleArrayOf(k1, kPlasma, kEffect)
            val expected = matrixExponential(rates, dt)
            for (from in rates.indices) {
                val unit = DoubleArray(4).also { it[from] = 1.0 }
                TrafficJamChain.propagate(unit[0], unit[1], unit[2], unit[3], k1, kc, dt, out)
                for (to in rates.indices) {
                    val e = expected[to][from]
                    assertThat(abs(out[to] - e)).isAtMost(1e-10 * abs(e) + 1e-300)
                }
            }
        }
    }

    @Test
    fun depotAndPlasmaInClosedForm() {
        val out = DoubleArray(4)
        val k1 = 0.05
        val t = 7.0
        TrafficJamChain.propagate(1.3, 0.7, 0.4, 0.2, k1, 1.0 / 8.0, t, out)
        assertThat(out[0]).isWithin(1e-15).of(1.3 * exp(-k1 * t))
        assertThat(out[1]).isWithin(1e-15).of(0.7 * exp(-kPlasma * t) + 1.3 * k1 * (exp(-k1 * t) - exp(-kPlasma * t)) / (kPlasma - k1))
    }

    @Test
    fun stepsComposeExactly() {
        // Rates held, two steps of t1 and t2 are one step of t1 + t2.
        val once = DoubleArray(4)
        val twice = DoubleArray(4)
        for (kc in listOf(0.0, 1.0 / 8.0)) for (k1 in listOf(0.03, kEffect, 0.4)) {
            TrafficJamChain.propagate(2.0, 0.3, 0.1, 0.05, k1, kc, 37.3, once)
            TrafficJamChain.propagate(2.0, 0.3, 0.1, 0.05, k1, kc, 0.37, twice)
            TrafficJamChain.propagate(twice[0], twice[1], twice[2], twice[3], k1, kc, 36.93, twice)
            for (i in 0..3) assertThat(twice[i]).isWithin(1e-13).of(once[i])
        }
    }

    @Test
    fun noTimeNoChange() {
        val out = DoubleArray(4)
        TrafficJamChain.propagate(1.0, 0.5, 0.2, 0.3, 0.02, 0.125, 0.0, out)
        assertThat(out.toList()).containsExactly(1.0, 0.5, 0.2, 0.3).inOrder()
    }

    @Test
    fun withoutACgmLagCIsLeftAsItIs() {
        val out = DoubleArray(4)
        TrafficJamChain.propagate(1.0, 0.5, 0.2, 0.0, 0.02, 0.0, 30.0, out)
        assertThat(out[3]).isEqualTo(0.0)
    }

    @Test
    fun aDoseActsWhollyAndTheLagDelaysIt() {
        // One dose at a fixed depot rate: the activity integrates to the dose, and with a lag it peaks later.
        fun curve(kc: Double): Pair<Int, Double> {
            val out = DoubleArray(4)
            var peakAt = 0
            var peak = 0.0
            var total = 0.0
            for (m in 1..4000) {
                TrafficJamChain.propagate(1.0, 0.0, 0.0, 0.0, 0.05, kc, m.toDouble(), out)
                val activity = if (kc > 0.0) kc * out[3] else kEffect * out[2]
                if (activity > peak) { peak = activity; peakAt = m }
                total += activity
            }
            return Pair(peakAt, total)
        }
        val (plainPeak, plainTotal) = curve(0.0)
        val (laggedPeak, laggedTotal) = curve(1.0 / 8.0)
        assertThat(plainTotal).isWithin(1e-3).of(1.0)
        assertThat(laggedTotal).isWithin(1e-3).of(1.0)
        assertThat(laggedPeak).isGreaterThan(plainPeak)
    }

    @Test
    fun theDepotRateFollowsTheFittedLaw() {
        assertThat(TrafficJamChain.rate(1.0, 1.0)).isWithin(1e-15).of(TrafficJamChain.K1_B0)
        assertThat(TrafficJamChain.rate(0.47, 10.0)).isWithin(1e-15).of(0.47 * TrafficJamChain.K1_B0 * 10.0.pow(TrafficJamChain.K1_B1))
        // An empty depot is floored, not infinitely fast.
        assertThat(TrafficJamChain.rate(1.0, 0.0)).isWithin(1e-12).of(TrafficJamChain.K1_B0 * 1e-6.pow(TrafficJamChain.K1_B1))
    }

    @Test
    fun aNewSiteReachesFullSpeedAtTheEndOfItsRamp() {
        val hour = 3_600_000L
        val ramp = TrafficJamChain.siteRampMs(TrafficJamChain.SITE_RAMP_HOURS)
        assertThat(ramp).isEqualTo(48 * hour)
        assertThat(TrafficJamChain.siteFactor(0.47, ramp, 0L)).isEqualTo(0.47)
        assertThat(TrafficJamChain.siteFactor(0.47, ramp, 24 * hour)).isWithin(1e-15).of(0.735)
        assertThat(TrafficJamChain.siteFactor(0.47, ramp, 48 * hour)).isEqualTo(1.0)
        assertThat(TrafficJamChain.siteFactor(0.47, ramp, 100 * hour)).isEqualTo(1.0)
        assertThat(TrafficJamChain.siteFactor(0.47, ramp, -hour)).isEqualTo(0.47)
        assertThat(TrafficJamChain.siteFactor(1.0, ramp, 5 * hour)).isEqualTo(1.0)
        // A day's ramp: halfway at 12 h, full speed at 24 h.
        val day = TrafficJamChain.siteRampMs(24.0)
        assertThat(TrafficJamChain.siteFactor(0.47, day, 12 * hour)).isWithin(1e-15).of(0.735)
        assertThat(TrafficJamChain.siteFactor(0.47, day, 24 * hour)).isEqualTo(1.0)
        // The setting is kept within an hour and a week.
        assertThat(TrafficJamChain.siteRampMs(0.0)).isEqualTo(hour)
        assertThat(TrafficJamChain.siteRampMs(1000.0)).isEqualTo(TrafficJamChain.MAX_SITE_RAMP_MS)
    }

    /** exp(M dt) for the chain's rates, M lower bidiagonal (-rate on the diagonal, rate below it), to 60 digits. */
    private fun matrixExponential(rates: DoubleArray, dt: Double): Array<DoubleArray> {
        val mc = MathContext(60)
        val n = rates.size
        var squarings = 0
        var norm = 2.0 * rates.max() * dt
        while (norm > 0.25) {
            norm /= 2.0
            squarings++
        }
        val scale = BigDecimal(2).pow(squarings)
        val m = Array(n) { Array(n) { BigDecimal.ZERO } }
        for (i in 0 until n) {
            val r = BigDecimal(rates[i]).multiply(BigDecimal(dt), mc).divide(scale, mc)
            m[i][i] = r.negate()
            if (i + 1 < n) m[i + 1][i] = r
        }
        fun times(a: Array<Array<BigDecimal>>, b: Array<Array<BigDecimal>>) = Array(n) { i ->
            Array(n) { j -> (0 until n).fold(BigDecimal.ZERO) { s, k -> s.add(a[i][k].multiply(b[k][j], mc), mc) } }
        }
        var e = Array(n) { i -> Array(n) { j -> if (i == j) BigDecimal.ONE else BigDecimal.ZERO } }
        var term = e
        for (k in 1..60) {
            term = times(term, m).map { row -> row.map { it.divide(BigDecimal(k), mc) }.toTypedArray() }.toTypedArray()
            e = Array(n) { i -> Array(n) { j -> e[i][j].add(term[i][j], mc) } }
        }
        repeat(squarings) { e = times(e, e) }
        return Array(n) { i -> DoubleArray(n) { j -> e[i][j].toDouble() } }
    }
}
