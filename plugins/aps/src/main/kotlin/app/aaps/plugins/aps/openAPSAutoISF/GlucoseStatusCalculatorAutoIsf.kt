package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.objects.extensions.fromGv
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSAutoISF.extensions.asRounded
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Reusable
class GlucoseStatusCalculatorAutoIsf @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val deltaCalculator: DeltaCalculator,
) {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var activePlugin: ActivePlugin

    // Why the last call's parabola fit scored no window at all (empty when it did). AutoISF prints it
    // next to "correlation ... is too low", which can't otherwise tell a skipped fit from a poor one.
    @Volatile var lastFitProblem: String = ""
        private set

    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAutoIsf? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        val orig = iobCobCalculator.ads.getBgReadingsDataTableCopy()

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
        val isLibre = cgm.text == "Libre2" || cgm.text == "Libre2 Native" || cgm.text == "Libre3"   // || cgm.text=="G7"
        // Raw (1-minute) path: readings genuinely denser than a 5-minute CGM - the newest 4 within 10
        // minutes - from an allowlisted Libre or, with ApsAutoIsfForce1MinuteRaw, any sensor. The flag
        // widens eligibility, it never skips the timing check. Requiring only the last 3 within 3
        // minutes let one late reading drop a whole status onto the 5-minute grid. Timing is tested
        // first so preferences is only consulted when there really is dense data from a non-Libre sensor.
        val use1MinuteRaw = orig.size > 3 && orig[0].timestamp - orig[3].timestamp < 10 * 60000 &&
            (isLibre || preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw))
        // On the raw path "now" is the newest raw reading. The bucketed grid re-anchors on it every
        // cycle (see AutosensDataStoreObject.clone()), but taking it from orig directly keeps glucose,
        // date, the staleness check, the sliding delta and the parabola fit on that same instant even
        // if the grid ever lags it - otherwise eventualBG would pair an older BG with a current delta,
        // overestimating BG while it falls.
        val statusDate = if (use1MinuteRaw) orig[0].timestamp else nowDate
        if (statusDate < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
            aapsLogger.debug(LTag.GLUCOSE, "oldData")
            return null
        }
        val smoothedRawWindow = if (use1MinuteRaw) smoothedRecentWindow(orig) else emptyList()
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
        // When eligible for the raw path, delta is a sliding 5-minutes-ago comparison on the raw
        // window as smoothed now, rather than on the bucketed grid, whose points are interpolated
        // from raw readings before smoothing. shortAvgDelta and longAvgDelta stay on the bucketed path.
        val fitValueAt = { i: Int -> smoothedRawWindow.getOrNull(i)?.recalculated ?: orig[i].value }
        val delta = (if (use1MinuteRaw) slidingDelta(smoothedRawWindow) else null) ?: deltaResult.delta

        // calculate 2 variables for 5% range; still using 5 minute data
        val bw = 0.05
        var sumBG: Double = now.recalculated
        var oldAvg: Double = sumBG
        var minutesDur = 0L
        var n = 1
        for (i in 1 until sizeRecords) {
            if (data[i].value > 39 && !data[i].filledGap) {
                n += 1
                val then = data[i]
                val thenDate: Long = then.timestamp
                //  stop the series if there was a CGM gap greater than 13 minutes, i.e. 2 regular readings
                //  needs shorter gap for Libre?
                if (((nowDate - thenDate) / (1000.0 * 60)).roundToInt() - minutesDur > 13) {
                    break
                }
                if (then.recalculated > oldAvg * (1 - bw) && then.recalculated < oldAvg * (1 + bw)) {
                    sumBG += then.recalculated
                    oldAvg = sumBG / n  // was: (i + 1)
                    minutesDur = ((nowDate - thenDate) / (1000.0 * 60)).roundToLong()
                } else {
                    break
                }
            }
        }

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
            sizeRecords = orig.size
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
            val time0 = if (use1MinuteRaw) orig[0].timestamp else data[0].timestamp
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
                        val then = orig[i]
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
                                val before = orig[j]
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
        lastFitProblem = if (windowScored) "" else describeFitProblem(fitStop, windowsTried, fslMinDur, use1MinuteRaw, orig, data, fitValueAt)
        if (!windowScored) aapsLogger.debug(LTag.GLUCOSE, "Parabola fit scored no window: $lastFitProblem")
        // End parabola fit

        return GlucoseStatusAutoIsf(
            glucose = statusGlucose,
            date = statusDate,
            noise = 0.0, //for now set to nothing as not all CGMs report noise
            shortAvgDelta = deltaResult.shortAvgDelta,
            delta = delta,
            longAvgDelta = deltaResult.longAvgDelta,
            duraISFminutes = minutesDur.toDouble(),
            duraISFaverage = oldAvg,
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

    // Caps the dense stream handed to the smoothing plugin to the window the parabola fit and the
    // sliding delta actually look at (the fit breaks off past 47.5 minutes; the delta only needs
    // ~5), rather than the full multi-hour getBgReadingsDataTableCopy() buffer - keeps per-cycle
    // smoothing cost (particularly the UKF's forward+backward pass) bounded regardless of how far
    // back raw history extends. 60 minutes matches the UKF's own majorGapThreshold, i.e. its own
    // notion of a useful history horizon for retrospective revision.
    private fun smoothedRecentWindow(orig: List<GV>): List<InMemoryGlucoseValue> {
        if (orig.isEmpty()) return emptyList()
        val cutoff = orig[0].timestamp - 60 * 60_000L
        val recent = orig.takeWhile { it.timestamp >= cutoff }   // always includes orig[0]
        return activePlugin.activeSmoothing.smooth(recent.map { InMemoryGlucoseValue.fromGv(it) }.toMutableList())
    }

    // "now" and "~5 minutes ago" interpolated off the smoothed dense stream, recomputed fresh on
    // every call (getGlucoseStatusData has no caching) from whatever raw data currently exists -
    // i.e. the smoother's current, fully-informed estimate for that instant, not whatever it
    // happened to output when that reading first arrived. Returns null (caller keeps the bucketed
    // delta) when there isn't enough lookback yet.
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
        orig: List<GV>, data: List<InMemoryGlucoseValue>, fitValueAt: (Int) -> Double
    ): String {
        fun mgdl(v: Double) = if (v.isNaN()) "NaN" else v.roundToInt().toString()
        val reason = stop.ifEmpty {
            if (windowsTried > 0) "$windowsTried windows, none with a valid correlation"
            else "never reached $minMinutes min with 4+ usable values"
        }
        val path = if (use1MinuteRaw) "1-min path"
        else "5-min path" + if (orig.size > 3) " (newest 4 readings span ${(orig[0].timestamp - orig[3].timestamp) / 1000}s)" else ""
        val newest = if (use1MinuteRaw)
            (0 until minOf(orig.size, 12)).joinToString(" ") { "${(orig[0].timestamp - orig[it].timestamp) / 1000}:${mgdl(fitValueAt(it))}" }
        else
            (0 until minOf(data.size, 8)).joinToString(" ") { "${(data[0].timestamp - data[it].timestamp) / 1000}:${mgdl(data[it].value)}${if (data[it].filledGap) "*" else ""}" }
        return "$path: $reason; newest (s ago:mg/dL${if (use1MinuteRaw) "" else ", *=gap-filled"}) $newest"
    }

    companion object {
        //this will be useful in the next step when I replace magic numbers with vals
    }
}