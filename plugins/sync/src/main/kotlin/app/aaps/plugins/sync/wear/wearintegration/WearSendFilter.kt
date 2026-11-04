package app.aaps.plugins.sync.wear.wearintegration

/**
 * Decides what of each update [DataHandlerMobile.resendData] sends the watch, by the options in the Wear plugin's
 * "Data sent to the watch" settings. Every message wakes the watch and has it redraw its face, tiles or
 * complications, and every calculation run and every loop run sends a full update - each minute with a 1-minute CGM.
 */
internal class WearSendFilter {

    companion object {

        /**
         * Every 5 minutes, a part goes out for a reading at least this much newer than the one it last went out for: every
         * fifth with a 1-minute CGM (with room for readings arriving a few seconds early), every one with a 5-minute CGM.
         */
        const val PACE_MS = 270_000L

        /** With action buttons only when changed, each of their messages still goes out this often, in case one was lost. */
        const val ACTION_BUTTONS_REFRESH_MS = 60 * 60_000L
    }

    /** Per part of the update paced every 5 minutes: the reading it last went out for, and the user's activity then. */
    private class Pace(var reading: Long = 0L, var userActivity: Any? = null)

    private val paces = HashMap<String, Pace>()
    private val lastSent = HashMap<String, Any?>()
    private val lastSentAt = HashMap<String, Long>()
    private var chartCleared = false

    /**
     * Whether [part] of an update after a calculation run or a loop run (the BG and chart, or the loop status) goes out when it
     * is sent every 5 minutes, given the time of the newest reading and the user's own treatments and loop state
     * ([userActivity], compared with the part's last call). It does for a reading at least [PACE_MS] newer than the one the part
     * last went out for, and for anything else about that same reading - the loop run after it, the SMB or temp basal that run
     * sets - until the next reading arrives. In between, it does only if [userActivity] changed: a bolus, carbs, a temp target
     * or a loop mode change shows at once. Each part keeps its own pace; given the same calls, two parts decide alike.
     */
    @Synchronized
    fun pacedUpdate(part: String, readingTime: Long, userActivity: Any?): Boolean {
        val pace = paces.getOrPut(part) { Pace() }
        val activityChanged = userActivity != pace.userActivity
        pace.userActivity = userActivity
        return when {
            readingTime == pace.reading                                       -> true
            readingTime >= pace.reading + PACE_MS || readingTime < pace.reading -> { // a reading due, or the clock or data went back
                pace.reading = readingTime
                true
            }

            else                                                              -> activityChanged
        }
    }

    /**
     * Whether the action-button message [kind] (the treatment preferences, quick wizard, user actions or loop modes) goes out
     * with [content]: always, unless [onlyWhenChanged] - then when [content] differs from what was last sent, at least every
     * [ACTION_BUTTONS_REFRESH_MS], or when [force]d because the watch asked for its data.
     */
    @Synchronized
    fun actionButtons(kind: String, content: Any?, now: Long, onlyWhenChanged: Boolean, force: Boolean): Boolean {
        val send = force || !onlyWhenChanged || !lastSent.containsKey(kind) || lastSent[kind] != content ||
            now - (lastSentAt[kind] ?: 0L) >= ACTION_BUTTONS_REFRESH_MS
        if (send) {
            lastSent[kind] = content
            lastSentAt[kind] = now
        }
        return send
    }

    /** The chart data (the BG table, treatments and predictions) were sent. */
    @Synchronized
    fun chartSent() {
        chartCleared = false
    }

    /**
     * With the chart data off: whether to send an empty BG table and treatments, so that the watch drops what it holds instead
     * of keeping an old chart - once, and again whenever the watch asks for its data ([force]).
     */
    @Synchronized
    fun clearChart(force: Boolean): Boolean {
        val clear = force || !chartCleared
        chartCleared = true
        return clear
    }
}
