package app.aaps.plugins.sync.wear.wearintegration

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearSendFilterTest {

    private val minute = 60_000L
    private val start = 1_800_000_000_000L

    @Test
    fun oneMinuteReadingsUpdateTheWatchWithEveryFifthReading() {
        val filter = WearSendFilter()
        val sent = mutableListOf<Int>()
        // A reading a minute, a few seconds early or late; each is followed by its loop run.
        val jitter = listOf(0L, -3_000L, 4_000L, -2_000L, 1_000L, -4_000L, 3_000L)
        for (i in 0..20) {
            val reading = start + i * minute + jitter[i % jitter.size]
            val afterCalculation = filter.pacedUpdate("bg", reading, "no change")
            val afterLoopRun = filter.pacedUpdate("bg", reading, "no change")
            assertThat(afterLoopRun).isEqualTo(afterCalculation) // the loop run goes with its reading's update
            if (afterCalculation) sent.add(i)
        }
        assertThat(sent).containsExactly(0, 5, 10, 15, 20).inOrder()
    }

    @Test
    fun fiveMinuteReadingsUpdateTheWatchWithEveryReading() {
        val filter = WearSendFilter()
        var reading = start
        for (gap in listOf(300_000L, 296_000L, 304_000L, 290_000L, 300_000L)) {
            reading += gap
            assertThat(filter.pacedUpdate("bg", reading, "no change")).isTrue()
        }
    }

    @Test
    fun theUsersOwnTreatmentsShowAtOnce() {
        val filter = WearSendFilter()
        assertThat(filter.pacedUpdate("bg", start, listOf(null, null))).isTrue()
        assertThat(filter.pacedUpdate("bg", start + minute, listOf(null, null))).isFalse()
        // A bolus between two paced updates goes out, once.
        assertThat(filter.pacedUpdate("bg", start + minute, listOf(start + minute to 2.0, null))).isTrue()
        assertThat(filter.pacedUpdate("bg", start + 2 * minute, listOf(start + minute to 2.0, null))).isFalse()
        // And the pace keeps its rhythm: the next update is still five minutes after the last paced one.
        assertThat(filter.pacedUpdate("bg", start + 4 * minute, listOf(start + minute to 2.0, null))).isFalse()
        assertThat(filter.pacedUpdate("bg", start + 5 * minute, listOf(start + minute to 2.0, null))).isTrue()
    }

    @Test
    fun aReadingOlderThanTheLastSentRestartsThePace() {
        val filter = WearSendFilter()
        assertThat(filter.pacedUpdate("bg", start, "no change")).isTrue()
        assertThat(filter.pacedUpdate("bg", start - 10 * minute, "no change")).isTrue()
        assertThat(filter.pacedUpdate("bg", start - 9 * minute, "no change")).isFalse()
    }

    @Test
    fun theBgAndTheLoopStatusKeepTheirOwnPace() {
        val filter = WearSendFilter()
        val bg = mutableListOf<Int>()
        val status = mutableListOf<Int>()
        for (i in 0..12) {
            val reading = start + i * minute
            if (filter.pacedUpdate("bg", reading, "no change")) bg.add(i)
            // The loop status switched to every 5 minutes two readings later than the BG.
            if (i >= 2 && filter.pacedUpdate("status", reading, "no change")) status.add(i)
        }
        assertThat(bg).containsExactly(0, 5, 10).inOrder()
        assertThat(status).containsExactly(2, 7, 12).inOrder()
        // Both see the user's bolus.
        assertThat(filter.pacedUpdate("bg", start + 13 * minute, "bolus")).isTrue()
        assertThat(filter.pacedUpdate("status", start + 13 * minute, "bolus")).isTrue()
    }

    @Test
    fun actionButtonsGoWithEveryUpdateUnlessOnlyWhenChanged() {
        val filter = WearSendFilter()
        repeat(3) { assertThat(filter.actionButtons("quickWizard", listOf("10 g"), start + it * minute, onlyWhenChanged = false, force = false)).isTrue() }
    }

    @Test
    fun onlyWhenChangedSendsChangesHourlyRefreshesAndRequests() {
        val filter = WearSendFilter()
        fun send(content: Any?, at: Long, force: Boolean = false) = filter.actionButtons("quickWizard", content, at, onlyWhenChanged = true, force = force)
        assertThat(send(listOf("10 g"), start)).isTrue()                    // first time
        assertThat(send(listOf("10 g"), start + minute)).isFalse()          // unchanged
        assertThat(send(listOf("10 g", "20 g"), start + 2 * minute)).isTrue() // changed
        assertThat(send(listOf("10 g", "20 g"), start + 3 * minute)).isFalse()
        assertThat(send(listOf("10 g", "20 g"), start + 3 * minute, force = true)).isTrue() // the watch asked
        assertThat(send(listOf("10 g", "20 g"), start + 62 * minute)).isFalse()
        assertThat(send(listOf("10 g", "20 g"), start + 63 * minute)).isTrue() // an hour since the last send
        // Each kind of message is tracked on its own.
        assertThat(filter.actionButtons("userActions", listOf("10 g", "20 g"), start + 64 * minute, onlyWhenChanged = true, force = false)).isTrue()
    }

    @Test
    fun theChartIsClearedOnceWhenTheChartDataAreOff() {
        val filter = WearSendFilter()
        assertThat(filter.clearChart(force = false)).isTrue()
        assertThat(filter.clearChart(force = false)).isFalse()
        assertThat(filter.clearChart(force = true)).isTrue()   // the watch asked for its data
        filter.chartSent()                                     // switched back on, and off again
        assertThat(filter.clearChart(force = false)).isTrue()
    }
}
