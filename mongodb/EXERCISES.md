# MongoDB — Exercises

Reasoning, tracing, simulation and design only. Nothing here asks you to write code. Where an
exercise says *check in the simulator*, open [`visualization.html`](visualization.html) and use
the scenario or free play named. Answers are in [`SOLUTIONS.md`](SOLUTIONS.md); attempt each
level before opening it.

Conventions: one tick = one heartbeat = 2 s; election timeout = 5 ticks; the simulator's
primary steps down one tick after the secondaries' election timeout. `ts s:i tN` is an optime.

---

## Level 1 — Explain

**1.1** In three sentences and without the words "replica", "quorum" or "consensus", explain to
a product manager why the customer in §4 received an "Order placed" e-mail for an order that
does not exist, and what the order-service should have asked the database for instead.

**1.2** "The document is the unit of everything." Name the four things it is the unit of, and
for each, name the limit or cost that follows from the choice.

**1.3** State what each of these means as a *position relative to the majority commit point*:
`w: 1`, `w: "majority"`, read concern `"local"`, `"majority"`, `"linearizable"`, and a read in a
causally consistent session.

**1.4** Why does a newly elected primary write a no-op oplog entry before its commit point can
advance? Answer with the rule from your `raft` lab and say what would go wrong without it.

---

## Level 2 — Identify

**2.1** Open scenario 4 and step to the tick where both `m1` and `m2` show PRIMARY. Which one is
the authoritative primary, and which two facts on the screen prove it?

**2.2** In scenario 3 at the end, `m1` is primary in term 3 but its commit point still shows a
term-2 optime for one tick. Point to the rule (on the page, under *Rules the simulator follows*)
that explains why, and to the oplog entry that will let it advance.

**2.3** In the shard diagram of §10.7, which component decides that a range should move, which
one performs the copy, and which one learns about it last? For each, name the failure that
would occur if it were unavailable.

**2.4** Read the connection string in §13.3. Identify the option whose *absence* would change
durability if someone later added an arbiter to the set, and explain why.

---

## Level 3 — Predict

**3.1** P-S-S set. A write is issued with `w: "majority"` at tick 0; `m3` is slow (3 ticks
behind). Predict the tick at which the write is acknowledged, and what a read with read
preference `secondary` on `m3` returns at that same tick with (a) no session and (b) a causal
session. Then check with scenario 5.

**3.2** Same set, healthy. The primary `m1` is *killed* (clean crash, not partitioned) at
tick 2 with an un-replicated `w: 1` write in its oplog. Predict whether that write survives the
election, and say which mechanism from §10.3 decides it. Then predict what changes if `m1` is
*partitioned* instead of killed.

**3.3** A read with read concern `"linearizable"` and `maxTimeMS: 10000` is issued against a
primary that has just been cut off from both secondaries. Predict what the client sees, and
when. Then predict what a read with `"majority"` would have returned instead.

**3.4** A three-shard cluster sharded on `{customerId: 1}`. Predict which of these are
targeted, which are broadcast, and which are rejected:
`find({customerId: "c-4471"})`, `find({customerId: {$gte: "c-4000", $lt: "c-5000"}})`,
`find({courierId: "k-19"})`, `updateOne({courierId: "k-19"}, ...)`,
`updateMany({status: "cooking"}, ...)`, `deleteOne({_id: ObjectId(...)})`.

**3.5** In a transaction, order-service reads the `inventory` document for `dal-tadka`, then a
non-transactional `updateOne` from the kitchen service decrements the same document, then the
transaction tries to update it. Predict what each party sees.

---

## Level 4 — Modify

**4.1** Change `electionTimeoutMillis` from 10 000 to 2 000. Which failure in §11 gets better,
which gets worse, and what determines the right value?

**4.2** Set `writeConcernMajorityJournalDefault: false`. Which guarantee in §12.1 is weakened,
under exactly which failure, and why does the docs page for `"linearizable"` mention this
setting?

**4.3** Replace the P-S-S set with P-S-S-S-S (five data-bearing voters). What is the calculated
majority for `w: "majority"`? How many members can be lost while still accepting majority
writes? What happens to the *latency* of a majority write, and why does a lagging fifth member
not affect it?

**4.4** Change Thali's `orders` shard key from `{customerId: 1}` to `{placedAt: 1}` to make the
"recent orders" report fast. Name the property from §10.7 that this violates, the symptom, and
two fixes — one that keeps the report targeted and one that does not.

