# Kafka Exactly-Once — Solutions

Rungs 1–5 have right answers. Rungs 6–8 have worked reasoning; your answer can
differ and still be good, provided it survives the same objections.

---

## Rung 1 — Explain

**1.1** A Kafka transaction makes one thing true: **a set of appends — records
written to any number of Kafka partitions, plus the consumer offset commit that
says the input was handled — becomes visible to `read_committed` readers all at
once, or never at all.** The set is appends to Kafka logs and nothing else.

Used as a decision rule: if every output of the stage is an append to a Kafka
topic, EOS applies. The moment one output is a database row, an HTTP call or a
file, that output is outside the set and the transaction says nothing about it.

**1.2** *"It does not change how many times your function runs — it runs
at-least-once, always. It changes whether the losing runs are ever visible."*

What they bought: for a stage whose outputs are all Kafka topics, downstream
consumers reading `read_committed` will never see the outputs of a run that did
not complete, and the input offset advances at the same instant those outputs
become visible.

**1.3** A SQL rollback removes the effects: the rows go away and the space is
reused. A Kafka abort removes nothing. The records are already appended to the
log at permanent offsets, replicated to every follower. The abort is itself an
**append** — a control record after the data saying "everything from this
producer above this line does not count." The **consumer client library** does
the filtering: the broker sends the aborted records over the network along with a
list of aborted transactions, and the client drops them before your application
sees them. A consumer that does not ask for filtering
(`isolation.level=read_uncommitted`) receives them as ordinary data.

**1.4** The three:

- **(a) No natural key where the duplicate lands.** An aggregate — a rolling
  hourly total — has no identity to deduplicate on. This one is the **hard**
  impossibility: it is not that dedupe is expensive there, it is that there is
  nothing to key on, so the deduplication must happen upstream of the aggregation
  or not at all.
- **(b) The dedupe store is a second system**, which rebuilds the dual-write
  problem at the point that was supposed to solve it. An expense plus a new
  failure mode, not an impossibility.
- **(c) The cost is per hop** and hops multiply. An expense.

**1.5** Kafka's reason: the two isolation levels behave identically on topics with
no transactional producers, which is the overwhelming majority of topics. Making
`read_committed` the default would impose LSO-bounded staleness and the
head-of-line blocking of §11.1 on every consumer in every cluster, to protect a
feature most of them do not use — and it would be a silent behaviour change on
upgrade for everyone. The consequence you have to live with is that EOS is opt-in
on both sides, and the two sides are usually configured by different teams.

**1.6**

- **Idempotent producer** — deduplicates retries of a *network conversation*
  between one producer and one broker, per partition, using `(PID, epoch,
  sequence)`. Protects the **producer → broker** hop. On by default since 3.0.
- **Transaction** — atomic visibility of a set of appends across many partitions
  plus the offset commit. Protects the **Kafka → Kafka** hop, including the
  progress record.
- **Idempotency key** — a client-minted identifier that lets a server recognise a
  replayed intention and return the original outcome. Protects **any hop**,
  including every hop that leaves Kafka, which is the only tool that does.

---

## Rung 2 — Identify

**2.1** At **step 7**, when the coordinator appends `PrepareCommit` to its
partition of `__transaction_state` and that record is replicated to
`transaction.state.log.min.isr` replicas (default 2). The record lives in an
ordinary replicated Kafka partition, which is exactly why coordinator failure
after this point cannot change the outcome (§10.4).

**2.2**

- The **transaction coordinator** — leader of the `__transaction_state` partition
  that `settlement-3` hashes to — receives `AddOffsetsToTxn(groupId)`, which adds
  `__consumer_offsets-12` to the transaction's partition set.
- The **group coordinator** — the broker that owns the `settlement` consumer
  group — receives `TxnOffsetCommit` and appends the actual offset record.

The split exists because the two brokers own two different things: one owns the
transaction's state machine, the other owns the group's offsets and its
membership generation. Keeping them separate is what makes the second fencing
check of §10.7 possible.

**2.3** The **broker** does, and it is consulting the partition's **last stable
offset**. This is not client-side filtering of a stream the consumer can see —
the fetch response is truncated at the LSO, so the data beyond it is never sent.
That is why one undecided transaction blocks the whole partition rather than just
its own records.

**2.4** LSO = **816** — one less than the offset of the first open transaction,
which is producer 5002's record at 816.

- `read_committed`: **808, 809, 814**. (810–812 are aborted; 813 and 815 are
  control records; 816 is beyond the LSO and is never sent.)
- `read_uncommitted`: **808, 809, 810, 811, 812, 814, 816**. Control records are
  still filtered out by the client in both modes — they are never delivered to an
  application.

