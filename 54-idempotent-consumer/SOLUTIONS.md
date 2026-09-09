# Solutions — The Idempotent Consumer

Attempt [`EXERCISES.md`](EXERCISES.md) first. Design questions (levels 7–8) show
a worked reasoning process, not a single correct answer — what is being checked
is whether you reached for the right *obligation*, not whether you matched the
words here.

---

## Level 1 — Explain

**1.1** When a sender gets no reply, it cannot tell "you never received this"
from "you did it and the reply was lost", so it has to send again — and that is
true of every hop, including the one between your consumer and its own
bookkeeping. So copies of the same message are normal traffic, not a bug. A
consumer therefore cannot be built to *receive* each message once; it can only
be built to *act* on each message once, which is a different and achievable
goal. Everything else in the lab is about how it recognises that it has already
acted.

**1.2**
- **Identity** — a stable name for the fact. Missing it, you deduplicate the
  *address* rather than the fact, and every republication slips through
  (§10-v1).
- **Atomicity** — the record of processing and the effect commit together.
  Missing it, two racers both pass the check and both act; the constraint fires
  too late to prevent anything (§4).
- **Memory** — the record still exists when the duplicate arrives. Missing it,
  the duplicate is a new fact; the classic form is a DLQ replay past the
  retention window (§10-v3).

**1.3** Both are failure modes; only one is *observable after the fact*. A
duplicate leaves evidence — two applications of one identity — that can be
found by reconciling against the source, and repaired. A dropped message leaves
nothing: you cannot query loyalty for a payment that produced no loyalty
record, because the absence looks identical to a payment that never happened.
The industry chose the failure it can detect.

---

## Level 2 — Identify

**2.1**

| Thing | Kind | Why it matters |
|---|---|---|
| `(topic, partition, offset)` | delivery | fresh on every append; blind to republication |
| outbox row UUID | **message** | minted with the fact, survives every republish — the right dedup key |
| `(PID, epoch, sequence)` | delivery | a producer *session*'s conversation with a broker; a relay restart makes a new PID |
| SQS `MessageId` | delivery | assigned per send; a re-send after visibility timeout is a *redelivery* of the same MessageId, but a producer re-send is a new one |
| SQS `MessageDeduplicationId` | **message** | producer-supplied — but honoured for only 5 minutes |
| Debezium `source.lsn` | **message** (and monotone) | position in the source WAL, stable across connector restarts — a ready-made watermark |
| your `orderId` | **message**, conditionally | correct only if one order produces at most one such event; wrong the moment an order can be updated twice |

**2.2**
- **(a) and (b) together are the entire correctness argument.** They must be in
  one transaction; that is what `@Transactional` is doing, and it is the only
  reason the zombie in §4 is harmless.
- **(c) is an optimisation.** If the ack is lost the records are re-read and
  (a) rejects them. See §10-v2's four-row crash table: the only bad row is
  "effect not committed, offset committed", which acking *after* the method
  makes unreachable.

**2.3** The `SELECT 1 FROM processed_messages` — the check — is the culprit, and
its problem is not where it sits in the transaction but that it exists at all.
A read cannot arbitrate between two writers. Replace it with the `INSERT`
itself, inside the effect's transaction, and let the `UNIQUE` violation be the
answer. This is the check-then-act result from the
[idempotency](../49-idempotency/) lab, and the reason it bites harder here is that
the two racers are two *consumer instances*, which a rebalance produces
routinely.

---

## Level 3 — Predict

**3.1** 80 (each fact once). Same offset redelivered, primary key rejects it,
transaction rolls back. This is the case design B was built for.

**3.2** 130 — `evt-9f31` applied twice. The republished copy has a genuinely
new offset, so the key accepts it. Verified in the visualization's scenario 2.

**3.3** Retention 7: **160**, both facts doubled — the inbox rows were deleted
on day 7 and the replayed events are new to the consumer. Retention *forever*:
**80**, both suppressed. Nothing else changes between the two runs, which is
the whole lesson: the design was never wrong, the *memory* was.

**3.4** `points = base + 30`, `last_seq = 18`. `seq 17` is rejected because
`18 < 17` is false. That is the third answer: **correct but a deliberate
decision**. The watermark did not deduplicate anything here — it *discarded a
legitimate update* because it arrived out of order. For an accumulative effect
that is data loss; for an assignment effect (a projection catching up) it is
exactly right. Which is why the choice between blocking and non-blocking
retries (F4) is not a tuning question.

**3.5** **Zero rows.** Every sequence in the log is at or below the watermark
already on the row, so every `UPDATE` matches nothing. Full-speed run, clean
logs, committed offsets, no state change. Visualization scenario 4.

---

## Level 4 — Modify

