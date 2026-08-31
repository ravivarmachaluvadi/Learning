# Solutions — Apache Kafka

Attempt [`EXERCISES.md`](EXERCISES.md) first. Where a question has no single right
answer (rungs 7 and 8), what follows is a worked reasoning process and the
tradeoffs a reviewer would expect you to name — not the only acceptable answer.

---

## Rung 1 — Explain

**1.1** Kafka gave up the broker's knowledge of who has handled what. A classic
broker tracks every message's delivery state per consumer and deletes it once
acknowledged; that bookkeeping is the service it sells. Kafka refuses to do it:
it appends facts to a file in order, keeps them for a fixed time, and makes each
reader remember its own position. What it buys with that refusal is everything
that per-message state made impossible — any number of independent audiences
reading the same records without copying them, a new audience able to start from
the beginning of history, no redelivery and therefore no reordering, and a broker
whose per-reader cost is one integer instead of one record of state per message
per consumer. The price is real and is paid by you: nothing tells you a record was
processed, retention deletes data whether or not anyone read it, and "retry just
this one message" is not an operation the broker offers.

**1.2** Two consumer groups on one topic. Produce one record; both groups receive
it. In a queue, one consumer takes the message and the other never sees it. No
amount of throughput turns one into the other — it is a different answer to "what
does consuming mean".

**1.3** RabbitMQ is a post office: it holds a letter until someone collects it,
then it is gone. Kafka is a newspaper archive: every day's edition is filed on a
shelf for a fixed period, and anyone can walk in and read from any date. A new
team is a new reader walking into the archive — the shelves do not care how many
readers there are, and nothing they read is removed for anyone else.

**1.4** If reading does not consume, the broker cannot mark anything as handled,
so it cannot hold per-message state per reader. If each reader has its own
position, the reader's progress must be represented somehow. If the bookkeeping
must be tiny regardless of how many messages exist, that representation cannot
grow with the message count. The only thing that satisfies all three is a single
position in an ordered sequence — which is why the records must be *ordered and
addressable by position*, i.e. a log with offsets. Any per-message representation
(a set of handled ids, a bitmap) grows with the backlog and fails the third
requirement.

**1.5** A **committed record** is a broker-side durability fact: it has been
replicated to every in-sync replica, so it is below the high watermark and
consumers may read it. A **committed offset** is a consumer-side progress fact:
this group has written "next read starts here" into `__consumer_offsets`. They
involve different actors, different topics and different failure modes. A record
can be committed and never read; an offset can be committed for records that were
never successfully processed (§10.8).

---

## Rung 2 — Identify

**2.1** All four land in the same partition — the one the routing table shows for
`P-1001` — because the partition is `hash(key) % partition-count` and both inputs
are unchanged. The only things that would move them are a different key or a
different partition count. (Deleting and recreating the topic with a different
partition count is the same thing wearing a disguise.)

**2.2** notifications has read up to 2 records per partition, analytics 1 per
partition. The broker deleted **nothing**, and stores exactly **one** copy of each
record per replica — not one per group. That is the whole economic argument for
the log: a new audience costs read bandwidth, not storage and not write
amplification.

**2.3** State `pending`; high watermark **0**; a consumer polling right now sees
**nothing**. The record is on the leader's disk and is still invisible and
unacknowledged, because "written on the leader" is not "committed".

**2.4** B3 is dropped at the **second** round (the simulator's stand-in for
`replica.lag.time.max.ms`). Afterwards the high watermark is the minimum LEO over
the *remaining* ISR members only — which is why it can suddenly leap forward at
the moment a lagging replica is ejected. That leap is worth internalising: **the
ISR shrinking makes the system look faster**, which is exactly why §11.3 is silent.

**2.5** "Partition-seconds stopped" differs; the final assignment is the same
under both protocols (they agree on *where* partitions end up, not on how much of
the group stops on the way). On a dashboard it is the area under the consumer-lag
spike: partitions that stopped × how long they stopped.

**2.6** Only the records in **closed** segments are eligible, and only where a
later record for the same key exists. So the last P-1001 record survives (nothing
supersedes it), everything in the active segment survives regardless of policy,
and the surviving record from the active segment may well not be the latest value
for its key — it is simply untouchable. Compaction is eventual; a consumer sees
duplicates.

---

## Rung 3 — Predict