**2.5**

- **Leader epoch** — fences a stale *replica leader*; introduced by KIP-101 to fix
  log divergence after failover (the `kafka` lab, §10.5 there).
- **Producer epoch** — fences a stale *producer instance* holding a
  `transactional.id`, and under transaction version 2 also fences a stale
  *transaction* from the same producer.

The general object is the fencing token, unified in `split-brain` under its seven
names — term, epoch, generation, fencing token, lease version, and now these two.

**2.6** It starts at the first **`AddPartitionsToTxn`** — the producer
documentation says so explicitly: *"the start of the transaction is set at the
time that the first partition is added to it."* People wrongly assume
`beginTransaction()`, which sends nothing over the network at all. The practical
consequence: expensive setup work between `beginTransaction()` and the first
`send()` is free, and expensive work after the first `send()` is on the clock.

---

## Rung 3 — Predict

**3.1** All five records **commit**. The coordinator drives the markers and
`CompleteCommit` on its own; the producer is the client that requested a decision,
not a participant in it.

The application observes only that it never learned the outcome. On restart it
reads the group's committed offset — which is **5003**, because the offset commit
was inside the transaction that committed — and resumes there. The batch is not
redone. Compare with §2's crash at the same point in the sequence, which double-paid
TeaHouse.

**3.2** Producer killed after `sendOffsetsToTransaction`, before `EndTxn`:

**At t+30s** — all five records are durably in their partitions, all undecided.
Coordinator state `Ongoing`. `ledger-entries-1` LSO 810, `merchant-payouts-0` LSO
44, `__consumer_offsets-12` LSO 9001. Every `read_committed` consumer of all three
partitions is stalled at those offsets; every `read_uncommitted` consumer is
unaffected and has already seen the payouts.

**At t+120s** — the 60-second timeout expired at t+60s. The coordinator wrote
`PrepareAbort`, ABORT markers into all three partitions, then `CompleteAbort`. The
five records are still physically present, marked aborted. The LSOs have jumped
past the markers. The group's committed offset is still 5000, so the batch will be
reprocessed by whichever instance owns the partition.

**3.3** `read_committed`: **only after the hung transaction is resolved**, because
the LSO is a property of the partition and sits at 44. A perfectly ordinary record
at 46 is unreachable behind somebody else's undecided one at 44.
`read_uncommitted`: **immediately**.

This is the single most surprising operational property of transactions and the
strongest argument against mixing transactional producers onto shared topics.

**3.4** TeaHouse is paid **₹3,920** — twice.

The mechanism that failed is **producer fencing**, and it failed because fencing is
by *name*. A and B have different `transactional.id`s, so from the coordinator's
point of view they are two unrelated jobs; no epoch is bumped, A is never fenced,
and A's transaction is not aborted. Every EOS mechanism was enabled and behaving
exactly as configured. (Simulator: scenario 6.)

**3.5** Two things change immediately:

1. **The one they configured:** output records and offset commits become
   transactional, and downstream `read_committed` consumers see them only at
   commit.
2. **The one they did not:** `commit.interval.ms` changes from **30000 to 100** —
   the documented default under `exactly_once_v2`. Their pipeline now commits 300
   times more often, writing a marker into every output and changelog partition
   each time.

A third, if the cluster is small: EOS requires at least three brokers by default,
because `transaction.state.log.replication.factor` is 3. On a smaller cluster the
application fails to start.

**3.6** **There is no data loss.** Offset 813 holds a **control record** — a COMMIT
or ABORT marker — which occupies a real offset and is filtered out by the consumer
client before the application sees it. Aborted records occupy offsets the same way.

The monitoring rule is wrong: on any partition with a transactional producer,
consecutive offsets in an application's view are not guaranteed and never were.
Any logic of the form `expectedNext = lastSeen + 1` breaks the day transactions
are switched on upstream.

---

## Rung 4 — Modify

**4.1** Raising `transaction.timeout.ms` 60 s → 600 s:

- **Less likely:** §11.7, the transaction that legitimately outruns its own
  timeout and is aborted underneath a healthy producer. Long batches and long GC
  pauses stop causing spurious aborts.
- **Worse:** §11.1's stall. The worst-case time a `read_committed` consumer can be
  held behind an abandoned transaction goes from 60 seconds to **10 minutes** —
  and the people who experience it are not the team that changed the setting.
  They are every consumer of every partition that producer touches, including
  applications with no relationship to it.

**4.2** With `transaction.state.log.min.isr=1`, `PrepareCommit` can be
acknowledged by the leader alone.

