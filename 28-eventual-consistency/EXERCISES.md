# Exercises — eventual consistency

Pencil-and-paper and visualization only. Attempt each rung before opening
`SOLUTIONS.md`. The visualization is `visualization.html`; scenario numbers refer
to its predict-then-run cards.

---

## Rung 1 — Explain (no notation)

**1.1** State the eventual-consistency promise in your own words, then name its two
clauses and say which one is about *time* and which is about *agreement*.

**1.2** A product manager says: "QA ran the app for a week and never saw a stale
read, so the store is consistent." Explain, in words a PM will accept, what that
week of green actually proved and what it cannot prove.

**1.3** The visualization's EC judge has no red light while writes flow, yet the
Monotonic-reads judge can turn red instantly. Explain the difference in one
paragraph without using the words "safety" or "liveness" — then say which word
names which judge.

## Rung 2 — Identify (in the visualization)

**2.1** Reset the page. Point at (name the panel): where the *delivery clause's*
work is visible, where the *decision clause's* work is visible, and where the
*measured-but-never-promised* number lives.

**2.2** Run scenario 3 to the end. Click the chip for "market moved to Sunday" in
any board's **Holds** list. Its inspector shows a "had seen" list. What in that
list proves the LWW overwrite was **not** a knowing (causal) replacement — and
therefore counts as a lost update?

**2.3** In scenario 4's end state, each board's **Holds** row is identical and the
**Displays** rows differ. Which clause of the engineering model does that pair of
rows witness against?

## Rung 3 — Predict (commit before checking)

For each setup, predict every judge's state, then reproduce it in the
visualization and check.

**3.1** Flood R3. Ben (home R3, sticky) reads twice, ten seconds apart, while Asha
posts twice on R1. Which judges object?

**3.2** Asha (home R1) posts a notice, then sets routing to *random* and reads
three times. What is the worst sequence of judge outcomes possible, and what is
the best? What single change makes the worst impossible?

**3.3** Cadence 6, forwarding ON, no flood, no skew. Asha posts at t=1. What is
the largest number of seconds R3 can remain behind, and which two mechanisms race
to close the gap?

## Rung 4 — Modify (what breaks, and which property is lost)

**4.1** Turn forwarding OFF and cadence to *never*, then post and read normally.
Nothing turns red. Which clause is now false, why does no judge notice, and what —
in a production system — is the *only* kind of signal that can notice it?

**4.2** Set ALL three skew sliders to +60 (every clock equally fast). Re-run
scenario 3's story by hand (Ben posts on R2, Asha posts 15 s later on R1, settle).
Does the genuinely-newer write still lose? What does this tell you about
*absolute* vs *relative* clock error under LWW?

**4.3** Suppose sync rounds only copied in one direction (R1 → others, never back).
Which clause breaks, for which writes, and what real-world misconfiguration does
this correspond to?

## Rung 5 — Trace by hand

LWW resolver. Skews: R1 +0, R2 −30, R3 +0. Cadence: sync round at t=10 and t=20,
all pairs. Forwarding OFF. All boards start with `u0 "Saturday" ts=0`.

```
t=2   Asha posts "Sunday"    at R1
t=4   Ben  posts "cancelled" at R2
t=6   Ben  reads R2
t=12  Ben  reads R3
t=20  (after the sync) settle
```

Write down: each write's stamped ts; what each read returns; each board's set and
displayed value after the t=10 round; the settled value; and the Lost-updates
judge's ruling with its reason. Then reproduce in the visualization (set skew
R2=−30, cadence off, use **Sync now** at the right moments) and compare.

## Rung 6 — Spot the flaw

A team describes their hand-rolled replicated key-value store:

> "Each replica keeps, per key, only the current value and its timestamp.
> Anti-entropy exchanges current values; the receiver keeps whichever timestamp
> is larger. Deletes are handled by removing the row — no need to waste space on
> dead keys. It's eventually consistent: same inputs, same max, deterministic."

There is a planted flaw. Construct a concrete run (replicas, timestamps, one
delete, one sync) where a key the user deleted **comes back on every replica**,
name the missing mechanism, and say which prior lab's term this is. Then answer:
is the resulting system still *eventually consistent* by the definition — and why
is that answer itself the most instructive part?

## Rung 7 — Handle failure (design on paper)

**7.1** The contract offers nothing to alarm on (rung 4.1). Design the monitoring
page for a Dynamo-style store so that every failure mode F1–F6 from the README has
a signal that fires *before a customer does*. For each alarm say what it measures,
why a simple "compare replicas, alert on divergence" check misses the failure it
covers, and which alarm is the falsifying query for "our store is healthy".

**7.2** Extend the noticeboard design so deletes are safe: what must a delete
store, how long must it live, what rule ties its lifetime to the repair schedule,
and what happens to a board that was cut off longer than that lifetime?

## Rung 8 — Design (production scale)

Your product runs in 3 regions, 80 ms apart. Four flows: (a) profile-photo
display, (b) shopping cart (add/remove across devices), (c) password change with
"log out everywhere", (d) account-balance display next to a "withdraw" button.

For each flow choose a contract from the ladder (EC / EC + session guarantees /
bounded staleness / causal+ / SEC / strong), a resolver where one applies, and the
read/write path. State which flows must never make a *decision* from an EC read
and what the write path needs instead; give the staleness SLO you would sign for
(a) and how you would measure it honestly; and name the one flow where choosing
strong consistency everywhere would be the actual design error.
