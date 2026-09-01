# The Idempotent Consumer — one effect per message, from a message that has no caller

> **Lab contents:** this document · [`visualization.html`](visualization.html)
> (interactive: "The Second Delivery") · [`EXERCISES.md`](EXERCISES.md) ·
> [`SOLUTIONS.md`](SOLUTIONS.md). No runnable code — mechanisms are shown as
> hand-worked traces here and as a controllable simulator there.

You already own most of the parts for this. The
[idempotency](../idempotency/) lab built a three-part protocol — the caller
mints a key, the server claims it atomically with the effect, the server
replays the stored response — and proved that the claim must be an `INSERT`
under a `UNIQUE` constraint rather than a check-then-act. The
[kafka](../kafka/) lab established that a consumer's commit position is a
forced choice with no third option: commit before processing is at-most-once,
commit after is at-least-once. The [transactional-outbox](../transactional-outbox/)
lab ended, like eight other labs, with the sentence *"…and therefore consumers
must be idempotent."*

So why a whole lab for the consumer?

Because a message consumer is not a request handler with a queue in front of
it. Three things are structurally different, and each one breaks a different
part of the protocol you already know:

1. **There is no caller.** Nobody mints a key on your behalf, and nobody is
   waiting for a response — so part 1 and part 3 of the idempotency protocol
   have no owner. The identity of the message has to come from somewhere else,
   and the first two places engineers reach for are both wrong.

2. **The duplicate does not come from one place.** On an HTTP endpoint the
   duplicate is a client retry, and it arrives seconds after the original. A
   consumer's duplicate can come from the producer, the outbox relay, a
   consumer-group rebalance, a retry topic, a dead-letter replay run by a human,
   or a deliberate reprocess after a bug fix — and those arrive anywhere from
   40 milliseconds to nine days later. That spread of *redelivery age* turns
   out to be the entire story of the hardest failure in this lab.

3. **The consumer keeps its own bookkeeping in a second system.** Your effect
   lands in Postgres; your read position lands in Kafka. That is a dual write,
   in a lab series where "dual write" has been the villain since the outbox.
   Whether it is a *dangerous* dual write is a question worth answering
   precisely, because the popular answer ("store offsets in your database") is
   often the wrong trade.

This lab opens up the slogan for the consumer specifically. By the end you
should be able to look at any consumer in your codebase and answer four
questions about it in order: *what names this message · what makes the record
of processing atomic with the effect · how long that record must survive ·
and what the operation would do if the record were gone.*

---

## Depth map

> **BEGINNER MODEL** (§1–§6): why the offset commit is not the fix, and the
> three separate obligations hiding inside the word "idempotent".
> **INTERMEDIATE MODEL** (§7–§10): the mechanism built five times, each version
> killed by a trace you can replay in the simulator.
> **ENGINEERING MODEL** (§11–§12): the consumer-specific failure catalogue and
> the exact statement of the guarantee, including the two ways it silently
> stops holding.
> **PRODUCTION MODEL** (§13–§14): Kafka/Spring, SQS, RabbitMQ, CDC, what goes
> on the dashboard, and the runbook line that prevents the worst incident in
> this lab.

---

## 1. WHY — the consumer inherits everyone else's uncertainty

Here is the cast for the whole lab. Keep them; every trace uses them.

| Actor | What it is |
|---|---|
| **`payments`** | A service. Captures a payment, writes an event to its outbox in the same transaction. |
| **`relay`** | The outbox poller. Reads unpublished outbox rows and sends them to Kafka. |
| **`payments.captured`** | A Kafka topic, 1 partition for now, so ordering is not yet a variable. |
| **`loyalty`** | Our consumer. One consumer group, `loyalty-v1`. |
| **`members`** | A Postgres table in the loyalty service. `M-77` is Asha. |
| **`evt-9f31`** | The event for payment `pay-8812`: ₹2,000, member `M-77`, worth **50 points**. |

The loyalty handler is one line:

```sql
-- the effect: accumulative, therefore NOT naturally idempotent
UPDATE members SET points = points + 50 WHERE id = 'M-77';
```

That one line is the reason this lab exists. If the effect were
`SET tier = 'GOLD'` — an *assignment* — running it twice would leave the same
state as running it once, and much of this lab would be unnecessary. Adding is
not like that. Adding remembers how many times it happened.

Now count the places a second copy of `evt-9f31` can come from. This is not a
theoretical list; every entry is a mechanism you have already learned.

1. **The relay crashed after `send()` returned but before it marked the outbox
   row published.** On restart it publishes the row again. Kafka now holds the
   same *event* at two different offsets. (This is the outbox lab's own
   at-least-once, seen from the receiving end.)
2. **The producer's network retry.** Mitigated by Kafka's idempotent producer —
   but only within one producer session, and only inside the cluster.
3. **The consumer crashed after the `UPDATE` committed and before the offset
   commit.** The forced choice from the kafka lab, paid in full.
4. **A rebalance.** The group re-assigns partition 0 while our consumer is
   half-way through a batch. The new owner starts from the last *committed*
   offset, which is behind.
5. **A retry topic.** Spring Kafka's non-blocking retries republish the failed
   record to `payments.captured-retry-1`. Same event, different topic,
   different offset, later.
6. **A dead-letter replay.** An on-call engineer re-injects 40,000 DLQ records
   nine days after the incident that produced them.
7. **A deliberate reprocess.** You fixed a bug in the points calculation and
   reset the group to offset 0 on purpose.

Seven sources. Note two properties of that list, because the rest of the lab
is built on them:

- The **time gap** between the original and the duplicate ranges over six
  orders of magnitude — milliseconds for (2), months for (6).
- The **identity** of the duplicate differs by source. In (3) and (4) it is the
  *same record at the same offset*. In (1), (5) and (6) it is a *different
  record, at a different offset, possibly on a different topic, carrying the
  same business fact*.

Any dedup scheme that only handles same-offset redelivery handles two of seven
cases. That is the trap in §10 v1, and it is extremely common in real code.

---