Smallest sequence: the coordinator writes `PrepareCommit` (leader only, not yet
replicated) → it sends `WriteTxnMarkers` and `ledger-entries-1` writes its COMMIT
marker → the coordinator's broker dies before the `PrepareCommit` record reaches
any follower → a new leader is elected from a follower whose log ends before
`PrepareCommit` → the new coordinator sees `Ongoing`, and eventually **aborts** →
ABORT markers go to the remaining partitions.

Result: `ledger-entries-1` has a COMMIT marker and `merchant-payouts-0` has an
ABORT marker for the same transaction. A `read_committed` consumer sees the ledger
lines but never the payouts.

**The broken guarantee is atomic visibility (§12.1).** The general lesson: the
durability configuration of `__transaction_state` is the durability of the
*decision*, and it is exactly as important as the durability of your data topics.

**4.3** Raising Streams `commit.interval.ms` 100 → 5000 under EOS:

- **Improves:** throughput and broker load. 50× fewer transactions means 50× fewer
  markers written into every output and changelog partition, and 50× fewer
  coordinator round trips.
- **Degrades:** end-to-end latency — and the unit is **the commit interval
  itself**. Under EOS nothing is visible downstream until the transaction commits,
  so the commit interval is a hard latency floor, not an average. Records
  processed at t+1 ms and t+4999 ms both become visible at t+5000 ms.

Also worse: an abort now discards up to 5 seconds of work instead of 100 ms.

**4.4** Batching 3 → 3,000 captures per transaction:

1. **Throughput improves** — the per-transaction round trips (`AddPartitionsToTxn`,
   `AddOffsetsToTxn`, `EndTxn`) and the marker writes are amortised over 1,000×
   more records.
2. **Latency worsens** by the same factor — nothing is visible until the batch
   commits.
3. **The cost of an abort rises** — a failure at record 2,999 discards all 3,000,
   and every one of them was written, replicated and stored.
4. **The LSO stall lengthens** — the transaction is open far longer, so the
   worst-case block on every `read_committed` consumer of those partitions grows.

The binding constraint becomes **`transaction.timeout.ms`** (default 60 s): the
whole batch must be produced and committed inside it. `max.poll.interval.ms`
(5 min) constrains the same batch from the consumer side, and by default the two
disagree — the consumer is allowed five minutes for work the coordinator will
abort after one.

**4.5** Running `read_uncommitted` and deduplicating downstream is **sound only if
every aborted record has a committed counterpart carrying the same deduplication
key** — that is, only if aborts are always *retries* of work that later succeeds
identically.

It is **strictly worse than never having used EOS** when an abort represents work
that never happens at all: a validation failure, a poison record aborted
permanently, or a transaction aborted because the input was withdrawn. A
deduplicator cannot tell "this is a second copy of something real" from "this was
never real." Worse, §11.4 shows that EOS *deliberately generates* aborted records
on every rebalance — records an at-least-once pipeline would never have produced.
So this configuration takes on EOS's cost and its extra aborted records, and then
disables the only thing that made those aborts safe.

---

## Rung 5 — Trace

**5.1** Full commit. Starting LEOs: LE 810, MP 44, CO 9001.

| # | Message | Appended | Coordinator | LSO: LE / MP / CO |
|---|---|---|---|---|
| 1 | `FindCoordinator` + `InitProducerId` | `__transaction_state-17`: pid 4711, epoch 0 | `Empty` | 810 / 44 / 9001 |
| 2 | `beginTransaction()` — nothing on the wire | — | `Empty` | 810 / 44 / 9001 |
| 3 | `AddPartitionsToTxn(ledger-entries-1)` | coordinator log; **timeout clock starts** | `Ongoing` | 810 / 44 / 9001 |
| 4 | `Produce` → ledger | LE 810, 811, 812 (undecided) | `Ongoing` | **810** / 44 / 9001 |
| 5 | `AddPartitionsToTxn` + `Produce` → payouts | MP 44, 45 (undecided) | `Ongoing` | 810 / **44** / 9001 |
| 6 | `AddOffsetsToTxn` + `TxnOffsetCommit` | CO 9001 (undecided) | `Ongoing` | 810 / 44 / **9001** |
| 7 | `EndTxn(commit)` | `PrepareCommit` — **the commit point** | `PrepareCommit` | 810 / 44 / 9001 |
| 8a | `WriteTxnMarkers` → ledger | LE 813 COMMIT | `PrepareCommit` | **814** / 44 / 9001 |
| 8b | `WriteTxnMarkers` → payouts | MP 46 COMMIT | `PrepareCommit` | 814 / **47** / 9001 |
| 8c | `WriteTxnMarkers` → offsets | CO 9002 COMMIT | `PrepareCommit` | 814 / 47 / **9003** |
| 9 | — | `CompleteCommit` | `CompleteCommit` | 814 / 47 / 9003 |

