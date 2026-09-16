package app.aaps.plugins.main.iob.iobCobCalculator

import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData

import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventPreferenceChange

import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.iobCalc
import app.aaps.core.objects.extensions.plus
import app.aaps.core.objects.extensions.round
import app.aaps.plugins.main.R
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject

import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class IobCobCalculatorPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val overviewData: OverviewData,
    private val calculationWorkflow: CalculationWorkflow,
    private val decimalFormatter: DecimalFormatter,
    private val processedTbrEbData: ProcessedTbrEbData
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.iob_cob_calculator)
        .showInList { false }
        .neverVisible(true)
        .alwaysEnabled(true),
    aapsLogger, rh
), IobCobCalculator {

    private val disposable = CompositeDisposable()
    private var iobTable = LongSparseArray<IobTotal>() // oldest at index 0
    private var basalDataTable = LongSparseArray<BasalData>() // oldest at index 0

    // Thread-safe Cache for the Euler Engine
    private var eulerCache = HashMap<String, EulerResult>()
    private var cacheVersion = 0

    override var ads: AutosensDataStore = AutosensDataStoreObject()

    private val dataLock = Any()
    private var thread: Thread? = null

    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventConfigBuilderChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event -> resetDataAndRunCalculation("onEventConfigBuilderChange", event) }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event -> newHistoryData(event.startDate, false, event) }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           if (event.isChanged(IntKey.AutosensPeriod.key) ||
                               event.isChanged(StringKey.SafetyAge.key) ||
                               event.isChanged(DoubleKey.AbsorptionMaxTime.key) ||
                               event.isChanged(DoubleKey.ApsAmaMin5MinCarbsImpact.key) ||
                               event.isChanged(DoubleKey.ApsSmbMin5MinCarbsImpact.key) ||
                               event.isChanged(DoubleKey.AbsorptionCutOff.key) ||
                               event.isChanged(DoubleKey.AutosensMax.key) ||
                               event.isChanged(DoubleKey.AutosensMin.key) ||
                               event.isChanged(IntKey.InsulinOrefPeak.key)
                           ) {
                               resetDataAndRunCalculation("onEventPreferenceChange", event)
                           }
                           if (event.isChanged(StringKey.GeneralUnits.key)) {
                               overviewData.reset()
                               rxBus.send(EventNewHistoryData(0, false))
                           }
                           if (event.isChanged(IntNonKey.RangeToDisplay.key)) {
                               overviewData.initRange()
                               calculationWorkflow.runOnScaleChanged(this, overviewData)
                               rxBus.send(EventNewHistoryData(0, false))
                           }
                       }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventNewHistoryData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event -> scheduleHistoryDataChange(event) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ calculationWorkflow.runOnEventTherapyEventChange(overviewData) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ calculationWorkflow.runOnEventTherapyEventChange(overviewData) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAppInitialized::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe(
                {
                    calculationWorkflow.runCalculation(
                        CalculationWorkflow.MAIN_CALCULATION,
                        this,
                        overviewData,
                        "onEventAppInitialized",
                        System.currentTimeMillis(),
                        bgDataReload = true,
                        cause = it
                    )
                },
                fabricPrivacy::logException
            )
        historyWorker = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStop() {
        disposable.clear()
        historyWorker?.shutdown()
        historyWorker = null
        super.onStop()
    }

    private fun resetDataAndRunCalculation(reason: String, event: Event?) {
        calculationWorkflow.stopCalculation(CalculationWorkflow.MAIN_CALCULATION, reason)
        clearCache()
        ads.reset()
        calculationWorkflow.runCalculation(
            job = CalculationWorkflow.MAIN_CALCULATION,
            iobCobCalculator = this,
            overviewData = overviewData,
            reason = reason,
            end = System.currentTimeMillis(),
            bgDataReload = true,
            cause = event
        )
    }

    override fun clearCache() {
        synchronized(dataLock) {
            aapsLogger.debug(LTag.AUTOSENS, "Clearing cached data.")
            iobTable = LongSparseArray()
            basalDataTable = LongSparseArray()
            eulerCache.clear()
            cacheVersion++
        }
    }

    private fun oldestDataAvailable(): Long {
        var oldestTime = System.currentTimeMillis()
        val oldestTempBasal = persistenceLayer.getOldestTemporaryBasalRecord()
        if (oldestTempBasal != null) oldestTime = min(oldestTime, oldestTempBasal.timestamp)
        val oldestExtendedBolus = persistenceLayer.getOldestExtendedBolusRecord()
        if (oldestExtendedBolus != null) oldestTime = min(oldestTime, oldestExtendedBolus.timestamp)
        val oldestBolus = persistenceLayer.getOldestBolus()
        if (oldestBolus != null) oldestTime = min(oldestTime, oldestBolus.timestamp)
        val oldestCarbs = persistenceLayer.getOldestCarbs()
        if (oldestCarbs != null) oldestTime = min(oldestTime, oldestCarbs.timestamp)
        val oldestPs = persistenceLayer.getOldestEffectiveProfileSwitch()
        if (oldestPs != null) oldestTime = min(oldestTime, oldestPs.timestamp)
        oldestTime -= 15 * 60 * 1000L // allow 15 min before
        return oldestTime
    }

    override fun calculateDetectionStart(from: Long, limitDataToOldestAvailable: Boolean): Long {
        val profile = profileFunction.getProfile(from)
        val dia = profile?.dia ?: Constants.defaultDIA
        val oldestDataAvailable = oldestDataAvailable()
        val getBGDataFrom: Long
        if (limitDataToOldestAvailable) {
            getBGDataFrom = max(oldestDataAvailable, (from - T.hours(1).msecs() * (24 + dia)).toLong())
            if (getBGDataFrom == oldestDataAvailable) aapsLogger.debug(LTag.AUTOSENS, "Limiting data to oldest available temps: " + dateUtil.dateAndTimeAndSecondsString(oldestDataAvailable))
        } else getBGDataFrom = (from - T.hours(1).msecs() * (24 + dia)).toLong()
        return getBGDataFrom
    }

    override fun calculateFromTreatmentsAndTemps(toTime: Long, profile: Profile): IobTotal {
        val now = System.currentTimeMillis()
        val time = ads.roundUpTime(toTime)

        if (activePlugin.activeInsulin.id.value == 106) {
            val cacheHit = iobTable[time]
            if (time < now && cacheHit != null) {
                return cacheHit
            }

            val normalRes = getCachedOrRunEulerSolver(time, 1.0)
            val bolusIob = IobTotal.combine(normalRes.bolusTotal, normalRes.extBolusTotal)
            val basalIob = normalRes.basalNetTotal

            val basalIobWithZeroTemp = basalIob.copy()
            val zeroTempStart = now + 60 * 1000L
            if (zeroTempStart < time) {
                val zeroRes = getCachedOrRunEulerSolver(time, 1.0, assumeZeroTempAfter = zeroTempStart)
                basalIobWithZeroTemp.basaliob = zeroRes.basalNetTotal.basaliob
                basalIobWithZeroTemp.netbasalinsulin = zeroRes.basalNetTotal.netbasalinsulin
                basalIobWithZeroTemp.hightempinsulin = zeroRes.basalNetTotal.hightempinsulin
            }

            basalIobWithZeroTemp.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()
            val iobTotal = IobTotal.combine(bolusIob, basalIob).round()
            iobTotal.iobWithZeroTemp = basalIobWithZeroTemp.iobWithZeroTemp

            if (time < now) {
                synchronized(dataLock) {
                    iobTable.put(time, iobTotal)
                }
            }
            return iobTotal
        }

        val cacheHit = iobTable[time]
        if (time < now && cacheHit != null) {
            return cacheHit
        }
        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = calculateIobToTimeFromTempBasalsIncludingConvertedExtended(time).round()

        val basalIobWithZeroTemp = basalIob.copy()
        val t = TB(
            timestamp = now + 60 * 1000L,
            duration = 240 * 60 * 1000L,
            rate = 0.0,
            isAbsolute = true,
            type = TB.Type.NORMAL
        )
        if (t.timestamp < time) {
            val calc = t.iobCalc(time, profile, activePlugin.activeInsulin)
            basalIobWithZeroTemp.plus(calc)
        }
        basalIob.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()
        val iobTotal = IobTotal.combine(bolusIob, basalIob).round()
        if (time < System.currentTimeMillis()) {
            synchronized(dataLock) {
                iobTable.put(time, iobTotal)
            }
        }
        return iobTotal
    }

    private fun calculateFromTreatmentsAndTemps(time: Long, lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): IobTotal {
        if (activePlugin.activeInsulin.id.value == 106) {
            val profile = profileFunction.getProfile(time) ?: return IobTotal(time)
            var sensitivityRatio = lastAutosensResult.ratio
            val normalTarget = Constants.NORMAL_TARGET_MGDL.toDouble()

            if (exerciseMode && isTempTarget && profile.getTargetMgdl() >= normalTarget + 5) {
                val mgdlHalfBasalExerciseTarget = halfBasalExerciseTarget * if (profile.units.toString() == "mmol/L") app.aaps.core.data.model.GlucoseUnit.MMOLL_TO_MGDL else 1.0
                val c = mgdlHalfBasalExerciseTarget - normalTarget
                sensitivityRatio = c / (c + profile.getTargetMgdl() - normalTarget)
            }

            val normalRes = getCachedOrRunEulerSolver(time, sensitivityRatio)
            val bolusIob = IobTotal.combine(normalRes.bolusTotal, normalRes.extBolusTotal)
            val basalIob = normalRes.basalNetAutoTotal

            val basalIobWithZeroTemp = basalIob.copy()
            val now = dateUtil.now()
            val zeroTempStart = now + 60 * 1000L
            if (zeroTempStart < time) {
                val zeroRes = getCachedOrRunEulerSolver(time, sensitivityRatio, assumeZeroTempAfter = zeroTempStart)
                basalIobWithZeroTemp.basaliob = zeroRes.basalNetAutoTotal.basaliob
                basalIobWithZeroTemp.netbasalinsulin = zeroRes.basalNetAutoTotal.netbasalinsulin
                basalIobWithZeroTemp.hightempinsulin = zeroRes.basalNetAutoTotal.hightempinsulin
            }

            basalIobWithZeroTemp.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()
            val iobTotal = IobTotal.combine(bolusIob, basalIob).round()
            iobTotal.iobWithZeroTemp = basalIobWithZeroTemp.iobWithZeroTemp
            return iobTotal
        }

        val now = dateUtil.now()
        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = getCalculationToTimeTempBasals(time, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget).round()

        val basalIobWithZeroTemp = basalIob.copy()
        val t = TB(
            timestamp = now + 60 * 1000L,
            duration = 240 * 60 * 1000L,
            rate = 0.0,
            isAbsolute = true,
            type = TB.Type.NORMAL
        )
        if (t.timestamp < time) {
            val profile = profileFunction.getProfile(t.timestamp)
            if (profile != null) {
                val calc = t.iobCalc(time, profile, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget, activePlugin.activeInsulin)
                basalIobWithZeroTemp.plus(calc)
            }
        }
        basalIob.iobWithZeroTemp = IobTotal.combine(bolusIob, basalIobWithZeroTemp).round()
        return IobTotal.combine(bolusIob, basalIob).round()
    }

    override fun getBasalData(profile: Profile, fromTime: Long): BasalData {
        val now = System.currentTimeMillis()
        val time = ads.roundUpTime(fromTime)
        var retVal = basalDataTable[time]
        if (retVal == null) {
            retVal = BasalData()
            val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(time)
            retVal.basal = profile.getBasal(time)
            if (tb != null) {
                retVal.isTempBasalRunning = true
                retVal.tempBasalAbsolute = tb.convertedToAbsolute(time, profile)
            } else {
                retVal.isTempBasalRunning = false
                retVal.tempBasalAbsolute = retVal.basal
            }
            if (time < now) {
                synchronized(dataLock) {
                    basalDataTable.append(time, retVal)
                }
            }
        }
        return retVal
    }

    override fun getLastAutosensDataWithWaitForCalculationFinish(reason: String): AutosensData? {
        if (thread?.isAlive == true) {
            aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA is waiting for calculation thread: $reason")
            try {
                thread?.join(5000)
            } catch (_: InterruptedException) { }
            aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA finished waiting for calculation thread: $reason")
        }
        return ads.getLastAutosensData(reason, aapsLogger, dateUtil)
    }

    override fun getCobInfo(reason: String): CobInfo {
        val autosensData = ads.getLastAutosensData(reason, aapsLogger, dateUtil)
        var displayCob: Double? = null
        var futureCarbs = 0.0
        val now = dateUtil.now()
        var timestamp = now
        val carbs = persistenceLayer.getCarbsFromTimeExpanded(autosensData?.time ?: now, true)
        if (autosensData != null) {
            displayCob = autosensData.cob
            carbs.forEach { carb ->
                if (carb.timestamp > autosensData.time && carb.timestamp <= now) {
                    displayCob = displayCob!! + carb.amount
                    displayCob = max(displayCob!!, 0.0)
                }
            }
            timestamp = autosensData.time
        }
        carbs.forEach { carb -> if (carb.timestamp > now) futureCarbs += carb.amount }
        return CobInfo(timestamp, displayCob, futureCarbs)
    }

    override fun getMealDataWithWaitingForCalculationFinish(): MealData {
        val result = MealData()
        val now = System.currentTimeMillis()
        val maxAbsorptionHours: Double = activePlugin.activeSensitivity.maxAbsorptionHours()
        val absorptionTimeAgo = now - (maxAbsorptionHours * T.hours(1).msecs()).toLong()
        persistenceLayer.getCarbsFromTimeToTimeExpanded(absorptionTimeAgo + 1, now, true)
            .forEach {
                if (it.amount != 0.0) {
                    result.carbs += it.amount
                    if (it.timestamp > result.lastCarbTime) result.lastCarbTime = it.timestamp
                }
            }
        val autosensData = getLastAutosensDataWithWaitForCalculationFinish("getMealData()")
        if (autosensData != null) {
            result.mealCOB = autosensData.cob
            result.slopeFromMinDeviation = autosensData.slopeFromMinDeviation
            result.slopeFromMaxDeviation = autosensData.slopeFromMaxDeviation
            result.usedMinCarbsImpact = autosensData.usedMinCarbsImpact
        }
        val lastBolus = persistenceLayer.getNewestBolus()
        result.lastBolusTime = lastBolus?.timestamp ?: 0L
        return result
    }

    override fun calculateIobArrayInDia(profile: Profile): Array<IobTotal> {
        var time = System.currentTimeMillis()
        time = ads.roundUpTime(time)
        val len = ((profile.dia * 60 + 30) / 5).toInt()
        val array = Array(len) { IobTotal(0) }
        for ((pos, i) in (0 until len).withIndex()) {
            val t = time + i * 5 * 60000
            val iob = calculateFromTreatmentsAndTemps(t, profile)
            array[pos] = iob
        }
        return array
    }

    override fun calculateIobArrayForSMB(lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): Array<IobTotal> {
        val now = dateUtil.now()
        val len = 4 * 60 / 5
        val array = Array(len) { IobTotal(0) }
        for ((pos, i) in (0 until len).withIndex()) {
            val t = now + i * 5 * 60000
            val iob = calculateFromTreatmentsAndTemps(t, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget)
            array[pos] = iob
        }
        return array
    }

    override fun iobArrayToString(array: Array<IobTotal>): String {
        val sb = StringBuilder()
        sb.append("[")
        for (i in array) {
            sb.append(decimalFormatter.to2Decimal(i.iob))
            sb.append(", ")
        }
        sb.append("]")
        return sb.toString()
    }

    private var historyWorker: ScheduledExecutorService? = null
    private var scheduledHistoryPost: ScheduledFuture<*>? = null
    private var scheduledEvent: EventNewHistoryData? = null

    @Synchronized
    private fun scheduleHistoryDataChange(event: EventNewHistoryData) {
        if (scheduledEvent == null || event.oldDataTimestamp < (scheduledEvent?.oldDataTimestamp ?: 0L)) {
            scheduledHistoryPost?.cancel(false)
            scheduledEvent?.let {
                event.reloadBgData = event.reloadBgData || it.reloadBgData
            }
            scheduledEvent = event
            scheduledHistoryPost = historyWorker?.schedule(
                {
                    synchronized(this) {
                        aapsLogger.debug(LTag.AUTOSENS, "Running newHistoryData")
                        persistenceLayer.clearCachedTddData(MidnightTime.calc(event.oldDataTimestamp))
                        newHistoryData(
                            event.oldDataTimestamp,
                            event.reloadBgData,
                            if (event.newestGlucoseValueTimestamp != null) EventNewBG(event.newestGlucoseValueTimestamp) else event
                        )
                        scheduledEvent = null
                        scheduledHistoryPost = null
                    }
                }, 5L, TimeUnit.SECONDS
            )
        } else {
            scheduledEvent?.let {
                if (!it.reloadBgData) it.reloadBgData = event.reloadBgData
                event.newestGlucoseValueTimestamp?.let { timestamp ->
                    if (timestamp > (it.newestGlucoseValueTimestamp ?: 0L)) it.newestGlucoseValueTimestamp = timestamp
                }
            }
        }
    }

    private fun newHistoryData(oldDataTimestamp: Long, bgDataReload: Boolean, event: Event) {
        calculationWorkflow.stopCalculation(CalculationWorkflow.MAIN_CALCULATION, "onEventNewHistoryData")
        synchronized(dataLock) {
            val time = oldDataTimestamp - 5 * 60 * 1000L
            aapsLogger.debug(LTag.AUTOSENS, "Invalidating cached data to: " + dateUtil.dateAndTimeAndSecondsString(time))
            for (index in iobTable.size() - 1 downTo 0) {
                if (iobTable.keyAt(index) > time) {
                    iobTable.removeAt(index)
                } else {
                    break
                }
            }
            for (index in basalDataTable.size() - 1 downTo 0) {
                if (basalDataTable.keyAt(index) > time) {
                    basalDataTable.removeAt(index)
                } else {
                    break
                }
            }
            eulerCache.clear()
            cacheVersion++
            ads.newHistoryData(time, aapsLogger, dateUtil)
        }
        calculationWorkflow.runCalculation(
            job = CalculationWorkflow.MAIN_CALCULATION,
            iobCobCalculator = this,
            overviewData = overviewData,
            reason = event.javaClass.simpleName,
            end = System.currentTimeMillis(),
            bgDataReload = bgDataReload,
            cause = event
        )
    }

    private fun range(): Long = ((profileFunction.getProfile()?.dia ?: Constants.defaultDIA) * 60 * 60 * 1000).toLong()

    override fun calculateIobFromBolus(): IobTotal = calculateIobFromBolusToTime(dateUtil.now())

    private fun calculateIobFromBolusToTime(toTime: Long): IobTotal {
        if (activePlugin.activeInsulin.id.value == 106) {
            val res = getCachedOrRunEulerSolver(toTime, 1.0)
            val pumpInterface = activePlugin.activePump
            return if (!pumpInterface.isFakingTempsByExtendedBoluses) {
                IobTotal.combine(res.bolusTotal, res.extBolusTotal)
            } else {
                res.bolusTotal
            }
        }

        val total = IobTotal(toTime)
        val profile = profileFunction.getProfile() ?: return total
        val dia = profile.dia
        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)
        assert(divisor > 0)

        val boluses = persistenceLayer.getBolusesFromTime(toTime - range(), true).blockingGet()

        boluses.forEach { t ->
            if (t.isValid && t.timestamp < toTime) {
                val tIOB = t.iobCalc(activePlugin, toTime, dia)
                total.iob += tIOB.iobContrib
                total.activity += tIOB.activityContrib
                if (t.amount > 0 && t.timestamp > total.lastBolusTime) total.lastBolusTime = t.timestamp
                if (t.type != BS.Type.SMB) {
                    val timeSinceTreatment = toTime - t.timestamp
                    val snoozeTime = t.timestamp + (timeSinceTreatment * divisor).toLong()
                    val bIOB = t.iobCalc(activePlugin, snoozeTime, dia)
                    total.bolussnooze += bIOB.iobContrib
                }
            }
        }

        total.plus(calculateIobToTimeFromExtendedBoluses(toTime))
        return total
    }

    private fun calculateIobToTimeFromExtendedBoluses(toTime: Long): IobTotal {
        val total = IobTotal(toTime)
        val now = dateUtil.now()
        val pumpInterface = activePlugin.activePump
        if (!pumpInterface.isFakingTempsByExtendedBoluses) {
            val extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(toTime - range(), toTime, true)
            for (pos in extendedBoluses.indices) {
                val e = extendedBoluses[pos]
                if (e.timestamp > toTime) continue
                if (e.end > now) {
                    val newDuration = now - e.timestamp
                    e.amount *= newDuration.toDouble() / e.duration
                    e.duration = newDuration
                }
                val profile = profileFunction.getProfile(e.timestamp) ?: return total
                val calc = e.iobCalc(toTime, profile, activePlugin.activeInsulin)
                total.plus(calc)
            }
        }
        return total
    }

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal {
        if (activePlugin.activeInsulin.id.value == 106) {
            return getCachedOrRunEulerSolver(toTime, 1.0).profileBaselineTotal
        }

        val total = IobTotal(toTime)
        var i = toTime - range()
        while (i < toTime) {
            val profile = profileFunction.getProfile(i)
            if (profile == null) {
                i += T.mins(5).msecs()
                continue
            }
            val running = profile.getBasal(i)
            val bolus = BS(
                timestamp = i,
                amount = running * 5.0 / 60.0,
                type = BS.Type.NORMAL,
                isBasalInsulin = true
            )
            val iob = bolus.iobCalc(activePlugin, toTime, profile.dia)
            total.basaliob += iob.iobContrib
            total.activity += iob.activityContrib
            i += T.mins(5).msecs()
        }
        return total
    }

    override fun calculateIobFromTempBasalsIncludingConvertedExtended(): IobTotal =
        calculateIobToTimeFromTempBasalsIncludingConvertedExtended(dateUtil.now())

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal {
        if (activePlugin.activeInsulin.id.value == 106) {
            return getCachedOrRunEulerSolver(toTime, 1.0).basalNetTotal
        }

        val total = IobTotal(toTime)
        val now = dateUtil.now()
        val pumpInterface = activePlugin.activePump

        val temporaryBasals = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(toTime - range(), toTime, true)
        for (pos in temporaryBasals.indices) {
            val t = temporaryBasals[pos]
            if (t.timestamp > toTime) continue
            val profile = profileFunction.getProfile(t.timestamp) ?: continue
            if (t.end > now) t.duration = now - t.timestamp
            val calc = t.iobCalc(toTime, profile, activePlugin.activeInsulin)
            total.plus(calc)
        }
        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            val totalExt = IobTotal(toTime)
            val extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(toTime - range(), toTime, true)
            for (pos in extendedBoluses.indices) {
                val e = extendedBoluses[pos]
                if (e.timestamp > toTime) continue
                val profile = profileFunction.getProfile(e.timestamp) ?: continue
                if (e.end > now) {
                    val newDuration = now - e.timestamp
                    e.amount *= newDuration.toDouble() / e.duration
                    e.duration = newDuration
                }
                val calc = e.iobCalc(toTime, profile, activePlugin.activeInsulin)
                totalExt.plus(calc)
            }
            totalExt.basaliob = totalExt.iob
            totalExt.iob = 0.0
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin
            total.plus(totalExt)
        }
        return total
    }

    private fun getCalculationToTimeTempBasals(toTime: Long, lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): IobTotal {
        if (activePlugin.activeInsulin.id.value == 106) {
            val profile = profileFunction.getProfile(toTime) ?: return IobTotal(toTime)
            var sensitivityRatio = lastAutosensResult.ratio
            val normalTarget = Constants.NORMAL_TARGET_MGDL.toDouble()

            if (exerciseMode && isTempTarget && profile.getTargetMgdl() >= normalTarget + 5) {
                val mgdlHalfBasalExerciseTarget = halfBasalExerciseTarget * if (profile.units.toString() == "mmol/L") app.aaps.core.data.model.GlucoseUnit.MMOLL_TO_MGDL else 1.0
                val c = mgdlHalfBasalExerciseTarget - normalTarget
                sensitivityRatio = c / (c + profile.getTargetMgdl() - normalTarget)
            }
            return getCachedOrRunEulerSolver(toTime, sensitivityRatio).basalNetAutoTotal
        }

        val total = IobTotal(toTime)
        val pumpInterface = activePlugin.activePump
        val now = dateUtil.now()
        val temporaryBasals = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(toTime - range(), toTime, true)
        for (pos in temporaryBasals.indices) {
            val t = temporaryBasals[pos]
            if (t.timestamp > toTime) continue
            val profile = profileFunction.getProfile(t.timestamp) ?: continue
            if (t.end > now) t.duration = now - t.timestamp
            val calc = t.iobCalc(toTime, profile, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget, activePlugin.activeInsulin)
            total.plus(calc)
        }
        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            val totalExt = IobTotal(toTime)
            val extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(toTime - range(), toTime, true)
            for (pos in extendedBoluses.indices) {
                val e = extendedBoluses[pos]
                if (e.timestamp > toTime) continue
                val profile = profileFunction.getProfile(e.timestamp) ?: continue
                if (e.end > now) {
                    val newDuration = now - e.timestamp
                    e.amount *= newDuration.toDouble() / e.duration
                    e.duration = newDuration
                }
                val calc = e.iobCalc(toTime, profile, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget, activePlugin.activeInsulin)
                totalExt.plus(calc)
            }
            totalExt.basaliob = totalExt.iob
            totalExt.iob = 0.0
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin
            total.plus(totalExt)
        }
        return total
    }

    private fun getCachedOrRunEulerSolver(toTime: Long, sensitivityRatio: Double, assumeZeroTempAfter: Long = Long.MAX_VALUE): EulerResult {
        val key = "${toTime}_${sensitivityRatio}_${assumeZeroTempAfter}"

        synchronized(dataLock) {
            eulerCache[key]?.let {
                return it.copy()
            }
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

        val currentVersion = synchronized(dataLock) { cacheVersion }
        // Pass the exactly requested toTime so the Master Solver guarantees it is generated
        val resultsMap = runMasterEulerSolver(toTime, startTime, horizon, sensitivityRatio, assumeZeroTempAfter)

        synchronized(dataLock) {
            if (currentVersion == cacheVersion) {
                for ((t, res) in resultsMap) {
                    val k = "${t}_${sensitivityRatio}_${assumeZeroTempAfter}"
                    eulerCache[k] = res
                }
            }
        }

        return synchronized(dataLock) {
            eulerCache[key]?.copy() ?: resultsMap[toTime]?.copy() ?: resultsMap.values.last().copy()
        }
    }

    // =========================================================================
    // THE MASTER EULER SOLVER (THE "TRAFFIC Jam" ENGINE)
    // =========================================================================
    private fun runMasterEulerSolver(
        requestedTime: Long,
        startTime: Long,
        horizonTime: Long,
        sensitivityRatio: Double,
        assumeZeroTempAfter: Long = Long.MAX_VALUE
    ): Map<Long, EulerResult> {
        val now = dateUtil.now()
        val isU200 = activePlugin.activeInsulin.id.value == 205

        val boluses = persistenceLayer.getBolusesFromTime(startTime, true).blockingGet()
        val pumpInterface = activePlugin.activePump
        val isFakingTemps = pumpInterface.isFakingTempsByExtendedBoluses

        val timeline = buildUnifiedEulerTimeline(startTime, horizonTime, sensitivityRatio, boluses, isFakingTemps, assumeZeroTempAfter, now)

        val maxWarpWindow = 6.0 * 60.0
        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        val capacity = ((horizonTime - startTime) / 300000L).toInt() + 20
        val activeCurves = ArrayList<TsunamiCurve>(capacity * 2)

        var globalPhysicalSC = 0.0
        var globalTauSC = 50.0
        var globalTheoreticalSC = 0.0
        var globalTauTheoreticalSC = 50.0
        var lastTime = startTime

        val resultsMap = HashMap<Long, EulerResult>()

        // Use a Set to automatically prevent duplicates, ensuring a perfect chronological map
        val targetTimes = mutableSetOf<Long>()
        var evalT = (startTime / 300000L) * 300000L
        while (evalT <= horizonTime) {
            if (evalT >= startTime) targetTimes.add(evalT)
            evalT += 5 * 60 * 1000L
        }
        // Force the engine to snapshoot the EXACT millisecond requested by AAPS
        targetTimes.add(requestedTime)
        targetTimes.add(horizonTime)

        val sortedTargets = targetTimes.sorted()
        var nextTargetIdx = 0

        for (dose in timeline) {
            // STRICTLY LESS THAN: Take snapshots AFTER the current dose is absorbed, but before the next!
            while (nextTargetIdx < sortedTargets.size && sortedTargets[nextTargetIdx] < dose.timestamp) {
                val tTarget = sortedTargets[nextTargetIdx]
                resultsMap[tTarget] = evaluateCurvesAt(tTarget, activeCurves, boluses, divisor, isU200)
                nextTargetIdx++
            }

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                globalPhysicalSC *= exp(-dtMins / globalTauSC)
                globalTheoreticalSC *= exp(-dtMins / globalTauTheoreticalSC)
            }
            lastTime = dose.timestamp

            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                globalPhysicalSC += physicalInjected
                val pkPeak = CompartmentModel.calculateSystemicPeak(globalPhysicalSC, isU200)
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

                activeCurves.add(TsunamiCurve(
                    timestamp = dose.timestamp,
                    bolusAmt = dose.bolusAmt,
                    extBolusAmt = dose.extBolusAmt,
                    basalAbsAmt = dose.basalAbsAmt,
                    profileBasalAmt = 0.0,
                    autoProfileBasalAmt = 0.0,
                    tpModelPD = newTpModelPD,
                    isPhysical = true
                ))
            }


            if (dose.profileBasalAmt > 0.0 || dose.autoProfileBasalAmt > 0.0) {
                globalTheoreticalSC += dose.profileBasalAmt
                val theoPeak = CompartmentModel.calculateSystemicPeak(globalTheoreticalSC, isU200)
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

                activeCurves.add(TsunamiCurve(
                    timestamp = dose.timestamp,
                    bolusAmt = 0.0, extBolusAmt = 0.0, basalAbsAmt = 0.0,
                    profileBasalAmt = dose.profileBasalAmt,
                    autoProfileBasalAmt = dose.autoProfileBasalAmt,
                    tpModelPD = theoTpModelPD,
                    isPhysical = false
                ))
            }
        }

        // Process remaining snapshots at the very end of the timeline
        while (nextTargetIdx < sortedTargets.size) {
            val tTarget = sortedTargets[nextTargetIdx]
            resultsMap[tTarget] = evaluateCurvesAt(tTarget, activeCurves, boluses, divisor, isU200)
            nextTargetIdx++
        }

        return resultsMap
    }

    private fun evaluateCurvesAt(tTarget: Long, activeCurves: List<TsunamiCurve>, boluses: List<BS>, divisor: Double, isU200: Boolean): EulerResult {
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
                        val snoozePeak = CompartmentModel.calculateSystemicPeak(b.amount, isU200)
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

        return EulerResult(
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
        var high = 300.0
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
            "profile" -> {
                t.basaliob = iob
            }
            "bolus" -> {
                t.lastBolusTime = lastBolusTime
                t.bolussnooze = bSnooze
            }
        }
        return t
    }

    private fun buildUnifiedEulerTimeline(
        fromTime: Long,
        toTime: Long,
        sensitivityRatio: Double,
        boluses: List<BS>,
        isFakingTemps: Boolean,
        assumeZeroTempAfter: Long,
        currentNow: Long
    ): List<EulerDose> {

        val capacity = ((toTime - fromTime) / 60000L).toInt() + 10
        val timelineMap = HashMap<Long, EulerDose>(capacity)

        fun getOrCreate(t: Long): EulerDose {
            var dose = timelineMap[t]
            if (dose == null) {
                dose = EulerDose(t)
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
                    var activeTb: app.aaps.core.data.model.TB? = null
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

    private data class EulerDose(
        val timestamp: Long,
        var bolusAmt: Double = 0.0,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var profileBasalAmt: Double = 0.0,
        var autoProfileBasalAmt: Double = 0.0
    )

    private data class EulerResult(
        val bolusTotal: IobTotal,
        val extBolusTotal: IobTotal,
        val profileBaselineTotal: IobTotal,
        val basalNetTotal: IobTotal,
        val basalNetAutoTotal: IobTotal
    )

    private object CompartmentModel {
        private const val A0 = 61.33
        private const val A1 = 12.27
        private const val B1 = 0.05185

        fun calculateSystemicPeak(currentScMass: Double, isU200: Boolean): Double {
            val effectiveMass = if (isU200) currentScMass * 2.0 else currentScMass
            return 0.74 * (A0 + A1 * effectiveMass) / (1.0 + B1 * effectiveMass)
        }
    }
}