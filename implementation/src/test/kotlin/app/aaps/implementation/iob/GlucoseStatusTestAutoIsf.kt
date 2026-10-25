package app.aaps.implementation.iob

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSAutoISF.GlucoseStatusCalculatorAutoIsf
import app.aaps.plugins.aps.openAPSAutoISF.extensions.asRounded
import app.aaps.plugins.aps.openAPSAutoISF.extensions.log
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * Created by mike on 26.03.2018.
 */
class GlucoseStatusTestAutoIsf : TestBaseWithProfile() {

    @Mock lateinit var autosensDataStore: AutosensDataStore

    @BeforeEach
    fun prepare() {
        whenever(dateUtil.now()).thenReturn(1514766900000L + T.mins(1).msecs())
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)
    }

    @Test fun toStringShouldBeOverloaded() {
        val glucoseStatus = GlucoseStatusAutoIsf(glucose = 0.0, noise = 0.0, delta = 0.0, shortAvgDelta = 0.0, longAvgDelta = 0.0, date = 0)
        assertThat(glucoseStatus.log(decimalFormatter)).contains("Delta")
    }

    @Test fun roundTest() {
        val glucoseStatus = GlucoseStatusAutoIsf(glucose = 100.11111, noise = 0.0, delta = 0.0, shortAvgDelta = 0.0, longAvgDelta = 0.0, date = 0)
        assertThat(glucoseStatus.asRounded().glucose).isWithin(0.0001).of(100.1)
    }

    @Test fun calculateValidGlucoseStatusAutoIsf() {
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateValidBgData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateDummyLibreData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(214.0)
        assertThat(glucoseStatus.delta).isWithin(0.001).of(-2.0)
        assertThat(glucoseStatus.shortAvgDelta).isWithin(0.001).of(-2.5) // -2 -2.5 -3 deltas are relative to current value
        assertThat(glucoseStatus.longAvgDelta).isWithin(0.001).of(-2.0) // -2 -2 -2 -2
        assertThat(glucoseStatus.date).isEqualTo(1514766900000L) // latest date

        assertThat(glucoseStatus.duraISFminutes).isEqualTo(35.0) // plateau size records in minutes
        assertThat(glucoseStatus.duraISFaverage).isWithin(0.1).of(221.5) // average during above time window
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.1).of(35.0) // parabola size records in minutes
        assertThat(glucoseStatus.deltaPl).isWithin(0.1).of(-2.56) // last delta
        assertThat(glucoseStatus.deltaPn).isWithin(0.1).of(-2.77) // next delta
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.01).of(-0.21) // glucose acceleration
        assertThat(glucoseStatus.a0).isWithin(0.1).of(214.0) //
        assertThat(glucoseStatus.a1).isWithin(0.0001).of(-2.6786) //
        assertThat(glucoseStatus.a2).isWithin(0.0001).of(-0.1071) //
        assertThat(glucoseStatus.corrSqu).isWithin(0.0001).of(0.9643) // parabola fit quality
    }

    @Test fun calculateValidLibreGlucoseStatusAutoIsf() {
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateDummyBgData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateValidLibreData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        // Raw path: glucose is the newest (smoothed) raw reading, not the bucketed grid point. The two
        // fixtures here were never consistent with each other (214... bucketed vs 114... raw) - that
        // didn't matter while only the parabola fit read the raw data.
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(114.0)
        assertThat(glucoseStatus.delta).isWithin(0.001).of(-2.0)
        assertThat(glucoseStatus.shortAvgDelta).isWithin(0.001).of(-2.5) // -2 -2.5 -3 deltas are relative to current value
        assertThat(glucoseStatus.longAvgDelta).isWithin(0.001).of(-2.0) // -2 -2 -2 -2
        assertThat(glucoseStatus.date).isEqualTo(1514766900000L) // latest date

        assertThat(glucoseStatus.duraISFminutes).isEqualTo(35.0) // plateau size records in minutes
        assertThat(glucoseStatus.duraISFaverage).isWithin(0.1).of(221.5) // average during above time window
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.1).of(14.0) // parabola size records in minutes
        assertThat(glucoseStatus.deltaPl).isWithin(0.0001).of(-1.7157) // last delta
        assertThat(glucoseStatus.deltaPn).isWithin(0.0001).of(0.4255) // next delta
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.0001).of(2.1413) // glucose acceleration
        assertThat(glucoseStatus.a0).isWithin(0.1).of(113.7) //
        assertThat(glucoseStatus.a1).isWithin(0.0001).of(-0.6451) //
        assertThat(glucoseStatus.a2).isWithin(0.0001).of(1.0706) //
        assertThat(glucoseStatus.corrSqu).isWithin(0.0006).of(0.9430) // parabola fit quality
    }

    @Test fun calculateValidForced1MinuteRawGlucoseStatusAutoIsf() {
        // Same data as calculateValidLibreGlucoseStatusAutoIsf above, byte-for-byte, except the
        // sensor is SYAI_TAG - not on the hardcoded Libre allowlist - and ApsAutoIsfForce1MinuteRaw
        // is on instead. If the flag genuinely widens eligibility for the raw-data path (rather than,
        // say, silently no-oping), this must produce the exact same fit results as that Libre test.
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(true)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateDummyNonLibreData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateValidNonLibreRawData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(114.0) // newest raw reading, as in the Libre test
        assertThat(glucoseStatus.delta).isWithin(0.0001).of(-2.0) // sliding delta: same fixture as calculateValidLibreGlucoseStatusAutoIsf's -2.0
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.1).of(14.0) // raw-path window, not the bucketed 35.0
        assertThat(glucoseStatus.deltaPl).isWithin(0.0001).of(-1.7157)
        assertThat(glucoseStatus.deltaPn).isWithin(0.0001).of(0.4255)
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.0001).of(2.1413)
        assertThat(glucoseStatus.a0).isWithin(0.1).of(113.7)
        assertThat(glucoseStatus.a1).isWithin(0.0001).of(-0.6451)
        assertThat(glucoseStatus.a2).isWithin(0.0001).of(1.0706)
        assertThat(glucoseStatus.corrSqu).isWithin(0.0006).of(0.9430)
    }

    @Test fun nonLibreSensorWithoutForceFlagShouldUseBucketedPath() {
        // Identical non-Libre data to the test above, but the flag is explicitly off. Must match
        // calculateValidGlucoseStatusAutoIsf's bucketed-path numbers exactly, not the raw-path ones -
        // confirms the gate actually gates, rather than the raw path leaking through some other way.
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateDummyNonLibreData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateValidNonLibreRawData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.duraISFminutes).isEqualTo(35.0)
        assertThat(glucoseStatus.duraISFaverage).isWithin(0.1).of(221.5)
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.1).of(35.0) // bucketed-path window, not the raw 14.0
        assertThat(glucoseStatus.deltaPl).isWithin(0.1).of(-2.56)
        assertThat(glucoseStatus.deltaPn).isWithin(0.1).of(-2.77)
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.01).of(-0.21)
        assertThat(glucoseStatus.a0).isWithin(0.1).of(214.0)
        assertThat(glucoseStatus.a1).isWithin(0.0001).of(-2.6786)
        assertThat(glucoseStatus.a2).isWithin(0.0001).of(-0.1071)
        assertThat(glucoseStatus.corrSqu).isWithin(0.0001).of(0.9643)
    }

    @Test fun slidingDeltaWhenForce1MinuteRawEnabled() {
        // Bucketed (5-min grid) delta from this fixture is 10.0/5min (200,190,180,170,160 every
        // 5 minutes). Raw delta over exactly the last 5 minutes is 20.0/5min (120 now, 100 five
        // minutes ago) - deliberately different values so a passing assertion can only mean the
        // sliding path actually ran, not that it coincidentally matched the bucketed one.
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(true)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateSlidingDeltaBucketedData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateSlidingDeltaRawData())

        val glucoseStatus = calculator.getGlucoseStatusData(false)!!

        assertThat(glucoseStatus.delta).isWithin(0.001).of(20.0)
        // glucose/date must share the delta's "now" - the newest raw reading (120), not the bucketed
        // grid point (200) - or eventualBG would mix two different instants.
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(120.0)
        assertThat(glucoseStatus.date).isEqualTo(1514766900000L)
    }

    @Test fun bucketedDeltaWhenForce1MinuteRawDisabled() {
        // Identical fixtures to the test above, flag off: delta must stay on the bucketed 10.0,
        // not the raw 20.0 - confirms the gate actually gates the delta too, not just the fit.
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateSlidingDeltaBucketedData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateSlidingDeltaRawData())

        val glucoseStatus = calculator.getGlucoseStatusData(false)!!

        assertThat(glucoseStatus.delta).isWithin(0.001).of(10.0)
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(200.0) // bucketed path keeps the bucketed BG
    }

    @Test fun slidingDeltaReflectsSmoothedNotRawValues() {
        // Same raw fixture as above (raw delta would be 20.0), but the active smoothing plugin
        // flattens every point to the same value - if delta read orig[].value directly instead of
        // the smoothed stream's .recalculated, it would still come out 20.0. Getting exactly 0.0
        // is only possible if the smoothed (flattened) values were actually used.
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(object : Smoothing {
            override fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
                data.forEach { it.smoothed = 500.0 }
                return data
            }
        })
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(true)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateSlidingDeltaBucketedData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateSlidingDeltaRawData())

        val glucoseStatus = calculator.getGlucoseStatusData(false)!!

        assertThat(glucoseStatus.delta).isWithin(0.001).of(0.0)
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(500.0) // glucose is the smoothed value too
    }

    @Test fun rawPathJudgesStalenessByNewestRawReading() {
        // On the raw path the bucketed grid trails the newest raw reading by up to ~5 minutes. Here the
        // grid's newest point is 7.5 min old (past the 7-minute staleness limit) while the newest raw
        // reading is 2.5 min old - e.g. after a brief stream hiccup with an unlucky grid phase. Fresh
        // raw data must not be dropped because of the grid's own lag.
        whenever(dateUtil.now()).thenReturn(1514766900000L + T.secs(150).msecs())
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(true)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateSlidingDeltaBucketedData().drop(1).toMutableList())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateSlidingDeltaRawData())

        val glucoseStatus = calculator.getGlucoseStatusData(false)

        assertThat(glucoseStatus).isNotNull()
        assertThat(glucoseStatus!!.date).isEqualTo(1514766900000L)
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(120.0)
        assertThat(glucoseStatus.delta).isWithin(0.001).of(20.0)
    }

    @Test fun bucketedPathStillRejectsStaleGrid() {
        // Same fixtures and clock as above, flag off: no raw path, so the grid's own age decides, as before.
        whenever(dateUtil.now()).thenReturn(1514766900000L + T.secs(150).msecs())
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        whenever(preferences.get(BooleanKey.ApsAutoIsfForce1MinuteRaw)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateSlidingDeltaBucketedData().drop(1).toMutableList())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateSlidingDeltaRawData())

        assertThat(calculator.getGlucoseStatusData(false)).isNull()
    }

    @Test fun recentCalibrationShouldReturnNoFit() {
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        calculator.activePlugin = activePlugin
        whenever(activePlugin.activeSmoothing).thenReturn(identitySmoothing())
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(10).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateDummyBgData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateValidLibreData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.corrSqu).isWithin(0.001).of(0.0) // parabola fit quality
    }

    @Test fun calculateValidGlucoseStatusWith10mGap() {
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateValidBgDataWith10mGap())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateDummyLibreData())
        val glucoseStatus =  calculator.getGlucoseStatusData(false)!!

        assertThat(glucoseStatus.duraISFminutes).isEqualTo(35.0) // plateau size records in minutes
        assertThat(glucoseStatus.duraISFaverage).isWithin(0.1).of(221.9) // average during above time window
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.1).of(35.0) // parabola size records in minutes
        assertThat(glucoseStatus.deltaPl).isWithin(0.1).of(-2.56) // last delta
        assertThat(glucoseStatus.deltaPn).isWithin(0.1).of(-2.77) // next delta
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.01).of(-0.21) // glucose acceleration
        assertThat(glucoseStatus.a0).isWithin(0.1).of(214.0) //
        assertThat(glucoseStatus.a1).isWithin(0.0001).of(-2.6703) //
        assertThat(glucoseStatus.a2).isWithin(0.0001).of(-0.1055) //
        assertThat(glucoseStatus.corrSqu).isWithin(0.0001).of(0.9627) // parabola fit quality
    }
    /*
        Not testing anymore, not valid for bucketed data

        @Test fun calculateMostRecentGlucoseStatus() {
            Mockito.`when`(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateMostRecentBgData())
            val glucoseStatus: GlucoseStatus = GlucoseStatusProviderImpl(aapsLogger, iobCobCalculatorPlugin, dateUtil).glucoseStatusData!!
            assertThat(glucoseStatus.glucose).isWithin(0.001).of(215.0) // (214+216) / 2
            assertThat(glucoseStatus.delta).isWithin(0.001).of(-1.0)
            assertThat(glucoseStatus.shortAvgDelta).isWithin(0.001).of(-1.0)
            assertThat( glucoseStatus.longAvgDelta).isWithin(0.001).of(0.0)
            assertThat(glucoseStatus.date).isEqualTo(1514766900000L) // latest date, even when averaging
        }

        private fun generateMostRecentBgData(): MutableList<InMemoryGlucoseValue> {
            val list: MutableList<InMemoryGlucoseValue> = ArrayList()
            list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT))
            list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766800000, trendArrow = TrendArrow.FLAT))
            list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT))
            return list
        }
    */

    @Test fun oneRecordShouldProduceZeroDeltas() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateOneCurrentRecordBgData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateDummyLibreData())
        val glucoseStatus = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator).getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.glucose).isWithin(0.001).of(214.0)
        assertThat(glucoseStatus.delta).isWithin(0.001).of(0.0)
        assertThat(glucoseStatus.shortAvgDelta).isWithin(0.001).of(0.0) // -2 -2.5 -3 deltas are relative to current value
        assertThat(glucoseStatus.longAvgDelta).isWithin(0.001).of(0.0) // -2 -2 -2 -2
        assertThat(glucoseStatus.date).isEqualTo(1514766900000L) // latest date

        assertThat(glucoseStatus.duraISFminutes).isEqualTo(0.0) // size records in minutes
        assertThat(glucoseStatus.duraISFaverage).isWithin(0.01).of(214.0) // last value
        assertThat(glucoseStatus.parabolaMinutes).isWithin(0.001).of(0.0) // size records in minutes
        assertThat(glucoseStatus.bgAcceleration).isWithin(0.001).of(0.0) //
        assertThat(glucoseStatus.a0).isWithin(0.001).of(214.0) // last value
        assertThat(glucoseStatus.corrSqu).isWithin(0.001).of(0.0) //
    }

    @Test fun insufficientDataShouldReturnNull() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateInsufficientBgData())
        val glucoseStatus = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator).getGlucoseStatusData(false)
        assertThat(glucoseStatus).isNull()
    }

    @Test fun early15mGapShouldReturnNoFit() {
        val calculator = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator)
        calculator.preferences = preferences
        whenever(preferences.get(IntKey.FslCalibrationDuration)).thenReturn(20)
        whenever(preferences.get(LongKey.FslCalibrationStart)).thenReturn(1514766900000L - T.mins(30).msecs())
        whenever(preferences.get(BooleanKey.FslCalibrationEnd)).thenReturn(false)
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateValidBgDataWith15mGap())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateDummyLibreData())
        val glucoseStatus = calculator.getGlucoseStatusData(false)!!
        assertThat(glucoseStatus.corrSqu).isWithin(0.001).of(0.0) //
    }

    @Test fun oldDataShouldReturnNull() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateOldBgData())
        val glucoseStatus = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator).getGlucoseStatusData(false)
        assertThat(glucoseStatus).isNull()
    }

    @Test fun returnOldDataIfAllowed() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(generateOldBgData())
        whenever(autosensDataStore.getBgReadingsDataTableCopy()).thenReturn(generateDummyLibreData())
        val glucoseStatus = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, deltaCalculator).getGlucoseStatusData(true)
        assertThat(glucoseStatus).isNotNull()
    }

    @Test fun averageShouldNotFailOnEmptyArray() {
        assertThat(DeltaCalculator.average(ArrayList())).isWithin(0.001).of(0.0)
    }

    // [{"mgdl":214,"mills":1521895773113,"device":"xDrip-DexcomG5","direction":"Flat","filtered":191040,"unfiltered":205024,"noise":1,"rssi":100},{"mgdl":219,"mills":1521896073352,"device":"xDrip-DexcomG5","direction":"Flat","filtered":200160,"unfiltered":209760,"noise":1,"rssi":100},{"mgdl":222,"mills":1521896372890,"device":"xDrip-DexcomG5","direction":"Flat","filtered":207360,"unfiltered":212512,"noise":1,"rssi":100},{"mgdl":220,"mills":1521896673062,"device":"xDrip-DexcomG5","direction":"Flat","filtered":211488,"unfiltered":210688,"noise":1,"rssi":100},{"mgdl":193,"mills":1521896972933,"device":"xDrip-DexcomG5","direction":"Flat","filtered":212384,"unfiltered":208960,"noise":1,"rssi":100},{"mgdl":181,"mills":1521897273336,"device":"xDrip-DexcomG5","direction":"SingleDown","filtered":210592,"unfiltered":204320,"noise":1,"rssi":100},{"mgdl":176,"mills":1521897572875,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":206720,"unfiltered":197440,"noise":1,"rssi":100},{"mgdl":168,"mills":1521897872929,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":201024,"unfiltered":187904,"noise":1,"rssi":100},{"mgdl":161,"mills":1521898172814,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":193376,"unfiltered":178144,"noise":1,"rssi":100},{"mgdl":148,"mills":1521898472879,"device":"xDrip-DexcomG5","direction":"SingleDown","filtered":183264,"unfiltered":161216,"noise":1,"rssi":100},{"mgdl":139,"mills":1521898772862,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":170784,"unfiltered":148928,"noise":1,"rssi":100},{"mgdl":132,"mills":1521899072896,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":157248,"unfiltered":139552,"noise":1,"rssi":100},{"mgdl":125,"mills":1521899372834,"device":"xDrip-DexcomG5","direction":"FortyFiveDown","filtered":144416,"unfiltered":129616.00000000001,"noise":1,"rssi":100},{"mgdl":128,"mills":1521899973456,"device":"xDrip-DexcomG5","direction":"Flat","filtered":130240.00000000001,"unfiltered":133536,"noise":1,"rssi":100},{"mgdl":132,"mills":1521900573287,"device":"xDrip-DexcomG5","direction":"Flat","filtered":133504,"unfiltered":138720,"noise":1,"rssi":100},{"mgdl":127,"mills":1521900873711,"device":"xDrip-DexcomG5","direction":"Flat","filtered":136480,"unfiltered":132992,"noise":1,"rssi":100},{"mgdl":127,"mills":1521901180151,"device":"xDrip-DexcomG5","direction":"Flat","filtered":136896,"unfiltered":132128,"noise":1,"rssi":100},{"mgdl":125,"mills":1521901473582,"device":"xDrip-DexcomG5","direction":"Flat","filtered":134624,"unfiltered":129696,"noise":1,"rssi":100},{"mgdl":120,"mills":1521901773597,"device":"xDrip-DexcomG5","direction":"Flat","filtered":130704.00000000001,"unfiltered":123376,"noise":1,"rssi":100},{"mgdl":116,"mills":1521902075855,"device":"xDrip-DexcomG5","direction":"Flat","filtered":126272,"unfiltered":118448,"noise":1,"rssi":100}]
    private fun generateValidBgData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 219.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 223.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 222.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 224.0, timestamp = 1514765400000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 226.0, timestamp = 1514765100000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return list
    }

    private fun generateValidBgDataWith10mGap(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        //st.add(InMemoryGlucoseValue(value = 219.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 223.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 222.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 224.0, timestamp = 1514765400000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 226.0, timestamp = 1514765100000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return list
    }

    private fun generateValidBgDataWith15mGap(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        //st.add(InMemoryGlucoseValue(value = 219.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        //st.add(InMemoryGlucoseValue(value = 223.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 222.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 224.0, timestamp = 1514765400000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 226.0, timestamp = 1514765100000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return list
    }

    private fun generateDummyBgData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 219.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 223.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 222.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 224.0, timestamp = 1514765400000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 226.0, timestamp = 1514765100000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        return list
    }

    private fun generateValidLibreData(): MutableList<GV> {
        val origData: MutableList<GV> = ArrayList()
        origData.add(GV(value = 114.0, timestamp = 1514766900000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 113.0, timestamp = 1514766840000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 114.0, timestamp = 1514766780000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 116.0, timestamp = 1514766720000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 114.0, timestamp = 1514766660000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 116.0, timestamp = 1514766600000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 116.0, timestamp = 1514766540000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 117.0, timestamp = 1514766480000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 118.0, timestamp = 1514766420000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 117.0, timestamp = 1514766360000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 119.0, timestamp = 1514766300000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 120.0, timestamp = 1514766240000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 123.0, timestamp = 1514766180000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 122.0, timestamp = 1514766120000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 124.0, timestamp = 1514766060000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        origData.add(GV(value = 123.0, timestamp = 1514766000000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2))
        return origData
    }

    // Byte-for-byte copies of generateDummyBgData()/generateValidLibreData() above, except the
    // sensor is SYAI_TAG - not on the hardcoded Libre allowlist - for the Force1MinuteRaw tests.
    private fun generateDummyNonLibreData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 216.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 219.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 223.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 222.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 224.0, timestamp = 1514765400000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 226.0, timestamp = 1514765100000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        return list
    }

    private fun generateValidNonLibreRawData(): MutableList<GV> {
        val origData: MutableList<GV> = ArrayList()
        origData.add(GV(value = 114.0, timestamp = 1514766900000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 113.0, timestamp = 1514766840000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 114.0, timestamp = 1514766780000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 116.0, timestamp = 1514766720000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 114.0, timestamp = 1514766660000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 116.0, timestamp = 1514766600000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 116.0, timestamp = 1514766540000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 117.0, timestamp = 1514766480000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 118.0, timestamp = 1514766420000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 117.0, timestamp = 1514766360000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 119.0, timestamp = 1514766300000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 120.0, timestamp = 1514766240000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 123.0, timestamp = 1514766180000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 122.0, timestamp = 1514766120000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 124.0, timestamp = 1514766060000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 123.0, timestamp = 1514766000000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        return origData
    }

    private fun identitySmoothing(): Smoothing = object : Smoothing {
        override fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> = data
    }

    // 5-minute-spaced bucketed data: delta/5min = (200-190)/5*5 = 10.0.
    private fun generateSlidingDeltaBucketedData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 200.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 190.0, timestamp = 1514766600000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 180.0, timestamp = 1514766300000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 170.0, timestamp = 1514766000000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        list.add(InMemoryGlucoseValue(value = 160.0, timestamp = 1514765700000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        return list
    }

    // 1-minute-spaced raw data, SYAI_TAG (needs ApsAutoIsfForce1MinuteRaw to be raw-eligible).
    // orig[0]-orig[2] = 120s < 3min, so it passes the empirical timing check. Sliding delta over
    // exactly 5 minutes = now(120.0) - at(t-5min, a direct hit at index 5, value 100.0) = 20.0.
    private fun generateSlidingDeltaRawData(): MutableList<GV> {
        val origData: MutableList<GV> = ArrayList()
        origData.add(GV(value = 120.0, timestamp = 1514766900000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 118.0, timestamp = 1514766840000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 116.0, timestamp = 1514766780000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 114.0, timestamp = 1514766720000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 112.0, timestamp = 1514766660000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 100.0, timestamp = 1514766600000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 98.0, timestamp = 1514766540000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        origData.add(GV(value = 96.0, timestamp = 1514766480000, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.SYAI_TAG))
        return origData
    }

    private fun generateDummyLibreData(): MutableList<GV> {
        val origData: MutableList<GV> = ArrayList()
        origData.add(GV(timestamp = 1514766900000, value = 214.0, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return origData
    }

    private fun generateInsufficientBgData(): MutableList<InMemoryGlucoseValue> {
        return ArrayList()
    }

    private fun generateOldBgData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 228.0, timestamp = 1514764800000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return list
    }

    private fun generateOneCurrentRecordBgData(): MutableList<InMemoryGlucoseValue> {
        val list: MutableList<InMemoryGlucoseValue> = ArrayList()
        list.add(InMemoryGlucoseValue(value = 214.0, timestamp = 1514766900000, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN))
        return list
    }
}
