package app.aaps.core.objects.extensions

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.smoothing.Smoothing
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OneMinuteCurveTest {

    private val minute = 60_000L
    private val now = 100_000 * minute

    // Adds [offset] to every value it smooths and remembers how many values it got.
    private class RecordingSmoothing(private val offset: Double) : Smoothing {

        val sizes = mutableListOf<Int>()
        override fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
            sizes += data.size
            data.forEach { it.smoothed = it.value + offset }
            return data
        }
    }

    // One reading a minute, newest first: [minutes] + 1 readings ending at [newest], each one higher than the one before.
    private fun readings(newest: Long, minutes: Int): List<GV> =
        (0..minutes).map {
            val time = newest - it * minute
            GV(timestamp = time, value = 100.0 + time / minute % 1000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG)
        }

    @Test
    fun theFirstLoadSmoothsEveryReading() {
        val smoothing = RecordingSmoothing(1000.0)
        val curve = OneMinuteCurve.smooth(readings(now, 180), null, smoothing)

        assertThat(smoothing.sizes).containsExactly(181)
        assertThat(curve).hasSize(181)
        assertThat(curve.all { it.smoothed == it.value + 1000.0 }).isTrue()
    }

    @Test
    fun laterLoadsSmoothOnlyTheLastTwoHoursAndKeepOlderValues() {
        val previous = OneMinuteCurve.smooth(readings(now, 180), null, RecordingSmoothing(1000.0))
        val smoothing = RecordingSmoothing(2000.0)

        // One minute later: a new reading, the oldest one dropped
        val curve = OneMinuteCurve.smooth(readings(now + minute, 180), previous, smoothing)

        assertThat(smoothing.sizes).containsExactly(121) // the last 2 hours, both ends included
        assertThat(curve).hasSize(181)
        for (value in curve) {
            val age = now + minute - value.timestamp
            if (age < OneMinuteCurve.KEEP_AFTER_MS) assertThat(value.smoothed).isEqualTo(value.value + 2000.0) // smoothed now
            else assertThat(value).isSameInstanceAs(previous.first { it.timestamp == value.timestamp }) // kept as it was
        }
    }

    @Test
    fun aChangedOlderReadingMakesEveryReadingSmoothedAgain() {
        val previous = OneMinuteCurve.smooth(readings(now, 180), null, RecordingSmoothing(1000.0))
        val changed = readings(now + minute, 180).toMutableList()
        changed[150] = changed[150].copy(value = 999.0) // 150 minutes old: a backfilled or changed reading
        val smoothing = RecordingSmoothing(2000.0)

        val curve = OneMinuteCurve.smooth(changed, previous, smoothing)

        assertThat(smoothing.sizes).containsExactly(181)
        assertThat(curve.all { it.smoothed == it.value + 2000.0 }).isTrue()
    }

    @Test
    fun withoutOlderReadingsNoPreviousCurveIsNeeded() {
        val smoothing = RecordingSmoothing(1000.0)

        val curve = OneMinuteCurve.smooth(readings(now, 60), null, smoothing)

        assertThat(smoothing.sizes).containsExactly(61)
        assertThat(curve.all { it.smoothed == it.value + 1000.0 }).isTrue()
    }

    @Test
    fun theGridTakesItsValuesFromTheCurve() {
        val curve = listOf(
            InMemoryGlucoseValue(timestamp = now, value = 120.0, smoothed = 118.0),
            InMemoryGlucoseValue(timestamp = now - 2 * minute, value = 110.0, smoothed = 112.0),
            InMemoryGlucoseValue(timestamp = now - 6 * minute, value = 100.0, smoothed = 100.0)
        )
        val grid = listOf(
            InMemoryGlucoseValue(timestamp = now + minute, value = 121.0), // newer than the newest reading
            InMemoryGlucoseValue(timestamp = now, value = 120.0), // the newest reading itself
            InMemoryGlucoseValue(timestamp = now - 5 * minute, value = 102.0), // between two readings
            InMemoryGlucoseValue(timestamp = now - 10 * minute, value = 95.0) // older than the oldest reading
        )

        OneMinuteCurve.sampleOnto(grid, curve)

        assertThat(grid.map { it.smoothed }).containsExactly(118.0, 118.0, 103.0, 100.0).inOrder()
        assertThat(grid.map { it.value }).containsExactly(121.0, 120.0, 102.0, 95.0).inOrder() // the grid's own values stay
    }
}
