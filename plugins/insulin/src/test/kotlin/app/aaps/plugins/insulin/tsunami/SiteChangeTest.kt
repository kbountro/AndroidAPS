package app.aaps.plugins.insulin.tsunami

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.ActivityMultiplierHistory
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Site changes in the Traffic Jam engine ([TsunamiIobEngineImpl]): the insulin at the old site and at the new one never
 * crowd each other, a new site's ramp applies to the insulin given to it only, priming insulin is never on board, and a
 * bolus stays at the site it started on. No basal (a profile rate of 0), a CGM lag of 8 minutes, the defaults otherwise.
 */
class SiteChangeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val change = 1_700_000_000_000L / minute * minute

    /** A bolus's IOB, activity and snooze at one time. */
    private data class Point(val iob: Double, val activity: Double, val snooze: Double)

    /** The bolus totals every 5 minutes for 8 hours from [from], for [boluses] and cannula changes at [siteChanges]. */
    private fun run(boluses: List<BS>, siteChanges: List<Long>, from: Long, mixing: Double = 1.0): List<Point> {
        val preferences = mock<Preferences>()
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSiteStartSpeed)).thenReturn(0.47)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSiteRamp)).thenReturn(48.0)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamMixing)).thenReturn(mixing)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamCgmLag)).thenReturn(8.0)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamPulseSize)).thenReturn(0.05)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamBolusSpeed)).thenReturn(1.5)
        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(2.0)
        val persistenceLayer = mock<PersistenceLayer>()
        whenever(persistenceLayer.getBolusesFromTime(any(), any())).thenAnswer { invocation ->
            Single.just(boluses.filter { it.timestamp >= invocation.getArgument<Long>(0) })
        }
        whenever(persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getTherapyEventDataFromTime(any(), any<TE.Type>(), any())).thenAnswer { invocation ->
            siteChanges.filter { it >= invocation.getArgument<Long>(0) }.map { TE(timestamp = it, type = TE.Type.CANNULA_CHANGE, glucoseUnit = GlucoseUnit.MGDL) }
        }
        val profile = mock<Profile>() // a basal rate of 0
        val profileFunction = mock<ProfileFunction>()
        whenever(profileFunction.getProfile(any<Long>())).thenReturn(profile)
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.now()).thenReturn(from + 10 * hour)
        val pump = mock<Pump>()
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(false)
        val activePlugin = mock<ActivePlugin>()
        whenever(activePlugin.activePump).thenReturn(pump)
        val multipliers = mock<ActivityMultiplierHistory>()
        whenever(multipliers.multiplierAt(any())).thenReturn(1.0)
        val rxBus = mock<RxBus>()
        whenever(rxBus.toObservable(EventNewHistoryData::class.java)).thenReturn(Observable.never())
        whenever(rxBus.toObservable(EventTherapyEventChange::class.java)).thenReturn(Observable.never())
        val engine = TsunamiIobEngineImpl(preferences, persistenceLayer, profileFunction, dateUtil, activePlugin, multipliers, rxBus)
        return (0..96).map { k ->
            val total = engine.resultAt(from + k * 5 * minute, 1.0).bolusTotal
            Point(total.iob, total.activity, total.bolussnooze)
        }
    }

    private fun bolus(time: Long, amount: Double, type: BS.Type = BS.Type.NORMAL) = BS(timestamp = time, amount = amount, type = type)

    private fun sum(a: List<Point>, b: List<Point>) = a.zip(b) { x, y -> Point(x.iob + y.iob, x.activity + y.activity, x.snooze + y.snooze) }

    private fun assertSameCurves(actual: List<Point>, expected: List<Point>, tolerance: Double) {
        assertThat(actual.size).isEqualTo(expected.size)
        for (i in actual.indices) {
            assertThat(actual[i].iob).isWithin(tolerance).of(expected[i].iob)
            assertThat(actual[i].activity).isWithin(tolerance).of(expected[i].activity)
            assertThat(actual[i].snooze).isWithin(tolerance).of(expected[i].snooze)
        }
    }

    @Test
    fun theOldAndTheNewSiteDoNotCrowdEachOther() {
        // Ten minutes apart, the site changed in between (the first bolus's pulses take 2 minutes).
        val atOldSite = bolus(change - 6 * minute, 3.0)
        val atNewSite = bolus(change + 4 * minute, 4.0)
        val from = change - 10 * minute
        for (mixing in listOf(1.0, 0.4)) {
            // With a site change between them, the two boluses are the sum of each alone...
            val both = run(listOf(atOldSite, atNewSite), listOf(change), from, mixing)
            val apart = sum(run(listOf(atOldSite), listOf(change), from, mixing), run(listOf(atNewSite), listOf(change), from, mixing))
            assertSameCurves(both, apart, 1e-12)
            // ...where at one site they slow each other down.
            val oneSite = run(listOf(atOldSite, atNewSite), emptyList(), from, mixing)
            val alone = sum(run(listOf(atOldSite), emptyList(), from, mixing), run(listOf(atNewSite), emptyList(), from, mixing))
            assertThat(oneSite.indices.maxOf { oneSite[it].iob - alone[it].iob }).isGreaterThan(CROWDING_SEEN * mixing)
        }
    }

    @Test
    fun aNewSiteRampsOnlyTheInsulinGivenToIt() {
        val atOldSite = bolus(change - 30 * minute, 3.0)
        val from = change - 40 * minute
        // The old site's insulin absorbs as if the site had not been changed...
        assertSameCurves(run(listOf(atOldSite), listOf(change), from), run(listOf(atOldSite), emptyList(), from), 0.0)
        // ...also while the old site is still on its own ramp, which the change does not restart.
        val earlier = change - 20 * hour
        assertSameCurves(run(listOf(atOldSite), listOf(earlier, change), from), run(listOf(atOldSite), listOf(earlier), from), 0.0)
        // The new site's insulin starts at the new-site speed, slower than at a settled site...
        val atNewSite = bolus(change + 10 * minute, 3.0)
        val newSite = run(listOf(atNewSite), listOf(change), from)
        val settled = run(listOf(atNewSite), emptyList(), from)
        val oneHourAfter = 22 // (50 + 60) min after `from`, in 5-min steps
        assertThat(newSite[oneHourAfter].iob).isGreaterThan(settled[oneHourAfter].iob + RAMP_SEEN)
        // ...and is at full speed once the ramp is over.
        val afterRamp = bolus(change + 48 * hour, 3.0)
        val later = change + 47 * hour
        assertSameCurves(run(listOf(afterRamp), listOf(change), later), run(listOf(afterRamp), emptyList(), later), 0.0)
    }

    @Test
    fun aPrimingBolusIsNeverOnBoard() {
        val prime = bolus(change, 0.7, BS.Type.PRIMING)
        val dose = bolus(change + 10 * minute, 2.0)
        val from = change - 10 * minute
        for (mixing in listOf(1.0, 0.4)) {
            assertSameCurves(run(listOf(prime, dose), listOf(change), from, mixing), run(listOf(dose), listOf(change), from, mixing), 0.0)
        }
    }

    @Test
    fun aBolusStaysAtTheSiteItStartedOn() {
        // 6 U take 4 minutes at 1.5 U/min: a site change logged 2 minutes into the bolus does not move its last pulses
        // to the new site.
        val dose = bolus(change - 2 * minute, 6.0)
        val from = change - 5 * minute
        for (mixing in listOf(1.0, 0.4)) {
            assertSameCurves(run(listOf(dose), listOf(change), from, mixing), run(listOf(dose), emptyList(), from, mixing), 0.0)
        }
    }

    companion object {

        /** Less than the extra IOB, U, that the two boluses ten minutes apart at one site show from crowding (0.08 U). */
        private const val CROWDING_SEEN = 0.02

        /** Less than the extra IOB, U, a 3 U bolus on a brand-new site shows an hour after it against a settled site (0.25 U). */
        private const val RAMP_SEEN = 0.1
    }
}
