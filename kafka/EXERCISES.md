# Exercises — Apache Kafka

Pencil-and-paper and simulator work only; there is nothing to run. Attempt each
rung before opening [`SOLUTIONS.md`](SOLUTIONS.md). The simulator is
[`visualization.html`](visualization.html) ("The Deodar Ledger"), and section
numbers refer to [`README.md`](README.md).

The rungs get harder in a specific way: 1–3 check that you *have* the model, 4–5
check that you can *run* it, and 6–8 check that you can find the smallest
situation where a design is wrong — which is the only skill that transfers to
production.

---

## Rung 1 — Explain

1.1 In your own words, with no jargon: what did Kafka give up compared with a
classic message broker, and what did it buy with what it gave up? One paragraph,
no lists.

1.2 A colleague says "Kafka is just a queue with better throughput." Give the
single smallest concrete scenario that shows this is not a throughput
difference but a semantic one.

1.3 Explain to a product manager why a brand-new team can read last month's
events out of Kafka but could not out of RabbitMQ — without using the words
offset, partition, or retention.

1.4 The lab claims that requirements 2, 5 and 6 of §6 *force* the design. Restate
that argument in three sentences: if reading does not consume, and each reader has
its own position, and the broker's per-reader bookkeeping must be tiny, then what
must a reader's state be — and why does no other option survive?

1.5 Explain the difference between "a committed record" and "a committed offset".
Both appear in every Kafka conversation and they are unrelated.

## Rung 2 — Identify (in the simulator)

2.1 Tab 1. Press "Scan parcel P-1001" four times. Which partition holds them?
Read the routing table: what is the arithmetic that put them there, and what
would have to change for the answer to change?

2.2 Tab 1. Press "notifications: poll" twice, then "analytics: poll" once. How
many records has each group read, how many records did the broker delete, and
what is the total number of copies of each record stored on the broker?

2.3 Tab 2. Produce one record with `acks=all` but do **not** run a replication
round. What is the record's state, what is the high watermark, and what would a
consumer see if it polled right now?

2.4 Tab 2. Freeze B3 and run three replication rounds. At which round does the
ISR change, and what number is the high watermark computed over afterwards?

2.5 Tab 3. Set the protocol to cooperative and add two consumers. In the cost
table, which number would be identical under both protocols and which would not —
and what real-world quantity does "partition-seconds stopped" correspond to on a
dashboard?

2.6 Tab 4. Append six records for P-1001 with `cleanup.policy=compact` and run the
cleanup pass. Exactly which records survive, and why is one of the survivors *not*
the latest value of some key?

## Rung 3 — Predict

Write your answer down before checking.

3.1 A topic has 4 partitions. A producer sends, in this order, records with keys
`A, B, A, C, A`. A consumer group with 4 consumers reads them. In what order can
the three `A` records be processed? In what order can `A` and `B` be processed?

3.2 `RF=3, acks=all, min.insync.replicas=2`. One broker is restarted for a kernel
patch. What do producers see during the restart? Now the second broker is
restarted before the first has caught up. What do producers see?

3.3 A consumer group has 8 consumers on 8 partitions using the **eager** protocol.
You do a rolling restart, one consumer at a time, waiting for the group to
stabilise between each. How many rebalances happen, and roughly how many
partition-seconds of processing are lost at 3 s per rebalance?

3.4 A consumer with `enable.auto.commit=true` (5 s interval) polls 500 records and
takes 20 s to process them, then crashes at second 12. Which records are
reprocessed on restart, and which are silently skipped? (Careful — the answer
contains both.)

3.5 A topic has `retention.ms=86400000` (1 day). A consumer group is stopped on
Friday evening with a lag of 3 hours and restarted on Monday morning with
`auto.offset.reset=latest`. Describe exactly what the lag graph looks like across
those three days, and what a dashboard-watcher would conclude.

3.6 A transactional producer writing to `fraud-alerts` hangs mid-transaction. A
`read_committed` consumer of that topic and a `read_uncommitted` consumer of the
same topic are both running. What does each see over the next 90 seconds?

## Rung 4 — Modify

4.1 Change `min.insync.replicas` from 1 to 2 on a RF=3 topic. Name one failure
that becomes impossible and one that becomes possible. Which of the two would you
rather explain to a customer?

4.2 In tab 1, add a partition and then produce more scans for a parcel that
"moved". Now propose a change to the *key* that would have made the partition
increase harmless. What does your change cost?

4.3 The default `max.in.flight.requests.per.connection` is 5. Set it to 1. What
improves, what gets worse, and — the real question — what does the idempotent
producer make of the change?

4.4 A team sets `linger.ms=100` (up from the 4.x default of 5). Trace the effect
on: producer latency, batch size, compression ratio, broker request rate, and
consumer lag. Which of those five move in the direction the team wanted?

4.5 Someone proposes running the whole company on one topic with one partition
"so everything is strictly ordered". Take that seriously for a moment: what
exactly *does* it guarantee? Then name the three things it costs, in order of how
soon they will hurt.

## Rung 5 — Trace

Work these on paper, writing the state after each step.

