# MongoDB — Solutions

Worked answers. For design questions the reasoning is the answer; a different conclusion with
the same reasoning is fine.

---

## Level 1 — Explain

**1.1** The database has three copies of the order book, and only one of them takes new orders.
When we placed the order we asked that one copy "have you written it?" and it said yes — but a
moment later it was cut off from the other two, they carried on without it, and when it came
back it had to throw away everything the others never saw. We should have asked "have at least
two of you written it?", which is a question that cannot later become false.

**1.2** Atomicity (one document changes all-or-nothing; crossing documents needs a transaction
that is priced to be rare). Storage locality (one contiguous value; the 16 MiB cap and the cost
of rewriting a large document on every update). Replication (one oplog entry; a transaction
becomes several entries, each still under 16 MB). Distribution (a shard key *inside* the
document places the whole document; queries without that key scatter, and the key must be
chosen for cardinality, frequency and non-monotonicity).

**1.3** `w: 1`: acknowledged above the line (on one member only). `w: "majority"`: acknowledged
only once the entry is at or below the line. `"local"`: may return entries above the line.
`"majority"`: returns only entries at or below the member's *known* line. `"linearizable"`:
returns only entries at or below the line *and* confirms, by a majority round trip, that the
line it knows is the current one. Causal session: returns only after the member's applied
position has reached the client's own last operation, which may be above or below the line
(hence the `majority`/`majority` requirement for the guarantee).

**1.4** Raft's Figure 8 rule: a leader may only advance the commit index over an entry from its
own term; earlier-term entries are committed indirectly once a current-term entry above them is
on a majority. Without the no-op, a new primary with no client writes could never advance the
commit point past the old term's entries, so majority writes from the previous term would stay
unacknowledged forever and `"majority"` reads would stay frozen even though a majority holds
the data.

---

## Level 2 — Identify

**2.1** `m2`. Proof on screen: its term is higher (2 versus `m1`'s 1), and its card shows it can
see a majority (it and `m3` voted); `m1`'s narration says "no majority seen for N ticks". A
third fact: `m2`'s oplog has the no-op entry in term 2, which `m1` does not.

**2.2** The rule "the commit point … only advances over entries from its own term". The entry
that lets it advance is `m1`'s no-op `ts …:1 t3`; once `m3` reports having pulled it, the term-3
commit point covers both the no-op and, indirectly, `ord-2` from term 2.

**2.3** The balancer on the config server primary decides; the source shard performs the copy
(the destination pulls documents and the delta); the `mongos` routers learn last, when a shard
tells them their cached routing table is stale. Config primary gone: metadata read-only, no
migrations, reads and writes continue. Source shard gone mid-migration: the migration aborts;
the metadata was not updated, so nothing is lost, and the destination's partial copy is
discarded. `mongos` gone: the application's driver fails over to another `mongos`; a `mongos`
holds no state.

**2.4** `w=majority`. Without it the driver uses the server's implicit default, which becomes
`w: 1` the moment the set contains an arbiter and the number of data-bearing voters is not
greater than the voting majority (P-S-A: 2 ≤ 2). With it, writes on a P-S-A set with one data
member down would *block* (then time out) rather than silently downgrade — which is the
behaviour you want to notice.

---

## Level 3 — Predict

**3.1** Acknowledged at tick 2: `m2` pulls at tick 1, reports at tick 2, commit point covers the
write, ack. At tick 2 `m3` has nothing. (a) NOT FOUND — legal, `m3` is a healthy secondary that
is behind. (b) The read waits; it completes at tick 4 when `m3` pulls the entry (written tick 0 +
3 ticks lag + 1 hop). Scenario 5 shows both.

**3.2** A clean crash: the write is *lost*, not rolled back — `m1` is down, so no one can pull
from it, and the new primary (`m2` or `m3`, same optime) does not have it. When `m1` restarts it
will roll the entry back into a file. If instead `m1` is partitioned but *up*, the answer is
still lost: catch-up (§10.3) would preserve the write only if the new primary could reach `m1`
during catch-up, and a partition prevents exactly that. Catch-up saves `w: 1` writes when the
old primary *steps down voluntarily or is slow* but remains reachable — a rolling restart, not a
crash or partition.

**3.3** The primary cannot confirm a majority; the read blocks and fails when `maxTimeMS`
expires — about 10 s later, an error, no data. A `"majority"` read would have returned
immediately from the primary's frozen commit point: durable data that may already be superseded
on the new primary.

**3.4** Targeted: `find({customerId})`, the range query on `customerId` (a contiguous key range
maps to a known set of ranges, possibly on more than one shard but not all), and
`deleteOne({_id})` (allowed because `_id` is accepted for single-document deletes — but it is
*broadcast*, since `_id` is not the shard key and `mongos` cannot know which shard holds it).
Broadcast: `find({courierId})`, `updateMany({status})`. Rejected: `updateOne({courierId})` — a
single-document update must carry the shard key or `_id`.

**3.5** The kitchen's non-transactional `updateOne` succeeds immediately (the transaction had
only *read* the document; it held no write lock). The transaction's later update finds the
document changed since its snapshot and aborts with `WriteConflict`, labelled
`TransientTransactionError`. The callback API retries the whole transaction, which now reads the
decremented value. Nothing was lost; the transaction simply lost the race.

