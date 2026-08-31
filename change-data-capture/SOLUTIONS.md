# Change Data Capture — solutions

Rungs 7 and 8 have no single right answer; what follows there is a worked
design discussion of the shape a good answer takes.

---

## Rung 1 — Explain

**1.1** Every database already writes down every change it makes, in order, in
a log — it has to, or it could not survive a power cut. Change data capture is
reading that log and turning each entry into a message other systems can
subscribe to. So instead of every consumer asking the database "what does this
table look like now?", they each get a stream of "here is what changed, in the
order it actually happened". Nothing in the application has to be modified, and
nothing gets missed, because the log was already complete for the database's own
reasons.

**1.2** Because the database's own survival depends on that log being complete
and correctly ordered — you are tapping a record whose correctness is already
load-bearing for something the vendor cares about far more than they care about
your search index.

**1.3** In review order, worst first:
1. **It cannot see deletes.** A `SELECT` returns rows that exist; a deleted row
   is not a row. Anything the query-based feed populates keeps deleted records
   forever.
2. **It silently skips late-committing transactions.** The application stamps
   `updated_at` at write time; the row becomes visible at commit time; a cursor
   over write-time values skips anything whose commit lands after the cursor has
   passed its stamp. No error is raised.
3. **It only ever sees the latest value.** Two changes inside one poll interval
   collapse into one row read, so anything reacting to a *transition* — a
   loyalty rule waiting for `PAID`, a fraud rule watching paid→refunded→paid —
   is blind by construction, and polling faster shrinks the window without ever
   closing it.

(A fourth, if they push back on all three: it is either lossy or duplicating at
timestamp ties, so they need idempotent consumers anyway — at which point one of
the stated advantages of polling has evaporated.)

---

## Rung 2 — Identify

**2.1** The database holds **4** orders; the poller's index holds **5**; they
disagree on **3** rows.

| order | database | poller's index | which failure |
|---|---|---|---|
| 7 | PAID | PENDING | the high-water mark skipped the transaction that started at t=1 and committed at t=9 |
| 91 | *deleted* | still present | deletes are invisible to a query |
| 104 | PAID | PAID ✅ | correct — but two intermediate states (PAID, REFUNDED) were never seen |
| 118 | PAID | PAID ✅ | correct |
| 132 | PAID | PENDING | the same-second tie: stamped 10, poll at t=10 moved the mark to 10, and 10 is not `> 10` |

Five distinct failures, three visibly wrong rows, and **zero** errors raised.

**2.2** "Read up to" is where the log reader has actually got to; "last told the
database" is the position it has **confirmed**, which is what the database uses
to decide what log it may free. Confirming costs a round trip, so connectors
batch it, which means the two numbers are always slightly apart. That gap is the
**duplicate window**: crash anywhere inside it and everything in the gap is
delivered again. It cannot be closed — confirming *before* emitting would
convert duplicates into losses, which is strictly worse.

**2.3** The buffers hold the decoded row changes of every transaction that has
been seen but not yet committed — for the rollback scenario, `buffer[90212]`
holds the cancellation of order 91 and the refund insert. The rule: they are
published **if and only if** a `COMMIT` record for that transaction id arrives;
an `ABORT` discards them and nothing is emitted.

**2.4** Zero (or near it). With writes landing on the captured table, the
connector is emitting events, so every flush confirms a new position and the
database frees the log behind it. Retention only grows when the confirmed
position stops moving.

---

## Rung 3 — Predict

**3.1** **Neither.** The poll at 10:00:05 cannot see the row (uncommitted) and
advances its high-water mark to 10:00:05 anyway. When the row appears at
10:00:09 it carries `updated_at = 10:00:00`, and the poll at 10:00:10 asks for
anything later than 10:00:05. Since the mark only moves forward, no future poll
will ever return it either.

