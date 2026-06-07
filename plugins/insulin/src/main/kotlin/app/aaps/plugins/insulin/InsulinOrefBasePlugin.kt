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

    /**
     * STATEFUL IOB CALCULATION
     * This is the custom entry point for the custom IobCobCalculator.
     * It accepts the physical scMass to dynamically delay the Tsunami curve.
     */
    fun iobCalcWithState(bolus: BS, time: Long, scMass: Double): Iob {
        val insulinInterface = activePlugin.activeInsulin
        val insulinID = insulinInterface.id.value 
        val result = Iob()
        
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            
            // If it's a Tsunami insulin (105 or 205), route it through the Compartment Model
            if (t < 8 * 60 && (insulinID == 105 || insulinID == 205)) { 
                val isU200 = (insulinID == 205)
                val pdResult = pdModelIobCalculation(bolus.amount, scMass, isU200, t)
                result.iobContrib = pdResult.iobContrib
                result.activityContrib = pdResult.activityContrib
            } else { 
                // Fallback to the standard vanilla AndroidAPS bilinear curve
                val td = dia * 60 
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

    fun pdModelIobCalculation(bolusAmount: Double, scMass: Double, isU200: Boolean, t: Double): Iob {
        // 1. Get the dynamic peak time from our new CompartmentModel!
        // We feed it the accumulated scMass, NOT just the isolated bolus.
        val tpModelRaw = CompartmentModel.calculateSystemicPeak(scMass, isU200)
        
        // 2. Transform the peak time for the equation (as per original logic)
        val tpModel = tpModelRaw.pow(2.0) * 2 
        
        val result = Iob()
        
        // 3. Calculate Activity 
        // NOTE: We use the literal bolusAmount for the amplitude, 
        // but the scMass-derived tpModel for the delayed curve shape!
        result.activityContrib = (2 * bolusAmount / tpModel) * t * exp(-t.pow(2.0) / tpModel)

        // 4. Calculate IOB (Integrated Activity)
        val lowerLimit = t 
        val upperLimit = 8.0 * 60 
        result.iobContrib = bolusAmount * (exp(-lowerLimit.pow(2.0)/tpModel) - exp(-upperLimit.pow(2.0)/tpModel))

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