The only row at which the outside world's behaviour changed is **8b**, and only
for readers who asked for `read_committed`. A `read_uncommitted` reader saw the
payouts at row 5.

**5.2** Abort of the same run. Rows 1–6 are identical. Then `EndTxn(abort)` →
`PrepareAbort` → ABORT markers at LE 813, MP 46, CO 9002 → `CompleteAbort`.

- **Bytes of payload reclaimed: 0.** Every record is where it was, replicated to
  every follower, and stays until ordinary retention or compaction removes its
  segment.
- **Offsets freed: 0.** 810–812, 44–45 and 9001 are permanently consumed.
- **Records now in `merchant-payouts-0` from this run: 3** — two aborted payout
  records plus one ABORT marker. The abort cost *more* than the commit would have.

**5.3** Protocol version 1, on `merchant-payouts-0`:

```
44  TeaHouse ₹1,960   pid 4711 epoch 0   undecided     (T1)
    BookBarn ₹2,450 — request stalled in the network, pid 4711 epoch 0
45  ABORT(4711)                                        (T1 aborted; epoch NOT bumped)
    → 44 becomes aborted
46  Deepa Tailors ₹500  pid 4711 epoch 0  undecided    (T2)
47  BookBarn ₹2,450     pid 4711 epoch 0  undecided    ← the stalled T1 request lands,
                                                          and joins T2
48  COMMIT(4711)  → decides every undecided record from pid 4711 → 46 AND 47 commit
```

Bank job pays **₹500 + ₹2,450 = ₹2,950**, of which ₹2,450 came from a transaction
that was explicitly aborted.

With the epoch bumped on `EndTxn` (transaction version 2): T1 is epoch 0, the
abort takes the producer to epoch 1, T2's record at 46 carries epoch 1, and the
stalled request carrying epoch 0 is **rejected — never appended**. The commit
marker decides only epoch-1 records. Bank job pays **₹500**, which is exactly what
T2 wrote. (Simulator: scenarios 7 and 8.)

**5.4** Coordinator failure between the first and second `WriteTxnMarkers`:

At the moment of failure — LE has 810–812 plus a COMMIT marker at 813, so its
records are committed and its LSO is 814. MP has 44–45 undecided, LSO 44. CO has
9001 undecided, LSO 9001. `__transaction_state-17` ends at `PrepareCommit`.

The new coordinator is elected as an ordinary partition leader election, replays
`__transaction_state-17`, and finds `PrepareCommit`. It re-sends `WriteTxnMarkers`
to all three partitions; the ledger already has its marker and ignores the
duplicate; MP gets 46 and CO gets 9002. It then writes `CompleteCommit`.

Final state is identical to a run with no failure. **The step that made the outcome
un-changeable was step 7**, the durable `PrepareCommit` — everything after it is
mechanical, and any coordinator can finish it because the decision was written
down rather than remembered.

**5.5** Zombie with a stable `transactional.id`:

```
t+0    A: pid 4711, epoch 0. Writes LE 810-812, MP 44-45. Coordinator: Ongoing.
       A freezes.
t+45s  A misses heartbeats; session.timeout.ms expires; group rebalances
       captures-3 to B.
t+46s  B: initTransactions() with transactional.id = settlement-3 (the same string)
       → coordinator bumps epoch 0 → 1
       → coordinator aborts A's transaction: PrepareAbort,
         ABORT marker LE 813, ABORT marker MP 46, CompleteAbort
       → A's records 810-812 and 44-45 are now aborted (still on disk)
t+50s  B, at epoch 1, reprocesses: LE 814-816, MP 47-48, CO offset record;
       commits; markers at LE 817, MP 49, CO 9002.
t+90s  A wakes and sends EndTxn(commit) carrying epoch 0.
       Coordinator's current epoch is 1 → ProducerFenced.
```

The exact error A receives is **`ProducerFenced`** (`InvalidProducerEpoch` on some
paths). The Java producer treats it as fatal: the object is permanently unusable
and the application must close it. TeaHouse is paid ₹1,960 exactly once.

---

## Rung 6 — Spot the flaw

**6.1 Redis dedupe set inside the transaction loop.**

Smallest failing scenario: the service polls capture 5000, adds `5000` to the
Redis set, writes its outputs, and the transaction then aborts (a rebalance, a
timeout, anything in §11). The Kafka side is correctly voided. **Redis is not.**
On reprocessing, the service sees `5000` in the set, skips it, and the capture is
never settled by anyone.

