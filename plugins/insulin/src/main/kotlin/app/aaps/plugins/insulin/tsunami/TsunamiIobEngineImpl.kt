package app.aaps.plugins.insulin.tsunami

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.ActivityMultiplierHistory
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.insulin.TsunamiIobEngine
import app.aaps.core.interfaces.insulin.TsunamiIobResult
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plus
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * "Traffic Jam" IOB engine backing [Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM].
 *
 * Models a shared subcutaneous depot as a small, continuously-evolving pool of linear
 * (first-order) compartments - not one independent closed-form curve per dose. Every dose
 * is an impulse added directly into its category's own depot state; crowding is expressed
 * as the depot's rate depending on how much mass is currently still in it, evaluated live
 * rather than snapshotted per dose. Because every state is a genuine ODE
 * state (not an age-parametrized curve), a rate constant can change value at any instant
 * with no reinterpretation needed - the state simply keeps evolving from wherever it
 * currently is. This is what the earlier per-dose-curve design could not do: that design
 * evaluated `IOB(t) = exp(-t^p/tp)` from each curve's own elapsed age, so an amount-driven
 * change to `tp` had no continuous way to apply mid-flight, and had to be patched with a
 * "time warp" (retroactively inventing a new elapsed age to keep IOB continuous) that
 * unavoidably forced a discontinuous drop in reported activity every time the pool grew.
 * Here the chain is depot -> plasma -> effect ([TrafficJamChain]): activity is K_EFFECT * A with a constant
 * K_EFFECT, only the depot's rate changes, and a dose adds only to the depot, so activity never jumps. An optional
 * CGM lag stage after A delays and smooths the reported activity to when the CGM can see it.
 *
 * See [TrafficJamChain] for the fit and [PdModel] for the isolated curves of the dose interaction.
 */
