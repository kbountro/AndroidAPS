package app.aaps.plugins.insulin.tsunami

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.insulin.TsunamiIobEngine
import app.aaps.core.interfaces.insulin.TsunamiIobResult
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.convertedToAbsolute
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Traffic Jam" IOB engine backing [Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM].
 *
 * Models a shared subcutaneous depot as a small, continuously-evolving pool of linear
 * (first-order) compartments - not one independent closed-form curve per dose. Every dose
 * is an impulse added directly into its category's own depot state; crowding is expressed
 * as the shared depot/transit rate depending on how much mass is currently still in the
 * depot, evaluated live rather than snapshotted per dose. Because every state is a genuine ODE
 * state (not an age-parametrized curve), a rate constant can change value at any instant
 * with no reinterpretation needed - the state simply keeps evolving from wherever it
 * currently is. This is what the earlier per-dose-curve design could not do: that design
 * evaluated `IOB(t) = exp(-t^p/tp)` from each curve's own elapsed age, so an amount-driven
 * change to `tp` had no continuous way to apply mid-flight, and had to be patched with a
 * "time warp" (retroactively inventing a new elapsed age to keep IOB continuous) that
 * unavoidably forced a discontinuous drop in reported activity every time the pool grew.
 * Here activity is KE * A with a constant KE, and a dose adds only to the depot, so activity
 * never jumps (see [PdModel]).
 *
 * See [PdModel] for the full derivation and the calibration that replaced it.
 */
