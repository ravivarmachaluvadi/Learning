# CockroachDB — Solutions

Answers to [EXERCISES.md](EXERCISES.md). Numbers labelled **(simulator)** were produced by
the engine in `visualization.html` and are reproducible by setting the dials as described;
numbers labelled **(docs)** come from the sources listed at the end of the
[write-up](README.md#sources).

---

## Rung 1 — Explain

### E1.1

On one machine, two things are true for free and nobody thinks about them: everything
happens in **one order**, and every event's **timestamp comes from the same clock**, so any
two timestamps are meaningfully comparable.

Spread the same database over three cities and both facts disappear. There is no single
order any more, and — the part that causes the trouble — every write is stamped by whichever
machine handled it, using that machine's own clock. Those clocks disagree by tens or
hundreds of milliseconds, and no machine can detect its own error, because to know your
clock is fast you would need a clock you already trusted. So a write that genuinely happened
first can carry a *larger* timestamp than one that happened after it, and a reader who
filters by timestamp will get the order backwards.

CockroachDB's idea is to stop trying to know the time. Instead you **declare** how far apart
any two clocks are allowed to be — 500 ms by default — and you make the **reader** carry that
doubt: any value stamped within that window above a reader's own timestamp is treated as
"this might secretly be in my past", and the reader does its work again at a higher timestamp
rather than risk a wrong answer.

*The trap to avoid:* answering in terms of network partitions or message loss. Assume the
network is perfect and instant and every problem in this lab still happens. It is a problem
of **interpretation**, not delivery.

### E1.2

Both give you serializable transactions across machines, and both have to pay for the fact
that clocks disagree. The difference is **who pays and when**.

**Spanner charges the writer, always.** Its clocks report an interval rather than an instant,
and a writer must wait out the width of that interval before telling the client the write
succeeded. The cost is small — a few milliseconds — but it is charged on *every write*, and
it is small only because there are GPS receivers and atomic clocks in every datacentre. In
exchange, reads are lock-free, local, never retry, and the resulting order never contradicts
real time.

**CockroachDB charges the reader, occasionally.** Writes are acknowledged immediately.
Nothing waits. But a read that happens to encounter a value stamped in an awkward window has
to run again at a higher timestamp. The cost is zero on most reads and one extra round trip
on the unlucky ones. In exchange it runs on rented hardware anywhere — and it stops one step
short of guaranteeing that the order matches real time (see E4.3 and stage 12).

**Choose Spanner** when you need the ordering guarantee itself — an audit trail whose order
will be litigated, or a system where an external observer's account of "what happened first"
must match the database's — and you can live inside Google Cloud.

**Choose CockroachDB** when you need PostgreSQL-compatible serializable transactions on
infrastructure you rent, especially write-heavy workloads where a fixed per-write latency
premium would hurt: payments, order capture, anything transactional and latency-sensitive.

### E1.3

They have mistaken the contract for a failure.

`SQLSTATE 40001` is CockroachDB telling the application: *"I cannot commit this transaction
without breaking serializability; run it again."* It is the visible edge of the design in
E1.1 — the database never blocks a transaction to prevent a conflict, it lets both proceed
and refuses one at the end. **A retry loop around every transaction is part of using this
database**, in the same way that handling a `409 Conflict` is part of using an optimistic
HTTP API. There is no bug to file. There is a missing loop.

There are also ordinary, non-contention causes: a **rolling restart** produces 40001s,
because a new leaseholder cannot vouch for reads the dead one recorded and refuses
transactions older than its lease (stage 11.1).

What I would actually look at, in order:

1. **The rate, and its trend.** A steady low background rate is normal. A step change is not.
2. **The breakdown by reason.** `RETRY_SERIALIZABLE` means contention.
   `ReadWithinUncertaintyIntervalError` means clock skew has become visible to the workload —
   a different problem with a different fix. `ABORT_REASON_NEW_LEASE_PREVENTS_TXN` means
   leases have been moving, so look for restarts and rebalancing.
3. **`crdb_internal.cluster_contention_events`**, which names the index and key range where
   transactions are colliding — usually one hot row.
4. **Whether transactions are succeeding on retry.** If they are, the system is working as
   designed and the question is throughput, not correctness.

The one thing that *would* worry me is the second reason above, because a rising
uncertainty-restart rate is the workload noticing clock skew — and the clock-offset gauge is
the only warning you get before the guarantee itself is at risk (stage 11.3).

---

## Rung 2 — Identify

### E2.1 (simulator, scenario 1, defaults)

- **(a)** Ohio holds the lease for `/accounts/n–z` — its chip is marked with a star.
- **(b)** **One** round of consensus. The step log says `rounds of consensus: 1`.
- **(c)** Both new versions carry **ts 1000**, the transaction's read timestamp, taken from
  Frankfurt's clock at the start. Nothing pushed it, so the commit timestamp equals the read
  timestamp.
- **(d)** It became committed at the step *"Committed — because the data says so"*
  (true time 1121), when the last of the three messages reached a majority. The word
  COMMITTED was first written at the *next* step, *"Tidying up, off the critical path"*
  (true time 1126) — after the client had already been told. That gap is the whole of stage
  10.3.

### E2.2 (simulator, scenario 3, defaults)

- **(a)** The steepest right-leaning dashed line belongs to Asha's payment, labelled
  **+180 ms**. It runs from where the transaction really began (true time 1000) to the
  timestamp it was given (1180). The lean *is* Mumbai's clock error, drawn to scale: a fast
  clock inventing the future.
- **(b)** Before the restart the band runs **960 → 1460**. After it, **1180⁺ → 1460**. The
  left edge moved; **the right edge did not**. That is brake one from stage 10.4, and it is
  why restarts terminate: the doubt is 500 ms wide at first and 280 ms wide afterwards.
- **(c)** Green — meaning "in the reader's past, and visible". At the previous step the same
  dot was amber, meaning "ambiguous". Nothing about the value changed; the reader's timestamp
  did.

### E2.3 (simulator, scenario 2, at *"Frankfurt dies"*)

T1's status is **STAGING**, its in-flight write list is
`/accounts/asha, /accounts/raj`, and its commit timestamp is **1000**.

**The money is moved.** The single determining fact on the screen is that both listed keys
carry an agreed intent at ts 1000 — which, together with the record saying STAGING, *is* the
definition of committed. Nothing has to be written down and nobody has to still be alive.
Note what is *not* on the screen and does not matter: whether the coordinator is running, and
whether anything anywhere says the word COMMITTED.

---

## Rung 3 — Predict

### E3.1

Window is `(1000, 1500]`.

| Version | Classification | Why |
|---|---|---|
| **860** | **visible** | at or below the read timestamp — plainly in the past |
| **1340** | **ambiguous** | inside `(1000, 1500]` — cannot be classified |
| **1780** | **invisible** | above the ceiling — plainly in the reader's future |

After the restart forced by 1340: read timestamp **1340⁺**, ceiling **still 1500**. The
window is now 160 ms wide instead of 500. (Verified against `classify()`: `past`,
`uncertain`, `future` respectively.)

Note that 1780 is *skipped without drama*. A value far in the future is not a problem — it
is unambiguously not yet visible. Only the near future is dangerous.

### E3.2

**With the node's clock at 1500 at first contact:** no change — still ambiguous. The value's
timestamp 1340 is *below* 1500, so it could have been written before we ever spoke to that
node, which means it could be in our past. The observed timestamp tells us nothing useful
here.

**With the node's clock at 1200 at first contact:** the classification changes to
**invisible**. A value stamped 1340 on a node whose clock read 1200 when we contacted it must
have been written *after* that conversation — so it is provably in our future, not merely
possibly. No restart. (Verified: `uncertain` and `future-observed` respectively.)

This is brake two, and it is the reason a transaction can be restarted **at most once per
node**: the first contact with a node fixes a line above which everything that node produces
is certainly future.

### E3.3 (simulator, scenario 3, Mumbai +900, self-check off)

**Prediction: PENDING, and zero restarts.** Confirmed — the simulator reports
`stale=1, restarts=0` and Ben reads `PENDING`.

Why: `off_writer − off_reader = 900 − (−120) = 1020`. The threshold is
`max_offset + Δ = 500 + 80 = 580`, where Δ = 80 ms is the real time between Mumbai taking
its timestamp (true 1000) and Ohio taking its own (true 1080). 1020 > 580, so the payment's
timestamp (1900) lands above Ben's ceiling (1430) and is filed as future.

**The crossover** is at `off_Mumbai = 460`:

| Mumbai offset | payment ts | Ben's ceiling | outcome |
|---|---|---|---|
| +420 | 1420 | 1460 | restart, SETTLED |
| +440 | 1440 | 1460 | restart, SETTLED |
| **+460** | **1460** | **1460** | restart, SETTLED — equal, and still caught |
| **+480** | **1480** | 1460 | **stale read, PENDING** |

So the answer changes between +460 and +480 (the slider moves in steps of 20; the exact
boundary is 460, because the interval is half-open at the top and a value *equal* to the
ceiling is still ambiguous).

The uncomfortable thing to notice: the change from "correct" to "silently wrong" is one
slider notch, and nothing on the screen except the verdict banner behaves differently. There
is no error, no retry, no log line.

---

## Rung 4 — Modify

### E4.1 (simulator, scenario 5, read refresh off)

Both rows end at **OFF**: `dr_mehta = OFF @ 1000` and `dr_qureshi = OFF @ 1000⁺`. Nobody is
on call.

The anomaly is **write skew**. The isolation level CockroachDB has been reduced to is
**snapshot isolation** — which is exactly why `READ COMMITTED` transactions in CockroachDB
explicitly do not perform read refreshing (docs). **Safety** was lost: an outcome that no
serial order could produce is now observable. Liveness in fact *improved* — turning the check
off removed an abort.

**The harder question.** There is no write-write conflict, and looking for one is why write
skew is hard to catch. The conflict is between **T_B's write and T_A's read**. T_A read
`dr_qureshi` at timestamp 1000 and made a decision based on the value it saw. T_B then wants
to change `dr_qureshi` at a timestamp *below* 1000 — which would retroactively falsify a
read that has already been acted upon. The timestamp cache is what makes that read *visible
to a later writer at all*; the push is what stops the write sliding in underneath it; and the
refresh is what stops the pushed transaction from keeping the benefit of a read it made at
the old timestamp. Remove any one of the three and the anomaly returns.

### E4.2 (simulator, scenario 4)

| | restarts | value read for `/payments/p2` |
|---|---|---|
| clock memory **on** (default) | **1** | `PENDING @ 580` — the older version |
| clock memory **off** | **2** | `REFUNDED @ 1285` — the newer version |

**How both are serializable.** A transaction's read timestamp is not a fact about the world;
it is the position in the transaction order that this transaction has been assigned. With the
optimisation on, the report ends up at position 1160⁺ and sees the world as of then. With it
off, it is pushed to 1285⁺ and sees the world as of then. Both are legitimate positions and
both give an internally consistent view. Neither is "more correct"; they are different points
in a valid serial order.

**What it tells you about optimisations.** The observed-timestamp rule is allowed to exist
precisely because it only ever moves a transaction to a *different valid position*, never to
an invalid one. It converts "possibly in my past" into "provably in my future" using
information the transaction already had. An optimisation that changed the answer to one no
serial order permits would be a bug, not an optimisation — and the way to check any proposed
optimisation in this design is to ask which serial order its outcome corresponds to.

### E4.3 (simulator, scenario 8)

| `max_offset` | clock memory | audit saw the effect? | saw the cause? | anomaly? |
|---|---|---|---|---|
| 0 | either | no | no | no |
| 250 | either | no | no | no |
| 500 (default) | either | **yes** | no | **yes** |
| 900 | on | **yes** | no | **yes** |
| 900 | off | yes | yes | no |

Read the table carefully, because it says something different from what the exercise's
framing invites you to expect.

- **At 900 with clock memory on, the anomaly still occurs.** The cause is stamped 1180, which
  is now *inside* the audit's window of `(600, 1500]` — but the audit had already spoken to
  Mumbai when Mumbai's clock read 820, so a value at 1180 is provably in the audit's future
  and is skipped without a restart. Only with clock memory *off* does the audit restart onto
  the cause and the reversal disappear.
- **At 0 and 250 the anomaly does not occur — but not because anything was fixed.** The
  audit's window is now so narrow that it cannot see the *effect* either. There is no
  reversal to observe because the audit sees neither write. That is not a stronger guarantee;
  it is a smaller observation.

**What `max_offset` actually governs.** It governs **stale reads**: whether a reader can miss
a write that was acknowledged *before the reader started*. The causal reverse is a different
thing — it is about the relative order of **two other transactions**, neither of which the
audit interacted with, and neither of which read anything the other wrote. No value of
`max_offset` addresses that, because the uncertainty interval only ever protects a reader
against writes in *its own* past. The only fix is to make one of the two transactions wait
until real time has caught up with its timestamp — which is commit-wait, which is Spanner,
and which CockroachDB does offer, for `GLOBAL` tables only (stage 13.3).

---

## Rung 5 — Trace

### E5.1

**Parallel commits on** (starting at t = 0, local read 1 ms, cross-region round trip 60 ms):

```
t=0     BEGIN on the near node · read_ts taken from its clock
t=1     read the local key   (1 ms)
t=61    read the remote key  (60 ms)
t=61    send, in parallel: intent on key A, intent on key B,
        txn record {STAGING, in-flight:[A,B]}
t=121   all three agreed → commit condition holds → client acknowledged
        client waited 121 ms
t=126   record → COMMITTED, intents resolved            (client already gone)
```

**Parallel commits off:**

```
t=61    send intent on key A and intent on key B
t=121   both agreed
t=121   send txn record PENDING → COMMITTED
t=181   agreed → client acknowledged
        client waited 181 ms
```

**121 ms versus 181 ms** (simulator). And the saving is exactly one cross-region round trip
at every latency — the simulator confirms it across the range:

| round trip | parallel commits on | off | difference |
|---|---|---|---|
| 10 ms | 21 ms | 31 ms | **10 ms** |
| 60 ms | 121 ms | 181 ms | **60 ms** |
| 120 ms | 241 ms | 361 ms | **120 ms** |
| 180 ms | 361 ms | 541 ms | **180 ms** |

**The generalisation.** The intents for all *n* ranges are sent **in parallel**, so they cost
one round trip, not *n*. So:

- with parallel commits, the client waits ≈ **1 × RTT** for the commit;
- without, ≈ **2 × RTT**.

*n* does not appear in either expression. What *n* actually changes is (a) the probability
that at least one of the ranges is slow, since you wait for the **slowest**, not the average
— so the tail latency degrades with *n* even though the mean does not; and (b) the number of
ranges whose leases must be reachable at once, so availability degrades with *n* too. This is
worth stating because it corrects a natural but wrong mental model: distributed commit is
expensive in *round trips* (a constant), and expensive in *exposure* (linear in *n*).

### E5.2 — swapped clocks (simulator: Frankfurt −120, Ohio +180)

```
tsA = HLC(Frankfurt) at true 1000 = 880      T_A, Dr Mehta's transaction
tsB = HLC(Ohio)      at true 1010 = 1190     T_B, Dr Qureshi's transaction

both read both rows; timestamp cache on each row now records the higher read, 1190 (T_B)

t=1200  T_A writes dr_mehta at 880.
        timestamp cache[dr_mehta] = 1190 (recorded by T_B, a different transaction)
        1190 ≥ 880  →  PUSH T_A's commit_ts to 1190⁺
        T_A was pushed → refresh its reads over (880, 1190⁺]
        nothing has changed yet → refresh CLEAN → T_A commits at 1190⁺
        (a successful refresh also re-records T_A's reads in the cache at 1190⁺)

t=1260  T_B writes dr_qureshi at 1190.
        timestamp cache[dr_qureshi] = 1190⁺ (T_A's refreshed read)
        1190⁺ ≥ 1190  →  PUSH T_B's commit_ts to 1190⁺⁺
        T_B was pushed → refresh its reads over (1190, 1190⁺⁺]
        dr_mehta changed at 1190⁺, inside the gap  →  refresh FAILED
        RETRY_SERIALIZABLE (40001)
```

Simulator confirms: `TA=COMMITTED commit@1190⁺`, `TB=ABORTED read@1190 commit@1190⁺`,
`refreshFail=1`, and `dr_qureshi` still `ON CALL`.

**The general rule this demonstrates.** In the default trace, T_A had the *higher* read
timestamp and survived; here T_A has the *lower* one and survives. So the rule is not about
timestamps at all:

> **The transaction that commits second in real time is the one refused** — because the
> conflict does not exist until the first one's write does, and a refresh can only detect
> what already happened.

That has a practical consequence worth carrying: under contention, **the slower transaction
loses**, so long-running transactions on hot data starve. It is the same starvation shape you
priced in [optimistic-vs-pessimistic-locking](../optimistic-vs-pessimistic-locking/), and it
is why `SELECT … FOR UPDATE` exists — it converts a late refusal into an early wait, which is
a trade a long transaction should usually take.

### E5.3

**The bound is one restart per node**, so with three nodes, **at most three**.

**Proof from the two brakes.**

*Brake one — the ceiling is fixed.* The uncertainty ceiling `read_ts + max_offset` is computed
once, when the transaction begins, and never recomputed. Every restart raises `read_ts`
strictly, so the window strictly shrinks, and no restart can ever be caused by a value above
the original ceiling. This alone bounds the *total spread* of restarts to `max_offset`, but
not their count.

*Brake two — the observed timestamp is monotone per node.* The first time the transaction
contacts node *N*, it records *N*'s clock reading `obs[N]`. Suppose a value on *N* stamped
`v` forces a restart. To be ambiguous it must satisfy `v ≤ obs[N]`, and after the restart
`read_ts = v⁺ > v`. Now consider any *later* value `w` on the same node *N*. Either `w ≤
read_ts`, in which case it is visible and causes no restart; or `w > read_ts > v`. For `w` to
be ambiguous it must also satisfy `w ≤ obs[N]` — but the transaction can only *see* a value
that was already written when it read, and any value written after the first contact has
`w > obs[N]`, so it is classified provably-future. The only remaining case is a value with
`v < w ≤ obs[N]` that already existed at first contact and was not returned by the first read
— which cannot happen, because a read returns the newest version below the relevant
timestamp. Hence at most one restart per node. This matches the documented statement that a
transaction restarts *"never for longer than the uncertainty interval, nor more than once per
node."*

**The worst case.** To actually achieve three restarts you need three separate nodes each
holding a value that is (i) stamped above the transaction's current read timestamp, (ii)
below that node's observed timestamp, and (iii) read *after* the transaction has already been
pushed by the previous one. Concretely: three fast-clocked leaseholders, each written to
shortly before the transaction contacts it, read in increasing order of their timestamps. The
practical version is a report that scans several ranges on several fast-clocked nodes while
they are all taking writes — and the mitigation is not to make the clocks better but to read
in the past on purpose (`AS OF SYSTEM TIME`), where nothing can be ambiguous at all.

---

## Rung 6 — Spot the flaw

### E6.1 — the moving ceiling

**The flaw: restarts would no longer terminate.**

Concrete run. `max_offset = 500`. A report begins on Frankfurt at read timestamp 1000, so its
window is `(1000, 1500]`. A fast-clocked node is committing one write every 50 ms, stamped
1200, 1250, 1300, and so on.

- Reads a value at 1200 → ambiguous → restart. **Real rule:** window becomes
  `(1200⁺, 1500]`. **Proposed rule:** window becomes `(1200⁺, 1700]`.
- Reads a value at 1250 → ambiguous under both → restart. Real: `(1250⁺, 1500]`.
  Proposed: `(1250⁺, 1750]`.
- …and so on. Under the real rule the window is shrinking by 50 ms each time and the process
  must stop within 500 ms of where it started. Under the proposed rule **the ceiling runs away
  ahead of the writes forever**, and the transaction can never reach a timestamp at which the
  node's next write is not ambiguous.

**Guarantee violated: liveness.** Safety is untouched — every answer the transaction would
give is correct; it simply never gives one. This is the most instructive kind of flaw,
because the proposed rule is *more* cautious than the real one and cautious-in-the-wrong-place
is a real failure mode.

The deeper point: the fixed ceiling is not a conservative approximation of the truth, it is
the *exact* statement of what the transaction needs. It began at real time *t*, and
`max_offset` bounds how far any clock can be from any other at *t*. Nothing that happens
later can widen that bound retrospectively, so nothing should widen the ceiling.

### E6.2 — recovery without the barrier

**The dropped step: raising the timestamp cache above the transaction's timestamp on the
missing key, *before* marking the record ABORTED.**

Concrete run. Transaction T writes intents on keys A and B and a record
`{STAGING, in-flight:[A,B]}`. The intent on A is agreed; the message carrying the intent on B
is delayed in the network — not lost, just slow. The coordinator dies.

```
t=100   reader R1 finds the intent on A, follows it to the STAGING record
t=110   R1 checks B: no intent present
        [proposed rule]  R1 marks the record ABORTED and discards A's intent
t=115   the delayed message finally arrives: B's intent is agreed by a majority
t=200   reader R2 finds the intent on B, follows it to the record
        the record says ABORTED... but under the proposed rule nothing stops the
        write to B having succeeded after the abort decision was taken
```

Depending on the order in which the two readers and the delayed message interleave, one
reader can conclude the transaction aborted while the other finds every listed write present
and concludes it committed. The result is a **torn transaction**: A discarded, B durable, and
two observers disagreeing about whether it happened.

The real procedure closes this by making the abort **self-enforcing**: it raises the barrier
on B above T's timestamp *first*, so the delayed write can no longer succeed at that
timestamp, and only then records ABORTED. The decision creates the condition that makes it
true.

**Guarantee violated: atomicity.** And the general principle worth extracting: in a system
with no coordinator, a decision is only safe if the act of making it **prevents the
alternative**. Writing down "aborted" is a claim; making the write impossible is a fact.

### E6.3 — the empty timestamp cache

**Concrete run.**

```
t=1000  T_A on Frankfurt reads /oncall/dr_qureshi at 1000 → ON CALL
        Frankfurt's timestamp cache records: dr_qureshi read at 1000
t=1050  Frankfurt (the leaseholder) dies
t=1100  Mumbai takes the lease with an empty cache
t=1200  T_B writes dr_qureshi = OFF at commit_ts 900
        the new leaseholder sees no read mark → no push → T_B commits at 900
t=1300  T_A commits its own write, having read dr_qureshi = ON CALL at 1000
```

T_A's decision rests on reading `dr_qureshi = ON CALL` as of timestamp 1000, and a write at
timestamp 900 — *below* T_A's read — has now been inserted underneath it. There is no serial
order containing both: T_A must come after T_B by timestamp, but T_A did not see T_B's write.
**Serializability is lost.**

The real design refuses instead: the new leaseholder installs a **low-water mark** at its
lease start covering the whole range, so any write from a transaction older than the lease is
refused with **`ABORT_REASON_NEW_LEASE_PREVENTS_TXN`** (simulator, scenario 6). The cost is
that healthy transactions are thrown away by an ordinary node restart — which is exactly why
the retry loop is not optional.

Note what this reveals about the structure: the timestamp cache is *described* as a
performance structure, and it is not replicated — but it is **load-bearing for safety**. The
design's answer is not to replicate it (expensive) but to make its absence conservative
(cheap, and occasionally rude to clients).

---

## Rung 7 — Handle failure

### E7.1 — the ambiguous commit

**The application change: an idempotency key, and a record of it inside the same
transaction.**

```
  BEGIN
    INSERT INTO payment_attempts (idempotency_key, ...)   -- unique constraint on the key
    UPDATE accounts SET balance = balance - 500 WHERE id = 'asha'
    UPDATE accounts SET balance = balance + 500 WHERE id = 'raj'
  COMMIT
```

The client generates the key *before* its first attempt and reuses the same key on every
retry. On a retry, one of two things happens: either the insert succeeds, meaning the first
attempt did not commit and this one should proceed; or it violates the unique constraint,
meaning the first attempt *did* commit and the client can safely report success.

**What the database must provide** for this to work: the uniqueness check and the money
movement must be in the **same transaction**, so that they commit or fail together. That is
precisely the thing a sharded setup cannot give you when the key table and the accounts are
on different shards — and it is the single most concrete reason to want a distributed
transaction at all. It is also why `payment_attempts` must not be a separate service.

**Why a 40001 retry loop is not enough on its own.** A 40001 is a *definite* answer: the
transaction did not commit, and retrying is unconditionally safe. An ambiguous result
(`RETRY_ASYNC_WRITE_FAILURE`, or simply a dropped connection at the moment of acknowledgement)
is an *absence* of an answer: the transaction may have committed. A loop that retries both
identically will double-charge on the second. The idempotency key is what converts the
ambiguous case into the definite one — and note the pleasing coincidence from stage 11.7: a
transaction body written to be re-runnable after a 40001 is already 90% of the way to being
re-runnable after an ambiguous result. The remaining 10% is the key.

See [idempotency](../idempotency/) for the full treatment.

### E7.2 — the sequential primary key

The cause is the encoding in stage 10.1: `/<table>/<index>/<pk>` in a **sorted** map, so a
monotonically increasing primary key means every insert sorts to the end and lands in the
last range, which has one leaseholder, on one machine. Load-based splitting will split that
range, and the new last range immediately becomes the new hot spot — the problem regenerates.

| Fix | What it costs |
|---|---|
| **`UUID` primary key** (random) | Inserts spread perfectly. You lose ordering by id: `ORDER BY id` is meaningless and "the last 100 rows" needs an index on a timestamp column. Keys are 16 bytes rather than 8, in the primary key *and* in every secondary index. |
| **Hash-sharded index** (`USING HASH`) | Keeps the sequential column and its ordering semantics, and spreads writes across *n* buckets. A range scan must now visit all *n* buckets and merge, so scans cost more; and choosing *n* is a tuning decision. |
| **Reorder a composite key** so a high-cardinality column leads | Free, if such a column exists and your access patterns tolerate it. Changes which queries are a seek and which are a scan — this is the fix that most often breaks something else. |

**(a) Only ever looked up by id** → **`UUID`**. You are not using ordering, so give it up
entirely and get perfect distribution for nothing. This is the default answer and should be
the default schema choice for new tables.

**(b) Frequently scanned by `created_at` ranges** → **hash-sharded index on `created_at`**.
Here ordering is the workload, so you cannot randomise it away. Accept the merge cost on
scans in exchange for writes that spread. Do measure it: the docs specifically recommend
performance testing with and without, because for some scan-heavy workloads the merge cost
exceeds the write benefit.

### E7.3 — the climbing clock offset

**Check, in this order:**

1. **Which node, and is it one or several?** One node is a local problem — a failing NTP
   client, a noisy neighbour on the hypervisor, a VM that has been live-migrated. Several
   nodes drifting together points at a shared upstream time source, which is worse.
2. **The uncertainty-restart rate.** If it is climbing alongside the offset, the skew has
   already become visible to your workload.
3. **The NTP or PTP daemon on the affected node** — is it running, is it synchronised, what
   stratum, when did it last step the clock?
4. **Recent infrastructure events** — instance migration, host maintenance, a kernel or
   hypervisor upgrade.

**Do first:** fix time synchronisation on that node, or **drain and remove the node from the
cluster**. Removing a node is cheap and loud; a bad clock is expensive and silent.

**Do not:** raise `--max-offset` to make the number look better. That is the exact wrong
move. Raising the budget does not make the clocks better; it widens every reader's window of
doubt, so it *increases* restart latency across the whole cluster while *reducing* the margin
that triggers the self-check. You would be paying more, for less safety, to hide a symptom.
Also do not lower it in a panic — lowering it while a node is genuinely 380 ms out is how you
turn a warning into a stale read.

**At 400 ms**, the node finds it disagrees with at least half its peers by more than 80% of
the budget and **terminates itself** (docs). You lose a node, availability drops for the
ranges it led until leases move, and everyone notices immediately.

**Why 400 and not 500.** Because at 500 the guarantee is *already broken*. The uncertainty
interval is exactly 500 ms wide; a clock 500 ms out can produce a write that hides from a
reader. The 20% margin exists so that removal happens **before** the promise can be violated,
not at the moment it is. It is the difference between a smoke alarm and a fire report — and
it is the same design instinct as the fixed uncertainty ceiling in E6.1: bound the damage a
misbehaving component can do *before* it misbehaves, rather than detecting it afterwards.

---

## Rung 8 — Design

These are discussions, not answers. What follows is one defensible position with its
reasoning exposed; a different set of choices, defended as well, is equally good.

### E8.1 — a three-region payments ledger

**Localities.**

- `accounts` and `payments` → **`REGIONAL BY ROW`**, partitioned by the account's home
  region. This is the whole ballgame: 90% of traffic touches one region's accounts, and
  `REGIONAL BY ROW` puts those rows' leaseholders in that region, so the common transaction
  is entirely local — no 120 ms round trip, no cross-region consensus. The 10% that crosses
  regions pays the full price, which is correct: it *is* a more expensive operation.
- Reference data — currencies, fee schedules, merchant categories, country rules → **`GLOBAL`**.
  Read on every payment from every region, written a handful of times a week. This is exactly
  the read-mostly shape `GLOBAL` exists for, and it removes a cross-region read from the hot
  path of every single payment. The write cost (a few hundred milliseconds of commit-wait) is
  irrelevant at that write rate.
- An `idempotency_keys` table, in the same locality as `payments`, written in the **same
  transaction** as the money movement (E7.1).

**Survival goal: `SURVIVE REGION FAILURE`.** For a payments ledger with a regulator, losing a
region must not lose the ledger. It costs write latency — a quorum now spans regions even for
a regional transaction — so measure it, and be honest that `SURVIVE ZONE FAILURE` is a real
option if the regulator's actual requirement is weaker than the instinct to survive
everything.

**`--max-offset`: 250 ms**, the documented recommendation for clusters using the multi-region
abstractions. Lower than the 500 ms default means narrower windows of doubt and fewer
restarts — and a thinner margin, so it must be paired with monitoring that treats the offset
gauge as a correctness alarm (E7.3).

**Primary keys: `UUID` everywhere**, with the region column leading the index where the
locality requires it. Payments are a strictly append-shaped workload, which is the worst case
for sequential keys (stage 11.6). Do not use `SERIAL`.

**The negative-balance invariant.** Read the balance and write it in the **same transaction**;
serializable isolation makes the check-then-act safe with no locks and no application logic.
Under contention on a popular merchant's account, expect 40001s — the fix is
`SELECT … FOR UPDATE` on that row to take the conflict pessimistically, or splitting the
merchant's balance into per-region sub-balances that are summed. Pick the second only when
the first stops being enough, because it changes what the invariant *means*.

**The nightly reconciliation:** `AS OF SYSTEM TIME follower_read_timestamp()`. It reads a
few seconds in the past, is served by local followers in every region, blocks nothing, is
blocked by nothing, and adds no contention to live traffic. Raise `gc.ttlseconds` above its
four-hour default for the tables it scans if the job can run long.

**Monitor:** clock offset per node (alert well below 400 ms — page at 200); transaction
retries broken down by reason; hot ranges; contention events; unavailable ranges; p99 write
latency per region. **Alert on:** clock offset, unavailable ranges, and a step change in
`RETRY_SERIALIZABLE`.

**If the regulator upgrades to "the order of all payments must match real time."** This is
the requirement CockroachDB does not meet by default, and it must be said plainly rather
than argued around. The available responses, in order of honesty:

1. **Make the affected tables `GLOBAL`**, which turns on commit-wait and buys back real-time
   ordering — at hundreds of milliseconds of write latency per payment. For a payments
   ledger that is very likely unacceptable, and you should say so with the number attached.
2. **Move to Spanner** for the ledger tables. This is what the requirement is actually asking
   for, and it is worth naming rather than engineering around.
3. **Interrogate the requirement.** The causal reverse needs three transactions, two of which
   share no data. Payments against the *same* account or the *same* merchant can never
   exhibit it, because they overlap. If the regulator's real concern is "two payments on one
   account must be correctly ordered", you already meet it, and the honest move is to
   demonstrate that rather than to buy a guarantee you do not need.

### E8.2 — migrating from sixteen sharded PostgreSQL instances

**What gets simpler, and this is most of the value:**

- **The saga disappears.** Every cross-shard case it existed to handle becomes an ordinary
  `BEGIN … COMMIT`. You delete the compensating transactions, the state machine, the
  orchestrator, and the tests for all of it. From [saga](../saga/): a saga is *chosen*
  fractured visibility with compensations — you were choosing it because you had no
  alternative, and now you do.
- **The nightly reconciliation job disappears too**, or shrinks to an assertion. It existed
  to repair what the saga missed. Nothing is missed now. Keep a much cheaper version as a
  canary; delete the repair logic.
- **Resharding disappears.** Ranges split and rebalance automatically; there is no shard map
  in application configuration and no migration project when a shard gets hot.
- **Cross-shard reads become ordinary queries** instead of scatter-gather in the application.

**What gets harder:**

- **Every transaction needs a retry loop.** This is new, it is not optional, and it is not
  only about contention — a rolling restart produces 40001s (stage 11.1). It touches every
  data-access path in the codebase.
- **Transaction bodies must be re-runnable**, which means no side effects inside the
  transaction that cannot be repeated: no sending an email, no calling a payment gateway, no
  incrementing an in-memory counter. This is a real refactor and it is easy to underestimate.
- **Latency for cross-region transactions is now visible in your own system** rather than
  hidden in the saga's asynchrony. The saga was slow too — it just was not slow *inside a
  request*. Expect a conversation about p99s that is partly a conversation about honesty.
- **Schema design decides physical distribution.** PostgreSQL forgives a `BIGSERIAL` primary
  key; CockroachDB pins your whole insert workload to one node for it (stage 11.6).
- **Not every PostgreSQL feature is present**, and the ones that are may perform differently.
  Audit before committing to a date.

**The one new failure mode the on-call rotation must learn: clock skew.** Nothing in
sixteen-shard PostgreSQL had a correctness property that depended on clocks. Now one does,
its failure is silent, and the only warning is a gauge (stage 11.3). Put it in the runbook,
put it on the dashboard, and rehearse it — because it is the only failure in the list that
does not announce itself.

**Migration order:** move a low-risk, single-shard table first to shake out the retry loops
and the schema idioms; then a table involved in the saga, so you can delete a compensation
path and prove the value; then the core ledger. Run dual-write with reconciliation during the
transition — which is the one time the reconciliation job you are about to delete earns its
keep.

### E8.3 — the global product catalogue

**Choice: a CockroachDB `GLOBAL` table.**

Apply the who-pays framing from stage 6. The workload is roughly a million reads per write.
The question is therefore: *who should carry the cost of clock uncertainty and cross-region
coordination?* The answer is obvious as soon as it is asked that way — **charge the writer**,
because there are almost no writers.

- **A default CockroachDB table** charges the reader: reads outside the leaseholder's region
  cross an ocean, on every page load. Wrong payer, at the worst possible ratio.
- **A `GLOBAL` table** closes timestamps in the future, forces the hundred daily writes to
  commit-wait a few hundred milliseconds, and lets every read be served from a local replica
  without blocking on anything. A merchandiser waiting 400 ms to save a product is a
  non-event; a customer waiting 120 ms extra on every page load is a business problem.
- **Spanner** would serve this well too and is architecturally the same answer — its writers
  always commit-wait. Choose it only if you are already there; it is not worth a cloud
  migration for one table.
- **A cache in front of single-region PostgreSQL** is the cheapest thing that works, and for
  a product catalogue it is a completely reasonable answer. What you give up is a *bound* on
  staleness: cache invalidation across regions is best-effort, and a stale price is a support
  ticket. `GLOBAL` gives you the same read latency with a transactional guarantee instead of
  a hope.

**What would change the answer:**

- **Writes become frequent** — say, algorithmic repricing every minute. Commit-wait now sits
  on the hot path, the ratio flips, and `REGIONAL BY ROW` partitioned by the merchandising
  team's region becomes the better shape.
- **Reads become tolerant of staleness.** If pages can happily show data five seconds old,
  `AS OF SYSTEM TIME follower_read_timestamp()` on an ordinary regional table gives you local
  reads for free, with no write penalty at all. This is the cheapest option in the whole
  list and is the one most often overlooked — *always ask how fresh the reads actually need
  to be before paying for freshness.*
- **The catalogue is only read in one region.** Then none of this matters and a plain
  regional table is correct. Do not buy multi-region machinery for a single-region problem.
