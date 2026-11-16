package app.aaps.core.objects.extensions

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.smoothing.Smoothing

/**
 * The one smoothed curve of a CGM that AutoISF treats as 1-minute data: every reading, smoothed by the
 * active smoothing plugin. Everything reads it: AutoISF directly, and autosens, COB, the deltas, the BG
 * on the home screen, the trend arrow and the watch through the 5-minute grid, which takes its values
 * from it ([sampleOnto]).
 *
 * At each data load only the newest [RESMOOTH_MS] of readings are smoothed. New readings hardly change
 * older values, so a value is final once its reading is [KEEP_AFTER_MS] old, and is kept from then on.
 * The older part of the window only gives the filter time to settle, and its values are not used.
 */
object OneMinuteCurve {

    const val RESMOOTH_MS = 2 * 60 * 60_000L
    const val KEEP_AFTER_MS = 90 * 60_000L

    /**
     * The curve for [readings] (newest first): the readings of the last [KEEP_AFTER_MS] as smoothed now,
     * older ones with their values from [previous], the curve of the last data load. When [previous] does
     * not hold those older readings, the same ones with the same values (the first load, a backfilled or
     * changed reading), all readings are smoothed once instead.
     */
    fun smooth(readings: List<GV>, previous: List<InMemoryGlucoseValue>?, smoothing: Smoothing): List<InMemoryGlucoseValue> {
        if (readings.isEmpty()) return emptyList()
        val newest = readings[0].timestamp
        val keepFrom = newest - KEEP_AFTER_MS
        val firstKept = readings.indexOfFirst { it.timestamp <= keepFrom }.let { if (it < 0) readings.size else it }
        val keptCount = readings.size - firstKept
        val previousFirstKept = previous?.indexOfFirst { it.timestamp <= keepFrom }?.let { if (it < 0) previous.size else it } ?: 0
        val canKeep = keptCount == 0 || previous != null && previous.size - previousFirstKept >= keptCount &&
            (0 until keptCount).all { k ->
                val reading = readings[firstKept + k]
                val kept = previous[previousFirstKept + k]
                kept.timestamp == reading.timestamp && kept.value == reading.value
            }
        if (!canKeep) return smoothing.smooth(readings.map { InMemoryGlucoseValue.fromGv(it) }.toMutableList())

        val windowEnd = readings.indexOfFirst { it.timestamp < newest - RESMOOTH_MS }.let { if (it < 0) readings.size else it }
        val window = smoothing.smooth(readings.subList(0, windowEnd).map { InMemoryGlucoseValue.fromGv(it) }.toMutableList())
        return List(readings.size) { i -> if (i < firstKept) window[i] else previous!![previousFirstKept + i - firstKept] }
    }

    /**
     * Gives every point of the 5-minute [grid] (newest first) its smoothed value from [curve] (newest
     * first): the curve's value at that time, a straight line between the two smoothed readings around
     * it, or the nearest end of the curve. The grid's own values, made from the readings, stay as they are.
     */
    fun sampleOnto(grid: List<InMemoryGlucoseValue>, curve: List<InMemoryGlucoseValue>) {
        if (curve.isEmpty()) return
        var k = 0
        for (point in grid) {
            val time = point.timestamp
            while (k + 1 < curve.size && curve[k + 1].timestamp >= time) k++
            val newer = curve[k]
            point.smoothed =
                if (newer.timestamp <= time || k + 1 == curve.size) newer.recalculated
                else {
                    val older = curve[k + 1]
                    older.recalculated + (time - older.timestamp).toDouble() / (newer.timestamp - older.timestamp) * (newer.recalculated - older.recalculated)
                }
        }
    }
}