@Singleton
class TsunamiIobEngineImpl @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin
) : TsunamiIobEngine {

    companion object {
        private const val DIA_HORIZON_MINUTES = 480.0 // 8h
        private const val DIA_HORIZON_MS = 8L * 60 * 60 * 1000L

        // Deep-history scrolling has no required batch size (forward horizon only affects how
        // much gets pre-cached for later queries, never the correctness of the current one) - tied
        // to a multiple of the DIA horizon so it scales automatically if that ever changes.
        private const val DEEP_HISTORY_BATCH_MS = 2 * DIA_HORIZON_MS
    }

    private data class ResultCacheKey(val time: Long, val sensitivityRatio: Double, val assumeZeroTempAfter: Long)

    private val engineLock = Any()
    private var resultCache = HashMap<ResultCacheKey, TsunamiIobResult>()
    private var cacheVersion = 0

    override val isActive: Boolean
        get() = activePlugin.activeInsulin.id.value == Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM.value

    override fun resetCache() {
        synchronized(engineLock) {
            resultCache.clear()
            cacheVersion++
        }
    }

    override fun calculateIobFromBolusToTime(toTime: Long): IobTotal {
        val res = resultAt(toTime, 1.0)
        val pumpInterface = activePlugin.activePump
        return if (!pumpInterface.isFakingTempsByExtendedBoluses) {
            IobTotal.combine(res.bolusTotal, res.extBolusTotal)
        } else {
            res.bolusTotal
        }
    }

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal =
        resultAt(toTime, 1.0).profileBaselineTotal

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal =
        resultAt(toTime, 1.0).basalNetTotal

    override fun calculateNetBasalAuto(toTime: Long, sensitivityRatio: Double): IobTotal =
        resultAt(toTime, sensitivityRatio).basalNetAutoTotal

    override fun resultAt(toTime: Long, sensitivityRatio: Double, assumeZeroTempAfter: Long): TsunamiIobResult {
        val key = ResultCacheKey(toTime, sensitivityRatio, assumeZeroTempAfter)

        synchronized(engineLock) {
            resultCache[key]?.let { return it.copy() }
        }

        val now = dateUtil.now()
        // If requested time is older than 24 hours, don't simulate all the way to present to save DB load
        val isDeepHistory = toTime < now - (24 * 60 * 60 * 1000L)

        val startTime = toTime - DIA_HORIZON_MS // Always guarantee a full DIA horizon of physical warmup
        val horizon = if (isDeepHistory) {
            toTime + DEEP_HISTORY_BATCH_MS // Batch a chunk forward for scrolling
        } else {
            max(toTime, now + DIA_HORIZON_MS) // Batch all the way through the future projection
        }

        val currentVersion = synchronized(engineLock) { cacheVersion }
        // Pass the exactly requested toTime so the solver guarantees it is generated
        val resultsMap = simulateDepotHistory(toTime, startTime, horizon, sensitivityRatio, assumeZeroTempAfter)

        synchronized(engineLock) {
            if (currentVersion == cacheVersion) {
                for ((t, res) in resultsMap) {
                    resultCache[ResultCacheKey(t, sensitivityRatio, assumeZeroTempAfter)] = res
                }
            }
        }

        return synchronized(engineLock) {
            resultCache[key]?.copy() ?: resultsMap[toTime]?.copy() ?: resultsMap.values.last().copy()
        }
    }

    // =========================================================================
    // THE TRAFFIC JAM DEPOT SIMULATION
    // Five attribution categories (bolus, extended bolus, absolute temp-basal delivery,
    // profile basal, autosens-adjusted profile basal) each carry their own persistent
    // 3-compartment pool (depot -> transit -> active, see [PdModel]) so IOB/activity can
    // still be broken down the same way AAPS expects. Bolus/extBolus share one crowding
    // rate, basalAbs has its own, and profileBasal/autoProfileBasal share one driven by the
    // plain profile pool - see [CrowdingGroup] for why basal crowds only itself.
    // Every dose is an impulse added straight into its category's depot mass; nothing
    // about any other category's or any earlier dose's state is ever rewritten - only each
    // group's absorption rate constant is updated, once a minute from its live depot mass,
    // and every pool simply keeps integrating forward from its current state under that
    // rate, in closed form.
    //
    // SITE CHANGES: a cannula/site change (TE.Type.CANNULA_CHANGE - also logged by patch
    // pumps on pod activation) starts a brand new set of these five pools ([SiteState]),
    // keyed by a monotonically increasing generation number. Insulin delivered before the
    // change keeps crowding only with other insulin from that same old site as it finishes
    // absorbing; insulin delivered at or after the change crowds only the new site's own
    // pools. The two never interact - no mass or rate crosses between generations - and
    // every generation keeps decaying independently for as long as it still holds any
    // mass, however many site changes occur in a window. Reported IOB/activity per
    // category is simply the sum across every generation still alive.
    // =========================================================================
    private fun simulateDepotHistory(
        requestedTime: Long,
        startTime: Long,
        horizonTime: Long,
        sensitivityRatio: Double,
        assumeZeroTempAfter: Long = Long.MAX_VALUE
    ): Map<Long, TsunamiIobResult> {
        val now = dateUtil.now()

        val boluses = persistenceLayer.getBolusesFromTime(startTime, true).blockingGet()
        val pumpInterface = activePlugin.activePump
        val isFakingTemps = pumpInterface.isFakingTempsByExtendedBoluses

        val timeline = buildDoseEventTimeline(startTime, horizonTime, sensitivityRatio, boluses, isFakingTemps, assumeZeroTempAfter, now)

        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        // Per-patient absorption speed knob (DoubleKey.InsulinTrafficJamSpeedMultiplier): only the
        // crowded depot/transit rate scales, never ke - see that key's doc comment for why. Read
        // once per simulated window, like the fitted constants are effectively constant - a
        // mid-window change to this preference is not retroactively applied to the past, same as
        // those.
        val speedFactor = preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)

        // Site-change boundaries within the simulated window: each one starts a fresh
        // [SiteState] generation. A dose's generation is how many boundaries fall at or
        // before its own timestamp, so doses before the first boundary (or when there is
        // none at all) all land in generation 0 - identical to the single-pool behavior
        // this replaces. Boundaries outside the window never matter: anything that old is
        // already past the DIA horizon, and anything that new hasn't happened yet.
        val siteChangeBoundaries = persistenceLayer.getTherapyEventDataFromTime(startTime, TE.Type.CANNULA_CHANGE, true)
            .map { it.timestamp }
            .filter { it <= horizonTime }

        // One persistent set of pools (and crowding rates) per site generation - never one
        // per dose, and never shared across generations.
        class SiteState {

            val pools = Array(Category.entries.size) { Pool() }

            // One shared depot/transit rate per crowding group, each driven by that group's own
            // live depot mass (see [CrowdingGroup]). Empty pools until the first dose, so the
            // starting value never touches any mass.
            val k1 = DoubleArray(CrowdingGroup.entries.size) { PdModel.rate(speedFactor, 0.0) }

            fun rateFor(cat: Category): Double = k1[cat.group.ordinal]

            fun refreshRates() {
                for (g in CrowdingGroup.entries) k1[g.ordinal] = PdModel.rate(speedFactor, groupDepotMass(pools, g))
            }
        }

        val sites = HashMap<Int, SiteState>()
        fun siteFor(gen: Int): SiteState = sites.getOrPut(gen) { SiteState() }
        var lastTime = startTime

        // Sorted once so the snooze/lastBolusTime bookkeeping below can sweep forward with
        // monotonically advancing indices instead of rescanning every bolus for every snapshot.
        val sortedBoluses = boluses.filter { it.isValid }.sortedBy { it.timestamp }
        var bolusValidIdx = 0 // boluses folded into lastBolusTimeSeen so far (timestamp <= current tTarget)
        var bolusFrontIdx = 0 // boluses before this index have permanently expired snooze windows
        var lastBolusTimeSeen = 0L

        // Evaluates every category's IOB/activity at [tTarget] by peeking [dtPeek] minutes ahead of the
        // pools' last-committed state - without mutating it, since several snapshot times can fall
        // between two consecutive dose events and each must be evaluated from the same starting state.
        fun evaluatePools(dtPeek: Double, tTarget: Long): TsunamiIobResult {
            while (bolusValidIdx < sortedBoluses.size && sortedBoluses[bolusValidIdx].timestamp <= tTarget) {
                val b = sortedBoluses[bolusValidIdx]
                if (b.amount > 0 && b.timestamp > lastBolusTimeSeen) lastBolusTimeSeen = b.timestamp
                bolusValidIdx++
            }
            // tSnoozeElapsed grows monotonically with tTarget for a fixed bolus (divisor > 0), so once
            // a bolus's window has expired it can never reopen - safe to drop it from the front for good.
            while (bolusFrontIdx < bolusValidIdx) {
                val b = sortedBoluses[bolusFrontIdx]
                val tSnoozeElapsed = ((tTarget - b.timestamp) * divisor) / 60000.0
                if (tSnoozeElapsed >= DIA_HORIZON_MINUTES) bolusFrontIdx++ else break
            }

            var bSnooze = 0.0
            for (idx in bolusFrontIdx until bolusValidIdx) {
                val b = sortedBoluses[idx]
                if (b.type != BS.Type.SMB) {
                    // Age used by this bolus's own isolated curve is divisor times the real elapsed
                    // time (matching the stock calculator's "evaluate the curve further in the future"
                    // technique - see IobCobCalculatorPlugin.calculateIobFromBolusToTime), NOT elapsed
                    // time since some derived timestamp - using the latter silently zeroed this out
                    // for every divisor value in the enforced [1, 10] range.
                    val tSnoozeElapsed = ((tTarget - b.timestamp) * divisor) / 60000.0
                    if (tSnoozeElapsed in 0.0..<DIA_HORIZON_MINUTES) {
                        // Snooze reflects this bolus's own isolated pool, not shared crowding.
                        bSnooze += b.amount * PdModel.isolatedIobFraction(tSnoozeElapsed, b.amount, speedFactor)
                    }
                }
            }

            // Sums every still-live site generation's own peeked pool - crowding never
            // crosses generations, but the reported total is their combined IOB/activity.
            fun peekSum(cat: Category): Pool {
                var sumD = 0.0; var sumX = 0.0; var sumA = 0.0
                for (site in sites.values) {
                    val k1 = site.rateFor(cat)
                    val p = site.pools[cat.ordinal].propagated(k1, k1, PdModel.KE, dtPeek)
                    sumD += p.D; sumX += p.X; sumA += p.A
                }
                return Pool(sumD, sumX, sumA)
            }

            val bolusP = peekSum(Category.BOLUS)
            val extBolusP = peekSum(Category.EXT_BOLUS)
            val basalAbsP = peekSum(Category.BASAL_ABS)
            val profileP = peekSum(Category.PROFILE_BASAL)
            val autoProfileP = peekSum(Category.AUTO_PROFILE_BASAL)

            val bnIob = basalAbsP.iob() - profileP.iob()
            val bnAct = basalAbsP.activity() - profileP.activity()
            val bnaIob = basalAbsP.iob() - autoProfileP.iob()
            val bnaAct = basalAbsP.activity() - autoProfileP.activity()

            return TsunamiIobResult(
                bolusTotal = mapToTotal(bolusP.iob(), bolusP.activity(), "bolus", lastBolusTimeSeen, bSnooze),
                extBolusTotal = mapToTotal(extBolusP.iob(), extBolusP.activity(), "extBolus"),
                profileBaselineTotal = mapToTotal(profileP.iob(), profileP.activity(), "profile"),
                basalNetTotal = mapToTotal(bnIob, bnAct, "netBasal"),
                basalNetAutoTotal = mapToTotal(bnaIob, bnaAct, "netBasal")
            )
        }

        val resultsMap = HashMap<Long, TsunamiIobResult>()

        // Use a Set to automatically prevent duplicates, ensuring a perfect chronological map
        val targetTimes = mutableSetOf<Long>()
        var evalT = (startTime / 300000L) * 300000L
        while (evalT <= horizonTime) {
            if (evalT >= startTime) targetTimes.add(evalT)
            evalT += 5 * 60 * 1000L
        }
        // Force the engine to snapshot the EXACT millisecond requested by AAPS
        targetTimes.add(requestedTime)
        targetTimes.add(horizonTime)

        val sortedTargets = targetTimes.sorted()
        var nextTargetIdx = 0

        for (dose in timeline) {
            // STRICTLY LESS THAN: Take snapshots AFTER the current dose is absorbed, but before the next!
            while (nextTargetIdx < sortedTargets.size && sortedTargets[nextTargetIdx] < dose.timestamp) {
                val tTarget = sortedTargets[nextTargetIdx]
                val dtPeek = (tTarget - lastTime) / 60000.0
                resultsMap[tTarget] = evaluatePools(dtPeek, tTarget)
                nextTargetIdx++
            }

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                // Every live generation decays continuously in real time regardless of
                // whether THIS dose belongs to it - an old site's leftover insulin keeps
                // absorbing on its own schedule even after the new site starts filling up.
                for (site in sites.values) {
                    for (cat in Category.entries) {
                        val pool = site.pools[cat.ordinal]
                        val k1 = site.rateFor(cat)
                        pool.propagateInPlace(k1, k1, PdModel.KE, dtMins)
                    }
                }
            }
            lastTime = dose.timestamp

            val site = siteFor(siteGenerationFor(dose.timestamp, siteChangeBoundaries))

            if (dose.bolusAmt > 0.0) site.pools[Category.BOLUS.ordinal].D += dose.bolusAmt
            if (dose.extBolusAmt > 0.0) site.pools[Category.EXT_BOLUS.ordinal].D += dose.extBolusAmt
            if (dose.basalAbsAmt > 0.0) site.pools[Category.BASAL_ABS.ordinal].D += dose.basalAbsAmt
            if (dose.profileBasalAmt > 0.0) site.pools[Category.PROFILE_BASAL.ordinal].D += dose.profileBasalAmt
            if (dose.autoProfileBasalAmt > 0.0) site.pools[Category.AUTO_PROFILE_BASAL.ordinal].D += dose.autoProfileBasalAmt

            // Every rate follows its group's live depot mass, so it is re-evaluated at every event,
            // whether or not this event injected anything into that group: buildDoseEventTimeline
            // emits one event per minute wherever a profile exists (zero temps and the zero-temp
            // projection included), so no rate is then held for more than a minute while its depot
            // empties. Where no profile exists, only boluses create events and the rates are held
            // between them. Every live generation too, since an old site's depot keeps emptying
            // after dosing has moved to the new one.
            for (s in sites.values) s.refreshRates()
        }

        // Process remaining snapshots at the very end of the timeline
        while (nextTargetIdx < sortedTargets.size) {
            val tTarget = sortedTargets[nextTargetIdx]
            val dtPeek = (tTarget - lastTime) / 60000.0
            resultsMap[tTarget] = evaluatePools(dtPeek, tTarget)
            nextTargetIdx++
        }

        return resultsMap
    }

    /**
     * How many of [boundaries] (sorted ascending site-change timestamps) fall at or before
     * [timestamp] - the generation number that timestamp's dose belongs to. A change logged
     * exactly at a dose's own timestamp already counts: that dose is the first one into the
     * new site. Binary search since [boundaries] is walked once per dose event.
     */
    private fun siteGenerationFor(timestamp: Long, boundaries: List<Long>): Int {
        var lo = 0
        var hi = boundaries.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (boundaries[mid] <= timestamp) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Mass still sitting in one site's depot for one crowding [group] (see [CrowdingGroup] for
     * which pools crowd which). Deliberately D only, never D+X: X is mass that has already left
     * the depot (past the crowding-limited step, in transit toward the active/eliminated stage),
     * so it no longer competes for SC depot space and must not inflate the rate that governs the
     * depot's own outflow. Getting this wrong compounds under sustained dosing - X never fully
     * clears between frequent small doses (real SMB/TBR cadence), so including it keeps dragging
     * k1 down further the longer a session runs. [pools] is always one site generation's own
     * pools, never mixed across sites. Floored inside [PdModel.rate].
     */
    private fun groupDepotMass(pools: Array<Pool>, group: CrowdingGroup): Double {
        var mass = 0.0
        for (cat in Category.entries) {
            if (cat.group == group && cat.drivesGroupRate) {
                mass += pools[cat.ordinal].D
            }
        }
        return mass
    }

    private fun mapToTotal(iob: Double, act: Double, type: String, lastBolusTime: Long = 0L, bSnooze: Double = 0.0): IobTotal {
        val t = IobTotal(0)
        t.iob = iob; t.activity = act
        when (type) {
            "netBasal" -> {
                t.basaliob = iob; t.netbasalinsulin = iob; t.hightempinsulin = max(0.0, iob)
            }

            "extBolus" -> {
                t.extendedBolusInsulin = iob
            }

            "profile"  -> {
                t.basaliob = iob
            }

            "bolus"    -> {
                t.lastBolusTime = lastBolusTime
                t.bolussnooze = bSnooze
            }
        }
        return t
    }

    private fun buildDoseEventTimeline(
        fromTime: Long,
        toTime: Long,
        sensitivityRatio: Double,
        boluses: List<BS>,
        isFakingTemps: Boolean,
        assumeZeroTempAfter: Long,
        currentNow: Long
    ): List<DoseEvent> {

        val capacity = ((toTime - fromTime) / 60000L).toInt() + 10
        val timelineMap = HashMap<Long, DoseEvent>(capacity)

        fun getOrCreate(t: Long): DoseEvent {
            var dose = timelineMap[t]
            if (dose == null) {
                dose = DoseEvent(t)
                timelineMap[t] = dose
            }
            return dose
        }

        for (b in boluses) {
            if (b.isValid && b.timestamp in fromTime..toTime) {
                getOrCreate(b.timestamp).bolusAmt += b.amount
            }
        }

        val alignedFromTime = (fromTime / 60000L) * 60000L
        var bTime = alignedFromTime

        // Fetch Temp Basals and Extended Boluses ONCE into RAM, sorted chronologically so the
        // per-minute loop below can sweep forward through them with monotonically advancing
        // indices instead of rescanning the full list every single minute.
        val sortedTbs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(alignedFromTime, toTime, true).sortedBy { it.timestamp }
        val sortedEbs = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(alignedFromTime, toTime, true).sortedBy { it.timestamp }

        // Everything before these indices has definitely ended relative to the current bTime,
        // and can never become relevant again since bTime only moves forward and each record's
        // own start/end window never changes - so each record is only ever inspected while it's
        // still (or not yet) possibly active, not on every unrelated minute.
        var tbFromIdx = 0
        var ebFromIdx = 0

        while (bTime < toTime) {
            val nextTime = min(bTime + 60000L, toTime)
            val durationMins = (nextTime - bTime) / 60000.0

            if (durationMins > 0) {
                val profile = profileFunction.getProfile(bTime)
                if (profile != null) {
                    val profileRate = profile.getBasal(bTime)
                    var absoluteRate = profileRate

                    // 1. Resolve Active Temp Basal in RAM (Replaces processedTbrEbData to support Deep History)
                    while (tbFromIdx < sortedTbs.size && min(sortedTbs[tbFromIdx].timestamp + sortedTbs[tbFromIdx].duration, currentNow) <= bTime) {
                        tbFromIdx++
                    }
                    var activeTb: TB? = null
                    for (idx in tbFromIdx until sortedTbs.size) {
                        val tb = sortedTbs[idx]
                        if (tb.timestamp > bTime) break // sorted ascending - none later can have started yet either
                        val effectiveEnd = min(tb.timestamp + tb.duration, currentNow)
                        if (bTime < effectiveEnd) {
                            if (activeTb == null || tb.timestamp > activeTb.timestamp) {
                                activeTb = tb
                            }
                        }
                    }
                    if (activeTb != null) {
                        absoluteRate = activeTb.convertedToAbsolute(bTime, profile)
                    }

                    // 2. Resolve Extended Boluses
                    while (ebFromIdx < sortedEbs.size && min(sortedEbs[ebFromIdx].timestamp + sortedEbs[ebFromIdx].duration, currentNow) <= bTime) {
                        ebFromIdx++
                    }
                    var extBolusChunk = 0.0
                    for (idx in ebFromIdx until sortedEbs.size) {
                        val e = sortedEbs[idx]
                        if (e.timestamp >= nextTime) break // sorted ascending - none later can overlap this minute either
                        val effectiveEbEnd = min(e.timestamp + e.duration, currentNow)
                        val overlapStart = max(bTime, e.timestamp)
                        val overlapEnd = min(nextTime, effectiveEbEnd)
                        if (overlapEnd > overlapStart) {
                            val overlapMins = (overlapEnd - overlapStart) / 60000.0
                            val ratePerMin = e.amount / (e.duration / 60000.0)
                            extBolusChunk += ratePerMin * overlapMins
                        }
                    }

                    // 3. Apply Zero-Temp Projection Cutoffs
                    if (bTime >= assumeZeroTempAfter) {
                        absoluteRate = 0.0
                        if (isFakingTemps) {
                            extBolusChunk = 0.0 // Also zero-out faked temps in the future
                        }
                    }

                    val absAmt = absoluteRate * (durationMins / 60.0)
                    val profAmt = profileRate * (durationMins / 60.0)
                    val autoProfAmt = (profileRate * sensitivityRatio) * (durationMins / 60.0)

                    val dose = getOrCreate(nextTime)

                    // 4. Map Faked Temps correctly without double-counting
                    if (isFakingTemps) {
                        dose.basalAbsAmt += absAmt + extBolusChunk
                        dose.extBolusAmt += 0.0
                    } else {
                        dose.basalAbsAmt += absAmt
                        dose.extBolusAmt += extBolusChunk
                    }

                    dose.profileBasalAmt += profAmt
                    dose.autoProfileBasalAmt += autoProfAmt
                }
            }
            bTime = nextTime
        }

        return timelineMap.values.sortedBy { it.timestamp }
    }

    /**
     * Which pools crowd each other. Each group has its own shared depot/transit rate, driven by
     * the live depot mass of the group's own pools only:
     *  - [BOLUS]: boluses, SMBs and extended boluses crowd each other, so an SMB cascade jams.
     *  - [BASAL]: delivered basal crowds only itself, so a bolus no longer slows the delivered
     *    basal pool and none of its effect is booked to net basal IOB. Counted into the bolus
     *    group instead, the basal depot mass slows every SMB on top of it, and no constants then
     *    fit both the EPAR curves (no basal infused) and TsunamiMods' small-dose curves given on
     *    a basal rate: see [PdModel] (found with earlier constants; not tested with the current
     *    ones). Left out of crowding altogether, basal would sit at the rate of an empty depot and
     *    reach 50% / 90% of a new steady state after 11 / 25 min (crowding only itself: 81 / 196
     *    min at 0.5 U/h).
     *  - [PROFILE]: the theoretical profile baseline, driven by the plain profile pool alone.
     *    autoProfileBasal (which scales with sensitivityRatio) must never feed back into it, or the
     *    "ground truth" profile curve would shift with autosens instead of reflecting only the
     *    programmed rate. It follows the same rule as [BASAL], so with no temp basal running the
     *    delivered and profile basal pools evolve identically and net basal IOB stays zero.
     */
    private enum class CrowdingGroup { BOLUS, BASAL, PROFILE }

    private enum class Category(val group: CrowdingGroup, val drivesGroupRate: Boolean = true) {
        BOLUS(CrowdingGroup.BOLUS), EXT_BOLUS(CrowdingGroup.BOLUS), BASAL_ABS(CrowdingGroup.BASAL),
        PROFILE_BASAL(CrowdingGroup.PROFILE), AUTO_PROFILE_BASAL(CrowdingGroup.PROFILE, drivesGroupRate = false)
    }

    /** One category's live state: mass still in the SC depot (D), in transit (X), or active (A). */
    private class Pool(var D: Double = 0.0, var X: Double = 0.0, var A: Double = 0.0) {

        fun iob(): Double = D + X + A
        fun activity(): Double = PdModel.KE * A

        fun propagated(k1: Double, k2: Double, ke: Double, dtMinutes: Double): Pool {
            if (dtMinutes <= 0.0 || (D == 0.0 && X == 0.0 && A == 0.0)) return Pool(D, X, A)
            val (nd, nx, na) = PdModel.propagate(D, X, A, k1, k2, ke, dtMinutes)
            return Pool(nd, nx, na)
        }

        fun propagateInPlace(k1: Double, k2: Double, ke: Double, dtMinutes: Double) {
            if (dtMinutes <= 0.0 || (D == 0.0 && X == 0.0 && A == 0.0)) return
            val (nd, nx, na) = PdModel.propagate(D, X, A, k1, k2, ke, dtMinutes)
            D = nd; X = nx; A = na
        }
    }

    private data class DoseEvent(
        val timestamp: Long,
        var bolusAmt: Double = 0.0,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var profileBasalAmt: Double = 0.0,
        var autoProfileBasalAmt: Double = 0.0
    )

    /**
     * Three linked first-order compartments: D (SC depot) -> X (transit) -> A (active, reported as
     * activity = KE*A). IOB(t) = D(t) + X(t) + A(t): mass not yet through the final step.
     *
     * Depot and transit share ONE crowding-dependent rate, K(M) = K1_B0 * M^K1_B1 ([rate]), where M
     * is the live depot mass of the dose's crowding group (D only - see groupDepotMass and
     * CrowdingGroup). It is re-evaluated at every event (every whole minute and every bolus
     * timestamp, right after that event's doses enter D) and held until the next, and each
     * interval is solved exactly. [KE] is fixed and never touched by crowding, so activity = KE*A
     * stays exactly continuous through every dose event: a new dose slows mass still in the depot
     * or transit, never mass already in the active stage. With K1_B1 < 0 the rate rises as a
     * dose's own depot empties, so absorption speeds up late in each dose: 0.0298 /min at 0.1U,
     * 0.0178 at 1U, 0.0116 at 7U, 0.0084 at 30U, 0.385 /min (half-life 1.80 min) at an empty depot
     * (the 1e-6U floor). That gives the flatter, more symmetric top of the EPAR curves, which no
     * chain of stages with rates held fixed per dose can produce (its skewness for a given CV is at
     * least an Erlang curve's). Each SMB of a cascade lowers the rate at once, and with KE's
     * half-life of 5.3 min the active stage passes that on within minutes: in the 12-SMB cascade
     * below, activity falls by up to 0.7% of its peak from one minute to the next while the SMBs
     * are being given, without jumping.
     *
     * Why the rate is live: earlier constants were fitted with the rate held at K(dose) for the
     * whole dose, while this engine re-evaluates it from the live depot mass. Run the way the
     * engine runs them with no basal, those constants scored EPAR activity R^2 0.654 / 0.533 /
     * 0.449 at 7 / 15 / 30U. With the basal depot mass counted in the same crowding mass as the
     * SMBs, no constants fit both the EPAR curves and TsunamiMods' small-dose curves given on a
     * basal rate (KE ran to the upper end of any search range, and the EPAR objective defined
     * below stayed near 0.26 at 0.5 U/h). Hence the separate crowding groups. That was found with
     * constants fitted to EPAR plus TsunamiMods' small-dose curves; the current constants have not
     * been tested with basal in the bolus group.
     *
     * Fit, in two steps, to the digitized EPAR GIR clamp curves (Lyumjev/LY900014, 7/15/30U, points
     * up to 480 min, no basal insulin infused). Both steps use curves truncated at 480 min: every
     * curve, target and model alike, is cut at 480 min and normalized over 0-480 min. Activity is
     * divided by its integral over 0-480 min, and IOB is 1 minus the cumulative activity divided by
     * its value at 480 min (1 at 0, 0 at 480 min); for the model's isolated single-dose activity
     * a(t) and IOB fraction S(t), that is a(t) / (1 - S(480)) and (S(t) - S(480)) / (1 - S(480)).
     * Step 1: TsunamiMods' Weibull form fitted to each EPAR dose alone (activity and IOB together,
     * each curve's squared errors divided by its total sum of squares) gives each dose's activity
     * peak: 103.34 / 127.31 / 138.45 min, height 5.4654 / 4.5913 / 3.9392e-3 /min at 7 / 15 / 30U.
     * Step 2: K1_B0, K1_B1 and KE fitted, with the rate re-evaluated every minute as above, to those
     * peaks: the sum over 7 / 15 / 30U of ln^2(model peak time / step-1 peak time) + ln^2(model
     * peak height / step-1 peak height), the model's peak taken on its cut, normalized activity
     * (sampled at whole minutes, parabola through the 3 samples around the maximum). The model's
     * peaks: 104.9 / 122.6 / 141.7 min, heights within 0.4% of step 1's. Scored on the EPAR curves
     * themselves (model sampled at whole minutes, right after each re-evaluation, interpolated
     * linearly to the EPAR sample times), the sum over the 6 curves of (1 - R^2) is 0.0672 (the
     * previous constants, fitted directly to that sum with four constants: 0.0441; TsunamiMods'
     * Weibull form fitted directly to it: 0.0582, a0 69.944 min, a1 0.2177, p 1.9017, within 2.0%
     * of TsunamiMods' constants). R^2 activity / IOB: 0.982 / 0.997, 0.988 / 0.999, 0.969 / 0.998
     * at 7 / 15 / 30U. No curve below 7U is in the fit, so the small-dose timing below is the
     * fitted law's extrapolation. Since a dose's rate does not depend on basal, each dose's own
     * curve is the same on any basal rate. Isolated dose at speed 1.0, minutes until 1 - IOB/dose
     * reaches 0.5 / 0.9 (whole curve, not cut at 480 min): 0.1U 55 / 97 (TsunamiMods 52 / 97),
     * 0.3U 68 / 121 (65 / 122), 1U 86 / 155 (84 / 158), 3U 108 / 195 (106 / 200), 5U 120 / 217
     * (119 / 223). Still on board at the 480-min horizon, where the engine drops a dose: at speed
     * 1.0, at most 1.1e-8% of a dose up to 7U and 0.09% of 30U; at speed 0.5, 0.009% of 1U, 1.9%
     * of 3U, 4.9% of 5U and 27% of 30U.
     * Python replica of this engine without its 480-min window (equal to this file run on the JVM
     * within 3.1e-8U of IOB over the first 480 min of the doses below), 12 SMBs
     * totalling 7.85U in one group (0.9U at 0 min, 0.6 at 10, 1.3 at 20, 0.4 at 35, 0.7 at 45, 0.5
     * at 60, 0.3 at 75, 0.8 at 90, 0.5 at 105, 0.4 at 120, 0.55 at 140, 0.9 at 160): the cascade's
     * IOB exceeds the sum of its SMBs' separate IOB contributions by up to 23.2% of 7.85U, and its
     * delivered insulin minus IOB reaches 50% / 90% of 7.85U 46.5 / 68.9 min later than for the
     * same SMBs computed separately and summed, on any basal rate (previous constants: 9.7%, 22.8 /
     * 23.7 min). With basal crowding only itself, a basal rate started with no insulin on board
     * (rate/60 U at the end of every minute) reaches 50% / 90% of its steady-state activity after
     * 81 / 196 min at 0.5 U/h and 96 / 237 min at 1 U/h (previous constants: 106 / 246 and 113 /
     * 262).
     *
     * Fitted constants (masses in U, rates in 1/min):
     *  - K(M) = K1_B0 * M^K1_B1, K1_B0 = 0.01784168, K1_B1 = -0.2224188 (depot and transit)
     *  - KE   = 0.1297809 (t1/2 5.34 min; the active stage)
     * Previous (active stage crowding too, KE(A) = KE_E0 * A^KE_E1, fitted directly to the EPAR
     * curves): K1_B0 = 0.01617986, K1_B1 = -0.03676295, KE_E0 = 0.0427785, KE_E1 = -0.4966087.
     * Before that (live rate, EPAR plus TsunamiMods' 0.2-3.5U curves): K1_B0 = 0.017258, K1_B1 =
     * -0.215412, KE = 0.4389397 (t1/2 1.6 min).
     * Before that (rate held per dose): K1_B0 = 0.032072, K1_B1 = -0.303093, KE = 0.0315818.
     * Raw-EPAR fit, rate held per dose: K1_B0 = 0.044874, K1_B1 = -0.339993, KE = 0.0170232.
     * Depot-only model: K1_B0 = 0.069092, K1_B1 = -0.596593 (depot only), K2 = 0.031796,
     * KE = ln(2)/44.
     */
    private object PdModel {
        const val KE = 0.1297809 // fitted; t1/2 5.34 min

        const val K1_B0 = 0.01784168
        const val K1_B1 = -0.2224188

        /** Never feed a literal zero into the negative-exponent power law. */
        private const val MASS_FLOOR = 1e-6

        /** Isolated single-dose state is tabulated on a 1-min grid this long. */
        private const val ISOLATED_GRID_MINUTES = 600

        private const val RATE_EPS = 1e-7

        /**
         * Exact closed-form propagation of (D, X, A) forward by [dt] minutes under constant
         * rates (k1, k2, ke) - equivalent to integrating the linear ODE
         *   dD/dt = -k1*D ; dX/dt = k1*D - k2*X ; dA/dt = k2*X - ke*A
         * with no discretization error for any dt. Built from two primitives ([chainStage2] and
         * [threeStageA]) so every place a rate-difference would divide by (near) zero is routed
         * through an explicit, numerically-safe degenerate branch instead - callers pass the same
         * crowded rate for k1 and k2, and crowding continuously varies it, so it crossing
         * arbitrarily close to the fixed KE is an expected, not exceptional, case.
         */
        fun propagate(d0: Double, x0: Double, a0: Double, k1: Double, k2: Double, ke: Double, dt: Double): Triple<Double, Double, Double> {
            val d = d0 * exp(-k1 * dt)
            val x = x0 * exp(-k2 * dt) + chainStage2(d0, k1, k2, dt)
            val a = a0 * exp(-ke * dt) + chainStage2(x0, k2, ke, dt) + threeStageA(d0, k1, k2, ke, dt)
            return Triple(d, x, a)
        }

        /**
         * Shared depot/transit rate of a crowding group whose live depot mass is [depotMass], at the
         * per-patient absorption [speedFactor] (DoubleKey.InsulinTrafficJamSpeedMultiplier, applied
         * directly - see that key's doc comment). Only this rate scales by it, never KE.
         */
        fun rate(speedFactor: Double, depotMass: Double): Double =
            speedFactor * K1_B0 * max(depotMass, MASS_FLOOR).pow(K1_B1)

        /** An isolated dose's (D, X, A) at whole minutes 0..ISOLATED_GRID_MINUTES, per (amount, speedFactor). */
        private val isolatedGrids = object : LinkedHashMap<Pair<Double, Double>, Array<DoubleArray>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Double, Double>, Array<DoubleArray>>?): Boolean = size > 256
        }

        private fun isolatedGrid(amount: Double, speedFactor: Double): Array<DoubleArray> = synchronized(isolatedGrids) {
            isolatedGrids.getOrPut(Pair(amount, speedFactor)) {
                val n = ISOLATED_GRID_MINUTES + 1
                val d = DoubleArray(n)
                val x = DoubleArray(n)
                val a = DoubleArray(n)
                d[0] = amount
                for (i in 1 until n) {
                    val k1 = rate(speedFactor, d[i - 1])
                    val (nd, nx, na) = propagate(d[i - 1], x[i - 1], a[i - 1], k1, k1, KE, 1.0)
                    d[i] = nd; x[i] = nx; a[i] = na
                }
                arrayOf(d, x, a)
            }
        }

        /**
         * (D, X, A) of an isolated (unpooled) single dose of [amount], [tElapsed] minutes after it,
         * with the rate re-evaluated every minute from the dose's own live depot mass, as the
         * engine does. Past ISOLATED_GRID_MINUTES the rate is held at its last value.
         */
        fun isolatedState(tElapsed: Double, amount: Double, speedFactor: Double): Triple<Double, Double, Double> {
            if (tElapsed <= 0.0) return Triple(amount, 0.0, 0.0)
            val g = isolatedGrid(amount, speedFactor)
            val i = min(tElapsed.toInt(), ISOLATED_GRID_MINUTES)
            val k1 = rate(speedFactor, g[0][i])
            return propagate(g[0][i], g[1][i], g[2][i], k1, k1, KE, tElapsed - i)
        }

        /** Isolated (unpooled) fraction of [amount] still on board [tElapsed] minutes after a single dose. */
        fun isolatedIobFraction(tElapsed: Double, amount: Double, speedFactor: Double): Double {
            if (amount <= 0.0) return 0.0
            val (d, x, a) = isolatedState(tElapsed, amount, speedFactor)
            return (d + x + a) / amount
        }

        /** Second stage (X) of a 2-compartment chain fed by [q0] entering at rate [r1], draining at [r2]. */
        private fun chainStage2(q0: Double, r1: Double, r2: Double, t: Double): Double {
            if (q0 == 0.0) return 0.0
            return if (abs(r2 - r1) < RATE_EPS) {
                q0 * r1 * t * exp(-r1 * t)
            } else {
                q0 * r1 * (exp(-r1 * t) - exp(-r2 * t)) / (r2 - r1)
            }
        }

        /** Third stage (A) impulse response fed by [d0] entering the chain (k1, k2, ke) at t=0. */
        private fun threeStageA(d0: Double, k1: Double, k2: Double, ke: Double, t: Double): Double {
            if (d0 == 0.0) return 0.0
            val k1k2 = abs(k2 - k1) < RATE_EPS
            val k1ke = abs(ke - k1) < RATE_EPS
            val k2ke = abs(ke - k2) < RATE_EPS

            return when {
                k1k2 && k1ke -> d0 * k1 * k1 * t * t / 2.0 * exp(-k1 * t) // all three rates equal (Erlang-3)
                k1k2         -> { // k1 == k2 != ke - the normal case, since depot and transit share one rate
                    val q = ke - k1
                    // Series near q*t -> 0: the closed form cancels catastrophically there, and the
                    // shared crowded rate crosses ke as a depot empties (0.385 /min at an empty depot
                    // against ke 0.130 at speed 1.0: equal at about 1.3e-4U of depot mass).
                    if (abs(q * t) < 1e-3) d0 * k1 * k1 * exp(-k1 * t) * t * t * (0.5 - q * t / 6.0 + q * q * t * t / 24.0)
                    else d0 * k1 * k1 * (t * exp(-k1 * t) / q - (exp(-k1 * t) - exp(-ke * t)) / (q * q))
                }

                k1ke         -> { // k1 == ke != k2
                    val d = k2 - k1
                    d0 * k1 * k2 * t * exp(-k1 * t) / d - d0 * k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d)
                }

                k2ke         -> { // k2 == ke != k1
                    val d = k2 - k1
                    d0 * k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d) - d0 * k1 * k2 * t * exp(-k2 * t) / d
                }

                else         -> d0 * k1 * k2 * (
                    exp(-k1 * t) / ((k2 - k1) * (ke - k1)) +
                        exp(-k2 * t) / ((k1 - k2) * (ke - k2)) +
                        exp(-ke * t) / ((k1 - ke) * (k2 - ke))
                    )
            }
        }
    }
}