**4.1** Better: the duplicate path gets cheaper (one insert, no effect work),
and the claim is visible to other processes immediately so a second consumer
can back off rather than contend. Catastrophic: a message whose handler throws
is now permanently marked processed — the claim committed, the effect did not,
and the redelivery is suppressed. A loud poison message becomes a **silent
skip**. That is **F7**. Claim-first is only correct when it also writes a
`RUNNING` state that a reaper re-drives — i.e. only when the effect is external
and cannot be in your transaction anyway.

**4.2** The first service to process an event inserts the row; services two and
three then hit the primary key violation and conclude "already processed", so
they skip the message entirely. They lose data, quietly, from the very
mechanism installed to protect them, and the loss is proportional to how often
they lose the race — which is *most of the time* if service one is faster. It
is noticed when someone reconciles a downstream total, typically weeks later.
Fix: key on `(consumer, message_id)`.

**4.3** Three things, and the third is not code:
1. Retention becomes ≥ 90 days plus a margin. Sizing changes accordingly — at
   4,000/s that is ~31 billion rows, which forces the table to be partitioned
   by day with `DROP PARTITION` rather than `DELETE` (F10), or forces a move to
   a watermark design where the shape allows.
2. The dedup key must be certain to be stable over 90 days — no
   content-derived keys, no keys that a producer redeploy could change.
3. **The runbook and the replay tool** must state and enforce the limit:
   records older than retention are regenerated from source, never replayed.
   Retention is only a guarantee if something refuses the operation that would
   exceed it.

**4.4** Designs A, B and C give the same guarantee as before — deduplication is
per-message and does not depend on order. **Design D breaks**: two threads
processing `seq 17` and `seq 18` concurrently for the same member can apply 18
first, after which 17 is silently dropped. You spent **per-key ordering**,
which was Kafka's only ordering guarantee and D's precondition. If you need the
throughput and the watermark, partition by key across *consumers*, not threads
within one.

---

## Level 5 — Trace

**5.1 Design C, retention forever**

| Step | inbox | points |
|---|---|---|
| off 41 `evt-9f31` | `{9f31@d0}` | 50 |
| off 42 `evt-a044` | `{9f31, a044}` | 80 |
| off 43 `evt-9f31` (republish) | unchanged — PK violation, txn rolls back | 80 |
| off 44 `evt-b210`, pod A | `{9f31, a044, b210}` | 100 |
| off 44 `evt-b210`, pod B (zombie race) | unchanged — PK violation | 100 |

Final: **100 points, three facts, each once.** Correct.

**5.2 Design D** — same final points (100) and same final `last_seq` (19), but
the *writes* differ: C performs an insert into a second table plus an index
maintenance operation for every applied message and a rejected insert for every
duplicate; D performs one conditional `UPDATE` against a row it was already
going to touch, and a zero-row `UPDATE` for every duplicate. Over a year at
4,000/s that is ~126 billion inbox rows versus zero extra rows. That difference
— not correctness — is the reason to prefer D when its preconditions hold.

**5.3 Design B** — identical through offset 42. **Divergence at offset 43**:
`(0/43)` has never been seen, so the insert succeeds and `evt-9f31` is applied a
second time → 130. Everything afterwards is correct again (the zombie at 44 is
still caught), which is what makes this bug so durable in production: the design
works for every failure the team has *tested*, and fails only for the one that
requires a relay crash to reproduce.

---

## Level 6 — Spot the flaw

**6.1** Nothing is wrong with the SQL. The flaw is what is **missing**: no
`consumer` scoping in the key you would need if any other service shares the
table (4.2), and — the intended answer — **no retention policy at all**, which
is correct on day one and an outage by month six (F10). The smallest failing
scenario is not a correctness trace, it is a capacity one: the table's index
stops fitting in memory, insert latency crosses the handler's budget, handlers
exceed `max.poll.interval.ms`, and the resulting rebalance storm produces
exactly the zombies the table was protecting you from. Violates **R3**, in the
direction nobody plans for. Dashboard: inbox size and p99 handler latency
rising together, weeks before anything breaks.

**6.2** Violates **R1**. The key is derived from content, so two legitimately
distinct facts with identical content collide — Asha buys the same ₹500 coffee
twice on the same day and the second purchase is silently swallowed as a
"duplicate". Smallest scenario: two identical purchases, one day. Worse, it is
undetectable from inside the consumer: suppressing a real event and suppressing
a duplicate look identical. Only the producer knows which two sends are one
intention; a consumer that invents an identity is guessing. Dashboard: nothing.
Ever. That is the point.

**6.3** Violates **R1**. Offsets are delivery identity, so this is §10-v1 with
extra machinery: it survives crashes and rebalances and is defeated by the relay
republish, the retry topic, a cluster migration and any replay. It is not
stronger than an inbox, it is strictly weaker, *and* it costs you a
`ConsumerRebalanceListener` and a piece of the group protocol. Smallest
scenario: relay crashes after `send()`, republishes, double application.

