# Anti-Entropy — Solutions

Rungs 7 and 8 have no single right answer; what follows there is a worked discussion
and the set of concerns a good answer covers.

---

## Rung 1 — Explain

**1.1** Keeping three copies means one machine can die without losing anything. But a
change only has to reach two of the three copies before the system calls it done —
that is deliberate, because waiting for all three would mean one sick machine could
stop the whole service. So the third copy is left holding the old value, and nothing
in the ordinary business of writing and reading is responsible for going back to fix
it. The background job exists because otherwise nobody ever does.

**1.2** R+W>N guarantees that a *read* touches at least one replica holding the latest
write; it says nothing about the replicas themselves. Better: *"Quorum reads mean a
read is not fooled by a stale copy. The stale copy is still there, and only repair
removes it."*

**1.3** Because "repair" and "sync" sound like things you finish. Anti-entropy names a
continuous pressure — replicas drift apart as an ordinary consequence of the system
working — so the mechanism is a pump you keep running, not an error handler you invoke
when something breaks. There is no state called "repaired" that you arrive at and stay
in.

**1.4** Instead of comparing every line of the price list, you compare one total for
the whole list. If the totals agree, the lists are identical and you are finished
after one comparison. If they disagree, you split the list in half, compare the two
halves the same way, and keep splitting — so you only ever look inside the parts that
actually disagree.

---

## Rung 2 — Identify

**2.1** The verdict number counts items where *a customer would be told different
things* depending on which shop serves them. The other figure counts items where the
shops hold *different records*. They differ when the difference is invisible to a
customer — most importantly when one shop holds a deletion marker and another holds no
row at all. Both mean "not sold", so visible divergence is 0 — and that is exactly the
state from which repair resurrects deleted data.

**2.2** `croissant`, highlighted in the drift colour on all three shop cards (the
highlight marks the *item*, not the wrong shop — the shops disagree, and no copy is
authoritative). Clicking it shows the timestamp on each shop's record, which is the
thing the merge rule actually compares and which the card does not display.

**2.3** With a 16-range tree and one differing item: 4 green (pruned) boxes, 4 red
(descended or streamed) boxes on the path down, and 22 faded. A faded box is a range
whose parent's hashes matched, so neither shop read a single row inside it or sent
anything about it. That pruning is the entire saving.

**2.4** **Rows read off disk** — always 32, whether one row differs or none do. It
matters because it is the cost that does not respond to your data being healthy: a
perfectly in-sync cluster still pays a full scan of every range on every replica for
every repair pass. In Cassandra this is validation compaction, and it is why repair is
scheduled for quiet hours.

---

## Rung 3 — Predict

**3.1** +1 hour → **0 divergent** (hints replayed). +4 hours → **3 divergent**. The
hints were discarded at the 3-hour mark. The difference is not that more went wrong in
the longer outage; it is that the cheap layer stopped covering it, and nothing
reported that.

**3.2** **3 divergent.** Hinted handoff failed — not through timeout, but because
hints are held on the single shop that took the write, and that shop restarted. Note
the elapsed time was never the problem: no outage in this run exceeded ten minutes.

**3.3** 2 ranges: **8 rows sent, 6 hashes**. 16 ranges: **1 row sent, 18 hashes**.
Deeper trees cost more comparisons and dramatically less transfer — and since a hash
is 32 bytes and a row is 200, that is a good trade until the tree stops fitting in
memory.

**3.4** Divergence stays at 1 forever. At R=1 the read has one response, and one
response is not a comparison. Read repair does not need "a read" — it needs *two
differing answers to the same question in the same request*. This is why a
latency-tuned path reading at `ONE` gets no convergence from layer 2 at all.

**3.5** Hashes 2, rows sent 0, rows actually different 0, bytes 64 — and **rows read
off disk: 32**. That is the full-scan cost, called validation compaction, and it is the
part of repair that a healthy cluster cannot avoid paying.

---

## Rung 4 — Modify

