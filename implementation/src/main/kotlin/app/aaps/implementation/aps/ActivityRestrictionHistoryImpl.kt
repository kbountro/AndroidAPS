package app.aaps.implementation.aps

import app.aaps.core.interfaces.aps.ActivityRestrictionHistory
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * [ActivityRestrictionHistory] as stretches of consecutive loop runs that applied the same activity ratio, kept in the
 * preferences so that a restart within the insulin's duration does not count the withheld basal as missing again.
 */
@Singleton
class ActivityRestrictionHistoryImpl @Inject constructor(
    private val preferences: Preferences
) : ActivityRestrictionHistory {

    companion object {

        /** The loop sets 30-min temp basals: a run's restriction holds that long unless a later run ends it first. */
        const val HOLD_MS = 30 * 60 * 1000L

        /** Restrictions that ended longer ago are dropped: the insulin engine reads no further back. */
        const val KEEP_MS = 48 * 60 * 60 * 1000L

        private const val OPEN = Long.MAX_VALUE
    }

    /**
     * Loop runs from [start] to [lastRun] that all applied [ratio], until [closedAt], the first later run that applied
     * another (OPEN while none has): the restriction holds from [start] to [end], or 30 min past the last run if the
     * loop stopped running.
     */
    private class Stretch(val start: Long, var lastRun: Long, var closedAt: Long, val ratio: Double) {

        val end: Long get() = min(closedAt, lastRun + HOLD_MS)
    }

    /** What [ratioAt] reads, without locking: each restriction's time span and ratio, sorted, never overlapping. */
    private class Span(val start: Long, val end: Long, val ratio: Double)

    private val lock = Any()
    private var stretches: MutableList<Stretch>? = null
    @Volatile private var spans: Array<Span>? = null

    override fun record(time: Long, ratio: Double) {
        val restricted = ratio < 1.0 // NaN and 1 or more: no restriction
        synchronized(lock) {
            val list = loadedLocked()
            val last = list.lastOrNull()
            val open = last != null && last.closedAt == OPEN
            if (last != null && time < (if (open) last.lastRun else last.closedAt)) return // older than what is recorded
            if (restricted) {
                val r = ratio.coerceAtLeast(0.0)
                if (open && last.ratio == r && time - last.lastRun < HOLD_MS) last.lastRun = time
                else {
                    if (open) last.closedAt = time
                    list.add(Stretch(time, time, OPEN, r))
                }
            } else {
                if (!open) return
                last.closedAt = time
            }
            list.removeAll { it.end < time - KEEP_MS }
            publishLocked(list)
            preferences.put(StringNonKey.ActivityRestrictionHistory, list.joinToString(";") {
                "${it.start},${it.lastRun},${if (it.closedAt == OPEN) -1 else it.closedAt},${it.ratio}"
            })
        }
    }

    override fun ratioAt(time: Long): Double {
        val current = spans ?: synchronized(lock) { loadedLocked(); spans!! }
        for (span in current) {
            if (time < span.start) break
            if (time < span.end) return span.ratio
        }
        return 1.0
    }

    private fun loadedLocked(): MutableList<Stretch> {
        stretches?.let { return it }
        val list = ArrayList<Stretch>()
        for (entry in preferences.get(StringNonKey.ActivityRestrictionHistory).split(';')) {
            val f = entry.split(',')
            if (f.size != 4) continue
            val start = f[0].toLongOrNull() ?: continue
            val lastRun = f[1].toLongOrNull() ?: continue
            val closedAt = f[2].toLongOrNull()?.let { if (it < 0) OPEN else it } ?: continue
            val ratio = f[3].toDoubleOrNull() ?: continue
            if (lastRun < start || closedAt < lastRun || !(ratio >= 0.0 && ratio < 1.0)) continue
            if (list.isNotEmpty() && start < list.last().end) continue // keep them sorted and apart
            list.add(Stretch(start, lastRun, closedAt, ratio))
        }
        stretches = list
        publishLocked(list)
        return list
    }

    private fun publishLocked(list: List<Stretch>) {
        spans = Array(list.size) { Span(list[it].start, list[it].end, list[it].ratio) }
    }
}
