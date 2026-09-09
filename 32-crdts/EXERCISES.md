# Exercises — CRDTs

Eight rungs, easiest first. Attempt each before opening
[`SOLUTIONS.md`](SOLUTIONS.md). Rungs 7 and 8 have no single right answer —
the solution is a worked discussion to compare yours against.

Everything runs with no build tool:

```bash
java src/Sim.java · java src/Tests.java · java src/Tests.java broken
java src/Experiments.java [n] · java src/YourMerge.java
```

---

## Rung 1 — Explain

**1.1** In three sentences, with no jargon at all, explain to a colleague who
has never heard the term why an app that works offline cannot just "send the
list up and keep the newest one".

**1.2** Explain the key idea of a CRDT without using the words *lattice*,
*commutative*, *associative* or *idempotent*.

**1.3** Someone says: "CRDTs solve conflicts automatically." Correct them in
one sentence. What is actually happening?

**1.4** Why is it *idempotence* specifically that makes CRDTs safe to run over
an at-least-once transport? Name the network fault each of the other two laws
kills.

---

## Rung 2 — Identify

Open [`visualization.html`](visualization.html) and switch to **Receipts**.
Press *add milk* on Amira, *add eggs* on Ben, then *Sync to everyone* on both,
then deliver only ONE of the four messages.

**2.1** Read the dashed box on each phone. Which phone holds which receipts?

**2.2** How many distinct versions does the verdict banner report, and why is
that not yet a failure?

**2.3** Now switch to **Whole list, newest wins** and do the same thing. Which
number in the dashed box decides the outcome, and which piece of information
that the OR-Set has is simply absent here?

**2.4** In `OrSet.java`, find the single line that implements
"observed-remove". Which test would fail if you changed it to tear up every dot
for that value, seen or not?

---

## Rung 3 — Predict

Commit to an answer, then run the command.

**3.1** A G-Counter reads `{amira: 3, ben: 2}`. Ben's phone is restored from a
backup taken when his row was 1, and then merges with Amira's copy. What does
the counter read? Is any data lost?

**3.2** Two replicas hold an OR-Set containing `milk` with dots
`{amira#1, ben#1}` — both added it independently. Ben removes milk. Amira does
nothing. They sync. Is milk on the list? (`java src/Tests.java`, look for the
observed-remove tests.)

**3.3** `java src/Experiments.java 2` delivers the same message ten times. A
sum-merge counter reads 11 and a max-merge reads 2. What would a *min*-merge
read, and would it still be a CRDT?

**3.4** In an MV-Register, Amira writes "Elm Rd" and Ben writes "Oak Rd"
concurrently, then Cara reads the pair and writes "Oak Rd, Flat 3". Amira's old
copy is later re-delivered. What does the register hold? (`java src/Tests.java`)

**3.5** Two edits to `rga_fuzz_three_editors_never_diverge`, run separately.

(a) Set `dropRate = 0.9` and re-run `java src/Tests.java`. Does it still pass?

(b) Put `dropRate` back to 0.9 *and* replace the `c.settle(60)` line with two
lossy rounds and no healthy ones:

```java
for (int k = 0; k < 2; k++) { c.gossipRound(); c.deliverAll(); }
if (!c.converged()) diverged++;
```

How many of the 500 sessions now "diverge"? Explain in one sentence why (a) and
(b) are not in conflict — and what word in the test's name is wrong for (b).

---

## Rung 4 — Modify

**4.1** Try to make the OR-Set **remove-wins** instead of add-wins by changing
`merge` alone — when the other side has tombstoned a value, drop it. Run
`java src/Tests.java`. Then answer the real question: *why can this not be
fixed inside `merge`?* What would a genuine remove-wins set have to store that
this one does not, and which sentence in §10 of the README told you so before
you started?

**4.2** In `GCounter.merge`, change `Math::max` to `Long::sum`. Run
`java src/Tests.java`. Exactly which checks fail, and which law do they
correspond to? Now run it with `dropRate = 0` and `duplicateRate = 0` — does it
pass? What does that tell you about testing CRDTs on a healthy network?

**4.3** Delete the replica-id tie-break from `LwwRegister.wins()` so that ties
keep `this`. Which law breaks, and what is the smallest scenario that exposes
it? (`java src/Tests.java broken` has the answer, but derive it first.)

