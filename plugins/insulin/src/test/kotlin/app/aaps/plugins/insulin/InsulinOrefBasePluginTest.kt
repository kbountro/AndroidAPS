package app.aaps.plugins.insulin

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class InsulinOrefBasePluginTest : TestBase() {

    var testPeak = 0
    var testUserDefinedDia = 0.0
    var shortDiaNotificationSend = false

    inner class InsulinBaseTest(
        preferences: Preferences,
        rh: ResourceHelper,
        profileFunction: ProfileFunction,
        rxBus: RxBus,
        aapsLogger: AAPSLogger,
        config: Config,
        hardLimits: HardLimits
    ) : InsulinOrefBasePlugin(preferences, rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

        override fun sendShortDiaNotification(dia: Double) {
            shortDiaNotificationSend = true
        }

        override val userDefinedDia: Double
            get() = testUserDefinedDia

        override val peak: Int
            get() = testPeak

        override fun commentStandardText(): String = ""
        override val id get(): Insulin.InsulinType = Insulin.InsulinType.UNKNOWN
        override val friendlyName get(): String = ""
        override fun configuration(): JSONObject = JSONObject()
        override fun applyConfiguration(configuration: JSONObject) {}
    }

    private lateinit var sut: InsulinBaseTest

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var preferences: Preferences

    @BeforeEach
    fun setUp() {
        sut = InsulinBaseTest(preferences, rh, profileFunction, rxBus, aapsLogger, config, hardLimits)
        whenever(hardLimits.minDia()).thenReturn(5.0)
    }

    @Test
    fun testGetDia() {
        assertThat(sut.dia).isEqualTo(5.0)
        testUserDefinedDia = 5.0 + 1
        assertThat(sut.dia).isEqualTo(5.0 + 1)
        testUserDefinedDia = 5.0 - 1
        assertThat(sut.dia).isEqualTo(5.0)
        assertThat(shortDiaNotificationSend).isTrue()
    }

    @Test
    fun testIobCalcForTreatment() {
        val treatment = BS(timestamp = 0, amount = 10.0, type = BS.Type.NORMAL)
        testPeak = 30
        testUserDefinedDia = 4.0
        val time = System.currentTimeMillis()
        // check directly after bolus
        treatment.timestamp = time
        treatment.amount = 10.0
        assertThat(sut.iobCalcForTreatment(treatment, time, Constants.defaultDIA).iobContrib).isWithin(0.01).of(10.0)
        // check after 1 hour
        treatment.timestamp = time - 1 * 60 * 60 * 1000 // 1 hour
        treatment.amount = 10.0
        assertThat(sut.iobCalcForTreatment(treatment, time, Constants.defaultDIA).iobContrib).isWithin(0.01).of(3.92)
        // check after 2 hour
        treatment.timestamp = time - 2 * 60 * 60 * 1000 // 2 hours
        treatment.amount = 10.0
        assertThat(sut.iobCalcForTreatment(treatment, time, Constants.defaultDIA).iobContrib).isWithin(0.01).of(0.77)
        // check after 3 hour
        treatment.timestamp = time - 3 * 60 * 60 * 1000 // 3 hours
        treatment.amount = 10.0
        assertThat(sut.iobCalcForTreatment(treatment, time, Constants.defaultDIA).iobContrib).isWithin(0.01).of(0.10)
        // check after dia
        treatment.timestamp = time - 4 * 60 * 60 * 1000 // 4 hours
        treatment.amount = 10.0
        assertThat(sut.iobCalcForTreatment(treatment, time, Constants.defaultDIA).iobContrib).isWithin(0.01).of(0.0)
    }

    @Test
    fun trafficJamCgmLagDelaysTheActivityButKeepsItsTotal() {
        pulses()
        val bolus = BS(timestamp = 0, amount = 1.0, type = BS.Type.NORMAL)
        fun curve(lagMinutes: Double) = run {
            whenever(preferences.get(DoubleKey.InsulinTrafficJamCgmLag)).thenReturn(lagMinutes)
            (0 until 480).map { sut.trafficJamPdModelIobCalculation(bolus, it.toDouble()) }
        }
        val plain = curve(0.0)
        val lagged = curve(8.0)

        val plainPeak = plain.indices.maxBy { plain[it].activityContrib }
        val laggedPeak = lagged.indices.maxBy { lagged[it].activityContrib }
        assertThat(laggedPeak).isGreaterThan(plainPeak + 3)
        assertThat(laggedPeak).isLessThan(plainPeak + 13)
        assertThat(lagged.sumOf { it.activityContrib }).isWithin(1e-3).of(plain.sumOf { it.activityContrib })
        // The whole dose is on board at once (less the 0.23% still on board at the 480-min horizon, which the preview
        // subtracts so that IOB ends at 0 there), and the effect still on its way to the CGM counts as on board.
        assertThat(lagged[0].iobContrib).isWithin(3e-3).of(1.0)
        assertThat(lagged[60].iobContrib).isGreaterThan(plain[60].iobContrib)
    }

    /** The pump study's pulses, 0.05 U at 1.5 U/min, at speed 1 and dose interaction [mixing]. */
    private fun pulses(mixing: Double = 1.0) {
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamPulseSize)).thenReturn(0.05)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamBolusSpeed)).thenReturn(1.5)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamMixing)).thenReturn(mixing)
    }

    @Test
    fun trafficJamABolusOfOnePulseIsOneDose() {
        // One pulse is one dose at the bolus's timestamp, at any dose interaction: the same as a basal part of its
        // amount, which is never pulsed.
        val pulse = BS(timestamp = 0, amount = 0.05, type = BS.Type.SMB)
        val part = BS(timestamp = 0, amount = 0.05, type = BS.Type.NORMAL, isBasalInsulin = true)
        pulses()
        val whole = (0 until 480).map { sut.trafficJamPdModelIobCalculation(part, it + 0.37) }
        for (mixing in listOf(0.0, 0.4, 1.0)) {
            pulses(mixing)
            for (i in 0 until 480) {
                val pulsed = sut.trafficJamPdModelIobCalculation(pulse, i + 0.37)
                assertThat(pulsed.iobContrib).isWithin(1e-12).of(whole[i].iobContrib)
                assertThat(pulsed.activityContrib).isWithin(1e-12).of(whole[i].activityContrib)
            }
        }
    }

    @Test
    fun trafficJamABolusAbsorbsAsLonePulsesBelowFullInteraction() {
        val bolus = BS(timestamp = 0, amount = 3.0, type = BS.Type.NORMAL) // 60 pulses, at 29 and 89 s
        fun curve(mixing: Double) = run {
            pulses(mixing)
            (0 until 480).map { sut.trafficJamPdModelIobCalculation(bolus, it.toDouble()) }
        }
        val pooled = curve(1.0)
        val lone = curve(0.0)
        // Each pulse alone absorbs faster than the whole bolus in one pool (the plasma and effect stages after the depot
        // are the same for both)...
        assertThat(lone.indices.maxBy { lone[it].activityContrib }).isLessThan(pooled.indices.maxBy { pooled[it].activityContrib } - 6)
        assertThat(lone[60].iobContrib).isLessThan(pooled[60].iobContrib - 0.1)
        // ...with the same total effect; and the whole bolus is on board from its timestamp, given yet or not (less what
        // is still on board at the 480-min horizon, which the preview subtracts).
        assertThat(lone.sumOf { it.activityContrib }).isWithin(0.01).of(pooled.sumOf { it.activityContrib })
        assertThat(pooled[0].iobContrib).isWithin(1e-2).of(3.0)
        assertThat(lone[0].iobContrib).isWithin(1e-2).of(3.0)
        assertThat(pooled[0].activityContrib).isEqualTo(0.0)
    }

    @Test
    fun trafficJamBasalPartsStayOneDose() {
        // The stock calculator's basal, temp basal and extended bolus parts are never pulsed: one dose, the same at every
        // dose interaction, while a bolus of the same amount absorbs faster as lone pulses below full interaction.
        val part = BS(timestamp = 0, amount = 0.4, type = BS.Type.NORMAL, isBasalInsulin = true)
        val bolus = BS(timestamp = 0, amount = 0.4, type = BS.Type.NORMAL)
        pulses(0.0)
        val alone = (0 until 480).map { sut.trafficJamPdModelIobCalculation(part, it + 0.5) }
        val lonePulses = (0 until 480).map { sut.trafficJamPdModelIobCalculation(bolus, it + 0.5) }
        pulses(1.0)
        val pooled = (0 until 480).map { sut.trafficJamPdModelIobCalculation(part, it + 0.5) }
        for (i in 0 until 480) {
            assertThat(pooled[i].iobContrib).isEqualTo(alone[i].iobContrib)
            assertThat(pooled[i].activityContrib).isEqualTo(alone[i].activityContrib)
        }
        assertThat(lonePulses[30].iobContrib).isLessThan(alone[30].iobContrib)
    }
}