Violated: *no input is silently dropped* — the at-least-once floor of §3. And the
mechanism is the `idempotency` lab's sharpest failure, rebuilt exactly: the dedupe
state lives in a different system from the effect, so the two can disagree. Kafka
transactions cover appends to Kafka logs; a Redis `SADD` is not one.

**6.2 `read_committed` with `enable.auto.commit=true`.**

Smallest failing scenario: the consumer polls 5000–5002 and begins a transaction.
Five seconds later — `auto.commit.interval.ms` — the background thread commits
offset 5003 **outside the transaction**. At t+6s the transaction aborts. The
outputs are void; the offset is not. Those three captures are never reprocessed
and never settled.

Violated: atomic visibility (§12.1), by removing the offset commit from the set.
The whole design of §5 depends on the progress record being *one of the outputs*;
auto-commit quietly makes it a seventh, uncovered write. The signature is
occasional, unreproducible loss under load and during rebalances.

**6.3 Three replicas, all with `transactional.id=settlement`.**

Smallest failing scenario: pod A is running and holding the id at epoch 3. Pod B
starts and calls `initTransactions()` → epoch 4, and A's in-flight transaction is
aborted. A's next write returns `ProducerFenced`, which is fatal, so A crashes.
The orchestrator restarts A → epoch 5 → B is fenced and crashes. Repeat forever.

Violated: **liveness**. Correctness is intact throughout — that is what fencing
is for — but the application makes no progress. The fix is the same rule as §11.3
read the other way: the id must be unique *across concurrent workers* as well as
stable across restarts. One id per task assignment, not one per application.

**6.4 `transaction.timeout.ms=2000` with 800 ms batches.**

Smallest failing scenario: a 1.5-second GC pause lands 1.2 seconds into a
transaction. At 2.0 s the coordinator aborts it. The producer wakes at 2.7 s and
tries to commit; under transaction version 2 its epoch has moved, so it is fenced
and must be recreated. The batch is reprocessed.

Violated: liveness again, plus wasted writes. "Plenty of headroom" is the error —
the budget must cover the *worst case*, including GC pauses, a slow broker, a
leader election on an output partition, and a `CONCURRENT_TRANSACTIONS` retry
storm. Note also which way the clock runs: it starts at the first
`AddPartitionsToTxn`, so setup before the first `send()` is free, and everything
after it counts.

**6.5 `@Transactional` with a chained transaction manager.**

Smallest failing scenario: the database transaction commits; the process is then
partitioned from the Kafka cluster and the Kafka commit fails. The offset was
never committed, so the record is reprocessed after the rebalance, and the
database write happens a second time.

Violated: the claim of atomicity. Nesting gives you an **ordering**, not
atomicity — you have chosen which side of the window to be on, not closed it.
Spring's own reference records that `ChainedKafkaTransactionManager` has been
deprecated since version 2.7, and this is why. The real answer is
`transactional-outbox` on the way out of the database, and an idempotency key on
the way into anything that is not Kafka.

**6.6 "Aborted records are filtered by the broker."**

They are not. The broker truncates the fetch at the LSO and attaches an
`AbortedTransactions` list; the **client** does the filtering. So every aborted
record is fetched over the network by **every consumer group** on that topic
before being discarded.

Smallest failing scenario: a pipeline with a 30% abort rate and five consumer
groups pays roughly 1.5× the aborted volume in extra broker egress, plus the
disk and replication cost of writing the records in the first place. Nothing is
incorrect — this is a capacity error, and abort rate belongs on the bandwidth
dashboard, not only the correctness one. (The reason the broker cannot filter is
the same reason Kafka is fast: fetches are served from the page cache in whole
batches by byte range. Per-consumer rewriting would destroy `sendfile`.)

---

## Rung 7 — Handle failure

**7.1 The six-hour LSO stall.**

*Diagnosis.* The signature is unambiguous: high watermark rising, LSO flat,
`read_committed` lag climbing linearly, `read_uncommitted` consumers healthy, and
log cleanup no longer reclaiming that partition. That combination is a **hanging
transaction** (§11.5) — a transactional record for which no marker will ever be
written. Note what it is *not*: an abandoned transaction that will time out. No
timer is running, because the coordinator has no open transaction for it.

*Commands.*

```
kafka-transactions.sh --list                            what the coordinators know
kafka-transactions.sh --find-hanging --broker-id <id>   scan the broker holding it
kafka-transactions.sh --describe --transactional-id <x> state, pid, epoch, partitions
kafka-transactions.sh --abort ...                       write the missing marker
```

*Fix.* Force the abort. The tool offers no commit, and that is correct: there is
no safe way to decide that an orphaned record should count.

