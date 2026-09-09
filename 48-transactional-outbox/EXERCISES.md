# Exercises — transactional outbox

Attempt each one before opening `SOLUTIONS.md`. The predict exercises are
runnable against the lab; run the command *after* committing to an answer.

## Tier 1 — Predict

**P1.** In the dual-write version, swap the two writes: publish to the broker
*first*, then commit the order to the database — and the process crashes
between them. What is the end state of (a) the orders table, (b) the broker
log, (c) the inventory service's stock after it consumes? Is this failure
better or worse than the DB-first crash, and for whom?

**P2.** With the outbox pattern, the producer commits 3 orders and then the
relay's *first ever* poll crashes after publishing only the first message
(before marking it). The relay restarts and polls again. Exactly how many
messages does the broker log contain, and in what order? Check with:

```bash
jshell --class-path ../lib/h2.jar OutboxLab.java - <<'EOF'
new java.io.File("./.lab-p2-orders.mv.db").delete();
var b = new OutboxLab.Broker();
var s = new OutboxLab.OrderService("./.lab-p2-orders");
for (var item : new String[]{"red", "blue", "green"}) s.placeOrderOutbox(item, 1);
try { new OutboxLab.Relay("./.lab-p2-orders").pollOnce(b, true); } catch (OutboxLab.Crash e) { }
new OutboxLab.Relay("./.lab-p2-orders").pollOnce(b);
System.out.println(b.log.stream().map(OutboxLab.Message::payload).toList());
/exit
EOF
```

**P3.** An idempotent consumer processes message `m91`, and *then its own
process crashes* after the stock update but before its transaction commits.
The broker redelivers `m91`. Is stock decremented zero, one, or two times in
the end — and which line of `InventoryService.consumeAll` makes it so?

**P4.** The relay is stopped for 6 hours (bad deploy) while the shop keeps
taking orders. List everything that is (a) lost, (b) wrong, (c) merely late,
once the relay restarts. Which single SQL query would have told the on-call
engineer about the problem within a minute?

## Tier 2 — Modify

**M1.** Change `InventoryService.consumeAll` so the dedupe-insert into
`processed_messages` happens in its **own separate transaction, committed
before** the stock-update transaction. Which test in `OutboxTest.java` should
now be able to fail, under what crash timing, and which guarantee flips from
"at-least-once outcome" to "at-most-once outcome"?

**M2.** Change `Relay.pollOnce` to mark the row published **before**
publishing to the broker (mark-then-publish). Re-run
`test_relay_crash_between_publish_and_mark_causes_duplicate` — it will fail.
Write the replacement test that captures the *new* failure mode this
ordering has, and name the delivery guarantee it now provides.

**M3.** The outbox table in the lab keeps rows forever. Add a
`prunePublished(olderThanSeq)` method to `OrderService` and decide: is it
safe to delete a row the *instant* `published_at` is set? What real-world
capability do you lose (hint: think replay / audit), and what does Debezium's
insert-and-delete-in-same-transaction trick preserve that plain pruning
doesn't?

**M4.** Make the *consumer* idempotent without a `processed_messages` table
at all, by changing the event payload from "subtract qty 2" semantics to a
naturally idempotent form. What payload would you choose for inventory, and
what new race appears if two *different* legitimate orders for the same item
are in flight?

## Tier 3 — Debug

**D1.** A teammate "optimizes" `placeOrderOutbox` by moving the outbox
insert *after* the transaction's `conn.commit()` ("the outbox insert is slowing
down the hot path — I commit the order first, then insert the outbox row
right after; it's only microseconds later"). Which test in `OutboxTest.java`
still passes despite the bug? Which *new* test would catch it? (Write it.)

**D2.** Production incident: inventory occasionally goes negative on popular
items during deploy windows. The consumer IS idempotent (`processed_messages`
in place), the outbox IS atomic. The relay logs show restarts. Where else in
this lab's code could a duplicate-with-a-*different*-message-id come from,
so that dedupe never triggers? (Hint: read `placeOrderDualWrite` vs
`placeOrderOutbox` — where is each `message_id` generated, and what would
happen if the *client/web tier* retried a timed-out `placeOrder` HTTP call?)

**D3.** Someone reports "events arrive out of order for the same order id".
The lab's relay uses `ORDER BY seq` — so where can reordering still creep in
once this design goes to production with (a) multiple relay instances using
`SKIP LOCKED`, or (b) a Kafka producer with `retries>0` and
`max.in.flight.requests.per.connection=5` and idempotence off? Name the fix
for each.
