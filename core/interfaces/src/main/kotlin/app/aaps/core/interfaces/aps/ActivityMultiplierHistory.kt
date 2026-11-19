package app.aaps.core.interfaces.aps

/**
 * The multiplier AutoISF's activity monitor puts on the profile basal rate, over time. While it detects activity
 * (below 1) or inactivity (above 1), the loop's profile basal is the profile rate times it. Each loop run records the
 * multiplier it applied, and the insulin engine keeps using it afterwards as those minutes' profile baseline: the
 * basal the loop delivered is counted against the profile basal it knew then, whatever it chose to deliver.
 */
interface ActivityMultiplierHistory {

    /**
     * Records the loop run at [time]: [multiplier] is what the activity monitor had it apply to the profile basal rate,
     * 1 when it applied none.
     */
    fun record(time: Long, multiplier: Double)

    /** The multiplier the activity monitor had the loop apply to the profile basal rate at [time], 1.0 if none. */
    fun multiplierAt(time: Long): Double
}
