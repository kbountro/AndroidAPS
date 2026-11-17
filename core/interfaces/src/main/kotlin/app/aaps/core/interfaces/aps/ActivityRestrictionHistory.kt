package app.aaps.core.interfaces.aps

/**
 * The basal the AutoISF activity monitor held back. While it detects steps the loop treats a fraction of the
 * profile basal as neutral (the activity ratio, below 1) and sets temp basals around that, so the basal it
 * withholds would show up as missing, negative basal IOB, and still count as owed once the activity ends.
 * Each loop run records the ratio it applied, and the insulin engine lowers the profile baseline of those minutes
 * to it, so the withheld basal is dropped instead of counted as missing.
 */
interface ActivityRestrictionHistory {

    /**
     * Records the loop run at [time]: [ratio] is the fraction of the profile basal it treated as neutral because of the
     * activity monitor, below 1 while that restricts the basal, 1 when it does not.
     */
    fun record(time: Long, ratio: Double)

    /** The fraction of the profile basal the activity monitor had the loop treat as neutral at [time], 1.0 if none. */
    fun ratioAt(time: Long): Double
}
