# Jepsen — Solutions

Attempt [EXERCISES.md](EXERCISES.md) first. Rungs 7 and 8 are worked reasoning, not
single answers — if yours differs and survives the same scrutiny, yours is fine.

Every verdict quoted here was computed by the checker in
[`visualization.html`](visualization.html).

---

## Rung 1 — Explain

**1.1** Three structurally different reasons:

1. **It never attempted the thing you care about.** The claim is about behaviour under
   failure. A test on a healthy cluster exercises the happy path and concludes nothing
   about the unhappy one.
2. **The final state is where the evidence isn't.** Many violations — a stale read
   above all — are transient events that corrupt a decision and then vanish, leaving
   the final state perfect. Errors in opposite directions also cancel.
3. **"Right" is not a single value.** A correct concurrent system has many legal
   outcomes and an incorrect one can produce a legal-looking outcome. Equality against
   a predicted value only works when the answer is deterministic.

**1.2** A request either succeeded, definitely failed, or **you never found out** —
and the third case is not rare, it is what every timeout is. `:ok` = definitely
happened, has a logical effect. `:fail` = definitely did not, has *no* logical effect.
`:info` = may or may not have, now or at any later time.

What the checker may assume: an `:ok` operation **must** appear in the linearization;
a `:fail` operation **must not**; an `:info` operation may be placed or omitted freely,
and if placed, may go anywhere from its invocation to the **end of the history**.

**1.3** Red: *there exists a set of client-observable operations that no correct
implementation could have produced* — a self-contained proof, independent of hardware
or luck. Green: *this workload, these faults, this concurrency, this version, this
duration produced no violation.* The asymmetry is falsification: a counterexample
settles the question forever; its absence settles nothing.

**1.4** A unit test knows the answer in advance and compares. A checker does not know
the answer and cannot — there isn't one. It **searches** for any ordering of the
recorded operations that a correct implementation could have produced, and only
reports a violation when the search is exhausted. The ordinary approach cannot work
because the set of legal outcomes is enormous and depends on timing you do not
control.

**1.5** Because the operation may still be in flight. If the same process issued a new
operation and the old one landed afterwards, the history would contain two overlapping
operations from a supposedly single-threaded process — breaking the one modelling
assumption the whole search rests on. Jepsen burns the process number and starts a
fresh one on the same thread.

---

## Rung 2 — Identify

**2.1** p2's read `[1,3]` and p3's read `[5,7]` do not overlap — p2 was finished two
ticks before p3 asked. That forces p2's read to be linearized before p3's, which means
the value went 1 → 0 with no write of 0 available. Delete **either read** and it
becomes legal; the checker confirms that deleting p3 leaves a valid history. **Three
operations is the minimum for this violation.**

**2.2** Because an `:info` operation's interval is extended to the **end of the
history**. The request may still be queued somewhere and take effect in ten seconds.
Confining it to `[0,8]` would be claiming knowledge you do not have — and would make
the checker reject histories that are actually fine.

