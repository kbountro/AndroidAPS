# Traffic Jam insulin model (this fork: Pool Compartment)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, insulin ID 106, built
around one idea: insulin sitting under the skin doesn't all absorb at the same fixed
speed. The more of it is sitting there at once, the slower it soaks in — the same way a
lot of fluid injected into one small patch of tissue takes longer to clear than a small
amount would. Stack a big bolus on top of your basal and correction insulin, and
everything absorbs a bit slower for a while, not just the new dose.

There's another implementation of this same idea on branch `AAPS_dev_TsunamiMods`,
described in its own copy of this file, built around one curve per dose rather than the
shared tanks described below. This file describes what actually runs on this branch.

Traffic Jam (ID 106) isn't the same curve as the plain Tsunami PD models (IDs 105/205,
"Lyumjev U100/U200 PD"). Those use a separate, older formula and fit. Traffic Jam's curve
was fit independently against the same published Lyumjev absorption data (GIR curves at
7/15/30U from its EPAR filing), using a different formula shape — rise, peak, tail, rather
than a plain peak-time estimate. Different formula, different constants, but the two end
up producing very similar-looking activity/IOB curves in practice.

## How it works

Every dose (bolus, extended bolus, temp basal delivery) is treated as insulin moving
through three stages:

1. **Depot** — sitting under the skin, not yet absorbed.
2. **Transit** — in the process of moving from the injection site into circulation.
3. **Active** — in the blood, doing its job, until it's cleared.

A dose just adds its amount straight into stage 1. From there it drains into stage 2, then
stage 3, then out. Reported IOB is whatever's left across all three stages; reported
activity is how fast stage 3 is being cleared.

Crowding only slows down **stage 1** — how fast insulin leaves the depot. The other two
stages run at fixed speeds that never change. The stage-3 clearance rate isn't a fitted
number either: it's pinned to insulin lispro's real, published blood half-life (44
minutes). Only the depot's own draining speed depends on how much insulin is currently
sitting in depots that share the same physical space.

Two things share crowding, tracked completely separately:

- **Physical**: bolus + extended bolus + actual delivered basal (temp basal rate). This is
  everything that's actually going into your body right now, and it's meant to crowd
  itself — a bolus really can slow down basal absorption sitting in the same tissue.
- **Theoretical**: your programmed profile basal, evaluated as if nothing else was
  happening. This is deliberately never touched by boluses, temp basals, or autosens — it's
  the baseline "how much basal insulin do I structurally need" number that other
  calculations (like how much basal IOB you're running above or below profile) get
  compared against. If this drifted with every bolus, that comparison would be meaningless.

Because crowding only reaches into the very first stage, and reported activity comes from
the last stage, a new dose can never touch insulin that has already moved past the depot —
activity never takes a sudden dip just because a new dose landed somewhere else in the
body.

## Absorption rates and the reference curve

The three absorption/transit/clearance rates here weren't fit fresh against lab data —
they were fit to reproduce Traffic Jam's own curve (described above) at the same 7/15/30U
reference points, as closely as a 3-stage tank system can manage: about 98.6% of that
curve's shape reproduced, not perfectly identical. Two practical consequences of that small
remaining gap:

- **The tail is a little longer.** Fixed clearance stages don't cut off as sharply as the
  reference curve does, so you may see a small nonzero IOB — usually well under 0.2U —
  lingering for a few hours longer than you might expect, e.g. overnight or fasting. Not a
  leak, just a slower final approach to zero.
- **Peak timing at high doses can run later than real life.** The reference curve itself
  was fitted to match the *whole* absorption shape (rise, peak, and tail together), not
  specifically to get the peak's timing right, and that carries through here. Small at low
  doses, growing at high ones — roughly accurate under ~15U. See the note in
  `TsunamiIobEngineImpl.PdModel` for the numbers.

## If something looks off

Give a bolus, then a second one a few minutes later, and watch the activity graph right at
the second dose — it should stay smooth, no visible kink. If total IOB looks unexpectedly
high under real, sustained dosing (lots of small boluses and temp-basal changes close
together, not just one or two isolated doses), that's worth checking rather than assuming
it's fine — it would mean the depot crowding is holding on to more mass than it should.
