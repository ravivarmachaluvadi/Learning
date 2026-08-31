# Change Data Capture — exercises

Eight rungs, easiest first. Everything here is pencil-and-paper or done in
`visualization.html` — there is nothing to run and nothing to install.
Attempt a rung before opening `SOLUTIONS.md`; the whole value is in being
wrong first.

Notation used throughout: the `orders` table is
`orders(id, customer, status, total_paise, updated_at)`, and the running
example is Meera's grocery app, Kirana.

---

## Rung 1 — Explain

**1.1** In your own words, with no jargon and no notation, explain to a
colleague who has never heard the term what change data capture is and what
problem it solves. Three or four sentences. If the word "log" does not appear,
you have probably described something else.

**1.2** Explain why reading the database's recovery log is *trustworthy* rather
than a clever hack. The answer is one sentence and it is not "because it is
fast".

**1.3** Someone says: "we already have an `updated_at` column and a job that
polls it every 5 seconds — that's change data capture." Name the three things
that job cannot do, in the order you would raise them in a design review.

---

## Rung 2 — Identify

Use `visualization.html`.

**2.1** Open Tab 1 and press **Run the five failures**. When it finishes: how
many orders does the database hold, how many does the poller's index hold, and
on how many rows do they disagree? Which specific orders are wrong, and which
of §4's five failures is responsible for each?

**2.2** On Tab 1, look at the "Write-ahead log" column. What is the difference
between the two positions reported underneath it — the one the log reader "has
read up to" and the one it "last told the database"? What is the gap between
them called, and what does it cost?

**2.3** On Tab 2, run **Ben rolls back** with decoder **v2**, and stop halfway.
What is in the decoder's buffers at that moment, and what is the rule that
decides whether those events are ever published?

**2.4** On Tab 4, set the connector running with 40% of writes hitting the
captured table. What is the retained-WAL figure, and why is it that number?

---

## Rung 3 — Predict

Answer before checking. Each has a matching scenario in the visualization.

**3.1** A transaction opens at 10:00:00, sets `updated_at = now()` on order 7,
and commits at 10:00:09. The poller runs every 5 seconds. Which poll delivers
order 7? *(Tab 1 → The slow transaction.)*

**3.2** Order 104 goes `PENDING → PAID → REFUNDED → PAID` in three seconds, and
the poll interval is 5 seconds. How many events does the loyalty service — which
awards points on `PAID` — receive from the poller, and how many should it have
received? *(Tab 1 → Three changes in one interval.)*

**3.3** Two transactions are open at once. T2 starts *second* and commits
*first*. With decoder v2, which transaction's events come out first, and why is
that the right answer rather than a bug? *(Tab 2 → T2 commits before T1.)*

**3.4** PostgreSQL, default settings. Order 91 is deleted. Write out, field by
field, the `before` object in the emitted delete event. *(Tab 2 → Delete an
order, replica identity DEFAULT.)*

**3.5** The connector is stopped for maintenance on a Friday at 18:00. The
database takes 12 MB/s of writes over the weekend and `pg_wal` has 400 GB free.
Roughly when does something break, and what breaks? *(Tab 4 → Stop the
connector — note the simulation uses MB rather than GB, so scale it.)*

---

## Rung 4 — Modify

**4.1** On Tab 1, set the poll interval to its minimum (2 s) and re-run
**Three changes in one interval**. Does the poller now catch every change? Set
it to its maximum (20 s) and re-run **The five failures**. Which failures get
worse, which stay exactly the same, and what does that tell you about which
ones are *tuning* problems and which are *design* problems?

**4.2** On Tab 1, run **Restart the connector** twice: once with the consumer
set to *upsert by order id*, once with *add to a running total*. Same events,
same duplicates. Explain why one consumer is unharmed and the other is wrong,
and state the general rule in one sentence.

**4.3** On Tab 3, run protocol **C** with the consumer appending instead of
upserting. The protocol is the correct one and the result is a corrupt index.
What does that tell you about where "correctness" lives in a CDC pipeline?

**4.4** On Tab 4, run **Quiet table, busy database** with the heartbeat off,
then again with it on. Which single number on the dashboard changed behaviour,
and why does no *connector-side* metric catch this failure? Which property is
lost in the failing case — safety or liveness?

**4.5** On Tab 2, switch replica identity from `DEFAULT` to `FULL` and re-run
the delete. Name two consumers that need `FULL` and two that are perfectly
happy with `DEFAULT`, and state what `FULL` costs.

---

## Rung 5 — Trace by hand

Do this one on paper before touching the visualization.

**Starting state.** A brand-new connector is being set up against a table with
three orders. It is 10:00:00 and the current log position is `16/C000000`.

```
orders
 id | customer | status  | total_paise
  7 | asha     | PENDING |       48000
 91 | ben      | PENDING |       12500
104 | cara     | PENDING |       30000
```

**The events, in real time.** The connector uses protocol C: it opens a
repeatable-read transaction at 10:00:00, notes the log position, and reads one
row per second in id order (order 7 at 10:00:01, order 91 at 10:00:02, order 104
at 10:00:03). It then streams from the noted position, one event per second
starting at 10:00:04. Meanwhile:

| time | what happens |
|---|---|
| 10:00:01 | Asha pays. `UPDATE orders SET status='PAID' WHERE id=7` commits at log position `16/C000100`. |
| 10:00:02 | Ben's order is deleted. `DELETE FROM orders WHERE id=91` commits at `16/C000200`. |
| 10:00:03 | A transaction that opened at 10:00:00 — *before* either of the other two — finally commits, at `16/C000300`, having run `UPDATE orders SET status='PAID' WHERE id=104`. Note that it started first and its commit position is the **highest** of the three, because a commit record is written when the commit happens. |
| 10:00:07 | The connector process is killed. Its last confirmed position was `16/C000100`. |
| 10:00:09 | It restarts and resumes. |