**4.1 Hint window 0.** Every hint is discarded immediately, so layer 1 is off. Nothing
becomes *wrong* — no bad value appears — but drift that used to be cleaned up in
seconds now waits for a scheduled repair. **Liveness lost, safety intact.**

**4.2 Headstones off.** Repair sees "one side has a row, the other has nothing" and
copies the row. The deleted item is back on sale on every replica, permanently, with
no error. **Safety lost** — the system produces a wrong answer, not a late one.

**4.3 Merge rule = whoever received it wins.** Both orders converge, quickly and
stably, to *different* values. Note what did not happen: no oscillation, no error, no
failure to converge. **Safety lost**, and lost in the hardest way to detect, because
every health check reports a perfectly consistent cluster.

**4.4 C's clock 120 minutes fast.** The shelves show C's 3.00, and the genuinely later
3.50 is gone. The unusual part is *when*: the wrong outcome was decided at write time,
by each replica independently, on arrival — before any repair, any read, or any
conflict. Repair then propagates it authoritatively. **Safety lost**, and the loss is
in the write path, not the repair path.

**4.5 W=1, R=1.** Hinted handoff still works (the coordinator still notices
unreachable replicas). Read repair is dead — R=1 gives it nothing to compare.
Scheduled Merkle repair still works and is now doing essentially all of the
convergence work; it is also now the *only* thing standing between you and permanent
divergence, so its schedule has become a correctness parameter.

---

## Rung 5 — Trace

**5.1** `tart` is item 13 of 16, so it falls in leaf index 12 of a 16-leaf tree.

```
level 0  root                      A: differs from C          → descend   (2 hashes)
level 1  items 1–8                 equal                      → PRUNE (8 items never read)
         items 9–16                differ                     → descend   (+4 hashes)
level 2  items 9–12                equal                      → PRUNE (4 items never read)
         items 13–16               differ                     → descend   (+4 hashes)
level 3  items 13–14 (tart,eclair) differ                     → descend   (+4 hashes)
         items 15–16               equal                      → PRUNE (2 items never read)
level 4  tart                      differ                     → STREAM    (+4 hashes)
         eclair                    equal                      → PRUNE (1 item never read)
```

Total **18 hashes**, **1 row** streamed in each direction, **15 of 16 items never
looked at**. Merge: A's `8.50 @ ts 40` beats C's `4.40 @ ts 0`, so both become 8.50.

Divergence before: 1. After: 0 — because B already held 8.50. **It is not zero in
general** because a repair is *pairwise*: had B been the odd one out, repairing A ↔ C
would have changed nothing at all. Convergence for the whole replica set needs every
pair covered, which is why real repair runs a session per pair per range.

**5.2** Root differs (2 hashes); descend (4 hashes) → total **6 hashes**. The second
leaf covers items 9–16, so **8 rows** are transferred each way, of which **7 were
already identical on both sides**. That is the over-streaming: a 7-row tax on a 1-row
problem, paid because the leaf, not the row, is the unit of transfer.

**5.3** The zombie trace:

| Time | A | B | C |
|---|---|---|---|
| t=11 | headstone @ ts 11 | headstone @ ts 11 | `eclair = 4.60 @ ts 0` (closed, knows nothing) |
| t=500 | *no row at all* | *no row at all* | `eclair = 4.60 @ ts 0` |
| t=501 | *no row at all* | *no row at all* | `eclair = 4.60 @ ts 0` (reopens, serving) |
| t=502 | `eclair = 4.60` | *no row* | `eclair = 4.60` |

The outcome became inevitable at **t=500, when the headstones were cleared** — not at
t=502 when repair ran. Up to t=499 a repair would have carried a live tombstone to C
and settled it correctly. After the clear, the evidence that the item was ever deleted
does not exist anywhere in the system, and no mechanism can recover it. What had to
happen before t=500 is simply: **a repair involving C.** That is the whole of the
`gc_grace_seconds` rule.

Notice also that B ends up disagreeing with A and C — one pairwise repair does not
converge the set, per 5.1.

