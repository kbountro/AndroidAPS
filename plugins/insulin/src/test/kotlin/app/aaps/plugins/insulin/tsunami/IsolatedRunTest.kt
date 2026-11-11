package app.aaps.plugins.insulin.tsunami

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * TsunamiIobEngineImpl sums the individual branches (dose interaction below 1) as runs of equal doses a minute
 * apart ([TsunamiIobEngineImpl.PdModel.runState]); that has to equal summing every dose's isolated state.
 */
class IsolatedRunTest {

    private val model = TsunamiIobEngineImpl.PdModel
    private val minute = 60_000L

    @Test
    fun runSumsEqualDoseByDoseSums() {
        // Ages of the run's oldest dose: whole minutes, a fraction past them, runs reaching past the table's end
        // (600 min, where the rate is held), and runs lying wholly beyond it.
        val oldestAges = listOf(37 * minute, 37 * minute + 23_456, 600 * minute, 605 * minute + 18_000, 1200 * minute, 1500 * minute + 41_234)
        val out = DoubleArray(4)
        val dose = DoubleArray(4)
        for (speed in listOf(0.5, 1.0, 2.0)) for (kc in listOf(0.0, 1.0 / 8.0)) for (amount in listOf(0.8 / 60.0, 0.35, 2.0)) {
            val curve = model.isolatedCurve(amount, speed, kc)
            for (ageMs in oldestAges) for (count in listOf(2, 7, 30)) for (shift in listOf(false, true)) {
                if (ageMs - (count - 1) * minute < 0) continue
                model.runState(curve, ageMs, count, shift, out)
                val expected = DoubleArray(4)
                for (j in 0 until count) {
                    model.isolatedStateOnGrid(curve.grid, (ageMs - j * minute) / 60_000.0, speed, kc, dose)
                    for (s in 0..3) expected[s] += dose[s]
                }
                for (s in 0..3) assertThat(abs(out[s] - expected[s])).isAtMost(1e-12 * max(1.0, abs(expected[s])))
            }
        }
    }

    @Test
    fun aSingleDoseIsItsIsolatedState() {
        val curve = model.isolatedCurve(0.35, 1.0, 0.0)
        val out = DoubleArray(4)
        val expected = DoubleArray(4)
        for (ageMs in listOf(1L, 12 * minute + 34_567, 700 * minute + 1_000)) {
            model.runState(curve, ageMs, 1, true, out)
            model.isolatedStateOnGrid(curve.grid, ageMs / 60_000.0, 1.0, 0.0, expected)
            assertThat(out.toList()).isEqualTo(expected.toList())
        }
    }

    @Test
    fun aDoseAtItsOwnTimeIsAllInTheDepot() {
        val out = DoubleArray(4)
        model.runState(model.isolatedCurve(0.35, 1.0, 1.0 / 8.0), 0L, 1, false, out)
        assertThat(out.toList()).containsExactly(0.35, 0.0, 0.0, 0.0).inOrder()
    }
}