**3.2** The loyalty service receives **one** event from the poller (the final
`PAID`) and should have received **three** changes, of which **two** were `PAID`
transitions worth acting on. Worse: had the sequence ended on `REFUNDED`, the
poller would have delivered exactly one event and the loyalty service would have
awarded nothing at all, despite a real payment having happened.

**3.3** **T2's, because it committed first.** v2 emits at commit, so the order it
produces is commit order — which is the order the database actually made those
changes real, and therefore the only order a consumer can safely reason about.
Start order is not a thing any observer of the database can see or should care
about.

**3.4**
```json
"before": {"id": 91, "customer": null, "status": null, "total_paise": null}
```
Only the primary key. `REPLICA IDENTITY DEFAULT` puts just the key columns in
the log's old-tuple record, and the `null`s mean *not captured*, not *was null* —
a distinction the payload has no way to express.

**3.5** At 12 MB/s the retained log grows about 1 GB every 90 seconds, so 400 GB
is roughly **10 hours** — which means it breaks at about **04:00 on Saturday**,
not on Monday morning when someone would have looked. What breaks is not the
search index: it is the **source database**, which runs out of `pg_wal` and
stops accepting writes. The shop goes offline because a search-index feeder was
paused for maintenance.

---

## Rung 4 — Modify

**4.1** Faster polling catches more of the rapid changes but never all of them —
two changes can always land inside any interval, however small. Running the five
failures at a 20-second interval makes the *intermediate-state* loss worse and
leaves the *delete* and *high-water skip* failures **exactly as bad**, because
neither has anything to do with how often you poll.

That split is the answer: interval is a **tuning** dial for one failure and
irrelevant to the others. Losing deletes and skipping late commits are
**design** properties of querying state through a forward-only cursor, and no
setting removes them. This is the difference between "we should tune this" and
"we should replace this", and it is the single most useful thing to be able to
say in the design review.

**4.2** An upsert keyed by order id is a **blind overwrite of the whole value**:
applying it twice lands the row in the same state as applying it once, so
duplicates are free. A running total is an **accumulation**: it reads its own
previous result and adds to it, so a duplicate is permanently baked in.

> The rule: **CDC is at-least-once, so keyed upserts are safe by construction
> and anything accumulative needs an explicit idempotency mechanism** — store
> the applied position, in the same transaction as the effect, and skip anything
> not strictly greater.

**4.3** That correctness in a CDC pipeline is a property of **the connector and
the consumer together**, never of the connector alone. Protocol C is correct
precisely *because* it produces a deliberate overlap instead of a gap — and an
overlap is only harmless if the apply function is idempotent. A vendor can
guarantee you at-least-once delivery in commit order; only you can guarantee
that applying an event twice is the same as applying it once.

**4.4** The number that changes is **retained WAL** (in PostgreSQL,
`pg_current_wal_lsn() − confirmed_flush_lsn`). No connector-side metric catches
it because the connector is genuinely fine: it is connected, it is decoding, its
lag is zero, its error count is zero. It confirms its position when it *emits*
something, and it has nothing to emit, so the position never moves while the
rest of the database's traffic races ahead.

The property lost is **liveness**, not safety: nothing wrong is ever produced —
no event is lost, no event is invented — the system simply stops making the
progress that keeps the source alive. This is why the alarm has to be on the
*backlog*, not on the activity. The dangerous state here is a quiet one, and a
quiet state cannot be detected by watching for noise.

**4.5** Need `FULL`: a warehouse that reverses an aggregate on delete
(`revenue -= before.total_paise` is nonsense if `before.total_paise` is null);
an audit log recording what a row used to say; a change-detection rule like
"alert when status goes PAID → REFUNDED"; anything computing a diff. Happy with
`DEFAULT`: a search index (it only needs the after-image, and for a delete it
only needs the key); a cache (same); a projection that overwrites by key; a
simple "this key exists / does not exist" consumer.

