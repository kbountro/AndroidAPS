package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.OneMinuteCurve
import app.aaps.core.objects.extensions.isOneMinuteRawData
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class LoadBgDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var preferences: Preferences

    class LoadBgData(
        val iobCobCalculator: IobCobCalculator,
        val end: Long
    )

    private fun AutosensDataStore.loadBgData(to: Long, persistenceLayer: PersistenceLayer, aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        synchronized(dataLock) {
            val start = to - T.hours((24 + 10 /* max dia */).toLong()).msecs()
            // there can be some readings with time in close future (caused by wrong time setting on sensor)
            // so add 2 minutes
            bgReadings = persistenceLayer
                .getBgReadingsDataFromTimeToTime(start, to + T.mins(2).msecs(), false)
            aapsLogger.debug(LTag.AUTOSENS) { "BG data loaded. Size: ${bgReadings.size} Start date: ${dateUtil.dateAndTimeString(start)} End date: ${dateUtil.dateAndTimeString(to)}" }
            createBucketedData(aapsLogger, dateUtil)
        }
    }

    // One smooth curve for everything. With a CGM that AutoISF treats as 1-minute data, every reading is
    // smoothed (OneMinuteCurve: the last 2 hours anew, older values kept from earlier loads), AutoISF reads
    // that curve, the graph shows it, and the 5-minute grid of autosens, COB and the deltas takes its
    // smoothed values from it. The grid stays 5-minute, because autosens and COB count its points as
    // 5-minute steps. Without such a CGM the grid itself is smoothed, as before. Called with dataLock held.
    private fun AutosensDataStore.smoothData(activePlugin: ActivePlugin, preferences: Preferences) {
        val readings = bgReadings
        val curve =
            if (readings.isOneMinuteRawData { preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw) })
                OneMinuteCurve.smooth(readings, smoothedReadings, activePlugin.activeSmoothing)
            else null
        val grid = bucketedData
        if (grid != null) {
            if (curve != null) OneMinuteCurve.sampleOnto(grid, curve)
            else bucketedData = activePlugin.activeSmoothing.smooth(grid)
        }
        smoothedReadings = curve
    }

    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as LoadBgData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        // One locked step, so nobody reads the new readings before their smoothed values exist. Smoothing
        // two hours of readings takes about a millisecond; only the first load smooths them all.
        val ads = data.iobCobCalculator.ads
        synchronized(ads.dataLock) {
            ads.loadBgData(data.end, persistenceLayer, aapsLogger, dateUtil)
            ads.smoothData(activePlugin, preferences)
        }
        rxBus.send(EventBucketedDataCreated())
        data.iobCobCalculator.clearCache()
        return Result.success()
    }
}