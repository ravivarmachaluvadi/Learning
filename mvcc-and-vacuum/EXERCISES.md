# Exercises — MVCC and VACUUM

Eight rungs, easiest first. Nothing here asks you to write code; every answer is
reasoning, a trace, or a design. Work them with `README.md` and
`visualization.html` open — several are meant to be *checked* against the
visualization rather than merely thought about.

Solutions are in `SOLUTIONS.md`. Attempt first; the design exercises in
particular have no single right answer and the solutions show a reasoning
process rather than a verdict.

---

## Rung 1 — Explain

**1.1** Explain to a backend engineer who has never heard of MVCC why a database
would write a *new copy* of a row instead of changing it in place. Six sentences
or fewer. **You may not use the words "version", "snapshot", "MVCC", or
"concurrency".** If you find yourself reaching for them, you are describing the
mechanism instead of the problem it solves.

**1.2** A colleague says: *"Autovacuum is causing our load spikes. Let's turn it
off on the hot tables and run VACUUM in a nightly window instead."* Give the
two-part answer: what is actually right about their observation, and what will
go wrong.

**1.3** PostgreSQL has VACUUM. InnoDB has purge threads and a "history list
length". Oracle raises `ORA-01555 snapshot too old`. SQL Server fills up
`tempdb`. CockroachDB has `gc.ttlseconds`. In **one sentence**, say what all five
are responses to. Then, in one more sentence, name the single design decision
that splits those five into two camps, and say which camp each is in.

---

## Rung 2 — Identify

**2.1** A table's tuples for row `id = 7`:

| tuple | xmin | xmax |
|---|---|---|
| P | 100 | 140 |
| Q | 140 | 155 |
| R | 155 | 0 |

Commit log: 100 committed, 140 committed, 155 **still running**.
A reader holds snapshot `{xmin: 150, xmax: 156, xip: [155]}`.

Which tuple does that reader return? Name the exact clause of the visibility
rule that eliminates each of the other two.

**2.2** You are paged for disk growth. Which of these is holding the horizon,
and how confident can you be from this output alone?

```
 pid  | state               | xmin_age | duration | query
------+---------------------+----------+----------+---------------------------
 8812 | active              |    18422 | 00:04:12 | SELECT ... FROM orders ...
 9104 | idle in transaction |  9182734 | 06:41:55 | SELECT 1
 9330 | idle                |          | 00:00:02 | COMMIT
```

Then: name **three** horizon holders that would not appear in this view at all,
and the query you would run to find each.

**2.3**

```
Index Only Scan using idx_orders_customer on orders  (cost=... rows=41000 ...)
  Index Cond: (customer_id = 88)
  Heap Fetches: 40873
  Buffers: shared hit=812 read=39104
```

The plan says "Index Only Scan" and the query is slow. Say what is happening,
what single fact about the table this tells you, and what you would do about it.

---

## Rung 3 — Predict

Commit to an answer before checking.

**3.1** Transaction **A** begins. Transaction **B** begins. B updates row 5 and
commits. A now updates row 5. What happens to A — does it succeed, wait, or
fail? Does your answer change at `READ COMMITTED` versus `REPEATABLE READ`?

**3.2** A table has 5,000,000 dead tuples. A developer's `idle in transaction`
session has been open for four hours. You run `VACUUM VERBOSE` manually. Predict
the three most important numbers in its output before you read them.

**3.3** You `DELETE` 40% of a 60 GB table, `COMMIT`, confirm no long-running
transactions exist, and run `VACUUM` to completion. Predict `du` before and
after. Then predict what happens to the *next* 24 GB of inserts.

**3.4** A nightly job bulk-loads 20 million rows into `events` at 02:00. At
07:00 a dashboard query that uses an index-only scan on `events` is 15× slower
than it was yesterday. `EXPLAIN` shows the identical plan. Predict the cause,
and predict what `Heap Fetches` says.

---

## Rung 4 — Modify

Each of these changes one assumption. Say what breaks, what improves, and which
guarantee moves.

**4.1** Suppose the horizon were computed **per table** rather than globally — a
transaction that has only ever touched `orders` would not hold back cleanup on
`sessions`. What would that fix? What would it cost, and what would have to
become true about snapshots for it to be safe? (PostgreSQL has moved partway
toward this idea in recent versions; reason it out before you look anything up.)

**4.2** Suppose `fillfactor` defaulted to 80 instead of 100. Name one workload
that gets strictly better, one that gets strictly worse, and the metric you would
use to decide per table.

**4.3** Suppose transaction IDs were 64-bit. Which of the following disappear
entirely, which shrink, and which are untouched: freezing, `vacuum_freeze_min_age`,
the visibility map's all-frozen bit, anti-wraparound autovacuum, autovacuum's
refusal to yield to locks, `n_dead_tup` growth, the horizon?

---

## Rung 5 — Trace

