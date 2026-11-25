package app.aaps.plugins.insulin.tsunami

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sinh

/**
 * The Traffic Jam chain, shared by TsunamiIobEngineImpl and the single-dose preview in InsulinOrefBasePlugin:
 *
 *   D -(k1)-> X -(K_PLASMA)-> A -(K_EFFECT)-> C -(kc)-> out
 *
 * D is insulin under the skin at the infusion site (the depot), X insulin in the blood (serum insulin is X over the
 * volume it spreads in), A insulin effect still to come, reported as activity = K_EFFECT * A, and C, with a CGM lag set
 * (DoubleKey.InsulinTrafficJamCgmLag, kc = 1 / lag), the effect the CGM has yet to show: activity is then kc * C. IOB is
 * D + X + A + C. Only the depot crowds: k1 = absorption speed * K1_B0 * D^K1_B1 ([rate]), which the callers
 * re-evaluate at every event from the live depot mass. The absorption speed is the speed setting
 * (DoubleKey.InsulinTrafficJamSpeedMultiplier) times the site's age factor ([siteFactor]): both are about the site.
 * Plasma and effect are the body's, and no setting scales them, so a dose's activity never jumps: a new dose only adds
 * to D.
 *
 * Absorption is fitted to the EMA's Lyumjev assessment report (EMA/86760/2020) pump study ITSC alone: serum insulin
 * lispro (digitized, figures 17 and 18) after a breakfast bolus on a MiniMed 640G with the basal running, on day 1 and
 * day 3 of one 3-day infusion set, single wave (1.5 U/min) and dual wave (half at 1.5 U/min, half over 3 hours). The
 * pump delivery is replayed as given: the bolus in 0.05 U pulses a minute at a time, the basal and the square wave a
 * dose a minute, all of it in one depot (dose interaction 1), serum taken as proportional to X with one scale for all
 * four curves. Fitted: K1_B0 0.11391363 /min (a day-3 site with 1 U in the depot, half-life 6.1 min), K1_B1 -0.32231826,
 * the plasma half-life 51.5 min, and day 1 at 0.47 times day 3's speed; R^2 0.993 / 0.986 (day 1, single / dual wave),
 * 0.983 / 0.972 (day 3). Held at the published 44 min instead, the plasma stage fits day 3 worse (0.974 / 0.970). The
 * report does not give the bolus size; with 10 U assumed (6 / 15 U: K1_B1 -0.36 / -0.28, day 1 0.49 / 0.44, plasma
 * 51-52 min).
 *
 * The effect stage is the delay from insulin in the blood to its effect on blood glucose: the same for every insulin
 * lispro product and every route once the insulin is in the blood. It is fitted from measured serum insulin lispro to
 * measured plasma glucose, with no glucose clamp, after the correction boluses of Leohr et al. (Diabetes Obes Metab
 * 2024;26:215): 0.2 U/kg of Lyumjev or Humalog injected in adults with type 1 diabetes on pumps once plasma glucose had
 * risen to 240 mg/dL during a basal suspension, glucose every 5 minutes, serum every 5 minutes for the first hour. The
 * glucose rise under way at the bolus continues at a constant rate and the insulin, one first-order stage from the
 * measured serum, bends it down, each arm with its own start, rise and scale: one half-life shared by both insulins
 * fits best at 2.5 min (about 1.5-3.5; Lyumjev alone 1 min, Humalog alone 3 min; fitted only from 20 or 30 minutes
 * after the bolus, 4-8 min). Glucose turned down 10 minutes after the Lyumjev injection and 15-20 minutes after the
 * Humalog one, too soon for a stage of 20 minutes or more. EFFECT_HALF_LIFE_MINUTES is that. Glucose clamps come out
 * slower: fitted from measured serum to the clamp's glucose infusion rate, 23.5-30 min for lispro in type 1 diabetes
 * (ITRR, Linnebjerg et al., Clin Pharmacokinet 2020; PDY12704, FDA review of Admelog), 23.5-30.5 min for aspart
 * (EMA report on Fiasp, injection and pump bolus), 27.5-29 min in type 2 diabetes (ITRU, Leohr et al., Clin
 * Pharmacokinet 2020) and 39 min in healthy subjects (IOPY, EMA report on Humalog 200 U/mL); Lilly's population PK/PD
 * model puts it at 2.1 /h, 19.8 min (FDA review of Lyumjev's pediatric and pump use, 2022). The loop predicts free
 * glucose, not a clamp's infusion rate.
 *
 * The site: a new infusion site absorbs at the start speed (DoubleKey.InsulinTrafficJamSiteStartSpeed, default 0.47, the
 * pump study's day 1 against its day 3), rising linearly to full speed over the site ramp ([siteRampMs]:
 * DoubleKey.InsulinTrafficJamSiteRamp, default 48 hours, day 1 to day 3).
 *
 * A 0.5 U SMB on a day-3 site at speed 1, given on a 1 U/h basal, no CGM lag: share of it still on board after 30 min
 * 0.78, 1 h 0.52, 2 h 0.23, 3 h 0.10, activity peak at 24 min; on a new site (start speed 0.47) 0.86, 0.61, 0.28, 0.12,
 * peak at 40 min. A 10 U bolus on a day-3 site: 0.60 after 1 h, 0.27 after 2 h, 0.12 after 3 h, peak at 40 min.
 *
 * [propagate] advances the chain exactly for constant rates, any of them coinciding: k1 varies continuously and crosses
 * the others. Each term is a divided difference of exp(-x) at the scaled rates: two rates closer than PAIR_CLOSE by
 * exp(-c) sinh(h) / h, three or four within SPREAD by a short series, and otherwise by splitting on the extreme rates.
 * Against a 60-digit matrix exponential, from 0.0001 to 600 min and rates coinciding to 1e-13: relative error at most
 * 5e-12 (TrafficJamChainTest checks it to 1e-10). It costs 3 exp() calls, 4 with the CGM lag, and some arithmetic.
 */
