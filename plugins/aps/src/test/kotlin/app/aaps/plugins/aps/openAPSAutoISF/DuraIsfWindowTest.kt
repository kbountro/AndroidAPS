package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.DuraIsfStart
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DuraIsfWindowTest {

    private val now = 1514766900000L

    /** Bucketed glucose, newest first, 5 minutes apart. */
    private fun every5min(vararg values: Double): List<InMemoryGlucoseValue> =
        values.mapIndexed { i, value -> InMemoryGlucoseValue(timestamp = now - i * 300_000L, value = value) }

    /** A reading [minutesAgo] minutes before the newest. */
    private fun reading(minutesAgo: Int, value: Double) = InMemoryGlucoseValue(timestamp = now - minutesAgo * 60_000L, value = value)

    @Test
    fun aRiseCountsFromWhereGlucoseRoseAbove150() {
        // 10 mg/dL every 5 minutes from 145 to 250. The old +/-5% band stopped at 230, after 5 minutes.
        val window = DuraIsfWindow.of(every5min(250.0, 240.0, 230.0, 220.0, 210.0, 200.0, 190.0, 180.0, 170.0, 160.0, 145.0, 140.0))
        assertThat(window.minutes).isEqualTo(45L)
        assertThat(window.average).isWithin(1e-9).of(205.0)
        // The curve averaged over the 45 minutes by trapezoids: 5 * ((0.18 + 0.022) / 2 + 0.166 + 0.152 + ... + 0.044) / 45,
        // below curve(205) = 0.117.
        assertThat(window.curveAverage).isWithin(1e-9).of(4.945 / 45)
        assertThat(window.start).isEqualTo(DuraIsfStart.RISE)
    }

    @Test
    fun aShortSpikeCountsOnlyForItsOwnMinutes() {
        // 50 minutes at 160, then up to 300 in the last 5: (5 * (0.25 + 0.022) / 2 + 50 * 0.022) / 55, where curve(300) = 0.25.
        val window = DuraIsfWindow.of(every5min(300.0, *DoubleArray(11) { 160.0 }, 140.0))
        assertThat(window.minutes).isEqualTo(55L)
        assertThat(window.average).isWithin(1e-9).of(2060.0 / 12)
        assertThat(window.curveAverage).isWithin(1e-9).of(1.78 / 55)
        assertThat(window.start).isEqualTo(DuraIsfStart.RISE)
    }

    @Test
    fun aDipWithinAPlateauDoesNotEndTheCount() {
        // An hour around 220 with one reading at 205, 15 minutes ago: the old band stopped there, after 10 minutes.
        val window = DuraIsfWindow.of(every5min(220.0, 218.0, 221.0, 205.0, 219.0, 222.0, 220.0, 221.0, 219.0, 220.0, 222.0, 218.0, 220.0, 140.0))
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(0.01).of(218.85)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.13625) // all between 200 and 300, where the curve is linear
        assertThat(window.start).isEqualTo(DuraIsfStart.HOUR)
    }

    @Test
    fun onlyTheLastHourCounts() {
        // Two hours at 200: the count stops at 60 minutes.
        val steady = DuraIsfWindow.of(every5min(*DoubleArray(25) { 200.0 }))
        assertThat(steady.minutes).isEqualTo(60L)
        assertThat(steady.average).isWithin(1e-9).of(200.0)
        assertThat(steady.curveAverage).isWithin(1e-9).of(0.11)
        assertThat(steady.start).isEqualTo(DuraIsfStart.HOUR)
        // An hour at 250 after a two-hour rise from 150: the average is the last hour's, not the rise's.
        val afterRise = DuraIsfWindow.of(every5min(*DoubleArray(13) { 250.0 }, *DoubleArray(24) { 246.0 - 4.0 * it }))
        assertThat(afterRise.minutes).isEqualTo(60L)
        assertThat(afterRise.average).isWithin(1e-9).of(250.0)
        assertThat(afterRise.curveAverage).isWithin(1e-9).of(0.18)
        assertThat(afterRise.start).isEqualTo(DuraIsfStart.HOUR)
        // A reading 65 minutes old is outside the window even when it would otherwise count.
        val slowRise = DuraIsfWindow.of(every5min(*DoubleArray(20) { 300.0 - 2.5 * it }))
        assertThat(slowRise.minutes).isEqualTo(60L)
        assertThat(slowRise.average).isWithin(1e-9).of(285.0)
        assertThat(slowRise.curveAverage).isWithin(1e-9).of(0.229)
        assertThat(slowRise.start).isEqualTo(DuraIsfStart.HOUR)
    }

    @Test
    fun oneMinuteReadingsCountToTheMinute() {
        val window = DuraIsfWindow.of((0..23).map { reading(it, 200.0) } + reading(24, 140.0))
        assertThat(window.minutes).isEqualTo(23L)
        assertThat(window.average).isWithin(1e-9).of(200.0)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.11)
        assertThat(window.start).isEqualTo(DuraIsfStart.RISE)
    }

    @Test
    fun everyMinuteWeighsTheSameHoweverDenseTheReadings() {
        // The last 30 minutes at 250, the 30 before at 200 with no readings from 41 to 44 minutes ago: 31 readings at 250 and
        // 26 at 200, but 30 minutes of each (plus the minute between them). The curve average is by time:
        // (30 * 0.18 + (0.18 + 0.11) / 2 + 29 * 0.11) / 60, not by reading (31 * 0.18 + 26 * 0.11) / 57 = 0.148.
        val data = (0..30).map { reading(it, 250.0) } + ((31..40) + (45..60)).map { reading(it, 200.0) }
        val window = DuraIsfWindow.of(data)
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(1e-9).of((31 * 250.0 + 26 * 200.0) / 57)
        assertThat(window.curveAverage).isWithin(1e-9).of(8.735 / 60)
        // The 1-minute readings end at the hour (GlucoseStatusCalculatorAutoIsf takes the last hour of them): the whole hour.
        assertThat(window.start).isEqualTo(DuraIsfStart.HOUR)
    }

    @Test
    fun aFallOnOneMinuteReadingsIsTestedOnASlidingFiveMinuteAverage() {
        // Falling 36 mg/dL/h to 200 now. At each reading, the average of it and the four before it is compared with the average
        // of all the newer readings: 30 minutes ago that is 219.2 (readings 30 to 34 minutes old), 5% or more above the 208.7
        // of the readings since, so the count ends at 29 minutes (on the table: 25; single readings: 33).
        val window = DuraIsfWindow.of((0..66).map { reading(it, 200.0 + 0.6 * it) })
        assertThat(window.minutes).isEqualTo(29L)
        assertThat(window.average).isWithin(1e-6).of(208.7)
        assertThat(window.curveAverage).isWithin(1e-6).of(0.12218)
        assertThat(window.start).isEqualTo(DuraIsfStart.FALL)
    }

    @Test
    fun aSingleHighOneMinuteReadingDoesNotEndTheCount() {
        // An hour at 200 with one reading at 212, 20 minutes ago: 6% above the average, which would end the count reading by
        // reading, but every sliding 5-minute average that includes it is 202.4.
        val window = DuraIsfWindow.of((0..60).map { reading(it, if (it == 20) 212.0 else 200.0) })
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(1e-9).of((60 * 200.0 + 212.0) / 61)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.11028)
        assertThat(window.start).isEqualTo(DuraIsfStart.HOUR)
    }

    @Test
    fun aShortDipTo150OrBelowOnOneMinuteReadingsDoesNotEndTheCount() {
        // An hour at 160 with two readings at 145, 20 and 21 minutes ago: every 5-minute average that includes them is 154 or
        // more, so the count goes on (testing the readings alone, it stopped after 19 minutes). They count at curve(145) = 0:
        // (19 * 0.022 + 0.011 + 0 + 0.011 + 38 * 0.022) / 60.
        val window = DuraIsfWindow.of((0..60).map { reading(it, if (it == 20 || it == 21) 145.0 else 160.0) })
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(1e-9).of((59 * 160.0 + 2 * 145.0) / 61)
        assertThat(window.curveAverage).isWithin(1e-9).of(1.276 / 60)
        assertThat(window.start).isEqualTo(DuraIsfStart.HOUR)
    }

    @Test
    fun aRiseOnOneMinuteReadingsCountsUntilItsFiveMinuteAverageReaches150() {
        // Rising 2 mg/dL a minute to 200: the 5-minute average ending at a reading is 4 below it, 150 at the reading of 154,
        // 23 minutes ago, so 22 minutes count (testing the readings alone, 24). The curve, linear from 150 to 200, averages
        // (curve(200) + curve(156)) / 2 = (0.11 + 0.0132) / 2 over them.
        val window = DuraIsfWindow.of((0..40).map { reading(it, 200.0 - 2 * it) })
        assertThat(window.minutes).isEqualTo(22L)
        assertThat(window.average).isWithin(1e-9).of(178.0)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.0616)
        assertThat(window.start).isEqualTo(DuraIsfStart.RISE)
    }

    @Test
    fun theNewestReadingIsTestedOnItsFiveMinuteAverageToo() {
        // An hour at 155, then one reading at 149: its 5-minute average is 153.8, so the hour counts (the reading alone gave 0).
        val window = DuraIsfWindow.of(listOf(reading(0, 149.0)) + (1..60).map { reading(it, 155.0) })
        assertThat(window.minutes).isEqualTo(60L)
        assertThat(window.average).isWithin(1e-9).of((149.0 + 60 * 155.0) / 61)
        assertThat(window.curveAverage).isWithin(1e-9).of((0.011 / 2 + 59 * 0.011) / 60) // curve(149) = 0, curve(155) = 0.011
        // On the table a reading's 5-minute average is the reading itself: nothing counts.
        val table = DuraIsfWindow.of(every5min(149.0, *DoubleArray(12) { 155.0 }))
        assertThat(table.minutes).isEqualTo(0L)
        assertThat(table.start).isEqualTo(DuraIsfStart.NOT_ABOVE_FLOOR)
    }

    @Test
    fun whenGlucoseIsNotAbove150TheAverageIsTheFiveMinuteAverageThatShowedIt() {
        // A newest reading of 152 after readings at 145: its 5-minute average is (152 + 4 * 145) / 5 = 146.4, not above 150.
        val window = DuraIsfWindow.of(listOf(reading(0, 152.0)) + (1..10).map { reading(it, 145.0) })
        assertThat(window.minutes).isEqualTo(0L)
        assertThat(window.average).isWithin(1e-9).of(146.4)
        assertThat(window.curveAverage).isEqualTo(0.0)
        assertThat(window.start).isEqualTo(DuraIsfStart.NOT_ABOVE_FLOOR)
        // An invalid newest reading: nothing counts, at its own value.
        val invalid = DuraIsfWindow.of(listOf(reading(0, 38.0)) + (1..10).map { reading(it, 200.0) })
        assertThat(invalid.minutes).isEqualTo(0L)
        assertThat(invalid.average).isWithin(1e-9).of(38.0)
        assertThat(invalid.start).isEqualTo(DuraIsfStart.NOT_ABOVE_FLOOR)
    }

    @Test
    fun aFallOfMoreThanFivePercentEndsTheCount() {
        // Down from a plateau at 260: 220 is more than 5% above 200, so the count starts again from the fall.
        val window = DuraIsfWindow.of(every5min(200.0, 220.0, 240.0, 260.0, 260.0, 260.0))
        assertThat(window.minutes).isEqualTo(0L)
        assertThat(window.average).isWithin(1e-9).of(200.0)
        assertThat(window.curveAverage).isWithin(1e-9).of(0.11) // the newest reading alone; dura_ISF needs 10 minutes
        assertThat(window.start).isEqualTo(DuraIsfStart.FALL)
    }

    @Test
    fun aSlowFallWithinFivePercentOfTheAverageKeepsCounting() {
        // As before the change (GlucoseStatusTestAutoIsf's data): each older reading is within 5% of the newer ones' average.
        val window = DuraIsfWindow.of(every5min(214.0, 216.0, 219.0, 223.0, 222.0, 224.0, 226.0, 228.0))
        assertThat(window.minutes).isEqualTo(35L)
        assertThat(window.average).isWithin(0.1).of(221.5)
        assertThat(window.start).isEqualTo(DuraIsfStart.OLDEST) // all eight readings count
    }

    @Test
    fun nothingCountsAtOrBelow150() {
        val atFloor = DuraIsfWindow.of(every5min(150.0, 160.0, 170.0))
        assertThat(atFloor.minutes).isEqualTo(0L)
        assertThat(atFloor.average).isWithin(1e-9).of(150.0)
        assertThat(atFloor.curveAverage).isEqualTo(0.0)
        assertThat(atFloor.start).isEqualTo(DuraIsfStart.NOT_ABOVE_FLOOR)
        // On the table, where a reading's 5-minute average is the reading itself, the count stops at the first one at or below 150.
        val above = DuraIsfWindow.of(every5min(180.0, 170.0, 160.0, 150.0, 160.0))
        assertThat(above.minutes).isEqualTo(10L)
        assertThat(above.average).isWithin(1e-9).of(170.0)
        assertThat(above.curveAverage).isWithin(1e-9).of(0.044) // (0.066 + 0.044 + 0.022) / 3
        assertThat(above.start).isEqualTo(DuraIsfStart.RISE)
    }

    @Test
    fun aGapOfMoreThan13MinutesEndsTheCount() {
        val data = every5min(200.0, 205.0).toMutableList()
        data.add(InMemoryGlucoseValue(timestamp = now - 20 * 60_000L, value = 200.0)) // 15 minutes after the one before
        val window = DuraIsfWindow.of(data)
        assertThat(window.minutes).isEqualTo(5L)
        assertThat(window.average).isWithin(1e-9).of(202.5)
        assertThat(window.start).isEqualTo(DuraIsfStart.GAP)
    }

    @Test
    fun aHoleBeforeTheEndOfTheHourIsAGap() {
        // 50 minutes at 200, the readings 55 and 60 minutes ago gap-filled, the next 65 minutes ago: past the hour, after a hole.
        val data = every5min(*DoubleArray(14) { 200.0 }).also { for (i in 11..12) it[i].filledGap = true }
        val window = DuraIsfWindow.of(data)
        assertThat(window.minutes).isEqualTo(50L)
        assertThat(window.start).isEqualTo(DuraIsfStart.GAP)
    }

    @Test
    fun gapFilledAndInvalidReadingsAreSkippedAndSmoothedValuesUsed() {
        // A gap-filled 300 or an invalid 38 in between would end the count if they counted.
        val gapFilled = every5min(200.0, 300.0, 204.0).also { it[1].filledGap = true }
        assertThat(DuraIsfWindow.of(gapFilled).minutes).isEqualTo(10L)
        assertThat(DuraIsfWindow.of(gapFilled).average).isWithin(1e-9).of(202.0)
        assertThat(DuraIsfWindow.of(gapFilled).start).isEqualTo(DuraIsfStart.OLDEST)
        val invalid = every5min(200.0, 38.0, 204.0)
        assertThat(DuraIsfWindow.of(invalid).minutes).isEqualTo(10L)
        // The smoothed value counts, not the raw one.
        val smoothed = every5min(200.0, 300.0).also { it[1].smoothed = 204.0 }
        assertThat(DuraIsfWindow.of(smoothed).minutes).isEqualTo(5L)
        assertThat(DuraIsfWindow.of(smoothed).average).isWithin(1e-9).of(202.0)
    }

    @Test
    fun theLogSaysHowLongGlucoseHasBeenAbove150AtWhatAverageAndSinceWhen() {
        assertThat(DuraIsfWindow.describe(45.0, 205.0, DuraIsfStart.RISE))
            .isEqualTo("glucose above 150 mg/dL for 45 min at an average of 205 mg/dL (since it rose above 150 mg/dL)")
        assertThat(DuraIsfWindow.describe(20.0, 199.6, DuraIsfStart.FALL))
            .isEqualTo("glucose above 150 mg/dL for 20 min at an average of 200 mg/dL (since it last fell by 5% or more)")
        assertThat(DuraIsfWindow.describe(60.0, 250.0, DuraIsfStart.HOUR))
            .isEqualTo("glucose above 150 mg/dL for 60 min at an average of 250 mg/dL (the whole hour counted)")
        assertThat(DuraIsfWindow.describe(12.0, 180.0, DuraIsfStart.GAP))
            .isEqualTo("glucose above 150 mg/dL for 12 min at an average of 180 mg/dL (since a gap in the readings)")
        assertThat(DuraIsfWindow.describe(35.0, 221.4, DuraIsfStart.OLDEST))
            .isEqualTo("glucose above 150 mg/dL for 35 min at an average of 221 mg/dL (since the oldest reading)")
        assertThat(DuraIsfWindow.describe(15.0, 160.0, null)).isEqualTo("glucose above 150 mg/dL for 15 min at an average of 160 mg/dL")
        // Nothing counts: the 5-minute average that showed it.
        assertThat(DuraIsfWindow.describe(0.0, 146.4, DuraIsfStart.NOT_ABOVE_FLOOR))
            .isEqualTo("glucose not above 150 mg/dL (5-minute average 146 mg/dL)")
        // The readings counted average 150 or less.
        assertThat(DuraIsfWindow.describe(12.0, 149.6, DuraIsfStart.GAP))
            .isEqualTo("glucose at an average of 150 mg/dL for 12 min (since a gap in the readings), not above 150 mg/dL")
        // A value that is not a number does not stop the loop run.
        assertThat(DuraIsfWindow.describe(15.0, Double.NaN, DuraIsfStart.RISE))
            .isEqualTo("glucose at an average of ? mg/dL for 15 min (since it rose above 150 mg/dL), not above 150 mg/dL")
    }
}