@Singleton
class TsunamiIobEngineImpl @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val activityMultiplierHistory: ActivityMultiplierHistory,
    rxBus: RxBus
) : TsunamiIobEngine {

    companion object {
        private const val DIA_HORIZON_MINUTES = 480.0 // 8h
        private const val DIA_HORIZON_MS = 8L * 60 * 60 * 1000L

        // How many requests must share an offset past the whole minute before the individual curves are
        // tabulated shifted by it (600 steps per curve, against one per dose and request).
        private const val SHIFTED_CURVE_MIN_USES = 4

        // How far around now the treatment records are loaded at once ([Records]): autosens and the IOB graph
        // reach 24 h plus the profile's DIA back, each request's window a DIA horizon more, and the IOB arrays
        // look ahead about a DIA horizon. A request reaching past that widens the range.
        private const val RECORDS_BACK_MS = 48L * 60 * 60 * 1000L
        private const val RECORDS_AHEAD_MS = 12L * 60 * 60 * 1000L

        // How far on either side of a request ending before that range (the history browser) its records reach.
        private const val PAST_RECORDS_MARGIN_MS = 48L * 60 * 60 * 1000L
    }

    private val disposable = CompositeDisposable()

    init {
        // Any change to the history the simulations read - boluses, temp basals, extended boluses and profile
        // switches (EventNewHistoryData), site changes (EventTherapyEventChange) - drops the cached results and
        // records the moment it is committed: the bus calls subscribers on the sender's thread. The calculation
        // run's own resetCache comes 5 s later at the earliest, and a request in between must not miss a dose.
        disposable += rxBus.toObservable(EventNewHistoryData::class.java).subscribe({ resetCache() }, { resetCache() })
        disposable += rxBus.toObservable(EventTherapyEventChange::class.java).subscribe({ resetCache() }, { resetCache() })
    }

    private data class ResultCacheKey(val time: Long, val sensitivityRatio: Double, val assumeZeroTempAfter: Long)

    // How often each of the 8 last offsets past the whole minute has been requested: the SMB IOB array asks
    // 95 times in a row for times sharing one (now, now + 5 min, ... and the same with a zero temp), which pays
    // for a table of every curve shifted by it ([PdModel.runState]); a one-off request does not.
    private val offsetUses = object : LinkedHashMap<Long, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean = size > 8
    }

    private fun offsetRequestedOften(offset: Long): Boolean = synchronized(offsetUses) {
        val uses = (offsetUses[offset] ?: 0) + 1
        offsetUses[offset] = uses
        uses >= SHIFTED_CURVE_MIN_USES
    }

    private val engineLock = Any()
    private var resultCache = HashMap<ResultCacheKey, TsunamiIobResult>()
    private var cacheVersion = 0

    // The records of this cache generation ([recordsFor]): around now, and around the last request far before it.
    private var recentRecords: Records? = null
    private var pastRecords: Records? = null

    override val isActive: Boolean
        get() = activePlugin.activeInsulin.id.value == Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM.value

    override fun resetCache() {
        synchronized(engineLock) {
            resultCache.clear()
            recentRecords = null
            pastRecords = null
            cacheVersion++
        }
    }

    /**
     * The treatment records the simulations read, and the profile basal of every minute, loaded once per cache
     * generation ([resetCache]: every calculation run and every change to that history) for a range ([recordsFor]),
     * and filtered per request exactly as the database queries filter them, so every request sees the same
     * records, in the same order, as if it had queried them itself. Every request used to: four queries, each
     * mapping every bolus since its window's start, and a profile lookup for every minute of the window. With a
     * 1-minute CGM that was thousands of queries a minute - each calculation run recomputes the IOB at every
     * autosens and IOB-graph time, some 860 requests, and every SMB IOB array is 95 more.
     */
    private inner class Records(val from: Long, val to: Long) {

        // Boluses come by id, newest first, with timestamp >= from; sorted by timestamp here (stable, so equal
        // timestamps keep that order) to be cut by binary search. The rest come by timestamp: temp basals and
        // extended boluses starting within [from, to], cannula changes from the longest site ramp (a week) before
        // `from` on, so that the age of the site in use at any request's start is known ([lastCannulaChangeBefore]).
        private val boluses = persistenceLayer.getBolusesFromTime(from, true).blockingGet().sortedBy { it.timestamp }
        private val tempBasals = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(from, to, true)
        private val extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(from, to, true)
        private val cannulaChanges = persistenceLayer.getTherapyEventDataFromTime(from - TrafficJamChain.MAX_SITE_RAMP_MS, TE.Type.CANNULA_CHANGE, true)

        /** Every whole minute's profile and basal rate, looked up the first time a request needs it. */
        private val minuteBasals = arrayOfNulls<Any>(((to - from) / 60_000L).toInt() + 1)

        /** getBolusesFromTime(time): every bolus at or after [time]. */
        fun bolusesFrom(time: Long): List<BS> = boluses.subList(firstAtOrAfter(boluses, time) { it.timestamp }, boluses.size)

        /** getTemporaryBasalsStartingFromTimeToTime(from, to). */
        fun tempBasalsStarting(from: Long, to: Long): List<TB> =
            tempBasals.subList(firstAtOrAfter(tempBasals, from) { it.timestamp }, firstAtOrAfter(tempBasals, to + 1) { it.timestamp })

        /** getExtendedBolusesStartingFromTimeToTime(from, to). */
        fun extendedBolusesStarting(from: Long, to: Long): List<EB> =
            extendedBoluses.subList(firstAtOrAfter(extendedBoluses, from) { it.timestamp }, firstAtOrAfter(extendedBoluses, to + 1) { it.timestamp })

        /** getTherapyEventDataFromTime(time, CANNULA_CHANGE). */
        fun cannulaChangesFrom(time: Long): List<TE> = cannulaChanges.subList(firstAtOrAfter(cannulaChanges, time) { it.timestamp }, cannulaChanges.size)

        /**
         * The last cannula change before [time], if one is within the site ramp [rampMs] of it (and [time] is at least
         * `from`); null otherwise: the site in use then has reached full speed ([TrafficJamChain.siteFactor]).
         */
        fun lastCannulaChangeBefore(time: Long, rampMs: Long): Long? {
            val i = firstAtOrAfter(cannulaChanges, time) { it.timestamp } - 1
            return if (i >= 0 && time - cannulaChanges[i].timestamp < rampMs) cannulaChanges[i].timestamp else null
        }

        /** The profile at the whole minute [minute] and its basal rate then, or null without a profile. */
        fun minuteBasal(minute: Long): MinuteBasal? {
            val i = ((minute - from) / 60_000L).toInt()
            if (minute < from || i >= minuteBasals.size) return lookUpBasal(minute)
            when (val known = minuteBasals[i]) {
                is MinuteBasal -> return known
                NoProfile      -> return null
            }
            // Two requests may look the same minute up at once; both store the same answer.
            return lookUpBasal(minute).also { minuteBasals[i] = it ?: NoProfile }
        }

        private fun lookUpBasal(minute: Long): MinuteBasal? = profileFunction.getProfile(minute)?.let { MinuteBasal(it, it.getBasal(minute)) }

        /** The first index whose key is at least [key], in a list sorted by it. */
        private fun <T> firstAtOrAfter(list: List<T>, key: Long, keyOf: (T) -> Long): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keyOf(list[mid]) < key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    private class MinuteBasal(val profile: Profile, val rate: Double)
    private object NoProfile

    /**
     * Records covering [from]..[to]. Requests reaching into the range around now (the loop, autosens, the IOB
     * graph) share one load of that range, widened to the request if it reaches past it; a request ending before
     * it (the history browser) loads its own range around itself, kept beside the recent one, so that browsing
     * an old day neither loads everything since then nor drops the records the loop is using.
     */
    private fun recordsFor(from: Long, to: Long): Records {
        val version: Int
        synchronized(engineLock) {
            recentRecords?.let { if (it.from <= from && it.to >= to) return it }
            pastRecords?.let { if (it.from <= from && it.to >= to) return it }
            version = cacheVersion
        }
        val now = dateUtil.now()
        val recent = to >= now - RECORDS_BACK_MS
        val loaded =
            if (recent) Records(Math.floorDiv(min(from, now - RECORDS_BACK_MS), 60_000L) * 60_000L, max(to, now + RECORDS_AHEAD_MS))
            else Records(Math.floorDiv(from - PAST_RECORDS_MARGIN_MS, 60_000L) * 60_000L, min(to + PAST_RECORDS_MARGIN_MS, now - RECORDS_BACK_MS))
        synchronized(engineLock) {
            if (version == cacheVersion) if (recent) recentRecords = loaded else pastRecords = loaded
        }
        return loaded
    }

    override fun calculateIobFromBolusToTime(toTime: Long): IobTotal {
        val res = resultAt(toTime, 1.0)
        val pumpInterface = activePlugin.activePump
        return if (!pumpInterface.isFakingTempsByExtendedBoluses) {
            // Summed like stock AAPS (IobTotal.combine would take only the extended bolus's basaliob, 0).
            // copy() first: the result objects are shared with the cache.
            res.bolusTotal.copy().plus(res.extBolusTotal)
        } else {
            res.bolusTotal
        }
    }

    override fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal =
        resultAt(toTime, 1.0).profileBaselineTotal

    override fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal =
        resultAt(toTime, 1.0).basalNetTotal

    override fun calculateNetBasalAuto(toTime: Long, sensitivityRatio: Double): IobTotal =
        resultAt(toTime, sensitivityRatio).basalNetAutoTotal

    override fun resultAt(toTime: Long, sensitivityRatio: Double, assumeZeroTempAfter: Long): TsunamiIobResult {
        val key = ResultCacheKey(toTime, sensitivityRatio, assumeZeroTempAfter)

        synchronized(engineLock) {
            resultCache[key]?.let { return it.copy() }
        }

        // Every requested time gets its own window - a full DIA horizon of warm-up before it, older doses
        // dropped - simulated only up to that time, since nothing later changes its result. Simulating on
        // to now + 8 h, to cache a 5-minute grid for later requests, served almost none: the callers ask for
        // times off that grid (the SMB IOB array: now, now + 5 min, ..., 95 times per loop run with the zero
        // temp; autosens and the IOB graph: the bucketed times), so each paid for a whole 16-hour simulation,
        // evaluated at some 190 times, to use one. The few requests the grid did serve got its longer window
        // instead of their own, so their values depended on which request had come first.
        val startTime = toTime - DIA_HORIZON_MS
        val currentVersion = synchronized(engineLock) { cacheVersion }
        val result = simulateDepotHistory(toTime, startTime, sensitivityRatio, assumeZeroTempAfter)
        synchronized(engineLock) {
            if (currentVersion == cacheVersion) resultCache[key] = result
        }
        return result.copy()
    }

    // =========================================================================
    // THE TRAFFIC JAM DEPOT SIMULATION
    // Five attribution categories (bolus, extended bolus, absolute temp-basal delivery,
    // profile basal, autosens-adjusted profile basal) each carry their own persistent
    // pool (depot -> plasma -> effect, see [TrafficJamChain]) so IOB/activity can
    // still be broken down the same way AAPS expects. These pools hold the shared branch of every
    // dose: bolus, extBolus and basalAbs crowd each other, and profileBasal crowds only itself, with
    // autoProfileBasal moving at its rate. Below a dose interaction of 1 the rest of every dose
    // absorbs on its own, from its isolated curve - see [CrowdingGroup].
    // Every dose is an impulse added straight into its category's depot mass; nothing
    // about any other category's or any earlier dose's state is ever rewritten - only each
    // group's absorption rate constant is updated, once a minute from its live depot mass,
    // and every pool simply keeps integrating forward from its current state under that
    // rate, in closed form.
    //
    // SITE CHANGES: a cannula/site change (TE.Type.CANNULA_CHANGE - also logged by patch
    // pumps on pod activation) starts a brand new set of these five pools ([SiteState]),
    // keyed by a monotonically increasing generation number. Insulin delivered before the
    // change keeps crowding only with other insulin from that same old site as it finishes
    // absorbing; insulin delivered at or after the change crowds only the new site's own
    // pools. The two never interact - no mass or rate crosses between generations - and
    // every generation keeps decaying independently for as long as it still holds any
    // mass, however many site changes occur in a window. Reported IOB/activity per
    // category is simply the sum across every generation still alive.
    // =========================================================================
    private fun simulateDepotHistory(
        requestedTime: Long,
        startTime: Long,
        sensitivityRatio: Double,
        assumeZeroTempAfter: Long = Long.MAX_VALUE
    ): TsunamiIobResult {
        val now = dateUtil.now()
        // Doses up to the first whole minute at or after requestedTime: the basal of the minute it falls in
        // then arrives as one event at that minute's end, after requestedTime, as when simulating past it.
        val horizonTime = (requestedTime + 59_999L) / 60_000L * 60_000L

        val records = recordsFor(startTime, horizonTime)
        val boluses = records.bolusesFrom(startTime)
        val pumpInterface = activePlugin.activePump
        val isFakingTemps = pumpInterface.isFakingTempsByExtendedBoluses

        // Every bolus and SMB as the pump gives it ([BolusPulses]), as the absorption fit replays the pump study's
        // boluses: in pulses of the pulse size at the bolus speed, a minute of pulses at a time. Basal, temp basals and
        // extended boluses stay one dose a minute.
        val pulses = BolusPulses(
            preferences.get(DoubleKey.InsulinTrafficJamPulseSize).coerceIn(0.01, 1.0),
            preferences.get(DoubleKey.InsulinTrafficJamBolusSpeed).coerceIn(0.1, 10.0)
        )

        val timeline = buildDoseEventTimeline(startTime, horizonTime, sensitivityRatio, boluses, records, isFakingTemps, assumeZeroTempAfter, now, pulses)

        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        // Per-patient absorption speed knob (DoubleKey.InsulinTrafficJamSpeedMultiplier): scales the depot's rate
        // only, the site's absorption ([TrafficJamChain]; plasma and effect are the body's). A new site starts at
        // DoubleKey.InsulinTrafficJamSiteStartSpeed times it, reaching it after the site ramp
        // (DoubleKey.InsulinTrafficJamSiteRamp, [TrafficJamChain.siteFactor]). Dose interaction
        // (DoubleKey.InsulinTrafficJamMixing, see [CrowdingGroup]). All read once per simulated window, like the
        // fitted constants are effectively constant - a mid-window change to any of them is not retroactively applied
        // to the past, same as those.
        val speedFactor = preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)
        val siteStartSpeed = preferences.get(DoubleKey.InsulinTrafficJamSiteStartSpeed).coerceIn(0.2, 1.0)
        val siteRampMs = TrafficJamChain.siteRampMs(preferences.get(DoubleKey.InsulinTrafficJamSiteRamp))
        val mixing = preferences.get(DoubleKey.InsulinTrafficJamMixing).coerceIn(0.0, 1.0)
        // CGM lag (DoubleKey.InsulinTrafficJamCgmLag): the rate of the stage after the effect stage, or 0 for no
        // such stage. Not scaled by the speed setting: it is the sensor's delay, not the insulin's.
        val lagMinutes = preferences.get(DoubleKey.InsulinTrafficJamCgmLag).coerceIn(0.0, 30.0)
        val kc = if (lagMinutes > 0.0) 1.0 / lagMinutes else 0.0

        // Site-change boundaries within the simulated window: each one starts a fresh
        // [SiteState] generation. A dose's generation is how many boundaries fall at or
        // before its own timestamp, so doses before the first boundary (or when there is
        // none at all) all land in generation 0 - identical to the single-pool behavior
        // this replaces. Boundaries outside the window never matter: anything that old is
        // already past the DIA horizon, and anything that new hasn't happened yet.
        val siteChangeBoundaries = records.cannulaChangesFrom(startTime)
            .map { it.timestamp }
            .filter { it <= horizonTime }
        // When the site in use at the window's start was put in, if less than a site ramp before: generation 0's start.
        val firstSiteStart = records.lastCannulaChangeBefore(startTime, siteRampMs)

        /** When site generation [gen] was put in, or null if it had reached full speed by the window's start. */
        fun siteStart(gen: Int): Long? = if (gen >= 1) siteChangeBoundaries[gen - 1] else firstSiteStart

        /** The absorption speed at [time] of a site put in at [start]: the speed setting times the site's age factor. */
        fun absorptionSpeed(start: Long?, time: Long): Double =
            if (start == null) speedFactor else speedFactor * TrafficJamChain.siteFactor(siteStartSpeed, siteRampMs, time - start)

        // One persistent set of pools (and crowding rates) per site generation, never shared
        // across generations: the shared branch of every dose, one pool per category (see [CrowdingGroup]).
        class SiteState(val start: Long?) {

            // All of a group's shared branches share one rate, so one pool per category holds them exactly.
            val pools = Array(Category.entries.size) { Pool() }

            /** When insulin was last added to this site, and when its rates were last re-evaluated ([refreshRates]). */
            var givenAt = Long.MIN_VALUE
            var refreshed = false
            var refreshedAt = 0L

            fun propagateInPlace(dtMinutes: Double) {
                for (p in pools) p.propagateInPlace(kc, dtMinutes)
            }

            /** Every pool's depot rate from its group's live depot mass (see [CrowdingGroup]) and the site's age at [time]. */
            fun refreshRates(time: Long) {
                refreshed = true
                refreshedAt = time
                val speed = absorptionSpeed(start, time)
                val deliveredDepot = groupDepotMass(pools, CrowdingGroup.DELIVERED)
                val profileDepot = groupDepotMass(pools, CrowdingGroup.PROFILE)
                for (cat in Category.entries) {
                    if (!cat.drivesGroupRate) continue
                    val groupDepot = if (cat.group == CrowdingGroup.DELIVERED) deliveredDepot else profileDepot
                    pools[cat.ordinal].k = TrafficJamChain.rate(speed, groupDepot)
                }
                // autoProfileBasal moves at the profile basal's rate, never at its own.
                pools[Category.AUTO_PROFILE_BASAL.ordinal].k = pools[Category.PROFILE_BASAL.ordinal].k
            }
        }

        val sites = HashMap<Int, SiteState>()
        fun siteFor(gen: Int): SiteState = sites.getOrPut(gen) { SiteState(siteStart(gen)) }
        var lastTime = startTime

        // The individual branch of every dose given so far, at a dose interaction below 1: its isolated
        // curve ([PdModel.IsolatedCurve], one per amount), grouped into runs of equal doses a minute apart
        // in each category ([IndividualRun]). A basal rate is one dose every minute, so a stretch at one
        // rate is a single run, summed in a few steps ([PdModel.runState]) instead of dose by dose. A
        // profile-basal dose also carries its autoProfileBasal twin, which moves at the profile basal's
        // rate and so is the same curve scaled by [IndividualRun.autoScale]. A minute of a bolus's pulses is
        // [scale] lone doses of the pulse size, and a long bolus's full minutes, a minute apart, one run. An
        // isolated curve absorbs at the site's speed when its dose is given, its age factor rounded to 0.01
        // ([PdModel.siteSpeed]) so that a new site's ramp needs some 50 curves per amount, not one per minute.
        val individualRuns = ArrayList<IndividualRun>()
        val openRuns = arrayOfNulls<IndividualRun>(Category.entries.size)
        val curvesByAmount = HashMap<Pair<Double, Double>, PdModel.IsolatedCurve>()
        fun addIndividual(timestamp: Long, siteStart: Long?, cat: Category, amount: Double, autoScale: Double = 0.0, scale: Double = 1.0) {
            val speed = PdModel.siteSpeed(speedFactor, siteStartSpeed, siteRampMs, siteStart, timestamp)
            val curve = curvesByAmount.getOrPut(Pair(amount, speed)) { PdModel.isolatedCurve(amount, speed, kc) }
            val open = openRuns[cat.ordinal]
            if (open != null && open.curve === curve && open.autoScale == autoScale && open.scale == scale && open.last + 60000L == timestamp) open.count++
            else IndividualRun(cat, curve, autoScale, timestamp, scale).also { individualRuns.add(it); openRuns[cat.ordinal] = it }
        }
        val runState = DoubleArray(4)
        // Runs of whole-minute doses are all requestedTime's offset past the whole minute old; if requests
        // keep sharing that offset, the curves are tabulated shifted by it ([PdModel.runState]).
        val offset = requestedTime % 60_000L
        val shiftWholeMinuteRuns = mixing < 1.0 && offset != 0L && offsetRequestedOften(offset)

        // Sorted so the snooze/lastBolusTime bookkeeping below can sweep forward with monotonically
        // advancing indices. Priming boluses count for lastBolusTime only, as in the stock calculator: their
        // insulin fills the cannula or tubing and is never on board (no snooze, no pulses still to come).
        val sortedBoluses = boluses.filter { it.isValid }.sortedBy { it.timestamp }
        var bolusValidIdx = 0 // boluses folded into lastBolusTimeSeen so far (timestamp <= current tTarget)
        var bolusFrontIdx = 0 // boluses before this index have permanently expired snooze windows
        var lastBolusTimeSeen = 0L

        // Evaluates every category's IOB/activity at [tTarget] by peeking [dtPeek] minutes ahead of the
        // pools' last-committed state, without mutating it.
        fun evaluatePools(dtPeek: Double, tTarget: Long): TsunamiIobResult {
            while (bolusValidIdx < sortedBoluses.size && sortedBoluses[bolusValidIdx].timestamp <= tTarget) {
                val b = sortedBoluses[bolusValidIdx]
                if (b.amount > 0 && b.timestamp > lastBolusTimeSeen) lastBolusTimeSeen = b.timestamp
                bolusValidIdx++
            }
            // tSnoozeElapsed grows monotonically with tTarget for a fixed bolus (divisor > 0), so once
            // a bolus's window has expired it can never reopen - safe to drop it from the front for good.
            while (bolusFrontIdx < bolusValidIdx) {
                val b = sortedBoluses[bolusFrontIdx]
                val tSnoozeElapsed = ((tTarget - b.timestamp) * divisor) / 60000.0
                if (tSnoozeElapsed >= DIA_HORIZON_MINUTES) bolusFrontIdx++ else break
            }

            var bSnooze = 0.0
            for (idx in bolusFrontIdx until bolusValidIdx) {
                val b = sortedBoluses[idx]
                if (b.type != BS.Type.SMB && b.type != BS.Type.PRIMING) {
                    // Age used by this bolus's own isolated curve is divisor times the real elapsed
                    // time (matching the stock calculator's "evaluate the curve further in the future"
                    // technique - see IobCobCalculatorPlugin.calculateIobFromBolusToTime), NOT elapsed
                    // time since some derived timestamp - using the latter silently zeroed this out
                    // for every divisor value in the enforced [1, 10] range.
                    val tSnoozeElapsed = ((tTarget - b.timestamp) * divisor) / 60000.0
                    if (tSnoozeElapsed in 0.0..<DIA_HORIZON_MINUTES) {
                        // Snooze reflects this bolus's own isolated pool, not shared crowding, at its site's speed.
                        val bolusSiteStart = siteStart(siteGenerationFor(b.timestamp, siteChangeBoundaries))
                        val speed = PdModel.siteSpeed(speedFactor, siteStartSpeed, siteRampMs, bolusSiteStart, b.timestamp)
                        bSnooze += b.amount * PdModel.isolatedIobFraction(tSnoozeElapsed, b.amount, speed, kc)
                    }
                }
            }

            // Shared branches: sums every still-live site generation's own peeked pools - crowding
            // never crosses generations, but the reported total is their combined IOB/activity.
            val iob = DoubleArray(Category.entries.size)
            val act = DoubleArray(Category.entries.size)
            for (cat in Category.entries) {
                val sum = Pool()
                for (site in sites.values) sum.add(site.pools[cat.ordinal].propagated(kc, dtPeek))
                iob[cat.ordinal] = sum.iob()
                act[cat.ordinal] = sum.activity(kc)
            }

            // m times the shared branches, which the pools hold as they would at m = 1, plus 1 - m times
            // the individual branches, i.e. the doses' isolated curves (see [CrowdingGroup]).
            if (mixing < 1.0) {
                val indIob = DoubleArray(Category.entries.size)
                val indAct = DoubleArray(Category.entries.size)
                for (run in individualRuns) {
                    PdModel.runState(run.curve, tTarget - run.first, run.count, shiftWholeMinuteRuns && run.first % 60_000L == 0L, runState)
                    val runIob = run.scale * (runState[0] + runState[1] + runState[2] + runState[3])
                    val runAct = run.scale * (if (kc > 0.0) kc * runState[3] else TrafficJamChain.K_EFFECT * runState[2])
                    indIob[run.cat.ordinal] += runIob
                    indAct[run.cat.ordinal] += runAct
                    if (run.autoScale > 0.0) {
                        indIob[Category.AUTO_PROFILE_BASAL.ordinal] += run.autoScale * runIob
                        indAct[Category.AUTO_PROFILE_BASAL.ordinal] += run.autoScale * runAct
                    }
                }
                for (i in iob.indices) {
                    iob[i] = mixing * iob[i] + (1.0 - mixing) * indIob[i]
                    act[i] = mixing * act[i] + (1.0 - mixing) * indAct[i]
                }
            }

            // The pulses of a bolus that are still to come at tTarget (in the chunks after it) are on board but not yet
            // active: the bolus counts whole from its timestamp, as in the stock calculators. Some drivers record a bolus
            // as it starts (Medtrum's, with its full amount), so a loop run during a long bolus would otherwise miss the
            // part not yet given.
            var notGivenYet = 0.0
            for (idx in 0 until bolusValidIdx) {
                val b = sortedBoluses[idx]
                if (b.amount > 0.0 && b.type != BS.Type.PRIMING) notGivenYet += pulses.notGivenYet(b.amount, tTarget - b.timestamp)
            }
            iob[Category.BOLUS.ordinal] += notGivenYet

            val bolus = Category.BOLUS.ordinal
            val extBolus = Category.EXT_BOLUS.ordinal
            val basalAbs = Category.BASAL_ABS.ordinal
            val profile = Category.PROFILE_BASAL.ordinal
            val autoProfile = Category.AUTO_PROFILE_BASAL.ordinal

            val bnIob = iob[basalAbs] - iob[profile]
            val bnAct = act[basalAbs] - act[profile]
            val bnaIob = iob[basalAbs] - iob[autoProfile]
            val bnaAct = act[basalAbs] - act[autoProfile]

            return TsunamiIobResult(
                bolusTotal = mapToTotal(iob[bolus], act[bolus], "bolus", lastBolusTimeSeen, bSnooze),
                extBolusTotal = mapToTotal(iob[extBolus], act[extBolus], "extBolus"),
                profileBaselineTotal = mapToTotal(iob[profile], act[profile], "profile"),
                basalNetTotal = mapToTotal(bnIob, bnAct, "netBasal"),
                basalNetAutoTotal = mapToTotal(bnaIob, bnaAct, "netBasal")
            )
        }

        for (dose in timeline) {
            // The snapshot comes AFTER every dose at or before requestedTime is absorbed, but before the next.
            if (dose.timestamp > requestedTime) break

            val dtMins = (dose.timestamp - lastTime) / 60000.0
            if (dtMins > 0) {
                // Every live generation decays continuously in real time regardless of
                // whether THIS dose belongs to it - an old site's leftover insulin keeps
                // absorbing on its own schedule even after the new site starts filling up.
                for (site in sites.values) site.propagateInPlace(dtMins)
            }
            lastTime = dose.timestamp

            val site = siteFor(siteGenerationFor(dose.timestamp, siteChangeBoundaries))
            val doseSiteStart = site.start

            // Shared branches, held in the pools as they would be at m = 1 (see [CrowdingGroup]). A bolus's pulses all go
            // to the site in use when the bolus started, those given after a site change logged during it too: one bolus,
            // one site. Basal and extended boluses go to the site in use at each minute.
            dose.bolusParts?.forEach { part ->
                val bolusSite = siteFor(siteGenerationFor(part.bolusTime, siteChangeBoundaries))
                bolusSite.pools[Category.BOLUS.ordinal].D += part.chunk.amount
                bolusSite.givenAt = lastTime
            }
            if (dose.extBolusAmt > 0.0 || dose.basalAbsAmt > 0.0 || dose.profileBasalAmt > 0.0) site.givenAt = lastTime
            if (dose.extBolusAmt > 0.0) site.pools[Category.EXT_BOLUS.ordinal].D += dose.extBolusAmt
            if (dose.basalAbsAmt > 0.0) site.pools[Category.BASAL_ABS.ordinal].D += dose.basalAbsAmt
            if (dose.profileBasalAmt > 0.0) {
                site.pools[Category.PROFILE_BASAL.ordinal].D += dose.profileBasalAmt
                if (dose.autoProfileBasalAmt > 0.0) site.pools[Category.AUTO_PROFILE_BASAL.ordinal].D += dose.autoProfileBasalAmt
            }

            // Individual branches, below m = 1. A bolus chunk's are its lone pulses: that many doses of the pulse size,
            // and the smaller last pulse.
            if (mixing < 1.0) {
                dose.bolusParts?.forEach { part ->
                    val bolusSiteStart = siteFor(siteGenerationFor(part.bolusTime, siteChangeBoundaries)).start
                    val chunk = part.chunk
                    if (chunk.pulses > 0) addIndividual(dose.timestamp, bolusSiteStart, Category.BOLUS, pulses.pulseSize, scale = chunk.pulses.toDouble())
                    if (chunk.extra > 0.0) addIndividual(dose.timestamp, bolusSiteStart, Category.BOLUS, chunk.extra)
                }
                if (dose.extBolusAmt > 0.0) addIndividual(dose.timestamp, doseSiteStart, Category.EXT_BOLUS, dose.extBolusAmt)
                if (dose.basalAbsAmt > 0.0) addIndividual(dose.timestamp, doseSiteStart, Category.BASAL_ABS, dose.basalAbsAmt)
                // autoProfileBasal is the profile basal scaled by sensitivityRatio, so it comes with it.
                if (dose.profileBasalAmt > 0.0) {
                    addIndividual(dose.timestamp, doseSiteStart, Category.PROFILE_BASAL, dose.profileBasalAmt, dose.autoProfileBasalAmt / dose.profileBasalAmt)
                }
            }

            // Every rate follows its group's live depot mass, so it is re-evaluated at every whole minute, whether or
            // not this event injected anything into that group: buildDoseEventTimeline emits one event per minute
            // wherever a profile exists (zero temps and the zero-temp projection included), so no rate is then held for
            // more than a minute while its depot empties. Every live generation too, since an old site's depot keeps
            // emptying after dosing has moved to the new one. Between whole minutes, at a bolus's pulses, only the sites
            // given insulin are re-evaluated, so that one site's doses never change how another site's insulin is
            // stepped: the sites stay independent. Where no profile exists only boluses create events, and a site given
            // nothing is then re-evaluated at the first event a minute or more after its last. A new site's age factor
            // is held with the rate between events too; it changes little in a minute (a site ramp is an hour at the
            // shortest).
            val wholeMinute = lastTime % 60_000L == 0L
            for (s in sites.values) {
                if (wholeMinute || s.givenAt == lastTime || !s.refreshed || lastTime - s.refreshedAt >= 60_000L) s.refreshRates(lastTime)
            }
        }

        return evaluatePools((requestedTime - lastTime) / 60000.0, requestedTime)
    }

    /**
     * How many of [boundaries] (sorted ascending site-change timestamps) fall at or before
     * [timestamp] - the generation number that timestamp's dose belongs to. A change logged
     * exactly at a dose's own timestamp already counts: that dose is the first one into the
     * new site. Binary search since [boundaries] is walked once per dose event.
     */
    private fun siteGenerationFor(timestamp: Long, boundaries: List<Long>): Int {
        var lo = 0
        var hi = boundaries.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (boundaries[mid] <= timestamp) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Mass still sitting in one site's category pools' depots for one crowding [group] (see
     * [CrowdingGroup] for which pools crowd which). Deliberately D only, never D+X: X is insulin that has already left
     * the depot (in the blood), so it no longer competes for SC depot space and must not inflate the rate that
     * governs the depot's own outflow. Getting this wrong compounds under sustained dosing - X never fully
     * clears between frequent small doses (real SMB/TBR cadence), so including it keeps dragging
     * k1 down further the longer a session runs. [pools] is always one site generation's own
     * pools, never mixed across sites. Floored inside [TrafficJamChain.rate].
     */
    private fun groupDepotMass(pools: Array<Pool>, group: CrowdingGroup): Double {
        var mass = 0.0
        for (cat in Category.entries) {
            if (cat.group == group && cat.drivesGroupRate) {
                mass += pools[cat.ordinal].D
            }
        }
        return mass
    }

    private fun mapToTotal(iob: Double, act: Double, type: String, lastBolusTime: Long = 0L, bSnooze: Double = 0.0): IobTotal {
        val t = IobTotal(0)
        t.iob = iob; t.activity = act
        when (type) {
            "netBasal" -> {
                t.basaliob = iob; t.netbasalinsulin = iob; t.hightempinsulin = max(0.0, iob)
            }

            "extBolus" -> {
                t.extendedBolusInsulin = iob
            }

            "profile"  -> {
                t.basaliob = iob
            }

            "bolus"    -> {
                t.lastBolusTime = lastBolusTime
                t.bolussnooze = bSnooze
            }
        }
        return t
    }

    private fun buildDoseEventTimeline(
        fromTime: Long,
        toTime: Long,
        sensitivityRatio: Double,
        boluses: List<BS>,
        records: Records,
        isFakingTemps: Boolean,
        assumeZeroTempAfter: Long,
        currentNow: Long,
        pulses: BolusPulses
    ): List<DoseEvent> {

        val capacity = ((toTime - fromTime) / 60000L).toInt() + 10
        val timelineMap = HashMap<Long, DoseEvent>(capacity)

        fun getOrCreate(t: Long): DoseEvent {
            var dose = timelineMap[t]
            if (dose == null) {
                dose = DoseEvent(t)
                timelineMap[t] = dose
            }
            return dose
        }

        for (b in boluses) {
            // A priming bolus fills the cannula or tubing: none of it is on board, as in the stock calculator.
            if (b.isValid && b.type != BS.Type.PRIMING && b.timestamp in fromTime..toTime) {
                for (chunk in pulses.chunks(b.amount)) {
                    // A minute of pulses at a time, at their average time ([BolusPulses]); the chunks after toTime are
                    // after the requested time, and count as not given yet there (evaluatePools).
                    val time = b.timestamp + chunk.offsetMs
                    if (time > toTime) break
                    val dose = getOrCreate(time)
                    (dose.bolusParts ?: ArrayList<BolusPart>(1).also { dose.bolusParts = it }).add(BolusPart(b.timestamp, chunk))
                }
            }
        }

        val alignedFromTime = (fromTime / 60000L) * 60000L
        var bTime = alignedFromTime

        // Fetch Temp Basals and Extended Boluses ONCE into RAM, sorted chronologically so the
        // per-minute loop below can sweep forward through them with monotonically advancing
        // indices instead of rescanning the full list every single minute.
        val sortedTbs = records.tempBasalsStarting(alignedFromTime, toTime).sortedBy { it.timestamp }
        val sortedEbs = records.extendedBolusesStarting(alignedFromTime, toTime).sortedBy { it.timestamp }

        // Everything before these indices has definitely ended relative to the current bTime,
        // and can never become relevant again since bTime only moves forward and each record's
        // own start/end window never changes - so each record is only ever inspected while it's
        // still (or not yet) possibly active, not on every unrelated minute.
        var tbFromIdx = 0
        var ebFromIdx = 0

        while (bTime < toTime) {
            val nextTime = min(bTime + 60000L, toTime)
            val durationMins = (nextTime - bTime) / 60000.0

            if (durationMins > 0) {
                val minuteBasal = records.minuteBasal(bTime)
                if (minuteBasal != null) {
                    val profile = minuteBasal.profile
                    val profileRate = minuteBasal.rate
                    var absoluteRate = profileRate

                    // 1. Resolve Active Temp Basal in RAM (Replaces processedTbrEbData to support Deep History)
                    while (tbFromIdx < sortedTbs.size && min(sortedTbs[tbFromIdx].timestamp + sortedTbs[tbFromIdx].duration, currentNow) <= bTime) {
                        tbFromIdx++
                    }
                    var activeTb: TB? = null
                    for (idx in tbFromIdx until sortedTbs.size) {
                        val tb = sortedTbs[idx]
                        if (tb.timestamp > bTime) break // sorted ascending - none later can have started yet either
                        val effectiveEnd = min(tb.timestamp + tb.duration, currentNow)
                        if (bTime < effectiveEnd) {
                            if (activeTb == null || tb.timestamp > activeTb.timestamp) {
                                activeTb = tb
                            }
                        }
                    }
                    if (activeTb != null) {
                        absoluteRate = activeTb.convertedToAbsolute(bTime, profile)
                    }

                    // 2. Resolve Extended Boluses
                    while (ebFromIdx < sortedEbs.size && min(sortedEbs[ebFromIdx].timestamp + sortedEbs[ebFromIdx].duration, currentNow) <= bTime) {
                        ebFromIdx++
                    }
                    var extBolusChunk = 0.0
                    for (idx in ebFromIdx until sortedEbs.size) {
                        val e = sortedEbs[idx]
                        if (e.timestamp >= nextTime) break // sorted ascending - none later can overlap this minute either
                        val effectiveEbEnd = min(e.timestamp + e.duration, currentNow)
                        val overlapStart = max(bTime, e.timestamp)
                        val overlapEnd = min(nextTime, effectiveEbEnd)
                        if (overlapEnd > overlapStart) {
                            val overlapMins = (overlapEnd - overlapStart) / 60000.0
                            val ratePerMin = e.amount / (e.duration / 60000.0)
                            extBolusChunk += ratePerMin * overlapMins
                        }
                    }

                    // 3. Apply Zero-Temp Projection Cutoffs
                    if (bTime >= assumeZeroTempAfter) {
                        absoluteRate = 0.0
                        if (isFakingTemps) {
                            extBolusChunk = 0.0 // Also zero-out faked temps in the future
                        }
                    }

                    // 4. The profile basal the loop knew (ActivityMultiplierHistory): in a past minute in which
                    // AutoISF's activity monitor had it multiply the profile basal rate (below 1 for activity, above
                    // 1 for inactivity), that product is the minute's profile basal, now and later. It is the profile
                    // baseline, and the autosens-adjusted baseline too, since the activity multiplier replaced the
                    // autosens ratio then; the delivered basal counts against it whatever the loop chose to deliver.
                    // The future minutes of the IOB arrays and of the zero-temp projection keep the profile rate.
                    val activityMultiplier = if (bTime < currentNow) activityMultiplierHistory.multiplierAt(bTime) else 1.0
                    val basalRate = profileRate * activityMultiplier
                    val absAmt = absoluteRate * (durationMins / 60.0)
                    val profAmt = basalRate * (durationMins / 60.0)
                    val autoProfAmt = (if (activityMultiplier != 1.0) basalRate else profileRate * sensitivityRatio) * (durationMins / 60.0)

                    val dose = getOrCreate(nextTime)

                    // 5. Map Faked Temps correctly without double-counting
                    if (isFakingTemps) {
                        dose.basalAbsAmt += absAmt + extBolusChunk
                        dose.extBolusAmt += 0.0
                    } else {
                        dose.basalAbsAmt += absAmt
                        dose.extBolusAmt += extBolusChunk
                    }

                    dose.profileBasalAmt += profAmt
                    dose.autoProfileBasalAmt += autoProfAmt
                }
            }
            bTime = nextTime
        }

        return timelineMap.values.sortedBy { it.timestamp }
    }

    /**
     * Which pools crowd each other, and how much: the dose interaction m
     * (DoubleKey.InsulinTrafficJamMixing, 0 to 1; the absorption is fitted at 1, with all of the pump study's
     * insulin in one depot - see [TrafficJamChain]). Every delivery is one dose: each pulse of a bolus/SMB (below),
     * and each event's (each minute's) delivered basal, extended bolus and profile basal. Each dose is split into two
     * branches, each a depot -> plasma -> effect chain:
     *  - a shared branch, m of the dose, absorbing together with the shared branches of all the other
     *    doses of its group. Its rate follows the group's whole depot mass as if all of that insulin
     *    were in the shared branches, so together they are m times the one pooled (D, X, A) per group
     *    that the engine runs at m = 1, one pool per category, exactly as fitted.
     *  - an individual branch, 1 - m of the dose, absorbing on its own. Its rate follows its own depot
     *    mass as if the whole dose were in it, so it is 1 - m times the dose's isolated curve
     *    ([PdModel.isolatedState]), which depends on the dose's amount and its site's speed when it is
     *    given ([PdModel.siteSpeed]) and is tabulated once per amount and speed.
     * Every IOB and activity is therefore m times its value at m = 1 plus 1 - m times the sum of the
     * doses' isolated curves, and a dose with no other insulin on board follows the fitted curve at
     * every m. At m = 1 (default) all insulin follows the same path. At m = 0 no dose interacts with
     * any other: each absorbs as an isolated dose of its own size (a basal minute as a dose of rate/60
     * U), so a cascade is exactly the sum of its SMBs' pulses' isolated curves, on any basal rate. The groups:
     *  - [DELIVERED]: all insulin actually delivered - boluses, SMBs, extended boluses and basal
     *    (profile rate, temp basals, zero temps and temps faked by extended boluses alike) - so an
     *    SMB cascade jams and a basal rate slows the doses given on top of it.
     *  - [PROFILE]: the theoretical profile baseline, the profile basal alone, by the same rule -
     *    in the past minutes in which AutoISF's activity monitor multiplied it, the profile basal
     *    the loop knew then (buildDoseEventTimeline, step 4).
     *    autoProfileBasal (which scales with sensitivityRatio) moves at the profile basal's rate and
     *    must never feed back into it, or the "ground truth" profile curve would shift with autosens
     *    instead of reflecting only the programmed rate. With no temp basal, no other insulin on
     *    board and no activity multiplier the delivered and profile basal evolve identically and net
     *    basal IOB stays zero.
     *    The loop's IOB, bolus + extended bolus + net basal (TsunamiAwareIobCobCalculator), is then
     *    the total on board of all [DELIVERED] insulin minus this profile baseline. At m > 0 boluses,
     *    SMBs and extended boluses slow the delivered basal's shared branch; the basal they hold back
     *    in the depot (and its lower activity) is part of that total, and shows under net basal IOB
     *    even with no temp running.
     *
     * A bolus or SMB is not one dose but the pump's pulses ([BolusPulses]), as the absorption fit replays the
     * pump study's boluses, taken a minute at a time: each minute's pulses enter the shared branch together,
     * and each pulse's individual branch absorbs as a lone dose of the pulse size. At m = 1 a bolus enters
     * over its delivery time. Below 1 the individual share of every bolus absorbs as fast as a lone pulse,
     * whatever the bolus's size, so a lone bolus does not follow the same curve at every m: at 0 it is the sum
     * of its pulses' isolated curves. Basal, temp basals and extended boluses stay one dose a minute.
     */
    private enum class CrowdingGroup { DELIVERED, PROFILE }

    private enum class Category(val group: CrowdingGroup, val drivesGroupRate: Boolean = true) {
        BOLUS(CrowdingGroup.DELIVERED), EXT_BOLUS(CrowdingGroup.DELIVERED), BASAL_ABS(CrowdingGroup.DELIVERED),
        PROFILE_BASAL(CrowdingGroup.PROFILE), AUTO_PROFILE_BASAL(CrowdingGroup.PROFILE, drivesGroupRate = false)
    }

    /**
     * One pool's live state ([TrafficJamChain]): insulin still in the SC depot (D), in the blood (X), effect still to
     * come (A), and the depot rate [k] it was last given (SiteState.refreshRates, at every event). With a CGM lag (kc > 0)
     * also C, the effect the CGM has yet to show: activity is then kc*C instead of K_EFFECT*A, and C counts as on board.
     * With kc = 0, C stays 0 and nothing changes.
     */
    private class Pool(var D: Double = 0.0, var X: Double = 0.0, var A: Double = 0.0, var C: Double = 0.0, var k: Double = 0.0) {

        private val step = DoubleArray(4)

        fun iob(): Double = D + X + A + C
        fun activity(kc: Double): Double = if (kc > 0.0) kc * C else TrafficJamChain.K_EFFECT * A

        fun add(p: Pool) {
            D += p.D; X += p.X; A += p.A; C += p.C
        }

        fun propagated(kc: Double, dtMinutes: Double): Pool =
            Pool(D, X, A, C, k).also { it.propagateInPlace(kc, dtMinutes) }

        fun propagateInPlace(kc: Double, dtMinutes: Double) {
            if (dtMinutes <= 0.0 || (D == 0.0 && X == 0.0 && A == 0.0 && C == 0.0)) return
            TrafficJamChain.propagate(D, X, A, C, k, kc, dtMinutes, step)
            D = step[0]; X = step[1]; A = step[2]; C = step[3]
        }
    }

    /**
     * [count] doses' individual branches in [cat], at a dose interaction below 1: doses of the same amount
     * ([curve], see [PdModel.IsolatedCurve]) a minute apart, from [first] on, each [scale] times over (for a
     * bolus, a minute of its pulses; otherwise 1). For profile basal, [autoScale] is the matching
     * autoProfileBasal amount over the profile amount (its twin moves at the profile basal's rate, so its
     * curve is the same scaled), otherwise 0.
     */
    private class IndividualRun(val cat: Category, val curve: PdModel.IsolatedCurve, val autoScale: Double, val first: Long, val scale: Double = 1.0) {

        var count = 1
        val last: Long get() = first + (count - 1) * 60000L
    }

    /** One minute's pulses ([chunk], see [BolusPulses]) of the bolus given at [bolusTime], whose site they go to. */
    private class BolusPart(val bolusTime: Long, val chunk: BolusPulses.Chunk)

    private data class DoseEvent(
        val timestamp: Long,
        /** The bolus pulses given at [timestamp], a minute of a bolus's pulses each; null without a bolus. */
        var bolusParts: ArrayList<BolusPart>? = null,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var profileBasalAmt: Double = 0.0,
        var autoProfileBasalAmt: Double = 0.0
    )

    /**
     * Isolated single doses, for the individual branches of the dose interaction ([CrowdingGroup]) and the bolus snooze:
     * a dose of some amount alone at its site, its depot rate re-evaluated every whole minute of its age from its own
     * depot mass ([TrafficJamChain.rate]), as the engine re-evaluates the pools' rates at every event. Tabulated once per
     * (amount, absorption speed, CGM lag) on a 1-minute grid ([IsolatedCurve]) and evaluated in between by exact
     * propagation ([TrafficJamChain.propagate]); runs of equal doses a minute apart are summed in a few steps
     * ([runState]). The chain and its fit: [TrafficJamChain].
     */
    internal object PdModel {

        /** Isolated single-dose state is tabulated on a 1-min grid this long. */
        private const val ISOLATED_GRID_MINUTES = 600

        /**
         * The absorption speed of the isolated curve of a dose given at [time] on a site put in at [siteStart] (null: at
         * full speed by then) with a site ramp of [siteRampMs]: [speedFactor] times the site's age factor at [time],
         * rounded to 0.01 - a new site's age factor changes every minute, and an isolated curve is tabulated per speed.
         */
        fun siteSpeed(speedFactor: Double, siteStartSpeed: Double, siteRampMs: Long, siteStart: Long?, time: Long): Double {
            if (siteStart == null) return speedFactor
            val factor = TrafficJamChain.siteFactor(siteStartSpeed, siteRampMs, time - siteStart)
            return if (factor >= 1.0) speedFactor else speedFactor * (Math.round(factor * 100.0) / 100.0)
        }

        /** Isolated curves ([IsolatedCurve]) per (amount, speedFactor, kc), the 256 last used. */
        private val isolatedCurves = object : LinkedHashMap<Triple<Double, Double, Double>, IsolatedCurve>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Double, Double, Double>, IsolatedCurve>?): Boolean = size > 256
        }

        fun isolatedCurve(amount: Double, speedFactor: Double, kc: Double): IsolatedCurve = synchronized(isolatedCurves) {
            isolatedCurves.getOrPut(Triple(amount, speedFactor, kc)) { IsolatedCurve(amount, speedFactor, kc) }
        }

        fun isolatedGrid(amount: Double, speedFactor: Double, kc: Double): Array<DoubleArray> = isolatedCurve(amount, speedFactor, kc).grid

        /**
         * An isolated dose of [amount] at absorption speed [speedFactor] and [kc]: its table ([grid]) and what [runState]
         * needs to sum runs of such doses a minute apart, each made the first time it is needed (a dose evaluated only at
         * its own time, one given right at the requested time, never tabulates anything).
         */
        class IsolatedCurve(val amount: Double, val speedFactor: Double, val kc: Double) {

            /** (D, X, A, C) at whole minutes 0..ISOLATED_GRID_MINUTES - C (the CGM lag stage) all zero at kc = 0. */
            val grid: Array<DoubleArray> by lazy { tabulate(amount, speedFactor, kc) }

            /** Running sums of [grid]: sums[s][i] = grid[s][0] + ... + grid[s][i]. */
            val sums: Array<DoubleArray> by lazy { runningSums(grid) }

            /** Past the table the rate is held at its last value, so a step of a minute is a fixed linear map M there. */
            val tailRate: Double by lazy { TrafficJamChain.rate(speedFactor, grid[0][ISOLATED_GRID_MINUTES]) }

            /**
             * The sum over m >= 0 of M^m applied to the table's last state g - every state still to come - which is
             * h = (I - M)^-1 g. M only moves mass down the chain, so I - M is lower triangular, and this is forward
             * substitution on M's columns (unit states stepped by a minute).
             */
            val tailSum: DoubleArray by lazy {
                val g = DoubleArray(4) { grid[it][ISOLATED_GRID_MINUTES] }
                val m = Array(4) { j -> DoubleArray(4).also { held(DoubleArray(4).also { e -> e[j] = 1.0 }, this, 1.0, it) } }
                val h = DoubleArray(4)
                h[0] = g[0] / (1.0 - m[0][0])
                h[1] = (g[1] + m[0][1] * h[0]) / (1.0 - m[1][1])
                h[2] = (g[2] + m[0][2] * h[0] + m[1][2] * h[1]) / (1.0 - m[2][2])
                h[3] = if (kc > 0.0) (g[3] + m[0][3] * h[0] + m[1][3] * h[1] + m[2][3] * h[2]) / (1.0 - m[3][3]) else 0.0
                h
            }

            /** Running sums of [grid] shifted by a fraction of a minute, per remainder in ms, the two last used. */
            private val shifted = object : LinkedHashMap<Long, Array<DoubleArray>>(4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Array<DoubleArray>>?): Boolean = size > 2
            }

            /** Running sums of the dose's state at every age i + [remainderMs]/60000 minutes below the table's end. */
            fun shiftedSums(remainderMs: Long): Array<DoubleArray> = synchronized(shifted) {
                shifted.getOrPut(remainderMs) {
                    val table = Array(4) { DoubleArray(ISOLATED_GRID_MINUTES) }
                    val state = DoubleArray(4)
                    for (i in 0 until ISOLATED_GRID_MINUTES) {
                        isolatedStateOnGrid(grid, i + remainderMs / 60000.0, speedFactor, kc, state)
                        for (s in 0..3) table[s][i] = state[s]
                    }
                    runningSums(table)
                }
            }
        }

        private fun tabulate(amount: Double, speedFactor: Double, kc: Double): Array<DoubleArray> {
            val n = ISOLATED_GRID_MINUTES + 1
            val d = DoubleArray(n)
            val x = DoubleArray(n)
            val a = DoubleArray(n)
            val c = DoubleArray(n)
            d[0] = amount
            val step = DoubleArray(4)
            for (i in 1 until n) {
                TrafficJamChain.propagate(d[i - 1], x[i - 1], a[i - 1], c[i - 1], TrafficJamChain.rate(speedFactor, d[i - 1]), kc, 1.0, step)
                d[i] = step[0]; x[i] = step[1]; a[i] = step[2]; c[i] = step[3]
            }
            return arrayOf(d, x, a, c)
        }

        private fun runningSums(table: Array<DoubleArray>): Array<DoubleArray> = Array(4) { s ->
            val column = table[s]
            var sum = 0.0
            DoubleArray(column.size) { i -> sum += column[i]; sum }
        }

        /** [s] stepped by [minutes] at [curve]'s held tail rate, written into [out], as the table's tail is ([isolatedStateOnGrid]). */
        private fun held(s: DoubleArray, curve: IsolatedCurve, minutes: Double, out: DoubleArray) =
            TrafficJamChain.propagate(s[0], s[1], s[2], s[3], curve.tailRate, curve.kc, minutes, out)

        /**
         * The sum of [isolatedStateOnGrid] over [count] doses of [curve] given a minute apart, the oldest [ageMs]
         * milliseconds before, written into [out] as (D, X, A, C); equal to it dose by dose up to rounding. Every dose
         * of the run is the same fraction of a minute past its whole minutes. The table's stretch of whole minutes
         * comes from running sums: of the table itself at whole-minute ages, of the table shifted by the fraction
         * ([IsolatedCurve.shiftedSums]) if [shift], else dose by dose. Past the table the rate is held, and the sum of
         * M^m g over m = a..b is M^a h - M^(b+1) h, with h = [IsolatedCurve.tailSum] - two steps however many doses.
         */
        fun runState(curve: IsolatedCurve, ageMs: Long, count: Int, shift: Boolean, out: DoubleArray) {
            if (count == 1) {
                if (ageMs <= 0L) {
                    out[0] = curve.amount; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0
                } else isolatedStateOnGrid(curve.grid, ageMs / 60000.0, curve.speedFactor, curve.kc, out)
                return
            }
            out.fill(0.0)
            val remainder = ageMs % 60000L
            val fraction = remainder / 60000.0
            val oldest = (ageMs / 60000L).toInt() // whole minutes of the oldest dose's age
            val newest = oldest - count + 1        // and of the newest's
            val state = DoubleArray(4)
            val tableTop = min(oldest, ISOLATED_GRID_MINUTES - 1)
            if (newest <= tableTop) {
                when {
                    remainder == 0L -> addSums(curve.sums, newest, tableTop, out)
                    shift           -> addSums(curve.shiftedSums(remainder), newest, tableTop, out)
                    else            -> for (i in newest..tableTop) {
                        isolatedStateOnGrid(curve.grid, i + fraction, curve.speedFactor, curve.kc, state)
                        for (s in 0..3) out[s] += state[s]
                    }
                }
            }
            val tailBottom = max(newest, ISOLATED_GRID_MINUTES)
            if (tailBottom == oldest) {
                isolatedStateOnGrid(curve.grid, oldest + fraction, curve.speedFactor, curve.kc, state)
                for (s in 0..3) out[s] += state[s]
            } else if (tailBottom < oldest) {
                val h = curve.tailSum
                held(h, curve, (tailBottom - ISOLATED_GRID_MINUTES) + fraction, state)
                for (s in 0..3) out[s] += state[s]
                held(h, curve, (oldest + 1 - ISOLATED_GRID_MINUTES) + fraction, state)
                for (s in 0..3) out[s] -= state[s]
            }
        }

        private fun addSums(sums: Array<DoubleArray>, from: Int, to: Int, out: DoubleArray) {
            for (s in 0..3) out[s] += sums[s][to] - (if (from > 0) sums[s][from - 1] else 0.0)
        }

        /**
         * (D, X, A, C) of an isolated (unpooled) single dose of [amount], [tElapsed] minutes after it,
         * with the rate re-evaluated every minute from the dose's own live depot mass, as the
         * engine does. Past ISOLATED_GRID_MINUTES the rate is held at its last value.
         */
        fun isolatedState(tElapsed: Double, amount: Double, speedFactor: Double, kc: Double): DoubleArray {
            val out = DoubleArray(4)
            if (tElapsed <= 0.0) {
                out[0] = amount
                return out
            }
            isolatedStateOnGrid(isolatedGrid(amount, speedFactor, kc), tElapsed, speedFactor, kc, out)
            return out
        }

        /**
         * [isolatedState] of the dose tabulated in [grid] ([isolatedGrid], for the same [kc]), written into
         * [out] as (D, X, A, C). On a whole minute of the table no propagation is needed (it would be by 0 minutes).
         */
        fun isolatedStateOnGrid(grid: Array<DoubleArray>, tElapsed: Double, speedFactor: Double, kc: Double, out: DoubleArray) {
            if (tElapsed <= 0.0) {
                out[0] = grid[0][0]; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0
                return
            }
            val i = min(tElapsed.toInt(), ISOLATED_GRID_MINUTES)
            val dt = tElapsed - i
            if (dt == 0.0) {
                out[0] = grid[0][i]; out[1] = grid[1][i]; out[2] = grid[2][i]; out[3] = grid[3][i]
                return
            }
            TrafficJamChain.propagate(grid[0][i], grid[1][i], grid[2][i], grid[3][i], TrafficJamChain.rate(speedFactor, grid[0][i]), kc, dt, out)
        }

        /** Isolated (unpooled) fraction of [amount] still on board [tElapsed] minutes after a single dose. */
        fun isolatedIobFraction(tElapsed: Double, amount: Double, speedFactor: Double, kc: Double): Double {
            if (amount <= 0.0) return 0.0
            val s = isolatedState(tElapsed, amount, speedFactor, kc)
            return (s[0] + s[1] + s[2] + s[3]) / amount
        }
    }
}