internal object TrafficJamChain {

    private const val LN2 = 0.6931471805599453

    /** Depot rate with 1 U in the depot on a settled (day-3) pump site at speed 1, 1/min. */
    const val K1_B0 = 0.11391363

    /** Crowding exponent: the depot rate is K1_B0 * D^K1_B1, D the depot mass in U. */
    const val K1_B1 = -0.32231826

    const val PLASMA_HALF_LIFE_MINUTES = 51.5
    const val EFFECT_HALF_LIFE_MINUTES = 2.5

    /** X's exit rate (insulin leaving the blood), 1/min. */
    const val K_PLASMA = LN2 / PLASMA_HALF_LIFE_MINUTES

    /** A's exit rate: activity is K_EFFECT * A (without a CGM lag), 1/min. */
    const val K_EFFECT = LN2 / EFFECT_HALF_LIFE_MINUTES

    /** Never feed a literal zero into the negative-exponent power law. */
    private const val MASS_FLOOR = 1e-6

    /** By default a new site reaches full speed this long after the change: the pump study's day 1 to its day 3. */
    const val SITE_RAMP_HOURS = 48.0

    /** The longest site ramp the setting allows, a week: a site change this long before a window still matters. */
    const val MAX_SITE_RAMP_MS = 168L * 60 * 60 * 1000

    /** Two scaled rates closer than this: their divided difference by exp(-c) sinh(h) / h instead. */
    private const val PAIR_CLOSE = 0.01

    /** Three or four scaled rates within this: their divided difference by its series around their middle. */
    private const val SPREAD = 0.05

    /** Terms kept of that series: within SPREAD, the first one left out is below 1e-20 of the sum. */
    private const val SERIES_TERMS = 9

    private val INV_FACTORIAL = DoubleArray(SERIES_TERMS + 3).also {
        var f = 1.0
        for (k in it.indices) {
            if (k > 0) f *= k
            it[k] = 1.0 / f
        }
    }

    /** The depot rate at [absorptionSpeed] (speed setting times [siteFactor]) with [depotMass] U in the depot. */
    fun rate(absorptionSpeed: Double, depotMass: Double): Double = absorptionSpeed * K1_B0 * max(depotMass, MASS_FLOOR).pow(K1_B1)

    /** The site ramp in ms for the setting's [hours] (DoubleKey.InsulinTrafficJamSiteRamp), kept within an hour and a week. */
    fun siteRampMs(hours: Double): Long = (hours.coerceIn(1.0, 168.0) * 3_600_000.0).roundToLong()

    /** A site's speed [ageMs] after its change, relative to a settled site: [startSpeed] rising linearly to 1 at [rampMs]. */
    fun siteFactor(startSpeed: Double, rampMs: Long, ageMs: Long): Double = when {
        ageMs >= rampMs -> 1.0
        ageMs <= 0L     -> startSpeed
        else            -> startSpeed + (1.0 - startSpeed) * ageMs / rampMs.toDouble()
    }

    /**
     * The chain after [dt] minutes from ([d0], [x0], [a0], [c0]), the depot at [k1] and C at [kc] (0: no CGM lag stage, C
     * left as it is), written into [out] as (D, X, A, C). [out] may be the array the inputs came from.
     */
    fun propagate(d0: Double, x0: Double, a0: Double, c0: Double, k1: Double, kc: Double, dt: Double, out: DoubleArray) {
        if (dt <= 0.0) {
            out[0] = d0; out[1] = x0; out[2] = a0; out[3] = c0
            return
        }
        val x1 = k1 * dt
        val x2 = K_PLASMA * dt
        val x3 = K_EFFECT * dt
        val e1 = exp(-x1)
        val e2 = exp(-x2)
        val e3 = exp(-x3)
        val hasD = d0 != 0.0
        val hasX = x0 != 0.0
        out[0] = d0 * e1
        out[1] = x0 * e2 + (if (hasD) d0 * x1 * dd2(x1, e1, x2, e2) else 0.0)
        out[2] = a0 * e3 + (if (hasX) x0 * x2 * dd2(x2, e2, x3, e3) else 0.0) + (if (hasD) d0 * x1 * x2 * dd3(x1, e1, x2, e2, x3, e3) else 0.0)
        if (kc > 0.0) {
            val x4 = kc * dt
            val e4 = exp(-x4)
            out[3] = c0 * e4 +
                (if (a0 != 0.0) a0 * x3 * dd2(x3, e3, x4, e4) else 0.0) +
                (if (hasX) x0 * x2 * x3 * dd3(x2, e2, x3, e3, x4, e4) else 0.0) +
                (if (hasD) d0 * x1 * x2 * x3 * dd4(x1, e1, x2, e2, x3, e3, x4, e4) else 0.0)
        } else out[3] = c0
    }

