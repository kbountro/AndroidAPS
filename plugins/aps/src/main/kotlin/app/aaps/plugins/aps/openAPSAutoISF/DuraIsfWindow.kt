package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import kotlin.math.roundToLong

/**
 * How long glucose has been at its current level, and at what average, for dura_ISF (OpenAPSAutoISFPlugin):
 * GlucoseStatusAutoIsf.duraISFminutes and duraISFaverage.
 *
 * The count goes back from the newest bucketed reading over every reading above [FLOOR_MGDL] that is less than
 * [FALL_BAND] above the running average of the newer ones, and stops at the first that isn't, or at a CGM gap of more
 * than [MAX_GAP_MINUTES]. Readings below the running average count as long as they are above the floor, so the time
 * glucose spent rising to its level counts, and so does a dip within a plateau. A reading more than 5% above the
 * running average means glucose has fallen since, and ends the count: after a fall it starts again from the fall,
 * as when insulin breaks through ("breaking the wall"). Gap-filled and invalid readings are skipped.
 *
 * Readings more than 5% below the running average used to end the count too, so a rise counted only once glucose
 * levelled off near its top, and a short dip reset an hour-long plateau.
 */
internal object DuraIsfWindow {

    /** Glucose (mg/dL) at and below which OpenAPSAutoISFPlugin.interpolate(), and so dura_ISF, is nil: time there does not count. */
    const val FLOOR_MGDL = 150.0

    /** A reading this fraction or more above the running average of the newer ones ends the count: glucose has fallen since. */
    private const val FALL_BAND = 0.05

    /** A CGM gap longer than this ends the count. */
    private const val MAX_GAP_MINUTES = 13

    /** [minutes] counted back from the newest reading, and the [average] glucose over them (the newest reading alone at 0). */
    class Window(val minutes: Long, val average: Double)

    /** The window of [data], bucketed glucose newest first (AutosensDataStore.getBucketedDataTableCopy()), not empty. */
    fun of(data: List<InMemoryGlucoseValue>): Window {
        val now = data[0]
        var sum = now.recalculated
        var count = 1
        var average = sum
        var minutes = 0L
        if (now.recalculated <= FLOOR_MGDL) return Window(minutes, average)
        for (i in 1 until data.size) {
            val then = data[i]
            if (then.value <= 39 || then.filledGap) continue
            val minutesAgo = ((now.timestamp - then.timestamp) / 60_000.0).roundToLong()
            if (minutesAgo - minutes > MAX_GAP_MINUTES) break
            if (then.recalculated <= FLOOR_MGDL || then.recalculated >= average * (1 + FALL_BAND)) break
            sum += then.recalculated
            count++
            average = sum / count
            minutes = minutesAgo
        }
        return Window(minutes, average)
    }
}