---

## Level 4 — Modify

**4.1** Better: §11.12's *duration* — failover takes ~4 s instead of ~12 s, so the window with
no writes shrinks. Worse: §11.12's *frequency* — a 2 s GC pause or disk stall now triggers an
election, each of which rolls back any `w: 1` tail and costs a term. Also worse: the
stale-primary window becomes relatively larger for clients with slow server discovery. The
right value is bounded below by your worst normal pause (GC, fsync stalls, cross-zone latency
spikes) with margin, and above by the outage you can tolerate.

**4.2** The guarantee that a majority-acknowledged write is never rolled back — but only under a
*majority crash-and-restart*: the members acknowledged from memory, then lost power before the
100 ms journal sync. A partition or a single crash still cannot roll it back, because the
surviving majority holds it. The `"linearizable"` page mentions it because a linearizable read's
durability claim rests on the same journal: with the setting `false`, "documents returned by
the read operation" are not durable across a majority restart.

**4.3** Voting majority is 3, data-bearing voters 5, so the calculated majority is 3. Two
members can be lost. Latency is governed by the *third-fastest* member's pull + journal sync +
position report — the two slowest members never gate the ack, which is why a lagging fifth
member is harmless to writes (and dangerous only to reads that land on it).

**4.4** Monotonicity. Every insert lands in the range ending at `MaxKey`; one shard takes the
whole write load; the balancer chases the hot range around. Fix that keeps the report
targeted: a compound key `{placedAt: 1, customerId: 1}` still routes inserts to the tail — so
no; the honest answer is that a *ranged* key on time cannot both spread writes and target time
ranges. Fixes: (a) hashed on `customerId` (or `{customerId: 1}` ranged) for the OLTP path and
feed the nightly report from a change stream into a separate reporting store (targeted report,
via `cqrs`); (b) `{placedAt: "hashed"}` — spreads writes, but the report scatters to every
shard, which for a nightly batch is acceptable.

**4.5** Original `{restaurantId, status, placedAt}`: equality, equality, sort — the index walk
yields `placedAt` order, no blocking sort. Modified `{restaurantId, placedAt, status}`: after the
equality on `restaurantId`, the index is in `placedAt` order, so the sort is free, but `status`
is now a *filter applied while scanning* every order of the restaurant, examining far more keys
than needed; worse than the original. With `status: {$ne: "delivered"}` the predicate becomes a
*range*, so ESR says it belongs after the sort field — and the *modified* index
`{restaurantId, placedAt, status}` is now the correct shape, while the original would force
either a blocking sort or a range scan on `status` that breaks the sort order.

