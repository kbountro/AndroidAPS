package app.aaps.plugins.smoothing

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.smoothing.Smoothing
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Adaptive Unscented Kalman Filter with RTS smoothing.
 * Backported for AAPS 3.4.2.x structure using AAPS 4.0.0.0 math/logic.
 */
@Singleton
class UnscentedKalmanFilterPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val persistenceLayer: PersistenceLayer,
    private val sp: SP
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.SMOOTHING)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_timeline_24)
        .pluginName(R.string.UKF_name)
        .shortName(R.string.smoothing_shortname)
        .description(R.string.description_UKF),
    aapsLogger, rh
), Smoothing {

    // ============================================================
    // UKF CONFIGURATION
    // ============================================================

    // State dimension.
    private val n = 2

    // UKF parameters (Merwe's scaled formulation).
    private val alpha = 0.1
    private val beta = 2.0
    private val kappa = 0.0

    // Derived parameters.
    private val lambda = alpha * alpha * (n + kappa) - n
    private val gamma = sqrt(n + lambda)

    // Sigma point weights.
    private val wm = DoubleArray(2 * n + 1)
    private val wc = DoubleArray(2 * n + 1)

    // FIXED process noise covariances - tuned for realistic glucose dynamics.
    private val q = doubleArrayOf(
        1.0, 0.0,     // Glucose process noise
        0.0, 0.35     // Rate process noise
    )

    // Initial measurement noise.
    private val rInit = 25.0

    // Adaptive R bounds (variance, mg/dL^2).
    private val rMin = 16.0
    private val rMax = 225.0
    private val rEffMax = 400.0

    // R adaptation window length for innovation statistics.
    private val innovationWindow = 18

    // Chi-squared based outlier detection (99.99% confidence, 1 DOF).
    private val chiSquaredThreshold = 15.13
    private val outlierAbsolute = 65.0

    // Covariance limits.
    private val maxGlucoseVariance = 400.0
    private val maxRateVariance = 4.0

    // Innovation-based validation - detect parameter corruption.
    private val innovationResetThreshold = 12.0
    private val innovationValidationSamples = 15

    // Gap handling.
    private val minorGapThreshold = 7.0
    private val majorGapThreshold = 60.0
    private val rateDecayTimeConstant = 30.0

    private val millisPerMinute = 1000.0 * 60.0

    private fun rateDamp(dt: Double): Double = exp(-dt / rateDecayTimeConstant)

    // ============================================================
    // DATA STRUCTURES
    // ============================================================

    private data class DataSegment(
        val startIdx: Int,
        val endIdx: Int
    )

    private data class FilterState(
        val x: DoubleArray,
        val p: DoubleArray,
        val xPred: DoubleArray,
        val pPred: DoubleArray,
        val dt: Double
    )

    // ============================================================
    // PERSISTENT STATE
    // ============================================================

    private var learnedR = rInit

    private val innovations = ArrayDeque<Double>(innovationWindow + 1)
    private val rawInnovationVariance = ArrayDeque<Double>(innovationWindow + 1)
    private val predVarHistory = ArrayDeque<Double>(innovationWindow + 1)

    private var lastProcessedTimestamp: Long = 0
    private var lastSensorChangeTimestamp: Long = 0
    private var sensorSessionId: Int = 0
    private var sessionMeasurementCount: Long = 0
    private var sessionOutlierCount: Long = 0

    private var consecutiveOutliers = 0

    // Event system (RxJava for 3.4.x)
    private val resetRequested = AtomicBoolean(false)
    private val disposable = CompositeDisposable()
    private val sensorChangeDisposables = CompositeDisposable()

    // ============================================================
    // INITIALIZATION
    // ============================================================

    init {
        wm[0] = lambda / (n + lambda)
        wc[0] = lambda / (n + lambda) + (1 - alpha * alpha + beta)
        val w = 1.0 / (2.0 * (n + lambda))
        for (i in 1 until 2 * n + 1) {
            wm[i] = w
            wc[i] = w
        }

        loadPersistedParameters()
        subscribeToSensorChanges()
        loadLastSensorChange()
    }

    // ============================================================
    // PARAMETER PERSISTENCE
    // ============================================================

    private fun loadPersistedParameters() {
        try {
            val lastSaved = sp.getLong("ukf_last_saved_timestamp", 0L)
            val savedSensorChange = sp.getLong("ukf_sensor_change_timestamp", 0L)

            if (lastSaved > 0) {
                lastSensorChangeTimestamp = savedSensorChange
                lastProcessedTimestamp = sp.getLong("ukf_last_processed_timestamp", 0L)
                learnedR = sp.getDouble("ukf_learned_r", rInit)
                sensorSessionId = sp.getInt("ukf_session_id", 0)

                if (learnedR !in rMin..rMax) {
                    aapsLogger.info(LTag.GLUCOSE, "UKF: Loaded R ($learnedR) out of bounds, resetting to R_INIT")
                    learnedR = rInit
                }

                aapsLogger.info(
                    LTag.GLUCOSE,
                    "UKF: Loaded session $sensorSessionId " +
                        "(R=${String.format(Locale.US, "%.1f", learnedR)}, " +
                        "Q_glucose=${String.format(Locale.US, "%.2f", q[0])} [FIXED], " +
                        "Q_rate=${String.format(Locale.US, "%.4f", q[3])} [FIXED])"
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.GLUCOSE, "UKF: Failed to load persisted parameters", e)
            learnedR = rInit
        }
    }

    private fun savePersistedParameters() {
        try {
            sp.putLong("ukf_last_saved_timestamp", System.currentTimeMillis())
            sp.putLong("ukf_sensor_change_timestamp", lastSensorChangeTimestamp)
            sp.putLong("ukf_last_processed_timestamp", lastProcessedTimestamp)
            sp.putDouble("ukf_learned_r", learnedR)
            sp.putInt("ukf_session_id", sensorSessionId)

            aapsLogger.debug(LTag.GLUCOSE, "UKF: Saved learned R for session $sensorSessionId")
        } catch (e: Exception) {
            aapsLogger.error(LTag.GLUCOSE, "UKF: Failed to save persisted parameters", e)
        }
    }

    // ============================================================
    // SENSOR CHANGE DETECTION (RxJava 3.4.2 structure)
    // ============================================================

    private fun subscribeToSensorChanges() {
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           checkForSensorChange()
                       }, { throwable ->
                           aapsLogger.error(LTag.GLUCOSE, "UKF: Error subscribing to therapy events", throwable)
                       })
    }

    private fun loadLastSensorChange() {
        sensorChangeDisposables.clear()
        sensorChangeDisposables += persistenceLayer
            .getTherapyEventDataFromTime(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000, false)
            .observeOn(aapsSchedulers.io)
            .subscribe({ therapyEvents ->
                           val latestSensorChange = therapyEvents
                               .filter { it.type == TE.Type.SENSOR_CHANGE }
                               .maxByOrNull { it.timestamp }

                           latestSensorChange?.let { sensorChange ->
                               if (sensorChange.timestamp > lastSensorChangeTimestamp) {
                                   aapsLogger.info(LTag.GLUCOSE, "UKF: Detected sensor change at ${sensorChange.timestamp}")
                                   lastSensorChangeTimestamp = sensorChange.timestamp

                                   if (lastProcessedTimestamp > 0 && sensorChange.timestamp > lastProcessedTimestamp) {
                                       aapsLogger.info(LTag.GLUCOSE, "UKF: Sensor changed after last processing, scheduling learning reset")
                                       resetRequested.set(true)
                                   }
                               }
                           }
                       }, { throwable ->
                           aapsLogger.error(LTag.GLUCOSE, "UKF: Error loading sensor change history", throwable)
                       })
    }

    private fun checkForSensorChange() {
        sensorChangeDisposables.clear()
        sensorChangeDisposables += persistenceLayer
            .getTherapyEventDataFromTime(lastSensorChangeTimestamp, false)
            .observeOn(aapsSchedulers.io)
            .subscribe({ therapyEvents ->
                           val newSensorChanges = therapyEvents
                               .filter { it.type == TE.Type.SENSOR_CHANGE && it.timestamp > lastSensorChangeTimestamp }

                           if (newSensorChanges.isNotEmpty()) {
                               val latestChange = newSensorChanges.maxByOrNull { it.timestamp }!!
                               aapsLogger.info(LTag.GLUCOSE, "UKF: New sensor change at ${latestChange.timestamp}")
                               lastSensorChangeTimestamp = latestChange.timestamp
                               resetRequested.set(true)
                           }
                       }, { throwable ->
                           aapsLogger.error(LTag.GLUCOSE, "UKF: Error checking for sensor changes", throwable)
                       })
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.info(LTag.GLUCOSE, "UKF: Cleaning up RxBus subscriptions")
        disposable.clear()
        sensorChangeDisposables.clear()
    }

    // ============================================================
    // RESET LOGIC
    // ============================================================

    private fun shouldResetLearning(currentTimestamp: Long): Boolean {
        if (resetRequested.getAndSet(false)) {
            aapsLogger.info(LTag.GLUCOSE, "UKF: Learning reset requested by sensor change event")
            return true
        }

        if (lastProcessedTimestamp == 0L) {
            aapsLogger.info(LTag.GLUCOSE, "UKF: First call, initializing learning")
            return true
        }

        val timeDiffMinutes = (currentTimestamp - lastProcessedTimestamp) / millisPerMinute

        if (timeDiffMinutes < 0) {
            aapsLogger.info(LTag.GLUCOSE, "UKF: Timestamp went backwards, resetting learning")
            return true
        }

        if (timeDiffMinutes > 1440.0) {
            aapsLogger.info(LTag.GLUCOSE, "UKF: Very large gap (${timeDiffMinutes.toInt()} min), resetting")
            return true
        }

        if (innovations.size >= innovationValidationSamples) {
            val avgInnovation = innovations.average()
            if (avgInnovation > innovationResetThreshold) {
                aapsLogger.info(
                    LTag.GLUCOSE,
                    "UKF: Severely mis-tuned parameters (avg innovation: ${String.format(Locale.US, "%.1f", avgInnovation)}), " +
                        "resetting (R was ${String.format(Locale.US, "%.1f", learnedR)})"
                )
                return true
            }
        }
        return false
    }

    private fun resetLearning() {
        learnedR = rInit
        innovations.clear()
        rawInnovationVariance.clear()
        predVarHistory.clear()
        sensorSessionId++
        sessionMeasurementCount = 0
        sessionOutlierCount = 0
        consecutiveOutliers = 0

        aapsLogger.info(
            LTag.GLUCOSE,
            "UKF: Learning reset complete (session $sensorSessionId, R=${String.format(Locale.US, "%.1f", learnedR)})"
        )
        savePersistedParameters()
    }

    // ============================================================
    // MAIN FILTERING API
    // ============================================================

    override fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
        if (data.isEmpty()) return data
        try {
            return smoothInternal(data)
        } catch (e: Exception) {
            aapsLogger.error(LTag.GLUCOSE, "UKF: Error during smoothing, falling back to raw values", e)
            copyRawToSmoothed(data)
            return data
        }
    }

    private fun findDataSegments(data: List<InMemoryGlucoseValue>): List<DataSegment> {
        if (data.size < 2) return emptyList()
        val segments = mutableListOf<DataSegment>()
        var segmentStart = 0

        for (i in 0 until data.size - 1) {
            val timeDiff = (data[i].timestamp - data[i + 1].timestamp) / millisPerMinute
            if (timeDiff !in 2.0..majorGapThreshold || data[i].value == 38.0) {
                if (i - segmentStart >= 2) {
                    segments.add(DataSegment(segmentStart, i))
                }
                segmentStart = i + 1
            }
        }
        if (data.size - segmentStart >= 2) {
            segments.add(DataSegment(segmentStart, data.size - 1))
        }
        return segments
    }

    private fun smoothInternal(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
        if (shouldResetLearning(data[0].timestamp)) {
            resetLearning()
        }

        val segments = findDataSegments(data)

        if (segments.isEmpty()) {
            copyRawToSmoothed(data)
            return data
        }

        val previousTimestamp = lastProcessedTimestamp
        lastProcessedTimestamp = data[0].timestamp

        for ((idx, segment) in segments.withIndex()) {
            processSegment(data, segment.startIdx, segment.endIdx, previousTimestamp)
        }

        for (i in data.indices) {
            if (data[i].smoothed == 0.0) {
                data[i].smoothed = max(data[i].value, 39.0)
                data[i].trendArrow = TrendArrow.NONE
            }
        }

        if (sessionMeasurementCount % 100 == 0L && sessionMeasurementCount > 0) {
            val sessionOutlierRate = sessionOutlierCount.toDouble() / sessionMeasurementCount
            val avgInnovation = if (innovations.isNotEmpty()) innovations.average() else 0.0
            aapsLogger.info(
                LTag.GLUCOSE,
                "UKF: Session $sensorSessionId, $sessionMeasurementCount measurements, " +
                    "R=${String.format(Locale.US, "%.1f", learnedR)} [ADAPTIVE], " +
                    "AvgInnovation=${String.format(Locale.US, "%.2f", avgInnovation)}, " +
                    "OutlierRate=${String.format(Locale.US, "%.1f%%", sessionOutlierRate * 100)}"
            )
        }

        val newDataProcessed = data.any { it.timestamp > previousTimestamp }
        if (newDataProcessed) {
            savePersistedParameters()
        }

        return data
    }

    private fun processSegment(
        data: MutableList<InMemoryGlucoseValue>,
        startIdx: Int,
        endIdx: Int,
        previousTimestamp: Long
    ) {
        val segmentSize = endIdx - startIdx + 1
        if (segmentSize < 2) {
            data[startIdx].smoothed = max(data[startIdx].value, 39.0)
            data[startIdx].trendArrow = TrendArrow.NONE
            return
        }

        val initialGlucose = data[endIdx].value
        var initialRate = 0.0

        if (endIdx > 0) {
            val dt = (data[endIdx - 1].timestamp - data[endIdx].timestamp) / millisPerMinute
            if (dt in 3.0..7.0) {
                initialRate = (data[endIdx - 1].value - data[endIdx].value) / dt
                initialRate = initialRate.coerceIn(-4.0, 4.0)
            }
        }

        val x = doubleArrayOf(initialGlucose, initialRate)
        val p = doubleArrayOf(16.0, 0.0, 0.0, 1.0)
        var r = learnedR

        val forwardStates = ArrayDeque<FilterState>(segmentSize)
        val forwardResults = DoubleArray(segmentSize)
        forwardResults[segmentSize - 1] = x[0]

        var segmentNewMeasurements = 0
        var segmentOutliers = 0
        val recentSigns = ArrayDeque<Int>(3)

        for (i in (endIdx - 1) downTo startIdx) {
            val dt = (data[i].timestamp - data[i + 1].timestamp) / millisPerMinute

            if (dt > minorGapThreshold && dt <= majorGapThreshold) {
                x[1] *= rateDamp(dt)
            }

            p[0] = p[0].coerceIn(0.1, maxGlucoseVariance)
            p[3] = p[3].coerceIn(0.001, maxRateVariance)

            val dtUsed = dt
            val (xPredBase, pPredBase) = predict(x, p, q, dtUsed)

            // Replaced .calibratedOrValue with .value to match 3.4.2 model
            val z = data[i].value

            if (z <= 38.0) {
                val stateBefore = FilterState(x.copyOf(), p.copyOf(), xPredBase.copyOf(), pPredBase.copyOf(), dtUsed)
                x[0] = xPredBase[0]
                x[1] = xPredBase[1]
                p[0] = pPredBase[0]
                p[1] = pPredBase[1]
                p[2] = pPredBase[2]
                p[3] = pPredBase[3]
                val resultIdx = i - startIdx
                forwardResults[resultIdx] = x[0]
                forwardStates.addFirst(stateBefore)
                continue
            }

            val innovation = z - xPredBase[0]
            val innovationVarianceRaw = pPredBase[0] + r
            val stdRaw = sqrt(innovationVarianceRaw)
            val normRaw = innovation / stdRaw
            val isNewData = data[i].timestamp > previousTimestamp

            val sign = when {
                normRaw > 0.0 -> 1
                normRaw < 0.0 -> -1
                else -> 0
            }

            if (recentSigns.size == 3) recentSigns.removeLast()
            recentSigns.addFirst(if (abs(normRaw) > 2.0) sign else 0)
            val sameSignCount = if (sign == 0) 0 else recentSigns.count { it == sign }
            val qInflateAllowed = sameSignCount >= 2

            val absn = abs(normRaw)
            val rScale = 1.0 + max(0.0, absn - 2.0)
            val rEff = min(r * rScale, min(r + 100.0, rEffMax))

            val zScore = absn.coerceAtLeast(1.0)
            val qScale = if (qInflateAllowed) zScore.coerceIn(1.0, 3.0) else 1.0
            val tempQ = if (qScale > 1.0) {
                q.copyOf().apply {
                    this[0] = q[0] * min(qScale, 2.0)
                    this[3] = q[3] * qScale
                }
            } else {
                q
            }

            val (xPredEff, pPredEff) = if (qScale > 1.0) predict(x, p, tempQ, dtUsed) else Pair(xPredBase, pPredBase)
            val stateBefore = FilterState(x.copyOf(), p.copyOf(), xPredEff.copyOf(), pPredEff.copyOf(), dtUsed)

            val innovationVarianceEff = pPredEff[0] + rEff
            val mahalSqEff = (innovation * innovation) / innovationVarianceEff

            predVarHistory.addFirst(pPredEff[0])
            if (predVarHistory.size > innovationWindow) predVarHistory.removeLast()

            update(xPredEff, pPredEff, z, rEff, x, p)
            trackInnovation(innovation, innovationVarianceEff)

            val skipRUpdate = qInflateAllowed || absn > 3.0
            if (!skipRUpdate) {
                r = adaptMeasurementNoise(r, innovations, rawInnovationVariance)
            }

            if (isNewData) {
                segmentNewMeasurements++
                sessionMeasurementCount++
                if (mahalSqEff > chiSquaredThreshold || abs(innovation) > outlierAbsolute) {
                    segmentOutliers++
                    sessionOutlierCount++
                }
            }

            val resultIdx = i - startIdx
            forwardResults[resultIdx] = x[0]
            forwardStates.addFirst(stateBefore)
        }

        learnedR = r

        val smoothedResults = forwardResults.copyOf()
        if (segmentSize >= 3 && forwardStates.isNotEmpty()) {
            val maxSmoothSteps = min(segmentSize - 1, forwardStates.size)
            val xSmooth = doubleArrayOf(forwardResults[0], x[1])

            for (i in 1..maxSmoothSteps) {
                val state = forwardStates[i - 1]
                val c = computeSmootherGain(state.p, state.pPred, state.dt)
                val dx0 = xSmooth[0] - state.xPred[0]
                val dx1 = xSmooth[1] - state.xPred[1]
                xSmooth[0] = forwardResults[i] + c[0] * dx0 + c[1] * dx1
                xSmooth[1] = state.x[1] + c[2] * dx0 + c[3] * dx1
                smoothedResults[i] = xSmooth[0]
            }
        }

        for (i in startIdx..endIdx) {
            val resultIdx = i - startIdx
            data[i].smoothed = max(smoothedResults[resultIdx], 39.0)
            data[i].trendArrow = if (i == startIdx) computeTrendArrow(x[1]) else TrendArrow.NONE
        }
    }

    // ============================================================
    // ADAPTIVE R ESTIMATION
    // ============================================================

    private fun trackInnovation(innovation: Double, innovationVariance: Double) {
        val normalizedSq = (innovation * innovation) / innovationVariance
        val rawSq = innovation * innovation

        innovations.addFirst(normalizedSq)
        rawInnovationVariance.addFirst(rawSq)

        if (innovations.size > innovationWindow) innovations.removeLast()
        if (rawInnovationVariance.size > innovationWindow) rawInnovationVariance.removeLast()
    }

    private fun adaptMeasurementNoise(
        currentR: Double,
        innovations: ArrayDeque<Double>,
        rawSq: ArrayDeque<Double>
    ): Double {
        if (innovations.size < 12 || predVarHistory.isEmpty()) return currentR

        fun trimmedMean(v: List<Double>, trim: Double = 0.20): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            val k = (s.size * trim).toInt().coerceAtMost((s.size - 1) / 2)
            val core = s.subList(k, s.size - k)
            return core.average()
        }

        val nSize = innovations.size
        val mRaw = trimmedMean(rawSq.take(nSize))
        val pyyMed = trimmedMean(predVarHistory.take(nSize))

        val rHatRaw = (mRaw - pyyMed).coerceAtLeast(rMin)
        val rHat = rHatRaw.coerceIn(rMin, rMax)

        val goingUp = rHat > currentR
        val kup = 0.18
        val kdn = 0.12
        val k = if (goingUp) kup else kdn
        val step = currentR + k * (rHat - currentR)

        val upCap = if (goingUp) 1.20 else 1.00
        val dnCap = if (goingUp) 1.00 else 0.90
        val clamped = step.coerceIn(currentR * dnCap, currentR * upCap).coerceIn(rMin, rMax)

        val eta = 0.25
        return (1.0 - eta) * currentR + eta * clamped
    }

    // ============================================================
    // TREND ARROW COMPUTATION
    // ============================================================

    private fun computeTrendArrow(rate: Double): TrendArrow {
        return when {
            rate > 2.0  -> TrendArrow.DOUBLE_UP
            rate > 1.0  -> TrendArrow.SINGLE_UP
            rate > 0.5  -> TrendArrow.FORTY_FIVE_UP
            rate < -2.0 -> TrendArrow.DOUBLE_DOWN
            rate < -1.0 -> TrendArrow.SINGLE_DOWN
            rate < -0.5 -> TrendArrow.FORTY_FIVE_DOWN
            else        -> TrendArrow.FLAT
        }
    }

    // ============================================================
    // UKF CORE FUNCTIONS
    // ============================================================

    private fun computeSmootherGain(p: DoubleArray, pPred: DoubleArray, dt: Double): DoubleArray {
        val damp = rateDamp(dt)
        val pfT00 = p[0] + p[1] * dt
        val pfT01 = p[1] * damp
        val pfT10 = p[2] + p[3] * dt
        val pfT11 = p[3] * damp

        val det = pPred[0] * pPred[3] - pPred[1] * pPred[2]
        if (abs(det) < 1e-10) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)

        val inv00 = pPred[3] / det
        val inv01 = -pPred[1] / det
        val inv10 = -pPred[2] / det
        val inv11 = pPred[0] / det

        return doubleArrayOf(
            pfT00 * inv00 + pfT01 * inv10,
            pfT00 * inv01 + pfT01 * inv11,
            pfT10 * inv00 + pfT11 * inv10,
            pfT10 * inv01 + pfT11 * inv11
        )
    }

    private fun predict(x: DoubleArray, p: DoubleArray, q: DoubleArray, dt: Double): Pair<DoubleArray, DoubleArray> {
        val sigmaPoints = generateSigmaPoints(x, p)
        val sigmaPointsPred = Array(2 * n + 1) { DoubleArray(n) }
        val damp = rateDamp(dt)
        for (i in 0 until (2 * n + 1)) {
            sigmaPointsPred[i][0] = sigmaPoints[i][0] + sigmaPoints[i][1] * dt
            sigmaPointsPred[i][1] = sigmaPoints[i][1] * damp
        }

        val xPred = DoubleArray(n)
        for (i in 0 until (2 * n + 1)) {
            xPred[0] += wm[i] * sigmaPointsPred[i][0]
            xPred[1] += wm[i] * sigmaPointsPred[i][1]
        }

        val pPred = DoubleArray(4)
        for (i in 0 until (2 * n + 1)) {
            val dx0 = sigmaPointsPred[i][0] - xPred[0]
            val dx1 = sigmaPointsPred[i][1] - xPred[1]
            pPred[0] += wc[i] * dx0 * dx0
            pPred[1] += wc[i] * dx0 * dx1
            pPred[2] += wc[i] * dx1 * dx0
            pPred[3] += wc[i] * dx1 * dx1
        }

        val qScale = dt / 5.0
        pPred[0] += q[0] * qScale
        pPred[3] += q[3] * qScale
        pPred[0] = max(pPred[0], 0.1)
        pPred[3] = max(pPred[3], 0.001)

        return Pair(xPred, pPred)
    }

    private fun update(xPred: DoubleArray, pPred: DoubleArray, z: Double, r: Double, x: DoubleArray, p: DoubleArray) {
        val sigmaPoints = generateSigmaPoints(xPred, pPred)
        val zSigma = DoubleArray(2 * n + 1)
        for (i in 0 until 2 * n + 1) {
            zSigma[i] = sigmaPoints[i][0]
        }

        var zPred = 0.0
        for (i in 0 until 2 * n + 1) {
            zPred += wm[i] * zSigma[i]
        }

        var pzz = 0.0
        for (i in 0 until 2 * n + 1) {
            val dz = zSigma[i] - zPred
            pzz += wc[i] * dz * dz
        }
        pzz += r

        if (pzz < 1e-6) {
            x[0] = xPred[0]
            x[1] = xPred[1]
            p[0] = pPred[0]
            p[1] = pPred[1]
            p[2] = pPred[2]
            p[3] = pPred[3]
            return
        }

        val pxz = DoubleArray(n)
        for (i in 0 until 2 * n + 1) {
            val dx0 = sigmaPoints[i][0] - xPred[0]
            val dx1 = sigmaPoints[i][1] - xPred[1]
            val dz = zSigma[i] - zPred
            pxz[0] += wc[i] * dx0 * dz
            pxz[1] += wc[i] * dx1 * dz
        }

        val k = DoubleArray(n)
        k[0] = pxz[0] / pzz
        k[1] = pxz[1] / pzz

        val innovation = z - zPred
        x[0] = xPred[0] + k[0] * innovation
        x[1] = xPred[1] + k[1] * innovation
        x[1] = x[1].coerceIn(-4.0, 4.0)

        p[0] = pPred[0] - k[0] * pzz * k[0]
        p[1] = pPred[1] - k[0] * pzz * k[1]
        p[2] = pPred[2] - k[1] * pzz * k[0]
        p[3] = pPred[3] - k[1] * pzz * k[1]

        p[0] = max(p[0], 0.1)
        p[3] = max(p[3], 0.001)
    }

    private fun generateSigmaPoints(x: DoubleArray, p: DoubleArray): Array<DoubleArray> {
        val sigmaPoints = Array(2 * n + 1) { DoubleArray(n) }
        val sqrtP = matrixSqrt2x2(p)
        sigmaPoints[0][0] = x[0]
        sigmaPoints[0][1] = x[1]

        for (i in 0 until n) {
            sigmaPoints[i + 1][0] = x[0] + gamma * sqrtP[i * 2 + 0]
            sigmaPoints[i + 1][1] = x[1] + gamma * sqrtP[i * 2 + 1]
            sigmaPoints[i + 1 + n][0] = x[0] - gamma * sqrtP[i * 2 + 0]
            sigmaPoints[i + 1 + n][1] = x[1] - gamma * sqrtP[i * 2 + 1]
        }
        return sigmaPoints
    }

    private fun matrixSqrt2x2(p: DoubleArray): DoubleArray {
        val a = p[0]
        val b = (p[1] + p[2]) / 2.0
        val d = p[3]

        val l11 = sqrt(max(a, 1e-9))
        val l21 = b / l11
        val discriminant = d - l21 * l21
        if (discriminant < -1e-9) {
            return doubleArrayOf(sqrt(max(a, 0.1)), 0.0, 0.0, sqrt(max(d, 0.01)))
        }

        val l22 = sqrt(max(discriminant, 1e-9))
        return doubleArrayOf(l11, l21, 0.0, l22)
    }

    // ============================================================
    // UTILITY FUNCTIONS
    // ============================================================

    private fun copyRawToSmoothed(data: MutableList<InMemoryGlucoseValue>) {
        for (reading in data) {
            reading.smoothed = max(reading.value, 39.0)
            reading.trendArrow = TrendArrow.NONE
        }
    }
}