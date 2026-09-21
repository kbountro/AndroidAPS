# Traffic Jam insulin model (this fork: original / per-dose curves)

This is the "Lyumjev U100 (Tsunami Traffic Jam)" insulin model, insulin ID 106. It's an
alternative to the normal oref insulin curves, built around one idea: insulin sitting
under the skin doesn't all absorb at the same fixed speed. The more of it is sitting there
at once, the slower it soaks in — the same way a lot of fluid injected into one small
patch of tissue takes longer to clear than a small amount would. Stack a big bolus on top
of your basal and correction insulin, and everything absorbs a bit slower for a while, not
just the new dose.

There's a second, newer implementation of this same idea on branch
`AAPS_dev_PoolCompartment`, which fixes a real side effect this version has (explained
below). This file describes what's actually running here, on this fork.

## How it works

Each dose (bolus, extended bolus, temp basal delivery, profile basal) gets its own
activity/IOB curve, shaped to match real Lyumjev absorption data (GIR curves at 7/15/30U
from its EPAR filing) — a rise, a peak, then a tail, not the usual bi-exponential shape
AAPS normally uses.

All the *actually delivered* insulin — bolus, extended bolus, and real temp basal
delivery — shares one running total: think of it as one shared patch of tissue. Every time
a new dose lands, that shared total goes up, and every active curve's absorption speed
(how spread-out its peak is) gets recalculated from the new total. Profile basal (your
programmed baseline, evaluated as if nothing else was happening) is tracked as a
completely separate running total, so boluses and temp basals never touch it — it stays a
clean number to compare actual delivery against.

## The known side effect

Each dose's curve is a fixed shape, drawn once, indexed by how much time has passed since
it was given. When the shared total grows and an already-active curve needs to slow down,
there's no clean way to change its speed mid-curve without breaking something — the curve's
whole shape is built around "time since this dose was given," and there's no way to update
its absorption speed without also deciding what to do with that elapsed-time number.

The way this is handled: elapsed time is stretched (the curve is treated as effectively
older than it really is), calculated so the reported *remaining IOB* comes out exactly
the same right before and after. That's the right thing to protect — IOB is what dosing
decisions actually use. But protecting IOB this way has a cost: reported *activity* for
that curve drops immediately, every single time, whenever the shared total grows. Not a
rare edge case — every dose that adds to the shared total does this to every other
currently-active curve. It's been checked and quantified: the size of the drop scales with
how much the total grew, and it's a real, visible kink in the activity graph, not a
rounding artifact.

IOB itself stays correct through all this. It's specifically the activity number (and
anything derived from it, like the graph curve) that visibly dips.

## Other things worth knowing

- **Peak timing at high doses can run later than real life.** The fitted curve shape
  matches the *whole* absorption curve (rise, peak, and tail together) as well as possible
  across all three calibration doses — it wasn't specifically fitted to get the peak's
  timing right. That trade-off is small at low doses and grows at high ones: roughly 2%
  early at 7U, worsening to about 25% (over 30 minutes) at 30U. This is a property of the
  calibration, not something that can be tuned away without a different underlying curve
  shape — see the note in `TsunamiIobEngineImpl.PdPkModel` for the numbers.
- **Bolus-wizard snooze doesn't actually do anything right now.** The formula meant to
  quiet SMB dosing for a while after a manual bolus has an inherited bug that makes it
  contribute nothing at any snooze setting. Confirmed fixed on the `AAPS_dev_PoolCompartment`
  fork; not yet ported here.

## If something looks off

Give a bolus, then a second one a few minutes later, and watch the activity graph right at
the second dose — you should see a small, sudden drop in every other active curve's
contribution at that exact moment. That's expected here, not a bug — it's the side effect
described above. If it looks unexpectedly large, or IOB itself (not just activity) jumps,
that's the thing worth actually reporting.