## 2. THE CONCRETE PROBLEM — Asha gets 100 points for one payment

Real values, ordered events. The consumer is the naive one everybody writes
first: poll, process, commit.

```
t=0     relay publishes evt-9f31            → topic offset 41
t=1     loyalty polls                        → gets [offset 41 : evt-9f31]
t=2     loyalty runs UPDATE members
          SET points = points + 50           → members: M-77 points = 50   ✅
t=3     loyalty COMMITs the DB transaction   → durable
t=4     ── the pod is evicted ──                (OOM, deploy, node drain, k8s)
                                                the offset commit never happened
t=5     new pod starts, joins group loyalty-v1
t=6     group coordinator reports committed offset = 41
t=7     loyalty polls                        → gets [offset 41 : evt-9f31]  again
t=8     loyalty runs UPDATE members
          SET points = points + 50           → members: M-77 points = 100  ❌
t=9     COMMIT, then commit offset 42        → all clean, nobody alerts
```

Nothing crashed *visibly*. No exception was logged at t=8. The consumer did
exactly what it was told. Asha has 100 points for one ₹2,000 payment, and the
only evidence is a number in a row.

> 🔮 **Predict before reading on.** The pod died between the database commit
> and the offset commit. Suppose we swap the two lines — commit the offset
> *first*, then do the `UPDATE`. What is the worst thing that can now happen,
> and is it better or worse than what you just read?

<details><summary>Answer</summary>

The window does not close, it inverts. If the pod dies after the offset commit
and before the `UPDATE`, the event is gone: the group's position has already
moved past 41, and no future poll will ever return it again. Asha gets **0
points**, permanently, and again nothing is logged.

Which is worse depends on a property most teams never state out loud:
**a duplicate is detectable and repairable downstream; a silent loss is
neither.** You can reconcile 100 points against a payments ledger and find the
double. You cannot find a payment that produced *no* loyalty record by looking
at loyalty. That asymmetry — not fairness, not elegance — is why the entire
industry standardised on at-least-once plus consumer-side deduplication.

</details>

---

## 3. THE OBVIOUS SOLUTION — remember what you have seen

If the problem is that the consumer forgot, give it a memory.

```java
// loyalty consumer, attempt 1
private final Set<String> seen = new HashSet<>();

void onMessage(Record r) {
    if (seen.contains(r.eventId())) return;   // skip the duplicate
    awardPoints(r);                            // UPDATE ... points + 50
    seen.add(r.eventId());
}
```

This is a genuinely reasonable first attempt and it *does* fix a class of
problem: two deliveries within one process lifetime, back to back, are now
handled. Run it in a test and it passes.

---

## 4. FAILURE — three ways, and only the third is interesting

**Way one: the memory is in the wrong place.** `seen` is a field on a heap that
dies with the pod. The failure in §2 is precisely a pod death. The set is empty
at t=5. Every duplicate that matters survives the thing that was supposed to
remember it.

**Way two: the memory is per-instance.** Scale the deployment to three pods and
each has its own `seen`. A rebalance moves partition 0 from pod A to pod B, and
pod B has never heard of `evt-9f31`. Even if the set were durable per pod, it
is not durable *per partition*, and partitions move.

Move the set into the database and both of those go away:

```sql
SELECT 1 FROM processed_messages WHERE event_id = 'evt-9f31';  -- seen?
UPDATE members SET points = points + 50 WHERE id = 'M-77';     -- effect
INSERT INTO processed_messages(event_id) VALUES ('evt-9f31');  -- remember
```

**Way three: the memory is checked in a gap.** You have met this exact shape
before — the idempotency lab called it check-then-act and killed it there — so
here is only what is *different* about it in a consumer.

On an HTTP endpoint, the two racers are two client retries hitting two threads.
In a consumer, the two racers are **two consumer instances that both believe
they own partition 0**. That is not a rare interleaving; it is the ordinary
outcome of a rebalance, and it lasts as long as the old owner's handler takes
to notice it has been fenced — which can be `max.poll.interval.ms`, five
minutes by default:

```
        pod A (old owner)                    pod B (new owner)
t=0     SELECT → not seen
t=1                                          SELECT → not seen
t=2     UPDATE points +50   (= 50)
t=3                                          UPDATE points +50   (= 100)
t=4     INSERT processed_messages            INSERT → unique violation, rolls back
```

Pod A is a **zombie**: still executing, already replaced. Note the shape of the
damage. The `UNIQUE` constraint did fire — but at t=4, which is after both
`UPDATE`s already landed. A constraint only protects the statements that are
inside the same transaction as the constraint violation. Here the check, the
effect and the record were three separate decisions, and only the last one was
guarded.

This is the moment the lab's real subject appears. "Make the consumer
idempotent" sounds like one obligation. It is three, and they fail
independently.

---

## 5. THE REAL REQUIREMENT — three obligations, not one

State them plainly first.

> **R1 — Identity.** Every message must carry a name that stays the same across
> every path that can redeliver it.
>
> **R2 — Atomicity.** The record "I have processed this name" must become
> durable in the *same atomic act* as the effect it guards.
>
> **R3 — Memory.** That record must still exist when the duplicate arrives —
> and the arrival time of the duplicate is not bounded by anything in your
> code.

R2 is the one you already know how to satisfy: it is the idempotency lab's
single-transaction claim, and for a consumer whose effect is a local database
write it is genuinely easy. **R1 and R3 are the consumer's own problems**, they
have no counterpart in the HTTP case, and each of them has a popular answer
that is wrong in a way that produces no error message.

The rest of the mechanism section is those three obligations, in that order.

> **Checkpoint.** You can now explain why committing the offset is not a fix in
> either direction, and why a durable dedup table checked *before* the effect
> is still broken. Next: where the message's name comes from, which is the
> obligation everyone assumes is already solved.

---

## 6. THE KEY IDEA

A consumer becomes idempotent when it stops asking *"have I seen this
delivery?"* and starts asking *"has this **fact** already been applied to my
state?"* — and records the answer in the same commit as the application.

