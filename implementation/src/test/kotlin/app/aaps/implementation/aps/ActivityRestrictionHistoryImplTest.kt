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

class ActivityRestrictionHistoryImplTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    private var stored = ""
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    @BeforeEach
    fun setup() {
        stored = ""
        whenever(preferences.get(StringNonKey.ActivityRestrictionHistory)).thenAnswer { stored }
        doAnswer { stored = it.getArgument(1); null }.whenever(preferences).put(eq(StringNonKey.ActivityRestrictionHistory), any<String>())
    }

    private fun history() = ActivityRestrictionHistoryImpl(preferences)

    @Test
    fun noRestrictionWithoutRecords() {
        assertThat(history().ratioAt(t0)).isEqualTo(1.0)
    }

    @Test
    fun restrictionHoldsFromItsFirstRunUntilARunWithoutIt() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 + 5 * min, 0.7)
        h.record(t0 + 10 * min, 0.7)
        h.record(t0 + 15 * min, 1.0)
        assertThat(h.ratioAt(t0 - 1)).isEqualTo(1.0)
        assertThat(h.ratioAt(t0)).isEqualTo(0.7)
        assertThat(h.ratioAt(t0 + 15 * min - 1)).isEqualTo(0.7)
        assertThat(h.ratioAt(t0 + 15 * min)).isEqualTo(1.0)
    }

    @Test
    fun restrictionLapsesWithTheTempBasalWhenTheLoopStops() {
        val h = history()
        h.record(t0, 0.7)
        assertThat(h.ratioAt(t0 + 29 * min)).isEqualTo(0.7)
        assertThat(h.ratioAt(t0 + 30 * min)).isEqualTo(1.0)
        // a run long after with the same ratio starts a new restriction instead of covering the gap
        h.record(t0 + 90 * min, 0.7)
        assertThat(h.ratioAt(t0 + 60 * min)).isEqualTo(1.0)
        assertThat(h.ratioAt(t0 + 90 * min)).isEqualTo(0.7)
    }

    @Test
    fun aChangedRatioStartsANewRestriction() {
        val h = history()
        h.record(t0, 0.85)
        h.record(t0 + 5 * min, 0.7)
        h.record(t0 + 10 * min, 1.0)
        assertThat(h.ratioAt(t0 + 4 * min)).isEqualTo(0.85)
        assertThat(h.ratioAt(t0 + 5 * min)).isEqualTo(0.7)
        assertThat(h.ratioAt(t0 + 10 * min)).isEqualTo(1.0)
    }

    @Test
    fun survivesARestart() {
        history().apply {
            record(t0, 0.7)
            record(t0 + 5 * min, 0.7)
            record(t0 + 10 * min, 1.0)
            record(t0 + 20 * min, 0.85)
        }
        val restarted = history()
        assertThat(restarted.ratioAt(t0 + 7 * min)).isEqualTo(0.7)
        assertThat(restarted.ratioAt(t0 + 12 * min)).isEqualTo(1.0)
        assertThat(restarted.ratioAt(t0 + 25 * min)).isEqualTo(0.85)
        assertThat(restarted.ratioAt(t0 + 50 * min)).isEqualTo(1.0)
        // and carries on where it was
        restarted.record(t0 + 25 * min, 0.85)
        assertThat(restarted.ratioAt(t0 + 54 * min)).isEqualTo(0.85)
    }

    @Test
    fun ignoresOlderRunsAndTreatsBadRatiosSafely() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 - 5 * min, 0.5)
        assertThat(h.ratioAt(t0 - 5 * min)).isEqualTo(1.0)
        h.record(t0 + 5 * min, Double.NaN) // not a restriction: ends it
        assertThat(h.ratioAt(t0 + 5 * min)).isEqualTo(1.0)
        h.record(t0 + 10 * min, -0.2) // below 0: restricts to 0
        assertThat(h.ratioAt(t0 + 10 * min)).isEqualTo(0.0)
    }

    @Test
    fun ignoresAMalformedStoredHistory() {
        stored = "garbage;1,2;${t0},${t0 + 5 * min},-1,0.7"
        val h = history()
        assertThat(h.ratioAt(t0 + 6 * min)).isEqualTo(0.7)
        assertThat(h.ratioAt(t0 + 35 * min)).isEqualTo(1.0)
    }

    @Test
    fun dropsRestrictionsOlderThanTwoDays() {
        val h = history()
        h.record(t0, 0.7)
        h.record(t0 + 5 * min, 1.0)
        h.record(t0 + 49 * 60 * min, 0.7)
        assertThat(stored.split(';')).hasSize(1)
        assertThat(h.ratioAt(t0 + min)).isEqualTo(1.0)
        assertThat(h.ratioAt(t0 + 49 * 60 * min)).isEqualTo(0.7)
    }
}
