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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Traffic Jam" IOB engine backing [Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM].
 *
 * Models a single shared subcutaneous depot: every dose adds to it, and the more mass
 * is sitting in the depot the longer the systemic peak time gets for everything still
 * absorbing out of it - including doses already in flight, whose effective onset gets
 * stretched ("time-warped") when a later dose crowds the depot further. Because doses
 * interact through this shared, amount-dependent state, they cannot be evaluated one
 * at a time and summed the way the standard oref/PD insulin models are; the whole dose
 * history has to be walked in chronological order, carrying the depot state forward.
 *
 * The PD side (the action/IOB curve actually reported to AAPS) and the PK side (serum
 * concentration) are independently fit Weibull models - see [PdPkModel] - rather than
 * one derived heuristically from the other. The PK model is not itself the depot's decay
 * curve (it's the combined absorption+elimination output); the depot's own absorption
 * rate is recovered from it by [computeScTau], holding elimination fixed at insulin
 * lispro's real serum half-life.
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
            toTime + (12 * 60 * 60 * 1000L) // Batch a 12h chunk forward for scrolling
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
    // An event-driven (impulsive) compartmental model: each dose is solved in
    // closed form - exactly, not by numerical integration - and dosing events
    // are the only points where curves are re-parametrized against the shared
    // depot's current state. This is the same style NONMEM's closed-form ADVAN
    // routines use for linear compartments, extended here with a depot-mass-
    // dependent (nonlinear, amount-in-depot) absorption rate.
    //
    // The PK model is fit to observed SERUM CONCENTRATION (EPAR Figure 8) - the
    // combined output of SC absorption *and* systemic elimination, not the SC
    // depot's own content. The depot itself, under the standard two-compartment
    // assumption, decays as simple exponential at its own (unobserved) absorption
    // rate. That rate is recovered by holding elimination fixed at insulin
    // lispro's real serum half-life (44 min -> tauE = 44/ln2 = 63.48 min) and
    // solving the classic Bateman peak-time formula backward (computeScTau)
    // against the PK model's own fitted peak time - not a heuristic. The shared
    // depot pool is then decayed with that recovered absorption rate.
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

        // Matches the full DIA horizon: a curve should still respond to crowding for as long as
        // it can still contribute any IOB at all. The re-warp loop already visits every curve
        // regardless of this cap (it only gates the cheap inner update), so widening it to the
        // full horizon costs nothing extra. An on-device A/B test confirmed this setting was not
        // the cause of an earlier freeze (which turned out to be unrelated).
        val maxWarpWindow = DIA_HORIZON_MINUTES
        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        val capacity = ((horizonTime - startTime) / 300000L).toInt() + 20
        val activeCurves = ArrayList<TsunamiCurve>(capacity * 2)

        var globalPhysicalSC = 0.0
        var globalTauSC = 50.0
        var globalTheoreticalSC = 0.0
        var globalTauTheoreticalSC = 50.0
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
                resultsMap[tTarget] = evaluateCurvesAt(tTarget, activeCurves, boluses, divisor)
                nextTargetIdx++
            }

            // A curve past the DIA horizon elapsed can never contribute to any evaluation from
            // here on (evaluateCurvesAt already filters it out), since elapsed time only grows
            // as processing moves forward. Dropping it here changes no computed value - it just
            // keeps the list bounded to a rolling window instead of the whole simulation span.
            activeCurves.removeAll { (dose.timestamp - it.timestamp) / 60000.0 >= DIA_HORIZON_MINUTES }

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                globalPhysicalSC *= exp(-dtMins / globalTauSC)
                globalTheoreticalSC *= exp(-dtMins / globalTauTheoreticalSC)
            }
            lastTime = dose.timestamp

            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                globalPhysicalSC += physicalInjected
                val newTpModelPD = PdPkModel.pdTp(globalPhysicalSC)
                globalTauSC = computeScTau(PdPkModel.pkPeakTime(globalPhysicalSC))

                for (curve in activeCurves) {
                    if (!curve.isPhysical) continue
                    val tElapsedOld = (dose.timestamp - curve.timestamp) / 60000.0
                    if (tElapsedOld > 0 && tElapsedOld <= maxWarpWindow && curve.tpModelPD > 0.0) {
                        if (newTpModelPD > curve.tpModelPD) {
                            val tElapsedNew = tElapsedOld * (newTpModelPD / curve.tpModelPD).pow(1.0 / PdPkModel.PD_P)
                            curve.timestamp = dose.timestamp - (tElapsedNew * 60000.0).toLong()
                            curve.tpModelPD = newTpModelPD
                        }
                    }
                }

                activeCurves.add(
                    TsunamiCurve(
                        timestamp = dose.timestamp,
                        bolusAmt = dose.bolusAmt,
                        extBolusAmt = dose.extBolusAmt,
                        basalAbsAmt = dose.basalAbsAmt,
                        profileBasalAmt = 0.0,
                        autoProfileBasalAmt = 0.0,
                        tpModelPD = newTpModelPD,
                        isPhysical = true
                    )
                )
            }

            if (dose.profileBasalAmt > 0.0 || dose.autoProfileBasalAmt > 0.0) {
                globalTheoreticalSC += dose.profileBasalAmt
                val theoTpModelPD = PdPkModel.pdTp(globalTheoreticalSC)
                globalTauTheoreticalSC = computeScTau(PdPkModel.pkPeakTime(globalTheoreticalSC))

                for (curve in activeCurves) {
                    if (curve.isPhysical) continue
                    val tElapsedOld = (dose.timestamp - curve.timestamp) / 60000.0
                    if (tElapsedOld > 0 && tElapsedOld <= maxWarpWindow && curve.tpModelPD > 0.0) {
                        if (theoTpModelPD > curve.tpModelPD) {
                            val tElapsedNew = tElapsedOld * (theoTpModelPD / curve.tpModelPD).pow(1.0 / PdPkModel.PD_P)
                            curve.timestamp = dose.timestamp - (tElapsedNew * 60000.0).toLong()
                            curve.tpModelPD = theoTpModelPD
                        }
                    }
                }

                activeCurves.add(
                    TsunamiCurve(
                        timestamp = dose.timestamp,
                        bolusAmt = 0.0, extBolusAmt = 0.0, basalAbsAmt = 0.0,
                        profileBasalAmt = dose.profileBasalAmt,
                        autoProfileBasalAmt = dose.autoProfileBasalAmt,
                        tpModelPD = theoTpModelPD,
                        isPhysical = false
                    )
                )
            }
        }

        // Process remaining snapshots at the very end of the timeline
        while (nextTargetIdx < sortedTargets.size) {
            val tTarget = sortedTargets[nextTargetIdx]
            resultsMap[tTarget] = evaluateCurvesAt(tTarget, activeCurves, boluses, divisor)
            nextTargetIdx++
        }

        return resultsMap
    }

    /**
     * Recovers the SC depot's own (unobserved) absorption time constant from the PK model's
     * fitted serum peak time, holding elimination fixed at insulin lispro's real half-life. The
     * classic two-compartment Bateman peak-time formula, solved backward by bisection:
     *   t_peak = (tauAbs*tauElim / (tauElim-tauAbs)) * ln(tauElim/tauAbs)
     */
    private fun computeScTau(targetTp: Double): Double {
        val tauE = 63.48 // insulin lispro serum elimination time constant: 44 min half-life / ln(2)
        var low = 1.0
        var high = 1000.0
        var mid = 150.0

        repeat(15) {
            mid = (low + high) / 2.0
            val currentTp = if (abs(mid - tauE) < 0.1) mid else (mid * tauE / (tauE - mid)) * ln(tauE / mid)
            if (currentTp > targetTp) high = mid else low = mid
        }
        return mid
    }

    private fun evaluateCurvesAt(tTarget: Long, activeCurves: List<TsunamiCurve>, boluses: List<BS>, divisor: Double): TsunamiIobResult {
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
                        // Snooze reflects this bolus's own single-dose PD curve, not pool crowding.
                        val snoozeTpModelPD = PdPkModel.pdTp(b.amount)
                        val snoozeBaseExp = exp(-tSnoozeElapsed.pow(PdPkModel.PD_P) / snoozeTpModelPD)
                        val snoozeLimitExp = exp(-PdPkModel.pdUpperLimitPow / snoozeTpModelPD)

                        val snoozeIobContrib = snoozeBaseExp - snoozeLimitExp
                        bSnooze += b.amount * snoozeIobContrib
                    }
                }
            }
        }

        var bIob = 0.0; var bAct = 0.0; var eIob = 0.0; var eAct = 0.0
        var baIob = 0.0; var baAct = 0.0; var pIob = 0.0; var pAct = 0.0; var apIob = 0.0; var apAct = 0.0

        for (curve in activeCurves) {
            val t = (tTarget - curve.timestamp) / 60000.0
            if (t !in 0.0..<DIA_HORIZON_MINUTES) continue

            val tPowPm1 = t.pow(PdPkModel.PD_P - 1.0)
            val baseExp = exp(-(tPowPm1 * t) / curve.tpModelPD)
            val limitExp = exp(-PdPkModel.pdUpperLimitPow / curve.tpModelPD)

            val safeIobContrib = baseExp - limitExp
            val activity = (PdPkModel.PD_P / curve.tpModelPD) * tPowPm1 * baseExp

            if (curve.bolusAmt > 0.0) { bIob += curve.bolusAmt * safeIobContrib; bAct += curve.bolusAmt * activity }
            if (curve.extBolusAmt > 0.0) { eIob += curve.extBolusAmt * safeIobContrib; eAct += curve.extBolusAmt * activity }
            if (curve.basalAbsAmt > 0.0) { baIob += curve.basalAbsAmt * safeIobContrib; baAct += curve.basalAbsAmt * activity }
            if (curve.profileBasalAmt > 0.0) { pIob += curve.profileBasalAmt * safeIobContrib; pAct += curve.profileBasalAmt * activity }
            if (curve.autoProfileBasalAmt > 0.0) { apIob += curve.autoProfileBasalAmt * safeIobContrib; apAct += curve.autoProfileBasalAmt * activity }
        }

        val bnIob = baIob - pIob
        val bnAct = baAct - pAct
        val bnaIob = baIob - apIob
        val bnaAct = baAct - apAct

        return TsunamiIobResult(
            bolusTotal = mapToTotal(bIob, bAct, "bolus", lastBolusTime, bSnooze),
            extBolusTotal = mapToTotal(eIob, eAct, "extBolus"),
            profileBaselineTotal = mapToTotal(pIob, pAct, "profile"),
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

        // Fetch Temp Basals and Extended Boluses ONCE into RAM for lightning-fast loop speeds
        val tbs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(alignedFromTime, toTime, true)
        val ebs = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(alignedFromTime, toTime, true)

        while (bTime < toTime) {
            val nextTime = min(bTime + 60000L, toTime)
            val durationMins = (nextTime - bTime) / 60000.0

            if (durationMins > 0) {
                val profile = profileFunction.getProfile(bTime)
                if (profile != null) {
                    val profileRate = profile.getBasal(bTime)
                    var absoluteRate = profileRate

                    // 1. Resolve Active Temp Basal in RAM (Replaces processedTbrEbData to support Deep History)
                    var activeTb: TB? = null
                    for (tb in tbs) {
                        val effectiveEnd = min(tb.timestamp + tb.duration, currentNow)
                        if (bTime >= tb.timestamp && bTime < effectiveEnd) {
                            if (activeTb == null || tb.timestamp > activeTb.timestamp) {
                                activeTb = tb
                            }
                        }
                    }
                    if (activeTb != null) {
                        absoluteRate = activeTb.convertedToAbsolute(bTime, profile)
                    }

                    // 2. Resolve Extended Boluses
                    var extBolusChunk = 0.0
                    for (e in ebs) {
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

    private data class TsunamiCurve(
        var timestamp: Long,
        val bolusAmt: Double,
        val extBolusAmt: Double,
        val basalAbsAmt: Double,
        val profileBasalAmt: Double,
        val autoProfileBasalAmt: Double,
        var tpModelPD: Double,
        val isPhysical: Boolean
    )

    private data class DoseEvent(
        val timestamp: Long,
        var bolusAmt: Double = 0.0,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var profileBasalAmt: Double = 0.0,
        var autoProfileBasalAmt: Double = 0.0
    )

    /**
     * Independently-fit PD (action/IOB) and PK (serum concentration) Weibull models, replacing the
     * earlier single heuristic formula that derived a PK target from the PD peak via a fixed ratio.
     * Both follow the same family: tau(dose) = a0 * dose^a1, tp = 2 * tau^p. The PD model is used
     * directly as the reported action/IOB curve: IOB(t) = exp(-t^p/tp) [survival], activity(t) =
     * (p/tp) * t^(p-1) * exp(-t^p/tp) [density]. The PK model describes serum concentration - the
     * combined output of SC absorption and elimination, not the SC depot's own content - so it is
     * only ever used via its own fitted peak time ([pkPeakTime]), which [computeScTau] inverts
     * against a fixed elimination rate to recover the depot's own absorption time constant.
     *
     * Fitted from LY900014 (Lyumjev) EPAR data at doses 7/15/30U:
     *  - PD from GIR curves (Figure 34): a0=1.1814 h, a1=0.2134, p=1.9002
     *  - PK from concentration curves (Figure 8): a0=0.7973 h, a1=0.1522, p=1.5495
     * a0 is converted from hours to minutes here (source value * 60) since this engine works in
     * minutes throughout.
     */
    private object PdPkModel {
        const val PD_A0 = 70.884 // = 1.1814 h * 60
        const val PD_A1 = 0.2134
        const val PD_P = 1.9002

        const val PK_A0 = 47.838 // = 0.7973 h * 60
        const val PK_A1 = 0.1522
        const val PK_P = 1.5495

        val pdUpperLimitPow: Double = TsunamiIobEngineImpl.DIA_HORIZON_MINUTES.pow(PD_P)

        fun pdTp(amount: Double): Double = 2.0 * (PD_A0 * amount.pow(PD_A1)).pow(PD_P)

        private fun pkTp(amount: Double): Double = 2.0 * (PK_A0 * amount.pow(PK_A1)).pow(PK_P)

        /** Peak time of the fitted PK (serum concentration) curve for a dose/pool mass of [amount]. */
        fun pkPeakTime(amount: Double): Double {
            val tp = pkTp(amount)
            return (tp * (PK_P - 1.0) / PK_P).pow(1.0 / PK_P)
        }
    }
}