*Prevention.* Upgrade the brokers. `transaction.partition.verification.enable`
(default `true`) makes the broker ask the coordinator whether a partition is
genuinely in an open transaction before appending, which closes the origin of
this failure for every client version; transaction version 2, on by default from
4.0, closes the related leak. Then add the alert nobody had: per-partition
**(high watermark − LSO)**, paging when it grows monotonically. Nothing else
detects this, because nothing else is wrong.

**7.2 Bank API call plus a Kafka write.**

The pipeline splits at the boundary, and the split is the point:

```
captures ──▶ settlement service ──▶ bank API (external, not covered by EOS)
                    │
                    └──▶ merchant-payouts + offset commit (Kafka, covered by EOS)
```

- **What EOS covers:** the payout row and the offset commit becoming visible
  together, so no downstream consumer sees a payout row for a batch whose progress
  was not recorded.
- **What it cannot cover:** the bank call. `abortTransaction()` cannot unsend an
  HTTP request. This is the `idempotency` lab's crux, unchanged.
- **What carries the guarantee across the boundary:** an **idempotency key** the
  bank honours, minted by the settlement service, deterministic in the capture id
  — e.g. `settlement:{capture_id}` — so that a replayed call after any abort or
  reprocessing returns the original outcome instead of moving money again.

The shape, then: call the bank first with the deterministic key, then write the
payout row and commit offsets inside one Kafka transaction. A crash before the
Kafka commit reprocesses the batch and re-calls the bank — safely, because the key
is the same. A crash after it does not reprocess. If the bank does not support
keys at all, you are in `distributed-locking`'s box G: choose at-most-once or
at-least-once deliberately, record the ambiguity durably, and reconcile.

**7.3 A 5-second reader SLO against a 3-minute producer.**

Both requirements are real, so something must give. Four ways out:

1. **Shrink the transactions.** Split the 3-minute batch into many short ones. The
   3 minutes was almost certainly batch size, not one indivisible operation. Cheap,
   and usually correct.
2. **Move the slow work outside the transaction.** The clock starts at the first
   `AddPartitionsToTxn`, so enrichment, lookups and I/O done *before* the first
   `send()` cost nothing. Often turns 3 minutes into 3 seconds with no design
   change.
3. **Separate the topics.** The stall is per partition, so give the slow producer
   its own topic and let the latency-sensitive consumer read a different one. Costs
   a topic and a fan-out step.
4. **Drop EOS for that stage** and use an idempotency key at the sink instead.

*Choice:* (2) first, because it is free and usually sufficient; then (1); and (3)
as the structural fix if the slow stage is genuinely slow. What you give up with
(3) is the ability for the two consumers to read one ordered stream — they are now
reading different topics and any cross-topic ordering assumption is gone.

**7.4 The alert set.**

| Alert | Signal | Shape | Catches | Does **not** catch |
|---|---|---|---|---|
| **LSO divergence** | per-partition (high watermark − LSO) | monotonic growth over ~10 min | hanging transactions (§11.5) — **produces no errors anywhere** | a correctly-timing-out abandoned transaction |
| **`read_committed` lag** | consumer lag on EOS consumer groups | sustained rise with a flat producer rate | §11.1 stalls, slow consumers | anything a `read_uncommitted` consumer is doing |
| **Transactional id count** | `ProducerIdCount`, or `kafka-transactions.sh --list` | growth across deploys that never falls | the random-`transactional.id` bug (§11.3) — **also produces no errors** | a stable id that is simply wrong |
| **Abort ratio** | producer `txn-abort-time-ns-total` vs `txn-commit-time-ns-total` | ratio rising | rebalance storms, timeout misconfiguration, poison records | a pipeline that aborts nothing but is misconfigured |
| **Verification failures** | `AddPartitionsToTxnManager,VerificationFailureRate` | sustained non-zero (transient during rolls is normal) | clients producing to partitions not in a transaction | anything on brokers with verification disabled |
| **Coordinator load time** | `transaction-coordinator-metrics,partition-load-time-max` | rising | oversized `__transaction_state`, slow failover | a healthy coordinator with a broken client |

The two that catch failures producing no errors anywhere are **LSO divergence**
and **transactional id count**, and they are the two nobody has.

What the whole set misses: **§11.6.** No broker or consumer metric distinguishes a
`read_uncommitted` consumer that should be `read_committed` from one that is
correctly `read_uncommitted`. That failure is only findable by configuration
audit, which is why it is first in 7.5.

**7.5 The audit.**

1. **`isolation.level` on every downstream consumer.** Follow the topic to every
   hop, including sink connectors, non-Java clients and other teams' services. A
   wrong answer here means EOS has been doing nothing but adding cost —
   `read_uncommitted` is the default and nobody has to have done anything wrong for
   it to be set. **This ends most investigations.**