**6.4** Violates **R1** and D's monotonicity precondition. `currentTimeMillis()`
is not monotone across producers (clock skew — see
[clock-synchronization](../30-clock-synchronization/)), not monotone across a
single producer's NTP correction, and not unique under 4,000/s on one
millisecond. Smallest scenario: two events for one member from two producer
pods whose clocks differ by 30 ms; the later fact carries the smaller number
and is silently discarded. Note the failure mode is **loss, not duplication** —
a watermark's failures always are, and that is why its sequence source must be
a real counter (an outbox row id, an LSN, a per-aggregate version), never a
clock.

**6.5** Violates **R2**, subtly, and only under a specific timing. The batching
and sleeping mean the retention job is not atomic with anything: it deletes
inbox rows in one transaction while the consumer is applying messages in
another. That is fine. The real flaw is that the job's *criterion* is
`applied_at`, which is when the consumer acted, while the risk it must bound is
the **redelivery age**, which is measured from the *event's* creation. A
message that sat in a retry topic for six days is applied on day 6 and its
inbox row is deleted on day 13 — seven days of protection for a fact that is
already thirteen days old, against a replay window everyone believes is seven
days. The window you think you have and the window you have differ by the
consumer's own lag. Dashboard: **event age at apply time** — and if that graph
does not exist, nothing shows this at all.

---

## Level 7 — Handle failure

**7.1** The move is to stop trying to make one transaction cover two systems
and instead give the second system its own idempotence.

- Points: inbox row + `UPDATE` in the Postgres transaction, as before.
- Elasticsearch: index with `_id = eventId` (or `_id = memberId` with
  `version_type=external, version=seq`). An index call with a fixed document id
  is an upsert — naturally idempotent, and the second call produces the same
  document.
- Order: Postgres transaction first, ES second, and let redelivery re-drive ES
  if the consumer dies between them. Committing the offset only after both means
  the failure is a redelivery, and both sides absorb it.

**Not guaranteed:** that the two systems are ever consistent *with each other*
at an instant. There is a window where points are awarded and the ES document
is missing, bounded by the redelivery interval, and if the consumer is down for
an hour the window is an hour. You are buying per-system idempotence, not
cross-system atomicity — that would need the effect to be one system, which is
what [CQRS](../51-cqrs/)'s "the read model is a lagging derived copy" was already
telling you.

**7.2** This is the [idempotency](../49-idempotency/) lab's v4, unchanged.

```
poll → INSERT claim(eventId, state=RUNNING)   COMMIT       (alone)
     → SMS provider call, carrying idempotency key = eventId
     → UPDATE claim SET state=DONE, response=… ; effects   COMMIT
     → commit offset
```
Die after the SMS but before the second commit: the claim sits in `RUNNING`.
Two things can re-drive it, and you should have both — the broker's redelivery
(the offset was never committed, so the message comes back) and a reaper that
sweeps `RUNNING` claims older than a threshold, which covers the case where the
offset *was* committed. The re-driven call carries the same key, so the provider
replays its own stored response rather than sending a second SMS. The
guarantee's last mile lives in the provider, which is the recursion the
idempotency lab described: each hop's doubt is absorbed by the next hop's key
table.

**7.3**
(a) The partition stops. Nothing behind the poison record is processed.
(b) Unaffected — the other partitions run normally, so aggregate throughput
barely moves.
(c) There is **no inbox row**, because the transaction rolls back every time.
That is correct and is what makes retry safe.
(d) Queue depth on that partition is *flat and low*, because one message is
being retried, not accumulating.

(d) is the reason for the rule. Depth is a count; a stuck partition has a small
count and an unbounded **age**. The alarm that fires is "oldest unprocessed
message age > N minutes"; the alarm that stays silent forever is anything
counting messages.

**7.4** Design D suppresses replays by construction, so the procedure must make
the reprocess a *different intention* rather than a duplicate.

1. Decide the scope precisely: which members, which date range, which formula
   version. "Everyone" is a scope; "all of it" is not.
2. Do not use the live consumer. Build a **backfill job** that reads the source
   of truth (the payments ledger, or the topic with a dedicated group id) and
   **computes** each member's correct total rather than accumulating deltas.
   Computed totals are assignments — naturally idempotent, safely re-runnable,
   and immune to whatever the watermark says.
3. Write the result to a shadow column or table first. Diff against live.
   Expect the diff to be non-empty; investigate any member whose diff is *zero*
   as hard as any whose diff is large.
4. Cut over with an assignment (`SET points = :computed`) that also sets
   `last_seq` to the highest sequence included, so the live consumer resumes
   cleanly from the right watermark and does not re-apply events the backfill
   already covered.
