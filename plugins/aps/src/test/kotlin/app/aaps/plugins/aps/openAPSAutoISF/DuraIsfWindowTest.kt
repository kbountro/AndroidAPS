package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DuraIsfWindowTest {

    private val now = 1514766900000L

    /** Bucketed glucose, newest first, 5 minutes apart. */
    private fun every5min(vararg values: Double): List<InMemoryGlucoseValue> =
        values.mapIndexed { i, value -> InMemoryGlucoseValue(timestamp = now - i * 300_000L, value = value) }

    @Test
    fun aRiseCountsFromWhereGlucoseRoseAbove150() {
        // 10 mg/dL every 5 minutes from 145 to 250. The old +/-5% band stopped at 230, after 5 minutes.
        val window = DuraIsfWindow.of(every5min(250.0, 240.0, 230.0, 220.0, 210.0, 200.0, 190.0, 180.0, 170.0, 160.0, 145.0, 140.0))
        assertThat(window.minutes).isEqualTo(45L)
        assertThat(window.average).isWithin(1e-9).of(205.0)
        // The curve averaged over the ten readings: (0.18 + 0.166 + ... + 0.022) / 10, below curve(205) = 0.117.
        assertThat(window.curveAverage).isWithin(1e-9).of(0.109)
    }

    @Test
    fun aShortSpikeCountsOnlyForItsOwnMinutes() {
        // 55 minutes at 160, then 300 for the newest reading: 1 of 12 readings at curve(300) = 0.25, 11 at curve(160) = 0.022.
        val window = DuraIsfWindow.of(every5min(300.0, *DoubleArray(11) { 160.0 }, 140.0))
        assertThat(window.minutes).isEqualTo(55L)
        assertThat(window.average).isWithin(1e-9).of(2060.0 / 12)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.041)
    }

    @Test
    fun aDipWithinAPlateauDoesNotEndTheCount() {
        // An hour around 220 with one reading at 205, 15 minutes ago: the old band stopped there, after 10 minutes.
        val window = DuraIsfWindow.of(every5min(220.0, 218.0, 221.0, 205.0, 219.0, 222.0, 220.0, 221.0, 219.0, 220.0, 222.0, 218.0, 220.0, 140.0))
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(0.01).of(218.85)
        assertThat(window.curveAverage).isWithin(1e-4).of(0.1364) // all between 200 and 300, where the curve is linear: curve(218.85)
    }

    @Test
    fun onlyTheLastHourCounts() {
        // Two hours at 200: the count stops at 60 minutes.
        val steady = DuraIsfWindow.of(every5min(*DoubleArray(25) { 200.0 }))
        assertThat(steady.minutes).isEqualTo(60L)
        assertThat(steady.average).isWithin(1e-9).of(200.0)
        assertThat(steady.curveAverage).isWithin(1e-9).of(0.11)
        // An hour at 250 after a two-hour rise from 150: the average is the last hour's, not the rise's.
        val afterRise = DuraIsfWindow.of(every5min(*DoubleArray(13) { 250.0 }, *DoubleArray(24) { 246.0 - 4.0 * it }))
        assertThat(afterRise.minutes).isEqualTo(60L)
        assertThat(afterRise.average).isWithin(1e-9).of(250.0)
        assertThat(afterRise.curveAverage).isWithin(1e-9).of(0.18)
        // A reading 65 minutes old is outside the window even when it would otherwise count.
        val slowRise = DuraIsfWindow.of(every5min(*DoubleArray(20) { 300.0 - 2.5 * it }))
        assertThat(slowRise.minutes).isEqualTo(60L)
        assertThat(slowRise.average).isWithin(1e-9).of(285.0)
        assertThat(slowRise.curveAverage).isWithin(1e-9).of(0.229)
    }

    @Test
    fun aFallOfMoreThanFivePercentEndsTheCount() {
        // Down from a plateau at 260: 220 is more than 5% above 200, so the count starts again from the fall.
        val window = DuraIsfWindow.of(every5min(200.0, 220.0, 240.0, 260.0, 260.0, 260.0))
        assertThat(window.minutes).isEqualTo(0L)
        assertThat(window.average).isWithin(1e-9).of(200.0)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.11) // the newest reading alone; dura_ISF needs 10 minutes
    }

    @Test
    fun aSlowFallWithinFivePercentOfTheAverageKeepsCounting() {
        // As before the change (GlucoseStatusTestAutoIsf's data): each older reading is within 5% of the newer ones' average.
        val window = DuraIsfWindow.of(every5min(214.0, 216.0, 219.0, 223.0, 222.0, 224.0, 226.0, 228.0))
        assertThat(window.minutes).isEqualTo(35L)
        assertThat(window.average).isWithin(0.1).of(221.5)
    }

    @Test
    fun nothingCountsAtOrBelow150() {
        val atFloor = DuraIsfWindow.of(every5min(150.0, 160.0, 170.0))
        assertThat(atFloor.minutes).isEqualTo(0L)
        assertThat(atFloor.average).isWithin(1e-9).of(150.0)
        assertThat(atFloor.curveAverage).isEqualTo(0.0)
        // The count stops at the first reading at or below 150.
        val above = DuraIsfWindow.of(every5min(180.0, 170.0, 160.0, 150.0, 160.0))
        assertThat(above.minutes).isEqualTo(10L)
        assertThat(above.average).isWithin(1e-9).of(170.0)
        assertThat(above.curveAverage).isWithin(1e-9).of(0.044) // (0.066 + 0.044 + 0.022) / 3
    }

    @Test
    fun aGapOfMoreThan13MinutesEndsTheCount() {
        val data = every5min(200.0, 205.0).toMutableList()
        data.add(InMemoryGlucoseValue(timestamp = now - 20 * 60_000L, value = 200.0)) // 15 minutes after the one before
        val window = DuraIsfWindow.of(data)
        assertThat(window.minutes).isEqualTo(5L)
        assertThat(window.average).isWithin(1e-9).of(202.5)
    }

    @Test
    fun gapFilledAndInvalidReadingsAreSkippedAndSmoothedValuesUsed() {
        // A gap-filled 300 or an invalid 38 in between would end the count if they counted.
        val gapFilled = every5min(200.0, 300.0, 204.0).also { it[1].filledGap = true }
        assertThat(DuraIsfWindow.of(gapFilled).minutes).isEqualTo(10L)
        assertThat(DuraIsfWindow.of(gapFilled).average).isWithin(1e-9).of(202.0)
        val invalid = every5min(200.0, 38.0, 204.0)
        assertThat(DuraIsfWindow.of(invalid).minutes).isEqualTo(10L)
        // The smoothed value counts, not the raw one.
        val smoothed = every5min(200.0, 300.0).also { it[1].smoothed = 204.0 }
        assertThat(DuraIsfWindow.of(smoothed).minutes).isEqualTo(5L)
        assertThat(DuraIsfWindow.of(smoothed).average).isWithin(1e-9).of(202.0)
    }
}