**3.1** The three `A` records are always processed **in their produced order**:
same key → same partition → one log → one consumer in the group. `A` and `B` have
**no defined order** unless they happen to hash to the same partition; two
consumers read two partitions concurrently and either can win. This is the whole
of Kafka's ordering guarantee in one exercise.

**3.2** During the first restart: nothing visible. ISR drops to 2, which still
satisfies `min.insync.replicas=2`, so writes succeed. During the second restart
before the first has caught up: ISR drops to 1, below the minimum, and every
produce fails with `NOT_ENOUGH_REPLICAS` (a retriable error — producers retry
until `delivery.timeout.ms`, so a short overlap is absorbed and a long one becomes
a produce outage). This is the rolling-restart rule: **wait for
`UnderReplicatedPartitions` to reach zero between brokers**, or you will take the
outage that the setting exists to warn you about.

**3.3** Sixteen rebalances — one when each consumer leaves, one when each returns.
Under eager, every rebalance stops all 8 partitions: 16 × 8 × 3 s ≈ **384
partition-seconds** of stopped processing to restart 8 processes. With
`CooperativeStickyAssignor` only the moved partitions stop (roughly 1–2 per
event), and with **static membership** (`group.instance.id`) plus a restart inside
`session.timeout.ms`, the count is **zero rebalances**. That is the entire
business case for both features.

**3.4** The trap: with the standard loop — `poll()`, then process the whole batch,
then `poll()` again — auto-commit does **not** fire during the 20 s of processing,
because it only runs inside `poll()`. So at the crash at second 12 nothing has
been committed, all 500 records are reprocessed, and **nothing is skipped**. Under
that loop shape auto-commit behaves like a coarse commit-after-processing.

The at-most-once window appears the moment processing is *not* inline: hand the
batch to an executor and return to `poll()` promptly, and the next poll commits
offsets for records still sitting in your queue — crash then and they are skipped
silently. Auto-commit is not dangerous because of what it does; it is dangerous
because what it does depends on the shape of code far away from the config.

**3.5** Friday evening lag is 3 h and rising, because nothing consumes over the
weekend: by Monday it is roughly the whole weekend plus 3 h. Retention is 1 day, so
from Saturday evening the group's committed offset points at deleted data. On
Monday's restart the fetch returns `OFFSET_OUT_OF_RANGE`, `auto.offset.reset=latest`
jumps to the end, and lag **falls vertically to ~0**. The graph is a ramp, then a
cliff. A dashboard-watcher concludes "it caught up in seconds — Kafka is
amazing". Roughly two days of records were never processed and no metric ever
said so.

**3.6** The `read_uncommitted` consumer sees the records **immediately**, including
ones that will later be aborted — it is reading uncommitted data by definition.
The `read_committed` consumer sees **nothing at or after the transaction's first
offset**: its read limit is the LSO, which is pinned by the open transaction. At
`transaction.timeout.ms` (default 60 s) the coordinator aborts the transaction,
writes abort markers, the LSO advances past them, and the `read_committed`
consumer resumes — having skipped the aborted records entirely. One hung producer
stalled every `read_committed` consumer of that partition for a minute.

---

## Rung 4 — Modify