Everything below is the consequence of taking "fact" and "same commit"
literally.

---

## 7. TERMINOLOGY — the words this lab earns

You already own *at-least-once*, *idempotency key*, *dedup window*, *offset*,
*consumer group*, *rebalance*, *DLQ*, and *effectively-once* from
[kafka](../kafka/) and [idempotency](../idempotency/). These four are new here.

**Delivery identity vs message identity.**
*Plain:* where a copy arrived versus what the copy says.
*Precise:* delivery identity is `(topic, partition, offset)` — assigned by the
broker at append time, unique per *append*. Message identity is a value carried
inside the record — assigned by the producer at creation time, identical across
every append of the same fact.
*Why the distinction exists:* a single business fact can be appended more than
once (relay republish, retry topic, mirrored cluster), and each append gets a
fresh delivery identity. Deduplicating on delivery identity therefore fails
exactly on the sources that produce duplicates *across* appends.
*Example:* `evt-9f31` at `payments.captured/0/41` and again at
`payments.captured/0/58` after a relay restart. Two delivery identities, one
message identity.
*Connects to:* the [CDC](../change-data-capture/) lab's `source.lsn` — that
field exists so a sink can use message identity rather than its position in
the stream.

**Inbox table (a.k.a. `processed_messages`).**
*Plain:* the consumer's list of facts it has already applied.
*Precise:* a table keyed by `(consumer, message_id)` with a `UNIQUE` constraint,
written in the same transaction as the effect. It is the mirror image of the
outbox: the outbox makes "I decided this" and "I told someone" atomic; the
inbox makes "I was told this" and "I acted on it" atomic.
*Why:* it converts "did I already do this?" from a question about your own past
behaviour into a database constraint, which is the only place in the system
where two racers get serialized for free.

**Redelivery age.**
*Plain:* how long after the original a duplicate can turn up.
*Precise:* the maximum wall-clock interval between two deliveries of one
message identity, taken over every redelivery source your architecture has.
*Why it needs a name:* it is the input to the only sizing decision in the
pattern (how long the inbox must remember), it is not a configuration value
anywhere, and it is usually set by a human running a DLQ replay rather than by
software.

**Watermark (high-water-mark) deduplication.**
*Plain:* instead of a list of everything seen, keep the highest number seen.
*Precise:* per dedup scope (usually per entity key), store the largest
monotonically increasing sequence applied; apply an incoming message only if
its sequence is strictly greater, then advance the stored value in the same
transaction.
*Why:* it turns R3 from a retention problem into no problem at all — a number
that only moves forward never needs pruning — at the price of requiring
monotone, per-key-ordered sequences.
*Connects to:* it is the same move as `expected_version` in
[event-sourcing](../event-sourcing/) and `source.lsn` in
[CDC](../change-data-capture/), used defensively.

---

## 8. MENTAL MODELS

### Simple model — the bouncer with a ledger

A club, one door, one bouncer, one ledger. A guest may only be let in once.
The bouncer must do three things, and they map exactly onto R1/R2/R3:

- **Recognise the guest** — by a name, not by which taxi they arrived in (R1).
- **Write the name and open the door as one movement** — because if he opens
  the door and *then* writes, someone can slip in during the pen stroke (R2).
- **Not tear out yesterday's page** while yesterday's guest can still come
  back (R3).

### Engineering model

```
                     ┌──────────────────────────────────────────────┐
 broker              │              consumer process                │
 ┌───────────┐       │                                              │
 │ offset 41 │──poll─┼─► handler(record)                            │
 │ evt-9f31  │       │      │                                       │
 └───────────┘       │      │  ┌──── ONE database transaction ───┐  │
       ▲             │      └─►│ INSERT inbox(consumer,msg_id)   │  │
       │             │         │        ▲ UNIQUE ⇒ arbitrates    │  │
       │             │         │ UPDATE members SET points+50    │  │
   commit offset ◄───┼─────────│ COMMIT                          │  │
   (after commit,    │         └─────────────────────────────────┘  │
    and only as an   │                                              │
    optimisation)    └──────────────────────────────────────────────┘
```

Read the diagram for one thing: **the offset commit is outside the box.** It is
not part of the correctness argument. It is an optimisation that stops you
re-reading records you have already suppressed. If it fails, you re-read, and
the inbox absorbs it. That single observation dissolves a lot of anxiety about
"the consumer's dual write" — we make it precise in §10 v2.

### Where the analogy breaks

The bouncer analogy hides the two hardest facts, so replace them now:

- **There are two bouncers, not one**, whenever a rebalance is in flight, and
  they are both convinced they are the only one. The ledger is what makes them
  agree — not their belief about who owns the door. Any design where the
  bouncer decides based on something *he* remembers rather than on a write that
  the ledger accepts or rejects is broken under this fact.
- **The guest can come back wearing a different wristband.** Republication
  gives the same fact a new delivery identity. A ledger that records wristband
  numbers will happily admit the same guest twice.

---

## 9. VISUALIZATION — "The Second Delivery"

Open [`visualization.html`](visualization.html) now, or after §10 v2. It runs
the loyalty consumer against the topic with four selectable designs — the four
versions built below — and lets you inject the four redelivery sources by hand.

**What to look at:** the verdict line at the top says how many points Asha has
for *one* payment. Everything else on the page exists to explain that number.
The dedup store panel is the interesting one: watch what goes into it in each
design, and — in design C — watch what the retention slider throws away.

**The question it answers:** each design suppresses some duplicates and misses
others. The simulator's point is that *which* ones it misses depends entirely
on which redelivery source you inject, and no design is safe against all four
until you get to D (and even D trades something away).

---

## 10. THE MECHANISM — built five times

Each version fixes the previous one's death and then dies of something new.

---

### v1 — dedup on the offset

The instinct is: Kafka already gives every record a unique address; use it.

```sql
CREATE TABLE processed_offsets (
  topic     text,
  partition int,
  "offset"  bigint,
  PRIMARY KEY (topic, partition, "offset")
);
```