2. **`transactional.id` — how is it built?** If it contains a UUID, a hostname, a
   pod name or a timestamp, nothing is ever fenced (§11.3). Cross-check with the
   id count over the last few deploys.
3. **`enable.auto.commit`, and any manual `commitSync` / `commitAsync`.** If
   offsets move outside `sendOffsetsToTransaction`, atomicity is broken at the one
   place that made it work (§11.8).
4. **Is `sendOffsetsToTransaction` called with `groupMetadata`?** Without it there
   is no group-generation fencing, and a rebalance mid-transaction can let a stale
   owner commit offsets for a partition it no longer holds (§10.7).
5. **Does anything in the pipeline touch the world outside Kafka?** If it does,
   that hop is not covered no matter what the other four answers are, and it needs
   an idempotency key.

---

## Rung 8 — Design

**8.1 The rolling hourly total.**

`idempotency`'s answer does not apply because **an aggregate has no identity**. The
output is `(TeaHouse, 14:00–15:00, ₹18,400)`. If an upstream duplicate added
₹1,200 twice, that number is wrong, and there is no key on the aggregate a
downstream reader could use to notice — you cannot deduplicate a sum. The
deduplication has to happen strictly upstream of the aggregation, at a point where
the records still have identities, or not at all.

What does apply: transactions, and specifically **Kafka Streams with
`processing.guarantee=exactly_once_v2`**, because the aggregation's state store
changelog is written inside the same transaction as the output and the offset
commit. That is the case where transactions have no substitute — a stateful
operator's state, its output and its progress are one atomic set or the state
drifts silently from the stream that produced it.

New operational properties you have taken on:

- a commit interval of 100 ms by default, and it is now your downstream latency
  floor;
- markers written into every output *and every changelog* partition on every
  commit;
- a hard requirement for three brokers;
- everything in §11 — including that a hung transaction now stalls consumers of
  your changelog topics too;
- and a state store that must be restored from a compacted changelog on every
  reassignment, which is its own operational surface.

**8.2 The shared topic.**

*The case against.* Transactions are not a private setting. Three consequences
land on the other five teams and none of them consented:

1. **Every `read_committed` consumer of that topic now stalls behind your
   transactions**, up to `transaction.timeout.ms`. Your batch duration becomes
   their p99.
2. **Every consumer, at either isolation level, now sees non-consecutive
   offsets** — markers and aborted records occupy them. Any offset arithmetic or
   gap detection anywhere on that topic breaks.
3. **Every consumer pays fetch bandwidth for your aborted records**, and §11.4
   guarantees you will generate them on every rebalance.

And one that lands on you: if any of those five teams' consumers are
`read_uncommitted` — the default — they will act on your aborted records as real
data, which is worse for correctness than not using transactions at all.

*Conditions for yes:* the topic gets its own partitions or, better, its own topic;
every consumer is inventoried and moved to `read_committed`; `transaction.timeout.ms`
is set to the smallest workable value and treated as a published SLO; transaction
duration is monitored; and the offset-contiguity assumption is audited across all
six teams' code. If you cannot do the inventory, the answer is no — because the
failure you cannot rule out is the silent one.

**8.3 KIP-939 for database-plus-Kafka atomicity in Spring Boot.**

*The mechanism is real.* `transaction.two.phase.commit.enable` plus
`prepareTransaction()` and `initTransactions(keepPreparedTxn)` genuinely let an
external coordinator hold Kafka in a prepared state and decide later. This is not
marketing.

*The property it switches off.* Read the producer config's own description: *"the
broker is informed that the client is participating in two phase commit protocol
and **transactions that this client starts never expire**."* The 60-second bound
on how long a `read_committed` consumer can be blocked is gone. A prepared
transaction whose external coordinator dies blocks that partition **until a human
intervenes**.

*What that costs.* It is precisely `two-phase-commit`'s blocking problem,
deliberately re-admitted. §10.4's whole argument was that Kafka avoids blocking
because the coordinator's state is a replicated log and the waiting is bounded.
Using KIP-939 means you are now the coordinator, and you must supply the durable,
replicated, recoverable decision log yourself. Flink can do this because its
checkpoint coordinator already is one. A Spring Boot service is not.

*Recommendation: no.* In terms a non-specialist accepts: *"This lets us hand Kafka
a promise and decide later whether to keep it. If our service dies while holding
that promise, Kafka waits for us — with no timeout — and every other reader of
that topic waits too, until somebody notices and clears it by hand. We would be
taking on the job of a transaction manager to solve a problem the outbox pattern
already solves without it."*

