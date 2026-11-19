package app.aaps.core.interfaces.aps

import androidx.collection.LongSparseArray
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil

interface AutosensDataStore {

    val dataLock: Any

    var bgReadings: List<GV>
    var autosensDataTable: LongSparseArray<AutosensData>
    var bucketedData: MutableList<InMemoryGlucoseValue>?

    /**
     * The one smoothed curve of a CGM that AutoISF treats as 1-minute data: every reading, smoothed by the
     * active smoothing plugin, newest first; null for other CGMs. LoadBgDataWorker makes it at each data
     * load (OneMinuteCurve) and publishes it together with [bgReadings] and [bucketedData], whose smoothed
     * values come from it. AutoISF and the graph read it directly. [bucketedData] stays a 5-minute grid,
     * because autosens and COB count its points as 5-minute steps.
     */
    var smoothedReadings: List<InMemoryGlucoseValue>?
    var lastUsed5minCalculation: Boolean?

    /**
     * Return last valid (>39) InMemoryGlucoseValue from bucketed data or null if db is empty
     *
     * @return InMemoryGlucoseValue or null
     */
    fun lastBg(): InMemoryGlucoseValue?

    /**
     * Provide last bucketed InMemoryGlucoseValue or null if none exists within the last 9 minutes
     *
     * @return InMemoryGlucoseValue or null
     */
    fun actualBg(): InMemoryGlucoseValue?
    fun lastDataTime(dateUtil: DateUtil): String
    fun clone(): AutosensDataStore
    fun getBgReadingsDataTableCopy(): List<GV>
    fun getLastAutosensData(reason: String, aapsLogger: AAPSLogger, dateUtil: DateUtil): AutosensData?
    fun getAutosensDataAtTime(fromTime: Long): AutosensData?
    fun getBucketedDataTableCopy(): MutableList<InMemoryGlucoseValue>?
    fun getSmoothedReadingsCopy(): List<InMemoryGlucoseValue>?
    fun createBucketedData(aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun slowAbsorptionPercentage(timeInMinutes: Int): Double
    fun newHistoryData(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun roundUpTime(time: Long): Long
    fun reset()
}