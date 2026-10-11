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
slower that rate gets: a jam slows both. The active stage clears at a fixed speed that never
changes. A new dose can only ever slow down insulin still in the depot or transit. It never
affects insulin that has reached the active stage, so reported activity stays continuous.

Profile basal (the true baseline, evaluated as if nothing else was happening) is tracked independently as usual, but following the same mechanism.

## Absorption rates and the fit

An earlier version crowded only the depot, with transit and the active stage at fixed speeds
(the active stage pinned to lispro's 44-minute serum half-life). Those two fixed stages alone
put a ~95-minute floor under the mean action time of every dose, against ~70 minutes for
TsunamiMods' curve at 0.3U, so small SMBs acted too slowly however fast the depot was. And the
depot, the only stage crowding could act on, held just ~7% of a 0.3U SMB's action time, so the
crowding mass drained within minutes and a cascade of SMBs barely jammed.

The current fit crowds depot and transit together and fits the active stage's clearance
instead of pinning it (half-life 23.8 minutes). The isolated single-dose curve is fit by
nonlinear least-squares against TsunamiMods' EPAR-fitted curve at 0.2/0.5/1/2/3.5/7/15/30U.
R^2 against that curve over 0-600 minutes is 0.983 at 0.1U, 0.990-0.991 from 0.3U to 3.5U,
0.988 at 7U and 0.978 at 30U (the previous fit had 0.765 at 0.1U and 0.891 at 0.3U). Most of
each dose's mean action time now sits in the crowded stages (55% at 0.3U, 76% at 7U).

Replaying the engine with 0.5 U/h basal running, the cumulative activity added by a 0.3U SMB
reaches 90% of the dose after 148 minutes (previously 195; TsunamiMods' isolated curve: 122).
For a 12-SMB, 7.85U cascade, the IOB exceeds the sum of the SMBs' separate IOB contributions
by up to 10.8% of the cascade total (previously 2.2%). Basal shares the crowded pool, so at
higher basal rates the depot holds more insulin and an SMB on top acts more slowly: at 1 U/h,
the 0.3U SMB takes 173 minutes to reach 90%, and the cascade's excess is 8.6% (previously 2.8%).

The plots below were generated with the previous constants and have not been regenerated yet.

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
transit-to-active legs move.

At the default 1.0, this is an exact no-op: every rate is multiplied by `1.0`, which is
bit-for-bit identical to the unscaled rate, so the default behaves exactly like the fit
described above with no knob present at all.

## Real multi-dose behavior

![Real 12-dose cascade replay: Pool Compartment vs TsunamiMods vs conventional Tsunami](traffic_jam_cascade.svg)

(Numbers in this section are for the previous constants.) Replaying a real 6.85U/12-SMB sequence from an actual device log (each fork's own
crowding mechanism included — this fork's pooled-depot rate, TsunamiMods' pooled-SC
time-warp) against the plain Tsunami model as a reference: this fork reaches a peak gap of
0.654U (9.6% of the total dose delivered) around 3.3h into the sequence, versus 0.557U
(8.1%) for TsunamiMods over the same replay. Both forks report meaningfully more IOB than
plain Tsunami would during a run of stacked SMBs; this fork's gap is still the larger of
the two, though the widened refit above brought it much closer to TsunamiMods' own
(previously 13.7% before that refit). Neither gap should be closed by preemptively raising
basal — it reflects the crowding model doing its job, not an error — but it's worth
knowing which fork is the gentler transition if switching between them.