```
BEGIN;
  INSERT INTO processed_offsets VALUES ('payments.captured', 0, 41);
  UPDATE members SET points = points + 50 WHERE id = 'M-77';
COMMIT;
```

R2 is satisfied — the record and the effect are one transaction, and the
primary key arbitrates between two racers instead of a `SELECT` doing it. Run
the §2 trace and the §4 zombie trace against this: **both are fixed.**

```
t=8   second delivery of offset 41
      INSERT processed_offsets (…,41) → PK violation → transaction rolls back
      the UPDATE rolls back with it → M-77 stays at 50 points ✅
```

That is a real improvement and it is where a great many production consumers
stop. Now break it.

> 🔮 **Predict:** the relay crashed after `send()` and republished the outbox
> row. Kafka now holds `evt-9f31` at offset 41 **and** at offset 58, and the
> consumer has already processed 41. What does v1 do at offset 58?

<details><summary>Answer</summary>

`INSERT INTO processed_offsets VALUES ('payments.captured', 0, 58)` succeeds —
58 has genuinely never been seen. The `UPDATE` runs. Asha has 100 points.

The dedup table did its job perfectly and the outcome is still wrong, because
the table was answering the wrong question. It knows every *delivery* it has
consumed. It knows nothing about which *facts* it has applied.

</details>

The same hole opens for three more sources: the retry topic (different topic
name → different key), a mirrored or migrated cluster (offsets do not survive
MirrorMaker; they are re-assigned), and any reprocess where records are
re-published rather than re-read.

**v1's lesson, which is the lab's first main result:**
*offsets are delivery identity. Deduplication requires message identity, and
only the producer can supply it.*

Concretely, the producer must put an id in the event at creation time — for an
outbox, the outbox row's id, minted inside the same transaction as the state
change:

```sql
-- in the payments service, one transaction
UPDATE payments SET status='CAPTURED' WHERE id='pay-8812';
INSERT INTO outbox(id, topic, key, payload)
VALUES ('evt-9f31', 'payments.captured', 'M-77', '{...}');
```

Republished later, from the same row, that `id` is unchanged. That is the
property that makes it usable.

---

### v2 — dedup on the message id, in one transaction with the effect

```sql
CREATE TABLE inbox (
  consumer   text  NOT NULL,      -- 'loyalty-v1'
  message_id text  NOT NULL,      -- 'evt-9f31'
  applied_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, message_id)
);
```

```
BEGIN;
  INSERT INTO inbox(consumer, message_id) VALUES ('loyalty-v1','evt-9f31');
  UPDATE members SET points = points + 50 WHERE id = 'M-77';
COMMIT;                              -- on PK violation: roll back, skip, ack
```

Two details in that DDL are load-bearing and both are routinely missed:

- **`consumer` is part of the key.** Three services consume
  `payments.captured`. If they share one `inbox` table keyed only by
  `message_id`, the first one to process an event suppresses it for the other
  two. Either key by `(consumer, message_id)` or give each consumer its own
  table. (One table with a compound key is usually better: one retention job,
  one dashboard.)
- **The `INSERT` comes first.** Not because ordering matters to the database —
  inside one transaction it does not — but because putting it first makes the
  duplicate path cost one failed insert rather than a full effect plus a failed
  insert. On a hot partition that is the difference between a cheap skip and a
  lock-contention incident.

Now re-run every trace so far:

| Redelivery source | v1 | v2 |
|---|---|---|
| Crash before offset commit (§2) | ✅ suppressed | ✅ suppressed |
| Rebalance zombie (§4) | ✅ suppressed | ✅ suppressed |
| Relay republish, new offset | ❌ **double** | ✅ suppressed |
| Retry topic | ❌ double | ✅ suppressed |
| DLQ replay 9 days later | ❌ double | ✅ suppressed (for now — see v3) |

#### The consumer's dual write, settled

Your effect committed to Postgres. Your offset commits to Kafka. Two systems,
two writes, no atomicity between them. Given how much damage the dual-write
problem has done in this curriculum, this deserves a straight answer rather
than a reflex.

Enumerate the four crash points:

| # | DB txn | offset commit | Consequence |
|---|---|---|---|
| 1 | ✗ | ✗ | Redelivered, processed. Correct. |
| 2 | ✓ | ✗ | Redelivered, **inbox suppresses it**. Correct. |
| 3 | ✗ | ✓ | **Event skipped forever. Data loss.** |
| 4 | ✓ | ✓ | Correct. |

Row 3 is the only bad one, and it is unreachable as long as you commit the
offset *after* the database transaction — which is exactly what
`enable.auto.commit=false` plus a manual ack after the handler returns gives
you. So:

> **The consumer's dual write is benign, because the second write is guarded by
> the first.** The inbox turns "the offset commit was lost" from a correctness
> problem into a performance problem: you re-read some records and throw them
> away.

This matters practically, because the popular alternative — **store the offset
in your own database inside the same transaction** — is real, correct, and
usually a worse trade than it looks:

- It *only* handles same-offset redelivery. It is v1 wearing a different hat,
  and it inherits v1's blindness to republication. Teams adopt it believing it
  is stronger than an inbox; it is strictly weaker.
- It requires a `ConsumerRebalanceListener` that seeks to your stored offsets on
  every partition assignment, so you now own a piece of the group protocol.
- It is defeated by any deliberate replay, because your stored offset *is* the
  thing you were trying to move.

**Rule:** you need offsets in your database only if you have decided not to
keep an inbox. Keeping both is coherent (Spring's Kafka + JPA chained
transaction managers effectively do), but it buys nothing the inbox has not
already bought.

> **Checkpoint.** v2 is a correct idempotent consumer for a local, transactional
> effect. If your handler only writes to one database, and your inbox never
> forgets, you are done — and most of the internet's advice ends here. The next
> two versions exist because "never forgets" is not a thing a table does.

---

### v3 — the inbox has to forget, and that is where the incident lives

`payments.captured` carries 4,000 events/second. That is 345 million inbox rows
a day, 126 billion a year. The table is not a design; it is a countdown. So
somebody adds the obvious cleanup:

```sql
DELETE FROM inbox WHERE applied_at < now() - interval '7 days';
```

Seven days sounds generous. Consumer restarts take seconds; rebalances take
tens of seconds; the relay republishes within a minute. Seven days is four
orders of magnitude of headroom against every duplicate anyone has seen.

> 🔮 **Predict:** Diwali weekend, the loyalty consumer's downstream tier-service
> is down for two hours, so 40,000 records land in the DLQ. The incident is
> closed on Monday. Nine days later, during cleanup, an engineer replays the DLQ
> to "get those members their points". What happens?

<details><summary>Answer</summary>

Every one of the 40,000 events is now older than the retention window. Their
inbox rows were deleted on day 7. To the consumer they are brand new facts, and
they are applied a second time: 40,000 members get double points, in a single
batch, at 3pm on a Tuesday, from an operation whose entire purpose was to
*repair* the incident.

Nothing errors. The replay tool reports 40,000 successes.

</details>

This is the most important failure in the lab, so state its lesson exactly:

> **The inbox's retention must exceed the maximum redelivery age — and
> redelivery age is set by human operational behaviour, not by any config file
> in your repository.**

There is no software setting anywhere that bounds "how long after an incident
might someone replay a dead-letter queue". Which produces an uncomfortable
conclusion: for the DLQ-replay and deliberate-reprocess sources, a
retention-based inbox is not a guarantee, it is a *bet* — and it is a bet whose
losing side is silent.

Three honest ways to respond, in order of how much they actually fix:

1. **Make retention explicitly longer than your stated maximum replay age, and
   write that number in the runbook.** "DLQ records older than 30 days must not
   be replayed; regenerate them from source instead." Cheap, and it converts a
   silent failure into a documented policy — but it is only as good as the
   human reading it.
2. **Give replays their own dedup scope.** A replay run gets a fresh
   `consumer` value (`loyalty-v1-replay-2026-09-01`) and, crucially, is
   *reviewed* rather than trusted. This is honest about the fact that a replay
   is a deliberate reprocess, not a duplicate.
3. **Stop needing retention at all** — which is v4.

---

### v4 — the watermark: memory that never expires because it never grows

Change what is stored. Instead of a set of facts, keep one number per entity.

Have the producer stamp each event with a per-key monotonically increasing
sequence. For an outbox this is easy: a per-aggregate counter incremented in
the same transaction, or the outbox row id if a single writer produces the
key's events in order.

```
evt-9f31  member M-77  seq 17  +50 points
evt-a044  member M-77  seq 18  +30 points
```

```sql
CREATE TABLE members (
  id          text PRIMARY KEY,
  points      int  NOT NULL,
  last_seq    bigint NOT NULL DEFAULT 0    -- the watermark, IN the entity row
);
```

```sql
BEGIN;
  UPDATE members
     SET points   = points + 50,
         last_seq = 17
   WHERE id = 'M-77'
     AND last_seq < 17;          -- the entire dedup mechanism, one predicate
COMMIT;
-- 0 rows updated ⇒ already applied (or superseded) ⇒ skip and ack
```

Look at what just happened to the three obligations:

- **R1 identity** — satisfied by `(key, seq)`, which is producer-minted and
  survives republication exactly like an event id does.
- **R2 atomicity** — satisfied more cheaply than before: the guard is a `WHERE`
  clause on the row being modified. No second table, no second index, no extra
  write. This is the [optimistic-vs-pessimistic-locking](../optimistic-vs-pessimistic-locking/)
  lab's conditional write in yet another costume.
- **R3 memory** — *dissolved*. `last_seq` is one number per member that only
  moves forward. It is never pruned, so there is no window, so the nine-day DLQ
  replay in v3 is suppressed correctly. Storage is O(entities), not O(messages)
  — a few million rows instead of a hundred billion.

It also gives you something the inbox never did: **reorder safety**. If
`seq 18` is processed before `seq 17` (retry topic, partition change,
concurrent handlers), `seq 17` is rejected by `last_seq < 17` rather than
applied out of order.

> 🔮 **Predict:** with v4 in place, an engineer resets the consumer group to
> offset 0 to reprocess the last month after fixing a points-calculation bug.
> What happens?

<details><summary>Answer</summary>

**Nothing happens.** Every event in the topic has a sequence at or below the
watermark already stored on its member row, so every `UPDATE` matches zero
rows. The consumer runs at full speed through a month of data, logs nothing
unusual, commits offsets, and changes no state at all.

If the intent was "suppress duplicates", this is a triumph. If the intent was
"recompute everyone's points with the fixed formula", it is a silent no-op that
will be discovered by a customer.

</details>

That is v4's honest cost, and it is the mirror of v3's: **the same property
that makes a watermark retention-free makes a deliberate replay impossible
without an explicit reset.** A well-run system therefore ships a documented
procedure for both — "how to reprocess" is a first-class operation, not an
improvisation.

The other three costs, stated plainly:

- **It needs per-key ordered delivery.** With key-based partitioning
  (`key = M-77`) Kafka gives you that per partition. Lose it — repartition,
  parallelise a partition across threads, route through a retry topic — and
  events arrive out of order, where the watermark does not merely fail to
  dedup: it *discards* legitimate updates. That is worse than a duplicate.
- **It needs a monotone per-key sequence from the producer.** Not every
  producer has one. CDC gives it free (`source.lsn`); a hand-written outbox
  needs a counter added.
- **It is per (key, stream).** Two different event types updating the same
  member need either one shared sequence or one watermark column each.

---

### v5 — what a production consumer actually is

No single design wins. A real consumer picks per handler, from four tools, in
this order of preference:

**1. Natural idempotency — use it whenever the operation allows.** If the
effect can be written as an *assignment* rather than an accumulation, no dedup
machinery is needed for duplicates at all:

