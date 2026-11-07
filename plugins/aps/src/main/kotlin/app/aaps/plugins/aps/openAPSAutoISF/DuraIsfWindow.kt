package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.math.roundToLong

/**
 * How long glucose has been at its current level within the last hour, its average, and the average of the ISF curve over
 * that time: GlucoseStatusAutoIsf's duraISFminutes, duraISFaverage and duraISFcurveAverage, from which OpenAPSAutoISFPlugin
 * computes, when minutes >= 10 and average > 150 mg/dL,
 *
 *     dura_ISF = 1 + (minutes / 60) * dura_ISF_weight * curveAverage
 *
 * The ISF curve ([AutoIsfCurve]) is the change in insulin effectiveness measured after one hour at a steady glucose level,
 * and the study says nothing about earlier or later times. So each reading counts by its own curve value: every 5 minutes at
 * a glucose level adds 1/12 of that level's one-hour effect, (minutes / 60) * curveAverage being the sum of curve(ri) * 5 / 60.
 * Higher readings count more, a short spike only for its own minutes. The effect builds up over the first hour and then holds:
 * the count never reaches back more than [MAX_MINUTES].
 *
 * The count, over the bucketed readings r0 (the newest), r1, r2, ... (newest first, 5 minutes apart, smoothed values):
 *
 *  1. If r0 <= [FLOOR_MGDL]: minutes = 0, average = r0, curveAverage = 0. Done.
 *  2. Start with sum = r0, curveSum = curve(r0), count = 1, average = r0, minutes = 0.
 *  3. Take each older reading ri in turn:
 *     a. skip it if it is gap-filled or invalid (39 mg/dL or less);
 *     b. stop if it is more than [MAX_MINUTES] old;
 *     c. stop if it is more than [MAX_GAP_MINUTES] older than the last reading counted (a CGM gap);
 *     d. stop if ri <= [FLOOR_MGDL]: glucose was not high yet;
 *     e. stop if ri >= 1.05 * average: ri is 5% or more above the readings since, so glucose has fallen since then;
 *     f. otherwise count it: sum += ri, curveSum += curve(ri), count += 1, average = sum / count, minutes = the age of ri.
 *  4. The result is minutes (0 to 60), average and curveAverage = curveSum / count.
 *
 * Step d lets every reading above 150 mg/dL through, however far below the current level, so the time spent rising to the
 * level counts, and so does a dip within a plateau. Step e ends the count at a fall of 5% or more, so after a fall it starts
 * again from there, as when insulin breaks through ("breaking the wall"). For example, rising 10 mg/dL every 5 minutes from
 * 145 to 250: 250, 240, ..., 160 count and 145 stops the count, giving 45 minutes and a curve average of 0.109, so
 * dura_ISF = 1 + 0.75 * dura_ISF_weight * 0.109. An hour at 250 after a rise of any length gives 60 minutes and curve(250).
 *
 * The count used to stop also at a reading more than 5% below the average (a band of +/-5%) and had no time limit, so a rise
 * counted only once glucose levelled off near its top, a short dip reset an hour-long plateau, and the effect kept growing
 * past the hour; and the curve was taken at the average glucose rather than averaged over the readings.
 */
internal object DuraIsfWindow {

    /** Glucose (mg/dL) at and below which the ISF curve is not positive, and so dura_ISF nil: time there does not count. */
    const val FLOOR_MGDL = 150.0

    /** The window: the hour after which the study measured the effect. Readings older than this never count. */
    const val MAX_MINUTES = 60

    /** A reading this fraction or more above the running average of the newer ones ends the count: glucose has fallen since. */
    private const val FALL_BAND = 0.05

    /** A CGM gap longer than this ends the count. */
    private const val MAX_GAP_MINUTES = 13

    /**
     * [minutes] counted back from the newest reading, 0 to [MAX_MINUTES]; the [average] glucose over them (the newest reading
     * alone at 0); and the average of the ISF curve over the same readings, [curveAverage] (0 when nothing counts).
     */
    class Window(val minutes: Long, val average: Double, val curveAverage: Double)

    /** The window of [data], bucketed glucose newest first (AutosensDataStore.getBucketedDataTableCopy()), not empty. */
    fun of(data: List<InMemoryGlucoseValue>): Window {
        val now = data[0]
        if (now.recalculated <= FLOOR_MGDL) return Window(0L, now.recalculated, 0.0)                     // 1
        var sum = now.recalculated                                                                          // 2
        var curveSum = AutoIsfCurve.at(now.recalculated)
        var count = 1
        var average = sum
        var minutes = 0L
        for (i in 1 until data.size) {                                                                      // 3
            val then = data[i]
            if (then.value <= 39 || then.filledGap) continue                                                // a
            val minutesAgo = ((now.timestamp - then.timestamp) / 60_000.0).roundToLong()
            if (minutesAgo > MAX_MINUTES) break                                                             // b
            if (minutesAgo - minutes > MAX_GAP_MINUTES) break                                               // c
            if (then.recalculated <= FLOOR_MGDL) break                                                      // d
            if (then.recalculated >= average * (1 + FALL_BAND)) break                                       // e
            sum += then.recalculated                                                                        // f
            curveSum += AutoIsfCurve.at(then.recalculated)
            count++
            average = sum / count
            minutes = minutesAgo
        }
        return Window(minutes, average, curveSum / count)                                                   // 4
    }
}