**5.1** Fill in the table by hand. Starting state: row 12 = `Meera`, written by
transaction 50 (committed). Then, in order:

```
t1  T60 BEGIN
t2  T61 BEGIN
t3  T61 UPDATE plot 12 -> 'Vikram'
t4  T61 COMMIT
t5  T62 BEGIN
t6  T62 UPDATE plot 12 -> 'Priya'
t7  T60 SELECT plot 12
t8  T62 ROLLBACK
t9  T63 BEGIN ; SELECT plot 12
t10 T60 COMMIT
t11 T63 COMMIT
```

For each of t3, t6, t7, t8, t9, t10, t11 write down: (a) every tuple that exists with
its `xmin`/`xmax`, (b) which tuple each *running* transaction would read, and
(c) how many tuples are removable **at that moment**. Then check it against
scenario 1 and 2 in the visualization.

The interesting cell is the removable count at **t10**. Most people get it
wrong, and getting it right is the whole lesson of this rung.

**5.2** Take your state at t10 above. Walk VACUUM's four phases in order and say
precisely what each phase does — including which phase would be a no-op and why.
Then say what the file size is at the end, given it was 1 page at t1.

---

## Rung 6 — Spot the flaw

Each of these is *nearly* right. Find the smallest concrete scenario where it
fails, and name the guarantee it violates.

**6.1** A proposed cleanup rule:

> *"Remove any tuple whose xmax is set and whose xmax transaction has committed.
> This is obviously safe — the row was deleted and the deletion is durable."*

**6.2** A proposed optimisation:

> *"Index-only scans need the visibility map because index entries carry no
> visibility information. We can do better: store the tuple's `xmin` in the index
> entry. Then if `xmin` is older than the reader's snapshot, the entry is visible
> and we can skip the heap fetch."*

**6.3** A monitoring setup:

> *"We alert when `now() - last_autovacuum > 1 hour` on any table with more than
> a million rows. That catches vacuum problems."*

**6.4 (counterfactual)** Suppose the rule "a tuple may only be removed when its
xmax committed before the horizon" were weakened to "…before the horizon **or**
the tuple is older than one hour". Describe the smallest concrete situation in
which a query returns a **wrong** answer — not a stale one, a wrong one — and say
what the user sees.

---

## Rung 7 — Handle failure

These are design discussions, not single answers.

**7.1** `pg_dump` of your 800 GB primary takes six hours every night. During
those six hours the horizon does not move, and by morning the three hottest
tables have grown 12%. The dump is a compliance requirement and cannot be
dropped. Give at least three genuinely different approaches, and say what each
one costs and what it does *not* fix.

**7.2** Your analytics team runs 40-minute queries against a physical replica.
With `hot_standby_feedback = on`, the primary bloats. With it `off`, their
queries die with `canceling statement due to conflict with recovery` about a
third of the time. Frame this as a choice rather than a bug: state exactly what
each side buys and pays, name at least two other levers that change the shape of
the problem, and say what you would actually do.

**7.3** It is 02:00. A schema migration has been hanging for 40 minutes. Every
query against `payment_attempts` is queued behind it. `pg_stat_activity` shows an
autovacuum worker on that table whose query text ends `(to prevent wraparound)`.
Write the runbook: what you check, in what order, what you do, and — importantly
— what you must **not** do, with the reason.

**7.4** A table is being updated 8,000 times a second. `n_dead_tup` climbs
steadily all day and only falls overnight. There is no long-running transaction:
the oldest `backend_xmin` is seconds old. Diagnose it and give an ordered list of
fixes, cheapest and safest first.

---

## Rung 8 — Design

**8.1** Redesign the `payment_attempts` table from §1 of the README so that the
03:10 incident cannot recur. State your schema decision, what operation replaces
the nightly `DELETE`, what you now monitor, and — honestly — what new problems
your design introduces.

**8.2** You own a `sessions` table: 40 million rows, every row updated on every
request (~10,000 updates/second), read by primary key, plus one dashboard query
that filters on `last_seen_at`. Design the storage: indexes, fillfactor,
autovacuum settings, and anything structural. For each decision say which
mechanism in the README it is exploiting. Then name the one decision you are
least confident about and what measurement would settle it.

**8.3** You are choosing a database for a system with an OLTP workload and a
requirement that analysts can run 2–4 hour queries directly against the primary,
with a consistent view, without being cancelled. Argue the choice between a
PostgreSQL-style engine and an InnoDB/Oracle-style engine **purely on the MVCC
bill** — where the old versions live, who pays, and what the failure looks like
when the assumption breaks. Then say what you would need to measure before
committing.

**8.4 (counterfactual)** Design an MVCC engine with **no background cleanup
process at all**. You may change anything except the requirement that readers
never block writers and every reader gets a consistent view. What must you give
up? Name at least one real system that made a version of your trade.
