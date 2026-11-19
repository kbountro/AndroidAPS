package app.aaps.implementation.aps

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class ActivityMultiplierHistoryImplTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    private var stored = ""
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    @BeforeEach
    fun setup() {
        stored = ""
        whenever(preferences.get(StringNonKey.ActivityMultiplierHistory)).thenAnswer { stored }
        doAnswer { stored = it.getArgument(1); null }.whenever(preferences).put(eq(StringNonKey.ActivityMultiplierHistory), any<String>())
    }

    private fun history() = ActivityMultiplierHistoryImpl(preferences)

    @Test
    fun noMultiplierWithoutRecords() {
        assertThat(history().multiplierAt(t0)).isEqualTo(1.0)
    }

    @Test
    fun multiplierHoldsFromItsFirstRunUntilARunWithoutIt() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 + 5 * min, 0.7)
        h.record(t0 + 10 * min, 0.7)
        h.record(t0 + 15 * min, 1.0)
        assertThat(h.multiplierAt(t0 - 1)).isEqualTo(1.0)
        assertThat(h.multiplierAt(t0)).isEqualTo(0.7)
        assertThat(h.multiplierAt(t0 + 15 * min - 1)).isEqualTo(0.7)
        assertThat(h.multiplierAt(t0 + 15 * min)).isEqualTo(1.0)
    }

    @Test
    fun inactivityMultipliersAreKeptToo() {
        val h = history()
        h.record(t0, 1.2)
        h.record(t0 + 5 * min, 1.1)
        h.record(t0 + 10 * min, 0.85)
        h.record(t0 + 15 * min, 1.0)
        assertThat(h.multiplierAt(t0 + 2 * min)).isEqualTo(1.2)
        assertThat(h.multiplierAt(t0 + 7 * min)).isEqualTo(1.1)
        assertThat(h.multiplierAt(t0 + 12 * min)).isEqualTo(0.85)
        assertThat(h.multiplierAt(t0 + 15 * min)).isEqualTo(1.0)
    }

    @Test
    fun multiplierLapsesThirtyMinutesAfterTheLastRunWhenTheLoopStops() {
        val h = history()
        h.record(t0, 0.7)
        assertThat(h.multiplierAt(t0 + 29 * min)).isEqualTo(0.7)
        assertThat(h.multiplierAt(t0 + 30 * min)).isEqualTo(1.0)
        // a run long after with the same multiplier starts a new stretch instead of covering the gap
        h.record(t0 + 90 * min, 0.7)
        assertThat(h.multiplierAt(t0 + 60 * min)).isEqualTo(1.0)
        assertThat(h.multiplierAt(t0 + 90 * min)).isEqualTo(0.7)
    }

    @Test
    fun survivesARestart() {
        history().apply {
            record(t0, 0.7)
            record(t0 + 5 * min, 0.7)
            record(t0 + 10 * min, 1.0)
            record(t0 + 20 * min, 1.2)
        }
        val restarted = history()
        assertThat(restarted.multiplierAt(t0 + 7 * min)).isEqualTo(0.7)
        assertThat(restarted.multiplierAt(t0 + 12 * min)).isEqualTo(1.0)
        assertThat(restarted.multiplierAt(t0 + 25 * min)).isEqualTo(1.2)
        assertThat(restarted.multiplierAt(t0 + 50 * min)).isEqualTo(1.0)
        // and carries on where it was
        restarted.record(t0 + 25 * min, 1.2)
        assertThat(restarted.multiplierAt(t0 + 54 * min)).isEqualTo(1.2)
    }

    @Test
    fun ignoresOlderRunsAndTreatsBadValuesSafely() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 - 5 * min, 0.5)
        assertThat(h.multiplierAt(t0 - 5 * min)).isEqualTo(1.0)
        h.record(t0 + 5 * min, Double.NaN) // no multiplier: ends the stretch
        assertThat(h.multiplierAt(t0 + 5 * min)).isEqualTo(1.0)
        h.record(t0 + 10 * min, -0.2) // below 0: a multiplier of 0
        assertThat(h.multiplierAt(t0 + 10 * min)).isEqualTo(0.0)
    }

    @Test
    fun ignoresAMalformedStoredHistory() {
        stored = "garbage;1,2;${t0},${t0 + 5 * min},-1,0.7"
        val h = history()
        assertThat(h.multiplierAt(t0 + 6 * min)).isEqualTo(0.7)
        assertThat(h.multiplierAt(t0 + 35 * min)).isEqualTo(1.0)
    }

    @Test
    fun dropsStretchesOlderThanTwoDays() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 + 5 * min, 1.0)
        h.record(t0 + 49 * 60 * min, 0.7)
        assertThat(stored.split(';')).hasSize(1)
        assertThat(h.multiplierAt(t0 + min)).isEqualTo(1.0)
        assertThat(h.multiplierAt(t0 + 49 * 60 * min)).isEqualTo(0.7)
    }
}
