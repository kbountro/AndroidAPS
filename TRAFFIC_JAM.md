# Traffic Jam insulin model (this fork: Pool Compartment)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, insulin ID 106, built around one idea: insulin sitting under the skin doesn't all absorb at the same fixed speed. The more of it is, the slower it is absorbed. A series of SMBs slows down the absorption of all previous unabsorbed doses, not just the new dose.

There's another implementation of this idea on branch `AAPS_dev_TsunamiMods` (one curve
per dose, rather than the shared tanks below) — see its own copy of this file. The present method tackles the problem of activity curve's discontinuity.

Traffic Jam is inspired by piecycle's Tsunami PD model (https://github.com/piecycle/tsunami), which adapts the absorption rate based solely on the individual dose. This branch started as an expansion of that model, but whilst on it, the target PD model itself was changed. That is, the individual activity curve (in the absence of previous doses) isn't the same curve as the plain Tsunami PD models. Traffic Jam's curve was fit independently against the same published Lyumjev absorption data, using a different formula shape. Different formula, different constants — but the two end up producing very similar-looking activity/IOB curves in practice, and that alone should not make much of a difference.

## How it works

Every dose (bolus, extended bolus, temp basal delivery) moves through three stages:
**depot** (sitting under the skin, not yet absorbed), **transit** (moving toward
circulation), **active** (in the blood, doing its job, until cleared). A dose adds
straight into the depot, drains through transit into active, then out. Reported IOB is
whatever's left across all three stages; reported activity is how fast the active stage is
being cleared. 

Insulin accumulation in the depot stage only slows the depot's drain rate. The other two stages run at fixed speeds
that never change. The active stage's clearance rate isn't fitted at all, it's pinned to
insulin lispro's real, published half-life (44 minutes). A new dose can
only ever slow down insulin that hasn't left the depot yet. It never affects
insulin that's either in the transit or the active stages.

Profile basal (the true baseline, evaluated as if nothing else was happening) is tracked independently as usual, but following the same mechanism.

## Absorption rates and the fit

The three absorption/transit/clearance rates were fit directly against the same published
Lyumjev EPAR clamp data (7/15/30U), by nonlinear least-squares — not against an intermediate
curve. Combined across all three doses this reaches R^2 ~= 0.956 (0.982/0.973/0.940 at
7U/15U/30U respectively): closer at the smaller, more common doses, looser at 30U.


![Pool Compartment vs conventional Tsunami vs EPAR clamp data](traffic_jam_comparison.svg)

Single isolated doses, nothing else in the system: 2U (extrapolated, no EPAR reference)
and 7U/15U/30U at the actual EPAR calibration points.