```sql
UPDATE orders SET status = 'SHIPPED' WHERE id = 'ord-51';   -- idempotent
INSERT ... ON CONFLICT (id) DO UPDATE SET ...               -- idempotent
INSERT INTO shipment_events(event_id, ...) VALUES (...)      -- idempotent (PK)
```
Beware the standing trap: naturally idempotent ≠ reorder-safe. `SET status =
'SHIPPED'` applied after `SET status = 'DELIVERED'` moves the order backwards,
and no dedup scheme in this lab notices. Idempotence handles duplicates;
versioning handles order; locking handles concurrency. Three orthogonal
defences, and you frequently need two.

**2. Watermark — when events for a key are ordered and carry a monotone
sequence.** Cheapest, retention-free, reorder-safe. Default choice for
projections, read models, replicated caches, CDC sinks — anything shaped like
"apply this stream of changes to this entity".

**3. Inbox table — when events are independent facts with no per-key order.**
"Send a welcome email", "award points for this specific payment", "create a
shipment". Needs a documented retention ≥ maximum redelivery age.

**4. Claim-first + external effect — when the effect leaves the database.**
This is the [idempotency](../idempotency/) lab's v4 shape, unchanged: the claim
commits alone as `RUNNING`, the external call happens carrying *its own*
idempotency key, the outcome commits second, and a reaper re-drives claims
stranded in `RUNNING`. Nothing about being a consumer changes it — except that
the "caller" who must not be left in doubt is now the broker's redelivery, and
a stranded `RUNNING` claim will be re-driven by the redelivery itself if you
let it.

---

## 11. FAILURE SCENARIOS — the consumer's own catalogue

The general shape of duplicate-handling is now settled. These are the failures
specific to living behind a broker.

### F1 — Crash between the effect and the offset commit
*How:* pod eviction, OOM, deploy, node drain — routine, several times a week.
*Watch:* the same offset appearing twice in the consumer log.
*Result:* redelivery. Suppressed by v2/v4. **Not** suppressed by v1 if the
redelivery arrives via republication instead.
*What stays intact:* no loss, because the offset never advanced.

### F2 — Rebalance with an in-flight handler (the zombie)
*How:* the handler exceeds `max.poll.interval.ms` (default 5 min), the
coordinator declares the member dead and reassigns the partition — while the
handler is still running and still holding a DB connection.
*Result:* two consumers execute the same message concurrently. This is the
trace in §4.
*What saves you:* only a conditional write at the database — the inbox PK or
the `last_seq <` predicate. The loser's whole transaction rolls back.
*What does not save you:* any in-memory check, any `SELECT` before the effect,
and — importantly — **any assumption that partition assignment is exclusive**.
It is exclusive from the *coordinator's* point of view, never from the point of
view of code already running.
*Production shape:* correlated with a long-tail dependency. The fix that
removes the cause is smaller batches and a `max.poll.interval.ms` above your
p99.9 handler time; the fix that removes the *damage* is the conditional write.

### F3 — Producer/relay republication
*How:* the relay sent the record but crashed before marking the outbox row
published; on restart it sends again.
*Result:* same fact, new offset. **v1 is defeated; v2 and v4 hold.**
*Note:* Kafka's idempotent producer does not help. It deduplicates a
`(PID, epoch, sequence)` conversation within one producer session; a relay
restart is a new session and, from Kafka's view, an honestly new record.

### F4 — Retry-topic reordering
*How:* Spring Kafka's non-blocking retries publish a failed record to
`…-retry-1` with a backoff, and the main partition continues.
*Result:* `seq 17` is retried while `seq 18` sails past on the main topic and
is applied first. Deduplication is not violated — both are applied once — but
the *order* is. An inbox consumer produces `points = base + 30 + 50` (fine,
addition commutes) while an assignment consumer produces the wrong final
status (not fine).
*What this teaches:* non-blocking retries buy availability by trading away
per-key ordering. If your handler relies on order, blocking retries (or a
watermark that rejects the late one) is the correct choice, and the watermark's
"rejection" is now a deliberate drop you must be able to explain.

### F5 — DLQ replay past the retention window
The §3/v3 incident. The only failure in this lab whose cause is a human with
good intentions. *Watch:* a spike in successful applications with `applied_at`
far from `event_time`. That gap — **event age at apply time** — is the metric
that would have caught it, and almost nobody graphs it.

### F6 — Deliberate reprocess suppressed by your own dedup
*How:* group reset to offset 0 after fixing a calculation bug.
*Result:* v2 skips everything with a PK violation; v4 skips everything below
the watermark. Both are working exactly as designed, and both defeat the
operation.
*Response:* a reprocess is a different intention, so give it a different dedup
scope (new `consumer` value / reset watermarks / a dedicated backfill path that
computes rather than accumulates). The general principle from the idempotency
lab holds here too: *different intentions must not share a key.*

### F7 — Poison message and the claim-before-effect trap
*How:* one record makes the handler throw every time. With blocking retries it
head-of-line-blocks the partition; lag climbs while throughput reads normal for
every other partition.
*The trap specific to this lab:* if you "claim" the message by inserting into
the inbox in its **own** transaction before doing the work — a natural instinct
after reading the idempotency lab — then the failing message is marked
processed and will never be retried. You have converted a loud poison message
into a silent skip. For a local effect the inbox insert must be in the *same*
transaction as the effect so it rolls back with it. Claim-first is only correct
when paired with `RUNNING` state and a reaper, i.e. only for external effects.

### F8 — Non-transactional sink
*How:* the handler writes to Elasticsearch, S3, Redis, or an email provider.
There is no transaction to put the inbox row into, so R2 cannot be satisfied
the easy way.
*Responses:* (a) make the sink hold the dedup itself — an ES document id equal
to the message id, or `version_type=external` with the event's sequence as the
version, which is a watermark implemented by the sink; (b) claim-first with a
downstream idempotency key (email providers and payment APIs accept one); (c)
for a genuinely unkeyable sink, accept at-least-once, reconcile, and publish the
measured residual — the idempotency lab's "box G", unchanged.

