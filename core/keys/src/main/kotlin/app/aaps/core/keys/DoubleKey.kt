package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 0.15, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.2, 0.1, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.1, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),
    FslCalOffset("fslCal_Offset", 0.0, -50.0, 50.0, defaultedBySM = true),      //dependency = BooleanKey.ApsCalibrationTrigger),
    FslCalSlope("fslCal_Slope", 1.0, 0.5, 1.5, defaultedBySM = true),           //dependency = BooleanKey.ApsCalibrationTrigger),
    FslSmoothAlpha("fsl_exp1_factor", 1.0, 0.1, 1.0, defaultedBySM = true),
    //FslSmoothCorrection("fsl_exp1_correction", 0.0, 0.0, 1.0, defaultedBySM = true),
    FslLastRaw("fsl_last_raw", -1.0, 40.0, 400.0, defaultedBySM = true),
    FslLastSmooth("fsl_last_smooth", -1.0, 40.0, 400.0, defaultedBySM = true),

    ActivityMonitorRatio("activity_ratio", 1.0, 0.0, 2.0, defaultedBySM = true),
    ActivityScaleFactor("activity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),
    InactivityScaleFactor("inactivity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),

    // Traffic Jam (Pool Compartment) per-patient absorption speed knob: a direct multiplier on every
    // stage's rate alike, the shared depot/transit rate (K1_B0) and the active stage's exit rate
    // (KE, PdModel in TsunamiIobEngineImpl), applied as-is - no derived "effective DIA" or other
    // indirect framing, since this model's decay is dose-dependent (crowding) and asymptotic, so no
    // single duration number would actually be dose-independent or exact. 2.0 doubles every rate and
    // 0.5 halves it, which compresses or stretches the whole curve in time by that factor, up to the
    // once-a-minute rate refresh: the time to the activity peak is divided by it and the peak height
    // multiplied by it (single doses of 0.1-10 U: within 0.6 min and 0.4% at 0.5-2, 1.5 min and
    // 1.5% at 0.2-5). Default 1.0 reproduces the unscaled fitted constants.
    InsulinTrafficJamSpeedMultiplier("insulin_traffic_jam_speed_multiplier", 1.0, 0.2, 5.0, hideParentScreenIfHidden = true),

    // Traffic Jam dose interaction (shown as "Dose Interaction"; CrowdingGroup in TsunamiIobEngineImpl):
    // the share of every dose that absorbs together with all insulin at the site. Every delivery is
    // one dose - each bolus/SMB and each minute's basal (temps included), extended-bolus and profile-
    // basal delivery - split into a shared branch, value of it, crowded by its whole group's depot (all
    // delivered insulin, or the profile baseline) as at 1.0, and an individual branch, 1 - value of it,
    // absorbing as an isolated dose. Every IOB is then value * (its value at 1.0) + (1 - value) * (the
    // doses' isolated curves, summed), and a lone dose follows the fitted curve at every value.
    // 0.0 turns it off: no dose interacts with any other. 1.0 (default): all insulin follows the same
    // path, one pooled depot, as fitted. Not fitted (the EPAR data has single doses with no basal only).
    InsulinTrafficJamMixing("insulin_traffic_jam_mixing", 1.0, 0.0, 1.0, hideParentScreenIfHidden = true),

}