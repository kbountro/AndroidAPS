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
import app.aaps.core.keys.BooleanKey
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
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)).thenReturn(1.0)
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
        // The whole dose is on board at once, and the effect still on its way to the CGM counts as on board.
        assertThat(lagged[0].iobContrib).isWithin(1e-6).of(1.0)
        assertThat(lagged[60].iobContrib).isGreaterThan(plain[60].iobContrib)
    }

    private fun microDoses(on: Boolean, mixing: Double = 1.0) {
        whenever(preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamPulseSize)).thenReturn(0.05)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamBolusSpeed)).thenReturn(1.5)
        whenever(preferences.get(DoubleKey.InsulinTrafficJamMixing)).thenReturn(mixing)
        whenever(preferences.get(BooleanKey.InsulinTrafficJamMicroDoses)).thenReturn(on)
    }

    @Test
    fun trafficJamMicroDosesLeaveABolusOfOnePulseAsItWas() {
        // One pulse is one dose at the bolus's timestamp, at any dose interaction.
        val pulse = BS(timestamp = 0, amount = 0.05, type = BS.Type.SMB)
        microDoses(false)
        val whole = (0 until 480).map { sut.trafficJamPdModelIobCalculation(pulse, it + 0.37) }
        for (mixing in listOf(0.0, 0.4, 1.0)) {
            microDoses(true, mixing)
            for (i in 0 until 480) {
                val pulsed = sut.trafficJamPdModelIobCalculation(pulse, i + 0.37)
                assertThat(pulsed.iobContrib).isWithin(1e-12).of(whole[i].iobContrib)
                assertThat(pulsed.activityContrib).isWithin(1e-12).of(whole[i].activityContrib)
            }
        }
    }

    @Test
    fun trafficJamMicroDosesAbsorbABolusAsLonePulsesBelowFullInteraction() {
        val bolus = BS(timestamp = 0, amount = 3.0, type = BS.Type.NORMAL) // 60 pulses, at 29 and 89 s
        fun curve(mixing: Double) = run {
            microDoses(true, mixing)
            (0 until 480).map { sut.trafficJamPdModelIobCalculation(bolus, it.toDouble()) }
        }
        val pooled = curve(1.0)
        val pulses = curve(0.0)
        // Each pulse alone absorbs much faster than the whole bolus in one pool...
        assertThat(pulses.indices.maxBy { pulses[it].activityContrib }).isLessThan(pooled.indices.maxBy { pooled[it].activityContrib } - 10)
        assertThat(pulses[60].iobContrib).isLessThan(pooled[60].iobContrib - 0.2)
        // ...with the same total effect; and the whole bolus is on board from its timestamp, given yet or not.
        assertThat(pulses.sumOf { it.activityContrib }).isWithin(0.01).of(pooled.sumOf { it.activityContrib })
        assertThat(pooled[0].iobContrib).isWithin(1e-6).of(3.0)
        assertThat(pulses[0].iobContrib).isWithin(1e-6).of(3.0)
        assertThat(pooled[0].activityContrib).isEqualTo(0.0)
    }

    @Test
    fun trafficJamMicroDosesLeaveBasalPartsAsOneDose() {
        // The stock calculator's basal, temp basal and extended bolus parts are never pulsed.
        val part = BS(timestamp = 0, amount = 0.4, type = BS.Type.NORMAL, isBasalInsulin = true)
        microDoses(true, 0.3)
        val on = (0 until 480).map { sut.trafficJamPdModelIobCalculation(part, it + 0.5) }
        microDoses(false, 0.3)
        val off = (0 until 480).map { sut.trafficJamPdModelIobCalculation(part, it + 0.5) }
        for (i in 0 until 480) {
            assertThat(on[i].iobContrib).isEqualTo(off[i].iobContrib)
            assertThat(on[i].activityContrib).isEqualTo(off[i].activityContrib)
        }
    }
}