### F9 — Concurrency inside one consumer
*How:* you parallelise a partition across a thread pool for throughput. Two
threads process two *different* events for member `M-77` simultaneously.
*Result:* not a duplicate — a lost update. Dedup does nothing. You need row
locking or a version check, and you have just given up per-key ordering (see
F4). *The distinction to hold onto:* deduplication answers "is this the same
fact twice?"; concurrency control answers "are two different facts colliding?".
Same table, different problem.

### F10 — The inbox becomes the bottleneck
*How:* every message now costs an extra indexed insert, and the table is the
hottest write target in the schema. Index bloat, autovacuum pressure
([mvcc-and-vacuum](../mvcc-and-vacuum/)), and a retention `DELETE` that
competes with the workload it protects.
*Responses:* partition the table by day and `DROP` partitions instead of
`DELETE` (one metadata operation vs a hundred million row versions); prefer the
watermark where the shape allows it; and never let the retention job be the
thing that discovers the table is too big.

---

## 12. CORRECTNESS — the exact guarantee, and its two silent failure modes

**What is guaranteed** (design v2 or v4, local transactional effect):

> For a given `(consumer, message identity)`, the effect is applied **at most
> once**, and — because delivery is at-least-once — **exactly once in effect**.
> Two consumers racing on the same message produce one application and one
> rolled-back transaction.

**What that guarantee rests on.** Each of these is an assumption, and each one
is a real place production systems break:

1. The producer mints a stable identity that survives republication. *(Breaks
   if the id is generated at send time rather than at fact-creation time.)*
2. Identities do not collide across different facts. *(Breaks if someone uses a
   business key like `orderId` for events that can legitimately repeat.)*
3. The dedup record and the effect are in one transaction against one database.
   *(Breaks the moment a second sink is added to the handler.)*
4. The dedup record outlives every redelivery. *(Breaks by TTL — F5.)*
5. The scope is per consumer. *(Breaks when a second consumer shares the
   table.)*

**What is not guaranteed, and never was:**

- **Exactly-once delivery.** Impossible, for the reason the
  [idempotency](../idempotency/) lab established from the two-generals result.
  What you have is at-least-once delivery plus at-most-once *application*.
- **Ordering.** Deduplication is order-blind. F4 and §10-v5 spell out the
  separate mechanism.
- **Protection against business duplicates.** Two *different* events describing
  the same real-world fact (a double-submitted refund with two event ids) have
  two identities and will both be applied. Deduplication is defined on
  identities, and only the producer knows which two sends are one intention.
- **Anything outside the transaction.** The email sent in the middle of the
  handler was sent, whatever the transaction later decides.
- **Safety of a replay.** Suppression is not safety; §10-v4 and F6.

**The trade you made.** Every version above buys correctness with a write on
the hot path — an extra insert (v2) or an extra predicate and column (v4) on
every single message — plus a permanent operational obligation (retention, or a
documented reprocess procedure). That is the cost of at-least-once. The
alternative is not "no cost"; it is at-most-once and silent loss.

---

## 13. PRODUCTION — where this lives in real systems

### Kafka + Spring

The pieces you will actually touch, and what each one silently decides:

- **`enable.auto.commit`** — the default `true` is not at-least-once. It commits
  on a timer *during* `poll()`, so what it means depends on code far away: with
  inline processing it approximates coarse commit-after; hand the batch to a
  thread pool and it becomes silent at-most-once. Set it `false` and ack
  explicitly. (Established in the [kafka](../kafka/) lab; repeated because it is
  the single most common cause of the F1 family in the wild.)
- **`@Transactional` on the `@KafkaListener` method** — puts the inbox insert
  and the effect in one transaction, which is exactly v2. The offset ack happens
  after the method returns, outside that transaction. That is the correct and
  intended arrangement; see §10-v2's crash table for why the gap is benign.
- **`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`** — blocking retries
  then DLQ. Preserves order, blocks the partition.
- **`@RetryableTopic`** — non-blocking retries. Does not block, and gives up
  per-key ordering (F4). Choosing between these two is choosing between
  availability and order; there is no third option.
- **Kafka transactions / EOS** — makes read→process→write→commit-offset atomic
  **inside Kafka**. If your consumer's output is another Kafka topic, EOS is the
  complete answer and you need none of this lab. If your consumer writes to a
  database, calls an API, or sends an email, EOS covers none of it and you need
  all of this lab. This distinction is the single most valuable sentence to be
  able to say out loud in a design review.

### SQS

- **Standard queues** are at-least-once by contract, and duplicates arrive
  through **visibility-timeout expiry**: if your handler outruns the timeout,
  the message becomes visible again and a second worker takes it *while the
  first is still working*. That is F2's zombie, from a different mechanism, and
  it is much more frequent than a Kafka rebalance.
- **FIFO queues** offer a `MessageDeduplicationId` with a **5-minute** window.
  Read that as: AWS deduplicates producer retries for you and nothing else.
  Every source in §1 with a gap longer than five minutes is yours to handle.

### RabbitMQ

The `redelivered` flag on a delivery is a *hint*, not a guarantee — it can be
`false` on a genuine redelivery after a broker failover. Never branch on it for
correctness; use it only for logging.

### CDC / Debezium sinks

The best-behaved case in practice, because the producer's identity problem is
already solved: `source.lsn` (Postgres) or the binlog coordinate (MySQL) is a
monotone per-source sequence, and records are keyed by primary key. A sink that
upserts by PK and guards with `WHERE last_lsn < :lsn` is v4 with no extra work
— which is what the [CDC](../change-data-capture/) lab meant by "convergence
under replay".

### The dashboard

Four things worth graphing, and only the first is commonly present:

1. **Consumer lag** — the one everyone has.
2. **Duplicate suppression rate** — inbox PK violations, or zero-row watermark
   updates, per second. A steady low rate is healthy; a sudden spike means a
   rebalance storm or a republish loop. A rate of exactly zero, forever, means
   your dedup has never been exercised and you do not know whether it works.
3. **Event age at apply time** (`now() − event_timestamp`) — the metric that
   catches F5 before it becomes an incident, and the one nobody has.