**4.5** Change the kitchen-screen index from `{restaurantId: 1, status: 1, placedAt: 1}` to
`{restaurantId: 1, placedAt: 1, status: 1}`. Using the ESR rule, say what the planner does
differently for `find({restaurantId, status: "cooking"}).sort({placedAt: 1})`, and whether it is
better or worse than the original. Then say what happens if the query changes to
`find({restaurantId, status: {$ne: "delivered"}}).sort({placedAt: 1})`.

---

## Level 5 — Trace

**5.1** Hand-trace scenario 2 from the moment the partition heals (tick 8), writing the oplog of
each member, the common point `m1` finds, the content of its rollback file, and the commit point
on `m2` at ticks 8, 9, 10 and 11. Then check against the simulator.

**5.2** Trace the P-S-A double failure (scenario 6) but with one change: `m2` restarts *before*
`m1` crashes, at tick 4. Write the state at ticks 4, 5, 6 and 7. Does the acknowledged `w: 1`
write `ord-1` survive? Does `ord-2`?

**5.3** Trace a retryable write end to end across a failover *when the first attempt never
reached any primary* (scenario 7) and *when it reached the primary and replicated but the ack
was lost* (scenario 3, second half). For each, write what the new primary finds in its
transaction table and what it does.

**5.4** Trace the seven steps of a range migration for the range `[c-3000, c-7000)` moving from
shard B to shard C while order-service keeps writing `ord-9` for `c-4471` in the middle of step
4. Say at which step a read with `"local"` on shard B would stop returning `ord-9`, and at which
step a read with `"available"` would.

---

## Level 6 — Spot the flaw

Each design contains one subtle mistake. Construct the smallest concrete scenario in which it
fails, and name the guarantee that is violated.

**6.1** *"We run P-S-A to save a machine. We set `w: "majority"` explicitly in every
connection string, so the implicit-default trap in §11.3 does not apply to us."*

**6.2** *"Our reads use `secondaryPreferred` with read concern `"majority"`. Since majority reads
can never be rolled back, our read-after-write path is safe."*

**6.3** *"Our transaction code sets `readConcern: "snapshot"` on the transaction and leaves the
write concern to the database default, which is majority since 5.0."*

**6.4** *"To dedupe orders we use `updateMany({ref: payRef}, {$setOnInsert: {...}}, {upsert:
true})` — updateMany is retried by the driver like any write, so a network error cannot create
a duplicate."*

**6.5** *"The oplog is 50 GB and our write rate is modest, so a secondary can be down for a
weekend and catch up on Monday."*

**6.6** *"Our change-stream consumer stores the resume token in its own database after every
event, so it can never lose or duplicate an event."*

---

## Level 7 — Handle failure

**7.1** A rollback happened on `m1` last night and the rollback directory holds 340 documents
from `orders`. Design the runbook: how to decide which documents are safe to re-apply, which
are not (think about the order status having moved on elsewhere), and how to re-apply them
without creating the anomaly from §4 a second time.

**7.2** A majority commit lag alert fires: the primary is 40 s ahead of `lastCommittedOpTime`.
Explain what each of these is experiencing right now — a `w: "majority"` writer, a `"majority"`
reader on the primary, a `"local"` reader on the primary, and a transaction — and what flow
control is doing about it.

**7.3** The config server replica set has lost its primary and cannot elect one. List what
still works in the sharded cluster, what does not, and the one application-visible symptom
that would appear first.

**7.4** A retryable write's second attempt fails with the `NoWritesPerformed` label. Explain to
a teammate why this label exists, what the application may safely conclude, and what it may
not.

---

## Level 8 — Design

**8.1** Thali's ledger. Payments must be recorded so that an acknowledged payment is never lost
and the customer's next page load always shows it, but the payment service's p99 write latency
must stay under 15 ms across two availability zones 4 ms apart. Choose the topology, the write
and read concerns, the read preference, and the session policy, and say which of them you would
*relax* if the latency target proved impossible — and what you would lose.

**8.2** Thali is sharding `orders`. The dominant queries are: customer order history (by
`customerId`), the kitchen screen (by `restaurantId` + `status`), the courier queue (by
`courierId`), and a nightly report by `placedAt`. Choose a shard key, say which queries
scatter, and design the read-side structure (a second collection, a view, or a change-stream-fed
projection) for the ones that do. Say how you would migrate if you chose wrong.

**8.3** Design order placement with stock reservation across `orders` and `inventory` in three
ways — (a) a multi-document transaction, (b) a single-document redesign with no transaction,
(c) a saga with compensation — and for each state what is atomic, what is isolated, what fails
under contention, and what an operator sees. Recommend one for Thali and defend it.

**8.4** You are asked to reduce the median failover time from ~12 s to ~4 s. Propose the
configuration changes, then list every failure in §11 whose frequency or blast radius they
change, and state the monitoring you would add before shipping the change.
