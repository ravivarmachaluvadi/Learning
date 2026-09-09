# Consistent hashing — exercises

Eight rungs, easiest first. Rungs 1–3 need only the README and the
[visualization](https://claude.ai/code/artifact/4169af96-aa2e-4918-9d19-a10c74336747).
From rung 4 you are editing and running code.

Answers in **[SOLUTIONS.md](SOLUTIONS.md)** — attempt first. For rungs 7–8 the answer is
a worked discussion, not a single output.

```bash
cd ~/Documents/Learning/35-consistent-hashing
java src/Tests.java              # 22 scenario tests
java src/Experiments.java N      # experiment N (1..9)
java src/BrokenRing.java         # the three planted bugs
```

---

## Rung 1 — Explain

**1.1** In one sentence that does **not** mention rings, circles or clockwise, say what
is wrong with `hash(key) % numberOfMachines`. If your sentence contains the word "slow"
or "unbalanced", try again — both are false.

**1.2** Explain monotonicity to a colleague **without using the word**, and without
describing the algorithm. Say only what is promised.

**1.3** Virtual nodes solve two different problems. Name both. Then say which one you
would lead with in a design review, and why.

**1.4** "Every client computes ownership alone, with no lookup" is stated as a hard
requirement rather than an optimisation. Why? What would you actually have built if you
allowed yourself one lookup?

**1.5** You have already met [redis](../13-redis/)'s 16,384 hash slots. Redis Cluster does
**not** use consistent hashing. Explain how `CRC16(key) % 16384` escapes the problem in
§4, and what it costs to escape it that way.

---

## Rung 2 — Identify

**2.1** In the visualization, click any dot. From the screen alone: what is its position,
which machine owns it, which machine would own it under modulo, and where does its
clockwise walk end?

**2.2** In `src/Ring.java`, find the single expression that makes the ring a circle
rather than a line. What happens without it — and is it a crash or something worse?

**2.3** In `src/Placements.java`, find the one line that is the entire bug this lab
exists to explain. It is short.

**2.4** In the visualization, set positions to 1 and look at the load bars. Which machine
has the biggest share? Now press Undo and Reset, then set it to 1 again. Could you have
predicted the winner from the machine names? Should you be able to?

**2.5** Run `java src/Experiments.java 6`. Rendezvous hashing beats the ring on balance
*and* needs no memory *and* needs no tuning. Find the column that explains why the ring
is nonetheless what everybody ships.

---

## Rung 3 — Predict

Commit to an answer, then run the check.

**3.1** A 100-machine cache becomes 101. What percentage of keys change owner, under
modulo and under the ring? What is the smallest that percentage could possibly be?
*Check:* `java src/Experiments.java 1`.

**3.2** 8 machines, one ring position each. One machine dies. Its keys are inherited by
— how many machines? Which ones? *Check:* `java src/Experiments.java 3`.

**3.3** You **add** a machine. Can a key move from one existing machine to another
existing machine? Answer for the ring and for modulo, and give the reason rather than
the number. *Check:* `java src/Experiments.java 4`.

**3.4** The ring is healthy and the machine positions cover the whole 64-bit circle. You
change only the key hash, to a 32-bit one. Predict the distribution across 8 machines
before you look. *Check:* the test `a_poor_hash_clumps_keys_onto_a_few_machines`.

**3.5** You ask for 3 replicas from a 3-machine cluster with 200 positions each. What do
you get back? Now ask for 4. What comes back, and should that be an error?
*Check:* `replication_degrades_gracefully_when_r_exceeds_the_cluster`.

**3.6** During a membership change, half your clients know about the new machine and half
do not. What fraction of the keyspace do the two halves disagree about, under each rule?
*Check:* `java src/Experiments.java 9`.

---

## Rung 4 — Modify

For each: predict, then measure, then say **which property moved — and did correctness
change at all?**

**4.1** Experiment 2 sweeps the position count. Add runs at 2,000 and 5,000 positions.
Predict the imbalance first. Then answer the real question: why does production sit at
100–200 rather than 5,000, given that more is measurably better?

**4.2** Experiment 5 adds one machine mid-traffic. Change it to add **three at once**.
Predict what happens to the ring's dip before running. Then answer: is adding three
machines at once better or worse than adding one machine three times, and why?

**4.3** Experiment 6 compares strategies at 100 machines. Change it to 10, then to 1,000.
Which strategy's position in the ranking changes, and which column changes it? At what
cluster size would you switch your recommendation?

**4.4** In experiment 8, give one machine a weight of 20 while the others stay at 1.
Does the ring still deliver proportional shares? Now drop the position count to 5 and try
the same thing. What breaks, and why does that explain the "weights need enough
positions" warning in §10?

**4.5** In `src/Ring.java`, change the virtual position naming from `node + "#" + i` to
`node + "-" + i`. Every test still passes. Now explain, in terms of §11 F7, why this
harmless-looking change would be a production incident.

---

## Rung 5 — Implement

**5.1 `Ring.ownedFraction(node)`** — return the share of the circle a machine owns,
computed **only from the arc widths**, touching no keys. Then verify it predicts the
measured key counts from experiment 2 to within a fraction of a percent. Why is it worth
having a measure of balance that does not depend on your traffic?

**5.2 Rack awareness.** Extend `owners(key, r)` so the r machines are in r distinct
**racks**, given a `Function<String,String> rackOf`. Then handle the hard part: what do
you return when there are fewer than r racks, and why is silently returning fewer
machines worse than either alternative?

**5.3 `keysToMove(Ring before, Ring after)`** — the piece §11 F8 says is missing. Return
the ranges of the circle whose owner changed, and which machine each must be streamed
from and to. Then answer: while that data is in flight, what should a read for one of
those keys do?

---

## Rung 6 — Debug

`java src/BrokenRing.java` runs three planted bugs. For **each**, before reading the
output, answer:

1. What is the observable symptom?
2. **Which test in `src/Tests.java` catches it?** (Find it by name.)
3. Which guarantee from README §12 does it break?
4. Why would this be hard to find in production?

| bug | the change | run |
|---|---|---|
| 1 | machine positions come from its index in the list, not its name | `java src/BrokenRing.java 1` |
| 2 | the replica walk does not skip machines it already chose | `java src/BrokenRing.java 2` |
| 3 | the successor search clamps to the last position instead of wrapping | `java src/BrokenRing.java 3` |

**6.4 — the one that matters.** Turn on bug 1 (`Ring.BUG_POSITIONS_FROM_INDEX = true`)
and run the full suite. The monotonicity test — the one named after the lab's headline
guarantee — **passes**. Work out why, then answer the general question: what does it
tell you that a suite can contain a test named after the exact property a bug destroys,
and still go green?

**6.5** Bug 3 misplaces 79.6% of keys on a 2-machine cluster and 0.0% on an 8×200 one.
The existing test catches it only because it happens to use a sparse ring. Write a test
that catches it **regardless of ring density** — one that tests the property rather than
a symptom of it.

**6.6** All three planted bugs are caught by the suite. Invent a fourth that would **not**
be — a change to `Ring.java` that is genuinely wrong and that all 22 tests accept. Then
write the test that catches it. (Hint: look for a property no current test asserts, not
for a cleverer bug.)

---

## Rung 7 — Handle failure

**7.1 — Survive the miss storm anyway.** The ring shrinks the storm from ~99% of the
cache to ~1/n. But 1/n of a 2 TB cache fleet is still 20 GB of cold keys arriving at your
database in seconds. Design the rest of the answer. Consider: introducing the new machine
gradually, pre-warming it, serving from the old owner during a grace period, and rate-
limiting misses. For each, say what it costs and what it can still not protect you from.

**7.2 — Be correct with a stale ring.** §11 F4: for a window, your client's machine list
is out of date. Design a client that stays correct anyway. Cover reads and writes
separately — they need different answers — and say which of your mechanisms you would
actually build first given a fixed budget.

**7.3 — Move the data.** §11 F8: placement is not migration. A machine joins. Write the
sequence of steps that gets the data to its new owner such that **no read ever returns
"not found" for a key that exists**. Then say what happens if the new machine dies
halfway through, and what happens if a write arrives for a key mid-transfer.

---

## Rung 8 — Design

**8.1 — The placement layer.** A 200-node object store, 3 datacentres, 4 PB, replication
factor 3, objects averaging 400 KB. Design it: which strategy, how many virtual
positions, how replicas are chosen across datacentres, how the membership list
propagates, and what you alert on. Then say what changes if the node count is 20 instead
of 200.

**8.2 — The expansion.** You are asked to add 50 nodes to a 200-node cluster "this week".
Walk through what actually happens hour by hour, using §11 F8 and F3. Then propose what
you would do instead, and quantify why it is better.

**8.3 — The reply.** A colleague proposes moving to consistent hashing to fix a hot-key
problem: one product ID is 30% of all cache traffic and its machine is at 95% CPU. Write
the reply you would actually send. It must say plainly that this will not help, explain
why in one sentence, and end with something that will.

**8.4 — The migration.** You run 20 memcached servers with `hash(key) % 20` in a client
library you control. You want to move to consistent hashing **without** a cache-miss
storm at the moment of the switch. Design the migration. (The naive cutover is a 95%
miss storm — the very thing you are adopting consistent hashing to avoid.)

**8.5 — The choice.** Your company already runs etcd for other purposes. You need to
shard a new 40-node service. Argue for consistent hashing, then argue for fixed slots
plus an assignment table, then say which you would pick and what single fact would change
your mind.
