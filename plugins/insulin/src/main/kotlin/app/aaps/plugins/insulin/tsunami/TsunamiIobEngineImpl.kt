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
    // still be broken down the same way AAPS expects. Bolus/extBolus/basalAbs share one
    // "physical" crowding pool; profileBasal/autoProfileBasal share one "theoretical"
    // pool - matching which doses are meant to compete for the same physical depot space.
    // Every dose is an impulse added straight into its category's depot mass; nothing
    // about any other category's or any earlier dose's state is ever rewritten - only the
    // shared group's absorption rate constant is updated, and every pool simply keeps
    // integrating forward from its current state under that rate, in closed form.
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
        // once per simulated window, like K1_B0/KE themselves are effectively constant - a
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
            var k1Physical = speedFactor * PdModel.K1_B0 * 1.0.pow(PdModel.K1_B1) // placeholder until the first physical dose sets it for real
            var k1Theoretical = k1Physical
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
                    val k1 = if (cat.isPhysical) site.k1Physical else site.k1Theoretical
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
                        val k1 = if (cat.isPhysical) site.k1Physical else site.k1Theoretical
                        pool.propagateInPlace(k1, k1, PdModel.KE, dtMins)
                    }
                }
            }
            lastTime = dose.timestamp

            val site = siteFor(siteGenerationFor(dose.timestamp, siteChangeBoundaries))

            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                if (dose.bolusAmt > 0.0) site.pools[Category.BOLUS.ordinal].D += dose.bolusAmt
                if (dose.extBolusAmt > 0.0) site.pools[Category.EXT_BOLUS.ordinal].D += dose.extBolusAmt
                if (dose.basalAbsAmt > 0.0) site.pools[Category.BASAL_ABS.ordinal].D += dose.basalAbsAmt

                val physicalMass = physicalGroupMass(site.pools)
                site.k1Physical = speedFactor * PdModel.K1_B0 * physicalMass.pow(PdModel.K1_B1)
            }

            if (dose.profileBasalAmt > 0.0 || dose.autoProfileBasalAmt > 0.0) {
                if (dose.profileBasalAmt > 0.0) site.pools[Category.PROFILE_BASAL.ordinal].D += dose.profileBasalAmt
                if (dose.autoProfileBasalAmt > 0.0) site.pools[Category.AUTO_PROFILE_BASAL.ordinal].D += dose.autoProfileBasalAmt

                // Driven by the plain profile pool ALONE - autoProfileBasal (which scales with
                // sensitivityRatio) must never feed back into this, or the "ground truth" profile
                // curve would shift with autosens instead of reflecting only the programmed rate.
                // Only D (mass still physically in the depot), not X (already past it) - see
                // physicalGroupMass's doc comment for why X must never count as crowding mass.
                val profilePool = site.pools[Category.PROFILE_BASAL.ordinal]
                val theoreticalMass = max(profilePool.D, 1e-6)
                site.k1Theoretical = speedFactor * PdModel.K1_B0 * theoreticalMass.pow(PdModel.K1_B1)
            }
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
     * Total mass still physically sitting in one site's shared depot (bolus + extBolus +
     * basalAbs) - every unit of actually-administered insulin genuinely competes for the same
     * physical SC space, so all three deliberately crowd each other here. Deliberately D only,
     * never D+X: X is mass that has already left the depot (past the crowding-limited step, in
     * transit toward the active/eliminated stage), so it no longer competes for SC depot space
     * and must not inflate the rate that governs the depot's own outflow. Getting this wrong
     * compounds under sustained dosing - X never fully clears between frequent small doses
     * (real SMB/TBR cadence), so including it keeps dragging k1 down further the longer a
     * session runs. [pools] is always one site generation's own pools, never mixed across sites.
     */
    private fun physicalGroupMass(pools: Array<Pool>): Double {
        var mass = 0.0
        for (cat in Category.entries) {
            if (cat.isPhysical) {
                mass += pools[cat.ordinal].D
            }
        }
        return max(mass, 1e-6) // never feed a literal zero into the negative-exponent power law
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

    private enum class Category(val isPhysical: Boolean) {
        BOLUS(true), EXT_BOLUS(true), BASAL_ABS(true),
        PROFILE_BASAL(false), AUTO_PROFILE_BASAL(false)
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
     * Depot and transit share ONE crowding-dependent rate, K(M) = K1_B0 * M^K1_B1, where M is the
     * live depot mass of the shared pool (D only - see physicalGroupMass). [KE] is fixed and never
     * touched by crowding, so activity = KE*A stays exactly continuous through every dose event: a
     * new dose slows mass still in the depot or transit, never mass already in the active stage.
     *
     * Why depot AND transit, with a fitted KE: in the previous fit only the depot (D -> X) was
     * crowded, while transit (K2 = 0.031796) and the active stage (KE pinned to lispro's 44 min
     * serum half-life) were fixed. Those two fixed stages alone put a ~95 min floor under the mean
     * action time of every dose, against ~70 min for TsunamiMods' curve at 0.3U, so small SMBs
     * were slow however fast the depot was - and the depot, the only stage crowding could act on,
     * held just ~7% of a 0.3U dose's mean action time. Crowding mass drained within minutes, so a
     * cascade barely jammed. With the crowded rate shared by depot and transit, the crowded stages
     * hold 33.5% of the mean action time at 0.3U and 59.5% at 7U.
     *
     * Fit: unweighted nonlinear least-squares of the isolated single-dose activity curve, with
     * K1_B0, K1_B1 and KE all free, against the digitized EPAR GIR clamp curves (Lyumjev/LY900014,
     * 7/15/30U, 832 points, each normalized to unit area over 0-600 min). R^2 per dose 0.982 /
     * 0.976 / 0.940, pooled over all points 0.970 (previous fit: 0.980 / 0.965 / 0.935, pooled
     * 0.964). The fitted KE half-life is 40.7 min. With K1_B0 and K1_B1 refitted at each fixed KE,
     * the residual sum of squares stays within 1% of its minimum for half-lives of 30.9-47.7 min,
     * and is 0.18% above it at lispro's 44 min serum half-life. EPAR has no curves below 7U.
     * Against TsunamiMods' curve there, an extrapolation of its own 7-30U fit, R^2 over 0-600 min
     * is 0.922 at 0.1U, 0.961 at 0.3U and 0.982 at 1U (previous fit: 0.765, 0.891 and 0.969).
     * Engine replay with 0.5 U/h basal in the pool: the cumulative activity added by a 0.3U SMB
     * reaches 90% of the dose at 172 min (was 195; TsunamiMods isolated: 122), and a 12-SMB/7.85U
     * cascade's IOB exceeds the sum of its SMBs' separate IOB contributions by up to 7.3% of the
     * cascade total (was 2.2%). Basal shares the crowded pool, so at higher basal rates the depot
     * holds more mass and SMBs on top act more slowly (at 1 U/h: 185 min, was 197, and 6.3% for
     * the cascade, was 2.8%).
     *
     * Fitted constants (dose in U, rates in 1/min):
     *  - K(M) = K1_B0 * M^K1_B1, K1_B0 = 0.044874, K1_B1 = -0.339993 (depot and transit)
     *  - KE   = 0.0170232 (t1/2 40.7 min; the active stage, no longer pinned to serum half-life)
     * Previous fit: K1_B0 = 0.069092, K1_B1 = -0.596593 (depot only), K2 = 0.031796, KE = ln(2)/44.
     */
    private object PdModel {
        const val KE = 0.0170232 // fitted; t1/2 40.7 min

        const val K1_B0 = 0.044874
        const val K1_B1 = -0.339993

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
         * Isolated (unpooled) fraction of [amount] still on board [tElapsed] minutes after a single
         * dose, at the given per-patient absorption [speedFactor] (DoubleKey.InsulinTrafficJamSpeedMultiplier,
         * applied directly - see that key's doc comment). Only the shared depot/transit rate scales by it, never ke.
         */
        fun isolatedIobFraction(tElapsed: Double, amount: Double, speedFactor: Double): Double {
            if (amount <= 0.0) return 0.0
            val k1 = speedFactor * K1_B0 * amount.pow(K1_B1)
            val (d, x, a) = propagate(amount, 0.0, 0.0, k1, k1, KE, tElapsed)
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
                    // shared crowded rate crosses ke at a common pool mass (~17U at speed 1.0).
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