    // The divided differences of exp(-x), sign-flipped so that all are positive: dd(a) = exp(-a),
    // dd(x0..xn) = (dd(x0..xn-1) - dd(x1..xn)) / (xn - x0). The chain's transfer from stage i to stage j > i over dt is
    // the product of the scaled rates of stages i..j-1 times dd at the scaled rates of stages i..j. The e arguments are
    // exp(-x) of the nodes, computed once by the caller.

    private fun dd2(a: Double, ea: Double, b: Double, eb: Double): Double {
        val d = b - a
        if (abs(d) >= PAIR_CLOSE) return (ea - eb) / d
        // (exp(-a) - exp(-b)) / (b - a) = exp(-c) sinh(h) / h, c the middle and h half the gap: no cancellation.
        val h = 0.5 * d
        return exp(-(a + h)) * sinhc(h)
    }

    /** sinh(h) / h. */
    private fun sinhc(h: Double): Double {
        if (abs(h) >= 1e-3) return sinh(h) / h
        val h2 = h * h
        return 1.0 + h2 / 6.0 * (1.0 + h2 / 20.0 * (1.0 + h2 / 42.0))
    }

    private fun dd3(a0: Double, ea0: Double, b0: Double, eb0: Double, c0: Double, ec0: Double): Double {
        var a = a0
        var ea = ea0
        var b = b0
        var eb = eb0
        var c = c0
        var ec = ec0
        if (a > b) { val t = a; a = b; b = t; val u = ea; ea = eb; eb = u }
        if (b > c) { val t = b; b = c; c = t; val u = eb; eb = ec; ec = u }
        if (a > b) { val t = a; a = b; b = t; val u = ea; ea = eb; eb = u }
        if (c - a < SPREAD) return series(doubleArrayOf(a, b, c))
        return (dd2(a, ea, b, eb) - dd2(b, eb, c, ec)) / (c - a)
    }

    private fun dd4(a0: Double, ea0: Double, b0: Double, eb0: Double, c0: Double, ec0: Double, d0: Double, ed0: Double): Double {
        var a = a0
        var ea = ea0
        var b = b0
        var eb = eb0
        var c = c0
        var ec = ec0
        var d = d0
        var ed = ed0
        // Sorting network (a,b) (c,d) (a,c) (b,d) (b,c).
        if (a > b) { val t = a; a = b; b = t; val u = ea; ea = eb; eb = u }
        if (c > d) { val t = c; c = d; d = t; val u = ec; ec = ed; ed = u }
        if (a > c) { val t = a; a = c; c = t; val u = ea; ea = ec; ec = u }
        if (b > d) { val t = b; b = d; d = t; val u = eb; eb = ed; ed = u }
        if (b > c) { val t = b; b = c; c = t; val u = eb; eb = ec; ec = u }
        if (d - a < SPREAD) return series(doubleArrayOf(a, b, c, d))
        return (dd3(a, ea, b, eb, c, ec) - dd3(b, eb, c, ec, d, ed)) / (d - a)
    }

    /**
     * dd at [nodes] (sorted, spanning less than SPREAD) by Taylor expansion around their middle c:
     * exp(-c) * sum over m of (-1)^m h_m(y) / (m + n)!, y the nodes minus c, h_m the complete homogeneous symmetric
     * polynomial of degree m, n + 1 the number of nodes. Only for clustered rates, so rare: very short steps, or the depot
     * rate passing close to two other rates at once.
     */
    private fun series(nodes: DoubleArray): Double {
        val n = nodes.size - 1
        val c = 0.5 * (nodes[0] + nodes[n])
        val h = DoubleArray(SERIES_TERMS)
        h[0] = 1.0
        for (x in nodes) {
            val y = x - c
            for (m in 1 until SERIES_TERMS) h[m] += y * h[m - 1]
        }
        var sum = 0.0
        var sign = 1.0
        for (m in 0 until SERIES_TERMS) {
            sum += sign * h[m] * INV_FACTORIAL[m + n]
            sign = -sign
        }
        return exp(-c) * sum
    }
}