5. Leave the live consumer running throughout; step 4's watermark write is what
   makes that safe.

The general principle: **a recomputation is not a replay.** Replay re-applies
history through a path designed to suppress repetition; recomputation derives
the answer and assigns it.

---

## Level 8 — Design

**8.1** The four questions, in this order, because each one's answer constrains
the next:

1. **What names this message, and does that name survive republication?**
   A bad answer (an offset, a content hash) means every other answer is
   irrelevant — you are deduplicating the wrong thing.
2. **What makes the record of processing atomic with the effect?** A bad answer
   (a `SELECT` first, a separate transaction, an in-memory set) costs you
   double-application under every rebalance, i.e. weekly.
3. **How long must that record survive, and what enforces the limit?** A bad
   answer costs the F5 incident: a repair operation that doubles data, silently,
   run by a human trying to help.
4. **What would this handler do if the record were gone entirely?** This is the
   quality question. If the honest answer is "nothing bad — the operation is an
   assignment/upsert", the team needs far less of the machinery above. If it is
   "double the money", the first three answers had better be excellent.

**8.2** Volume forces the choice. 20,000/s is 1.7 billion messages/day; an inbox
with any usable retention is 50–150 billion rows, so **design D (watermark)** is
the only affordable option — *if* events are keyed by merchant and carry a
monotone sequence. Against the obligations: R1 satisfied by `(merchantId, seq)`,
producer-minted; R2 by a conditional `UPDATE` on the daily-total row itself, no
second table; R3 dissolved. Storage is O(merchants × days), a few million rows,
not O(messages). Precondition to verify explicitly: partitioning is by
`merchantId` and retries are **blocking**, because non-blocking retries would
reorder and the watermark converts reordering into silent loss.

Page on: **event age at apply time, p99**. It catches lag, stalled partitions,
and out-of-window replays with one number, and it is the one that would have
caught every failure in §11 except the zombie (which the conditional write
handles without human involvement anyway). A daily reconciliation of the totals
against the payments ledger is the backstop, and its residual is the number you
publish.

**8.3**

*Becomes unnecessary:* the inbox table, its retention policy, the retention
runbook line, the watermark's dedup role, and the duplicate-suppression metric.

*Does not become unnecessary:*
- **Ordering.** Exactly-once delivery says nothing about sequence; retry topics
  and repartitioning still reorder, and versioning is still required.
- **Concurrency control.** Two *different* messages touching one row still race
  (F9).
- **The consumer's dual write.** Your effect is still in a different system from
  your read position; "delivered exactly once" does not mean "your database
  transaction committed".
- **External effects.** The SMS you sent mid-handler is still sent whatever the
  transaction decides, and the payment API still needs its own idempotency key.
- **Business duplicates.** Two distinct events describing one real-world fact
  are two messages; delivery semantics cannot see that.
- **Reprocessing.** You still need a procedure for recomputation, and it still
  cannot be "replay the topic".

The second list is longer than the first, which is the exercise's point:
"exactly-once" was never the whole problem, and most teams who believe they have
solved it have solved the smallest part of it.

**8.4** Something close to:

> EOS makes read → process → write → commit-offset atomic **inside Kafka**, so
> it fully covers consumers whose only output is another Kafka topic — for those
> it genuinely does remove the need for an inbox. It covers nothing that leaves
> the cluster, so any consumer that writes to a database, calls an API or sends
> a message still needs consumer-side deduplication exactly as before. It also
> has two costs worth stating: a hung transactional producer stalls every
> `read_committed` consumer until `transaction.timeout.ms`, and
> `isolation.level` still defaults to `read_uncommitted`, so enabling it on
> producers changes nothing until every consumer is changed too.

The class for which the proposal is correct: **Kafka-to-Kafka stream processors**
(Kafka Streams applications, filters, enrichments, aggregations whose entire
output is topics and changelogs).

**8.5**

| Panel | Question it answers | Shape that means trouble |
|---|---|---|
| **Consumer lag per partition** | Are we keeping up? | One partition flat while others drain = poison record (F7). Alarm on *age*, not count. |
| **Duplicate suppression rate** | Is the dedup working, and is something upstream misbehaving? | A spike = rebalance storm or a republish loop. A permanent **zero** is worse than a spike: it means the mechanism has never been exercised and you do not know whether it works. |
| **Event age at apply time (p99 / max)** | Are we applying facts far older than our dedup remembers? | Any value approaching inbox retention. This is the panel almost nobody has, and it is the only one that catches F5 *before* the double-application. |
| **Inbox size and oldest-row age** | Is retention doing what the runbook claims? | Size growing faster than volume (the delete job is losing), or oldest-row age drifting below the documented maximum replay age (your stated guarantee quietly stopped being true). |