`FULL` costs WAL volume: every `UPDATE` and `DELETE` now writes the **entire
previous row** to the log. On a wide table with hot updates that is a real,
measurable increase in write I/O — and in what the replication slot retains,
which makes the F1 disk problem arrive sooner. Decide per table and write down
why.

---

## Rung 5 — The trace

**Phase 1 — snapshot** (repeatable read, taken as of `16/C000000`, blocking
nobody). Every row is read as it stood at 10:00:00, so concurrent commits are
invisible to it:

| time | event | key | payload | position |
|---|---|---|---|---|
| 10:00:01 | `r` | 7 | `{id:7, customer:asha, status:PENDING, total:48000}` | `16/C000000` |
| 10:00:02 | `r` | 91 | `{id:91, customer:ben, status:PENDING, total:12500}` | `16/C000000` |
| 10:00:03 | `r` | 104 | `{id:104, customer:cara, status:PENDING, total:30000}` | `16/C000000` |

Order 104 reads **PENDING**, even though the transaction changing it committed
at 10:00:03 — the snapshot is as of 10:00:00 and does not move.

**Phase 2 — streaming from `16/C000000`, in commit-position order.** The three
commits are `16/C000100`, `16/C000200`, `16/C000300`; note that the transaction
which **started first** (10:00:00, order 104) has the **highest** position,
because the position is stamped when the commit happens:

| time | event | key | payload | position |
|---|---|---|---|---|
| 10:00:04 | `u` | 7 | `before {status:PENDING}` → `after {status:PAID}` | `16/C000100` |
| 10:00:05 | `d` | 91 | `before {id:91, customer:null, status:null, total:null}` | `16/C000200` |
| 10:00:06 | `u` | 104 | `before {status:PENDING}` → `after {status:PAID}` | `16/C000300` |

**Phase 3 — crash and restart.** Killed at 10:00:07 with last confirmed
`16/C000100`; resumes at 10:00:09 from there:

| time | event | key | payload | position |
|---|---|---|---|---|
| 10:00:10 | `d` | 91 | (identical to above) | `16/C000200` |
| 10:00:11 | `u` | 104 | (identical to above) | `16/C000300` |

**Eight events for five real facts.** Final search index: `7 → PAID`,
`91 → absent`, `104 → PAID`. The database holds exactly the same, so the index
is **correct**.

Three things this trace is designed to make you notice:

1. The snapshot's value for order 104 is stale *by design*, and it is the
   stream that repairs it. The two pieces are only correct **together**.
2. Commit order is not start order. A connector that emitted in start order
   would have delivered 104 first, and any consumer trying to reconstruct
   causality from the stream would have been misled.
3. The two duplicate events were harmless, and would not have been if the
   consumer had been counting rather than overwriting. Notice also that the
   delete's before-image is mostly `null` — with `REPLICA IDENTITY DEFAULT`,
   an audit consumer would have recorded that Ben's order previously had no
   customer and no total.

---

## Rung 6 — Spot the flaw

**6.1 The "safe" poller.** The guess is the bug. At 02:00:00 a nightly job
begins, stamps 1,200 rows `updated_at = 02:00:00`, waits on a lock, and commits
at **02:00:45**. Polls run every 5 s with a 30 s lag:

- 02:00:35 → window `(hw, 02:00:05]`. The rows are uncommitted and invisible.
  `hw := 02:00:05`.
- 02:00:40 → window `(02:00:05, 02:00:10]`. Still invisible. `hw := 02:00:10`.
- 02:00:45 → the transaction commits; 1,200 rows appear carrying `02:00:00`.
- 02:00:50 → window `(02:00:15, 02:00:20]`. `02:00:00` is below the low end.
  Skipped, and skipped by every future poll.

**1,200 rows lost, silently**, and the lag bought nothing except 30 seconds of
permanent latency on every event. Violated: completeness. The deeper point is
that the fix substituted an *assumption about transaction duration* for a
*guarantee*, and the day the assumption breaks is exactly the day a big data
change was happening. The only correct version of this idea reads the
database's own in-flight transaction state (`pg_snapshot_xmin`) and refuses to
advance past it — at which point you are reading database internals to make
polling safe, and should read the log instead.