Do this instead: write the state change and the outgoing event in one **database**
transaction (`transactional-outbox`), and relay the outbox to Kafka. You get
at-least-once into Kafka, which is what consumers should assume anyway.

**8.4 GDPR erasure on transactional topics.**

*What "delete" cannot mean.* Kafka deletes by **whole segments**, never by record.
There is no API that removes one record, and this is not an oversight — it is the
append-only log that everything else depends on. Aborted records are in exactly the
same position: they are on disk, replicated, and not going anywhere before their
segment does. "Delete the merchant's data" is not an operation Kafka offers.

*What it can mean, in increasing order of honesty:*

1. **Wait for retention.** Only defensible if retention is short and you can prove
   every copy — including tiered storage, mirrored clusters, and every downstream
   sink — expires on the same schedule. Usually you cannot.
2. **Compacted topic plus a tombstone.** Works only if the topic is keyed by
   something merchant-scoped, and only *eventually* — compaction runs on closed
   segments past `min.cleanable.dirty.ratio`. Aborted records with the same key
   are cleaned the same way, but "eventually" is not a compliance answer on its
   own.
3. **Crypto-shredding.** Encrypt each merchant's payload under a per-merchant key
   held outside Kafka; erasure is deleting the key. The bytes remain and become
   permanently unreadable. This is the standard answer, and it works uniformly for
   committed and aborted records because it never depended on deletion.
4. **Keep the personal data out of Kafka.** Put a merchant id on the topic and the
   personal data in a database that supports `DELETE`. Erasure becomes a database
   operation and the log holds a dangling reference, which is fine.

*Design:* (4) where the schema allows it, (3) everywhere else, with (2) as
housekeeping rather than as the compliance mechanism. And a rule going forward:
**personal data in an append-only log is a design decision, not a storage detail**
— decide it when the topic is created, because it cannot be undone afterwards.

**8.5 Counterfactual — no last stable offset.**

*Smallest breaking situation.* A consumer is served offset 810 the instant it is
written. It acts on it — pays TeaHouse ₹1,960. Two seconds later the transaction
aborts. The money is gone and no filter can help, because the consumer acted before
the outcome existed.

*What every consumer would have to implement.* To be safe it would have to buffer
every record from an open transaction until it learned the outcome, which means:
maintaining a connection to coordinator state; holding unbounded memory for
long-running transactions; persisting that buffer so a restart does not lose it or
double-apply it; and reconciling with the coordinator after any disconnection.
Every consumer, in every language, on every topic that has ever had a transactional
producer. And the decision would be a **distributed read** on the consuming path,
so the coordinator becomes a throughput dependency for every reader in the cluster.

*What the LSO actually buys.* It puts the decision **inside the partition**, so a
reader needs no extra lookup, no coordinator connection, no memory, and no
persistence — and it makes it structurally impossible to act early, because the
data beyond the LSO is never sent. The cost is head-of-line blocking: one undecided
record holds up everything behind it. That is the trade, and it is the same trade
as §10.4 — uncertainty was moved to the reader, and then made cheap by putting the
answer where the reader already is.

**8.6 Counterfactual — no per-transaction epoch bump.**

*Smallest wrong result* (this is the real pre-4.0 protocol):

```
T1, pid 4711 epoch 0: produce record R to partition P. The request stalls.
T1 aborts.               ← client-initiated abort, epoch NOT bumped
T2, pid 4711 epoch 0: produce record S to partition P.
R finally arrives: pid 4711, epoch 0, partition P has an open transaction
                   for pid 4711, epoch 0 → appended, joins T2.
T2 commits → one marker decides both S and R.
```

R was explicitly aborted and is now durably committed.

*Why "reject requests older than N seconds" cannot be the fix.* Three reasons,
each sufficient:

1. **There is no trustworthy age on the request.** A broker would have to believe a
   timestamp set by the client, on a different machine, with a different clock —
   and `clock-synchronization` is a lab about why that fails.
2. **Network delay has no bound you may assume.** Any N is either too small
   (rejecting valid slow requests, turning a latency blip into data loss) or too
   large (leaving a window). This is the same reason the two-generals problem has
   no timing-based solution.
3. **It answers the wrong question.** Age is a proxy. The actual question is *which
   transaction does this record belong to*, and until something in the record
   answers it, no amount of filtering on age can.

The correct fix is to make the record carry its own answer: bump the epoch on every
commit and abort so that `(pid, epoch)` identifies **a transaction** rather than a
producer. A late record then carries a stale epoch and is fenced by machinery that
already existed and was already trusted — which is why KIP-890 phase 2 is a small
change that closes the problem completely, rather than a heuristic that narrows it.