---

## Level 5 — Trace

**5.1** Tick 8, partition heals: `m1` = [ord-1 `100:1 t1`, ord-2 `104:1 t1`], SECONDARY t1;
`m2` = [ord-1, noop `114:1 t2`], PRIMARY t2, commit point `100:1 t1` (the no-op is not yet on a
majority as far as `m2` knows); `m3` = [ord-1, noop]. Tick 9: `m1` syncs from `m2`; common
point is index 0 (`100:1 t1`); `m1`'s extra entry `104:1 t1` goes to the rollback file; `m1`
rebuilds to {ord-1}; state ROLLBACK. Same tick, `m3`'s reported position now includes the no-op,
so `m2`'s commit point advances to `114:1 t2`. Tick 10: `m1` pulls the no-op; `m1` = [ord-1,
noop], SECONDARY t2, rollback file [ord-2]. Tick 11: `m1` learns the commit point `114:1 t2`
via heartbeat. Matches the simulator's narration.

**5.2** Tick 4, `m2` restarts as SECONDARY with an empty oplog and syncs from `m1`, which is
still PRIMARY (it has the arbiter's vote). Tick 5: `m2` pulls `ord-1` and `ord-2` (both written
at tick 1). Tick 6: `m2` reports; the calculated majority (both data members) now holds both
entries; `m1`'s commit point advances to `102:2 t1`, and the pending `w: "majority"` write of
`ord-2` — if it had not already timed out at tick 6 (5 ticks after issue at tick 1) — is
acknowledged; in fact wtimeout and the commit race at the same tick, and in the simulator the
request loop runs after the commit-point step, so it is acknowledged. Tick 7: `m1` crashes;
`m2` has both entries; after the election timeout `m2` becomes primary with the arbiter's
vote. Both writes survive. The lesson: the arbiter did nothing; the *second data member being
up* did everything.

**5.3** Scenario 7: the first attempt found no primary and was never applied anywhere. The new
primary's transaction table has no entry for `(s1, txn)`; it applies the write fresh and records
the result. Scenario 3: the first attempt was applied on `m2` and replicated to `m1` and `m3`
along with the oplog entry carrying `(s1, 2)`; when `m1` becomes primary its table already has
`(s1, 2)` → it returns the stored result and applies nothing. In both cases the client's second
attempt returns success once, and the collection holds exactly one `ord-2`.

**5.4** Steps: (1) balancer sends `moveRange` to B; (2) B starts, writes still go to B; (3) C
builds missing indexes; (4) C copies documents in the range — `ord-9` is inserted on B *during*
the copy; (5) C pulls the delta, which includes `ord-9`; (6) B enters the critical section
(brief write block on the range), updates the config metadata: the range now belongs to C;
`mongos` routers learn on their next stale-version error; (7) B deletes its copy once no cursor
is open, asynchronously. A `"local"` read on B stops returning `ord-9` at step 6 — ownership
moved, and `"local"` filters documents B no longer owns. An `"available"` read keeps returning
it until step 7 actually deletes it: an orphan.

---

## Level 6 — Spot the flaw

**6.1** Explicit `w: "majority"` on P-S-A is *safer* but has a different failure: with the one
secondary down, every write blocks (or times out) because the calculated majority is both data
members. The design has traded silent data loss for a full write outage on a single failure —
which is not what "we saved a machine" promised. Violated: availability under one failure, the
thing the arbiter was bought for.

**6.2** `"majority"` on a secondary returns that secondary's *known* commit point, which lags
the primary's by its replication delay plus a heartbeat. A client that writes with
`w: "majority"` and immediately reads with `secondaryPreferred` + `"majority"` can land on a
secondary whose known commit point predates the write → NOT FOUND. Rollback-safety is not
read-your-writes. Violated: read-your-writes; fix: a causal session (with `majority`/`majority`)
or `primary` for that path.

