# Traffic Jam insulin model (this fork: Pool Compartment)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, insulin ID 106, built
around one idea: insulin sitting under the skin doesn't all absorb at the same fixed
speed. The more of it is sitting there at once, the slower it soaks in — the same way a
lot of fluid injected into one small patch of tissue takes longer to clear than a small
amount would. Stack a big bolus on top of your basal and correction insulin, and
everything absorbs a bit slower for a while, not just the new dose.

There's another implementation of this idea on branch `AAPS_dev_TsunamiMods` (one curve
per dose, rather than the shared tanks below) — see its own copy of this file. Neither is
the same curve as the plain Tsunami PD models (IDs 105/205, "Lyumjev U100/U200 PD"), which
use an older, separate formula and fit. Traffic Jam's curve was fit independently against
the same published Lyumjev GIR data (7/15/30U, from its EPAR filing), using a rise-peak-
tail shape instead of a plain peak-time estimate — different formula, different constants,
but very similar-looking curves in practice.

## How it works

Every dose (bolus, extended bolus, temp basal delivery) moves through three stages:
**depot** (sitting under the skin, not yet absorbed), **transit** (moving toward
circulation), **active** (in the blood, doing its job, until cleared). A dose adds
straight into the depot, drains through transit into active, then out. Reported IOB is
whatever's left across all three stages; reported activity is how fast the active stage is
being cleared.

Crowding only slows the depot's own drain rate. The other two stages run at fixed speeds
that never change — the active stage's clearance rate isn't fitted at all, it's pinned to
insulin lispro's real, published blood half-life (44 minutes). Because crowding never
reaches past the depot, and reported activity comes from the last stage, a new dose can
only ever slow down insulin that hasn't left the depot yet. It never touches, or dips,
insulin that's already further along.

Two things share crowding, tracked completely separately:

- **Physical**: bolus + extended bolus + actual delivered basal (temp basal rate) — every
  actually-administered unit, meant to crowd itself, since a bolus really can slow down
  basal absorption sitting in the same tissue.
- **Theoretical**: your programmed profile basal, evaluated as if nothing else was
  happening. Deliberately never touched by boluses, temp basals, or autosens — it's the
  baseline "how much basal insulin do I structurally need" figure that other calculations
  (like how much basal IOB you're running above or below profile) get compared against.

## Absorption rates and the reference curve

The three absorption/transit/clearance rates weren't fit fresh from lab data — they were
fit to reproduce Traffic Jam's own curve at those same reference doses, as closely as a
3-stage tank system can manage: about 98.6% of that curve's shape reproduced. Two
consequences of the small remaining gap:

- **The tail is a little longer.** Fixed clearance stages don't cut off as sharply as the
  reference curve does, so a small nonzero IOB — usually well under 0.2U — can linger a few
  hours longer than you might expect, e.g. overnight or fasting. Not a leak, just a slower
  final approach to zero.
- **Peak timing at high doses can run later than real life.** The reference curve was
  fitted to match the whole absorption shape (rise, peak, tail together), not specifically
  to get the peak's timing right, and that trade-off carries through here — small under
  ~15U, growing above it. See `TsunamiIobEngineImpl.PdModel` for the numbers.
