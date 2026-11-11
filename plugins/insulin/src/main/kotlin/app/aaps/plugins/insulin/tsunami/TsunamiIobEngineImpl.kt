package app.aaps.plugins.insulin.tsunami

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Traffic Jam" IOB engine backing [Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM].
 *
 * Models a shared subcutaneous depot as a small, continuously-evolving pool of linear
 * (first-order) compartments - not one independent closed-form curve per dose. Every dose
 * is an impulse added directly into its category's own depot state; crowding is expressed
 * as the shared depot/transit rate depending on how much mass is currently still in the
 * depot, evaluated live rather than snapshotted per dose. Because every state is a genuine ODE
 * state (not an age-parametrized curve), a rate constant can change value at any instant
 * with no reinterpretation needed - the state simply keeps evolving from wherever it
 * currently is. This is what the earlier per-dose-curve design could not do: that design
 * evaluated `IOB(t) = exp(-t^p/tp)` from each curve's own elapsed age, so an amount-driven
 * change to `tp` had no continuous way to apply mid-flight, and had to be patched with a
 * "time warp" (retroactively inventing a new elapsed age to keep IOB continuous) that
 * unavoidably forced a discontinuous drop in reported activity every time the pool grew.
 * Here activity is KE * A with a constant KE, and a dose adds only to the depot, so activity
 * never jumps (see [PdModel]). An optional CGM lag stage after A ([CgmLagStage]) delays and smooths
 * the reported activity to when the CGM can see it.
 *
 * See [PdModel] for the full derivation and the calibration that replaced it.
 */
