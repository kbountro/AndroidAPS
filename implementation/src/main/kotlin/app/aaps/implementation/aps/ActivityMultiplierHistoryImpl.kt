package app.aaps.implementation.aps

import app.aaps.core.interfaces.aps.ActivityMultiplierHistory
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * [ActivityMultiplierHistory] as stretches of consecutive loop runs that applied the same multiplier, kept in the
 * preferences so that it survives a restart.
 */
@Singleton
class ActivityMultiplierHistoryImpl @Inject constructor(
    private val preferences: Preferences
) : ActivityMultiplierHistory {

    companion object {

        /**
         * A loop run's multiplier holds until the next run. If none follows, it holds for 30 minutes, the length of the
         * temp basals the loop sets, and the profile basal applies again after that.
         */
        const val HOLD_MS = 30 * 60 * 1000L

        /** Stretches that ended longer ago are dropped: the insulin engine reads no further back. */
        const val KEEP_MS = 48 * 60 * 60 * 1000L

        private const val OPEN = Long.MAX_VALUE
    }

    /**
     * Loop runs from [start] to [lastRun] that all applied [multiplier], until [closedAt], the first later run that
     * applied another (OPEN while none has): it holds from [start] to [end].
     */
    private class Stretch(val start: Long, var lastRun: Long, var closedAt: Long, val multiplier: Double) {

        val end: Long get() = min(closedAt, lastRun + HOLD_MS)
    }

    /** What [multiplierAt] reads, without locking: each stretch's time span and multiplier, sorted, never overlapping. */
    private class Span(val start: Long, val end: Long, val multiplier: Double)

    private val lock = Any()
    private var stretches: MutableList<Stretch>? = null
    @Volatile private var spans: Array<Span>? = null

    override fun record(time: Long, multiplier: Double) {
        val applied = !multiplier.isNaN() && multiplier != 1.0
        synchronized(lock) {
            val list = loadedLocked()
            val last = list.lastOrNull()
            val open = last != null && last.closedAt == OPEN
            if (last != null && time < (if (open) last.lastRun else last.closedAt)) return // older than what is recorded
            if (applied) {
                val m = multiplier.coerceAtLeast(0.0)
                if (open && last.multiplier == m && time - last.lastRun < HOLD_MS) last.lastRun = time
                else {
                    if (open) last.closedAt = time
                    list.add(Stretch(time, time, OPEN, m))
                }
            } else {
                if (!open) return
                last.closedAt = time
            }
            list.removeAll { it.end < time - KEEP_MS }
            publishLocked(list)
            preferences.put(StringNonKey.ActivityMultiplierHistory, list.joinToString(";") {
                "${it.start},${it.lastRun},${if (it.closedAt == OPEN) -1 else it.closedAt},${it.multiplier}"
            })
        }
    }

    override fun multiplierAt(time: Long): Double {
        val current = spans ?: synchronized(lock) { loadedLocked(); spans!! }
        for (span in current) {
            if (time < span.start) break
            if (time < span.end) return span.multiplier
        }
        return 1.0
    }

    private fun loadedLocked(): MutableList<Stretch> {
        stretches?.let { return it }
        val list = ArrayList<Stretch>()
        for (entry in preferences.get(StringNonKey.ActivityMultiplierHistory).split(';')) {
            val f = entry.split(',')
            if (f.size != 4) continue
            val start = f[0].toLongOrNull() ?: continue
            val lastRun = f[1].toLongOrNull() ?: continue
            val closedAt = f[2].toLongOrNull()?.let { if (it < 0) OPEN else it } ?: continue
            val multiplier = f[3].toDoubleOrNull() ?: continue
            if (lastRun < start || closedAt < lastRun || !(multiplier >= 0.0) || multiplier == 1.0 || multiplier.isInfinite()) continue
            if (list.isNotEmpty() && start < list.last().end) continue // keep them sorted and apart
            list.add(Stretch(start, lastRun, closedAt, multiplier))
        }
        stretches = list
        publishLocked(list)
        return list
    }

    private fun publishLocked(list: List<Stretch>) {
        spans = Array(list.size) { Span(list[it].start, list[it].end, list[it].multiplier) }
    }
}