**6.3** The transaction does not inherit the *database-level* default only in the sense that
Jepsen found (pre-5.0 defaults); in 5.0+ the implicit default is `majority`, so this *usually*
works — until the set gains an arbiter (P-S-A → implicit `w: 1`), or someone runs
`setDefaultRWConcern` to `w: 1` for a bulk load. Then "snapshot read concern provides no
guarantee that read operations in the transaction used a snapshot of majority-committed data"
and the transaction can be rolled back on failover. Violated: snapshot isolation and durability,
silently, by a configuration change elsewhere. Set both on the transaction.

**6.4** `updateMany` is a multi-document write and is *not* retryable, regardless of `upsert`.
Scenario: the first attempt applies (inserts the order) and the ack is lost; the driver does not
retry (no `(lsid, txnNumber)` dedup for multi writes); the application's own retry loop runs
the upsert again — which, being an upsert keyed on `ref`, happens to be idempotent this time.
The flaw is the *reasoning*, and it fails the moment the write is not an upsert on a unique key:
use `updateOne` with `upsert: true` (retryable) and a unique index on `ref`.

**6.5** The oplog window is the *time* between the oldest and newest entries at the current
write rate, and the cap is by *size*: 50 GB at a "modest" rate may be days — or, during a bulk
import or an index rebuild on the primary (which writes large oplog entries), hours. If Monday
arrives with the secondary's last optime older than the primary's oldest entry, the secondary
needs an initial sync. Violated: the assumption that size equals time; fix:
`oplogMinRetentionHours` set to longer than the weekend, and monitor the window.

**6.6** Two flaws. Duplicates: the consumer processes an event, then crashes before storing the
token; on restart it resumes from the previous token and processes the event again — the
store-after-process order makes at-least-once, not exactly-once; the consumer must be idempotent
(`idempotent-consumer`). Loss: if the consumer is down longer than the oplog window, the resume
token points at a position the oplog no longer holds and `resumeAfter` fails; the consumer must
detect that and re-snapshot. Violated: exactly-once (never promised) and resumability (promised
only within the window).

---

## Level 7 — Handle failure

**7.1** First classify by what happened *on the winning side* since the rollback: for each
rolled-back document, look up its current state on the primary. (a) Absent on the primary and
no later write refers to it (e.g. an order that no service ever saw): safe to re-insert, with
`w: "majority"`, as a *new* write with a note in an audit field. (b) Present on the primary with
a *newer* state (the customer re-placed the order, or the status moved on): do not re-apply;
reconcile by hand or discard. (c) Rolled-back *deletes* and drops are not in the files at all —
list those from the oplog gap instead. Re-apply through the application's normal write path so
validation and change streams see it, never by loading BSON directly. Then fix the cause: the
write concern that let a rollback contain acknowledged data.

**7.2** A `w: "majority"` writer waits ~40 s per write (or hits `wtimeout`). A `"majority"`
reader on the primary sees data 40 s old — durable, stale. A `"local"` reader sees the latest,
which is 40 s of unacknowledged, rollback-eligible writes. A transaction reads a majority
snapshot 40 s old and, on commit with majority concern, waits like the writer; meanwhile its
pinned snapshot adds cache pressure. Flow control is throttling the primary's write admission
(tickets) to pull the lag back toward `flowControlTargetLagSeconds`, which shows up as *rising
write latency for everyone* — the deliberate cure.

**7.3** Works: reads and writes to data already placed (routers use their cached routing
table). Does not: migrations and splits, any DDL (which in 8.3 goes through `mongos` to the
config servers), adding or draining shards, creating sharded collections, and — after routers
restart or their cache expires — routing at all. First symptom: DDL or `sh.status()` hangs, or
a `mongos` restart fails to load the routing table.

