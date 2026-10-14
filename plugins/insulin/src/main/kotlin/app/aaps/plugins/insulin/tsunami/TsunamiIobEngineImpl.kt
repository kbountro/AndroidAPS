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
 * depot, and the active stage's exit rate depending on how much mass is currently in the
 * active stage, both evaluated live rather than snapshotted per dose. Because every state is
 * a genuine ODE state (not an age-parametrized curve), a rate constant can change value at any instant
 * with no reinterpretation needed - the state simply keeps evolving from wherever it
 * currently is. This is what the earlier per-dose-curve design could not do: that design
 * evaluated `IOB(t) = exp(-t^p/tp)` from each curve's own elapsed age, so an amount-driven
 * change to `tp` had no continuous way to apply mid-flight, and had to be patched with a
 * "time warp" (retroactively inventing a new elapsed age to keep IOB continuous) that
 * unavoidably forced a discontinuous drop in reported activity every time the pool grew.
 * Here a dose causes no jump in activity; activity steps only where the active stage's exit
 * rate is re-evaluated (see [PdModel]).
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
    // still be broken down the same way AAPS expects. Bolus/extBolus share one pair of
    // crowding rates, basalAbs has its own, and profileBasal/autoProfileBasal share one driven
    // by the plain profile pool - see [CrowdingGroup] for why basal crowds only itself.
    // Every dose is an impulse added straight into its category's depot mass; nothing
    // about any other category's or any earlier dose's state is ever rewritten - only each
    // group's two rates are updated, once a minute: the depot/transit rate from the group's
    // live depot mass and the active stage's exit rate from its live active mass. Every pool
    // simply keeps integrating forward from its current state under those rates, in closed
    // form.
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

            // One shared depot/transit rate and one active-stage exit rate per crowding group,
            // driven by that group's own live depot and active masses (see [CrowdingGroup]).
            // Empty pools until the first dose, so the starting values never touch any mass.
            val k1 = DoubleArray(CrowdingGroup.entries.size) { PdModel.rate(speedFactor, 0.0) }
            val ke = DoubleArray(CrowdingGroup.entries.size) { PdModel.exitRate(0.0) }

            fun rateFor(cat: Category): Double = k1[cat.group.ordinal]
            fun exitRateFor(cat: Category): Double = ke[cat.group.ordinal]

            fun refreshRates() {
                for (g in CrowdingGroup.entries) {
                    k1[g.ordinal] = PdModel.rate(speedFactor, groupDepotMass(pools, g))
                    ke[g.ordinal] = PdModel.exitRate(groupActiveMass(pools, g))
                }
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
            // Activity is summed site by site, since each site's active stage drains at its
            // own rate: a site's activity is its exit rate times its own active mass.
            fun peekSum(cat: Category): Peek {
                var iob = 0.0; var act = 0.0
                for (site in sites.values) {
                    val k1 = site.rateFor(cat)
                    val ke = site.exitRateFor(cat)
                    val p = site.pools[cat.ordinal].propagated(k1, k1, ke, dtPeek)
                    iob += p.iob(); act += ke * p.A
                }
                return Peek(iob, act)
            }

            val bolusP = peekSum(Category.BOLUS)
            val extBolusP = peekSum(Category.EXT_BOLUS)
            val basalAbsP = peekSum(Category.BASAL_ABS)
            val profileP = peekSum(Category.PROFILE_BASAL)
            val autoProfileP = peekSum(Category.AUTO_PROFILE_BASAL)

            val bnIob = basalAbsP.iob - profileP.iob
            val bnAct = basalAbsP.activity - profileP.activity
            val bnaIob = basalAbsP.iob - autoProfileP.iob
            val bnaAct = basalAbsP.activity - autoProfileP.activity

            return TsunamiIobResult(
                bolusTotal = mapToTotal(bolusP.iob, bolusP.activity, "bolus", lastBolusTimeSeen, bSnooze),
                extBolusTotal = mapToTotal(extBolusP.iob, extBolusP.activity, "extBolus"),
                profileBaselineTotal = mapToTotal(profileP.iob, profileP.activity, "profile"),
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
                        pool.propagateInPlace(k1, k1, site.exitRateFor(cat), dtMins)
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

            // Every rate follows its group's live masses, so it is re-evaluated at every event,
            // whether or not this event injected anything into that group: buildDoseEventTimeline
            // emits one event per minute wherever a profile exists (zero temps and the zero-temp
            // projection included), so no rate is then held for more than a minute while its depot
            // or active stage empties. Where no profile exists, only boluses create events and the
            // rates are held between them. Every live generation too, since an old site's pools keep
            // emptying after dosing has moved to the new one.
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

    /**
     * Mass in one site's active stage (A) for one crowding [group]: what the group's active-stage
     * exit rate follows ([PdModel.exitRate]), as the depot rate follows [groupDepotMass]. Same
     * pools as there, so autoProfileBasal never feeds back into the profile group's rate. Floored
     * inside [PdModel.exitRate].
     */
    private fun groupActiveMass(pools: Array<Pool>, group: CrowdingGroup): Double {
        var mass = 0.0
        for (cat in Category.entries) {
            if (cat.group == group && cat.drivesGroupRate) {
                mass += pools[cat.ordinal].A
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
     * the live depot mass of the group's own pools only, and its own active-stage exit rate,
     * driven by the live active mass of the same pools:
     *  - [BOLUS]: boluses, SMBs and extended boluses crowd each other, so an SMB cascade jams.
     *  - [BASAL]: delivered basal crowds only itself, so a bolus no longer slows the delivered
     *    basal pool and none of its effect is booked to net basal IOB. Counted into the bolus
     *    group instead, the basal depot mass slows every SMB on top of it, and no constant-KE
     *    constants then fit both the EPAR curves (no basal infused) and TsunamiMods' small-dose
     *    curves given on a basal rate: see [PdModel] (found with a constant KE; not tested with
     *    the current constants). Left out of crowding altogether, basal would sit at the rates of
     *    empty pools and reach 50% / 90% of a new steady state after 63 / 145 min (crowding only
     *    itself: 106 / 246 min at 0.5 U/h).
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

    /** One category's IOB and activity summed over every live site generation. */
    private class Peek(val iob: Double, val activity: Double)

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
     * CrowdingGroup). The active stage drains at its own crowding-dependent rate, KE(A) = KE_E0 *
     * A^KE_E1 ([exitRate]), where A is the live active mass of the same group (groupActiveMass).
     * Both rates are re-evaluated at every event (every whole minute and every bolus timestamp)
     * and held until the next, and each interval is solved exactly. With KE_E1 < 0 the active stage drains more slowly the more insulin it
     * holds: 0.134 /min (half-life 5.2 min) at 0.1U, 0.0428 /min (16.2 min) at 1U, 0.0192 /min
     * (36.0 min) at 5U, so a cascade crowds the active stage as well as the depot. With K1_B1 =
     * -0.037 the depot rate hardly depends on the depot mass: 0.0176 /min at 0.1U, 0.0162 at 1U,
     * 0.0151 at 7U, 0.0143 at 30U, 0.0269 at an empty depot (the 1e-6U floor). A new dose enters D
     * and the depot/transit rate is re-evaluated right after it with the dose included, so it slows
     * mass still in the depot or transit at once. The dose adds nothing to A, so KE and activity do
     * not jump when it is given. Activity steps at every re-evaluation of KE: for an isolated dose
     * of 0.1-30U by up to 4.5-6.8% of the dose's peak activity in its first 6 minutes, at most 2.4%
     * from minute 20 and at most 0.8% from minute 60 (Python replica of this engine).
     *
     * Fit: K1_B0, K1_B1, KE_E0 and KE_E1 free, both rates re-evaluated every minute as above,
     * directly to the digitized EPAR GIR clamp curves (Lyumjev/LY900014, 7/15/30U, points up to
     * 480 min, no basal insulin infused), with the objective TsunamiMods' Weibull constants were
     * fitted with: activity and IOB together, on curves truncated at 480 min. Every curve, target
     * and model alike, is cut at 480 min and normalized over 0-480 min: activity is divided by its
     * integral over 0-480 min, and IOB is 1 minus the cumulative activity divided by its value at
     * 480 min (1 at 0, 0 at 480 min). For the model's isolated single-dose activity a(t) and IOB
     * fraction S(t), that is a(t) / (1 - S(480)) and (S(t) - S(480)) / (1 - S(480)). Each target
     * curve's squared errors are divided by its total sum of squares, so the objective is the sum
     * over the 6 curves of (1 - R^2), all over 0-480 min. The model is sampled at whole minutes,
     * right after each re-evaluation, and interpolated linearly to the EPAR sample times. The
     * objective is 0.758 times that of TsunamiMods' Weibull form fitted the same way (a0 69.944
     * min, a1 0.2177, p 1.9017, within 2.0% of TsunamiMods' constants). The same three stages with
     * a constant KE, fitted the same way to the EPAR curves alone (K1_B0 0.017553, K1_B1
     * -0.220458, KE 0.29147), reach 0.958 times. R^2 activity / IOB on the truncated curves: 0.988 /
     * 0.999, 0.989 / 0.997, 0.982 / 1.000 at 7 / 15 / 30U. No curve below 7U is in the fit, so the
     * small-dose timing below is the fitted law's extrapolation. Since neither of a dose's rates
     * depends on basal, each dose's own curve is the same on any basal rate. Isolated dose at
     * speed 1.0, minutes until 1 - IOB/dose reaches 0.5 / 0.9 (whole curve, not cut at 480 min):
     * 0.1U 93 / 206 (TsunamiMods 52 / 97), 0.3U 97 / 215 (65 / 122), 1U 104 / 225 (84 / 158), 3U
     * 114 / 236 (106 / 200), 5U 122 / 242 (119 / 223). Still on board at the 480-min horizon, where
     * the engine drops a dose: at speed 1.0, 0.04% of 0.1U, 0.21% of 7U, 0.34% of 30U; at speed 0.5,
     * 6.7-9.8% of 0.3-5U.
     * Python replica of this engine (equal to this file run on the JVM within 4e-15U), 12 SMBs
     * totalling 7.85U in one group (0.9U at 0 min, 0.6 at 10, 1.3 at 20, 0.4 at 35, 0.7 at 45, 0.5
     * at 60, 0.3 at 75, 0.8 at 90, 0.5 at 105, 0.4 at 120, 0.55 at 140, 0.9 at 160): the cascade's
     * IOB exceeds the sum of its SMBs' separate IOB contributions by up to 9.7% of 7.85U, and its
     * delivered insulin minus IOB reaches 50% / 90% of 7.85U 22.8 / 23.7 min later than for the
     * same SMBs computed separately and summed, on any basal rate (previous constants: 23.1%,
     * 46.5 / 71.1 min). With basal crowding only itself, a basal rate started with no insulin on
     * board (rate/60 U at the end of every minute) reaches 50% / 90% of its steady-state activity
     * after 106 / 246 min at 0.5 U/h and 113 / 262 min at 1 U/h (previous constants: 79 / 200 and
     * 95 / 241).
     *
     * Why the rates are live: earlier constants were fitted with the rate held at K(dose) for the
     * whole dose, while this engine re-evaluates it from the live depot mass. Run the way the
     * engine runs them with no basal, those constants scored EPAR activity R^2 0.654 / 0.533 /
     * 0.449 at 7 / 15 / 30U. With the basal depot mass counted in the same crowding mass as the
     * SMBs, no constant-KE constants fit both the EPAR curves and TsunamiMods' small-dose curves
     * given on a basal rate (KE ran to the upper end of any search range, EPAR objective near 4.5
     * times the Weibull form's at 0.5 U/h). Hence the separate crowding groups. That was found for
     * the constant-KE model; the current constants, fitted to the EPAR curves alone, have not been
     * tested with basal in the bolus group.
     *
     * Fitted constants (masses in U, rates in 1/min):
     *  - K(M)  = K1_B0 * M^K1_B1, K1_B0 = 0.01617986, K1_B1 = -0.03676295 (depot and transit)
     *  - KE(A) = KE_E0 * A^KE_E1, KE_E0 = 0.0427785, KE_E1 = -0.4966087 (the active stage)
     * Previous (constant KE, live rate, EPAR plus TsunamiMods' 0.2-3.5U curves): K1_B0 = 0.017258,
     * K1_B1 = -0.215412, KE = 0.4389397 (t1/2 1.6 min).
     * Before that (rate held per dose): K1_B0 = 0.032072, K1_B1 = -0.303093, KE = 0.0315818.
     * Raw-EPAR fit, rate held per dose: K1_B0 = 0.044874, K1_B1 = -0.339993, KE = 0.0170232.
     * Depot-only model: K1_B0 = 0.069092, K1_B1 = -0.596593 (depot only), K2 = 0.031796,
     * KE = ln(2)/44.
     */
    private object PdModel {
        const val K1_B0 = 0.01617986
        const val K1_B1 = -0.03676295

        const val KE_E0 = 0.0427785
        const val KE_E1 = -0.4966087

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
         * crowded rate for k1 and k2, and crowding changes both it and ke at every refresh, so the
         * two crossing arbitrarily close to each other is an expected, not exceptional, case.
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
         * directly - see that key's doc comment). Only this rate scales by it, never [exitRate].
         */
        fun rate(speedFactor: Double, depotMass: Double): Double =
            speedFactor * K1_B0 * max(depotMass, MASS_FLOOR).pow(K1_B1)

        /**
         * Active-stage exit rate of a crowding group whose live active mass is [activeMass]. Never
         * multiplied by the absorption speed knob, which describes the injection site; it follows
         * only the active mass.
         */
        fun exitRate(activeMass: Double): Double =
            KE_E0 * max(activeMass, MASS_FLOOR).pow(KE_E1)

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
                    val (nd, nx, na) = propagate(d[i - 1], x[i - 1], a[i - 1], k1, k1, exitRate(a[i - 1]), 1.0)
                    d[i] = nd; x[i] = nx; a[i] = na
                }
                arrayOf(d, x, a)
            }
        }

        /**
         * (D, X, A) of an isolated (unpooled) single dose of [amount], [tElapsed] minutes after it,
         * with both rates re-evaluated every minute from the dose's own live depot and active
         * masses, as the engine does. Past ISOLATED_GRID_MINUTES the rates are held at their last
         * values.
         */
        fun isolatedState(tElapsed: Double, amount: Double, speedFactor: Double): Triple<Double, Double, Double> {
            if (tElapsed <= 0.0) return Triple(amount, 0.0, 0.0)
            val g = isolatedGrid(amount, speedFactor)
            val i = min(tElapsed.toInt(), ISOLATED_GRID_MINUTES)
            val k1 = rate(speedFactor, g[0][i])
            return propagate(g[0][i], g[1][i], g[2][i], k1, k1, exitRate(g[2][i]), tElapsed - i)
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
                    // Series near q*t -> 0: the closed form cancels catastrophically there, and ke
                    // crosses the shared crowded rate whenever the active mass passes the point where
                    // the two are equal (about 2.5-9U at speed 1.0, depending on the depot mass; no
                    // isolated dose up to 30U reaches it at speed 1.0, but larger speed factors do).
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
