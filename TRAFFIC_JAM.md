# Traffic Jam insulin model (this fork: Pool Compartment)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, insulin ID 106. It's an
alternative to the normal oref insulin curves, built around one idea: insulin sitting
under the skin doesn't all absorb at the same fixed speed. The more of it is sitting there
at once, the slower it soaks in — the same way a lot of fluid injected into one small
patch of tissue takes longer to clear than a small amount would. Stack a big bolus on top
of your basal and correction insulin, and everything absorbs a bit slower for a while,
not just the new dose.

This fork is the second implementation of that idea. The first one (branch
`AAPS_dev_TsunamiMods`) is described in its own copy of this file — read that if you want
the history. Short version of why this one exists: the first version had a real, measurable
side effect (a brief dip in reported activity every time a new dose crowded the depot,
explained there), and this rewrite removes it by changing how the crowding is tracked
internally, not by changing what the model is trying to represent.

One more thing worth knowing up front: Traffic Jam (ID 106) isn't the same curve as the
plain Tsunami PD models (IDs 105/205, "Lyumjev U100/U200 PD"). Those use an older, separate
formula and fit. Traffic Jam's curve was fit independently against the same published
Lyumjev absorption data, using a different formula shape (rise, peak, tail, rather than a
plain peak-time estimate). Different formula, different constants — but the two end up
producing very similar-looking activity/IOB curves in practice. Worth reporting, not worth
worrying about.

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
the last stage, a new dose can never touch insulin that has already moved past the depot.
That's the whole fix compared to the first version: there's no need to reach back and
"re-age" anything that's already in flight, so there's nothing left to cause a jump.

## What's different from the original Traffic Jam curve, in practice

The three absorption/transit/clearance rates in this fork weren't fit fresh against lab
data — they were fit to reproduce the *other* fork's own Traffic Jam curve (the rise-peak-
tail one described above, at the same 7/15/30U reference points), as closely as a 3-stage
tank system can manage: about 98.6% of that curve's shape reproduced, not perfectly
identical. Two practical consequences of that small remaining gap:

- **The tail is a little longer.** Real, and by design (fixed clearance stages don't cut
  off sharply the way the original curve did), you may see a small nonzero IOB — usually
  well under 0.2U — lingering for a few hours longer than the original model would show,
  e.g. overnight or fasting. Not a leak, just a slower final approach to zero.
- **Peak timing at high doses can run later than real life**, same caveat the original
  model already had. This comes from how the underlying curve was fitted (matched across
  the whole shape, not specifically to peak time) — worse at high doses, roughly accurate
  under ~15U. Documented in code (`TsunamiIobEngineImpl.PdModel`) if you want the numbers.

## Known issues fixed since the rewrite

- Crowding mass briefly counted insulin that had already left the depot (stage 2 as well as
  stage 1), which let it drag absorption down further the longer a session ran under
  frequent dosing. Fixed — crowding now only counts what's still actually in the depot.
- The programmed-basal baseline briefly picked up autosens' influence through a shared
  crowding number. Fixed — it now only ever moves with the profile rate itself.
- Bolus-wizard snooze (the thing that's supposed to quiet SMB dosing for a while after a
  manual bolus) was structurally never contributing anything, at any snooze setting — an
  old bug inherited from the original engine, not something this rewrite introduced. Fixed
  here; not yet ported to the other fork.

## If something looks off

Give a bolus, then a second one a few minutes later, and watch the activity graph right at
the second dose — it should be smooth, no visible kink. If total IOB looks unexpectedly
high compared to the other fork under real, sustained dosing (lots of small boluses/temp
basal changes close together, not just one or two isolated doses), that's worth reporting —
it's exactly the kind of thing that's already bitten this model once.
