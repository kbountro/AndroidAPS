package app.aaps.plugins.insulin.tsunami

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
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
 * as the depot's absorption rate depending on how much mass is currently still in transit,
 * evaluated live rather than snapshotted per dose. Because every state is a genuine ODE
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

        // One persistent pool per attribution category - never one per dose.
        val pools = Array(Category.entries.size) { Pool() }
        var k1Physical = PdModel.K1_B0 * 1.0.pow(PdModel.K1_B1) // placeholder until the first physical dose sets it for real
        var k1Theoretical = k1Physical
        var lastTime = startTime

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
                resultsMap[tTarget] = evaluatePools(pools, k1Physical, k1Theoretical, dtPeek, boluses, tTarget, divisor)
                nextTargetIdx++
            }

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                for (cat in Category.entries) {
                    val pool = pools[cat.ordinal]
                    val k1 = if (cat.isPhysical) k1Physical else k1Theoretical
                    pool.propagateInPlace(k1, PdModel.K2, PdModel.KE, dtMins)
                }
            }
            lastTime = dose.timestamp

            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                if (dose.bolusAmt > 0.0) pools[Category.BOLUS.ordinal].D += dose.bolusAmt
                if (dose.extBolusAmt > 0.0) pools[Category.EXT_BOLUS.ordinal].D += dose.extBolusAmt
                if (dose.basalAbsAmt > 0.0) pools[Category.BASAL_ABS.ordinal].D += dose.basalAbsAmt

                val physicalMass = groupMass(pools, physical = true)
                k1Physical = PdModel.K1_B0 * physicalMass.pow(PdModel.K1_B1)
            }

            if (dose.profileBasalAmt > 0.0 || dose.autoProfileBasalAmt > 0.0) {
                if (dose.profileBasalAmt > 0.0) pools[Category.PROFILE_BASAL.ordinal].D += dose.profileBasalAmt
                if (dose.autoProfileBasalAmt > 0.0) pools[Category.AUTO_PROFILE_BASAL.ordinal].D += dose.autoProfileBasalAmt

                val theoreticalMass = groupMass(pools, physical = false)
                k1Theoretical = PdModel.K1_B0 * theoreticalMass.pow(PdModel.K1_B1)
            }
        }

        // Process remaining snapshots at the very end of the timeline
        while (nextTargetIdx < sortedTargets.size) {
            val tTarget = sortedTargets[nextTargetIdx]
            val dtPeek = (tTarget - lastTime) / 60000.0
            resultsMap[tTarget] = evaluatePools(pools, k1Physical, k1Theoretical, dtPeek, boluses, tTarget, divisor)
            nextTargetIdx++
        }

        return resultsMap
    }

    /** Total mass still somewhere in the shared depot (depot + transit stages) for a crowding group. */
    private fun groupMass(pools: Array<Pool>, physical: Boolean): Double {
        var mass = 0.0
        for (cat in Category.entries) {
            if (cat.isPhysical == physical) {
                val pool = pools[cat.ordinal]
                mass += pool.D + pool.X
            }
        }
        return max(mass, 1e-6) // never feed a literal zero into the negative-exponent power law
    }

    /**
     * Evaluates every category's IOB/activity at [tTarget] by peeking [dtPeek] minutes ahead of the
     * pools' last-committed state - without mutating it, since several snapshot times can fall
     * between two consecutive dose events and each must be evaluated from the same starting state.
     */
    private fun evaluatePools(
        pools: Array<Pool>,
        k1Physical: Double,
        k1Theoretical: Double,
        dtPeek: Double,
        boluses: List<BS>,
        tTarget: Long,
        divisor: Double
    ): TsunamiIobResult {
        var bSnooze = 0.0
        var lastBolusTime = 0L

        for (b in boluses) {
            if (b.isValid && b.timestamp <= tTarget) {
                if (b.amount > 0 && b.timestamp > lastBolusTime) {
                    lastBolusTime = b.timestamp
                }
                if (b.type != BS.Type.SMB) {
                    val timeSinceTreatment = tTarget - b.timestamp
                    val snoozeTime = b.timestamp + (timeSinceTreatment * divisor).toLong()
                    val tSnoozeElapsed = (tTarget - snoozeTime) / 60000.0

                    if (tSnoozeElapsed in 0.0..<DIA_HORIZON_MINUTES) {
                        // Snooze reflects this bolus's own isolated pool, not shared crowding.
                        bSnooze += b.amount * PdModel.isolatedIobFraction(tSnoozeElapsed, b.amount)
                    }
                }
            }
        }

        fun peek(cat: Category): Pool {
            val k1 = if (cat.isPhysical) k1Physical else k1Theoretical
            return pools[cat.ordinal].propagated(k1, PdModel.K2, PdModel.KE, dtPeek)
        }

        val bolusP = peek(Category.BOLUS)
        val extBolusP = peek(Category.EXT_BOLUS)
        val basalAbsP = peek(Category.BASAL_ABS)
        val profileP = peek(Category.PROFILE_BASAL)
        val autoProfileP = peek(Category.AUTO_PROFILE_BASAL)

        val bnIob = basalAbsP.iob() - profileP.iob()
        val bnAct = basalAbsP.activity() - profileP.activity()
        val bnaIob = basalAbsP.iob() - autoProfileP.iob()
        val bnaAct = basalAbsP.activity() - autoProfileP.activity()

        return TsunamiIobResult(
            bolusTotal = mapToTotal(bolusP.iob(), bolusP.activity(), "bolus", lastBolusTime, bSnooze),
            extBolusTotal = mapToTotal(extBolusP.iob(), extBolusP.activity(), "extBolus"),
            profileBaselineTotal = mapToTotal(profileP.iob(), profileP.activity(), "profile"),
            basalNetTotal = mapToTotal(bnIob, bnAct, "netBasal"),
            basalNetAutoTotal = mapToTotal(bnaIob, bnaAct, "netBasal")
        )
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
     * Three linked first-order compartments replacing the earlier independently-fit Weibull
     * PD/PK curves: D (SC depot) -> X (transit) -> A (active, reported as activity = KE*A).
     * IOB(t) = D(t) + X(t) + A(t): mass not yet through the final elimination step.
     *
     * Only [K1_B0]/[K1_B1] (the depot's own absorption rate, D -> X) depend on how much mass
     * is currently shared-pool crowded; [K2] (transit) and [KE] (elimination) are fixed
     * constants, never touched by crowding. This is deliberate, not just simpler: since
     * activity = KE*A and KE never changes, activity is exactly continuous through every dose
     * event, for every category, with no exception - a dose can only ever slow down mass that
     * hasn't left the depot yet, never mass that has already moved on.
     *
     * [KE] is not a free-fit parameter: it's fixed at insulin lispro's real serum elimination
     * half-life (44 min -> ke = ln(2)/44). [K2] and [K1_B0]/[K1_B1] were then calibrated by
     * nonlinear least-squares against the *entire* previous Weibull-fitted PD curve (rising
     * edge, peak, and tail together) at the three EPAR calibration doses (7/15/30U) - the same
     * whole-curve methodology the original Weibull fit itself used - rather than only matching
     * peak time/height. A 2-compartment (D -> A) version was checked first and is infeasible
     * outright: the tallest peak it can produce for a matched peak time falls ~35% short of
     * the Weibull-fitted target at every calibration dose, regardless of rate constants. The
     * 3-compartment version reaches R^2 ~= 0.986 against the full target curve at all three
     * doses, with K2 and KE fixed and only K1 varying - freeing K2/KE to vary too improves
     * this by less than 0.0002, i.e. essentially not at all.
     *
     * Fitted constants (dose in U, rates in 1/min):
     *  - KE    = ln(2)/44 = 0.015753/min (fixed; real lispro serum elimination half-life)
     *  - K2    = 0.019413/min (fixed; transit-stage rate)
     *  - K1(M) = K1_B0 * M^K1_B1, K1_B0=0.105992, K1_B1=-0.653314 (the only crowding-dependent
     *            rate; M is the live shared-pool mass, generalizing the single-dose amount used
     *            during calibration)
     */
    private object PdModel {
        const val KE = 0.0157533 // ln(2)/44
        const val K2 = 0.019413

        const val K1_B0 = 0.105992
        const val K1_B1 = -0.653314

        private const val RATE_EPS = 1e-7

        /**
         * Exact closed-form propagation of (D, X, A) forward by [dt] minutes under constant
         * rates (k1, k2, ke) - equivalent to integrating the linear ODE
         *   dD/dt = -k1*D ; dX/dt = k1*D - k2*X ; dA/dt = k2*X - ke*A
         * with no discretization error for any dt. Built from two primitives ([chainStage2] and
         * [threeStageA]) so every place a rate-difference would divide by (near) zero is routed
         * through an explicit, numerically-safe degenerate branch instead - crowding continuously
         * varies K1, so K1 crossing arbitrarily close to the fixed K2 or KE is an expected,
         * not exceptional, case.
         */
        fun propagate(d0: Double, x0: Double, a0: Double, k1: Double, k2: Double, ke: Double, dt: Double): Triple<Double, Double, Double> {
            val d = d0 * exp(-k1 * dt)
            val x = x0 * exp(-k2 * dt) + chainStage2(d0, k1, k2, dt)
            val a = a0 * exp(-ke * dt) + chainStage2(x0, k2, ke, dt) + threeStageA(d0, k1, k2, ke, dt)
            return Triple(d, x, a)
        }

        /** Isolated (unpooled) fraction of [amount] still on board [tElapsed] minutes after a single dose. */
        fun isolatedIobFraction(tElapsed: Double, amount: Double): Double {
            if (amount <= 0.0) return 0.0
            val k1 = K1_B0 * amount.pow(K1_B1)
            val (d, x, a) = propagate(amount, 0.0, 0.0, k1, K2, KE, tElapsed)
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
                k1k2         -> { // k1 == k2 != ke
                    val q = ke - k1
                    d0 * k1 * k1 * (t * exp(-k1 * t) / q - (exp(-k1 * t) - exp(-ke * t)) / (q * q))
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
