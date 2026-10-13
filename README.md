# Traffic Jam insulin model (this fork: Pool Compartment)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, built around one idea: insulin sitting under the skin doesn't all absorb at the same fixed speed. The more of it is, the slower it is absorbed. A series of SMBs slows down the absorption of all previous unabsorbed doses, not just the new dose.

There's another implementation of this idea on branch `AAPS_dev_TsunamiMods` (one curve
per dose, rather than the shared tanks below) — see its own README. The present method tackles the problem of activity curve's discontinuity.

Traffic Jam is inspired by piecycle's Tsunami PD model (https://github.com/piecycle/tsunami), which adapts the absorption rate based solely on the individual dose. This branch started as an expansion of that model, but whilst on it, the target PD model itself was changed. That is, the individual activity curve (in the absence of previous doses) isn't the same curve as the plain Tsunami PD models. Traffic Jam's curve was fit independently against the same published Lyumjev absorption data, using a different formula shape. Different formula, different constants — but the two end up producing very similar-looking activity/IOB curves in practice, and that alone should not make much of a difference.

## How it works

Every dose (bolus, extended bolus, temp basal delivery) moves through three stages:
**depot** (sitting under the skin, not yet absorbed), **transit** (moving toward
circulation), **active** (in the blood, doing its job, until cleared). A dose adds
straight into the depot, drains through transit into active, then out. Reported IOB is
whatever's left across all three stages; reported activity is how fast the active stage is
being cleared.

The depot and transit stages share one drain rate, and the more insulin sits in the depot, the
slower that rate gets: a jam slows both. The rate is re-evaluated every minute from the insulin
still in the depot, so as a dose's depot empties its absorption speeds up. Boluses, SMBs and
extended boluses crowd each other: a cascade of SMBs slows every earlier SMB still in the depot.
Delivered basal crowds only itself, so a basal rate does not slow the SMBs on top of it. The
active stage clears at a constant rate that crowding never changes (half-life 1.6 minutes,
fitted as described below). A new dose can only ever slow down insulin still in the depot or
transit. It never affects insulin that has reached the active stage, so reported activity stays
continuous.

Profile basal (the true baseline, evaluated as if nothing else was happening) is tracked independently as usual, but following the same mechanism: it crowds only itself, like delivered basal, so with no temp basal running the two evolve identically.

## Absorption rates and the fit

An earlier version crowded only the depot, with transit and the active stage at fixed speeds
(the active stage pinned to lispro's 44-minute serum half-life). Those two fixed stages alone
put a ~95-minute floor under the mean action time of every dose, against ~70 minutes for
TsunamiMods' curve at 0.3U, so small SMBs acted too slowly however fast the depot was. The next
versions crowded depot and transit together, with the rate held at its value for the dose's own
amount for the whole dose, and fitted the active stage's clearance along with the two crowding
constants. The engine, though, re-evaluates the rate from the live depot mass, basal included,
whenever basal runs. Run that way with no basal, those constants matched the EPAR activity
curves with R^2 0.654 / 0.533 / 0.449 at 7 / 15 / 30U, and on 0.5 U/h basal the activity a
0.3U SMB added (its own pool plus the basal it slowed, which the engine booked as net basal)
reached 50% / 90% of the dose after 77 / 146 minutes (as fitted: 66 / 134).

The constants are now fitted with the rate re-evaluated every minute from the live depot mass,
as the engine runs it, and with the objective TsunamiMods' Weibull constants were fitted with:
activity and IOB together, on curves truncated at 480 minutes. Every curve, target and model
alike, is cut at 480 minutes and normalized over 0-480 minutes: activity is divided by its
integral over 0-480 minutes, and IOB is 1 minus the cumulative activity divided by its value at
480 minutes (1 at 0 minutes, 0 at 480). For the model's isolated single-dose activity a(t) and
IOB fraction S(t), that is a(t) / (1 - S(480)) and (S(t) - S(480)) / (1 - S(480)). Each target
curve's squared errors are divided by its total sum of squares, so the objective is the sum over
the curves of 1 - R^2, all over 0-480 minutes. TsunamiMods' Weibull form fitted to the EPAR
curves this way gives a0 69.944 minutes, a1 0.2177, p 1.9017, within 2.0% of TsunamiMods'
70.884 / 0.2134 / 1.9002.

EPAR has no curves below 7U, so the fit also uses TsunamiMods' curves at 0.2, 0.5, 1, 2 and 3.5U
(1-minute grid) as the small-dose target, truncated and normalized the same way. Those curves
are an extrapolation of TsunamiMods' own fit to the same 7-30U EPAR data, not measurements. The
16 curves (activity and IOB at the 3 EPAR doses and the 5 TsunamiMods doses) are weighted
equally. The EPAR clamp studies infused no basal insulin, and since a dose's rate does not
depend on basal, each dose's own curve is the same on any basal rate. With basal counted in the
same crowding mass as the SMBs, no constants fit both the EPAR curves and TsunamiMods'
small-dose curves given on a basal rate: the active stage's rate runs to the end of any search
range, and on the EPAR curves the objective stays near 4.5 times the Weibull form's at 0.5 U/h
(11.2 times at 1 U/h). Hence basal crowding only itself.

Fitted: K1_B0 0.017258, K1_B1 -0.215412 (depot and transit rate 0.017258 * M^-0.215412 per
minute, M the depot mass in U), active-stage half-life 1.6 minutes. On the EPAR curves the
objective is 0.982 times that of TsunamiMods' Weibull form fitted the same way (the previous
constants, rate held per dose: 2.55). R^2 activity / IOB on the truncated curves:
EPAR 0.985 / 0.998, 0.985 / 0.998, 0.978 / 0.999 at 7 / 15 / 30U; TsunamiMods 0.998 / 1.000 at
0.1U, 0.3U and 5U, none of which is in the fit, and at each of the five fitted doses. With the
active stage this fast, the timing is set by the depot and transit outflow, and its half-life
is loosely determined: the objective stays within about 3% of its minimum from 1.0 to 2.3
minutes. With the rate rising
as the depot empties, absorption speeds up late in each dose, which gives the flatter, more
symmetric top of the EPAR curves; no chain of stages with its rates held fixed for the whole
dose can produce that shape.