**5.4** Deletes performed on days 1–2, whose tombstones were purged around day 11, may
now exist as live rows on any replica that missed the original delete. The damage is
bounded by: *deletes issued in the window* × *replicas that missed them*.

The first thing to check is whether that keyspace does deletes at all — if it is
append-only, there is no exposure and you can simply repair. If it does, the important
thing is what **not** to do: *do not just run repair.* Running repair is the step that
performs the resurrection. The safer sequence is to raise `gc_grace_seconds` first (so
that any tombstones still alive are not purged mid-repair), establish which replicas
were behind, and prefer rebuilding a badly-behind replica over repairing it. Then
repair, then lower the grace back once the cycle is healthy again.

---

## Rung 6 — Spot the flaw

**6.1** A single checksum answers *whether* you differ, never *where*, so a mismatch
leaves you no option but to stream everything. On a cluster taking continuous writes,
two replicas essentially always differ by at least one in-flight row, so the answer is
always "differ" and the design degenerates into a full sync **plus** an extra round
trip and a full scan. It is strictly worse than just doing the full sync. The lesson:
the tree is not there to detect differences — one hash detects them — it is there to
*localise* them.

**6.2** One-directional repair. A holds `tart = 8.50 @ ts 40`; B and C hold
`4.40 @ ts 0`. Rule: higher ID sends to lower.

- C → B: identical, nothing changes.
- C → A: A merges 4.40 into 8.50 and keeps 8.50.
- B → A: same.

Final: **A = 8.50, B = 4.40, C = 4.40**, and no further repair will ever change that.
Data flows only downhill, so a value that exists only on the highest-ID node can never
leave it. A read at R=2 landing on B and C returns 4.40 — the newer write is invisible
— and if A is later replaced, it is gone. **Convergence is broken**, permanently, by a
design that looked like it merely halved the traffic.

**6.3** "Deleted if missing on a majority" replaces *what happened most recently* with
*how many copies exist*, and those are different questions.

- **It deletes live data.** Write a new row at W=1; it lands on A only. It is missing
  on B and C — a majority — so repair deletes it. A successfully acknowledged write is
  destroyed.
- **It resurrects deleted data.** A row exists on all three. A delete is issued while
  B and C are unreachable and is accepted at W=1, so only A removes it. The row is now
  present on B and C — a majority — so repair copies it back to A.

Both failures come from the same root: absence carries no timestamp, so counting
absences cannot order events. A tombstone is exactly the fix, because it gives the
deletion a timestamp and makes it comparable.

**6.4** Keeping hints forever. **Storage:** hints accumulate unboundedly on the
coordinators during a long outage — and the moment the replica returns you replay a
gigantic backlog write-by-write into a machine that has just started, which is a
reliable way to knock it over again. This is precisely why the window exists.
**Correctness:** it still does not achieve "no write is ever lost", because hints die
with the coordinator holding them, and because hints only cover writes the coordinator
*accepted and could not deliver* — they do nothing about a replica that lost data
locally (disk corruption, a restore from an old snapshot, an operator error). Hinted
handoff is an optimisation. Making it more thorough does not make it a guarantee.

**6.5** The 4% is measuring **how often no write landed between the two tree builds** —
that is, it is a measure of your write traffic and the gap between the two scans, not
of your data quality. On a busy cluster it will approach 0% while the data is perfectly
healthy. Tracking it as data quality means the dashboard gets worse exactly when the
business gets busier, which is how a metric trains a team to ignore it.

---

## Rung 7 — Handle failure

**7.1 Eleven days down, ten-day grace.** The concerns a good runbook covers:

1. **Do not repair it, and do not let it serve reads.** Repairing is the operation that
   resurrects deleted data, because tombstones for anything deleted in the last eleven
   days have been purged on the healthy replicas. Letting it join and serve is how
   stale values reach customers.
2. **Replace rather than repair.** Wipe it and bootstrap it — stream the ranges from
   scratch. This is deliberately choosing full sync over Merkle, and it is also the
   cheaper choice: at that much divergence the tree comparison prunes nothing and costs
   *more* than sending everything (measured in the lab: 8,384 bytes against 6,400).
