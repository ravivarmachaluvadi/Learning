# Exercises — linearizability

Eight rungs. Rungs 1–3 need nothing but the doc and the visualization. Rungs
4–7 need the code. Rung 8 needs a whiteboard.

Answers in [SOLUTIONS.md](SOLUTIONS.md). Attempt first — especially the
predictions, where being wrong is the point.

Shorthand used throughout: `C1 put(1) [0,4]` means client 1 sent `put(1)` at
time 0 and was told it succeeded at time 4.

---

## Rung 1 — Explain

**1.1** A colleague says: "Our database is consistent — all the replicas end up
with the same data." In three sentences, explain what that sentence does not
promise, using Meera and Raghu.

**1.2** Explain, with no notation and no jargon, what is bought by insisting
that every operation "took effect at one instant inside its own request". Why
is that stronger than "reads return recent data"?

**1.3** One sentence each, separating linearizability from:
sequential consistency · serializability · strict serializability ·
eventual consistency · read-your-own-writes.

**1.4** Why is linearizability defined on what clients *observed* rather than on
what the system *did*? Give one practical consequence of that choice.

---

## Rung 2 — Identify

**2.1** For this history:

```
C1  [--------- put(1) ---------]                            [0, 20]
C2      [-- get()→0 --]                                     [4, 10]
C3                        [-- get()→1 --]                   [14, 22]
C4                                     [-- get()→0 --]      [26, 34]
```

- Which pairs of operations are concurrent?
- Which pairs have a real-time edge (one finished before the other started)?
- Where must `put(1)`'s linearization point be, if C3's answer is to be
  explained?
- Given that, is C4's answer explainable?

**2.2** Open the visualization and load situation **D**. Identify: which bar is
the write, which read was answered first, and which single fact about the
timing makes the pair impossible. Now drag one bar to make the verdict flip,
and say which bar you moved and why that works.

**2.3** In [`src/Checker.java`](src/Checker.java), point at:
- the line that enforces the real-time rule,
- the line that enforces the sequential specification,
- the line that lets a timed-out operation be treated as if it never happened.

**2.4** In [`src/Sim.java`](src/Sim.java), find the single line that turns the
broken quorum design into the correct one.

---

## Rung 3 — Predict

For each, write your answer down *before* running anything.

**3.1** Classify each history as **linearizable**, **sequentially consistent but
not linearizable**, or **neither**:

```
(a)  C1 put(1) [0,20]     C2 get()→0 [4,10]     C3 get()→1 [12,18]
(b)  C1 put(1) [0,30]     C2 get()→1 [4,10]     C2 get()→0 [12,18]
(c)  C1 put(1) [0,30]     C2 get()→1 [4,10]     C3 get()→0 [12,18]
(d)  C1 put(1) [0,4]      C1 put(2) [6,10]      C2 get()→1 [12,16]
```

Then check yourself by adding them to `Scenarios.java` and running the checker,
or by drawing them in the visualization.

**3.2** `java src/Experiments.java jitter` runs the broken quorum design 600
times at each hiccup rate. How many violations do you expect at a **0%** hiccup
rate? Write a number, then run it. If you were wrong, write one sentence about
what that means for your team's integration tests.

**3.3** `AtomicInteger` is a linearizable register. Three virtual threads each
do 10,000 `int v = reg.get(); reg.set(v + 1);`. Predict the final value to the
nearest 5,000. Then adapt `Experiments.race` and run it.

**3.4** In the visualization, generate a history from *"Quorum: write 2 of 3,
read 2 of 3"* with hiccups set to *occasional*, and press **Run 120 & count**.
Predict the percentage first.

---

## Rung 4 — Modify

**4.1** In `Tests.java`, change `lin(...)` to use `Checker.Opts.sequential()`
instead of `Checker.Opts.linearizable()` and run the suite. Which checks fail?
For each failure, say whether the *system* got worse or the *judge* got weaker.
Is the property you lost a safety property or a liveness property?

**4.2** Predict, then measure, each of these on `QUORUM_NAIVE` (no write-back),
600 histories at 15% hiccups. Only one of them fixes it — say which and why,
*before* running:

| change | violations? |
|---|---|
| R=2, W=2, N=3 (baseline) | 36 |
| R=2, **W=3**, N=3 — writes wait for every replica | ? |
| **R=3**, W=2, N=3 — reads ask every replica | ? |
| R=3, W=3, N=5 | ? |

**4.3** In `CACHED` mode, set `cacheTtl = 1` — a cache that expires after a
single tick. Predict the violation count out of 600, then run it. What does
your answer say about "we only cache for a second"?

**4.4** Set `Opts.memo = false` and run `java src/Experiments.java checkercost`.
The verdicts are identical; the state counts differ by 330×.

- In one sentence: what is the memo table remembering, and why can remembering
  it never change a verdict?
