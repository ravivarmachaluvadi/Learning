# Exercises — hybrid logical clocks

Climb in order. Answers in [`SOLUTIONS.md`](SOLUTIONS.md) — attempt first, in
writing, before opening it.

## Rung 1 — Explain

1.1 A colleague says "we stamp everything with `System.currentTimeMillis()`
and NTP keeps us within 50ms — good enough." Explain, without jargon, the two
distinct ways this goes wrong (one needs skew, one doesn't), and what each
does to a support engineer reading the logs.

1.2 The key idea in one breath: what may `l` contain, what may it never
contain, and why does that single restriction produce the drift bound?

## Rung 2 — Identify

Run `java src/Scenes.java 3` (HLC event by event).

2.1 Ben's wall clock reads 8, yet his receive event is stamped 16.2. Which of
the three inputs to the receive rule won, and where did the value 16
physically come from?

2.2 In the same scene, find the moment `c` returns to 0. What exactly
happened to permit it?

2.3 In experiment 4's table, why does the measured drift equal spread − 1
rather than spread exactly? (Look at how `pt` and the offsets are pinned in
`Fuzz.build`.)

## Rung 3 — Predict

Write your answer down BEFORE running the command.

3.1 Node clock at `(50, 2)`; a message arrives stamped `(50, 7)`; wall reads
50. Compute the new stamp by hand, then check with case 4 of
`java src/YourClock.java` (or the rule in `Clocks.java`).

3.2 Experiment 3 doubles run length from 600 to 1200 ticks. Predict roughly
what NAIVE's maxDrift does and what HLC's does, then run
`java src/Experiments.java 3`. What single syntactic difference explains the
two columns?

3.3 In the token experiment, why is it 86/86 stale — every single read —
rather than "some"? What about the setup makes staleness certain instead of
probabilistic, and what real deployment does that setup correspond to?

## Rung 4 — Modify

4.1 In `Clocks.java`, change the HLC local rule to `l = max(l, pt); c++;`
(increment always, never reset). Run `java src/Tests.java`. Which tests fail?
Which practical resource is being silently consumed, and how long would it
last at 1M causally-chained events/sec with 16 counter bits?

4.2 Change `Fuzz.build` so offsets are NOT pinned to the extremes (delete the
two pinning lines). Which measured numbers get *weaker* (not wrong), and what
lesson from two earlier labs does that echo?

4.3 Make messages ALSO carry the sender's `pt` and absorb it into `l`. Is
anything gained? Is anything broken? (Think before you code — the answer is
short.)

## Rung 5 — Implement

5.1 `java src/YourClock.java` — implement the receive rule until 6/6. The six
cases cover all four branches; the hints name the two ties people mishandle.

## Rung 6 — Debug

`java src/Tests.java broken` runs four planted bugs. For each, before reading
the printed line: which of the lab's four guarantees (order / bound /
cut-consistency / packing budget) does it break, and which experiment or test
number would catch it in CI? One of the four produces NO inversions and NO
drift — argue why it is still a production incident.

## Rung 7 — Handle failure

7.1 A read replica rejoins after a 3-day network partition, its wall clock
now 40s slow (NTP daemon died during the partition). Using only mechanisms
from this lab, describe what happens to (a) its stamps' correctness, (b) its
stamps' drift, (c) queries "as of now" served locally by it — and what a
production system should do at rejoin time before serving anything.

7.2 Design the abuse case: a malicious client sends a fabricated causality
token of `(Long.MAX_VALUE/2, 0)` to an unsigned-token deployment. Trace the
damage through the receive rule, node by node. Which experiment is this, at
scale? What are the two independent defenses production systems use?

## Rung 8 — Design

A 3-region (EU/US/APAC, ~80ms apart) multi-tenant SaaS keeps an append-only
audit log per tenant, writable in every region, with three requirements:
regulators replay any tenant's log in an order consistent with causality;
support engineers query "the tenant's state as of <wall time>"; and a tenant's
own dashboard must always see its just-written entries ("did my change
land?"). NTP holds ±25ms normally, ±2s during incidents.

Design the timestamping: which clock, carried where, what the comparison and
conflict story is for concurrent entries, how each of the three requirements
maps to a lab mechanism, what you monitor, and — explicitly — which
requirement CANNOT be met by clocks alone and what you add for it. State what
breaks at ±2s and what merely degrades.