Minutes until an isolated dose's cumulative activity reaches 50% / 90% of the dose (nothing
else in the system, not truncated). The current fit's times hold on any basal rate; the other
columns are each version's own figures with the rate held per dose:

| Dose | Current fit | Previous fit (rate held per dose) | Fit to raw EPAR activity alone | Depot-only version | TsunamiMods |
|------|-------------|-----------------------------------|--------------------------------|--------------------|-------------|
| 0.1U | 52 / 96     | 55 / 114                          | 63 / 158                       | 81 / 192           | 52 / 97     |
| 0.3U | 65 / 121    | 66 / 134                          | 73 / 169                       | 85 / 195           | 65 / 122    |
| 1U   | 84 / 155    | 84 / 167                          | 88 / 191                       | 93 / 204           | 84 / 158    |
| 3U   | 106 / 196   | 105 / 211                         | 108 / 222                      | 107 / 223          | 106 / 200   |
| 5U   | 118 / 219   | 118 / 238                         | 120 / 243                      | 116 / 239          | 119 / 223   |

The current fit's 90% time is 1.85-1.86 times its 50% time at these doses, against 1.88 for
TsunamiMods' curve (with the rate held per dose, at least 1.99).

Replaying the engine, a 12-SMB, 7.85U cascade's IOB exceeds the sum of the SMBs' separate IOB
contributions by up to 23.1% of the cascade total, and the cascade reaches 50% / 90% of its
total activity 46.5 / 71.1 minutes later than the sum of its SMBs replayed separately, on any
basal rate. The previous fit, as the engine ran it on 0.5 U/h basal: 10.9% and 22.5 / 22.6
minutes, with a 0.3U SMB reaching 90% after 146 minutes (171 at 1 U/h). A basal rate started
from nothing reaches 50% / 90% of its steady-state activity after 78 / 199 minutes at 0.5 U/h
and 94 / 239 minutes at 1 U/h (previous fit: 58 / 125 and 68 / 148). All of these come from a
Python replica of the engine, not from the app.

The plots below were generated with the depot-only version's constants and have not been
regenerated yet.

![Pool Compartment vs TsunamiMods vs conventional Tsunami vs EPAR clamp data](traffic_jam_comparison.svg)

Single isolated doses, nothing else in the system: 2U (extrapolated, no EPAR reference)
and 7U/15U/30U at the actual EPAR calibration points, now alongside `AAPS_dev_TsunamiMods`'
own curve so the two forks can be compared directly, not just against conventional Tsunami
and the real data.

## Per-patient absorption speed

A preference (Insulin settings → Absorption Speed, `DoubleKey.InsulinTrafficJamSpeedMultiplier`,
range 0.2-5.0, default 1.0) scales the shared depot/transit rate directly: 2.0 means
absorption — and therefore roughly peak activity timing — is twice as fast as standard;
0.5 means half as fast. It's a direct multiplier applied as-is, not a derived "effective
DIA" or other indirect framing, since this model's decay is dose-dependent (crowding) and
asymptotic, so no single duration number would actually be dose-independent or exact.

The active stage's clearance (KE) is deliberately never scaled by this knob, since it doesn't
depend on how fast insulin leaves the injection site. Only the depot-to-transit and
transit-to-active legs move. With the active stage's half-life at 1.6 minutes, those two legs set
nearly all of a dose's timing, so the knob scales nearly all of it.

At the default 1.0, this is an exact no-op: every rate is multiplied by `1.0`, which is
bit-for-bit identical to the unscaled rate, so the default behaves exactly like the fit
described above with no knob present at all.

## Real multi-dose behavior

![Real 12-dose cascade replay: Pool Compartment vs TsunamiMods vs conventional Tsunami](traffic_jam_cascade.svg)

(Numbers in this section are for the depot-only version's constants.) Replaying a real 6.85U/12-SMB sequence from an actual device log (each fork's own
crowding mechanism included — this fork's pooled-depot rate, TsunamiMods' pooled-SC
time-warp) against the plain Tsunami model as a reference: this fork reaches a peak gap of
0.654U (9.6% of the total dose delivered) around 3.3h into the sequence, versus 0.557U
(8.1%) for TsunamiMods over the same replay. Both forks report meaningfully more IOB than
plain Tsunami would during a run of stacked SMBs; this fork's gap is still the larger of
the two, though the earlier widened refit brought it much closer to TsunamiMods' own
(previously 13.7% before that refit). Neither gap should be closed by preemptively raising
basal — it reflects the crowding model doing its job, not an error — but it's worth
knowing which fork is the gentler transition if switching between them.
