package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.DuraIsfStart
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.extensions.isLibre1Minute
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSAutoISF.extensions.asRounded
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt

@Reusable
class GlucoseStatusCalculatorAutoIsf @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val deltaCalculator: DeltaCalculator,
) {

    @Inject lateinit var preferences: Preferences

    // Why the last call's parabola fit scored no window at all (empty when it did). AutoISF prints it
    // next to "correlation ... is too low", which can't otherwise tell a skipped fit from a poor one.
    @Volatile var lastFitProblem: String = ""
        private set

    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAutoIsf? {
        // One consistent snapshot: LoadBgDataWorker publishes the readings, the grid and the curve together.
        val ads = iobCobCalculator.ads
        val (bucketed, orig, oneMinute) = synchronized(ads.dataLock) {
            Triple(ads.getBucketedDataTableCopy(), ads.getBgReadingsDataTableCopy(), ads.getSmoothedReadingsCopy().orEmpty())
        }
        val data = bucketed ?: return null

        var sizeRecords = data.size
        if (sizeRecords == 0) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
            return null
        }
        val now = data[0]
        val nowDate = now.timestamp
        val nowValue = now.value
        val recalc = now.recalculated
        val smooth = now.smoothed
        val filled = now.filledGap
        val cgm = now.sourceSensor
        val isLibre = cgm.isLibre1Minute()   // || cgm.text=="G7"
        // Raw (1-minute) path: whenever LoadBgDataWorker has made the one smoothed curve of the readings
        // (OneMinuteCurve). It does so for a CGM that AutoISF treats as 1-minute data (isOneMinuteRawData:
        // the newest 4 readings within 10 minutes, from an allowlisted Libre or, with
        // ApsAutoIsfForce1MinuteRaw, any sensor). The fit, dura_ISF, the sliding delta, glucose and the
        // staleness check then all read that curve, and nothing is smoothed here.
        val use1MinuteRaw = oneMinute.isNotEmpty() && oneMinute[0].timestamp == orig.firstOrNull()?.timestamp
        // On the raw path "now" is the newest raw reading. The bucketed grid re-anchors on it every
        // cycle (see AutosensDataStoreObject.clone()), but taking it from orig directly keeps glucose,
        // date, the staleness check, the sliding delta and the parabola fit on that same instant even
        // if the grid ever lags it - otherwise eventualBG would pair an older BG with a current delta,
        // overestimating BG while it falls.
        val statusDate = if (use1MinuteRaw) oneMinute[0].timestamp else nowDate
        if (statusDate < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
            aapsLogger.debug(LTag.GLUCOSE, "oldData")
            return null
        }
        // The last hour of the curve: all that dura_ISF looks at, and more than the sliding delta needs
        val smoothedRawWindow = if (use1MinuteRaw) oneMinute.takeWhile { it.timestamp >= oneMinute[0].timestamp - 60 * 60_000L } else emptyList()
        val statusGlucose = smoothedRawWindow.firstOrNull()?.recalculated ?: now.recalculated
        val fsl = orig[0]
        val fslDate = fsl.timestamp
        val fslValue = fsl.raw
        val fslRaw = fsl.noise
        val fslSmooth = fsl.value
        val fslMinDur = if (use1MinuteRaw) 10 else 15
        if (sizeRecords == 1) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
            lastFitProblem = "only one bucketed value"
            return GlucoseStatusAutoIsf(
                glucose = statusGlucose,
                noise = 0.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                date = statusDate,
                duraISFminutes = 0.0,
                duraISFaverage = now.value,
                duraISFstart = if (now.value > DuraIsfWindow.FLOOR_MGDL) DuraIsfStart.OLDEST else DuraIsfStart.NOT_ABOVE_FLOOR,
                parabolaMinutes = 0.0,
                deltaPl = 0.0,
                deltaPn = 0.0,
                bgAcceleration = 0.0,
                a0 = now.value,
                a1 = 0.0,
                a2 = 0.0,
                corrSqu = 0.0
            ).asRounded()
        }

        val deltaResult = deltaCalculator.calculateDeltas(data)
        // On the raw path, delta is a sliding 5-minutes-ago comparison on the curve rather than on the
        // bucketed grid. shortAvgDelta and longAvgDelta stay on the grid, which takes its values from the
        // same curve. fitValueAt is only called on the raw path.
        val fitValueAt = { i: Int -> oneMinute[i].recalculated }
        val delta = (if (use1MinuteRaw) slidingDelta(smoothedRawWindow) else null) ?: deltaResult.delta

        // dura_ISF: how long, within the last hour, glucose has been at its current level above 150 mg/dL, rise included,
        // and the ISF curve's average over that time - on the raw path counted on the same smoothed 1-minute readings as the
        // sliding delta (the last hour of them, all DuraIsfWindow looks at), to the minute; else on the 5-minute grid
        val duraWindow = DuraIsfWindow.of(if (use1MinuteRaw && smoothedRawWindow.isNotEmpty()) smoothedRawWindow else data)

        // calculate best parabola and determine delta by extending it 5 minutes into the future
        // after https://www.codeproject.com/Articles/63170/Least-Squares-Regression-for-Quadratic-Curve-Fitti
        //
        //  y = a2*x^2 + a1*x + a0      or
        //  y = a*x^2  + b*x  + c       respectively
        var duraP = 0.0
        var deltaPl = 0.0
        var deltaPn = 0.0
        var bgAcceleration = 0.0
        var corrMax = 0.0
        var a0 = 0.0
        var a1 = 0.0
        var a2 = 0.0
        //var b = 0.0
        if (use1MinuteRaw) {
            sizeRecords = oneMinute.size
        }
        aapsLogger.debug(LTag.GLUCOSE, "BgReadings stamp=$fslDate; raw=$fslRaw; value=$fslValue; Libre=$isLibre; raw1min=$use1MinuteRaw; fitMinutes=$fslMinDur; fslSmooth=$fslSmooth; " +
            "BgBucketed value=$nowValue; recalc=$recalc; smooth=$smooth; filled=$filled; CGM=$cgm")

        val calibrationDuration = preferences.get(IntKey.FslCalibrationDuration)
        val calibrationMinutes = calibrationDuration - (dateUtil.now() - preferences.get(LongKey.FslCalibrationStart)) / 60000
        val calibrationStopsSMB = calibrationMinutes > 0 && !preferences.get(BooleanKey.FslCalibrationEnd)
        var fitStop = ""
        var windowsTried = 0
        var windowScored = false
        if (sizeRecords > 3 && !calibrationStopsSMB) {
            var sy = 0.0 // y
            var sx = 0.0 // x
            var sx2 = 0.0 // x^2
            var sx3 = 0.0 // x^3
            var sx4 = 0.0 // x^4
            var sxy = 0.0 // x*y
            var sx2y = 0.0 // x^2*y
            val time0 = if (use1MinuteRaw) oneMinute[0].timestamp else data[0].timestamp
            var tiLast = 0.0
            //# for best numerical accuracy time and bg must be of same order of magnitude
            val scaleTime = 1.0 // was 00.0 // in 5m; values are  0, -1, -2, -3, -4, ...
            val scaleBg = 1.0   // was 50.0 // TIR range is now 1.4 - 3.6

            // if (data[i].recalculated > 38) {  } // not checked in past 1.5 years
            var n = 0
            for (i in 0 until sizeRecords) {
                val noGap = if (use1MinuteRaw) true else !data[i].filledGap
                val usableValue = if (use1MinuteRaw) fitValueAt(i) else data[i].value
                if (usableValue > 39 && noGap) {
                    n += 1
                    val thenDate: Long
                    var bg: Double
                    if (use1MinuteRaw) {
                        val then = oneMinute[i]
                        thenDate = then.timestamp
                        bg = fitValueAt(i) / scaleBg
                    } else {    // all other including standard 5m CGM smoothed
                        val then = data[i]
                        thenDate = then.timestamp
                        bg = then.recalculated / scaleBg
                    }
                    val ti = (thenDate - time0) / 1000.0 / scaleTime
                    if (-ti * scaleTime > 47 * 60) {                       // skip records older than 47.5 minutes
                        break
                    } else if (ti < tiLast - 11.0 * 60 / scaleTime) {      // stop scan if a CGM gap > 11 minutes is detected
                        if (i < 3 || -ti * scaleTime < fslMinDur * 60) {   // history too short for fit
                            duraP = -tiLast * scaleTime / 60.0
                            deltaPl = 0.0
                            deltaPn = 0.0
                            bgAcceleration = 0.0
                            corrMax = 0.0
                            a0 = 0.0
                            a1 = 0.0
                            a2 = 0.0
                        }
                        fitStop = "gap of ${tenths((tiLast - ti) * scaleTime / 60.0)} min after the newest ${tenths(-tiLast * scaleTime / 60.0)} min"
                        break
                    }
                    tiLast = ti
                    sx += ti
                    sx2 += ti.pow(2.0)
                    sx3 += ti.pow(3.0)
                    sx4 += ti.pow(4.0)
                    sy += bg
                    sxy += ti * bg
                    sx2y += ti.pow(2.0) * bg
                    //val n = i + 1
                    var detH = 0.0
                    var detA = 0.0
                    var detB = 0.0
                    var detC = 0.0
                    if (n > 3 && -ti * scaleTime > fslMinDur * 60) {
                        detH = sx4 * (sx2 * n - sx * sx) - sx3 * (sx3 * n - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
                        detA = sx2y * (sx2 * n - sx * sx) - sxy * (sx3 * n - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)
                        detB = sx4 * (sxy * n - sy * sx) - sx3 * (sx2y * n - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)
                        detC = sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)
                    }
                    if (detH != 0.0) {
                        windowsTried++
                        val a: Double = detA / detH * scaleBg * (300 / scaleTime).pow(2.0)
                        val b = detB / detH * scaleBg * (300 / scaleTime)
                        val c: Double = detC / detH * scaleBg
                        val yMean = sy / n
                        var sSquares = 0.0
                        var sResidualSquares = 0.0
                        //var rawBg: Double
                        for (j in 0..i) {
                            if (use1MinuteRaw) {
                                val before = oneMinute[j]
                                val scaledBg = fitValueAt(j) / scaleBg
                                sSquares += (scaledBg - yMean).pow(2.0)
                                val deltaT: Double = (before.timestamp - time0) / 1000.0 / 300
                                val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                                sResidualSquares += (scaledBg - bgj/scaleBg).pow(2.0)
                            } else {                            // default case anyway
                                val before = data[j]
                                val scaledBg = before.recalculated/ scaleBg
                                sSquares += (scaledBg - yMean).pow(2.0)
                                val deltaT: Double = (before.timestamp - time0) / 1000.0 / 300.0
                                val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                                sResidualSquares += (scaledBg - bgj/scaleBg).pow(2.0)
                            }
                        }
                        var rSqu = 0.0
                        if (sSquares != 0.0) {
                            rSqu = 1 - sResidualSquares / sSquares
                        }
                        if (rSqu >= corrMax) {
                            windowScored = true
                            corrMax = rSqu

                            duraP = -ti * scaleTime / 60.0 // remember we are going backwards in time
                            val delta5Min = 1.0 //5 * 60 / scaleTime
                            deltaPl = - (a * (-delta5Min).pow(2.0) - b * delta5Min)    // 5 minute slope from last fitted bg ending at this bg, i.e. t=0
                            deltaPn =   (a * delta5Min.pow(2.0) + b * delta5Min)    // 5 minute slope to next fitted bg starting from this bg, i.e. t=0
                            bgAcceleration = 2 * a
                            a0 = c
                            a1 = b
                            a2 = a
                        }
                    }
                }
            }
        } else fitStop = if (calibrationStopsSMB) "calibration in progress" else "only $sizeRecords records"
        lastFitProblem = if (windowScored) "" else describeFitProblem(fitStop, windowsTried, fslMinDur, use1MinuteRaw, orig, oneMinute, data)
        if (!windowScored) aapsLogger.debug(LTag.GLUCOSE, "Parabola fit scored no window: $lastFitProblem")
        // End parabola fit

        return GlucoseStatusAutoIsf(
            glucose = statusGlucose,
            date = statusDate,
            noise = 0.0, //for now set to nothing as not all CGMs report noise
            shortAvgDelta = deltaResult.shortAvgDelta,
            delta = delta,
            longAvgDelta = deltaResult.longAvgDelta,
            duraISFminutes = duraWindow.minutes.toDouble(),
            duraISFaverage = duraWindow.average,
            duraISFcurveAverage = duraWindow.curveAverage,
            duraISFstart = duraWindow.start,
            parabolaMinutes = duraP,
            deltaPl = deltaPl,
            deltaPn = deltaPn,
            corrSqu = corrMax,
            bgAcceleration = bgAcceleration,
            a0 = a0,
            a1 = a1,
            a2 = a2,
        )   //.also { aapsLogger.debug(LTag.GLUCOSE, it.log(decimalFormatter)) }.asRounded()
    }

    // "now" and "~5 minutes ago" interpolated off the smoothed curve. Its last 2 hours are smoothed again
    // at every data load, so this is the smoother's current estimate for that instant, not whatever it
    // gave when that reading first arrived. Returns null (caller keeps the bucketed delta) when there
    // isn't enough lookback yet.
    private fun slidingDelta(smoothed: List<InMemoryGlucoseValue>): Double? {
        if (smoothed.size < 2) return null
        val now = smoothed[0].timestamp
        val target = now - 5 * 60_000L
        if (smoothed.last().timestamp > target) return null // not enough history yet
        val thenValue = interpolateAt(smoothed, target) ?: return null
        return smoothed[0].recalculated - thenValue
    }

    // smoothed is newest-first. Finds the pair straddling `time` and linearly interpolates; returns
    // null if that pair's gap is too large to trust a linear interpolation across (matching
    // DeltaCalculator's own 13-minute gap-abort convention).
    private fun interpolateAt(smoothed: List<InMemoryGlucoseValue>, time: Long): Double? {
        for (i in 1 until smoothed.size) {
            val newer = smoothed[i - 1]
            val older = smoothed[i]
            if (older.timestamp <= time) {
                if (newer.timestamp - older.timestamp > 13 * 60_000L) return null
                if (newer.timestamp == older.timestamp) return newer.recalculated
                val frac = (newer.timestamp - time).toDouble() / (newer.timestamp - older.timestamp)
                return newer.recalculated - frac * (newer.recalculated - older.recalculated)
            }
        }
        return null
    }

    private fun tenths(x: Double): Double = (x * 10).roundToInt() / 10.0

    // One line saying why no window was scored plus the newest values the fit actually saw, as
    // "seconds ago:mg/dL" pairs. NaN is printed as such rather than rounded (roundToInt would throw).
    private fun describeFitProblem(
        stop: String, windowsTried: Int, minMinutes: Int, use1MinuteRaw: Boolean,
        orig: List<GV>, oneMinute: List<InMemoryGlucoseValue>, data: List<InMemoryGlucoseValue>
    ): String {
        fun mgdl(v: Double) = if (v.isNaN()) "NaN" else v.roundToInt().toString()
        val reason = stop.ifEmpty {
            if (windowsTried > 0) "$windowsTried windows, none with a valid correlation"
            else "never reached $minMinutes min with 4+ usable values"
        }
        val path = if (use1MinuteRaw) "1-min path"
        else "5-min path" + if (orig.size > 3) " (newest 4 readings span ${(orig[0].timestamp - orig[3].timestamp) / 1000}s)" else ""
        val newest = if (use1MinuteRaw)
            (0 until minOf(oneMinute.size, 12)).joinToString(" ") { "${(oneMinute[0].timestamp - oneMinute[it].timestamp) / 1000}:${mgdl(oneMinute[it].recalculated)}" }
        else
            (0 until minOf(data.size, 8)).joinToString(" ") { "${(data[0].timestamp - data[it].timestamp) / 1000}:${mgdl(data[it].value)}${if (data[it].filledGap) "*" else ""}" }
        return "$path: $reason; newest (s ago:mg/dL${if (use1MinuteRaw) "" else ", *=gap-filled"}) $newest"
    }

    companion object {
        //this will be useful in the next step when I replace magic numbers with vals
    }
}