@Singleton
class TsunamiIobEngineImpl @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
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
        // extended boluses starting within [from, to], cannula changes from `from` on.
        private val boluses = persistenceLayer.getBolusesFromTime(from, true).blockingGet().sortedBy { it.timestamp }
        private val tempBasals = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(from, to, true)
        private val extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(from, to, true)
        private val cannulaChanges = persistenceLayer.getTherapyEventDataFromTime(from, TE.Type.CANNULA_CHANGE, true)

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
    // 3-compartment pool (depot -> transit -> active, see [PdModel]) so IOB/activity can
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

        val timeline = buildDoseEventTimeline(startTime, horizonTime, sensitivityRatio, boluses, records, isFakingTemps, assumeZeroTempAfter, now)

        val divisor = preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)

        // Per-patient absorption speed knob (DoubleKey.InsulinTrafficJamSpeedMultiplier): scales
        // every stage's rate alike, the crowded depot/transit rate and KE, so the whole curve
        // stretches or compresses in time. Dose interaction
        // (DoubleKey.InsulinTrafficJamMixing, see [CrowdingGroup]). Both read once per
        // simulated window, like the fitted constants are effectively constant - a mid-window
        // change to either preference is not retroactively applied to the past, same as those.
        val speedFactor = preferences.get(DoubleKey.InsulinTrafficJamSpeedMultiplier)
        val ke = speedFactor * PdModel.KE
        val mixing = preferences.get(DoubleKey.InsulinTrafficJamMixing).coerceIn(0.0, 1.0)
        // CGM lag (DoubleKey.InsulinTrafficJamCgmLag, see [CgmLagStage]): the rate of the stage after the
        // active one, or 0 for no such stage (the default, unchanged from before). Not scaled by the speed
        // setting: it is the sensor's delay, not the insulin's.
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

        // One persistent set of pools (and crowding rates) per site generation, never shared
        // across generations: the shared branch of every dose, one pool per category (see [CrowdingGroup]).
        class SiteState {

            // All of a group's shared branches share one rate, so one pool per category holds them exactly.
            val pools = Array(Category.entries.size) { Pool() }

            fun propagateInPlace(dtMinutes: Double) {
                for (p in pools) p.propagateInPlace(ke, kc, dtMinutes)
            }

            /** Every pool's depot/transit rate from its group's live depot mass (see [CrowdingGroup]). */
            fun refreshRates() {
                val deliveredDepot = groupDepotMass(pools, CrowdingGroup.DELIVERED)
                val profileDepot = groupDepotMass(pools, CrowdingGroup.PROFILE)
                for (cat in Category.entries) {
                    if (!cat.drivesGroupRate) continue
                    val groupDepot = if (cat.group == CrowdingGroup.DELIVERED) deliveredDepot else profileDepot
                    pools[cat.ordinal].k = PdModel.rate(speedFactor, groupDepot)
                }
                // autoProfileBasal moves at the profile basal's rate, never at its own.
                pools[Category.AUTO_PROFILE_BASAL.ordinal].k = pools[Category.PROFILE_BASAL.ordinal].k
            }
        }

        val sites = HashMap<Int, SiteState>()
        fun siteFor(gen: Int): SiteState = sites.getOrPut(gen) { SiteState() }
        var lastTime = startTime

        // The individual branch of every dose given so far, at a dose interaction below 1: its isolated
        // curve ([PdModel.IsolatedCurve], one per amount), grouped into runs of equal doses a minute apart
        // in each category ([IndividualRun]). A basal rate is one dose every minute, so a stretch at one
        // rate is a single run, summed in a few steps ([PdModel.runState]) instead of dose by dose. A
        // profile-basal dose also carries its autoProfileBasal twin, which moves at the profile basal's
        // rate and so is the same curve scaled by [IndividualRun.autoScale].
        val individualRuns = ArrayList<IndividualRun>()
        val openRuns = arrayOfNulls<IndividualRun>(Category.entries.size)
        val curvesByAmount = HashMap<Double, PdModel.IsolatedCurve>()
        fun addIndividual(timestamp: Long, cat: Category, amount: Double, autoScale: Double = 0.0) {
            val curve = curvesByAmount.getOrPut(amount) { PdModel.isolatedCurve(amount, speedFactor, kc) }
            val open = openRuns[cat.ordinal]
            if (open != null && open.curve === curve && open.autoScale == autoScale && open.last + 60000L == timestamp) open.count++
            else IndividualRun(cat, curve, autoScale, timestamp).also { individualRuns.add(it); openRuns[cat.ordinal] = it }
        }
        val runState = DoubleArray(4)
        // Runs of whole-minute doses are all requestedTime's offset past the whole minute old; if requests
        // keep sharing that offset, the curves are tabulated shifted by it ([PdModel.runState]).
        val offset = requestedTime % 60_000L
        val shiftWholeMinuteRuns = mixing < 1.0 && offset != 0L && offsetRequestedOften(offset)

        // Sorted so the snooze/lastBolusTime bookkeeping below can sweep forward with monotonically
        // advancing indices.
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
                if (b.type != BS.Type.SMB) {
                    // Age used by this bolus's own isolated curve is divisor times the real elapsed
                    // time (matching the stock calculator's "evaluate the curve further in the future"
                    // technique - see IobCobCalculatorPlugin.calculateIobFromBolusToTime), NOT elapsed
                    // time since some derived timestamp - using the latter silently zeroed this out
                    // for every divisor value in the enforced [1, 10] range.
                    val tSnoozeElapsed = ((tTarget - b.timestamp) * divisor) / 60000.0
                    if (tSnoozeElapsed in 0.0..<DIA_HORIZON_MINUTES) {
                        // Snooze reflects this bolus's own isolated pool, not shared crowding.
                        bSnooze += b.amount * PdModel.isolatedIobFraction(tSnoozeElapsed, b.amount, speedFactor, kc)
                    }
                }
            }

            // Shared branches: sums every still-live site generation's own peeked pools - crowding
            // never crosses generations, but the reported total is their combined IOB/activity.
            val iob = DoubleArray(Category.entries.size)
            val act = DoubleArray(Category.entries.size)
            for (cat in Category.entries) {
                val sum = Pool()
                for (site in sites.values) sum.add(site.pools[cat.ordinal].propagated(ke, kc, dtPeek))
                iob[cat.ordinal] = sum.iob()
                act[cat.ordinal] = sum.activity(ke, kc)
            }

            // m times the shared branches, which the pools hold as they would at m = 1, plus 1 - m times
            // the individual branches, i.e. the doses' isolated curves (see [CrowdingGroup]).
            if (mixing < 1.0) {
                val indIob = DoubleArray(Category.entries.size)
                val indAct = DoubleArray(Category.entries.size)
                for (run in individualRuns) {
                    PdModel.runState(run.curve, tTarget - run.first, run.count, shiftWholeMinuteRuns && run.first % 60_000L == 0L, runState)
                    val runIob = runState[0] + runState[1] + runState[2] + runState[3]
                    val runAct = if (kc > 0.0) kc * runState[3] else ke * runState[2]
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

            // Shared branches, held in the pools as they would be at m = 1 (see [CrowdingGroup]).
            if (dose.bolusAmt > 0.0) site.pools[Category.BOLUS.ordinal].D += dose.bolusAmt
            if (dose.extBolusAmt > 0.0) site.pools[Category.EXT_BOLUS.ordinal].D += dose.extBolusAmt
            if (dose.basalAbsAmt > 0.0) site.pools[Category.BASAL_ABS.ordinal].D += dose.basalAbsAmt
            if (dose.profileBasalAmt > 0.0) {
                site.pools[Category.PROFILE_BASAL.ordinal].D += dose.profileBasalAmt
                if (dose.autoProfileBasalAmt > 0.0) site.pools[Category.AUTO_PROFILE_BASAL.ordinal].D += dose.autoProfileBasalAmt
            }

            // Individual branches, below m = 1.
            if (mixing < 1.0) {
                if (dose.bolusAmt > 0.0) addIndividual(dose.timestamp, Category.BOLUS, dose.bolusAmt)
                if (dose.extBolusAmt > 0.0) addIndividual(dose.timestamp, Category.EXT_BOLUS, dose.extBolusAmt)
                if (dose.basalAbsAmt > 0.0) addIndividual(dose.timestamp, Category.BASAL_ABS, dose.basalAbsAmt)
                // autoProfileBasal is the profile basal scaled by sensitivityRatio, so it comes with it.
                if (dose.profileBasalAmt > 0.0) {
                    addIndividual(dose.timestamp, Category.PROFILE_BASAL, dose.profileBasalAmt, dose.autoProfileBasalAmt / dose.profileBasalAmt)
                }
            }

            // Every rate follows its group's live depot mass, so it is re-evaluated at every event,
            // whether or not this event injected anything into that group: buildDoseEventTimeline
            // emits one event per minute wherever a profile exists (zero temps and the zero-temp
            // projection included), so no rate is then held for more than a minute while its depot
            // empties. Where no profile exists, only boluses create events and the rates are held
            // between them. Every live generation too, since an old site's depot keeps emptying
            // after dosing has moved to the new one.
            for (s in sites.values) s.refreshRates()
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
     * [CrowdingGroup] for which pools crowd which). Deliberately D only, never D+X: X is mass that has already left
     * the depot (past the crowding-limited step, in transit toward the active/eliminated stage),
     * so it no longer competes for SC depot space and must not inflate the rate that governs the
     * depot's own outflow. Getting this wrong compounds under sustained dosing - X never fully
     * clears between frequent small doses (real SMB/TBR cadence), so including it keeps dragging
     * k1 down further the longer a session runs. [pools] is always one site generation's own
     * pools, never mixed across sites. Floored inside [PdModel.rate].
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
        currentNow: Long
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
            if (b.isValid && b.timestamp in fromTime..toTime) {
                getOrCreate(b.timestamp).bolusAmt += b.amount
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

                    val absAmt = absoluteRate * (durationMins / 60.0)
                    val profAmt = profileRate * (durationMins / 60.0)
                    val autoProfAmt = (profileRate * sensitivityRatio) * (durationMins / 60.0)

                    val dose = getOrCreate(nextTime)

                    // 4. Map Faked Temps correctly without double-counting
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
     * (DoubleKey.InsulinTrafficJamMixing, 0 to 1, not fitted - the EPAR data has single doses with no
     * basal only). Every delivery is one dose: each bolus/SMB, and each event's (each minute's)
     * delivered basal, extended bolus and profile basal. Each dose is split into two branches, each a
     * depot -> transit -> active chain:
     *  - a shared branch, m of the dose, absorbing together with the shared branches of all the other
     *    doses of its group. Its rate follows the group's whole depot mass as if all of that insulin
     *    were in the shared branches, so together they are m times the one pooled (D, X, A) per group
     *    that the engine runs at m = 1, one pool per category, exactly as fitted.
     *  - an individual branch, 1 - m of the dose, absorbing on its own. Its rate follows its own depot
     *    mass as if the whole dose were in it, so it is 1 - m times the dose's isolated curve
     *    ([PdModel.isolatedState]), which depends on the dose's amount alone and is tabulated once per
     *    amount.
     * Every IOB and activity is therefore m times its value at m = 1 plus 1 - m times the sum of the
     * doses' isolated curves, and a dose with no other insulin on board follows the fitted curve at
     * every m. At m = 1 (default) all insulin follows the same path. At m = 0 no dose interacts with
     * any other: each absorbs as an isolated dose of its own size (a basal minute as a dose of rate/60
     * U), so a cascade is exactly the sum of its SMBs' isolated curves, on any basal rate. The groups:
     *  - [DELIVERED]: all insulin actually delivered - boluses, SMBs, extended boluses and basal
     *    (profile rate, temp basals, zero temps and temps faked by extended boluses alike) - so an
     *    SMB cascade jams and a basal rate slows the doses given on top of it.
     *  - [PROFILE]: the theoretical profile baseline, the profile basal alone, by the same rule.
     *    autoProfileBasal (which scales with sensitivityRatio) moves at the profile basal's rate and
     *    must never feed back into it, or the "ground truth" profile curve would shift with autosens
     *    instead of reflecting only the programmed rate. With no temp basal and no other insulin on
     *    board the delivered and profile basal evolve identically and net basal IOB stays zero.
     *    The loop's IOB, bolus + extended bolus + net basal (TsunamiAwareIobCobCalculator), is then
     *    the total on board of all [DELIVERED] insulin minus this profile baseline. At m > 0 boluses,
     *    SMBs and extended boluses slow the delivered basal's shared branch; the basal they hold back
     *    in the depot (and its lower activity) is part of that total, and shows under net basal IOB
     *    even with no temp running.
     */
    private enum class CrowdingGroup { DELIVERED, PROFILE }

    private enum class Category(val group: CrowdingGroup, val drivesGroupRate: Boolean = true) {
        BOLUS(CrowdingGroup.DELIVERED), EXT_BOLUS(CrowdingGroup.DELIVERED), BASAL_ABS(CrowdingGroup.DELIVERED),
        PROFILE_BASAL(CrowdingGroup.PROFILE), AUTO_PROFILE_BASAL(CrowdingGroup.PROFILE, drivesGroupRate = false)
    }

    /**
     * One pool's live state: mass still in the SC depot (D), in transit (X), or active (A), and the
     * depot/transit rate [k] it was last given (SiteState.refreshRates, at every event). With a CGM lag
     * (kc > 0, see [CgmLagStage]) also C, insulin whose effect the CGM has yet to see: activity is then
     * kc*C instead of ke*A, and C counts as on board. With kc = 0, C stays 0 and nothing changes.
     */
    private class Pool(var D: Double = 0.0, var X: Double = 0.0, var A: Double = 0.0, var C: Double = 0.0, var k: Double = 0.0) {

        fun iob(): Double = D + X + A + C
        fun activity(ke: Double, kc: Double): Double = if (kc > 0.0) kc * C else ke * A

        fun add(p: Pool) {
            D += p.D; X += p.X; A += p.A; C += p.C
        }

        fun propagated(ke: Double, kc: Double, dtMinutes: Double): Pool =
            Pool(D, X, A, C, k).also { it.propagateInPlace(ke, kc, dtMinutes) }

        fun propagateInPlace(ke: Double, kc: Double, dtMinutes: Double) {
            if (dtMinutes <= 0.0 || (D == 0.0 && X == 0.0 && A == 0.0 && C == 0.0)) return
            if (kc > 0.0) {
                val s = DoubleArray(4)
                PdModel.propagateWithLag(D, X, A, C, k, ke, kc, dtMinutes, s)
                D = s[0]; X = s[1]; A = s[2]; C = s[3]
            } else {
                val (nd, nx, na) = PdModel.propagate(D, X, A, k, k, ke, dtMinutes)
                D = nd; X = nx; A = na
            }
        }
    }

    /**
     * [count] doses' individual branches in [cat], at a dose interaction below 1: doses of the same amount
     * ([curve], see [PdModel.IsolatedCurve]) a minute apart, from [first] on. For profile basal, [autoScale]
     * is the matching autoProfileBasal amount over the profile amount (its twin moves at the profile basal's
     * rate, so its curve is the same scaled), otherwise 0.
     */
    private class IndividualRun(val cat: Category, val curve: PdModel.IsolatedCurve, val autoScale: Double, val first: Long) {

        var count = 1
        val last: Long get() = first + (count - 1) * 60000L
    }

    private data class DoseEvent(
        val timestamp: Long,
        var bolusAmt: Double = 0.0,
        var extBolusAmt: Double = 0.0,
        var basalAbsAmt: Double = 0.0,
        var profileBasalAmt: Double = 0.0,
        var autoProfileBasalAmt: Double = 0.0
    )

    /**
     * Three linked first-order compartments: D (SC depot) -> X (transit) -> A (active, reported as
     * activity = KE*A). IOB(t) = D(t) + X(t) + A(t): mass not yet through the final step.
     * With a CGM lag set (DoubleKey.InsulinTrafficJamCgmLag), a fourth, C, follows A at the fixed rate
     * kc = 1/lag ([CgmLagStage]): activity is then kc*C and IOB D + X + A + C, the effect as the CGM sees
     * it. The lag stage is not part of the fit below - the EPAR clamp curves measure the effect in the blood.
     *
     * Depot and transit share ONE crowding-dependent rate, K(M) = K1_B0 * M^K1_B1 ([rate]), where M
     * is the live depot mass of the dose's crowding group (D only - see groupDepotMass and
     * CrowdingGroup; at a dose interaction below 1, this is the shared branch, and the individual
     * branch is the dose's isolated curve, with M its own depot mass). It is re-evaluated at
     * every event (every whole minute and every bolus timestamp, right after that event's doses
     * enter D; for an isolated curve, every whole minute of the dose's age) and held until the next,
     * and each interval is solved exactly. [KE] is fixed and
     * never touched by crowding (only the speed setting scales it, like every other rate), so
     * activity = KE*A stays exactly continuous through every dose event: a new dose slows mass
     * still in the depot or transit, never mass already in the active stage. With K1_B1 < 0 the
     * rate rises as a dose's own depot empties, so absorption speeds up late in each dose: 0.0298 /min at 0.1U,
     * 0.0178 at 1U, 0.0116 at 7U, 0.0084 at 30U, 0.385 /min (half-life 1.80 min) at an empty depot
     * (the 1e-6U floor). That gives the flatter, more symmetric top of the EPAR curves, which no
     * chain of stages with rates held fixed per dose can produce (its skewness for a given CV is at
     * least an Erlang curve's). Each SMB of a cascade lowers the rate at once, and with KE's
     * half-life of 5.3 min the active stage passes that on within minutes: in the 12-SMB cascade
     * below, activity falls by up to 0.7% of its peak from one minute to the next while the SMBs
     * are being given, without jumping.
     *
     * Why the rate is live: earlier constants were fitted with the rate held at K(dose) for the
     * whole dose, while this engine re-evaluates it from the live depot mass. Run the way the
     * engine runs them with no basal, those constants scored EPAR activity R^2 0.654 / 0.533 /
     * 0.449 at 7 / 15 / 30U. When constants were fitted to EPAR plus TsunamiMods' small-dose curves
     * given on a basal rate, with the basal depot mass counted in full in the SMBs' crowding mass
     * (dose interaction 1), no constants fitted both (KE ran to the upper end of any search range, and
     * the EPAR objective defined below stayed near 0.26 at 0.5 U/h). The current constants are
     * fitted to EPAR alone, with no basal; how much a basal rate slows the doses given on it is set
     * by the dose interaction (see below).
     *
     * Fit, in two steps, to the digitized EPAR GIR clamp curves (Lyumjev/LY900014, 7/15/30U, points
     * up to 480 min, no basal insulin infused). Both steps use curves truncated at 480 min: every
     * curve, target and model alike, is cut at 480 min and normalized over 0-480 min. Activity is
     * divided by its integral over 0-480 min, and IOB is 1 minus the cumulative activity divided by
     * its value at 480 min (1 at 0, 0 at 480 min); for the model's isolated single-dose activity
     * a(t) and IOB fraction S(t), that is a(t) / (1 - S(480)) and (S(t) - S(480)) / (1 - S(480)).
     * Step 1: TsunamiMods' Weibull form fitted to each EPAR dose alone (activity and IOB together,
     * each curve's squared errors divided by its total sum of squares) gives each dose's activity
     * peak: 103.34 / 127.31 / 138.45 min, height 5.4654 / 4.5913 / 3.9392e-3 /min at 7 / 15 / 30U.
     * Step 2: K1_B0, K1_B1 and KE fitted, with the rate re-evaluated every minute as above, to those
     * peaks: the sum over 7 / 15 / 30U of ln^2(model peak time / step-1 peak time) + ln^2(model
     * peak height / step-1 peak height), the model's peak taken on its cut, normalized activity
     * (sampled at whole minutes, parabola through the 3 samples around the maximum). The model's
     * peaks: 104.9 / 122.6 / 141.7 min, heights within 0.4% of step 1's. Scored on the EPAR curves
     * themselves (model sampled at whole minutes, right after each re-evaluation, interpolated
     * linearly to the EPAR sample times), the sum over the 6 curves of (1 - R^2) is 0.0672 (the
     * previous constants, fitted directly to that sum with four constants: 0.0441; TsunamiMods'
     * Weibull form fitted directly to it: 0.0582, a0 69.944 min, a1 0.2177, p 1.9017, within 2.0%
     * of TsunamiMods' constants). R^2 activity / IOB: 0.982 / 0.997, 0.988 / 0.999, 0.969 / 0.998
     * at 7 / 15 / 30U. No curve below 7U is in the fit, so the small-dose timing below is the
     * fitted law's extrapolation. Isolated dose with no basal at speed 1.0, minutes until 1 -
     * IOB/dose reaches 0.5 / 0.9 (whole curve, not cut at 480 min): 0.1U 55 / 97 (TsunamiMods
     * 52 / 97), 0.3U 68 / 121 (65 / 122), 1U 86 / 155 (84 / 158), 3U 108 / 195 (106 / 200), 5U
     * 120 / 217 (119 / 223). Given on a steady basal rate, the basal depot slows the dose (see
     * CrowdingGroup.DELIVERED; as with no basal at dose interaction 0, slower above): on 0.5 / 1
     * U/h, 0.3U 90 / 192 and 104 / 227, 1U 100 / 206 and 110 / 236, 3U 116 / 230 and 123 / 253 at
     * dose interaction 1; 0.3U 73 / 140 and 75 / 150, 1U 90 / 168 and 92 / 176, 3U 110 / 204 and
     * 112 / 210 at 0.3. Still on board at the 480-min horizon, where the engine drops a dose: at speed
     * 1.0, at most 1.1e-8% of a dose up to 7U and 0.09% of 30U; at speed 0.5 (every stage at half
     * its rate), 0.038% of 1U, 2.3% of 3U, 5.5% of 5U and 28% of 30U.
     * Python replica of this engine without its 480-min window (equal to this file run on the JVM
     * within 4.1e-8U of IOB over the first 480 min of the doses below, at speed 0.5 to 2), 12 SMBs
     * totalling 7.85U in one group (0.9U at 0 min, 0.6 at 10, 1.3 at 20, 0.4 at 35, 0.7 at 45, 0.5
     * at 60, 0.3 at 75, 0.8 at 90, 0.5 at 105, 0.4 at 120, 0.55 at 140, 0.9 at 160): the cascade's
     * IOB exceeds the sum of its SMBs' separate IOB contributions by up to 23.2% of 7.85U, and its
     * delivered insulin minus IOB reaches 50% / 90% of 7.85U 46.5 / 68.9 min later than for the
     * same SMBs computed separately and summed, with no basal (previous constants: 9.7%, 22.8 /
     * 23.7 min). That is at the default dose interaction of 1 (CrowdingGroup); at 0.5 / 0.3 / 0.1 /
     * 0 (that times the extra IOB, by the two branches): up to 11.6 / 7.0 / 2.3 / 0% of 7.85U, and
     * 20.1 / 40.9, 11.3 / 25.6, 3.5 / 8.6 and 0 / 0 min later. On a steady 0.5 / 1 U/h basal rate,
     * against the same reference: 52.6 / 92.5 and 58.2 / 110.7 min later at dose interaction 1, 12.5
     * / 31.7 and 13.6 / 37.0 at 0.3, 0 / 0 at 0. At dose interaction 1, a basal rate started with no other
     * insulin on board (rate/60 U at the end of every minute) reaches 50% / 90% of its steady-state activity after
     * 81 / 196 min at 0.5 U/h and 96 / 237 min at 1 U/h (previous constants: 106 / 246 and 113 /
     * 262).
     *
     * Fitted constants (masses in U, rates in 1/min):
     *  - K(M) = K1_B0 * M^K1_B1, K1_B0 = 0.01784168, K1_B1 = -0.2224188 (depot and transit)
     *  - KE   = 0.1297809 (t1/2 5.34 min; the active stage)
     * Previous (active stage crowding too, KE(A) = KE_E0 * A^KE_E1, fitted directly to the EPAR
     * curves): K1_B0 = 0.01617986, K1_B1 = -0.03676295, KE_E0 = 0.0427785, KE_E1 = -0.4966087.
     * Before that (live rate, EPAR plus TsunamiMods' 0.2-3.5U curves): K1_B0 = 0.017258, K1_B1 =
     * -0.215412, KE = 0.4389397 (t1/2 1.6 min).
     * Before that (rate held per dose): K1_B0 = 0.032072, K1_B1 = -0.303093, KE = 0.0315818.
     * Raw-EPAR fit, rate held per dose: K1_B0 = 0.044874, K1_B1 = -0.339993, KE = 0.0170232.
     * Depot-only model: K1_B0 = 0.069092, K1_B1 = -0.596593 (depot only), K2 = 0.031796,
     * KE = ln(2)/44.
     */
    internal object PdModel {
        const val KE = 0.1297809 // fitted; t1/2 5.34 min

        const val K1_B0 = 0.01784168
        const val K1_B1 = -0.2224188

        /** Never feed a literal zero into the negative-exponent power law. */
        private const val MASS_FLOOR = 1e-6

        /** Isolated single-dose state is tabulated on a 1-min grid this long. */
        private const val ISOLATED_GRID_MINUTES = 600

        private const val RATE_EPS = 1e-7

        /**
         * [propagateWithLag] splits longer intervals into steps this long - exact, since rates are constant
         * within one call - so that exp() of the scaled rate differences in [CgmLagStage] stays finite.
         */
        private const val LAG_STEP_MINUTES = 60.0

        /**
         * Exact closed-form propagation of (D, X, A) forward by [dt] minutes under constant
         * rates (k1, k2, ke) - equivalent to integrating the linear ODE
         *   dD/dt = -k1*D ; dX/dt = k1*D - k2*X ; dA/dt = k2*X - ke*A
         * with no discretization error for any dt. Built from two primitives ([chainStage2] and
         * [threeStageA]) so every place a rate-difference would divide by (near) zero is routed
         * through an explicit, numerically-safe degenerate branch instead - callers pass the same
         * crowded rate for k1 and k2, and crowding continuously varies it, so it crossing
         * arbitrarily close to the fixed KE is an expected, not exceptional, case.
         */
        fun propagate(d0: Double, x0: Double, a0: Double, k1: Double, k2: Double, ke: Double, dt: Double): Triple<Double, Double, Double> {
            val d = d0 * exp(-k1 * dt)
            val x = x0 * exp(-k2 * dt) + chainStage2(d0, k1, k2, dt)
            val a = a0 * exp(-ke * dt) + chainStage2(x0, k2, ke, dt) + threeStageA(d0, k1, k2, ke, dt)
            return Triple(d, x, a)
        }

        /**
         * Depot/transit rate of a pool whose crowding mass is [depotMass], at the
         * per-patient absorption [speedFactor] (DoubleKey.InsulinTrafficJamSpeedMultiplier, applied
         * directly - see that key's doc comment). KE is scaled by the same factor at every caller.
         */
        fun rate(speedFactor: Double, depotMass: Double): Double =
            speedFactor * K1_B0 * max(depotMass, MASS_FLOOR).pow(K1_B1)

        /**
         * [propagate] with the CGM lag stage ([CgmLagStage]) at rate [kc] after the active stage, depot and
         * transit both at [k]: (D, X, A, C) after [dt] minutes, written into [out]. D and X come out exactly as
         * from [propagate]; A is the same exact solution, without [propagate]'s rounding of rates closer than
         * RATE_EPS to equal.
         */
        fun propagateWithLag(d0: Double, x0: Double, a0: Double, c0: Double, k: Double, ke: Double, kc: Double, dt: Double, out: DoubleArray) {
            out[0] = d0; out[1] = x0; out[2] = a0; out[3] = c0
            var left = dt
            while (left > 0.0) {
                val step = min(left, LAG_STEP_MINUTES)
                CgmLagStage.propagate(out[0], out[1], out[2], out[3], k, ke, kc, step, out)
                left -= step
            }
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
         * An isolated dose of [amount] at [speedFactor] and [kc]: its table ([grid]) and what [runState] needs to sum
         * runs of such doses a minute apart, each made the first time it is needed (a dose evaluated only at its own
         * time, one given right at the requested time, never tabulates anything).
         */
        class IsolatedCurve(val amount: Double, val speedFactor: Double, val kc: Double) {

            /** (D, X, A, C) at whole minutes 0..ISOLATED_GRID_MINUTES - C (the CGM lag stage) all zero at kc = 0. */
            val grid: Array<DoubleArray> by lazy { tabulate(amount, speedFactor, kc) }

            /** Running sums of [grid]: sums[s][i] = grid[s][0] + ... + grid[s][i]. */
            val sums: Array<DoubleArray> by lazy { runningSums(grid) }

            /** Past the table the rate is held at its last value, so a step of a minute is a fixed linear map M there. */
            val tailRate: Double by lazy { rate(speedFactor, grid[0][ISOLATED_GRID_MINUTES]) }

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
                val k1 = rate(speedFactor, d[i - 1])
                if (kc > 0.0) {
                    CgmLagStage.propagate(d[i - 1], x[i - 1], a[i - 1], c[i - 1], k1, speedFactor * KE, kc, 1.0, step)
                    d[i] = step[0]; x[i] = step[1]; a[i] = step[2]; c[i] = step[3]
                } else {
                    val (nd, nx, na) = propagate(d[i - 1], x[i - 1], a[i - 1], k1, k1, speedFactor * KE, 1.0)
                    d[i] = nd; x[i] = nx; a[i] = na
                }
            }
            return arrayOf(d, x, a, c)
        }

        private fun runningSums(table: Array<DoubleArray>): Array<DoubleArray> = Array(4) { s ->
            val column = table[s]
            var sum = 0.0
            DoubleArray(column.size) { i -> sum += column[i]; sum }
        }

        /** [s] stepped by [minutes] at [curve]'s held tail rate, written into [out], as the table's tail is ([isolatedStateOnGrid]). */
        private fun held(s: DoubleArray, curve: IsolatedCurve, minutes: Double, out: DoubleArray) {
            val ke = curve.speedFactor * KE
            if (curve.kc > 0.0) propagateWithLag(s[0], s[1], s[2], s[3], curve.tailRate, ke, curve.kc, minutes, out)
            else {
                val (d, x, a) = propagate(s[0], s[1], s[2], curve.tailRate, curve.tailRate, ke, minutes)
                out[0] = d; out[1] = x; out[2] = a; out[3] = 0.0
            }
        }

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
            val k1 = rate(speedFactor, grid[0][i])
            if (kc > 0.0) {
                propagateWithLag(grid[0][i], grid[1][i], grid[2][i], grid[3][i], k1, speedFactor * KE, kc, dt, out)
            } else {
                val (d, x, a) = propagate(grid[0][i], grid[1][i], grid[2][i], k1, k1, speedFactor * KE, dt)
                out[0] = d; out[1] = x; out[2] = a; out[3] = 0.0
            }
        }

        /** Isolated (unpooled) fraction of [amount] still on board [tElapsed] minutes after a single dose. */
        fun isolatedIobFraction(tElapsed: Double, amount: Double, speedFactor: Double, kc: Double): Double {
            if (amount <= 0.0) return 0.0
            val s = isolatedState(tElapsed, amount, speedFactor, kc)
            return (s[0] + s[1] + s[2] + s[3]) / amount
        }

        /** Second stage (X) of a 2-compartment chain fed by [q0] entering at rate [r1], draining at [r2]. */
        private fun chainStage2(q0: Double, r1: Double, r2: Double, t: Double): Double {
            if (q0 == 0.0) return 0.0
            return if (abs(r2 - r1) < RATE_EPS) {
                q0 * r1 * t * exp(-r1 * t)
            } else {
                q0 * r1 * (exp(-r1 * t) - exp(-r2 * t)) / (r2 - r1)
            }
        }

        /** Third stage (A) impulse response fed by [d0] entering the chain (k1, k2, ke) at t=0. */
        private fun threeStageA(d0: Double, k1: Double, k2: Double, ke: Double, t: Double): Double {
            if (d0 == 0.0) return 0.0
            val k1k2 = abs(k2 - k1) < RATE_EPS
            val k1ke = abs(ke - k1) < RATE_EPS
            val k2ke = abs(ke - k2) < RATE_EPS

            return when {
                k1k2 && k1ke -> d0 * k1 * k1 * t * t / 2.0 * exp(-k1 * t) // all three rates equal (Erlang-3)
                k1k2         -> { // k1 == k2 != ke - the normal case, since depot and transit share one rate
                    val q = ke - k1
                    // Series near q*t -> 0: the closed form cancels catastrophically there, and the
                    // shared crowded rate crosses ke as a depot empties (0.385 /min at an empty depot
                    // against ke 0.130 at speed 1.0: equal at about 1.3e-4U of crowding mass, at any
                    // speed since both scale with it).
                    if (abs(q * t) < 1e-3) d0 * k1 * k1 * exp(-k1 * t) * t * t * (0.5 - q * t / 6.0 + q * q * t * t / 24.0)
                    else d0 * k1 * k1 * (t * exp(-k1 * t) / q - (exp(-k1 * t) - exp(-ke * t)) / (q * q))
                }

                k1ke         -> { // k1 == ke != k2
                    val d = k2 - k1
                    d0 * k1 * k2 * t * exp(-k1 * t) / d - d0 * k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d)
                }

                k2ke         -> { // k2 == ke != k1
                    val d = k2 - k1
                    d0 * k1 * k2 * (exp(-k1 * t) - exp(-k2 * t)) / (d * d) - d0 * k1 * k2 * t * exp(-k2 * t) / d
                }

                else         -> d0 * k1 * k2 * (
                    exp(-k1 * t) / ((k2 - k1) * (ke - k1)) +
                        exp(-k2 * t) / ((k1 - k2) * (ke - k2)) +
                        exp(-ke * t) / ((k1 - ke) * (k2 - ke))
                    )
            }
        }
    }
}
