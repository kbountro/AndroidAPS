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
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Created by adrian on 13.08.2017.
 *
 * parameters are injected from child class
 *
 */
abstract class InsulinOrefBasePlugin(
    val preferences: Preferences,
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

    companion object {

        // Must match TsunamiIobEngineImpl's DIA horizon (8h) - kept separate since that
        // engine constant is private and this preview path is decoupled from the pooled engine.
        private const val TRAFFIC_JAM_HORIZON_MINUTES = 480.0

        // The "Effective DIA" preference (DoubleKey.InsulinTrafficJamEffectiveDia) is defined at
        // this reference dose and IOB-remaining threshold - see that key's doc comment for why
        // (1U: the smallest dose actually inside the crowding-refit's target range; 1%: a small,
        // easily-explained "done" cutoff for an otherwise-asymptotic curve). Must match the
        // reference point TsunamiIobEngineImpl's PdModel.speedFactorForEffectiveDia uses.
        private const val EFFECTIVE_DIA_REFERENCE_DOSE_U = 1.0
        private const val EFFECTIVE_DIA_IOB_FRACTION_THRESHOLD = 0.01
    }

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
    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val insulinInterface = activePlugin.activeInsulin
        val insulinID = insulinInterface.id.value
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0

            // Hardcoded 8-hour limit for Tsunami
            if (t < 8 * 60 && (insulinID == 105 || insulinID == 205)) {
                val pdResult = pdModelIobCalculation(bolus, insulinID, t)
                result.iobContrib = pdResult.iobContrib
                result.activityContrib = pdResult.activityContrib
            } else if (t < TRAFFIC_JAM_HORIZON_MINUTES && insulinID == 106) {
                val pdResult = trafficJamPdModelIobCalculation(bolus, t)
                result.iobContrib = pdResult.iobContrib
                result.activityContrib = pdResult.activityContrib
            } else { // MP: If the pharmacodynamic models are not used (IDs 105 & 205), use the traditional PK-based insulin model instead;
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
        }
        return result
    }

    fun pdModelIobCalculation(bolus: BS, insulinID: Int, t: Double): Iob {
        //MP Model for estimation of PD-based peak time: (a0 + a1*X)/(1+b1*X), where X = bolus size
        val a0 = 61.33 //MP Units = min
        val a1 = 12.27
        val b1 = 0.05185
        val tp: Double
        val result = Iob()
        if (insulinID == 205) { //MP ID = 205 for Lyumjev U200
            tp = (a0 + a1 * 2 * bolus.amount)/(1 + b1 * 2 * bolus.amount) //MP Units = min
        } else {
            tp = (a0 + a1 * bolus.amount) / (1 + b1 * bolus.amount) //MP Units = min
        }
        val tpModel = tp.pow(2.0) * 2 //MP The peak time in the model is defined as half of the square root of this variable - thus the tp entered into the model must be transformed first
        /**
         *
         * MP - UAM Tsunami PD model U100 vs U200
         *
         * Insulin Activity calculation below: The same formula is used for both, U100 and U200
         * insulin as the concentration effect is already included in the peak time calculation.
         * If peak time is kept constant and only the dose is doubled, the general shape of the
         * curve doesn't change and hence the equation does not need adjusting. Unless a global
         * U200 mode is introduced where ISF between U100 and U200 has the same value (i.e.: When
         * ISF doubling and basal halving is done in AAPS' calculations and not by the user), the
         * equation doesn't need any changing.
         * The user must keep in mind that the displayed IOB is only half of the actual IOB.
         *
         */
        result.activityContrib = (2 * bolus.amount / tpModel) * t * exp(-t.pow(2.0) / tpModel)

        //MP New IOB formula - integrated version of the above activity curve
        val lowerLimit = t //MP lower integration limit, in min
        val upperLimit = 8.0 * 60 //MP upper integration limit, in min
        result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0)/tpModel) - exp(-upperLimit.pow(2.0)/tpModel))

        return result
    }

    /**
     * Isolated single-dose 3-compartment PD model for Traffic Jam (insulin ID 106), used both by
     * the profile screen's sample activity/IOB preview graph and by this plugin's own per-treatment
     * summation whenever it runs without going through the pooled TsunamiAwareIobCobCalculator
     * decorator. Mirrors TsunamiIobEngineImpl's PdModel (D -> X -> A compartment chain; see that
     * object's doc comment for the full derivation and calibration) for an unpooled, single-bolus
     * case - the actual Traffic Jam engine additionally accounts for pooling with other doses,
     * which is out of scope here. Kept as a separate copy since that engine's constants are
     * private and this path is decoupled from the pooled engine.
     *
     * Note this also fixes a pre-existing bug in the previous Weibull version of this function:
     * activityContrib was never scaled by bolus.amount here (unlike iobContrib, and unlike the
     * sibling pdModelIobCalculation above), silently under-reporting activity by a factor of the
     * dose size wherever this path was actually reached.
     */
    fun trafficJamPdModelIobCalculation(bolus: BS, t: Double): Iob {
        val ke = 0.0157533 // ln(2)/44 - real lispro serum elimination half-life, fixed - NEVER scaled by speed
        // widened refit (1/3.5/7/15/30U vs TsunamiMods), in effect - see PdModel's doc comment in
        // TsunamiIobEngineImpl for the full rationale (small-SMB cascade match vs single-dose accuracy)
        val k2Base = 0.031796 // transit-stage rate, fixed per-dose, scaled below by the user's own speed
        // val k2Base = 0.019023 // official: EPAR-only fit (7/15/30U), kept for rollback
        val k1B0 = 0.069092
        val k1B1 = -0.596593
        // val k1B0 = 0.110235; val k1B1 = -0.644422 // official: EPAR-only fit, kept for rollback

        // Per-patient absorption speed knob (see DoubleKey.InsulinTrafficJamEffectiveDia): only the
        // depot/transit rates (k1, k2) scale, never ke - see that key's doc comment for why.
        val speedFactor = speedFactorForEffectiveDia(preferences.get(DoubleKey.InsulinTrafficJamEffectiveDia))
        val k2 = speedFactor * k2Base
        val k1 = speedFactor * k1B0 * bolus.amount.pow(k1B1) // crowding-dependent absorption rate (unpooled: driven by this dose's own amount)
        val result = Iob()

        val d = exp(-k1 * t)
        val x = chainStage2(k1, k2, t)
        val a = threeStageA(k1, k2, ke, t)

        // Subtract the (small, non-zero) residual right at the gating horizon so IOB reaches
        // exactly zero there instead of a visible cliff where the caller stops calling this at all.
        val horizon = TRAFFIC_JAM_HORIZON_MINUTES
        val dH = exp(-k1 * horizon)
        val xH = chainStage2(k1, k2, horizon)
        val aH = threeStageA(k1, k2, ke, horizon)

        result.activityContrib = bolus.amount * ke * a
        result.iobContrib = bolus.amount * ((d + x + a) - (dH + xH + aH))

        return result
    }

    /**
     * Inverts the user-facing "Effective DIA" (hours, time for a lone [EFFECTIVE_DIA_REFERENCE_DOSE_U]
     * dose to decay to [EFFECTIVE_DIA_IOB_FRACTION_THRESHOLD] of IOB) into the internal speed
     * multiplier applied to k1/k2. No closed form exists (the underlying curve is a sum of
     * exponentials), so this bisects: isolated-dose IOB fraction at the reference dose is strictly
     * monotonic in speed (faster absorption -> reference dose clears sooner), so a fixed number of
     * bisection steps always converges. See TsunamiIobEngineImpl's PdModel.speedFactorForEffectiveDia
     * for the pooled engine's copy of this same inversion.
     */
    private fun speedFactorForEffectiveDia(effectiveDiaHours: Double): Double {
        val targetMinutes = effectiveDiaHours * 60.0
        val k2Base = 0.031796
        val k1B0 = 0.069092
        val k1B1 = -0.596593
        val ke = 0.0157533

        fun isolatedIobFractionAtReferenceDose(speed: Double): Double {
            val k1 = speed * k1B0 * EFFECTIVE_DIA_REFERENCE_DOSE_U.pow(k1B1)
            val k2 = speed * k2Base
            val d = exp(-k1 * targetMinutes)
            val x = chainStage2(k1, k2, targetMinutes)
            val a = threeStageA(k1, k2, ke, targetMinutes)
            return d + x + a
        }

        var lo = 0.01 // slowest allowed speed
        var hi = 100.0 // fastest allowed speed
        repeat(60) {
            val mid = (lo + hi) / 2.0
            // Higher speed -> faster clearance -> lower remaining fraction at a fixed target time.
            if (isolatedIobFractionAtReferenceDose(mid) > EFFECTIVE_DIA_IOB_FRACTION_THRESHOLD) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** Second stage of a unit-dose 2-compartment chain entering at rate [r1], draining at [r2]. */
    private fun chainStage2(r1: Double, r2: Double, t: Double): Double {
        val eps = 1e-7
        return if (abs(r2 - r1) < eps) r1 * t * exp(-r1 * t)
        else r1 * (exp(-r1 * t) - exp(-r2 * t)) / (r2 - r1)
    }

    /** Third stage (active pool) of a unit-dose 3-compartment chain (k1, k2, ke). Degenerate-safe. */
    private fun threeStageA(k1: Double, k2: Double, ke: Double, t: Double): Double {
        val eps = 1e-7
        val k1k2 = abs(k2 - k1) < eps
        val k1ke = abs(ke - k1) < eps
        val k2ke = abs(ke - k2) < eps
        return when {
            k1k2 && k1ke -> k1 * k1 * t * t / 2.0 * exp(-k1 * t)
            k1k2         -> {
                val q = ke - k1
                k1 * k1 * (t * exp(-k1 * t) / q - (exp(-k1 * t) - exp(-ke * t)) / (q * q))
            }

            k1ke         -> {
                val d = k2 - k1
                k1 * k2 * t * exp(-k1 * t) / d - k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d)
            }

            k2ke         -> {
                val d = k2 - k1
                k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d) - k1 * k2 * t * exp(-k2 * t) / d
            }

            else         -> k1 * k2 * (
                exp(-k1 * t) / ((k2 - k1) * (ke - k1)) +
                    exp(-k2 * t) / ((k1 - k2) * (ke - k2)) +
                    exp(-ke * t) / ((k1 - ke) * (k2 - ke))
                )
        }
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