**4.4** In the visualization, run the *One phone's clock is a minute fast*
situation. Then use **Step back**, turn Amira's clock back to accurate, and
replay. Which single number in the dashed box changed the outcome?

---

## Rung 5 — Implement

**5.1** `src/YourMerge.java` is an OR-Set with `merge()` removed. Implement it.

```bash
java src/YourMerge.java     # starts at 2/5; you are done at 5/5
```

Two hints, in escalating order — try without them first:
- There are two piles to combine. The order in which you combine them matters.
- Check 1 (commutativity) failing while 2 and 3 pass almost always means you
  subtracted one side's tombstones before you had learned the other side's.

**5.2** Add a `PNCounter.metadataCells()`-style method to `OrSet` that reports
the *bytes* it would ship, and use it to reproduce experiment 7's "50 records
per visible item" from your own code.

**5.3** Implement a **G-Counter-backed unique-visitor set** — a set that only
ever grows and reports its size — and then explain in one line why you cannot
build a *shrinking* one the same way.

---

## Rung 6 — Debug

`src/Broken.java` contains three plausible, wrong CRDTs. Run
`java src/Tests.java broken` only *after* you have answered:

**6.1** `SummingCounter` merges rows with `Long::sum`. Which of the three laws
does it break? Write the shortest sequence of events that produces a wrong
number, and say what the wrong number is.

**6.2** `LeakyOrSet.merge` subtracts only the tombstones it already had before
learning the other side's. Which law breaks? Construct two replicas where
`a.merge(b)` and `b.merge(a)` differ, and say which of the two answers is the
correct one.

**6.3** `TielessLww` resolves equal timestamps by keeping `this`. Why is this
bug almost invisible in a test suite and almost certain in production?

**6.4** A colleague proposes a fourth "bug fix": make `OrSet.remove` also
tombstone every dot *it can predict the other replicas will mint*, so removes
are never overruled. Why is this not implementable, and what would you have to
add to the system to make something like it work?

---

## Rung 7 — Handle a failure the lab does not

**7.1 — Tombstone garbage collection.** `java src/Experiments.java 7` shows a
cart with 10 visible items storing 500 records. Design a scheme that frees
tombstones safely. State precisely: what fact must be true before a tombstone
can be dropped, what mechanism establishes that fact, what happens if you drop
one too early, and what happens if one replica is permanently unreachable.
Then implement the *check* (not the transport): a method
`boolean safeToDrop(Dot d, List<VersionVector> whatEveryoneHasSeen)`.

**7.2 — A replica restored from backup.** Experiment 10 shows a *wiped* replica
recovering. Show that a replica restored to an *older non-empty* state can, with
a badly-designed type, cause permanent divergence — and explain why `OrSet` and
`GCounter` survive it. What operational rule follows for restoring CRDT
replicas from backup?

**7.3 — Sibling explosion.** An MV-Register accumulates one sibling per
concurrent write, and nothing collapses them until someone writes a value that
saw them all. Sketch what a client must do so that a write-heavy workload does
not produce a value with 10,000 siblings, and name the read-side behaviour that
makes this problem worse if you get it wrong.

---

## Rung 8 — Design

**8.1 — Offline-first shopping cart for a payments product.**
Three data centres, 80ms apart. Mobile clients that are offline for hours. The
cart holds items with quantities; the checkout takes payment and decrements
stock.

Produce: which pieces are CRDTs and which type each one is; which pieces must
**not** be CRDTs and what they use instead; where the boundary between the two
sits and what crosses it; what the user sees when the two disagree; and the
three metrics you would put on a dashboard before launch.

Be explicit about the one thing that would get you fired if you got it wrong.

**8.2 — Choosing between CRDT and OT for a document editor.**
You already built the OT side in
[`google-docs-system-design`](../71-google-docs-system-design/). Given a product
that must work offline for a week on a laptop, argue the choice in six
sentences, and name the measured cost you are accepting.

**8.3 — A CRDT you should not build.**
Someone proposes a CRDT for "seat reservations in a cinema" so the app works
offline. Write the two-paragraph reply: why the mechanism cannot do it, and
what to build instead that still gives most of the offline experience.
