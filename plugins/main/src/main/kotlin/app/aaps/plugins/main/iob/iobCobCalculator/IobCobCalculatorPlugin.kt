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
    private var eulerCache = HashMap<String, EulerResult>()

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
            eulerCache.clear()
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
            eulerCache.clear()
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

    /**
     * Calculate IobTotal from boluses and extended to provided timestamp.
     * NOTE: Only isValid == true boluses are included
     * NOTE: if faking by TBR by extended boluses is enabled, extended boluses are not included
     *  and are calculated towards temporary basals
     *
     * @param toTime timestamp in milliseconds
     * @return calculated iob
     */
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
            // Convert to basal iob
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
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob
            totalExt.iob = 0.0
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin
            total.plus(totalExt)
        }
        return total
    }

    private fun getCachedOrRunEulerSolver(toTime: Long, sensitivityRatio: Double): EulerResult {
        val key = "${toTime}_${sensitivityRatio}"
        synchronized(dataLock) {
            eulerCache[key]?.let { return it }
        }
        val result = runMasterEulerSolver(toTime, sensitivityRatio)
        synchronized(dataLock) {
            eulerCache[key] = result
        }
        return result
    }

    // =========================================================================
    // THE MASTER EULER SOLVER (THE "TRAFFIC JAM" ENGINE)
    // 1. One-Way Capped Time-Warp (Prevents upward Activity spikes).
    // 2. Dual SC Puddles (Separates physical accumulation from theoretical baseline).
    // 3. No-Tail Subtraction (Guarantees injected mass is strictly preserved).
    // 4. Accurate AMA Snooze tracking mapping to original AAPS standard.
    // =========================================================================
    private fun runMasterEulerSolver(toTime: Long, sensitivityRatio: Double): EulerResult {
        val diaMs = 8 * 60 * 60 * 1000L
        val startTime = toTime - diaMs
        val isU200 = activePlugin.activeInsulin.id.value == 205

        // EFFICIENCY FIX: Fetch heavy DB records strictly ONCE per calculation and pass them to memory
        val boluses = persistenceLayer.getBolusesFromTime(startTime, true).blockingGet()
        val tbs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(startTime, toTime, true)
        val ebs = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(startTime, toTime, true)

        val pumpInterface = activePlugin.activePump
        val isFakingTemps = pumpInterface.isFakingTempsByExtendedBoluses

        val capacity = ((toTime - startTime) / 300000L).toInt() + 10
        val timelineMap = HashMap<Long, EulerDose>(capacity)

        fun getOrCreate(t: Long): EulerDose {
            var dose = timelineMap[t]
            if (dose == null) {
                dose = EulerDose(t)
                timelineMap[t] = dose
            }
            return dose
        }

        val upperLimit = 8.0 * 60.0 // 8 hours hard cap
        val maxWarpWindow = 4.0 * 60.0 // 4 hours maximum time-warp constraint

        // AMA Bolus Snooze
        var bSnooze = 0.0
        var lastBolusTime = 0L
        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        for (b in boluses) {
            if (b.isValid && b.timestamp in startTime..toTime) {
                getOrCreate(b.timestamp).bolusAmt += b.amount
                if (b.amount > 0 && b.timestamp > lastBolusTime) {
                    lastBolusTime = b.timestamp
                }
                if (b.type != BS.Type.SMB) {
                    val timeSinceTreatment = toTime - b.timestamp
                    val snoozeTime = b.timestamp + (timeSinceTreatment * divisor).toLong()
                    val tSnoozeElapsed = (toTime - snoozeTime) / 60000.0

                    if (tSnoozeElapsed in 0.0..upperLimit) {
                        val snoozePeak = CompartmentModel.calculateSystemicPeak(b.amount, isU200)

                        // EFFICIENCY FIX: Replaced expensive Math.pow(x, 2) with pure multiplication
                        val pDiv = snoozePeak / 0.41
                        val snoozeTpModelPD = 2.0 * (pDiv * pDiv)
                        val tSq = tSnoozeElapsed * tSnoozeElapsed
                        val snoozeIobContrib = exp(-tSq / snoozeTpModelPD)
                        bSnooze += b.amount * snoozeIobContrib
                    }
                }
            }
        }

        val now = dateUtil.now()

        for (e in ebs) {
            if (e.timestamp <= toTime && e.timestamp >= startTime) {
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
                        if (chunkTime in startTime..toTime) {
                            if (isFakingTemps) {
                                getOrCreate(chunkTime).basalAbsAmt += vol
                            } else {
                                getOrCreate(chunkTime).extBolusAmt += vol
                            }
                        }
                    }
                }
            }
        }

        // EFFICIENCY FIX: Build Basal timeline using pure In-Memory iteration (0 Database Queries)
        var bTime = startTime
        while (bTime <= toTime) {
            val profile = profileFunction.getProfile(bTime)
            if (profile != null) {
                val activeTb = tbs.find { it.timestamp <= bTime && (it.timestamp + it.duration) > bTime }

                val absoluteRate = if (activeTb != null) {
                    activeTb.convertedToAbsolute(bTime, profile)
                } else {
                    profile.getBasal(bTime)
                }
                val absAmt = absoluteRate * (5.0 / 60.0)

                val profileRate = profile.getBasal(bTime)
                val profAmt = profileRate * (5.0 / 60.0)
                val autoProfAmt = (profileRate * sensitivityRatio) * (5.0 / 60.0)

                val dose = getOrCreate(bTime)
                dose.basalAbsAmt += absAmt
                dose.profileBasalAmt += profAmt
                dose.autoProfileBasalAmt += autoProfAmt
            }
            bTime += 5 * 60000L
        }

        val timeline = timelineMap.values.sortedBy { it.timestamp }

        // EFFICIENCY FIX: Pre-allocate capacity to prevent GC memory resizing churn
        val activeCurves = ArrayList<TsunamiCurve>(capacity * 2)

        // 1. The Physical Reality Puddle
        var globalPhysicalSC = 0.0
        var globalTauSC = 50.0

        // 2. The Theoretical Baseline Puddle
        var globalTheoreticalSC = 0.0
        var globalTauTheoreticalSC = 50.0

        var lastTime = startTime

        for (dose in timeline) {
            val dtMins = (dose.timestamp - lastTime) / 60000.0

            if (dtMins > 0) {
                globalPhysicalSC *= exp(-dtMins / globalTauSC)
                globalTheoreticalSC *= exp(-dtMins / globalTauTheoreticalSC)
            }
            lastTime = dose.timestamp

            // ======================================================
            // A. UPDATE PHYSICAL REALITY (Bolus + TBRs)
            // ======================================================
            val physicalInjected = dose.bolusAmt + dose.extBolusAmt + dose.basalAbsAmt
            if (physicalInjected > 0.0) {
                globalPhysicalSC += physicalInjected

                val pkPeak = CompartmentModel.calculateSystemicPeak(globalPhysicalSC, isU200)
                val pDiv = pkPeak / 0.41
                val newTpModelPD = 2.0 * (pDiv * pDiv)
                globalTauSC = computeScTau(pkPeak)

                // The One-Way Valve (Physical Only)
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

            // ======================================================
            // B. UPDATE THEORETICAL BASELINE (Scheduled Profile)
            // ======================================================
            if (dose.profileBasalAmt > 0.0 || dose.autoProfileBasalAmt > 0.0) {
                globalTheoreticalSC += dose.profileBasalAmt

                val theoPeak = CompartmentModel.calculateSystemicPeak(globalTheoreticalSC, isU200)
                val tDiv = theoPeak / 0.41
                val theoTpModelPD = 2.0 * (tDiv * tDiv)
                globalTauTheoreticalSC = computeScTau(theoPeak)

                // The One-Way Valve (Theoretical Only)
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

        // ==========================================================
        // FINAL AGGREGATION
        // ==========================================================
        var bIob = 0.0; var bAct = 0.0; var eIob = 0.0; var eAct = 0.0
        var baIob = 0.0; var baAct = 0.0; var pIob = 0.0; var pAct = 0.0; var apIob = 0.0; var apAct = 0.0

        for (curve in activeCurves) {
            val t = (toTime - curve.timestamp) / 60000.0
            if (t !in 0.0..upperLimit) continue

            val tSq = t * t
            val safeIobContrib = exp(-tSq / curve.tpModelPD)
            val activity = (2.0 / curve.tpModelPD) * t * safeIobContrib

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
        val tauE = 63.48 // Hepatic Clearance (44-min half-life)
        var low = 1.0
        var high = 300.0
        var mid = 150.0

        repeat(15) {
            mid = (low + high) / 2.0

            val currentTp = if (abs(mid - tauE) < 0.1) {
                mid
            } else {
                (mid * tauE / (tauE - mid)) * ln(tauE / mid)
            }

            if (currentTp > targetTp) {
                high = mid
            } else {
                low = mid
            }
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
            return 0.41 * (A0 + A1 * effectiveMass) / (1.0 + B1 * effectiveMass)
        }
    }
}