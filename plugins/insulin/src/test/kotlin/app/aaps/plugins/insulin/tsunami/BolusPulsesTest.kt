package app.aaps.plugins.insulin.tsunami

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The micro-dose schedule: a bolus as the pump's pulses, a minute of them at a time ([BolusPulses]). */
class BolusPulsesTest {

    private val pump = BolusPulses(0.05, 1.5) // a pulse every 2 s

    @Test
    fun pulsesComeEveryPulseSizeOverSpeed() {
        assertThat(pump.intervalMs).isEqualTo(2000L)
        assertThat(BolusPulses(0.05, 1.0).intervalMs).isEqualTo(3000L)
        assertThat(BolusPulses(0.1, 6.0).intervalMs).isEqualTo(1000L)
    }

    @Test
    fun anSmbIsOneChunkAtItsPulsesAverageTime() {
        val chunks = pump.chunks(0.5) // 10 pulses, at 0, 2, ... 18 s
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].offsetMs).isEqualTo(9000L)
        assertThat(chunks[0].pulses).isEqualTo(10)
        assertThat(chunks[0].extra).isEqualTo(0.0)
        assertThat(chunks[0].amount).isWithin(1e-12).of(0.5)
        // One pulse comes at the timestamp itself.
        assertThat(pump.chunks(0.05)[0].offsetMs).isEqualTo(0L)
        assertThat(pump.lastChunkOffsetMs(0.5)).isEqualTo(9000L)
    }

    @Test
    fun aLongBolusIsAChunkForEveryMinuteOfPulses() {
        val chunks = pump.chunks(6.0) // 120 pulses over 238 s
        assertThat(chunks.map { it.offsetMs }).isEqualTo(listOf(29_000L, 89_000L, 149_000L, 209_000L))
        assertThat(chunks.map { it.pulses }).isEqualTo(listOf(30, 30, 30, 30))
        assertThat(chunks.sumOf { it.amount }).isWithin(1e-12).of(6.0)
        assertThat(pump.lastChunkOffsetMs(6.0)).isEqualTo(209_000L)
        // 3.5 U: two full minutes, then the last 10 pulses, at 120 to 138 s.
        val partial = pump.chunks(3.5)
        assertThat(partial.map { it.offsetMs }).isEqualTo(listOf(29_000L, 89_000L, 129_000L))
        assertThat(partial.map { it.pulses }).isEqualTo(listOf(30, 30, 10))
        assertThat(pump.lastChunkOffsetMs(3.5)).isEqualTo(129_000L)
    }

    @Test
    fun theBolusSpeedSetsHowManyPulsesAMinuteHolds() {
        val chunks = BolusPulses(0.05, 1.0).chunks(1.5) // 30 pulses 3 s apart: 20 in the first minute, then 10
        assertThat(chunks.map { it.pulses }).isEqualTo(listOf(20, 10))
        assertThat(chunks.map { it.offsetMs }).isEqualTo(listOf(28_500L, 73_500L))
    }

    @Test
    fun anAmountNotAWholeNumberOfPulsesEndsWithASmallerOne() {
        val chunks = pump.chunks(0.12) // 0.05, 0.05 and 0.02, at 0, 2 and 4 s
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].pulses).isEqualTo(2)
        assertThat(chunks[0].extra).isWithin(1e-12).of(0.02)
        assertThat(chunks[0].offsetMs).isEqualTo(2000L)
        assertThat(chunks[0].amount).isWithin(1e-12).of(0.12)
        // Less than a pulse is one smaller pulse.
        val small = pump.chunks(0.02)
        assertThat(small[0].pulses).isEqualTo(0)
        assertThat(small[0].extra).isWithin(1e-12).of(0.02)
        assertThat(small[0].offsetMs).isEqualTo(0L)
    }

    @Test
    fun roundingDoesNotAddAPulse() {
        // 0.15 / 0.05 is 2.9999999999999996 in floating point: still 3 pulses, nothing left over.
        val chunks = pump.chunks(0.15)
        assertThat(chunks[0].pulses).isEqualTo(3)
        assertThat(chunks[0].extra).isEqualTo(0.0)
    }

    @Test
    fun theChunksStillToComeAreNotGivenYet() {
        assertThat(pump.notGivenYet(6.0, 0L)).isWithin(1e-12).of(6.0)
        assertThat(pump.notGivenYet(6.0, 30_000L)).isWithin(1e-12).of(4.5)
        assertThat(pump.notGivenYet(6.0, 208_999L)).isWithin(1e-12).of(1.5)
        assertThat(pump.notGivenYet(6.0, 209_000L)).isEqualTo(0.0)
        assertThat(pump.notGivenYet(0.5, 8_999L)).isWithin(1e-12).of(0.5)
        assertThat(pump.notGivenYet(0.5, 9_000L)).isEqualTo(0.0)
    }

    @Test
    fun noInsulinHasNoPulses() {
        assertThat(pump.chunks(0.0)).isEmpty()
        assertThat(pump.chunks(-1.0)).isEmpty()
        assertThat(pump.lastChunkOffsetMs(0.0)).isEqualTo(0L)
        assertThat(pump.notGivenYet(0.0, 0L)).isEqualTo(0.0)
    }
}