**6.2 The optimized outbox relay.** Transaction A does its business write plus
`INSERT INTO outbox` at 10:00:00, receiving `id = 5000`, then does slow work and
commits at 10:00:09. Transactions B…Z insert ids 5001–5100 and commit
immediately. The relay drains up to 5100 and sets `last_id = 5100`. At 10:00:09
row 5000 becomes visible, and `WHERE id > 5100` will never return it again. The
`OrderPaid` event is **lost forever**, and nothing notices, because nothing looks
at `processed` any more.

**Why the original was safe:** it tracked **per-row state**, not a position. A
row committed late simply shows up later, still `processed = false`, and gets
sent. That is the general lesson, and it is worth carrying:

> The high-water-mark bug is not a bug about **polling**. It is a bug about
> **cursors over write-time values.** Polling is safe exactly when it claims
> each row individually instead of remembering a position.

The optimization traded a guarantee for an index scan. (Bonus flaw in *both*
versions: `ORDER BY id` is not commit order either, so an outbox relay does not
preserve commit ordering — which usually does not matter, and matters
enormously the once it does.)

**6.3 The warehouse loader.** Two bugs.

*Bug one — the two statements.* `INSERT` and `UPDATE checkpoint` are two acts
with a crash window between them. This is the **dual-write problem rebuilt
inside the consumer**, at the exact point the whole pattern was supposed to
solve it. Crash between them and the fact row exists while the checkpoint does
not, so on restart the event is re-applied and revenue is double-counted; swap
the order and a crash loses the fact row entirely. The fix is the shape from the
[idempotency](../idempotency/) lab: both statements in **one transaction**, so
the checkpoint moves if and only if the effect landed.

*Bug two — the dedup claim.* "We can deduplicate on `lsn` later" is false,
because **an LSN is not unique per event**. Every row change in one transaction
carries the same commit position — a transaction touching 40 rows produces 40
events with one LSN — so deduplicating on `lsn` would delete 39 real facts. The
unique identity of an event is `(lsn, table, primary key)`, or the position of
the event within its transaction. And "later" is doing unearned work: every
report run between the duplicate landing and the cleanup is wrong, and in
practice the cleanup is written the week after finance notices.

**6.4 The audit log.** `REPLICA IDENTITY DEFAULT` means the `before` image
contains the primary key and nothing else. The integration tests pass because
they assert that an audit **row appears**, and one does — full of `null`s.

Two years later the auditor asks what order 91 said before it was cancelled, and
the audit table answers: customer `null`, status `null`, total `null`. It cannot
be distinguished from a genuinely empty row, so the audit trail is not merely
incomplete, it is **confidently wrong** — the worst possible property for a
compliance artifact. Violated: nothing the pipeline promised, which is the
point; CDC never promised a complete before-image, and the design assumed one.

Fix: `ALTER TABLE ... REPLICA IDENTITY FULL` on every audited table, accept the
WAL cost, and add a **startup assertion** that fails loudly if any table in the
publication has `relreplident <> 'f'`. Configuration this consequential should
be checked by the program, not by a person remembering.

**6.5 The event contract.** The rename produces events whose `after` object no
longer has a `total_paise` field. Team B's consumer either throws on
deserialization (the good outcome — someone gets paged) or, far more likely with
schemaless JSON, silently reads `undefined` and writes **zero** into the
warehouse. Revenue for that day is ₹0 and the pipeline is green.

Whose fault: not Team A's as individuals. They ran their full test suite; their
test suite contains nothing about Team B because Team B is not in their repo and
they were never told Team B exists. The fault is **architectural** — CDC on a
table makes that table's schema a public API, for consumers the table's owner
does not know about, with no contract, no version, and no test.