3. **If you genuinely cannot rebuild it**, raise `gc_grace_seconds` across the cluster
   first so no further tombstones are purged during the operation, then repair, then
   accept that anything deleted in the missed window may need reconciling at the
   application level. Restore the grace afterwards.
4. **Afterwards, fix the thing that let a replica be down for eleven days** without a
   page. That is the actual defect.

**7.2 400 GB nightly to fix a few thousand rows.** The levers, and their costs:

| Lever | What it costs |
|---|---|
| Deeper Merkle trees (smaller leaves) | RAM per repair session, and sessions run concurrently |
| Sub-range repair | More sessions, more coordination, longer wall-clock |
| Repair more often | Less drift per pass, so less to stream — but more scans |
| Repair less often | Fewer scans, more drift per pass, and you approach the `gc_grace` deadline |
| Throttle streaming | Protects live traffic; risks repair not finishing inside `gc_grace` |
| Move to a database with row-level repair (ScyllaDB) | A migration |
| Reduce data per range (more nodes / more vnodes) | Hardware, and rebalancing |

Also worth checking before tuning anything: whether the 400 GB is over-streaming at
all, or whether the replicas genuinely are that far apart because something upstream is
dropping writes.

**7.3 24-hour compliance deletes vs a 30-hour repair.** In plain language for a
product manager: *"Deleting a row does not erase it — it writes a note saying 'this was
deleted'. Every copy of the data has to see that note before we are allowed to throw
the note away. Right now it takes us 30 hours to make sure every copy has seen it. If
we throw the note away after 24, some copies never learn about the deletion, and the
next time the copies are compared the deleted record comes back — and it comes back
looking completely legitimate."*

Two designs:

- **Make repair fit.** Sub-range or incremental repair, more parallelism, more nodes —
  get the full cycle to, say, 8 hours, then set the grace to 24 with real margin. This
  keeps the model simple and costs operational work and hardware.
- **Stop relying on tombstone GC for the guarantee.** Encrypt each user's data with a
  per-user key and delete the *key* on request. The rows may survive for weeks; they
  are unreadable within milliseconds. This satisfies most regulatory definitions of
  erasure, decouples compliance from repair scheduling entirely, and costs you a key
  management system and the ability to do anything with the data after deletion —
  including debugging.

A third, narrower option: put the compliance-relevant data in its own table with its
own much smaller repair scope, so only that slice has to meet the tight deadline.

**7.4 Monitoring.** The alert that matters most is the one with no symptoms:

- **"Time since last successful repair completion, per keyspace, per replica."** Page
  at 60% of `gc_grace_seconds`. This is a data-correctness alert, and it fires before
  anything is visibly wrong — which is the entire point, because after `gc_grace` the
  damage is unrecoverable and silent.
- Repair job failures and stalls (a session blocked on one unreachable replica leaves
  its range unrepaired while every other range looks healthy).
- Bytes streamed per repair pass, trended. A sudden jump means something upstream
  started dropping writes; a slow climb means your cadence is losing to your drift.
- Hint delivery vs expiry ratio. Rising expiries mean layer 1 has stopped covering your
  outage profile and layer 3 is quietly taking the load.
- Tombstone-per-read warnings, which are the early symptom of the pressure that tempts
  people to shorten `gc_grace_seconds` — the change that buys you 11.6.

**7.5 A four-second clock drift under LWW.** Every write from that server for a week
carried a stamp four seconds in the future. Consequences: its writes beat genuinely
later writes from every other server, and those later writes were discarded *on
arrival*, with no error. Repair does not help — it **hurts**, because it takes the
winning (wrong) value and propagates it authoritatively to every replica, converting a
one-replica problem into a cluster-wide fact.

