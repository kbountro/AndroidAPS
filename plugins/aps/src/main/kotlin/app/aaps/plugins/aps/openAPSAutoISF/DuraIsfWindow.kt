package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.DuraIsfStart
import kotlin.math.roundToInt
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
 * first, smoothed values), with avg5(ri) the 5-minute average ending at ri: the average of ri and the readings less than 4.5
 * minutes older than it, gap-filled and invalid ones (39 mg/dL or less) left out - the five 1-minute readings ri to ri+4, or
 * ri alone on the table, whose next reading is 5 minutes older:
 *
 *  1. If r0 is gap-filled or invalid, or avg5(r0) <= [FLOOR_MGDL]: minutes = 0, average = avg5(r0) (r0 if invalid),
 *     curveAverage = 0, start = NOT_ABOVE_FLOOR. Done.
 *  2. Start with sum = r0, count = 1, average = r0, minutes = 0, area = 0, last = r0.
 *  3. Take each older reading ri in turn:
 *     a. skip it if it is gap-filled or invalid;
 *     b. stop if it is more than [MAX_MINUTES] old;
 *     c. stop if it is more than [MAX_GAP_MINUTES] older than the last reading counted (a CGM gap): start = GAP;
 *     d. stop if avg5(ri) <= [FLOOR_MGDL]: glucose was not high yet: start = RISE;
 *     e. stop if avg5(ri) >= 1.05 * average: glucose has fallen since: start = FALL;
 *     f. otherwise count it: sum += ri, count += 1, average = sum / count,
 *        area += (curve(last) + curve(ri)) / 2 * (minutes from ri to last), minutes = the age of ri, last = ri.
 *  4. The result is minutes (0 to 60, rounded to the minute), average, curveAverage = area divided by the time from the last
 *     reading counted to r0 (curve(r0) when no older reading counted), and start ([DuraIsfStart]). When step b ended the count
 *     or the readings ran out, start = HOUR if minutes >= 59 (the 1-minute readings end at the hour), else GAP if step b ended
 *     it (a hole before the hour's end), else OLDEST.
 *
 * Steps 1, d and e test the 5-minute average, which slides along with ri, rather than ri alone, so that a single noisy reading
 * does not end the count. On the table, where the average is ri itself, they test ri.
 *
 * Step e is upstream AutoISF's rule, the upper half of its +/-5% band around the running average: on the table it is that rule
 * exactly. On 1-minute readings the 5-minute average keeps a fall's end close to the table's (falling 36 mg/dL/h: 29 minutes,
 * the table 25, single readings 33), at the resolution of a minute. Because it compares with the average of everything newer
 * rather than with the reading before, it finds the latest fall within the hour however slowly it went (10 mg/dL in an hour
 * stays within 5%; 15 mg/dL or more from 220 stops the count), and also a fall that is over (glucose fell half an hour ago and
 * has been flat since: the count starts after the fall). The 5-minute delta, which says only whether glucose is falling now,
 * cannot tell either.
 *
 * Steps 1 and d keep the count above 150 mg/dL, where the curve is positive. Every reading whose 5-minute average is above 150
 * goes through, however far below the current level, so the time spent rising to the level counts, and so does a dip within a
 * plateau, even a minute or two at 150 or below (at its own curve value). On 1-minute readings a rise counts until its
 * 5-minute average reaches 150, about 2 minutes short of where the readings themselves do; those minutes, near 150, add almost
 * nothing to dura_ISF. In step f the area is the curve's integral over time by trapezoids, so every minute weighs the same
 * however dense or irregular the readings.
 *
 * For example, on the table, rising 10 mg/dL every 5 minutes from 145 to 250: 250, 240, ..., 160 count and 145 stops the
 * count, giving 45 minutes and a curve average of 0.110, so dura_ISF = 1 + 0.75 * dura_ISF_weight * 0.110. An hour at 250
 * after a rise of any length gives 60 minutes and curve(250).
 *
 * The count used to stop also at a reading more than 5% below the average (the lower half of upstream's band), always used the
 * 5-minute table and had no time limit, so a rise counted only once glucose levelled off near its top, a short dip reset an
 * hour-long plateau, and the effect kept growing past the hour; and the curve was taken at the average glucose rather than
 * averaged over the time.
 */
internal object DuraIsfWindow {

    /** Glucose (mg/dL) at and below which the ISF curve is not positive, and so dura_ISF nil: time there does not count. */
    const val FLOOR_MGDL = 150.0

    /** The window: the hour after which the study measured the effect. Readings older than this never count. */
    const val MAX_MINUTES = 60

    /** A 5-minute average this fraction or more above that of the readings counted before it ends the count. */
    private const val FALL_BAND = 0.05

    /** avg5 averages a reading with the valid readings less than this much older (ms): 5 minutes of 1-minute readings. */
    private const val SLIDING_WINDOW_MS = 270_000L

    /** A CGM gap longer than this ends the count. */
    private const val MAX_GAP_MINUTES = 13

    /**
     * [minutes] counted back from the newest reading, 0 to [MAX_MINUTES]; the [average] glucose of the readings counted (the
     * newest alone at 0; when glucose is not above [FLOOR_MGDL], the 5-minute average that showed it); the ISF curve averaged
     * over the same time, [curveAverage] (0 when nothing counts); and where the count starts, [start].
     */
    class Window(val minutes: Long, val average: Double, val curveAverage: Double, val start: DuraIsfStart)

    /**
     * The window of [data], glucose readings newest first, not empty: the smoothed 1-minute readings of the last hour, or the
     * bucketed table (AutosensDataStore.getBucketedDataTableCopy()).
     */
    fun of(data: List<InMemoryGlucoseValue>): Window {
        val now = data[0]
        val nowAverage5 = if (valid(now)) avg5(data, 0) else now.recalculated
        if (!valid(now) || nowAverage5 <= FLOOR_MGDL) return Window(0L, nowAverage5, 0.0, DuraIsfStart.NOT_ABOVE_FLOOR) // 1
        var sum = now.recalculated                                                                          // 2
        var count = 1
        var minutes = 0L
        var area = 0.0
        var lastTime = now.timestamp
        var lastCurve = AutoIsfCurve.at(now.recalculated)
        var start: DuraIsfStart? = null // where step c, d or e ended the count
        var pastTheHour = false         // whether step b did
        for (i in 1 until data.size) {                                                                      // 3
            val then = data[i]
            if (!valid(then)) continue                                                                      // a
            val minutesAgo = ((now.timestamp - then.timestamp) / 60_000.0).roundToLong()
            if (minutesAgo > MAX_MINUTES) {                                                                 // b
                pastTheHour = true
                break
            }
            if (minutesAgo - minutes > MAX_GAP_MINUTES) {                                                   // c
                start = DuraIsfStart.GAP
                break
            }
            val average5 = avg5(data, i)
            if (average5 <= FLOOR_MGDL) {                                                                   // d
                start = DuraIsfStart.RISE
                break
            }
            if (average5 >= sum / count * (1 + FALL_BAND)) {                                                // e
                start = DuraIsfStart.FALL
                break
            }
            sum += then.recalculated                                                                        // f
            count++
            val curve = AutoIsfCurve.at(then.recalculated)
            area += (lastCurve + curve) / 2 * ((lastTime - then.timestamp) / 60_000.0)
            minutes = minutesAgo
            lastTime = then.timestamp
            lastCurve = curve
        }
        val span = (now.timestamp - lastTime) / 60_000.0                                                    // 4
        val curveAverage = if (span > 0.0) area / span else AutoIsfCurve.at(now.recalculated)
        return Window(
            minutes, sum / count, curveAverage, start ?: when {
                minutes >= MAX_MINUTES - 1 -> DuraIsfStart.HOUR
                pastTheHour                -> DuraIsfStart.GAP
                else                       -> DuraIsfStart.OLDEST
            }
        )
    }

    /**
     * For AutoISF's log, from GlucoseStatusAutoIsf's duraISFminutes, duraISFaverage and duraISFstart: how long glucose has
     * been above [FLOOR_MGDL], at what average and since when, as in "glucose above 150 mg/dL for 45 min at an average of
     * 205 mg/dL (since it rose above 150 mg/dL)", or "glucose not above 150 mg/dL (5-minute average 146 mg/dL)" when nothing
     * counts.
     */
    fun describe(minutes: Double, average: Double, start: DuraIsfStart?): String {
        val floor = FLOOR_MGDL.roundToInt()
        val min = whole(minutes)
        val avg = whole(average)
        if (start == DuraIsfStart.NOT_ABOVE_FLOOR) return "glucose not above $floor mg/dL (5-minute average $avg mg/dL)"
        val since = when (start) {
            DuraIsfStart.RISE   -> " (since it rose above $floor mg/dL)"
            DuraIsfStart.FALL   -> " (since it last fell by ${(FALL_BAND * 100).roundToInt()}% or more)"
            DuraIsfStart.HOUR   -> " (the whole hour counted)"
            DuraIsfStart.GAP    -> " (since a gap in the readings)"
            DuraIsfStart.OLDEST -> " (since the oldest reading)"
            else                -> ""
        }
        return if (average > FLOOR_MGDL) "glucose above $floor mg/dL for $min min at an average of $avg mg/dL$since"
        else "glucose at an average of $avg mg/dL for $min min$since, not above $floor mg/dL"
    }

    /** [x] rounded to a whole number for the log; "?" for NaN, which roundToInt() would throw on. */
    private fun whole(x: Double): String = if (x.isNaN()) "?" else x.roundToInt().toString()

    private fun valid(reading: InMemoryGlucoseValue): Boolean = reading.value > 39 && !reading.filledGap

    /** avg5 of the steps: the average of data[i], which is valid, and the valid readings under [SLIDING_WINDOW_MS] older. */
    private fun avg5(data: List<InMemoryGlucoseValue>, i: Int): Double {
        val from = data[i].timestamp - SLIDING_WINDOW_MS
        var sum = 0.0
        var count = 0
        var j = i
        while (j < data.size && data[j].timestamp > from) {
            if (valid(data[j])) {
                sum += data[j].recalculated
                count++
            }
            j++
        }
        return sum / count
    }
}