What should have been built: an **outbox** owned by Team A with an explicit,
versioned event schema — the row that says `OrderCancelledByCustomer`, not the
row diff that says `status: 'CANCELLED'` — drained by CDC via Debezium's outbox
event router, so Team A keeps the log-based relay and Team B gets a contract.
Failing that, at minimum: a schema registry with compatibility enforcement, plus
a consumer-driven contract test that runs in **Team A's** CI, so the deploy
fails on Tuesday morning instead of finance failing on Friday.

---

## Rung 7 — Handle the failure (worked discussion)

**7.1 Failover.**

*(a) What is lost.* Every change to captured tables committed between the moment
the old primary stopped and the moment the new connector's fresh slot began
streaming. You can **size** it — write rate to captured tables × that duration —
but you cannot **enumerate** it from anything the pipeline holds, because the
evidence was in a log that no slot was pinning. Enumeration requires comparing
source and sink directly.

*(b) Why nothing fires.* Every metric is green and every metric is telling the
truth. The connector is running, the new slot is active, retained WAL is zero,
and lag is milliseconds — because it is streaming from *now*, promptly. This is
the "green on empty" trap: the pipeline is perfectly healthy and it skipped a
window. No liveness metric can see a gap in history.

*(c) Three design changes.*
1. **Never let the connector silently create a slot.** Make slot absence a hard
   failure; slot creation should be a deliberate operational act. A connector
   that self-heals into a lossy state is worse than one that stops.
2. **Make the connector's endpoint stable and its start-up loud.** Log and emit
   an event on every slot creation and every snapshot start, and treat both as
   alertable, not informational.
3. **Run scheduled reconciliation** — counts and checksums, source against sink,
   per key range, hourly or nightly. This is the only thing that converts an
   unbounded silent divergence into a bounded detection time. The
   [eventual-consistency](../eventual-consistency/) lab's rule applies exactly:
   the machinery's death produces no event, so you must probe.

*(d) The one alert.* "A replication slot was created" — or equivalently, the
count of slots on this database changed. It fires within seconds of the actual
mistake, unlike reconciliation which fires within an hour. If you can only have
one, have this one, and let reconciliation be the backstop.

**7.2 The unavoidable slot.** State the trilemma explicitly, because the whole
answer is choosing which leg to break: you cannot simultaneously have source
availability, connector resumability, and no operator involvement.

The arrangement I would run:

- **Set `max_slot_wal_keep_size`** to comfortably survive the known 20-minute
  outage at *peak* write rate — 20 min × peak MB/s, times a safety factor of
  about 2 — and confirm that number fits inside the volume with room to spare.
  This is choosing to sacrifice **resumability** in the tail, not availability.
- **Alarm on retained bytes at ~50% of that ceiling**, and separately on
  `active = false` for more than a few minutes. The first is your warning, the
  second is your cause.
- **Runbook, in order:** restart the connector; if it comes back before the
  ceiling, nothing else is needed. If the slot was invalidated, do **not** kick
  off a blind full re-snapshot — trigger an incremental snapshot (7.3).
- **Narrow the publication** to the smallest set of tables that anyone actually
  consumes; every extra table is extra WAL volume and extra retained bytes.
- **Turn heartbeats on regardless**, because the quiet-table failure will
  otherwise produce this same incident from a perfectly healthy connector.

The sacrifice, stated plainly so nobody is surprised at 3 a.m.: **on an outage
longer than the budget, we lose the connector's position and re-sync.** That is
a written, priced decision, not an accident.

**7.3 The re-snapshot you cannot afford.** The feature is the **incremental
snapshot** (Debezium's implementation of the Netflix **DBLog** watermark
approach). It chunks the table and, for each chunk, writes a low watermark to
the log, reads the chunk, writes a high watermark, then reconciles the chunk
against the log events that landed between them.

Why that solves *this* problem specifically: it runs **concurrently with
streaming**, so the connector keeps consuming and keeps advancing its confirmed
position throughout the eleven hours, instead of freezing it. Retained WAL stays
bounded by the streaming lag rather than by the snapshot duration. It is also
pausable and resumable, and can be triggered for one table via the signal table,
so a botched attempt does not cost you another eleven hours.