**4.1** Impossible: an acknowledged `acks=all` write surviving on one replica only
and then being lost to a single disk failure (§10.4's trace). Newly possible:
producers being rejected with `NOT_ENOUGH_REPLICAS` when two brokers are down or
restarting together — a write outage. Given the choice, explain the write outage:
it is loud, bounded, and recoverable, and the customer's data is intact. Silent
loss of acknowledged data is the conversation nobody recovers from.

**4.2** Any key that is *stable under a partition-count change* — i.e. one whose
partition you compute yourself and store, rather than one derived by hashing. In
practice: assign each parcel to a partition explicitly (a custom partitioner with
a persisted mapping, or a key that encodes the partition). The cost is that you
have now built and must operate your own partition map — the routing table of
`partitioning-and-rebalancing`, with its staleness and rebalancing problems, which
is precisely the work Kafka avoided by freezing the modulo. Usually the better
answer is "over-provision partitions and never change the count".

**4.3** `max.in.flight=1` means one unacknowledged batch per connection: throughput
drops sharply (you pay a full round trip per batch), latency per batch is
unchanged, and ordering under retries is guaranteed by serialisation. What the
idempotent producer makes of it: **it makes the change pointless.** With
`enable.idempotence=true` the sequence-number check already guarantees ordering up
to 5 in flight, so setting 1 buys nothing and costs most of your throughput. It is
pre-2017 advice that survived into a lot of internal wikis.

**4.4** Latency: **worse** (up to 100 ms added before a batch is sent). Batch size:
**bigger**. Compression ratio: **better** (more similar records per block). Broker
request rate: **lower** — often the real win, since broker CPU is frequently spent
on request handling rather than bytes. Consumer lag: **slightly better** if the
broker was request-bound, unchanged otherwise. If the team wanted throughput they
got it; if they wanted lower end-to-end latency they made it worse. `linger.ms` is
the clearest throughput-vs-latency dial in the client.

**4.5** It guarantees a total order over every record in the topic, and processing
by exactly one consumer in each group, in that order. Costs, in the order they
hurt: (1) **one consumer** — your parallelism is 1 forever, so throughput is capped
by a single process, and this bites first; (2) **one broker** holds the whole topic
— its disk and NIC are the ceiling and no cluster growth helps; (3) **recovery
time** — a single partition with a year of data means one replica to re-sync, one
consumer to catch up, and no way to spread the catch-up. And the requirement is
almost always false: the business rarely needs "all events in one order", it needs
"events for one customer in one order", which is a key, not a partition count.

---

## Rung 5 — Trace

**5.1**

| step | A (leader) | B | C | ISR | HW | producer told |
|---|---|---|---|---|---|---|
| start | LEO 5 | 5 | 5 | A,B,C | 5 | — |
| 1. produce off 5 | LEO 6 | 5 | 5 | A,B,C | 5 | *waiting* |
| 2. B fetches | 6 | 6 | 5 | A,B,C | **5** | still waiting — C holds the HW back |
| 3. C frozen | 6 | 6 | 5 | A,B,C | 5 | still waiting |
| 4. 30 s pass | 6 | 6 | 5 | **A,B** | **6** | **success** for offset 5 |
| 5. produce off 6 | 7 | 6 | 5 | A,B | 6 | waiting (ISR=2 ≥ min 2, so accepted) |
| 6. B fetches | 7 | 7 | 5 | A,B | **7** | **success** for offset 6 |
| 7. A crashes | dead | 7 (leader, epoch+1) | 5 | **B** | 7 | new writes **rejected**: ISR 1 < min 2 |
| 8. C returns | dead | 7 | catches up → 7 | **B,C** | 7 | writes accepted again |

Both records exist; nothing was lost. The two things worth noticing: at step 2 the
producer is *still waiting* even though two replicas have the record — the high
watermark is the **minimum** over the ISR, not a majority; and at step 7 the topic
became read-only, which is `min.insync.replicas` choosing consistency over
availability exactly as designed.

**5.2** Cooperative reconciliation, target one partition each:

1. `C3` sends JoinGroup. The coordinator starts a rebalance; the assignor computes
   the target assignment (`C1:{p0}, C2:{p2}, C3:{p1}`) and compares it with the
   current one.
2. **Round 1 (revocation only):** `C1` is told to revoke `p1`. It runs
   `onPartitionsRevoked` — committing its offset for `p1` — and stops reading it.
   `C1` keeps reading `p0` and `C2` keeps reading `p2` throughout. No global pause.
3. `C1` re-joins, triggering a second, short rebalance round.
4. **Round 2 (assignment):** `p1` is assigned to `C3`, which calls
   `onPartitionsAssigned`, fetches the committed offset for `(group, topic, p1)`
   from `__consumer_offsets`, and starts reading from there.

The offsets for the moved partition come from the group's committed offsets — which
is exactly why the revoking consumer must commit before it lets go, and why a
rebalance during uncommitted work produces duplicates.

**5.3** First pass — only offsets 0–2 (the closed segment) are eligible:
`0:(a,1)` superseded by 5, `1:(b,1)` superseded by the tombstone at 4, `2:(a,2)`
superseded by 5. **Remaining: 3, 4, 5.**

Append `6:(d,1)`; now 3–5 is closed and 6 is active. Second pass: `3:(c,1)` — no
later `c`, kept. `4:(b,null)` — a tombstone with no later `b`; kept until
`delete.retention.ms` has passed since it was written, then removed. `5:(a,3)` —
latest `a`, kept. **Remaining: 3, 4, 5, 6.**

A consumer replaying from 0 builds `{c:1, a:3, d:1}` and, on reading the tombstone
at 4, **removes** `b` from its map (or never adds it). If it ignores null values it
will carry a phantom `b` forever — the single most common bug when materialising a
compacted topic.

**5.4** Order of events:

1. `beginTransaction()` — local only; nothing on the wire yet.
2. First `send()` to `out-p0` — the producer first sends `AddPartitionsToTxn` to
   the coordinator (recorded in `__transaction_state`), then the record is written
   into `out-p0`'s log **immediately**, marked transactional. It is physically
   present; a `read_uncommitted` consumer can already see it; a `read_committed`
   consumer cannot, because the LSO has stopped there.
3. `sendOffsetsToTransaction(101, groupId)` — `AddOffsetsToTxn` followed by a
   `TxnOffsetCommit` write into the group's `__consumer_offsets` partition, as part
   of the same transaction.
4. `commitTransaction()` — the coordinator writes `PREPARE_COMMIT` to
   `__transaction_state`, then writes a **commit control record** into every
   participating partition (`out-p0` and the `__consumer_offsets` partition), then
   marks the transaction complete.
5. Only now does the LSO advance past the record, and the `read_committed`
   consumer sees it — together with the offset commit, atomically.

Note the shape: it is `two-phase-commit` with the coordinator's decision durable in
a log before any participant is told, which is why a coordinator crash mid-commit
is recoverable rather than a blocking hole.

---

## Rung 6 — Spot the flaw

**6.1** The nightly repartition destroys per-customer ordering forever. Smallest
scenario: customer `C7`'s events are in partition 2; at 02:00 the count goes 12→16
and `C7` now routes to partition 5; at 02:05 `C7`'s "address changed" lands in
partition 5 while "order placed" is still unread in partition 2 — two consumers,
either order. **Violated: per-key ordering.** Secondary flaw: splitting by *size*
does nothing for a hot key, because the hot key stays whole (§10.3's prediction).

**6.2** Idempotence protects against duplicates, not against gaps. Smallest
scenario: the consumer hands each batch to a thread pool and returns to `poll()`;
auto-commit commits offsets for records still queued; the process is killed; those
records are never processed. **Violated: at-least-once processing.** An idempotent
consumer has no opinion about records it never saw.

**6.3** `min.insync.replicas` is unset, so it is **1**, and `acks=all` degrades
silently the moment the ISR shrinks (§10.4). Smallest scenario: two brokers slow,
ISR = {leader}, write acknowledged, leader's disk fails, record gone. Secondary:
`retries=MAX_INT` is bounded by `delivery.timeout.ms` (2 minutes) and by the
process staying alive — a producer that dies with records in its accumulator loses
them, which is why the transactional-outbox pattern exists at all.

**6.4** Committing on failure converts every processing error into silent data
loss, and pins lag at zero so nothing ever alerts. Smallest scenario: the fraud
scoring service returns 503 for 90 seconds; every scan in that window is logged,
committed and never scored. **Violated: at-least-once processing** — and worse, the
one metric anyone watches has been engineered to lie. The fix is a dead-letter
topic (§11.7): commit the offset *and* preserve the record somewhere retriable.

**6.5** Two flaws. (a) "After replay" is doing a lot of work: the map is correct
only once the consumer has read to the **log end offset of every partition** of the
topic. Serve traffic while still catching up and you answer with a partially built
map — no error, just missing and stale parcels. (b) Tombstones: the replay must
treat a null value as *remove*, and the process must finish within
`delete.retention.ms` of a tombstone being written, or it can miss the deletion
entirely (the tombstone is gone, the old value may be gone too — fine — but a
long-running replay that started before the tombstone and cached the old value
keeps it). Compaction guarantees "at least the latest value per key"; it does not
guarantee your reader reached the end, and it does not interpret nulls for you.

**6.6** EOS covers Kafka-read → Kafka-write → offset-commit atomically. The payment
API is none of those. Smallest scenario: transaction includes the API call; the
call succeeds; the process dies before `commitTransaction()`; the transaction
aborts; the record is reprocessed; the card is charged twice. **Violated: nothing
Kafka promised** — the promise was misread. The fix is an idempotency key at the
payment API.

**6.7** Lag misses every failure that is not about speed. Three counterexamples,
each real: §11.6's silent skip makes lag *fall to zero*; §11.3's ISR shrink to one
leaves lag perfectly flat while durability is gone; an unclean leader election
deletes committed records without moving lag at all. Lag is a superb *business*
metric and a poor *infrastructure* one. Pair it with
`UnderMinIsrPartitionCount`, and alert on lag as a fraction of retention rather
than in absolute records.

---

## Rung 7 — Handle failure

**7.1** *(a) Next ten minutes.* Confirm the shape first — one partition stuck, all
others fine — from per-partition lag. Then choose between two levers and say which
you are pulling and why: skip the record by advancing the group's committed offset
past it (`kafka-consumer-groups --reset-offsets --to-offset N+1` for that one
partition, consumers stopped), recording the offset and the parcel id so it can be
recovered later; or deploy a config that catches the deserialisation error and
routes it aside. The first is faster and loses one record's processing; the second
is slower and loses none. Under business pressure, skip, but **write down the
coordinates before you skip** — an offset you did not record is a record you cannot
recover.

*(b) The rebuild.* An error-handling boundary in the consumer: catch on the
processing of a single record; produce the raw record, its headers, its
`(topic, partition, offset)` and the exception to `parcel-scans.DLQ`; commit the
offset; continue. Then: a metric `dlq_records_total` with an alert on any non-zero
rate (a DLQ nobody watches is a delete with extra steps), a retention on the DLQ
long enough to reprocess (7–30 days), and a documented replay path — a consumer of
the DLQ that can re-inject after a fix. What it still does not cover: a record that
deserialises fine but is *semantically* wrong, which will not throw and will
silently produce bad output; and a poison record that kills the process before your
handler runs (an OOM on a huge payload), which needs `max.partition.fetch.bytes`
and a size limit at the producer.

**7.2** Within an hour: processing time per batch crosses `max.poll.interval.ms`
(5 min); the coordinator evicts a consumer mid-batch; its partitions move to peers
already saturated; those slow further and are evicted in turn; under the eager
protocol each eviction stops the entire group. Throughput collapses toward zero
while lag climbs, and every eviction reprocesses uncommitted records, so the
downstream API — already the problem — receives *duplicate* load. That is the
rebalance storm (§11.8), and note its shape: a downstream latency problem became a
Kafka outage through a timeout, not through Kafka.

Three independent breaks: (1) **reduce `max.poll.records`** so one batch is
completable well inside the interval — the smallest, safest, fastest change; (2)
**switch to `CooperativeStickyAssignor` / `group.protocol=consumer`** so an
eviction stops two partitions instead of all of them; (3) **add a circuit breaker
and bounded concurrency around the downstream call** so its degradation shows up as
lag rather than as eviction. Do (1) first: it is a config change, it takes effect on
restart, and it directly removes the trigger. Then (3), because it is the actual
cause. Then (2), because it reduces the blast radius of the next one.

**7.3** Split the guarantee honestly.

*Kafka's part:* at-least-once delivery of the scan to your consumer, in order per
parcel. That is all. Kafka transactions do not help, because the charge is outside
Kafka.

*Your part:* an **idempotency key** derived deterministically from the record —
`(topic, partition, offset)` is tempting but wrong if you ever replay from a
different topic; prefer a business-derived key such as
`payment:{parcel_id}:{invoice_id}`. Send it to the payment API as its idempotency
key (every serious payment API supports one). If yours does not, keep the dedup
state yourself: a table keyed by the idempotency key, written in the **same
database transaction** as the record of the charge, with a claim row inserted
*before* calling the API (`idempotency`'s claim/settled shape).

*Timeout without an answer:* this is the case the whole design exists for. Do not
retry blindly and do not assume failure. Re-send with the **same** idempotency key —
the API either performs it once or returns the original result. If your own table
holds a claim in `RUNNING` state with no outcome, reconcile by querying the payment
provider for that key before deciding.

*Lifetime of the dedup state:* at least as long as any record can be replayed —
so `retention.ms` of the topic, plus the longest outage you would replay after,
plus a margin. Sizing it as "24 hours" because that felt like a lot is the usual
mistake; a topic with 7-day retention needs at least 7 days of dedup state.

*What remains uncovered:* a duplicate produced *upstream* — the same physical scan
published twice as two different records with different ids — which no consumer-side
key can detect. That has to be solved where the scan is created.

**7.4** *(a) `min.insync.replicas=1`:* producers notice nothing. Latency may
improve. Consumers notice nothing. `UnderReplicatedPartitions` goes non-zero and
`UnderMinIsrPartitionCount` stays at zero. You are one further failure away from
losing acknowledged data and no user-visible signal says so.

*(b) `min.insync.replicas=2`:* if the ISR drops to 1, producers fail with
`NOT_ENOUGH_REPLICAS` and retry; if the disk fills for long enough, produce
requests start timing out and the upstream service feels it. Consumers keep
reading committed data normally.

**Prefer to be paged for (b).** It is loud, it is bounded, it does not lose data,
and the remedy (free disk, or restore the replica) is the same in both cases. In
(a) the page you get is the one three weeks later titled "we cannot reconcile
January".

**7.5** Sketch: producers in both regions write to their local cluster;
MirrorMaker 2 (or Cluster Linking) replicates topics asynchronously to the other
region; consumers run in the primary and are started in the secondary on failover.

The hard part has nothing to do with the data: **offsets are not the same in the
two clusters.** A mirrored topic is re-produced into the target cluster, so
`partition 3, offset 918,204` in the source is some other offset in the target.
A consumer failing over cannot simply resume from its committed offset. MirrorMaker
2 maintains an offset-translation topic to map them approximately, and
approximately is the operative word — the failover consumer typically restarts at a
translated offset that is *behind* where it was, and reprocesses. So the real
requirement of cross-region failover is not replication: it is that **every
consumer be idempotent**, because failover means replay. Everything else is
plumbing.

---

## Rung 8 — Design

**8.1** *Consumers and partitions.* Steady-state need: 40,000 ÷ 800 = **50
consumers**. Catch-up requirement: 6 h of backlog is 6 × 3,600 × 40,000 ≈ **864 M
records**, to be cleared within 1 h while live traffic continues. Required rate =
40,000 + (864,000,000 ÷ 3,600) = 40,000 + 240,000 = **280,000 records/s** →
280,000 ÷ 800 = **350 consumer instances** → **partitions ≥ 350**. Round to **384**
(a friendly multiple for even assignment across brokers) and note the consequence
honestly: you are provisioning 384 partitions for a 50-consumer steady state
because of a *recovery-time* requirement, not a throughput one. If the business
will accept a 4-hour catch-up instead of 1, the number drops to ~120 partitions and
the cluster gets much cheaper — that is a conversation to have before creating the
topic, because it cannot be had afterwards.

*Storage.* 40,000 × 500 B = 20 MB/s = 1.73 TB/day; × 3 days = **5.2 TB** logical;
× RF 3 = **15.6 TB** on disk; plus 30% headroom ≈ **20 TB**, before compression
(`zstd` on JSON-ish payloads typically 3–5×, so the real figure is often 4–7 TB —
but size the disks for the uncompressed case if you cannot verify the ratio).

*Least confident, and how to measure.* (1) **800 records/s per consumer** — it is
whatever the slowest downstream call allows, and it is usually measured on an idle
staging system with a warm cache. Measure it by replaying a real hour of production
traffic through one instance against a production-like downstream, at p99 not mean.
(2) **500 B average record** — measure directly from an existing topic
(`bytes-in ÷ records-in` from broker metrics), not from a schema, because headers,
keys and batch overhead are easy to forget.

**8.2** Five rules, each expensive to change later:

1. **Partition count is set at creation using §13.3's arithmetic, with a stated
   catch-up requirement.** Wrong → either a permanent throughput ceiling or a
   partition explosion; repairing it means a new topic and a dual-read migration.
2. **Every topic declares a key, and the key is the ordering unit.** Wrong → no
   ordering where the business needed it, or a hot partition; repairing it means
   re-keying, i.e. a new topic.
3. **`RF=3`, `min.insync.replicas=2`, `acks=all` on anything whose loss is
   noticeable; `unclean.leader.election.enable=false` everywhere.** Wrong → silent
   loss; unrepairable after the fact.
4. **Retention is declared as a recovery-time budget, in writing** ("we can be
   broken for N days and still catch up"). Wrong → §11.6's silent skip;
   unrepairable, because the data is gone.
5. **Every topic has a registered schema with a compatibility mode, and no
   producer writes without one.** Wrong → poison records across every consumer of
   that topic; repair is a DLQ plus a coordinated redeploy of teams you have never
   met.

A sixth, softer one worth having: **naming and ownership** — a topic with no named
owning team becomes undeletable within a year, because nobody can prove nobody is
reading it.

**8.3** *For "just Kafka":* the data is already there, a compacted topic gives you
the latest value per parcel with bounded storage, and it removes a database and a
sync path (one source of truth instead of two that can disagree).

*Against:* the query is **5,000 point lookups per second by key**, and Kafka has no
key lookup at all. Serving it from Kafka means replaying the entire compacted topic
into memory or RocksDB on every instance at startup (200 M rows — minutes to hours
of startup time, and a large per-instance memory or disk footprint), then keeping it
current. You have not removed a database; you have written one, without indexes,
backups, or ad-hoc queries.

*Recommendation:* keep the table; feed it **from** Kafka (`cqrs`'s read model,
`change-data-capture`'s sink). Kafka carries changes; the database answers
questions. That is the log–table duality used correctly rather than as a dare.

*The measurement that would change my mind:* the actual access pattern. If those
5,000 QPS turn out to be a small number of services each wanting *the whole state*
periodically, or if the working set is small enough to sit in memory per instance
and startup time is acceptable, then a Kafka Streams state store (which is exactly
this design, done properly, with standby replicas) becomes the better answer.

**8.4** Per consumer group: **lag per partition**, alerted on (i) lag ÷ retention >
25% — page, because you are approaching irreversible loss; (ii) lag rising
monotonically for 15 minutes — warn, capacity or downstream degradation; (iii) lag
== 0 for a group that normally has some, *and* a drop of more than 90% in one
scrape — warn, because that is the §11.6 signature. Action for (i): stop the
bleeding by raising retention *first* (it is a topic config and it is instant),
then fix the consumer. For (iii): compare the group's committed offset with the
partition's earliest offset before assuming good news.

Per cluster: **`UnderMinIsrPartitionCount` > 0** — page; action: find the
lagging/dead replica, and do not restart another broker until it is zero.
**`UnderReplicatedPartitions` > 0 for 15 min** — warn; action: identify whether it
is a slow disk, a GC problem, or a dead broker. **`ActiveControllerCount ≠ 1`** —
page immediately; a cluster with zero controllers accepts no metadata changes and
one with two is a split brain. **`OfflinePartitionsCount` > 0** — page; a partition
with no leader is a hard outage for its producers.

Per broker: **`RequestHandlerAvgIdlePercent` < 30%** — warn; you are on the
queueing cliff and latency is about to move everywhere at once. Disk free vs
projected retention — warn at 7 days of headroom.

The **silent** one that no other metric covers: `UnderMinIsrPartitionCount`. Lag,
throughput and latency all look *better* during the failure it detects.

**8.5** Without a high watermark: leader A has records 0–104 replicated and writes
105 locally. A consumer reads 105 and acts on it — say it texts a customer. A dies
before any follower fetches. B becomes leader with LEO 105 and assigns offset 105
to a **different** record. The consumer has now (i) processed a record that no
longer exists anywhere, and (ii) holds a committed offset of 106 that skips the new
record at 105. For downstream systems to remain correct, every consumer would have
to treat every read as provisional until some later signal confirmed the record's
durability — i.e. every consumer would have to implement its own two-phase commit
against the broker's replication state. The high watermark is the cheap alternative:
**publish nothing until it cannot be unpublished.** It costs one round trip of
staleness and removes an entire class of impossible-to-write application code.

**8.6** Three things that depend on offsets being permanent positions in one log:

1. **Committed offsets.** A group's entire state is "next offset per partition". If
   records could move between partitions, every committed offset in
   `__consumer_offsets` would become a pointer to something else.
2. **Replica truncation and leader epochs.** Failover works by asking "where did
   epoch 5 end?" and truncating to that offset. Both the question and the answer are
   meaningless if offsets do not identify records.
3. **Compaction, and the idempotent producer's sequence numbers.** "Later offset
   wins" is how compaction decides which value survives; "sequence n+1 must follow
   n" is how duplicate batches are detected. Both are ordering arguments over a
   fixed sequence.

Add the sparse index and `sendfile`: serving a consumer is "send bytes X–Y of this
file", which is only possible because the file is the log and position is identity.

So the inconvenience — a frozen partition count, a key routing that breaks when you
change it, no way to move a hot partition's records — buys: a consumer's state
being one integer, replication being a byte-range copy, failover being an integer
comparison, and reads being a kernel call with no deserialisation. **Kafka is fast
and simple because the offset means exactly one thing, and it is willing to be
inflexible to keep it that way.** That is the trade at the bottom of the whole
system, and every awkward operational rule in this lab is a payment on it.