Finding the damage is genuinely hard, because nothing is inconsistent afterwards — it
is uniformly wrong. The realistic approach: identify the affected window and server,
then look for rows whose current timestamp falls in that window while a *later*
audit-log or event-stream entry exists for the same key. If you have no independent
record of writes, you likely cannot recover the lost updates at all — which is the real
argument for [hybrid-logical-clocks](../31-hybrid-logical-clocks/), or for a merge rule
that keeps concurrent versions instead of picking one.

---

## Rung 8 — Design

**8.1 Product catalogue, 40M items, 3 DCs, RF=5.** What survives from the lab: the
three-layer structure, unchanged — hinted handoff, read repair, scheduled Merkle
repair. What must change:

- **The merge rule.** "Prices must never be silently wrong" and last-write-wins do not
  belong in the same system, for the reason in 7.5. Either use server-side timestamps
  from a single trusted source per key, or keep concurrent versions and resolve them
  in the application (the price a human last approved wins over an automated feed).
  Say which, and why, before anything else — it is the decision the rest hangs on.
- **Repair topology.** Repairing all pairs across 80 ms WAN links is the expensive
  part. Repair frequently within each datacentre, less often across them, and be
  explicit that cross-DC drift therefore lives longer.
- **Quorum settings.** Heavy reads and modest writes suit a high W and low R:
  `LOCAL_QUORUM` reads with W across DCs, so writes carry the coordination cost and
  reads stay fast.
- **Monitoring.** Per 7.4, with the time-since-repair alert as the primary. Add a
  business-level check that does not depend on the storage layer at all: sample prices
  from each replica directly and compare them against the source of truth. That check
  catches every failure in this lab, including the ones where the cluster agrees with
  itself about the wrong number.

**8.2 A message store with 30% deletes.** The four things interact badly here.
Tombstones accumulate; reads scan them and slow down; the temptation is to cut
`gc_grace_seconds`; cutting it tightens the repair deadline; and repair is already
expensive because there is a lot of data.

A workable scheme: partition by conversation and time, so deletes cluster into whole
partitions that can be dropped rather than tombstoned; use TTLs where the retention
policy allows, so rows expire without a delete being issued at all; keep
`gc_grace_seconds` at the default and instead invest in making repair reliably fast
(sub-range, frequent, monitored); and accept slower reads on the small number of
partitions that genuinely have heavy interleaved deletes. What you give up: some
storage efficiency, and the ability to delete an arbitrary message cheaply — deletes
become a design-time concern rather than a runtime one.

**8.3 Two billion rows, a 9-hour scan.** The scan is the thing to remove, so the
mechanism has to maintain its tree **incrementally as writes land**, rather than
building it per repair. That is Riak's active anti-entropy: a persistent hash tree,
updated on every write, stored alongside the data.

The state you now maintain is a second copy of the truth in summary form — and the
hard question is how that state stays honest, because a bug or a crash that leaves the
tree disagreeing with the data produces repairs that are confidently wrong in both
directions. Riak's answer is an expiry: rebuild every tree from scratch periodically,
so a corrupted tree has a bounded lifetime. So you have not removed the scan; you have
moved it from *every repair* to *once per expiry period*, which is the actual win.

An alternative worth naming: skip trees and repair by *recent-writes* — every replica
keeps a log of what it wrote in the last N hours and exchanges that, falling back to a
full comparison only occasionally. That covers the common case (drift caused by recent
misses) very cheaply and does nothing about silent disk corruption, which is a
different failure that trees do catch.

**8.4 Where you would run none.** A Kafka partition, or a PostgreSQL streaming
replica. One leader, one ordered log; a follower's state is defined entirely as "the
log up to offset N". A lagging follower is not divergent, it is *behind*, and catching
up means replaying from its offset — there is nothing to compare because the log
already says what the truth is and in what order.

What flips the answer: allowing any replica to accept a write. The moment writes are
leaderless (or multi-leader, or accepted during a partition), there is no single log
defining the truth, two replicas can hold unrelated histories, and comparison becomes
the only tool left. **Anti-entropy is the price of leaderless writes** — which is also
why it shows up in exactly the systems that chose availability over coordination, and
in none of the ones that chose the other way.
