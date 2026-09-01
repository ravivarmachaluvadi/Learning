# Exercises — The Idempotent Consumer

Work through these before opening [`SOLUTIONS.md`](SOLUTIONS.md). Levels 1–5
check that you can operate the mechanism; 6–8 check that you understand why it
is shaped the way it is. Several ask you to verify with
[`visualization.html`](visualization.html) — do that rather than trusting the
prose, including this document's.

Cast, unchanged from the lab: `payments` → outbox → `relay` →
`payments.captured` → `loyalty` (group `loyalty-v1`) → `members` in Postgres.
`evt-9f31` is member `M-77`, sequence 17, worth 50 points.

---

## Level 1 — Explain

**1.1** In four sentences, explain to a colleague who has never used a broker
why a consumer that "just processes each message once" is not a thing that
exists. Do not use the words *idempotent*, *exactly-once*, or *at-least-once*.

**1.2** The lab claims "idempotent consumer" is really three obligations, not
one. Name them, and for each give the one-line failure that happens when only
that one is missing.

**1.3** Why is it *fortunate* that the industry standardised on at-least-once
delivery rather than at-most-once? Answer in terms of what you can and cannot
detect after the fact.

---

## Level 2 — Identify

**2.1** For each item, say whether it is **delivery identity** or **message
identity**, and in one clause say why it matters:
`(topic, partition, offset)` · the outbox row's UUID · Kafka's producer
`(PID, epoch, sequence)` · SQS `MessageId` · SQS `MessageDeduplicationId` ·
Debezium's `source.lsn` · your `orderId`.

**2.2** In this handler, name every line that is part of the correctness
argument and every line that is merely an optimisation:

```java
@KafkaListener(topics = "payments.captured")
@Transactional
public void onCaptured(PaymentCaptured e, Acknowledgment ack) {
    inboxRepo.insert("loyalty-v1", e.eventId());     // (a)
    memberRepo.addPoints(e.memberId(), e.points());  // (b)
    ack.acknowledge();                               // (c)
}
```

**2.3** In the §4 zombie trace, the `UNIQUE` constraint *did* fire and the
outcome was still wrong. Point at the exact statement whose position in the
transaction caused that, and say what it should have been instead.

---

## Level 3 — Predict

For each, commit to an answer before checking in the visualization.

**3.1** Design B (dedup by offset). The pod is evicted between the database
commit and the offset commit. Points?

**3.2** Design B. The relay republishes `evt-9f31` at a new offset after the
first copy was processed cleanly. Points?

**3.3** Design C (inbox by event id), retention 7 days. A DLQ replay on day 9.
Points? Now change retention to *forever* and predict again.

**3.4** Design D (watermark). Events arrive `seq 18`, then `seq 17` (a retry
topic delivered 17 late). What is on `M-77` at the end, and is that outcome
correct, incorrect, or "correct but a decision someone should have made
deliberately"?

**3.5** Design D. The group is reset to offset 0 to recompute points after a
formula bugfix. Predict the number of database rows changed.

---

## Level 4 — Modify

**4.1** You move the `INSERT INTO inbox` out of the handler's transaction and
into its own transaction that commits *first*. Name two things that get better
and one thing that becomes catastrophic. Which failure in §11 is it?

**4.2** Three services consume `payments.captured` and all three write to the
same `inbox` table, keyed on `message_id` alone. Describe what the second and
third services observe, and how long it takes anyone to notice.

**4.3** Your inbox retention is 7 days. The business asks you to support
replaying dead-lettered records "up to a quarter old". What exactly must change,
and which of those changes is not a code change?

**4.4** You raise consumer throughput by processing each partition across an
8-thread pool. Which of the four designs still gives the guarantee it gave
before, and which property did you spend to buy the throughput?

---

## Level 5 — Trace

**5.1** Hand-trace the following against **design C** with retention = forever.
Write the state of `members.points` and the inbox after every line.

