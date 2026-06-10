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
import app.aaps.core.interfaces.insulin.CompartmentModel

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

    override var ads: AutosensDataStore = AutosensDataStoreObject()

    private val dataLock = Any()
    private var thread: Thread? = null

    override fun onStart() {
        super.onStart()
        // EventConfigBuilderChange
        disposable += rxBus
            .toObservable(EventConfigBuilderChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           resetDataAndRunCalculation("onEventConfigBuilderChange", event)
                       }, fabricPrivacy::logException)
        // EventEffectiveProfileSwitchChanged
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           newHistoryData(event.startDate, false, event)
                       }, fabricPrivacy::logException)
        // EventPreferenceChange
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
        // EventNewHistoryData
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
        val cacheHit = iobTable[time]
        if (time < now && cacheHit != null) {
            //og.debug(">>> calculateFromTreatmentsAndTemps Cache hit " + new Date(time).toLocaleString());
            return cacheHit
        } // else log.debug(">>> calculateFromTreatmentsAndTemps Cache miss " + new Date(time).toLocaleString());
        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = calculateIobToTimeFromTempBasalsIncludingConvertedExtended(time).round()
        // OpenAPSSMB only
        // Add expected zero temp basal for next 240 minutes
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
        val now = dateUtil.now()
        val bolusIob = calculateIobFromBolusToTime(time).round()
        val basalIob = getCalculationToTimeTempBasals(time, lastAutosensResult, exerciseMode, halfBasalExerciseTarget, isTempTarget).round()
        // OpenAPSSMB only
        // Add expected zero temp basal for next 240 minutes
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
            //log.debug(">>> getBasalData Cache miss " + new Date(time).toLocaleString());
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
        } //else log.debug(">>> getBasalData Cache hit " +  new Date(time).toLocaleString());
        return retVal
    }

    override fun getLastAutosensDataWithWaitForCalculationFinish(reason: String): AutosensData? {
        if (thread?.isAlive == true) {
            aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA is waiting for calculation thread: $reason")
            try {
                thread?.join(5000)
            } catch (_: InterruptedException) { // ignore
            }
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
        // Future carbs
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
        // predict IOB out to DIA plus 30m
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
        // predict IOB out to DIA plus 30m
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

    // Limit rate of EventNewHistoryData
    private var historyWorker: ScheduledExecutorService? = null
    private var scheduledHistoryPost: ScheduledFuture<*>? = null
    private var scheduledEvent: EventNewHistoryData? = null

    @Synchronized
    private fun scheduleHistoryDataChange(event: EventNewHistoryData) {
        // if there is nothing scheduled or asking reload deeper to the past
        if (scheduledEvent == null || event.oldDataTimestamp < (scheduledEvent?.oldDataTimestamp ?: 0L)) {
            // cancel waiting task to prevent sending multiple posts
            scheduledHistoryPost?.cancel(false)
            // prepare task for execution in 1 sec
            scheduledEvent?.let {
                // set reload bg data if was not set
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
            // asked reload is newer -> adjust params only
            scheduledEvent?.let {
                // set reload bg data if was not set
                if (!it.reloadBgData) it.reloadBgData = event.reloadBgData
                // set Glucose value if newer
                event.newestGlucoseValueTimestamp?.let { timestamp ->
                    if (timestamp > (it.newestGlucoseValueTimestamp ?: 0L)) it.newestGlucoseValueTimestamp = timestamp
                }
            }
        }
    }

    // When historical data is changed (coming from NS etc) finished calculations after this date must be invalidated
    private fun newHistoryData(oldDataTimestamp: Long, bgDataReload: Boolean, event: Event) {
        //log.debug("Locking onNewHistoryData");
        calculationWorkflow.stopCalculation(CalculationWorkflow.MAIN_CALCULATION, "onEventNewHistoryData")
        synchronized(dataLock) {

            // clear up 5 min back for proper COB calculation
            val time = oldDataTimestamp - 5 * 60 * 1000L
            aapsLogger.debug(LTag.AUTOSENS, "Invalidating cached data to: " + dateUtil.dateAndTimeAndSecondsString(time))
            for (index in iobTable.size() - 1 downTo 0) {
                if (iobTable.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS, "Removing from iobTable: " + dateUtil.dateAndTimeAndSecondsString(iobTable.keyAt(index)))
                    iobTable.removeAt(index)
                } else {
                    break
                }
            }
            for (index in basalDataTable.size() - 1 downTo 0) {
                if (basalDataTable.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS, "Removing from basalDataTable: " + dateUtil.dateAndTimeAndSecondsString(basalDataTable.keyAt(index)))
                    basalDataTable.removeAt(index)
                } else {
                    break
                }
            }
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
        //log.debug("Releasing onNewHistoryData");
    }

    /**
     *  Time range to the past for IOB calculation
     *  @return milliseconds
     */
    private fun range(): Long = ((profileFunction.getProfile()?.dia ?: Constants.defaultDIA) * 60 * 60 * 1000).toLong()

    override fun calculateIobFromBolus(): IobTotal = calculateIobFromBolusToTime(dateUtil.now())

    override fun calculateIobFromBolus(): IobTotal = calculateIobFromBolusToTime(dateUtil.now())

    // --- 1. AAPS OVERRIDE ROUTERS --- 
    // These seamlessly route standard AAPS calls to our centralized physics solver.

    private fun calculateIobFromBolusToTime(toTime: Long): IobTotal {
        return runMasterEulerSolver(toTime, 1.0).bolusTotal
    }

    private fun calculateIobToTimeFromExtendedBoluses(toTime: Long): IobTotal {
        return runMasterEulerSolver(toTime, 1.0).extBolusTotal
    }

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal {
        return runMasterEulerSolver(toTime, 1.0).basalAbsoluteTotal
    }

    override fun calculateIobFromTempBasalsIncludingConvertedExtended(): IobTotal =
        calculateIobToTimeFromTempBasalsIncludingConvertedExtended(dateUtil.now())

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal {
        val res = runMasterEulerSolver(toTime, 1.0)
        
        val pumpInterface = activePlugin.activePump
        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            res.basalNetTotal.plus(res.extBolusTotal) // Fold EB into Basal Net for faked temps
        }
        return res.basalNetTotal
    }

    private fun getCalculationToTimeTempBasals(toTime: Long, lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Double, isTempTarget: Boolean): IobTotal {
        val profile = profileFunction.getProfile(toTime) ?: return IobTotal(toTime)
        var sensitivityRatio = lastAutosensResult.ratio
        val normalTarget = Constants.NORMAL_TARGET_MGDL.toDouble()

        if (exerciseMode && isTempTarget && profile.getTargetMgdl() >= normalTarget + 5) {
            val mgdlHalfBasalExerciseTarget = halfBasalExerciseTarget * if (profile.units.toString() == "mmol/L") app.aaps.core.data.model.GlucoseUnit.MMOLL_TO_MGDL else 1.0
            val c = mgdlHalfBasalExerciseTarget - normalTarget
            sensitivityRatio = c / (c + profile.getTargetMgdl() - normalTarget)
        }

        val res = runMasterEulerSolver(toTime, sensitivityRatio)
        
        val pumpInterface = activePlugin.activePump
        if (pumpInterface.isFakingTempsByExtendedBoluses) {
            res.basalNetAutoTotal.plus(res.extBolusTotal)
        }
        return res.basalNetAutoTotal
    }

    // --- 2. THE MASTER EULER SOLVER ---

    private var eulerCache: MasterEulerState? = null

    private fun runMasterEulerSolver(toTime: Long, sensitivityRatio: Double): EulerResult {
        val diaMs = 8 * 60 * 60 * 1000L 
        var startTime = toTime - diaMs
        
        // State Initialization
        var scBolus = 0.0; var scExtBolus = 0.0; var scBasalAbs = 0.0; var scBasalNet = 0.0; var scBasalNetAuto = 0.0
        var serumBolus = 0.0; var serumExtBolus = 0.0; var serumBasalAbs = 0.0; var serumBasalNet = 0.0; var serumBasalNetAuto = 0.0
        var actBolus = 0.0; var actExtBolus = 0.0; var actBasalAbs = 0.0; var actBasalNet = 0.0; var actBasalNetAuto = 0.0
        var currentTau = 50.0 

        val currentDbModified = persistenceLayer.lastTreatmentModificationTime()

        // Cache Hit Evaluation
        if (eulerCache != null && 
            eulerCache!!.timestamp >= startTime && 
            eulerCache!!.timestamp <= toTime &&    
            eulerCache!!.dbLastModified == currentDbModified &&
            kotlin.math.abs(eulerCache!!.autosensRatio - sensitivityRatio) < 0.001
        ) {
            val c = eulerCache!!
            startTime = c.timestamp
            scBolus = c.scBolus; scExtBolus = c.scExtBolus; scBasalAbs = c.scBasalAbs; scBasalNet = c.scBasalNet; scBasalNetAuto = c.scBasalNetAuto
            serumBolus = c.serumBolus; serumExtBolus = c.serumExtBolus; serumBasalAbs = c.serumBasalAbs; serumBasalNet = c.serumBasalNet; serumBasalNetAuto = c.serumBasalNetAuto
            currentTau = c.currentTau
        }

        val tauE = 63.48 // Hepatic Clearance (44-min half-life)
        val isU200 = activePlugin.activeInsulin.id.value == 205
        val timeline = buildUnifiedEulerTimeline(startTime, toTime, sensitivityRatio)
        var lastTime = startTime

        for (dose in timeline) {
            val tNow = dose.timestamp
            if (tNow <= startTime) continue 

            val deltaMins = (tNow - lastTime) / 60000.0
            if (deltaMins > 0) {
                val scDecay = kotlin.math.exp(-deltaMins / currentTau)
                val serumDecay = kotlin.math.exp(-deltaMins / tauE)

                // Parallel Absorb
                val aBolus = scBolus * (1.0 - scDecay); scBolus *= scDecay
                val aExt = scExtBolus * (1.0 - scDecay); scExtBolus *= scDecay
                val aBasalAbs = scBasalAbs * (1.0 - scDecay); scBasalAbs *= scDecay
                val aBasalNet = scBasalNet * (1.0 - scDecay); scBasalNet *= scDecay
                val aBasalNetAuto = scBasalNetAuto * (1.0 - scDecay); scBasalNetAuto *= scDecay

                // Parallel Serum Clear
                val cBolus = serumBolus * (1.0 - serumDecay); serumBolus = (serumBolus * serumDecay) + aBolus; actBolus = cBolus / deltaMins
                val cExt = serumExtBolus * (1.0 - serumDecay); serumExtBolus = (serumExtBolus * serumDecay) + aExt; actExtBolus = cExt / deltaMins
                val cBasalAbs = serumBasalAbs * (1.0 - serumDecay); serumBasalAbs = (serumBasalAbs * serumDecay) + aBasalAbs; actBasalAbs = cBasalAbs / deltaMins
                val cBasalNet = serumBasalNet * (1.0 - serumDecay); serumBasalNet = (serumBasalNet * serumDecay) + aBasalNet; actBasalNet = cBasalNet / deltaMins
                val cBasalNetAuto = serumBasalNetAuto * (1.0 - serumDecay); serumBasalNetAuto = (serumBasalNetAuto * serumDecay) + aBasalNetAuto; actBasalNetAuto = cBasalNetAuto / deltaMins
            }

            // Parallel Additions
            scBolus += dose.bolusAmt
            scExtBolus += dose.extBolusAmt
            scBasalAbs += dose.basalAbsAmt
            scBasalNet += dose.basalNetAmt
            scBasalNetAuto += dose.basalNetAutoAmt
            lastTime = tNow

            // Physics Update (Driven strictly by TOTAL absolute pool volume)
            val totalSc = scBolus + scExtBolus + scBasalAbs
            val pkPeak = CompartmentModel.calculateSystemicPeak(totalSc, isU200)
            currentTau = CompartmentModel.computeScTau(pkPeak) 
        }

        // Final Time Gap Check to Present Moment
        val finalDeltaMins = (toTime - lastTime) / 60000.0
        if (finalDeltaMins > 0) {
            val scDecay = kotlin.math.exp(-finalDeltaMins / currentTau)
            val serumDecay = kotlin.math.exp(-finalDeltaMins / tauE)

            val aBolus = scBolus * (1.0 - scDecay); scBolus *= scDecay
            val aExt = scExtBolus * (1.0 - scDecay); scExtBolus *= scDecay
            val aBasalAbs = scBasalAbs * (1.0 - scDecay); scBasalAbs *= scDecay
            val aBasalNet = scBasalNet * (1.0 - scDecay); scBasalNet *= scDecay
            val aBasalNetAuto = scBasalNetAuto * (1.0 - scDecay); scBasalNetAuto *= scDecay

            val cBolus = serumBolus * (1.0 - serumDecay); serumBolus = (serumBolus * serumDecay) + aBolus; actBolus = cBolus / finalDeltaMins
            val cExt = serumExtBolus * (1.0 - serumDecay); serumExtBolus = (serumExtBolus * serumDecay) + aExt; actExtBolus = cExt / finalDeltaMins
            val cBasalAbs = serumBasalAbs * (1.0 - serumDecay); serumBasalAbs = (serumBasalAbs * serumDecay) + aBasalAbs; actBasalAbs = cBasalAbs / finalDeltaMins
            val cBasalNet = serumBasalNet * (1.0 - serumDecay); serumBasalNet = (serumBasalNet * serumDecay) + aBasalNet; actBasalNet = cBasalNet / finalDeltaMins
            val cBasalNetAuto = serumBasalNetAuto * (1.0 - serumDecay); serumBasalNetAuto = (serumBasalNetAuto * serumDecay) + aBasalNetAuto; actBasalNetAuto = cBasalNetAuto / finalDeltaMins
            lastTime = toTime
        }

        // Cache Preservation
        if (kotlin.math.abs(dateUtil.now() - toTime) < 5 * 60000L) {
            eulerCache = MasterEulerState(lastTime, scBolus, scExtBolus, scBasalAbs, scBasalNet, scBasalNetAuto, serumBolus, serumExtBolus, serumBasalAbs, serumBasalNet, serumBasalNetAuto, currentTau, currentDbModified, sensitivityRatio)
        }

        // Object Mapping for AAPS Returns
        fun mapToTotal(iobVal: Double, actVal: Double, mapToNetBasal: Boolean = false, mapToHighTemp: Boolean = false): IobTotal {
            val t = IobTotal(toTime)
            t.iob = iobVal
            t.activity = actVal
            t.basaliob = if (mapToNetBasal) iobVal else 0.0
            t.netbasalinsulin = if (mapToNetBasal) iobVal else 0.0
            t.extendedBolusInsulin = if (mapToHighTemp) iobVal else 0.0
            t.hightempinsulin = if (mapToHighTemp) iobVal else 0.0
            return t
        }

        return EulerResult(
            bolusTotal = mapToTotal(serumBolus, actBolus),
            extBolusTotal = mapToTotal(serumExtBolus, actExtBolus, mapToNetBasal = true, mapToHighTemp = true),
            basalAbsoluteTotal = mapToTotal(serumBasalAbs, actBasalAbs, mapToNetBasal = true),
            basalNetTotal = mapToTotal(serumBasalNet, actBasalNet, mapToNetBasal = true),
            basalNetAutoTotal = mapToTotal(serumBasalNetAuto, actBasalNetAuto, mapToNetBasal = true)
        )
    }

    private fun buildUnifiedEulerTimeline(fromTime: Long, toTime: Long, sensitivityRatio: Double): List<EulerDose> {
        val timelineMap = mutableMapOf<Long, EulerDose>()
        fun getOrCreate(t: Long) = timelineMap.getOrPut(t) { EulerDose(t) }

        // 1. Boluses
        persistenceLayer.getBolusesFromTime(fromTime, true).blockingGet()
            .filter { it.isValid && it.timestamp <= toTime }
            .forEach { getOrCreate(it.timestamp).bolusAmt += it.amount }

        // 2. Extended Boluses
        val pumpInterface = activePlugin.activePump
        if (!pumpInterface.isFakingTempsByExtendedBoluses) {
            val ebs = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(fromTime, toTime, true)
            val now = dateUtil.now()
            ebs.forEach { e ->
                if (e.timestamp <= toTime) {
                    var dur = e.duration
                    var amt = e.amount
                    if (e.end > now) {
                        dur = now - e.timestamp
                        amt *= dur.toDouble() / e.duration
                    }
                    val durMins = dur / 60000.0
                    if (durMins > 0) {
                        val rate = amt / durMins
                        val intervals = kotlin.math.ceil(durMins / 5.0).toInt()
                        val spacing = durMins / intervals
                        val vol = rate * spacing
                        for (j in 0 until intervals) {
                            val chunkTime = (e.timestamp + j * spacing * 60000 + 0.5 * spacing * 60000).toLong()
                            if (chunkTime in fromTime..toTime) getOrCreate(chunkTime).extBolusAmt += vol
                        }
                    }
                }
            }
        }

        // 3. Basals
        var bTime = fromTime
        while (bTime <= toTime) {
            val profile = profileFunction.getProfile(bTime)
            if (profile != null) {
                val absoluteRate = getBasalData(profile, bTime).tempBasalAbsolute
                val absAmt = absoluteRate * (5.0 / 60.0)
                
                val profileRate = profile.getBasal(bTime)
                val netAmt = absAmt - (profileRate * 5.0 / 60.0)
                
                val autoProfileRate = profileRate * sensitivityRatio
                val netAutoAmt = absAmt - (autoProfileRate * 5.0 / 60.0)

                val dose = getOrCreate(bTime)
                dose.basalAbsAmt += absAmt
                dose.basalNetAmt += netAmt
                dose.basalNetAutoAmt += netAutoAmt
            }
            bTime += 5 * 60000L
        }

        return timelineMap.values.sortedBy { it.timestamp }
    }

    // --- STATE TRACKERS ---
    
    private data class EulerDose(
        val timestamp: Long,
        var bolusAmt: Double = 0.0,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var basalNetAmt: Double = 0.0,
        var basalNetAutoAmt: Double = 0.0
    )

    private data class EulerResult(
        val bolusTotal: IobTotal,
        val extBolusTotal: IobTotal,
        val basalAbsoluteTotal: IobTotal,
        val basalNetTotal: IobTotal,
        val basalNetAutoTotal: IobTotal
    )

    private data class MasterEulerState(
        val timestamp: Long,
        val scBolus: Double, val scExtBolus: Double, val scBasalAbs: Double, val scBasalNet: Double, val scBasalNetAuto: Double,
        val serumBolus: Double, val serumExtBolus: Double, val serumBasalAbs: Double, val serumBasalNet: Double, val serumBasalNetAuto: Double,
        val currentTau: Double, val dbLastModified: Long, val autosensRatio: Double
    )
}