5.1 Partition with replicas A (leader), B, C; all at LEO 5, HW 5;
`acks=all`, `min.insync.replicas=2`. Trace the state (LEO of each, HW, ISR,
producer's view) through:

```
1. producer sends record at offset 5
2. B fetches
3. C is frozen by a GC pause
4. 30 s pass
5. producer sends a record at offset 6
6. B fetches
7. A crashes
8. C recovers and returns
```

At each step say what the producer has been told, and at the end say which
records exist and which are gone.

5.2 Group `g1` reads a 3-partition topic with 2 consumers (`C1` has p0 and p1,
`C2` has p2). `C3` joins. Under the cooperative protocol, list every step of the
reconciliation: what is revoked, what keeps running, what the final assignment is,
and where the offsets for the moved partition come from.

5.3 Compacted topic, records in offset order:
`0:(a,1) 1:(b,1) 2:(a,2) 3:(c,1) 4:(b,null) 5:(a,3)`, segment size 3, so offsets
0–2 are a closed segment and 3–5 are the active one. Run the cleaner. Which
offsets remain? Now append `6:(d,1)` (closing the second segment) and run the
cleaner again. Which offsets remain, and what does a consumer replaying from 0
build as its table?

5.4 Trace a consume-process-produce loop with transactions: `beginTransaction`,
read offset 100 from `in`, write a record to `out`, `sendOffsetsToTransaction`,
`commitTransaction`. Say what is written where, in what order, and what a
`read_committed` consumer of `out` can see at each step.

## Rung 6 — Spot the flaw

Each design below is *almost* right. Find the smallest concrete scenario where it
fails, and name the guarantee it violates.

6.1 "We need ordering per customer, so we key by `customer_id`. And we want good
balance, so we run a nightly job that increases the partition count whenever a
partition gets more than 100 GB."

6.2 "Our consumer is idempotent, so delivery semantics don't matter to us. We use
`enable.auto.commit=true` and never think about it again."

6.3 "To make sure we never lose an event, we set `acks=all` and `retries=MAX_INT`
on the producer, and RF=3 on every topic. Durability solved."

6.4 "Our fraud consumer must never fall behind, so if processing a record throws,
we log the error and commit the offset anyway. Lag stays at zero."

6.5 "We store the current state of every parcel in a compacted topic and read it
at startup to rebuild an in-memory map. Since compaction keeps the latest value
per key, the map is always correct after replay."

6.6 "We use exactly-once semantics (`processing.guarantee=exactly_once_v2`), so
our downstream payment API will never be called twice for the same scan."

6.7 "Consumer lag is the only alert we need — it covers every Kafka failure that
matters, because anything that goes wrong eventually shows up as lag."

## Rung 7 — Handle failure

Design answers, not one-liners. Say what you would build, what it costs, and what
it still does not cover.

7.1 A single record in the middle of a partition cannot be deserialised and your
consumer crash-loops on it. The partition is 40 minutes behind and the business is
noticing. Give (a) what you do in the next ten minutes, and (b) what you build so
this cannot recur — including what your rebuilt design does with the bad record,
who finds out about it, and how.

7.2 A consumer group's processing calls a downstream API whose p99 has degraded
from 40 ms to 4 s. Describe the failure that will follow within an hour if nothing
changes, the sequence of events that produces it, and three independent changes
that each break the loop. Which one would you make first, and why that one?

7.3 You must guarantee that a customer is never double-charged, and your payment
step is an HTTP call from a Kafka consumer. Design it. Be explicit about which
part of the guarantee Kafka provides and which part it cannot, where the dedup
state lives, how long it must live, and what happens if the payment API times out
without answering.

7.4 A broker's disk fills at 03:00 and it drops out of the ISR for 20 topics.
Walk through what happens to producers and consumers for topics configured
(a) `min.insync.replicas=1` and (b) `min.insync.replicas=2`, and say which of the
two situations you would rather be paged for.

7.5 A cross-region consumer must survive the loss of an entire region. Sketch the
design, then name the specific thing that makes consumer failover across clusters
hard — the thing that has nothing to do with the data itself.

## Rung 8 — Design

8.1 Size a topic for Deodar Dispatch's next phase: 40,000 scans/second at peak,
average record 500 bytes, retention 3 days, RF=3, a consumer that handles 800
records/s per instance and must be able to catch up 6 hours of backlog within 1
hour. Give partition count, storage, and the arithmetic. Then name the two numbers
you are least confident about and how you would measure them.

8.2 Your company has one Kafka cluster and 60 teams. Write the five rules you
would enforce on every new topic — the ones that are hard to change later — and
for each say what breaks if it is wrong and how expensive the repair is.

8.3 You are asked to replace a database table (`parcel_current_state`, 200 M rows,
queried by parcel id at 5,000 QPS) with "just Kafka". Argue both sides honestly,
then give your recommendation and the one measurement that would change your mind.

8.4 Design the observability for a Kafka-based pipeline with four consumer groups.
Name the metrics, the alert thresholds, and — the part people skip — for each
alert, the specific action the on-call engineer should take when it fires. Include
at least one alert for a failure that is *silent* in every other metric.

8.5 Counterfactual. Suppose Kafka had no high watermark and consumers could read
any record the leader had written. Construct the smallest scenario where a
consumer observes something that later becomes false, and describe precisely what
downstream systems would have to do to remain correct in such a world.

8.6 Counterfactual. Suppose partitions could be split like database shards, with
records redistributed across the new partitions. What would break? Name three
things in the lab that depend on a partition's offsets being permanent and
gap-free-in-order — and then say why "the offset is a position in one file" is
worth all the inconvenience it causes.