```
day 0   offset 41  evt-9f31  seq 17  +50   → processed, offset committed
day 0   ── relay republishes evt-9f31 at offset 43 ──
day 0   offset 42  evt-a044  seq 18  +30
day 0   offset 43  evt-9f31  seq 17  +50
day 5   ── rebalance: pod B takes over mid-handler on offset 44 ──
day 5   offset 44  evt-b210  seq 19  +20   (both pods run it)
```

**5.2** Same trace against **design D**. Where do the two designs produce
different *database writes* even though they produce the same final points?

**5.3** Now run 5.1 against **design B** and mark the exact line where the two
diverge.

---

## Level 6 — Spot the flaw

Each design below is subtly wrong. For each: construct the *smallest* concrete
scenario where it fails, name the obligation (R1/R2/R3) it violates, and say
what an operator would see on a dashboard.

**6.1**
```sql
BEGIN;
  UPDATE members SET points = points + 50 WHERE id = 'M-77';
  INSERT INTO inbox(consumer, message_id) VALUES ('loyalty-v1', :eventId);
COMMIT;
```
(Note: this is *not* about statement order inside the transaction.)

**6.2** The consumer computes its own dedup key:
`sha256(memberId + amount + eventDate)`.

**6.3** The consumer stores its offsets in Postgres inside the effect's
transaction and keeps no inbox table, on the reasoning that this is strictly
stronger than an inbox.

**6.4** A watermark consumer where the producer stamps `seq` with
`System.currentTimeMillis()` at send time.

**6.5** The retention job runs `DELETE FROM inbox WHERE applied_at < now() -
interval '7 days'` every night at 02:00, and the DBA has tuned it to run in
batches of 10,000 with a sleep between batches so it does not affect the
workload.

---

## Level 7 — Handle failure

**7.1** The loyalty handler must now also push a document into Elasticsearch.
There is no shared transaction. Design the consumer so that a redelivery
produces one points award and one ES document, and state precisely what you are
*not* guaranteeing and why.

**7.2** The handler calls an external SMS provider to tell Asha about her
points. The provider accepts an idempotency key. Sketch the states the consumer
moves through, what happens if it dies after the SMS is sent but before
anything is recorded, and what re-drives it. Name which lab this shape comes
from.

**7.3** A poison record makes the handler throw on every attempt. You have
blocking retries and a DLQ. Walk through what happens to (a) the partition,
(b) consumer lag on the *other* partitions, (c) the inbox row for that message,
and (d) what an alert on queue depth would show. Which of those four is the
reason "alarm on age, never on count" is a rule.

**7.4** You discover the loyalty points formula has been wrong for a month and
must be recomputed for 4 million members. Your consumer is design D. Write the
procedure. It should not contain the words "just replay the topic".

---

## Level 8 — Design

**8.1** You are designing the consumer contract for a new platform with 40
teams. Write the four questions every team must answer about each handler
before it goes to production, in the order they must be answered, and say what
a bad answer to each one costs.

**8.2** A payments reconciliation service consumes `payments.captured` and
maintains a daily total per merchant. Volume is 20,000/s. Choose a design from
§10-v5, justify it against the three obligations, size the storage, and name the
one metric you would page on.

**8.3** Counterfactual. Suppose brokers *did* offer true exactly-once delivery
to a consumer — one delivery, guaranteed, forever. List everything in this lab
that becomes unnecessary, and everything that does **not**. (There is more in
the second list than in the first; that is the point of the exercise.)

**8.4** Your company runs Kafka and is considering enabling EOS
(`transactional.id` on producers, `read_committed` on consumers) as a
platform-wide policy, described in the proposal as "this removes the need for
idempotent consumers". Write the three-sentence reply that a design review
needs, and name the one class of consumer for which the proposal is actually
correct.

**8.5** Design the dashboard. Four panels, each with the question it answers and
the shape that means trouble. At least one of them must be a metric almost no
team has.
