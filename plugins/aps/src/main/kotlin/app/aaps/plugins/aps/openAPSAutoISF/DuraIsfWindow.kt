package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.math.roundToLong

/**
 * How long glucose has been at its current level within the last hour, its average, and the ISF curve averaged over that
 * time: GlucoseStatusAutoIsf's duraISFminutes, duraISFaverage and duraISFcurveAverage, from which OpenAPSAutoISFPlugin
 * computes, when minutes >= 10 and average > 150 mg/dL,
 *
 *     dura_ISF = 1 + (minutes / 60) * dura_ISF_weight * curveAverage
 *
 * The ISF curve ([AutoIsfCurve]) is the change in insulin effectiveness measured after one hour at a steady glucose level,
 * and the study says nothing about earlier or later times. So every minute counts by its own curve value: a minute at a
 * glucose level adds 1/60 of that level's one-hour effect, (minutes / 60) * curveAverage being the curve's integral over the
 * counted time divided by 60. Higher glucose counts more, a short spike only for its own minutes. The effect builds up over
 * the first hour and then holds: the count never reaches back more than [MAX_MINUTES].
 *
 * The readings (GlucoseStatusCalculatorAutoIsf) are the smoothed CGM readings of the last hour on AutoISF's 1-minute raw path,
 * so minutes count to the minute, and the 5-minute bucketed table otherwise. Over them, r0 (the newest), r1, r2, ... (newest
 * first, smoothed values):
 *
 *  1. If r0 <= [FLOOR_MGDL]: minutes = 0, average = r0, curveAverage = 0. Done.
 *  2. Start with sum = r0, count = 1, average = r0, minutes = 0, area = 0, last = r0.
 *  3. Take each older reading ri in turn:
 *     a. skip it if it is gap-filled or invalid (39 mg/dL or less);
 *     b. stop if it is more than [MAX_MINUTES] old;
 *     c. stop if it is more than [MAX_GAP_MINUTES] older than the last reading counted (a CGM gap);
 *     d. stop if ri <= [FLOOR_MGDL]: glucose was not high yet;
 *     e. stop if ri >= 1.05 * average: ri is 5% or more above the readings since, so glucose has fallen since then;
 *     f. otherwise count it: sum += ri, count += 1, average = sum / count,
 *        area += (curve(last) + curve(ri)) / 2 * (minutes from ri to last), minutes = the age of ri, last = ri.
 *  4. The result is minutes (0 to 60, rounded to the minute), average, and curveAverage = area divided by the time from the
 *     last reading counted to r0 (curve(r0) when no older reading counted).
 *
 * Step e compares each reading with the average of all the newer ones counted, not with its neighbour: it finds the latest
 * fall within the hour however slowly it went (10 mg/dL in an hour stays within 5%; 15 mg/dL or more from 220 stops the count,
 * whether in 10 minutes or in 40), and also a fall that is over (glucose fell half an hour ago and has been flat since: the
 * count starts after the fall). The 5-minute delta, which says only whether glucose is falling now, cannot tell either.
 * Step d lets every reading above 150 mg/dL through, however far below the current level, so the time spent rising to the
 * level counts, and so does a dip within a plateau. In step f the area is the curve's integral over time by trapezoids, so
 * every minute weighs the same however dense or irregular the readings.
 *
 * For example, on the 5-minute table, rising 10 mg/dL every 5 minutes from 145 to 250: 250, 240, ..., 160 count and 145 stops
 * the count, giving 45 minutes and a curve average of 0.110, so dura_ISF = 1 + 0.75 * dura_ISF_weight * 0.110. An hour at 250
 * after a rise of any length gives 60 minutes and curve(250).
 *
 * The count used to stop also at a reading more than 5% below the average (a band of +/-5%), always used the 5-minute table and
 * had no time limit, so a rise counted only once glucose levelled off near its top, a short dip reset an hour-long plateau, and
 * the effect kept growing past the hour; and the curve was taken at the average glucose rather than averaged over the time.
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
     * [minutes] counted back from the newest reading, 0 to [MAX_MINUTES]; the [average] glucose of the readings counted (the
     * newest alone at 0); and the ISF curve averaged over the same time, [curveAverage] (0 when nothing counts).
     */
    class Window(val minutes: Long, val average: Double, val curveAverage: Double)

    /**
     * The window of [data], glucose readings newest first, not empty: the smoothed 1-minute readings of the last hour, or the
     * bucketed table (AutosensDataStore.getBucketedDataTableCopy()).
     */
    fun of(data: List<InMemoryGlucoseValue>): Window {
        val now = data[0]
        if (now.recalculated <= FLOOR_MGDL) return Window(0L, now.recalculated, 0.0)                     // 1
        var sum = now.recalculated                                                                          // 2
        var count = 1
        var average = sum
        var minutes = 0L
        var area = 0.0
        var lastTime = now.timestamp
        var lastCurve = AutoIsfCurve.at(now.recalculated)
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
            val curve = AutoIsfCurve.at(then.recalculated)
            area += (lastCurve + curve) / 2 * ((lastTime - then.timestamp) / 60_000.0)
            minutes = minutesAgo
            lastTime = then.timestamp
            lastCurve = curve
        }
        val span = (now.timestamp - lastTime) / 60_000.0                                                    // 4
        return Window(minutes, average, if (span > 0.0) area / span else AutoIsfCurve.at(now.recalculated))
    }
}
