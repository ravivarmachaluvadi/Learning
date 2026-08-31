# Solutions

## Tier 1 — Predict

**P1.** (a) empty — the order rolled back / was never committed; (b) one
`order-placed` message: a **phantom event**; (c) stock decremented for an
order that doesn't exist — inventory reserved (or shipped) for free. It's
*worse for you* than DB-first: DB-first loses a notification (customer-visible
delay, recoverable by support); broker-first corrupts downstream state based
on a fact that never happened, and un-shipping is harder than re-notifying.
Neither ordering is acceptable — that's the point of the pattern.

**P2.** Four messages: `red, red, blue, green`. Crash after publishing `red`
leaves all three rows unmarked; the second poll re-reads from the start and
publishes all three. Only the message that crossed the crash gap duplicates;
order per the `seq` column is preserved otherwise. General rule: after a relay
crash, exactly the messages published-but-not-yet-marked in that batch appear
twice.

**P3.** Exactly once. The consumer's dedupe-insert, stock update, and offset
advance share one transaction (the single `conn.commit()` in `consumeAll`). The
crash before commit rolls back all three, so the redelivered `m91` finds no
`processed_messages` row and applies cleanly. This same-transaction property
is the load-bearing line of the whole consumer.

**P4.** (a) Lost: nothing. (b) Wrong: nothing durable — though consumers'
views are stale for 6 h, which can cause business-level errors like overselling
(stale ≠ corrupt: it heals on catch-up). (c) Late: every event from the
window; the relay drains the backlog in commit order on restart. The query:
`SELECT min(created_at) FROM outbox WHERE published_at IS NULL` (oldest
unpublished age = outbox lag) — alert when it exceeds a few seconds.

## Tier 2 — Modify

**M1.** `test_idempotent_consumer_resumes_after_its_own_restart` — or any
crash between "dedupe committed" and "effect committed". Timing: consumer
commits the `processed_messages` insert, crashes, restarts, sees the id as
already-processed, skips the message — whose effect **never happened**. The
message is lost at the consumer despite being delivered: at-most-once
processing. Moral: the dedupe record must commit *with* the effect, never
before it.

**M2.** New failure: relay marks the row, crashes before publishing → the
row is never re-read → **message lost** (at-most-once delivery). Replacement
test shape:

```java
run("test_mark_then_publish_loses_message_on_crash", () -> {
    var svc = orderService(); svc.placeOrderOutbox("red-mug", 2);
    var crashing = relay();
    assertCrash(() -> crashing.pollOnceMarkedFirst(broker, true));  // crashAfterMark
    relay().pollOnceMarkedFirst(broker);
    assertEquals(0, broker.log.size(), "gone forever");
});
```

This is the whole choice in one line: publish-then-mark = duplicates
(recoverable); mark-then-publish = losses (not recoverable).

**M3.** Deleting the instant it's marked is *safe for delivery* (the broker
has it) but you lose: replay (re-publish history to a new consumer), audit
("did we ever emit X?"), and debugging of consumer claims. Debezium's
insert+delete-in-one-transaction keeps the event in the **WAL/CDC stream**
(which has its own retention, e.g. the Kafka topic), so history lives in the
log where it belongs while the table stays at size zero — you've moved
retention to a system designed for it.

**M4.** Payload carries absolute intent per order, e.g.
`{"order_id":"a17","item":"red-mug","reserved_qty":2}` applied as an upsert
into a `reservations(order_id PRIMARY KEY, item, qty)` table, with stock
computed as `initial - SUM(reservations)`. Re-applying the same order_id
upserts the same row — naturally idempotent. The race: two *different*
orders both reserving from stock 1 — both upserts succeed and the sum
oversells; you now need a constraint/check (`WHERE stock >= qty` style) or to
move the availability decision back to the producer side. Idempotency by
natural key trades the dedupe table for harder invariants.

## Tier 3 — Debug

**D1.** Every current test still passes — including
`test_outbox_write_is_atomic_with_business_write` — because no test crashes
*between* the two commits (the bug window is microseconds and tests are
crash-free there). The catching test injects a crash between them:

```java
run("test_split_commits_lose_message_on_crash_between", () -> {
    var svc = orderService();
    assertCrash(() -> svc.placeOrderOutboxSplit(  // the "optimized" version
            "red-mug", 2, true));                 // crashBetweenCommits
    var restarted = orderService();
    assertEquals(1, restarted.orders().size(), "order committed");
    assertEquals(1, restarted.unpublishedOutboxRows().size(), "outbox row");  // FAILS: 0
});
```

The "optimization" is dual write reborn one layer down — same crash, same
silent loss. Lesson: atomicity bugs are invisible to crash-free tests; you
must test the crash points themselves (this is why the lab has `Crash`).

**D2.** Client-level retries. `message_id` is generated *per call* inside
`placeOrder*`. If the web tier times out and retries `placeOrder`, you get
two orders, two outbox rows, two **different** message_ids — dedupe by
message_id is blind to it, correctly, because to this layer they *are*
different messages. The fix lives a layer up: an **idempotency key** supplied
by the client (`Idempotency-Key` header, unique per user action), enforced by
a unique constraint on `orders`. Dedupe must exist at every layer that
retries; each layer's key identifies *that layer's* notion of "the same
action".

**D3.** (a) Two relay instances each grab disjoint row sets with
`SKIP LOCKED`; instance B can publish order a17's *second* event while
instance A (holding the first) is slow — cross-instance reordering. Fixes:
single active relay (leader election), or partition rows by aggregate id so
one aggregate's events always go through one instance. (b) With
`max.in.flight=5` and idempotence off, batch 1 fails, batch 2 succeeds,
batch 1 retries → arrives after batch 2. Fixes: `enable.idempotence=true`
(caps in-flight and sequences batches) or `max.in.flight=1`. Ordering, like
delivery, must be defended at *every* hop: table → relay → producer →
partition → consumer.