**2.3** Red: **`:info → it failed`**. Jepsen's is the middle one, `:info → may or may
not`. The consequence of shipping the red one: it reports a violation every time a
partition causes a timeout — which is every run — so within a week nobody believes the
test, and the real violation, when it comes, is dismissed with the rest.

**2.4** Constrained pairs are exactly those where one *completed* before the other was
*invoked*:

- `w1 [0,10]` before `r4 [14,16]` (10 < 14) ✓
- `w2 [2,12]` before `r4` (12 < 14) ✓
- `r3 [4,6]` before `r4` (6 < 14) ✓

Everything else overlaps and is free — in particular `w1`, `w2` and `r3` may be
arranged in any order. **The rule: A must precede B in the linearization if and only
if A's completion is earlier than B's invocation.** Overlapping operations are
unordered.

**2.5** `T1 → T2` on `x` is a **wr** edge, derived from what T2 *read* (x contained
T1's value 1). `T2 → T1` on `y` is derived from what T2 *did not* read — it saw
`y = []`, the version before T1 appended — so T2 must precede T1. That second kind is
an **anti-dependency** (`rw` edge). It is the one that catches the hard anomalies,
because it extracts an ordering from an absence.

---

## Rung 3 — Predict

**3.1** **VIOLATION under all three readings.** The fact that defeats "valid": only two
values can ever exist here — the initial 0 and the single write of 1. Three
non-overlapping reads saw 1, then 0, then 1, which requires the value to change twice.
`:info` grants the *write* freedom; it grants the *reads* nothing, and three `:ok`
reads are three hard facts.

**3.2** Red chip: **`:info → it succeeded`**. Verified: `fail=valid, ok=VIOLATION,
jepsen=valid`.

The mirror image of S3: there, the naive-restrictive reading manufactured a bug that
did not exist (false alarm). Here, the *correct* reading lets a bug that does exist go
unreported — because the client destroyed the evidence by recording a known success as
unknown. **S3 punishes over-claiming; S6 punishes under-claiming.** And note the trap
in S6: the red chip is "right" only by accident. Jepsen's verdict is correct *given
this transcript*. The transcript is the defect.

**3.3** list-append: **G0, a write cycle.** register: **no cycle found** — Elle reports
a clean run against a database doing exactly the same wrong thing. Both `ww` edges came
from reading `x = [1, 2]` and `y = [1, 2]`, and a register read returns only the latest
value, so the order of everything before it is unrecoverable.

**3.4** Register mode does **not** hide E5. Both its edges are anti-dependencies
derived from reading the *initial* (empty/nil) version of a key, and "I saw the version
before you wrote" is just as visible with a register.

The one-sentence difference: **E2's evidence is the order of past writes, which a
register destroys; E5's evidence is the absence of a write, which a register
preserves.**

**3.5** At k=8 the measured factor is **30.5×** (109,601 unpruned vs 3,593 pruned). At
k=9 it would be roughly 60–100× — the ratio has been doubling with each step (1.2, 2.0,
4.0, 10.1, 30.5). You cannot check it on this page because the slider is capped at 8:
unpruned growth tracks `k!`, so k=9 is ~1M recursive calls and k=12 would hang the
browser. **That cap is the lesson, not a limitation of the page.**

---

## Rung 4 — Modify

**4.1** **No longer a violation.** Verified valid; the checker's order is
`read→0 @p3 → write(1) @p1 → read→1 @p2`. All three points sit inside their own bars,
and the sequence is exactly what one register does.

The design principle: **constraints come only from non-overlapping operations.** A
history in which everything overlaps everything is nearly unfalsifiable. This is why
Jepsen keeps client concurrency low and deliberately staggers operations — a test that
maximises concurrency maximises the checker's freedom to explain the results away.

**4.2** The **`:info → may or may not` chip flips from red to green** — that is,
Jepsen's own verdict flips. Verified: S5 is `FFF` (violation under all readings), S6 is
`TFT`.

What that tells you: **a Jepsen test's power comes from the client's honesty, not from
the checker.** The checker is a fixed piece of mathematics. The variable is how well
the client library maps the database's responses onto `:ok` / `:fail` / `:info`. Get
that wrong in the "safe-looking" direction and you have a green test over a broken
database.

**4.3** **All three valid.** With `read → 0`, the read is returning the initial value,
and the write overlaps it, so the order `read→0, write(1)` works under every reading —
including `:info → it succeeded`, where the write is forced into the history but is
free to sit after the read. Under `:info → it failed` the write simply is not there.

**4.4** Erasing invocation times destroys the real-time precedence relation entirely
(no operation can be shown to have completed before another started). Measured
consequence:

| Scenario | With invocation times | Without |
|---|---|---|
| S2 stale read | VIOLATION | **valid — invisible** |
| S4 value changes twice | VIOLATION | **valid — invisible** |
| S5 lost acknowledged write | VIOLATION | **valid — invisible** |
| garbage read (value nobody wrote) | VIOLATION | VIOLATION — still caught |

**Every ordering violation becomes unjudgeable. Only value-provenance violations
survive.** The invocation timestamp carries the entire *real-time* axis of
linearizability — the "time must not run backwards" half. Without it you are left
checking little more than "did the database invent a value", which is a far weaker
claim than any vendor makes.

**4.5** If T2 read `y = [1]`, it saw both of T1's appends. The `rw` edge `T2 → T1`
disappears (T2 no longer read a version older than T1's write), leaving only
`T1 → T2` on x. **No cycle — the history is fine.** T2 would have observed T1's
transaction *atomically*: all of it rather than half of it. The anomaly in E4 was
precisely the "half".

---

## Rung 5 — Trace

**5.1 — S2.** Ops: `w1=write(1)[0,9]`, `r2=read→1 [1,3]`, `r3=read→0 [5,7]`. Value = 0.

Candidates initially: `w1` (nothing completed before t=0) and `r2` (nothing remaining
completed before t=1). **`r3` is blocked**, because `r2` completes at 3 and 3 < 5.

- **Pick `w1`** → value 1. Remaining `{r2, r3}`; `r3` still blocked by `r2`. Pick `r2`:
  wants 1, value is 1 ✓ → value 1. Remaining `{r3}`: wants 0, value is 1 ✗. **Dead.**
- **Pick `r2`** → wants 1, value is 0 ✗. **Dead immediately.**

No candidates left. **Not linearizable.**

**5.2 — S7.** Ops: `w1=write(1)[0,10]`, `w2=write(2)[2,12]`, `r3=read→1 [4,6]`,
`r4=read→2 [14,16]`. Candidates: `w1`, `w2`, `r3`. `r4` is blocked by all three.

- **Abandoned branch:** pick `w1` → value 1; pick `w2` → value 2; now `r3` wants 1 but
  value is 2 ✗. Back up.
- **Winning branch:** `w1` → value 1; `r3` wants 1 ✓; `w2` → value 2; `r4` wants 2 ✓.

**Order: `write(1) → read→1 → write(2) → read→2`. Linearizable.**

**5.3 — S6.** Ops: `w1=write(1)[0,4] :info`, `r2=read→0 [8,10] :ok`.

- **Branch A — "the write did not take effect":** history is just `r2`. Value is the
  initial 0; `r2` wants 0 ✓. **Succeeds.**
- **Branch B — "the write took effect":** its interval becomes `[0, ∞)`, so it is
  unconstrained. Place it *after* `r2`: order `read→0, write(1)` ✓. **Also succeeds.**

Both branches are needed in general because either alone can be the only one that
works — S3 is rescued by branch B, S6 by branch A (and here also B). Jepsen returns
valid if **any** branch succeeds, which is what makes it sound: it never convicts on
an assumption it was not entitled to make.

**5.4 — E3.**

- *"T1 must have committed before T2, because T2 read `x` containing the value 1, and
  only T1 appended 1 to x."*
- *"T2 must have committed before T1, because T1 read `y` containing the value 1, and
  only T2 appended 1 to y."*

Contradiction without the word cycle: **each transaction must have finished before the
other one started, which is impossible.**

**5.5 — the extra history.** Verified: **VIOLATION under all three readings.**

The reasoning that matters is the Jepsen branch where `w2 = write(2)` *did* take
effect, since that is the permissive one. Its interval becomes `[1, ∞)`, so it is
completely free. But:

- `w1` completes at 6, before `r3` is invoked at 8 → **`w1` must precede `r3`**.
- `r3` completes at 9, before `r4` is invoked at 10 → **`r3` must precede `r4`**.
- `r3` returned 2, so the last write before it is `w2`.
- `r4` returned 1, so a write of 1 must fall **between `r3` and `r4`**.

The only write of 1 is `w1`, and `w1` is pinned before `r3`. There is nothing left to
produce the 1 that `r4` saw. Violation.

**The trap:** it is tempting to think a fully-unconstrained `:info` write can rescue
anything. It cannot. Here the conviction comes from the *pinned* `:ok` write and the
*ordering of the two reads* — the indeterminate operation is irrelevant to the proof.

---

## Rung 6 — Spot the flaw

**6.1** Smallest failing history is **two operations — S3 itself**: `write(1)` recorded
`:info`, then `read → 1`. Drop the write and a client has read a value nobody wrote;
the checker reports a violation against a database that did nothing wrong.

**Direction: false positives only, never missed bugs — and you can prove that.**
"Drop all `:info` ops" is *exactly* Jepsen's `mask = 0` branch. Jepsen returns valid if
**any** branch succeeds, so dropping-everything valid ⇒ Jepsen valid. The naive
strategy is strictly more restrictive, so it can only ever convict more often, never
less.

That sounds harmless and is not: a checker that raises a false alarm on every timeout
raises one every run, and a test nobody believes is worse than no test, because it
occupies the slot where a real one would go.

**6.2** The mishandled class is a **definite, server-issued rejection delivered as an
exception** — "aborted", "constraint violation", "rejected: not the leader, nothing
was written". Those are `:fail`, not `:info`. The `catch (Exception e)` lumps them in
with genuine timeouts.

Smallest history where a real bug goes unreported (two operations):

| process | operation | result recorded | truth |
|---|---|---|---|
| p1 | `write(1)` | `:info` | server explicitly replied **aborted, no effect** |
| p2 | `read()` → 1 | `:ok` | — |

With the truth recorded as `:fail`, this is a **dirty/garbage read** — the client read
a value from an operation the database said it had rejected. That is a serious
violation. With `:info` recorded, the checker reasons "the write may have happened
after all" and returns **valid**. The bug is invisible.

The fix is unglamorous and is most of the work in a real Jepsen client: enumerate the
database's error codes and classify each one deliberately, with a comment explaining
why. `:info` is the correct answer only for genuine unknowns.

**6.3** From 2.4: **ordering constraints come only from operations that do not
overlap.** At 500 concurrent processes on one key, almost every pair of operations
overlaps, so almost no pair is constrained, so the checker has near-total freedom to
arrange them into an explanation. The extreme case: **if every operation overlaps every
other operation, any history whose values are individually producible is linearizable**
— the test can no longer fail for ordering reasons at all.

There is a second, independent cost: the search is exponential in concurrency (3.5), so
the checker will also time out. You get a weaker test that takes longer to run.

**6.4** Two independent reasons:

1. **Datatype.** An rw-register workload has, in Elle's own words, weaker inference. As
   E2 demonstrates, whole classes of anomaly (anything whose evidence is the *order of
   past writes*) are structurally invisible to it. Finding nothing with registers is
   partly a statement about the instrument, not the system. Re-run with list-append.
2. **Falsification.** Two weeks of green is the weak half of the asymmetry. It is
   consistent with: the bug needs a fault they did not inject, or two faults at once, or
   a longer run, or a client that under-claims, or a checker that timed out and was
   recorded as a pass.

The defensible sentence is: *"Over N runs with faults X, Y, Z at concurrency C against
version V, we observed no serializability violation."* Everything stronger is not
supported.

**6.5** The smallest missed failure needs **no clock error at all**: a **process
pause**. A leader `SIGSTOP`ped — or GC-paused, or descheduled on a noisy host, or
live-migrated — for 12 seconds wakes up believing its 10-second lease is still valid
and writes, while the rest of the cluster has already elected a successor. Every clock
in the fleet was within 200ms of correct the entire time. NTP has nothing to say about
a process that was not running.

Two further gaps: "synchronised within 200ms" is an assertion about the *healthy* case,
not a bound the system enforces — a failed `ntpd`, a VM snapshot restore, or a
leap-second handling bug produces unbounded skew with no alarm; and NTP corrects by
*stepping* the clock, so a node can observe time jumping backwards, which breaks any
code that assumes monotonicity. If you take a lease's expiry from the wall clock and
have no fencing at the resource, this is your outage.

---

## Rung 7 — Handle failure

**7.1 — before blaming the vendor.** In priority order:

1. **Error classification.** The most likely defect and the easiest to make. Audit
   every path that produces `:ok` and `:fail`: is each one *definitely* true? Rule out
   by re-running with the suspect operations forced to `:info` — if the violation
   survives, it did not depend on your classification.
2. **Configuration.** Are you running the consistency settings you think you are? The
   MongoDB 4.2.6 finding was partly a case of settings silently not applying. Rule out
   by asserting the effective settings from inside a session at runtime, not from the
   config file.
3. **Whose claim is it?** Check that the model you are checking is the one the vendor
   promises at these settings. A stale read at a weak read concern is not a bug.
4. **The nemesis exceeded the contract.** If you killed 3 of 5 nodes, a system promising
   minority tolerance owes you nothing. Check what the fault actually did, not what you
   meant it to do.
5. **Clock on the control node.** Your real-time ordering is only as good as that one
   clock. Confirm nothing is stepping it during the run.

Only then: minimise the history, reproduce, and file.

**7.2 — the checker that did not finish.**

Why "pass" is wrong: the checker returned **no verdict**. `valid? true` means a search
completed and found an explanation; a timeout means the search was abandoned. Recording
"unknown" as "pass" is the same error as recording a definite failure as `:info` —
throwing away the distinction between *knowing* and *not knowing*, which is the one
thing this entire methodology exists to preserve. Report it as **indeterminate** and
treat it as a broken test run.

Three changes, each with its price:

| Change | Why it helps | What you give up |
|---|---|---|
| **Shorten the run** (60s not 30min) and do many more of them | Cost is superlinear in history length; ten short histories check far faster than one long one and cover more fault timings | Bugs that need a long build-up — log growth, compaction, disk filling — become unreachable |
| **Cut client concurrency** (5 processes, not 50) | Search cost is exponential in *overlap*; also makes the history more constrained and therefore stronger (6.3) | Fewer simultaneous races; some concurrency bugs need many writers |
| **Spread over more keys** and check each key independently | Linearizability is local, so per-key checking is sound and turns one huge problem into many tiny ones | Nothing for linearizability — this one is nearly free. It is **not** available for serializability, which does not compose; there you must move to Elle |

A fourth, if the claim allows it: **switch from Knossos to Elle** with a list-append
workload. Cycle detection is polynomial; permutation search is not.

**7.3 — two claims, two tests.**

*"Acknowledged writes are never lost"* — this is a **durability/availability** claim, not
a recency one, and it does not need a linearizability checker at all. Workload: a
**grow-only set** — clients add unique elements and, at the end, one client reads the
whole set. Checker: a set checker comparing acknowledged adds against the final read.
Faults: `:kill` (does `fsync` mean what they say), partitions, and membership changes.
Counterexample: an element whose `add` returned `:ok` and which is absent from the final
read. **This is a cheap, fast, extremely productive test and it is where I would start
on any system.** Note the deliberate design: elements added with `:info` are excluded
from the "must be present" set but *permitted* to appear — the same three-outcome
discipline, applied to a set.

*"Reads never return uncommitted data"* — this is an **isolation** claim at transaction
scope. Workload: list-append transactions. Checker: **Elle**, watching for `G1a`
(aborted read) and `G1b` (intermediate read). Faults matter less: as PostgreSQL 12.3
showed, isolation bugs often appear with no faults at all. Counterexample: a read
containing a value appended by a transaction that returned `:fail`.

**7.4 — making an irreproducible finding persuasive.** Send:

1. **The history file itself** — the raw log of every invocation and completion with
   timestamps. This is the evidence; everything else is commentary.
2. **The minimised counterexample** — the smallest set of operations that cannot be
   explained, with the constraint written out as in section 11 of the README. A vendor
   can verify three operations by eye in a minute; they cannot verify 20,000.
3. **The nemesis timeline**, aligned to the anomaly: "partition started at 14.2s,
   healed at 21.7s, first bad read at 22.1s." Because the nemesis writes into the same
   history, this alignment is free — and it is usually what tells the vendor which code
   path to look at.
4. **The exact version, configuration, and effective consistency settings.**
5. **The complete test code**, so they can run it themselves.
6. **The failure rate** — "1 in 40 runs" is information, not an apology. It tells them
   how narrow the window is, which is a clue about the mechanism.
7. **Their server logs** for the window, which Jepsen collects from every node.

What makes it persuasive is that (1) and (2) together are **checkable without trusting
you or your tooling**. That is the whole reason this methodology produces fixes rather
than arguments.

---

## Rung 8 — Design

**8.1 — Spring Boot + PgBouncer + read replica.**

*The key insight to state first:* you said you trust PostgreSQL, and you should — but
**the guarantee you get is the weakest link in the path between your code and the
storage engine**, and you have deliberately installed two components in that path that
can weaken it. The bug you are hunting is almost certainly yours, not PostgreSQL's.

**Where the guarantee is likely lost:**

- **The read replica.** Replicas are asynchronous. A transaction routed to a replica
  reads a stale snapshot. Your `linearizability-vs-serializability` lab's F1 exactly:
  the result may still be *serializable* (there is an order that explains it) while
  being badly stale. If your routing layer sends reads to a replica and writes to the
  primary within one logical unit of work, you have built read skew into the
  architecture.
- **PgBouncer in transaction mode.** Server connections are handed back to the pool at
  each commit. Anything whose scope is the *session* rather than the transaction —
  `SET TRANSACTION ISOLATION LEVEL` issued once at connect, session-level advisory
  locks, prepared statements, `SET` variables — can silently apply to the wrong
  connection or be lost. A service that sets `SERIALIZABLE` at connection setup may be
  running at `READ COMMITTED`.
- **Spring's `@Transactional`.** Boundaries are a *declaration*. Reads outside the
  annotation, a `readOnly = true` that routes to the replica, or a lazily-initialised
  entity loaded after commit all put operations outside the group the database was
  asked to protect. Your `isolation-levels` lab's central point: the guarantee cannot
  protect a group it was never told about.

**Workload:** list-append over a small key space (say 8 keys), transactions of 2–4
operations mixing reads and appends, issued **through the real application stack** —
your repository layer, your `@Transactional` boundaries, your pool, your router. Not
through a raw JDBC connection. The whole point is to test the stack, not the database.

**Checker:** Elle, list-append, checking serializability — and also the strict variant,
because a stale replica read is invisible to plain serializability and is probably your
most likely real defect.

**Do you need a nemesis?** **Not at first, and this is the important answer.** PostgreSQL
12.3 and MongoDB 4.2.6 both produced anomalies *with no faults at all*. Configuration
and pooling bugs are steady-state bugs. Run clean first; if that is green, add replica
lag (the most realistic fault here), then `:kill` on the primary to exercise failover.

**Assert the effective isolation level at runtime** from inside a transaction, not from
config. That single check catches the PgBouncer class of bug on its own.

**In production, monitor:** replication lag as a *correctness* signal rather than a
performance one, with an alert threshold derived from what your reads can tolerate;
serialization-failure retry counts (a sudden drop can mean you silently stopped running
at `SERIALIZABLE`); and the fraction of transactions routed to replicas.

**8.2 — a world with no `:info`.**

Both errors from section 6 become unavoidable rather than optional, because the client
is *forced* to guess on every timeout, and the guess is wrong roughly half the time.

- **False positive:** guess `:fail` on a write that actually landed. A later read
  returns the value, the checker sees a value nobody wrote, and reports a violation
  against a healthy database. This is S3, and under partition it happens constantly.
- **False negative:** guess `:ok` on a write that never landed. The checker now *must*
  place it in the order, which is usually harmless — but the mirror case is the real
  danger: an acknowledged write recorded as `:fail` because the ack was lost. Now the
  checker cannot see that a genuinely lost write was ever promised, and the system's
  central durability claim becomes untestable. This is S6 without any option to do
  better.

**Which is worse: the false negative.** A false positive is loud — you investigate,
find your own bug, fix the client. A false negative is silent, and it converts a safety
test into a ritual. The deeper point is that `:info` is not a convenience: **without it,
the framework cannot represent the actual epistemic state of a network client**, and a
tool that cannot represent what you know cannot reason correctly about it.

**8.3 — four hours a night against a Raft key-value store.**

| Rank | Fault | Reasoning |
|---|---|---|
| 1 | **`:pause` / `hammer-time`** | Cheapest to run, closest to a real production event (a GC pause or a descheduled process), and attacks the assumption Raft leases and leader liveness rest on. **Assumption it encodes: a leader can stop executing for longer than its election timeout, and production will do this to you without being asked.** |
| 2 | **`:kill` + restart** | Directly tests durability — whether the log was actually `fsync`ed — and crash recovery, which is the least-exercised code path in most systems. NATS 2.10.22 lost entire streams to this alone. **Assumption: processes die abruptly and must recover from disk, not from memory.** |
| 3 | **`partition-majorities-ring`** | Nastier than plain halves precisely because *every node sees a majority* — it breaks the common shortcut "I can see a majority, therefore I am the only leader". **Assumption: partitions are not always clean two-way splits.** |
| … | `partition-random-halves`, `:membership`, `:packet` | Valuable, especially membership changes (the hardest part of Raft), but slower to run and to diagnose |
| Bottom | **`:clock`** | Raft's safety does not depend on wall-clock time — only its *liveness* does. Clock skew should cause elections and unavailability, not data loss. Worth running occasionally to confirm exactly that, but it should find little. If it finds a safety bug, someone put a lease or a timestamp somewhere it does not belong — which is worth knowing, just not nightly. |
| Bottom | **`:file-corruption`** | Real and serious, but slow, noisy, and usually diagnosed as "checksums missing" once rather than nightly. Run it quarterly. |

The general principle for ranking: **inject the faults production will inject anyway,
in rough order of how often production does it.** Exotic faults find exotic bugs; pauses
and crashes find the ones that will actually page you.

**8.4 — when Jepsen is the wrong instrument.**

1. **A property with no finite counterexample — liveness.** "The cluster always
   eventually elects a leader" cannot be falsified by any finite history: no matter how
   long you waited, it might have elected one a second later. Jepsen can only measure
   *availability* (how long it was down), which is a different, weaker statement. Use
   a model checker such as **TLA+** for the design-level liveness argument, and SLOs plus
   production monitoring for the operational one. Your `flp-impossibility` lab explains
   why the theoretical version of this question has no answer at all.

2. **A rare bug you need to *reproduce*, not just find.** Jepsen's non-determinism is
   the price of testing the real binary. Once a bug appears once in forty runs, Jepsen
   is a poor debugging tool — you cannot replay it. **Deterministic simulation testing**
   (FoundationDB, TigerBeetle, Antithesis) replays exactly from a seed, at the cost of
   needing the system built for it. The two are complements: Jepsen to find it from
   outside, DST to hold it still while you look at it.

*Also defensible:* single-machine data-structure correctness (use property-based testing
— faster and exhaustive-ish); security properties (a different threat model entirely);
and performance regressions (Jepsen deliberately runs at low concurrency, so its latency
numbers are not a benchmark).
