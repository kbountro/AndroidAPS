package app.aaps.plugins.insulin.tsunami

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A bolus or SMB the way the pump delivers it, as the Traffic Jam model gives every bolus and SMB (micro-doses, as its
 * absorption fit replays the pump study's boluses): pulses of [pulseSize] U, the first at the bolus's timestamp and one
 * every pulseSize / bolusSpeed minutes after it, the last one smaller when the amount is not a whole number of pulses.
 *
 * The model takes each minute of pulses together, as a [Chunk] at the pulses' average time. A chunk enters the shared
 * depot at once, and its individual branch is that many lone pulses, each absorbing as a dose of the pulse size
 * (CrowdingGroup in TsunamiIobEngineImpl). At 0.05 U and 1.5 U/min the pump gives 30 pulses a minute, 2 s apart: an SMB
 * of up to 1.5 U is one chunk (0.5 U at 9 s), and 6 U four chunks of 30 pulses, at 29, 89, 149 and 209 s. Each minute's
 * pulses are given within it anyway, so taking them together moves none by more than a minute, and keeps a bolus to one
 * event a minute, like the basal.
 */
internal class BolusPulses(val pulseSize: Double, bolusSpeed: Double) {

    /**
     * One minute's pulses, [offsetMs] after the bolus's timestamp: [pulses] of the pulse size and, in the last chunk,
     * the smaller [extra] pulse (0 if there is none). [amount] is the chunk's insulin; the chunks' amounts add up to the
     * bolus.
     */
    class Chunk(val offsetMs: Long, val pulses: Int, val extra: Double, val amount: Double)

    /** Time from one pulse to the next, ms. */
    val intervalMs: Long = max(1L, (pulseSize / bolusSpeed * 60_000.0).roundToLong())

    /** The chunks of a bolus of [amount], in time order; none for an amount of 0 or less. */
    fun chunks(amount: Double): List<Chunk> {
        val count = pulseCount(amount) // pulses in all, the smaller one last
        if (count == 0) return emptyList()
        val extra = extraPulse(amount, wholePulses(amount))
        val result = ArrayList<Chunk>((lastMinute(count) + 1).toInt())
        var given = 0.0
        var first = 0
        while (first < count) {
            val last = min(count - 1, lastPulseOfMinute(first * intervalMs / 60_000L))
            val lastChunk = last == count - 1
            val hasExtra = lastChunk && extra > 0.0
            val pulses = last - first + 1 - if (hasExtra) 1 else 0
            // The last chunk takes the rest of the amount, so the chunks add up to the bolus exactly.
            val chunkAmount = if (lastChunk) amount - given else pulses * pulseSize
            given += chunkAmount
            result.add(Chunk((first + last) * intervalMs / 2, pulses, if (hasExtra) extra else 0.0, chunkAmount))
            first = last + 1
        }
        return result
    }

    /** When the last chunk of a bolus of [amount] comes, ms after its timestamp (0 for an amount of 0 or less). */
    fun lastChunkOffsetMs(amount: Double): Long {
        val count = pulseCount(amount)
        if (count == 0) return 0L
        val first = firstPulseOfMinute(lastMinute(count))
        return (first + count - 1) * intervalMs / 2
    }

    /** The insulin of a bolus of [amount] in chunks later than [elapsedMs] after its timestamp: not given yet. */
    fun notGivenYet(amount: Double, elapsedMs: Long): Double {
        if (elapsedMs >= lastChunkOffsetMs(amount)) return 0.0
        var rest = 0.0
        for (chunk in chunks(amount)) if (chunk.offsetMs > elapsedMs) rest += chunk.amount
        return rest
    }

    /** How many pulses give [amount]: its whole pulses, and the smaller last one if any. */
    private fun pulseCount(amount: Double): Int {
        if (amount <= 0.0) return 0
        val whole = wholePulses(amount)
        return whole + if (extraPulse(amount, whole) > 0.0) 1 else 0
    }

    /** Whole pulses in [amount], allowing for rounding (0.15 U is 3 pulses of 0.05 U, not 2.999...). */
    private fun wholePulses(amount: Double): Int = floor(amount / pulseSize + 1e-9).toInt()

    /** What [amount] holds beyond [whole] pulses, if more than rounding: the smaller last pulse. */
    private fun extraPulse(amount: Double, whole: Int): Double = (amount - whole * pulseSize).let { if (it > 1e-9) it else 0.0 }

    /** The minute after the timestamp in which the last of [count] pulses comes. */
    private fun lastMinute(count: Int): Long = (count - 1) * intervalMs / 60_000L

    private fun firstPulseOfMinute(minute: Long): Int = ((minute * 60_000L + intervalMs - 1) / intervalMs).toInt()

    private fun lastPulseOfMinute(minute: Long): Int = (((minute + 1) * 60_000L - 1) / intervalMs).toInt()
}
