package app.aaps.plugins.insulin

import kotlin.math.exp

/**
 * Dedicated mathematical engine for physiological insulin pharmacodynamics.
 * Completely stateless. Handles Subcutaneous (SC) tissue saturation and Tsunami PK/PD shifts.
 */
object PharmacodynamicModel {

    // --- Physiological & Tsunami Constants ---
    private const val a0 = 61.33
    private const val a1 = 12.27
    private const val b1 = 0.05185

    // Pre-calculated coefficients mapping actual serum peak (t_peak) to the SC time constant (tau).
    // Derived via 3rd-order polynomial regression assuming ke = ln(2)/44 (44 min half-life).
    private const val C3 =  0.000042
    private const val C2 = -0.009698
    private const val C1 =  0.689376
    private const val C0 =  24.974922

    /**
     * Step 1: Calculate the expected systemic peak time (t_p) based on the Tsunami model.
     * * @param currentActiveInsulin The current PK IOB circulating in the serum.
     * @param isU200 True if the active insulin is U200 (e.g., Lyumjev 200, ID 205).
     * @return The raw Tsunami peak time in minutes.
     */
    fun calculateSystemicPeak(currentActiveInsulin: Double, isU200: Boolean): Double {
        val effectiveInsulin = if (isU200) currentActiveInsulin * 2 else currentActiveInsulin
        return (a0 + a1 * effectiveInsulin) / (1 + b1 * effectiveInsulin)
    }

    /**
     * Step 2: Calculates the true Subcutaneous absorption time constant (tau).
     * * @param systemicTp The raw Tsunami peak time (from Step 1).
     * @return The physiological tau in minutes.
     */
    fun calculateTau(systemicTp: Double): Double {
        // Shift the Tsunami parameter to the true observable serum peak (PK)
        val pkPeak = 0.47 * systemicTp
        
        // O(1) Cubic Polynomial substitution replacing the transcendental root
        return C0 + (C1 * pkPeak) + (C2 * pkPeak * pkPeak) + (C3 * pkPeak * pkPeak * pkPeak)
    }

    /**
     * Step 3: Calculates the physical volume of insulin remaining in the Subcutaneous tissue.
     * Uses the physical model: SC(t) = D * (1 + t/tau) * e^(-t/tau)
     * * @param doseAmount The original volume of the injected dose (Units).
     * @param timeElapsedMins The time since the injection (in minutes).
     * @param tau The historical absorption sluggishness assigned to this specific dose.
     * @return The remaining SC mass (Units).
     */
    fun calculateSubcutaneousMass(doseAmount: Double, timeElapsedMins: Double, tau: Double): Double {
        if (timeElapsedMins <= 0) return doseAmount
        return doseAmount * (1.0 + (timeElapsedMins / tau)) * exp(-timeElapsedMins / tau)
    }
}