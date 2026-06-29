package app.aaps.core.interfaces.insulin

/**
 * Dedicated mathematical engine for physiological insulin pharmacodynamics.
 * Completely stateless. Handles Subcutaneous (SC) tissue saturation and Tsunami PK/PD shifts.
 */
object CompartmentModel {

    // --- Physiological & Tsunami Constants ---
    private const val A0 = 61.33
    private const val A1 = 12.27
    private const val B1 = 0.05185


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

}