- The memo key is `(remaining operations, notebook value)`. Argue why dropping
  the notebook value from the key would be **unsound**, and say which direction
  the error would go — false alarm, or missed violation?
- Now try to build a history where dropping it actually changes the answer.
  This lab tried across 3,700 histories, including deliberately
  fully-concurrent ones, and never triggered it. What does that tell you about
  "we tested the checker" as an argument that a checker is correct?

---

## Rung 5 — Implement

**5.1** The sequential specification is a *parameter*, not a fact. Implement a
**FIFO queue** specification in `Checker.apply`: `enqueue(v)` returns nothing,
`dequeue()` returns the oldest un-dequeued value or `0` if empty. Then write a
history where two concurrent `dequeue()` calls both return the same value and
confirm the checker rejects it. *(You will need to widen the model state beyond
one `int`; keep it small.)*

**5.2** Extend `Checker.minimize` so that, in addition to removing operations,
it reports **the smallest amount of extra overlap** that would make the history
acceptable — i.e. how far one bar's edge would have to move. Print it as
"widening C3's read by 6 ticks makes this legal". Why is that number useful to
someone reading a bug report?

**5.3** Implement a `Checker.explain(History)` that, for a non-linearizable
history, prints the specific real-time edge that kills it: "C1's read was
answered at 30, before C2's read began at 34, so C1's answer must come first —
and then C2 cannot return 0." Hint: compare the verdicts of the two checkers.

---

## Rung 6 — Debug

**6.1** `Checker.Opts.dropPending = true` is a planted bug: the checker refuses
to ever place a timed-out operation. Which test catches it? Does it produce a
**false alarm** or a **missed violation**? Which of those two would do more
damage in a tool used to audit a database, and why?

**6.2** `Sim.noTiebreak = true` is a planted bug: two writers that pick the same
timestamp are no longer ordered by writer id. Which test catches it? Describe,
in terms of what is physically stored on the three replicas, what has gone
wrong — and what a user would report.

**6.3** Plant this one yourself. Stage 10.1 described the intuitive-but-wrong
checker: walk the history in the order requests were *sent*. Implement it — in
`Checker.candidates`, replace the real-time filter with "only the remaining
operation with the earliest invocation may go next":

```java
int best = -1;
for (int i = 0; i < ops.size(); i++)
    if ((remaining & (1L << i)) != 0 && (best < 0 || ops.get(i).invoke < ops.get(best).invoke)) best = i;
out.add(best);
```

Predict which tests fail and, more importantly, in which **direction** the
checker is now wrong. Then run it.

**6.4** Here is a history the checker rejects. Two engineers disagree: one says
the storage layer is broken, the other says the *test harness* recorded the
times wrong. What single measurement would settle it?

```
C1 put(1) [0,10]     C2 get()→0 [11,20]
```

---

## Rung 7 — Handle failure

**7.1** The deposed-leader failure (stage 11, F3) is described in the doc but
**not simulated**. Add a `LEADER_DEPOSED` mode to `Sim.java`:

- until tick `T`, replica 0 is the leader and handles everything;
- from tick `T`, writes go to replica 1, but replica 0 keeps serving reads from
  its own state for `lag` ticks because it has not noticed;
- add a test asserting the checker convicts it.

Then add a **lease**: replica 0 refuses to serve reads after tick `T` (it lets
the lease expire rather than assuming it still leads), and show the violations
go to zero. What did the lease cost you?

**7.2** The reader's write-back costs a round trip on **every read**, including
the overwhelming majority where nothing is in flight. Propose a cheaper scheme
that is still correct, and say exactly what it assumes. Then say what happens
to correctness when that assumption is violated.

**7.3** A client sends `put(1)` and times out. It retries. Using the *pending
operation* rules, explain why the retry is safe for a `put` and unsafe for
`increment`, and what the server must add to make the second one safe too.

---

## Rung 8 — Design

**8.1 — Payments, three regions.** You run a ledger. Accounts are sharded by
account id. Three regions: Mumbai, Frankfurt, Virginia, ~110 ms apart. Traffic
is 95% reads.

For each read path below, decide whether it must be linearizable, and justify
the cost:

- the balance shown on the customer's dashboard,
- the balance check inside an authorisation decision,
- the transaction list on the statements page,
- a fraud model's feature lookup,
- the idempotency-key check that decides whether a payment has already been
  captured.

Then: where do you put the leader for a shard, what is the p99 for the reads
that must be linearizable, what do you monitor, and what do you tell the
product team when they ask why the dashboard is 200 ms slower in one region?

**8.2 — A lock you can actually trust.** A nightly job captures authorised
payments. It must not run twice. Given failure F6, design the mechanism. State
what you assume about clocks, what happens when a job pauses for 90 seconds
mid-run, and where the guarantee is actually enforced.

**8.3 — The read that must not be linearizable.** Name a read in a payments
system where insisting on linearizability would be *actively wrong* — not merely
expensive. Explain what you would break by making it strongly consistent.