**7.4** Since 6.1, a retry that fails when "both the first and second attempt … fail without a
single write being performed" carries `NoWritesPerformed`. May conclude: the write did *not*
happen, so it is safe to retry at the application level or to report failure without
reconciliation. May not conclude: anything about *why* (the primary may still be unavailable)
or that a *third* attempt will succeed. Without the label, a retryable write's failure means
"unknown", which forces reconciliation.

---

## Level 8 — Design

**8.1** Topology: five voters across three zones (2 + 2 + 1) so a zone loss keeps a majority
and one more member can be lost; or three (1 + 1 + 1) if cost forbids. Writes:
`w: "majority"`, `j` implied — the acknowledgement must be a fact. Reads for the customer's
page: `primary` with `"majority"`, or `secondaryPreferred` + causal session with `majority`
reads *inside the same session as the write*, which is the only combination with all four
guarantees. Latency: the calculated majority is 3 of 5, so the ack waits for the second-fastest
*other* member; with two members in the same zone as the primary that is intra-zone (< 1 ms) +
journal sync, comfortably under 15 ms; with three zones and a 1+1+1 layout it is the 4 ms hop
plus journal. If the target still fails: relax *reads* first (accept `secondary` +
`maxStalenessSeconds` for non-payment pages), never the write concern; relaxing `w` reintroduces
§4 on a ledger. Monitor majority-commit lag and journal sync latency as the two components of
the ack.

**8.2** Shard key `{customerId: 1}` (ranged, high cardinality, non-monotonic; targets the
dominant history query and single-order updates by `customerId`+`_id`). Scatter: kitchen screen
(`restaurantId`), courier queue (`courierId`), nightly report (`placedAt`). Read side: a
`kitchen_queue` collection sharded on `{restaurantId: 1}` and a `courier_queue` sharded on
`{courierId: 1}`, both fed by a change stream on `orders` (majority-committed events, resumable,
with `fullDocument`) — the `cqrs` projection; the nightly report goes to the analytics store via
the same stream. If wrong: `reshardCollection` (5.0) in the background, or
`refineCollectionShardKey` if only a suffix is needed; both are online but heavy, so pick with
`analyzeShardKey` on sampled queries first.

**8.3** (a) Transaction across `orders` and `inventory`: atomic and snapshot isolated; fails
under contention on the hot `inventory` document (write conflicts, 60 s cap, 5 ms lock wait);
operator sees `totalAborted` rising; commit costs a majority round trip even for a `w: 1`
request. (b) Single-document: put the reservation *inside* the inventory document
(`reservations: [{orderId, qty}]`, bounded per SKU per hour by design) using one conditional
`findOneAndUpdate` (`stock >= qty`), then create the order; the order creation is a separate
write, so "reserved but no order" is possible and needs a TTL sweep — atomic per document,
isolated by the storage engine, fails only as a clean "insufficient stock" verdict. (c) Saga:
reserve, place, confirm, with compensations; no isolation between steps, eventual consistency,
operator sees stuck sagas in a state table. Recommend (b) for Thali: the contention profile is
"many orders, few SKUs", where (a) aborts constantly and (c) is machinery for a two-step
problem; keep (a) for the rare multi-SKU basket where all-or-nothing matters more than
throughput.

**8.4** `electionTimeoutMillis` 10 000 → ~3 000 (elections start sooner) and consider
`catchUpTimeoutMillis` from −1 to a small bound (a new primary stops waiting to preserve `w: 1`
tails). Changes: §11.12 elections become more frequent for the same pause profile; §11.1
rollbacks of `w: 1` writes become *more* likely (shorter catch-up, more elections); §11.4's
stale-primary window shrinks in absolute time; §11.2's blocked majority writes resolve sooner
after a partition. Monitor before shipping: term-change rate, `NotWritablePrimary` error rate in
the application, count of members entering `ROLLBACK`, GC and fsync pause distributions on
every member (the timeout must clear their p99.9), and p99 write latency across the change.
Ship with `w: "majority"` everywhere first — then the extra elections cost time, not data.