4. **Inbox size and oldest-row age** — sized against a *written down* maximum
   redelivery age, not against disk.

### The runbook line

One sentence, in the DLQ replay procedure, that prevents this lab's worst
outcome:

> *Before replaying dead-lettered records, confirm the records are younger than
> the consumer's inbox retention. Older records must be regenerated from the
> source of truth, not replayed.*

---

## 14. COMMON WRONG MODELS

**"We turned on the idempotent producer / EOS, so consumers don't need this."**
Feels reasonable because both features have "exactly once" in their marketing.
Smallest counterexample: idempotent producer on, EOS on, consumer writes to
Postgres, pod dies between the DB commit and the offset commit. Redelivery,
double points. EOS is exactly-once *within the Kafka cluster*; the moment an
effect leaves the cluster it is at-least-once again.

**"Deduplicate on the offset."** Feels reasonable because the offset is
genuinely unique. Smallest counterexample: relay republishes the same outbox row
after a crash; new offset, same fact, applied twice (§10-v1).

**"An inbox table gives exactly-once."** It gives at-most-once *application*
given four assumptions (§12), and quietly stops giving it when one of them
lapses — most often retention.

**"Check whether we've seen it, then process."** The gap is not a rare
interleaving; a rebalance produces two live processors as a matter of routine
(§4). Only a conditional write arbitrates.

**"Seven days of retention is plenty."** It is plenty for every duplicate a
machine produces and useless against the one a human produces. Redelivery age
is bounded by your operational practice, not your software (§10-v3).

**"Idempotent means I can safely replay anything."** Replay is *suppressed*,
which is not the same as safe. A reprocess intended to fix data will do
nothing at all, silently (§10-v4, F6).

**"Our handler is `UPDATE … SET status='PAID'`, so it's idempotent and we're
done."** It is idempotent, and it is not reorder-safe, and non-blocking retries
reorder. Duplicates, ordering and concurrency are three problems with three
mechanisms (F4, F9).

**"The consumer's dual write is the same bug as the outbox's."** It looks
identical and it is not, because the failure mode it produces (a lost offset
commit) is absorbed by the inbox, whereas the outbox's failure mode (a lost
event) is absorbed by nothing. The asymmetry is the whole argument (§10-v2).

---

## 15. KNOWLEDGE MAP & NEXT STEPS

**Builds directly on**

- [idempotency](../idempotency/) — the claim-under-a-unique-constraint, the
  check-then-act gap, claim-first-for-external-effects. This lab is that
  protocol with the caller removed and the redelivery sources multiplied.
- [kafka](../kafka/) — offsets, consumer groups, rebalance,
  `max.poll.interval.ms`, retry topics, DLQ, idempotent producer, EOS.
- [transactional-outbox](../transactional-outbox/) — the inbox is its mirror
  image, and the relay is redelivery source #1.
- [optimistic-vs-pessimistic-locking](../optimistic-vs-pessimistic-locking/) —
  the watermark predicate is the conditional write again.
- [change-data-capture](../change-data-capture/) — `source.lsn` is a
  ready-made watermark; CDC sinks are the easiest idempotent consumers there are.

**Related**

- [saga](../saga/), [cqrs](../cqrs/), [event-sourcing](../event-sourcing/) — all
  three assume idempotent consumers; this is the mechanism they assumed.
- [mvcc-and-vacuum](../mvcc-and-vacuum/) — why a `DELETE`-based retention job on
  a hot table is a worse idea than partition-and-drop.
- [distributed-locking](../distributed-locking/) — the zombie consumer in F2 is
  its expired-lease problem, and the answer is the same: do not trust ownership,
  guard the write.

**Commonly confused with**

- *Idempotent producer* — deduplicates a producer's network retries inside one
  session. Different layer, different lifetime, does not help you.
- *Exactly-once semantics (EOS)* — atomic read-process-write **within Kafka**.
  Covers no effect that leaves the cluster.
- *Deduplication in the broker* (SQS FIFO, 5 minutes) — a producer-retry
  window, not a consumer guarantee.
- *Optimistic locking* — same primitive, different question: it rejects a
  *stale* writer; deduplication rejects a *repeat* writer.

**Next lessons**

- `/learn backpressure` — the consumer-side pressure this lab assumed away.
- `/learn schema-evolution` — the inbox survives a redeploy; does the payload?
- `/learn observability-slos` — "event age at apply time" needs somewhere to live.
- `/learn stream-processing-semantics` — windowing and joins, where dedup and
  ordering stop being separable.

---

## 16. GLOSSARY (in order of appearance)

- **Redelivery source** — any mechanism that can hand a consumer a second copy
  of one fact. Seven of them are enumerated in §1.
- **Accumulative vs assignment effect** — `points = points + 50` versus
  `status = 'PAID'`. The first is not naturally idempotent; the second is.
- **Zombie consumer** — an instance still executing a handler for a partition
  the coordinator has already reassigned. Produces two concurrent writers.
- **Delivery identity** — `(topic, partition, offset)`. Assigned by the broker,
  unique per append, *not* stable across republication.
- **Message identity** — an id carried inside the record, minted by the producer
  when the fact was created. Stable across every republication.
- **Inbox table** — the consumer's `processed_messages`, keyed by
  `(consumer, message_id)`, written in the effect's transaction.
- **Redelivery age** — the maximum wall-clock gap between two deliveries of the
  same fact, across all redelivery sources. Sets the inbox's required retention.
- **Dedup scope** — the `consumer` component of the inbox key. Two consumers of
  one topic need two scopes; a deliberate reprocess needs a third.
- **Watermark deduplication** — storing the highest applied per-key sequence
  instead of a set of seen ids. Retention-free and reorder-safe; requires
  per-key ordering and a monotone producer sequence.
- **Event age at apply time** — `now() − event_timestamp` at the moment the
  effect commits. The metric that detects a replay outside the dedup window.
- **Effectively-once / exactly-once processing** — at-least-once delivery plus
  at-most-once application. The only honest form of "exactly once" that crosses
  a system boundary.
