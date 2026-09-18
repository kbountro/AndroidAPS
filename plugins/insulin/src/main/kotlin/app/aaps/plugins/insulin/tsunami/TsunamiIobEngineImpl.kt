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
import kotlin.math.sqrt

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
 */
@Singleton
class TsunamiIobEngineImpl @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin
) : TsunamiIobEngine {

    private val engineLock = Any()
    private var resultCache = HashMap<String, TsunamiIobResult>()
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
        val key = "${toTime}_${sensitivityRatio}_${assumeZeroTempAfter}"

        synchronized(engineLock) {
            resultCache[key]?.let { return it.copy() }
        }

        val now = dateUtil.now()
        // If requested time is older than 24 hours, don't simulate all the way to present to save DB load
        val isDeepHistory = toTime < now - (24 * 60 * 60 * 1000L)

        val startTime = toTime - (8 * 60 * 60 * 1000L) // Always guarantee 8h physical warmup
        val horizon = if (isDeepHistory) {
            toTime + (12 * 60 * 60 * 1000L) // Batch a 12h chunk forward for scrolling
        } else {
            max(toTime, now + 8 * 60 * 60 * 1000L) // Batch all the way through the future projection
        }

        val currentVersion = synchronized(engineLock) { cacheVersion }
        // Pass the exactly requested toTime so the solver guarantees it is generated
        val resultsMap = simulateDepotHistory(toTime, startTime, horizon, sensitivityRatio, assumeZeroTempAfter)

        synchronized(engineLock) {
            if (currentVersion == cacheVersion) {
                for ((t, res) in resultsMap) {
                    val k = "${t}_${sensitivityRatio}_${assumeZeroTempAfter}"
                    resultCache[k] = res
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

        val maxWarpWindow = 6.0 * 60.0
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

            // A curve past 480 min (8h) elapsed can never contribute to any evaluation from
            // here on (evaluateCurvesAt already filters it out), since elapsed time only grows
            // as processing moves forward. Dropping it here changes no computed value - it just
            // keeps the list bounded to a rolling ~8h window instead of the whole simulation span.
            activeCurves.removeAll { (dose.timestamp - it.timestamp) / 60000.0 >= 480.0 }

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                globalPhysicalSC *= exp(-dtMins / globalTauSC)
                globalTheoreticalSC *= exp(-dtMins / globalTauTheoreticalSC)
            }
            lastTime = dose.timestamp

            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                globalPhysicalSC += physicalInjected
                val pkPeak = CompartmentModel.calculateSystemicPeak(globalPhysicalSC)
                val pDiv = pkPeak / 0.74
                val newTpModelPD = 2.0 * (pDiv * pDiv)
                globalTauSC = computeScTau(pkPeak)

                for (curve in activeCurves) {
                    if (!curve.isPhysical) continue
                    val tElapsedOld = (dose.timestamp - curve.timestamp) / 60000.0
                    if (tElapsedOld > 0 && tElapsedOld <= maxWarpWindow && curve.tpModelPD > 0.0) {
                        if (newTpModelPD > curve.tpModelPD) {
                            val tElapsedNew = tElapsedOld * sqrt(newTpModelPD / curve.tpModelPD)
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
                val theoPeak = CompartmentModel.calculateSystemicPeak(globalTheoreticalSC)
                val tDiv = theoPeak / 0.74
                val theoTpModelPD = 2.0 * (tDiv * tDiv)
                globalTauTheoreticalSC = computeScTau(theoPeak)

                for (curve in activeCurves) {
                    if (curve.isPhysical) continue
                    val tElapsedOld = (dose.timestamp - curve.timestamp) / 60000.0
                    if (tElapsedOld > 0 && tElapsedOld <= maxWarpWindow && curve.tpModelPD > 0.0) {
                        if (theoTpModelPD > curve.tpModelPD) {
                            val tElapsedNew = tElapsedOld * sqrt(theoTpModelPD / curve.tpModelPD)
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

                    if (tSnoozeElapsed in 0.0..<480.0) {
                        val snoozePeak = CompartmentModel.calculateSystemicPeak(b.amount)
                        val pDiv = snoozePeak / 0.74
                        val snoozeTpModelPD = 2.0 * (pDiv * pDiv)
                        val tSq = tSnoozeElapsed * tSnoozeElapsed
                        val upperLimitSq = 480.0 * 480.0 // 8 hours in minutes, squared
                        val snoozeBaseExp = exp(-tSq / snoozeTpModelPD)
                        val snoozeLimitExp = exp(-upperLimitSq / snoozeTpModelPD)

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
            if (t !in 0.0..<480.0) continue

            val tSq = t * t
            val upperLimitSq = 480.0 * 480.0
            val baseExp = exp(-tSq / curve.tpModelPD)
            val limitExp = exp(-upperLimitSq / curve.tpModelPD)

            val safeIobContrib = baseExp - limitExp
            val activity = (2.0 / curve.tpModelPD) * t * baseExp

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

    private fun computeScTau(targetTp: Double): Double {
        val tauE = 63.48
        var low = 1.0
        var high = 1000.0 // reaches the ~175 min asymptote of calculateSystemicPeak; 300 only reached ~125 min
        var mid = 150.0

        repeat(15) {
            mid = (low + high) / 2.0
            val currentTp = if (abs(mid - tauE) < 0.1) mid else (mid * tauE / (tauE - mid)) * ln(tauE / mid)
            if (currentTp > targetTp) high = mid else low = mid
        }
        return mid
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

    private object CompartmentModel {
        private const val A0 = 61.33
        private const val A1 = 12.27
        private const val B1 = 0.05185

        fun calculateSystemicPeak(currentScMass: Double): Double =
            0.74 * (A0 + A1 * currentScMass) / (1.0 + B1 * currentScMass)
    }
}