Supporting moves: take the snapshot's reads from a **read replica** if your
tooling supports it, so the 4 TB scan does not compete with production traffic;
and check whether you need all 4 TB in the sink at all before you copy it.

---

## Rung 8 — Design (worked discussion)

**8.1 The read-model pipeline.**

*Capture.* `orders`, `order_lines`, `payments`. Refuse `users` unless somebody
names a consumer for it — every captured table is permanent WAL volume, a
permanent schema-coupling surface, and, for a table holding personal data, a
GDPR question you have now created for the data lake.

*Replica identity.* `orders` → **FULL**, because the warehouse reverses
aggregates on delete and the fraud consumer needs `before.status` to see
transitions. `order_lines` → `DEFAULT` (search and cache only need after-images).
`payments` → `DEFAULT`; it is append-only, so there is no before-image to want.
Write the reason next to each setting in the repo, because the default silently
decides this for you in the direction that produces plausible wrong data.

*Topics and keys.* One topic per table, **keyed by primary key**, compacted.
Keying by PK buys per-key ordering for free and makes the natural consumer
operation an upsert. Compaction means a consumer can be rebuilt by replaying the
topic from the beginning without touching the source database — which is the
property that makes 8.1's "rebuild on a Tuesday" answer possible at all.

*Apply strategy per consumer:*

| consumer | apply | needs an explicit idempotency mechanism? |
|---|---|---|
| Elasticsearch | `PUT /orders/{id}` — whole-document upsert | **No.** Naturally idempotent. |
| Redis cache | `SET order:{id}` | **No.** Same. |
| BigQuery warehouse | land raw into an append-only staging table, then `MERGE` into the target keyed by `order_id` keeping the highest LSN | **Yes** — and this is where it lives. Idempotency at the *merge*, not at the ingest, so the staging table can hold duplicates harmlessly. |
| Loyalty service | awards points — an accumulating, externally-visible effect | **Yes, and the strictest one.** Claim `(order_id, event_lsn)` in the same transaction as awarding the points; skip anything already claimed. This is the idempotency lab's one-transaction shape, unchanged. |

*Rebuilds.* Delete the index/table and replay the compacted topic from offset 0.
No source-database involvement, no re-snapshot, no slot pressure. The warehouse
rebuilds by truncating the target and re-running the `MERGE` over staging. This
is the [cqrs](../cqrs/) lab's "disposable read models" claim finally backed by a
mechanism.

*Monitoring, with the failure each catches:*

| alert | threshold | catches |
|---|---|---|
| slot retained bytes | absolute + growth rate | connector down (F1) **and** the quiet-table trap (F2), which no connector metric sees |
| slot `active = false` | > 5 min | connector down, early |
| `MilliSecondsBehindSource` | p99 ceiling | ordinary lag; spikes on big batch transactions |
| connector restart count | any sustained rate | duplicate windows (F5) |
| slot **count changed** | any | the failover trap (7.1) |
| source↔sink reconciliation | any divergence | F6 and every silent-loss mode |
| consumer group lag, per consumer | per consumer | a slow consumer, which is not a slow connector and is constantly confused with one |

*What goes to the loyalty service.* **Not the CDC stream.** They are another
team, they need *intent* (`OrderCancelledByCustomer` vs
`OrderCancelledByFraudRule` — five different business events produce the
identical row diff `status: 'CANCELLED'`), and pointing them at our tables makes
our column names their API. Kirana writes an **outbox** table inside the same
transaction as the order change, and Debezium's **outbox event router** drains
it to a versioned `kirana.orders.events` topic. We keep log-based delivery; they
get a contract. Everything else in this design — search, cache, warehouse — is
our own derived data and is exactly what raw CDC is *for*.

**8.2 The strangler.**