**Write out, one line per step:** every event the search index receives, in
order, with its operation code (`r`/`c`/`u`/`d`), its key, its payload, and the
log position it carries. Then state the final contents of the search index and
whether they match the database.

Three things to be careful about, each of which is the point of the exercise:
what the snapshot reads for order 104; what order the three stream events
arrive in (start order or commit order?); and what the restart re-delivers.

---

## Rung 6 — Spot the flaw

Each of these is a design somebody has actually shipped. For each: construct a
**concrete run** — real values, real times — where it produces a wrong answer,
and name the guarantee it violates.

**6.1 The "safe" poller.** A team knows about the late-commit skip, so instead
of a high-water mark they poll with a lag: `WHERE updated_at > :hw AND
updated_at <= now() - interval '30 seconds'`, and set `hw` to `now() - 30s`.
They reason that no transaction lasts 30 seconds. Break it.

**6.2 The optimized outbox relay.** An existing relay drains an outbox table
with `SELECT * FROM outbox WHERE processed = false ORDER BY id LIMIT 500`, then
marks the rows processed. Someone profiles it, notices the `processed` index is
hot, and "optimizes" it to `SELECT * FROM outbox WHERE id > :last_id ORDER BY
id LIMIT 500`, keeping `last_id` in memory and flushing it periodically. The
change ships and the tests pass. Break it — and say precisely which property of
the original made it safe.

**6.3 The warehouse loader.** A CDC stream feeds a revenue table. The consumer
does, for each event: `INSERT INTO revenue_facts (order_id, amount, lsn) VALUES
(...)`, then separately `UPDATE checkpoint SET lsn = :lsn`. It is idempotent, the
team says, because "we can always deduplicate on `lsn` later". Break it, and
name the two distinct bugs (there are two — one about ordering of the two
statements, one about the dedup claim).

**6.4 The audit log.** A compliance system consumes the CDC stream and writes
every `before` image into an immutable audit table, so the company can always
answer "what did this row say before it changed?" It runs on PostgreSQL with
default settings, and passes its integration tests, which assert that an audit
row appears for every change. Break it, and say what the auditor sees two years
later.

**6.5 The event contract.** Team A owns the `orders` table. Team B wants order
events, so Team A points Debezium at the table and gives Team B the topic. Six
months later Team A renames `total_paise` to `total_minor_units` as part of a
tidy-up, runs its full test suite, and deploys on a Tuesday. Describe what
happens, whose fault it is, and what should have been built instead.

---

## Rung 7 — Handle a failure the design does not survive

**7.1 Failover.** Your PostgreSQL primary fails over to a standby at 03:00. The
connector reconnects, cannot find its replication slot, and its configuration
tells it to create one and carry on. Write down: (a) exactly what data is lost
and how you would size it, (b) why no alert fires, (c) three changes to the
design so this cannot silently happen again, and (d) the one alert you would
add that would have caught it. Assume you cannot upgrade PostgreSQL this
quarter.

**7.2 The unavoidable slot.** You must run CDC on a database whose `pg_wal`
volume is small and cannot be grown, and whose connector runs on a cluster that
is occasionally down for 20 minutes at a time. Design the arrangement of
settings, alerts and runbook steps that makes this survivable. State explicitly
what you are choosing to sacrifice, because you cannot have all three of:
database availability, connector resumability, and no operator involvement.

**7.3 The re-snapshot you cannot afford.** A 4 TB `orders` table's slot got
invalidated. A full snapshot takes 11 hours, single-threaded, during which the
slot retains WAL for 11 hours. Your disk cannot hold 11 hours of WAL. Design a
way out, and name the feature that exists precisely for this.

---

## Rung 8 — Design

**8.1 The read-model pipeline.** Design the CDC pipeline for Kirana:
PostgreSQL as the source; consumers are an Elasticsearch order-search index, a
Redis cache behind the order-status screen, a BigQuery revenue warehouse owned
by finance, and the loyalty service owned by another team.

Cover, and justify each: which tables you capture and which you refuse to;
what `REPLICA IDENTITY` you set on each and why; the topic and key layout; the
apply strategy for each of the four consumers (naming exactly which ones need
an explicit idempotency mechanism and what it is); how each consumer is rebuilt
from scratch on a Tuesday afternoon without touching the source database; what
you monitor, with the alert thresholds and which failure each catches; and
what you send to the loyalty service, given §12.

**8.2 The strangler.** Kirana is migrating off a 12-year-old monolith with a
2 TB MySQL database. The new order service has its own PostgreSQL. For eighteen
months both must be live and consistent: the monolith writes some orders, the
new service writes others, and both must see all of them.

Design it. You must address: the initial load; keeping both directions in sync
without an infinite loop of events echoing between them (name the mechanism);
what happens to a row written to both sides in the same second; which system is
authoritative for which fields; how you cut over; and how you would *prove*,
with a query you could run every hour, that the two databases agree — bearing
in mind that "we found no differences" is only evidence if you first prove the
comparison was looking at anything at all.

**8.3 The one-paragraph verdict.** A team proposes: "instead of building an
event API, let's just give every consuming team read access to our CDC topics —
it's the same data, it's free, and it's real time." Write the paragraph you
would post in reply. Be specific about which of their use cases this is right
for and which it is wrong for, and offer the concrete alternative rather than
only the objection.
