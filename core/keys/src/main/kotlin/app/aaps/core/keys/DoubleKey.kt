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

    // Traffic Jam (Pool Compartment) per-patient absorption speed knob: a direct multiplier on the depot's
    // rate (K1_B0, TrafficJamChain in plugins/insulin), the absorption from the infusion site, applied
    // as-is. The plasma and effect stages are the body's and stay as fitted, so 2.0 speeds up the
    // absorption only, not the whole curve. Default 1.0: a settled (day-3) pump site, as fitted to the
    // EMA report's pump study.
    InsulinTrafficJamSpeedMultiplier("insulin_traffic_jam_speed_multiplier", 1.0, 0.2, 5.0, hideParentScreenIfHidden = true),

    // Traffic Jam new-site speed: a new infusion site's absorption speed, as a share of a settled site's
    // (TrafficJamChain.siteFactor in plugins/insulin). The site starts at this share of the speed setting
    // at its cannula change and reaches the full setting linearly over InsulinTrafficJamSiteRamp. Default 0.47,
    // the EMA report's pump study's day 1 against its day 3; 1.0 turns it off.
    InsulinTrafficJamSiteStartSpeed("insulin_traffic_jam_site_start_speed", 0.47, 0.2, 1.0, hideParentScreenIfHidden = true),

    // Traffic Jam new-site ramp: how many hours a new infusion site takes to reach a settled site's speed, rising
    // linearly from InsulinTrafficJamSiteStartSpeed (TrafficJamChain.siteFactor in plugins/insulin). Default 48, the
    // EMA report's pump study's day 1 to day 3; 1 h to a week.
    InsulinTrafficJamSiteRamp("insulin_traffic_jam_site_ramp", 48.0, 1.0, 168.0, hideParentScreenIfHidden = true),

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

    // Traffic Jam CGM lag, minutes (TrafficJamChain in plugins/insulin): one more first-order stage after the
    // effect stage, at rate 1/lag, so the reported activity is the insulin's effect as the CGM sees it -
    // interstitial glucose and the sensor's own processing trail the blood. Delays and smooths every dose's
    // activity by about this much, and counts the effect still on its way to the CGM as insulin on board. Not
    // scaled by the speed setting. Default 8: the effect stage's 8 minutes were fitted with this 8-minute lag.
    // 0: no lag stage.
    InsulinTrafficJamCgmLag("insulin_traffic_jam_cgm_lag", 8.0, 0.0, 30.0, hideParentScreenIfHidden = true),

    // Traffic Jam micro-doses (BolusPulses in plugins/insulin): every bolus and SMB is modelled the way the pump gives it,
    // in pulses of this size, U, at this bolus speed, U/min, as the absorption fit replays the pump study's boluses.
    // Default 0.05 U at 1.5 U/min, one pulse every 2 s: 1.5 U/min is the standard bolus speed of the EPAR's pump study
    // (MiniMed 640G). AAPS's Medtrum driver expects 1 U/min.
    InsulinTrafficJamPulseSize("insulin_traffic_jam_pulse_size", 0.05, 0.01, 1.0, hideParentScreenIfHidden = true),
    InsulinTrafficJamBolusSpeed("insulin_traffic_jam_bolus_speed", 1.5, 0.1, 10.0, hideParentScreenIfHidden = true),

}