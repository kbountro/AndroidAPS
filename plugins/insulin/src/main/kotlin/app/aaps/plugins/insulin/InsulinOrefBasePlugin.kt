package app.aaps.plugins.insulin

import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.pow

/**
 * Created by adrian on 13.08.2017.
 *
 * parameters are injected from child class
 *
 */
abstract class InsulinOrefBasePlugin(
    rh: ResourceHelper,
    val profileFunction: ProfileFunction,
    val rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.INSULIN)
        .fragmentClass(InsulinFragment::class.java.name)
        .pluginIcon(R.drawable.ic_insulin)
        .shortName(R.string.insulin_shortname)
        .visibleByDefault(false)
        .neverVisible(config.AAPSCLIENT),
    aapsLogger, rh
), Insulin {

    private var lastWarned: Long = 0
    override val dia
        get(): Double {
            val dia = userDefinedDia
            return if (dia >= hardLimits.minDia()) {
                dia
            } else {
                sendShortDiaNotification(dia)
                hardLimits.minDia()
            }
        }

    open fun sendShortDiaNotification(dia: Double) {
        if (System.currentTimeMillis() - lastWarned > 60 * 1000) {
            lastWarned = System.currentTimeMillis()
            uiInteraction.addNotification(Notification.SHORT_DIA, String.format(notificationPattern, dia, hardLimits.minDia()), Notification.URGENT)
        }
    }

    private val notificationPattern: String
        get() = rh.gs(R.string.dia_too_short)

    open val userDefinedDia: Double
        get() {
            val profile = profileFunction.getProfile()
            return profile?.dia ?: hardLimits.minDia()
        }
/*
    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60 //getDIA() always >= MIN_DIA
            val tp = peak.toDouble()
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }
*/
    @Inject lateinit var activePlugin: ActivePlugin //MP for Tsunami PD models
    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double, backgroundIob: Double, usePkCurve: Boolean): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val insulinInterface = activePlugin.activeInsulin
        val insulinID = insulinInterface.id.value
        val result = Iob()

        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60

            // Hardcoded 8-hour limit for Tsunami --- kbountro: 9-hour
            if (t < 9 * 60 && (insulinID == 105 || insulinID == 205)) {
                val pdResult = pdModelIobCalculation(bolus, insulinID, t, backgroundIob, usePkCurve)
                result.iobContrib = pdResult.iobContrib
                result.activityContrib = pdResult.activityContrib
            } else {
                val tp = peak.toDouble()
                if (t < td) {
                    val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                    val a = 2 * tau / td
                    val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                    result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                    result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
                }
            }
        }
        return result
    }

    fun pdModelIobCalculation(bolus: BS, insulinID: Int, t: Double, backgroundIob: Double, usePkCurve: Boolean): Iob {
        val a0 = 61.33
        val a1 = 12.27
        val b1 = 0.05185

        // 1. THE STACKING EFFECT: Calculate precise peak using the TOTAL active insulin pool
        val effectiveDose = bolus.amount + backgroundIob

        var tp: Double = if (insulinID == 205) {
            (a0 + a1 * 2 * effectiveDose) / (1 + b1 * 2 * effectiveDose)
        } else {
            (a0 + a1 * effectiveDose) / (1 + b1 * effectiveDose)
        }

        // 2. PK HEURISTIC SHIFT: If evaluating the physical pool, shift the peak to 47%
        if (usePkCurve) {
            tp *= 0.47
        }

        val tpModel = tp.pow(2.0) * 2
        val result = Iob()

        // 3. ACTIVITY MULTIPLIER: Use ONLY bolus.amount so we don't duplicate insulin
        result.activityContrib = (2 * bolus.amount / tpModel) * t * exp(-t.pow(2.0) / tpModel)

        val lowerLimit = t
        val upperLimit = 9.0 * 60.0

        result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0) / tpModel) - exp(-upperLimit.pow(2.0) / tpModel))

        return result
    }

    override val iCfg: ICfg
        get() = ICfg(friendlyName, (dia * 1000.0 * 3600.0).toLong(), T.mins(peak.toLong()).msecs())

    override val comment
        get(): String {
            var comment = commentStandardText()
            val userDia = userDefinedDia
            if (userDia < hardLimits.minDia()) {
                comment += "\n" + rh.gs(R.string.dia_too_short, userDia, hardLimits.minDia())
            }
            return comment
        }

    abstract override val peak: Int
    abstract fun commentStandardText(): String
}