package app.aaps.plugins.smoothing

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.math.abs

class UnscentedKalmanFilterPluginTest {

    @Mock lateinit var aapsLogger: AAPSLogger
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var rxBus: RxBus
    @Mock lateinit var aapsSchedulers: AapsSchedulers
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var sp: SP

    private lateinit var ukf: UnscentedKalmanFilterPlugin

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(aapsSchedulers.io).thenReturn(Schedulers.trampoline())
        whenever(rxBus.toObservable(EventTherapyEventChange::class.java)).thenReturn(Observable.never())
        whenever(persistenceLayer.getTherapyEventDataFromTime(any<Long>(), any<Boolean>())).thenReturn(Single.just(emptyList<TE>()))
        ukf = UnscentedKalmanFilterPlugin(aapsLogger, rh, rxBus, aapsSchedulers, persistenceLayer, sp)
    }

    @Test
    fun denseOneMinuteDataIsActuallyFiltered() {
        // With a flat 2-minute minimum gap, every consecutive 1-minute pair became its own segment,
        // which is too short to filter - so every point came back with smoothed == value.
        val data = series(newest = T0, count = 60, stepMinutes = 1)

        ukf.smooth(data)

        val changed = data.count { abs(it.smoothed!! - it.value) > 0.01 }
        assertThat(changed).isGreaterThan(data.size / 2)
    }

    @Test
    fun interleavedDenseAndBucketedCallsDoNotResetLearning() {
        // One loop cycle on the raw path: the dense raw window, then the bucketed grid (whose newest
        // point can trail the newest raw reading), then the next minute's dense window. Each reset
        // bumps the persisted session id: the very first call legitimately takes it to 1, and nothing
        // here should take it to 2.
        ukf.smooth(series(newest = T0, count = 60, stepMinutes = 1))
        ukf.smooth(series(newest = T0 - 3 * MIN, count = 12, stepMinutes = 5))
        ukf.smooth(series(newest = T0 + MIN, count = 60, stepMinutes = 1))

        verify(sp, never()).putInt("ukf_session_id", 2)
    }

    @Test
    fun genuineBackwardJumpStillResetsLearning() {
        ukf.smooth(series(newest = T0, count = 60, stepMinutes = 1))
        ukf.smooth(series(newest = T0 - 60 * MIN, count = 60, stepMinutes = 1))

        verify(sp, atLeastOnce()).putInt("ukf_session_id", 2)
    }

    @Test
    fun forwardOnlyValuesDoNotChangeWhenNewerReadingsArrive() {
        // Forward pass only: each value depends on the readings up to its own time, so five newer
        // readings must leave every older smoothed value exactly as it was.
        val older = series(newest = T0, count = 60, stepMinutes = 1)
        val alone = copyOf(older)
        forwardUkf().smooth(alone)
        val withNewer = (series(newest = T0 + 5 * MIN, count = 5, stepMinutes = 1) + copyOf(older)).toMutableList()
        forwardUkf().smooth(withNewer)

        for (i in alone.indices) assertThat(withNewer[i + 5].smoothed).isEqualTo(alone[i].smoothed)
    }

    @Test
    fun backwardPassRevisesOlderValuesButNotTheNewest() {
        val data = series(newest = T0, count = 60, stepMinutes = 1)
        val rts = copyOf(data)
        val forward = copyOf(data)
        ukf.smooth(rts)
        forwardUkf().smooth(forward)

        assertThat(rts[0].smoothed).isEqualTo(forward[0].smoothed)
        assertThat((1 until data.size).count { abs(rts[it].smoothed!! - forward[it].smoothed!!) > 0.01 }).isGreaterThan(data.size / 2)
    }

    @Test
    fun learnedNoiseCanFallBelowTheOldFloorOf16() {
        // The series' +/-2 mg/dL noise is variance 4; with the old floor the learned R stayed at 16 or more.
        ukf.smooth(series(newest = T0, count = 120, stepMinutes = 1))

        val learnedR = argumentCaptor<Double>()
        verify(sp, atLeastOnce()).putDouble(eq("ukf_learned_r"), learnedR.capture())
        assertThat(learnedR.lastValue).isLessThan(16.0)
    }

    @Test
    fun forwardOnlyVariantKeepsItsOwnLearnedState() {
        forwardUkf().smooth(series(newest = T0, count = 60, stepMinutes = 1))

        verify(sp, atLeastOnce()).putDouble(eq("ukf_fwd_learned_r"), any())
        verify(sp, never()).putDouble(eq("ukf_learned_r"), any())
    }

    private fun forwardUkf() = UnscentedKalmanFilterForwardPlugin(aapsLogger, rh, rxBus, aapsSchedulers, persistenceLayer, sp)

    private fun copyOf(values: List<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> =
        values.map { InMemoryGlucoseValue(timestamp = it.timestamp, value = it.value) }.toMutableList()

    // Slowly rising line (0.5 mg/dL/min) with +/-2 mg/dL alternating noise, newest first.
    private fun series(newest: Long, count: Int, stepMinutes: Int): MutableList<InMemoryGlucoseValue> =
        MutableList(count) { i ->
            val minutesAgo = i * stepMinutes
            val trueValue = 120.0 + 0.5 * ((newest - T0) / MIN.toDouble() - minutesAgo)
            InMemoryGlucoseValue(timestamp = newest - minutesAgo * MIN, value = trueValue + if (i % 2 == 0) 2.0 else -2.0)
        }

    companion object {

        private const val MIN = 60_000L
        private const val T0 = 1_700_000_000_000L
    }
}
