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
import org.mockito.kotlin.atLeastOnce
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
        // point trails the newest raw reading by up to ~5 minutes), then the next minute's dense
        // window. Each reset bumps the persisted session id: the very first call legitimately takes
        // it to 1, and nothing here should take it to 2.
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
