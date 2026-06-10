package app.aaps.core.interfaces.insulin

import kotlin.math.exp

/**
 * Dedicated mathematical engine for physiological insulin pharmacodynamics.
 * Completely stateless. Handles Subcutaneous (SC) tissue saturation and Tsunami PK/PD shifts.
 */
object CompartmentModel {

    // --- Physiological & Tsunami Constants ---
    private const val A0 = 61.33
    private const val A1 = 12.27
    private const val B1 = 0.05185

    // Pre-calculated coefficients mapping actual serum peak (t_peak) to the SC time constant (tau).
    // Derived via 3rd-order polynomial regression assuming ke = ln(2)/44 (44 min half-life).
    private const val C3 =  0.000042
    private const val C2 = -0.009698
    private const val C1 =  0.689376
    private const val C0 =  24.974922

    /**
     * Step 1: Calculate the expected systemic peak time (t_p) based on the Tsunami model.
     * The Tsunami delay is caused by the "Depot Effect" — larger physical volumes of fluid 
     * pooling in the subcutaneous fat take longer to dissociate and absorb.
     * * @param currentScMass The total physical volume of unabsorbed insulin sitting in the SC tissue.
     * @param isU200 True if the active insulin is U200 (e.g., Lyumjev 200, ID 205).
     * @return The mechanistic PK peak time in minutes.
     */
    fun calculateSystemicPeak(currentScMass: Double, isU200: Boolean): Double {
        val effectiveMass = if (isU200) currentScMass * 2.0 else currentScMass
        
        // 0.41 strictly scales the empirical Tsunami PD peak down to the mechanistic PK peak
        return 0.41 * (A0 + A1 * effectiveMass) / (1.0 + B1 * effectiveMass)
    }

    /**
     * Numerically solves the bi-exponential compartment equation for SC Absorption Tau.
     */
    fun computeScTau(targetTp: Double): Double {
        val tauE = 63.48 // Hepatic Clearance (44-min half-life)
        
        // Safety bounds for SC absorption in minutes
        var low = 1.0
        var high = 300.0
        var mid = 150.0

        // 15 iterations guarantees accuracy to ~0.01 minutes
        for (i in 0 until 15) {
            mid = (low + high) / 2.0
            
            // L'Hopital's limit prevents division by zero if tauA exactly matches tauE
            val currentTp = if (kotlin.math.abs(mid - tauE) < 0.1) {
                mid 
            } else {
                (mid * tauE / (tauE - mid)) * kotlin.math.ln(tauE / mid)
            }

            // Binary path logic
            if (currentTp > targetTp) {
                high = mid
            } else {
                low = mid
            }
        }
        return mid
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