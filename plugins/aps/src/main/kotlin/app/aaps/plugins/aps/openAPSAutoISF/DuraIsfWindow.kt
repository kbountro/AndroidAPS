package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.math.roundToLong

/**
 * How long glucose has been at its current level within the last hour, and at what average: GlucoseStatusAutoIsf's
 * duraISFminutes and duraISFaverage, from which OpenAPSAutoISFPlugin computes, when minutes >= 10 and average > 150 mg/dL,
 *
 *     dura_ISF = 1 + (minutes / 60) * dura_ISF_weight * interpolate(average)
 *
 * The ISF curve (interpolate) is the change in insulin effectiveness measured after one hour at a steady glucose level, and
 * the study says nothing about earlier or later times. So the effect builds up linearly over the first hour and then holds:
 * the count never reaches back more than [MAX_MINUTES], and the average is over that same window.
 *
 * The count, over the bucketed readings r0 (the newest), r1, r2, ... (newest first, 5 minutes apart, smoothed values):
 *
 *  1. If r0 <= [FLOOR_MGDL]: minutes = 0, average = r0. Done.
 *  2. Start with sum = r0, count = 1, average = r0, minutes = 0.
 *  3. Take each older reading ri in turn:
 *     a. skip it if it is gap-filled or invalid (39 mg/dL or less);
 *     b. stop if it is more than [MAX_MINUTES] old;
 *     c. stop if it is more than [MAX_GAP_MINUTES] older than the last reading counted (a CGM gap);
 *     d. stop if ri <= [FLOOR_MGDL]: glucose was not high yet;
 *     e. stop if ri >= 1.05 * average: ri is 5% or more above the readings since, so glucose has fallen since then;
 *     f. otherwise count it: sum += ri, count += 1, average = sum / count, minutes = the age of ri.
 *  4. The result is minutes (0 to 60) and average.
 *
 * Step d lets every reading above 150 mg/dL through, however far below the current level, so the time spent rising to the
 * level counts, and so does a dip within a plateau. Step e ends the count at a fall of 5% or more, so after a fall it starts
 * again from there, as when insulin breaks through ("breaking the wall"). For example, rising 10 mg/dL every 5 minutes from
 * 145 to 250: 250, 240, ..., 160 count and 145 stops the count, giving 45 minutes at an average of 205; an hour at 250 after a
 * rise of any length gives 60 minutes at 250, i.e. dura_ISF = 1 + dura_ISF_weight * interpolate(250).
 *
 * The count used to stop also at a reading more than 5% below the average (a band of +/-5%) and had no time limit, so a rise
 * counted only once glucose levelled off near its top, a short dip reset an hour-long plateau, and the effect kept growing
 * past the hour.
 */
internal object DuraIsfWindow {

    /** Glucose (mg/dL) at and below which OpenAPSAutoISFPlugin.interpolate(), and so dura_ISF, is nil: time there does not count. */
    const val FLOOR_MGDL = 150.0

    /** The window: the hour after which the study measured the effect. Readings older than this never count. */
    const val MAX_MINUTES = 60

    /** A reading this fraction or more above the running average of the newer ones ends the count: glucose has fallen since. */
    private const val FALL_BAND = 0.05

    /** A CGM gap longer than this ends the count. */
    private const val MAX_GAP_MINUTES = 13

    /** [minutes] counted back from the newest reading, 0 to [MAX_MINUTES], and the [average] glucose over them (the newest reading alone at 0). */
    class Window(val minutes: Long, val average: Double)

    /** The window of [data], bucketed glucose newest first (AutosensDataStore.getBucketedDataTableCopy()), not empty. */
    fun of(data: List<InMemoryGlucoseValue>): Window {
        val now = data[0]
        var sum = now.recalculated
        var count = 1
        var average = sum
        var minutes = 0L
        if (now.recalculated <= FLOOR_MGDL) return Window(minutes, average)                                // 1
        for (i in 1 until data.size) {                                                                      // 3
            val then = data[i]
            if (then.value <= 39 || then.filledGap) continue                                                // a
            val minutesAgo = ((now.timestamp - then.timestamp) / 60_000.0).roundToLong()
            if (minutesAgo > MAX_MINUTES) break                                                             // b
            if (minutesAgo - minutes > MAX_GAP_MINUTES) break                                               // c
            if (then.recalculated <= FLOOR_MGDL) break                                                      // d
            if (then.recalculated >= average * (1 + FALL_BAND)) break                                       // e
            sum += then.recalculated                                                                        // f
            count++
            average = sum / count
            minutes = minutesAgo
        }
        return Window(minutes, average)
    }
}
