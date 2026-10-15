package app.aaps.plugins.main.iob.iobCobCalculator

import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.insulin.TsunamiIobEngine
import app.aaps.core.interfaces.insulin.TsunamiIobResult
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.plus
import app.aaps.core.objects.extensions.round
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes IOB/COB calculation to the "Traffic Jam" Tsunami engine when it is the active
 * insulin model, and to the stock, unmodified [IobCobCalculatorPlugin] otherwise. This is
 * the only place that knows both paths exist - [IobCobCalculatorPlugin] itself stays a
 * plain, untouched fallback calculator.
 *
 * Bound to [IobCobCalculator] in place of [IobCobCalculatorPlugin] (see PluginsModule), so
 * every caller that injects [IobCobCalculator] - including [IobCobCalculatorPlugin]'s own
 * self-triggered recalculation - reaches this routing.
 */
@Singleton
class TsunamiAwareIobCobCalculator @Inject constructor(
    private val plugin: IobCobCalculatorPlugin,
    private val tsunamiIobEngine: TsunamiIobEngine,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil
) : IobCobCalculator {

    private val dataLock = Any()
    private var iobTable = LongSparseArray<IobTotal>()

    override var ads: AutosensDataStore
        get() = plugin.ads
        set(value) {
            plugin.ads = value
        }

    override fun getMealDataWithWaitingForCalculationFinish(): MealData =
        plugin.getMealDataWithWaitingForCalculationFinish()

    override fun getLastAutosensDataWithWaitForCalculationFinish(reason: String): AutosensData? =
        plugin.getLastAutosensDataWithWaitForCalculationFinish(reason)

    override fun calculateDetectionStart(from: Long, limitDataToOldestAvailable: Boolean): Long =
        plugin.calculateDetectionStart(from, limitDataToOldestAvailable)

    override fun getBasalData(profile: Profile, fromTime: Long): BasalData =
        plugin.getBasalData(profile, fromTime)

    override fun iobArrayToString(array: Array<IobTotal>): String =
        plugin.iobArrayToString(array)

    override fun getCobInfo(reason: String): CobInfo =
        plugin.getCobInfo(reason)

    override fun clearCache() {
        plugin.clearCache()
        tsunamiIobEngine.resetCache()
        synchronized(dataLock) { iobTable = LongSparseArray() }
    }

    override fun calculateFromTreatmentsAndTemps(toTime: Long, profile: Profile): IobTotal {
        if (!tsunamiIobEngine.isActive) return plugin.calculateFromTreatmentsAndTemps(toTime, profile)

        val now = System.currentTimeMillis()
        val time = ads.roundUpTime(toTime)
        val cacheHit = iobTable[time]
        if (time < now && cacheHit != null) return cacheHit

        val iobTotal = composeTsunamiResult(time, 1.0, now)
        if (time < now) {
            synchronized(dataLock) { iobTable.put(time, iobTotal) }
        }
        return iobTotal
    }

    override fun calculateIobArrayInDia(profile: Profile): Array<IobTotal> {
        if (!tsunamiIobEngine.isActive) return plugin.calculateIobArrayInDia(profile)

        var time = System.currentTimeMillis()
        time = ads.roundUpTime(time)
        val len = ((profile.dia * 60 + 30) / 5).toInt()
        return Array(len) { i -> calculateFromTreatmentsAndTemps(time + i * 5 * 60000, profile) }
    }

    override fun calculateIobArrayForSMB(lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): Array<IobTotal> {
        if (!tsunamiIobEngine.isActive) return plugin.calculateIobArrayForSMB(lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget)

        val now = dateUtil.now()
        val len = 4 * 60 / 5
        return Array(len) { i ->
            val t = now + i * 5 * 60000
            val sensitivityRatio = sensitivityRatioFor(t, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget)
            composeTsunamiResult(t, sensitivityRatio, now, useAutoBasal = true)
        }
    }

    override fun calculateIobFromBolus(): IobTotal =
        if (tsunamiIobEngine.isActive) tsunamiIobEngine.calculateIobFromBolusToTime(dateUtil.now())
        else plugin.calculateIobFromBolus()

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal =
        if (tsunamiIobEngine.isActive) tsunamiIobEngine.calculateAbsoluteIobFromBaseBasals(toTime)
        else plugin.calculateAbsoluteIobFromBaseBasals(toTime)

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal =
        if (tsunamiIobEngine.isActive) tsunamiIobEngine.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime)
        else plugin.calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime)

    override fun calculateIobFromTempBasalsIncludingConvertedExtended(): IobTotal =
        calculateIobToTimeFromTempBasalsIncludingConvertedExtended(dateUtil.now())

    /** Ratio adjustment for exercise/temp-target mode, mirroring the non-Tsunami exercise logic. */
    private fun sensitivityRatioFor(time: Long, lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): Double {
        val profile = profileFunction.getProfile(time) ?: return lastAutosensResult.ratio
        var sensitivityRatio = lastAutosensResult.ratio
        val normalTarget = Constants.NORMAL_TARGET_MGDL.toDouble()
        if (exerciseMode && isTempTarget && profile.getTargetMgdl() >= normalTarget + 5) {
            val mgdlHalfBasalExerciseTarget = halfBasalExerciseTarget * if (profile.units.toString() == "mmol/L") GlucoseUnit.MMOLL_TO_MGDL else 1.0
            val c = mgdlHalfBasalExerciseTarget - normalTarget
            sensitivityRatio = c / (c + profile.getTargetMgdl() - normalTarget)
        }
        return sensitivityRatio
    }

    /**
     * IOB = all insulin on board at the site (boluses, SMBs, extended boluses and delivered basal) minus
     * the profile baseline (the profile basal alone), both from the engine's site model. The zero-temp
     * IOB is the same quantity from the engine run with delivered basal set to zero from one minute
     * after [now]: every part of it, IOB and activity alike, comes from that run, since with basal
     * interacting with the other doses a zero temp also changes how fast the boluses absorb.
     */
    private fun composeTsunamiResult(time: Long, sensitivityRatio: Double, now: Long, useAutoBasal: Boolean = false): IobTotal {
        val iobTotal = totalMinusProfile(tsunamiIobEngine.resultAt(time, sensitivityRatio), useAutoBasal)
        val zeroTempStart = now + 60 * 1000L
        iobTotal.iobWithZeroTemp =
            if (zeroTempStart < time) totalMinusProfile(tsunamiIobEngine.resultAt(time, sensitivityRatio, assumeZeroTempAfter = zeroTempStart), useAutoBasal)
            else iobTotal.copy()
        return iobTotal
    }

    private fun totalMinusProfile(res: TsunamiIobResult, useAutoBasal: Boolean): IobTotal {
        // copy() first: the engine's result objects are shared with its cache.
        val bolusIob = res.bolusTotal.copy().plus(res.extBolusTotal)
        val basalIob = if (useAutoBasal) res.basalNetAutoTotal else res.basalNetTotal
        return IobTotal.combine(bolusIob, basalIob).round()
    }
}