*Initial load.* Incremental snapshot (7.3), not a big-bang copy: 2 TB with a
frozen slot is the incident from F1 waiting to happen.

*Loop prevention* — the part everyone gets wrong. Without it, a row synced
A→B produces a CDC event on B, which syncs back to A, forever. Three real
mechanisms:
1. **Origin filtering**, the proper answer. PostgreSQL tracks a replication
   origin per applied change and can be told not to forward changes that did not
   originate locally (`pg_replication_origin`, and in PostgreSQL 16+
   `CREATE SUBSCRIPTION ... WITH (origin = none)`). MySQL's GTID scheme does the
   equivalent: a server ignores events already carrying its own server UUID.
   *(Documentation-sourced; I have not run either.)*
2. **Filter by the writing identity** — the sync process uses a dedicated
   database user or `application_name`, and each connector drops events whose
   `source` says they came from the other sync. Crude, portable, and it works.
3. **A `written_by` column.** Explicit, visible in the data, and the one most
   likely to be forgotten by exactly one code path.

Prefer (1); use (2) as a belt-and-braces filter regardless, because the cost of
(1) failing quietly is an infinite event loop between two production databases.

*Same-second writes to both sides.* The honest answer is: **do not have this
case.** Conflict resolution by timestamp is last-write-wins, and the
[eventual-consistency](../eventual-consistency/) lab already showed what that
means — the clock becomes the resolver, and the resolver decides which write
silently dies. Instead **partition authority**: every order has exactly one
authoritative side, decided by a rule that needs no coordination (e.g. orders
created on or after the cutover date belong to the new service; everything older
belongs to the monolith), and the non-authoritative side's copy is read-only.
Field-level split — the monolith owns billing fields, the new service owns
fulfilment fields — is workable but doubles the reasoning and should be a last
resort. Where a genuine conflict remains, log it to a manual queue rather than
resolving it silently; a small number of human decisions beats an unknown number
of silent wrong ones.

*Cutover.* Move authority a slice at a time, not all at once: flip one order
type, or one region, watch reconciliation for a week, then the next. Keep the
reverse sync running long after you think you need it — the thing that ends a
strangler badly is discovering on day 400 that one nightly report still reads
the monolith.

*The hourly proof.* Per key range: row counts plus a checksum of a stable field
set (`sum(total_paise)`, `count(*)`, `max(updated_at)` per bucket). And obey the
falsification discipline before you believe a green:

> **Prove the comparison set is non-empty and covers the whole key space
> first.** A reconciliation that silently compared zero rows — wrong date
> filter, wrong shard, an empty ranges table — reports perfect agreement. A pass
> over an empty dataset is the most dangerous green there is, and it is exactly
> what an eighteen-month migration will eventually produce.

Also assert both directions: "every row in A is in B" and "every row in B is in
A". One-directional reconciliation cannot see invented rows, which is what a
loop-prevention bug produces.

**8.3 The verdict paragraph.** Something close to:

> For your own derived stores this is exactly right, and I'd encourage it —
> search indexes, caches, read models, the warehouse, anything where you own
> both ends and what you want is "what do the rows say now". That is what CDC is
> for, and it costs the source team nothing.
>
> Across team boundaries it is a trap, for two specific reasons. First, a row
> diff carries no intent: `status: 'CANCELLED'` is produced identically by a
> customer cancelling, a payment declining, a fraud rule firing, and a batch job
> clearing stale carts — and consuming teams almost always need to tell those
> apart. Second, and worse, it makes our table schema a public API for consumers
> we don't know exist. The next routine column rename becomes an incident in
> three other teams, our test suite will pass, and we will find out from finance.
>
> The alternative costs us about a day: we write an outbox table inside the same
> transaction as the change, with explicit versioned business events, and point
> Debezium at *that*. You get the same log-based latency and the same delivery
> guarantees, and you get a contract we can actually keep. Happy to set up the
> first one this week if you tell me which events you need.
