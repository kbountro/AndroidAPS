package app.aaps.core.interfaces.insulin

import app.aaps.core.interfaces.aps.IobTotal

/**
 * Stateful IOB/activity engine for insulin models whose doses cannot be evaluated
 * independently and summed - e.g. because absorption out of a shared subcutaneous
 * depot depends on how much insulin other recent doses have already deposited there.
 * The implementation owns its own chronological walk of the dose history, its model
 * state, and its result caching; callers only see aggregated totals for a requested
 * point in time.
 */
interface TsunamiIobEngine {

    /** True when the currently active [Insulin] model requires this engine. */
    val isActive: Boolean

    /** Drops all cached results. Call whenever the underlying treatment history changes. */
    fun resetCache()

    /**
     * Aggregated bolus/basal totals at [toTime] for the given autosens/exercise [sensitivityRatio].
     * [assumeZeroTempAfter] lets callers project a hypothetical zero temp basal from that point on.
     */
    fun resultAt(toTime: Long, sensitivityRatio: Double, assumeZeroTempAfter: Long = Long.MAX_VALUE): TsunamiIobResult

    fun calculateIobFromBolusToTime(toTime: Long): IobTotal
    fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal
    fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal
    fun calculateNetBasalAuto(toTime: Long, sensitivityRatio: Double): IobTotal
}

data class TsunamiIobResult(
    val bolusTotal: IobTotal,
    val extBolusTotal: IobTotal,
    val profileBaselineTotal: IobTotal,
    val basalNetTotal: IobTotal,
    val basalNetAutoTotal: IobTotal
)
