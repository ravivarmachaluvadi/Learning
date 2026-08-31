# Solutions — MVCC and VACUUM

Worked answers. For the design rungs these are *a* line of reasoning, not the
only one; what matters is whether your answer names the right mechanism and the
right bill.

---

## Rung 1 — Explain

**1.1** *(one acceptable answer)*

> Changing a row in place destroys the value that used to be there. That is fine
> if only one person is looking, but a long report reading the table needs the
> table to hold still for as long as it runs. If the only way to make it hold
> still is to stop everyone else from writing, then a read-only report can halt
> the business for forty minutes. So instead of destroying the old value, the
> database writes the new one somewhere else and makes a note against the old one
> saying when it stopped being current. Readers that started earlier can still
> find the value they need, and writers never have to wait for them. The price is
> that the table now contains copies nobody needs any more, and something has to
> come along later and clear them out.

Self-check: the words you were forbidden are the *names*; the paragraph above
earns each of them without using any.

**1.2** The right half: autovacuum genuinely does cause I/O and CPU, and on an
under-tuned system the spikes are real — usually because the default
`autovacuum_vacuum_scale_factor` of 0.2 lets 20% of a large table die before
anything happens, so each vacuum is enormous.

The wrong half: turning it off does not remove the work, it **defers and
compounds** it. Three things then happen. Dead tuples accumulate with no ceiling,
so every sequential scan and every cached page gets progressively more expensive.
The visibility map stops being maintained, so index-only scans start fetching the
heap. And most seriously, freezing stops — so eventually
`autovacuum_freeze_max_age` is crossed and an **anti-wraparound** autovacuum
starts *regardless of your setting*, does not throttle itself the way a normal
one does, and **does not yield to lock requests**. You have converted a
predictable continuous cost into an unpredictable one that arrives at the worst
moment.

The actual fix for their observation is the opposite: make vacuum run *more*
often on those tables (lower the scale factor) so each run is small, and raise
`vacuum_cost_limit` so the runs finish instead of dragging.

**1.3** All five are responses to the same thing: **in an MVCC system somebody
must retain old row versions until the oldest live snapshot no longer needs
them, and the amount to retain is controlled by a client the database does not
control.**

The decision that splits them: **is retention bounded?** PostgreSQL, InnoDB and
SQL Server retain **whatever the oldest snapshot demands** and pay in unbounded
space — bloat, history list length, `tempdb` growth. Oracle and CockroachDB
**bound retention** (`UNDO_RETENTION`, `gc.ttlseconds`) and pay by **failing the
slow reader** — `ORA-01555`, `must be after replica GC threshold`. There is no
third option: either the space is unbounded or the reader can be refused.
(InnoDB sits nearer the first camp; Oracle nearer the second, though both use
undo.)

---

## Rung 2 — Identify

**2.1** The reader returns **Q**.

- **R** is eliminated by the `xip` clause of step 1: `xmin = 155` is in the
  in-progress list, so its creator is invisible, full stop.
- **P** is eliminated by step 2: its `xmax = 140` is below the snapshot's xmax,
  not in `xip`, and committed — so the *deletion* is visible to this reader, and
  a tuple whose deletion you can see is not there for you.
- **Q** passes both: `xmin = 140` is committed and visible (step 1), and
  `xmax = 155` is in `xip`, so the deletion is **not** visible (step 2) — from
  this reader's point of view Q has not been deleted.

Note what that last line means physically: another transaction is *right now*
deleting Q, and this reader is serenely reading it with no lock and no wait.

**2.2** **pid 9104** is the horizon holder — `idle in transaction`, 6h41m,
`backend_xmin` age of 9.1 million transactions against pid 8812's 18 thousand.
The `query` column shows `SELECT 1`, which is the giveaway: that is the *last*
statement it ran, not what it is doing. It is doing nothing.

Confidence from this output alone: high for "9104 is the oldest of the three",
but **not** high for "9104 is the oldest in the system", because
`pg_stat_activity` only shows backends. The three horizon holders it cannot see:

| holder | query |
|---|---|
| a replication slot (a CDC consumer, a decommissioned replica) | `SELECT slot_name, active, xmin, catalog_xmin FROM pg_replication_slots;` |
| a prepared transaction left by a failed 2PC coordinator | `SELECT * FROM pg_prepared_xacts;` |
| a replica's query, via `hot_standby_feedback` | `SELECT application_name, backend_xmin FROM pg_stat_replication;` |

The third is the cruel one: the cause is on a different machine.

**2.3** `Heap Fetches: 40873` against ~41,000 rows means the index-only scan
fetched the heap for **essentially every row** — it did all the work of a plain
index scan plus the visibility-map lookups, for nothing.

The one fact it tells you: **the pages holding those rows are not marked
all-visible in the visibility map**, which means vacuum has not processed them
since they were last written. The table has recently been written to (or bulk
loaded) and autovacuum has not caught up.

What to do: run `VACUUM orders` (not `VACUUM FULL`) and re-check. If it recurs,
lower that table's `autovacuum_vacuum_scale_factor`, and — if the table is
insert-mostly on an older major version — schedule a periodic manual `VACUUM`,
because before PostgreSQL 13 an insert-only table never crossed autovacuum's
dead-tuple threshold at all.

The transferable lesson: **whether vacuum ran recently is an input to query
performance, not only to disk usage.**

---

## Rung 3 — Predict

**3.1** A **waits**, blocking on B's transaction until B ends. This is
first-committer-wins implemented physically: A tries to stamp row 5's `xmax`,
finds a live claim there, and waits on the lock.

Because B has already committed by the time A tries, what happens next depends on
the level, and the difference is exactly the one you measured in
[isolation-levels](../isolation-levels/):

- `READ COMMITTED`: A re-reads the **newest** committed version, re-checks its
  `WHERE` clause against it, and proceeds. This succeeds — and it is precisely
  the behaviour that permits **lost update**.
- `REPEATABLE READ` / `SERIALIZABLE`: A gets `ERROR: could not serialize access
  due to concurrent update` (SQLSTATE **40001**) and must retry the whole
  transaction.

So: same physical mechanism, two different policies layered on it.

**3.2** Predict:

1. **Removable tuples: ~0** — or a small number from before the developer's
   session began.
2. **"nonremovable" / "dead but not yet removable": ~5,000,000**, with a line
   naming the oldest xmin.
3. **Pages removed / truncated: 0.**

The verbose output tells you this directly with a phrase like *"N dead row
versions cannot be removed yet, oldest xmin: 12345678"* — that number is the
horizon, and it is the number to take to the incident channel.

**3.3** `du` **before ≈ 60 GB. After ≈ 60 GB.** Vacuum removed 24 GB worth of
tuples and returned essentially nothing to the filesystem, because the freed
space is scattered through pages in the middle of the file and only *trailing*
empty pages can be truncated.

The next 24 GB of inserts: they consume **almost no new disk**, because the free
space map directs them into the reclaimed slots. That is the whole point — the
correct outcome of vacuum is that the table *stops growing*, not that it shrinks.

If you actually need the 24 GB back today: `pg_repack` (brief lock, needs ~2×
space) or `VACUUM FULL` (long `ACCESS EXCLUSIVE` lock, needs ~2× space). If you
need it back *every* month, the answer is not a tool, it is **partitioning** —
make the operation a `DROP` of a partition.

**3.4** The cause: the bulk load wrote 20 million rows, which cleared the
all-visible bit on every page it touched, and autovacuum has not yet re-set them
(and on a pre-13 major version, an insert-only load may not have triggered
autovacuum at all).

`Heap Fetches` will be **large — roughly equal to the number of rows the scan
returns**, where yesterday it was 0 or near it.

The fix is a plain `VACUUM events` at the end of the load job. Note that it
removes *no dead tuples whatsoever* — its entire value here is setting
visibility-map bits.

---

## Rung 4 — Modify

**4.1 Per-table horizon.** What it fixes: the worst property of the current
design — that a transaction touching one table pins cleanup on tables it has
never referenced. The developer at lunch would only bloat what they actually
looked at.

What it costs and what must become true: a snapshot would have to record *which
relations it can still reach*, and that is the hard part — a transaction can
issue an arbitrary new query at any moment, so the database cannot know in
advance that it will never touch `sessions`. To make it safe you would need
either (a) transactions to declare their relation set up front and be refused if
they exceed it, or (b) a mechanism to detect that a transaction has been pinned
out of a relation and abort it if it later tries — which is Oracle's
`ORA-01555` in a new costume, i.e. you have moved from the unbounded-space camp
to the fail-the-reader camp.

PostgreSQL has taken partial steps in this direction (notably distinguishing the
horizon for regular tables, catalog access, temporary tables and replication
slots) rather than a full per-relation horizon. *Check your version's notes; the
detail here moves.*

**4.2 `fillfactor` default 80.**

- **Strictly better:** an update-heavy table with no index on the updated
  columns — every update that fits becomes HOT, so no index entries are written,
  index bloat stops, and vacuum can clean the chains inside the page with no
  index scan.
- **Strictly worse:** an append-only table (events, logs, audit). Every page is
  20% empty forever, for no benefit at all: 20% more disk, 20% more pages per
  sequential scan, 20% less useful cache.
- **The metric:** the ratio of `n_tup_upd` to `n_tup_ins` in
  `pg_stat_user_tables`, and specifically `n_tup_hot_upd` — if HOT updates are a
  small fraction of total updates on an update-heavy table, lowering fillfactor
  is likely to pay. That is a per-table decision, which is why the *default* is
  100: the default should be the one that is never actively harmful.

**4.3 64-bit XIDs.**

| | effect |
|---|---|
| freezing | **gone as a correctness requirement.** (A concept like it might survive as an optimisation for skipping visibility checks, but the doomsday clock disappears.) |
| `vacuum_freeze_min_age` | gone |
| all-frozen bit | mostly gone as a freezing device; the **all-visible** bit is unaffected and still does the index-only-scan job |
| anti-wraparound autovacuum | **gone** |
| autovacuum refusing to yield to locks | **gone** — that behaviour exists *only* for anti-wraparound |
| `n_dead_tup` growth | **completely untouched** |
| the horizon | **completely untouched** |

That table is the point of the exercise: 64-bit XIDs would delete PostgreSQL's
scariest failure mode and **would not fix bloat at all**, because bloat is caused
by the horizon, which has nothing to do with counter width. Two failure families
that people habitually merge are in fact independent.

---

## Rung 5 — Trace

**5.1**

| after | tuples (row 12) | T60 reads | T61 reads | T62 reads | T63 reads | removable |
|---|---|---|---|---|---|---|
| t3 | A(50,61) · B(61,0) | Meera | Vikram | — | — | 0 |
| t6 | A(50,61) · B(61,62) · C(62,0) | Meera | — | Priya | — | 0 |
| t7 | same | **Meera** | — | Priya | — | 0 |
| t8 | A(50,61) · B(61,62✗) · C(62✗,0) | Meera | — | — | — | 0 |
| t9 | same | Meera | — | — | **Vikram** | 0 |
| t10 | same | (committed) | — | — | Vikram | **0** |
| t11 | same | — | — | — | — | **2** |

(✗ marks a stamp written by a transaction that aborted.)

Three things worth extracting:

- **At t8 the rollback resurrected B.** B's `xmax = 62` still sits in the tuple
  header, physically unchanged; it simply stopped meaning anything the moment
  62's commit-log entry said "aborted". Nothing walked back over the data.
- **At t9, T60 and T63 read different values simultaneously and both are
  right.** T63 reads `Vikram` because 61 committed before it began and 62
  aborted; T60 reads `Meera` because 61 began after *it* did.
- **The t10 cell is the trap.** Most people write "now T60 has committed, so the
  horizon moves and A becomes removable." It does not. **T63's snapshot recorded
  `xmin = 60`** when it began, because 60 was running at that moment, and T63 is
  still holding that snapshot. The horizon is the minimum over *snapshots*, not
  over *live transactions* — so T60's exit changes nothing until T63 also
  releases. Only at t11 does the horizon jump to 64, making A (xmax 61,
  committed) and C (xmin 62, aborted) both removable: **2**.

  *(Nuance worth knowing: at `READ COMMITTED` a new snapshot is taken per
  statement, so a mostly-idle T63 would refresh its snapshot on its next
  statement and release the horizon then. At `REPEATABLE READ` it holds for the
  whole transaction. Same trap, different duration.)*

**5.2** At t10, with the horizon still at 60:

- **Phase 1 (scan):** reads every page, evaluates every tuple, finds **0**
  removable. Not a no-op — it did all the reading.
- **Phase 2 (index):** the dead-TID list is empty, so **0** entries are deleted —
  but a vacuum still has to decide there is nothing to do, and in the general case
  this is where the full index scans happen. This is the phase that makes
  "vacuum ran and did nothing" expensive rather than free.
- **Phase 3 (reclaim):** genuine no-op.
- **Phase 4 (freeze):** tuple A has `xmin = 50` against a current XID around 64 —
  an age of ~14, nowhere near `vacuum_freeze_min_age`. No tuple is frozen.

**File size at the end: still 1 page.** Nothing was removed, nothing was freed,
nothing was truncated, and the vacuum reported success. That is exactly the
shape of the incident in README §1 — and it is why the first query on a bloat
page is `pg_stat_activity`, not `last_autovacuum`.

---

## Rung 6 — Spot the flaw

**6.1** Smallest failing scenario: **two transactions.**

`T1` begins and takes a snapshot. `T2` updates row 5 and commits. Now row 5's
old tuple has a committed `xmax`, so the rule removes it. `T1` reads row 5: the
new version is invisible to it (created after its snapshot), and the old version
no longer exists. **T1 sees no row 5 at all** — a row that has existed
continuously throughout.

Guarantee violated: **snapshot stability** — "every reader sees a complete
picture of the data as it stood at one moment, no matter how long it takes". Note
that the failure is not staleness; it is a *missing row*, and a report that
silently omits a payment is worse than one that is an hour old.

The missing clause is exactly the horizon: `xmax` must have committed **before
the oldest live snapshot**, not merely committed.

**6.2** The flaw: it handles creation and ignores deletion.

Smallest failing scenario: a row created by an ancient transaction (`xmin = 100`,
long committed) and **deleted** yesterday (`xmax = 900`, committed). The index
entry is still present, because index entries are only removed by vacuum's phase
2, which has not run. A reader with a current snapshot checks the stored
`xmin = 100`, finds it comfortably older than its snapshot, declares the entry
visible, and **returns a deleted row**.

There is also a second, subtler failure even for live rows: an index entry
pointing at a tuple whose creating transaction later **aborted** would be
returned, since `xmin` alone says nothing about the outcome.

This is precisely why the visibility map answers a different question. It does
not ask *"is this entry visible?"* — it asks *"is this whole page visible to
everybody?"*, which is a claim about *all* tuples on the page, deletions
included, and which only vacuum is in a position to make.

**6.3** What it catches: a table that autovacuum has stopped visiting entirely —
starved by locks (§20 failure 7), or with `autovacuum = off` set on it.

What it misses, all of which are more common:

- **Vacuum running perfectly and removing nothing**, because the horizon is
  pinned. `last_autovacuum` updates every few minutes and looks healthy while the
  table triples in size. This is the single most common real incident.
- **Wraparound**, which has nothing to do with vacuum *frequency* and everything
  to do with `age(relfrozenxid)`.
- **Vacuum falling behind**: it runs constantly and still loses, so
  `last_autovacuum` is always fresh while `n_dead_tup` climbs.
- Big tables where the 20% scale factor means "recently" still allows enormous
  bloat.

The four metrics that do cover it: `n_dead_tup` per large table,
`max(age(relfrozenxid))`, the oldest `backend_xmin` age, and the count of
inactive replication slots.

**6.4** The weakened rule removes a tuple whose `xmax` committed more than an
hour ago even if a snapshot older than that still exists.

Smallest situation: a reporting transaction begins at 09:00 and takes 90 minutes.
At 09:05 a row is deleted and committed. At 10:06 vacuum removes it under the
"older than one hour" clause. At 10:20 the report reaches that row's table.

The user sees: **a report that is internally inconsistent and silently wrong.**
Rows the report already counted in an earlier query are missing from a later one;
a `SUM` of balances does not match the `SUM` of the transactions that produced
them; a reconciliation fails with no error anywhere and no way to reproduce it,
because by the time anyone looks the tuple is gone and the state that would
explain the discrepancy no longer exists.

That is the difference between *stale* and *wrong*, and it is why the horizon
rule has no time component at all.

---

## Rung 7 — Handle failure

**7.1** Three genuinely different approaches:

1. **Move the dump off the primary.** Take it from a physical replica with
   `hot_standby_feedback = off`. *Cost:* the dump can be cancelled by recovery
   conflicts and needs retry logic; you need the replica. *Does not fix:* if you
   turn feedback on to stop the cancellations, you have moved the problem back to
   the primary unchanged.
2. **Stop using `pg_dump` for this.** Use a physical/base backup
   (`pg_basebackup`, or a filesystem/volume snapshot with WAL archiving). A
   physical backup does not hold a long MVCC snapshot in the same way, and it is
   usually far faster for 800 GB. *Cost:* it is an all-or-nothing cluster-level
   artifact, not a selective logical dump; if compliance specifically requires a
   logical export, this does not satisfy it. *Does not fix:* the need to prove
   restorability, which you must now test differently.
3. **Shrink what has to be dumped.** Partition the large tables and dump only
   the partitions that changed, or move cold data to a separate database.
   *Cost:* a schema migration and more moving parts. *Does not fix:* the hot
   partitions still take time.

A fourth, weaker option: **accept the bloat and budget for it** — lower the scale
factor and raise the cost limit so the 12% is cleaned up promptly each morning,
and size the disk for the sawtooth. This is a legitimate answer if the growth is
bounded and reclaimed daily.

What *none* of them fixes: as long as one process needs a consistent view of
800 GB for six hours, **something** retains six hours of history. You are only
choosing where.

**7.2** The frame: `hot_standby_feedback` decides **which machine pays for the
replica's long snapshots.**

- **On:** the primary retains versions the replica's queries need. Buys:
  analytics queries always complete. Pays: primary bloat, proportional to query
  duration × primary write rate.
- **Off:** the primary cleans up on its own schedule; when replay needs to
  remove a version a replica query still needs, that query is cancelled. Buys: a
  clean primary. Pays: a third of the analytics work is thrown away.

Two other levers that change the shape rather than picking a side:

- **`max_standby_streaming_delay`** — how long replay is allowed to wait before
  cancelling. Raising it buys the queries more time at the cost of replica lag
  (and therefore a longer failover recovery). This is a third position, not a
  compromise between the first two.
- **A dedicated replica.** Point analytics at a replica with feedback off and a
  large standby delay, kept out of the failover set. Its lag no longer matters.
- **Logical replication or a separate warehouse.** Removes the coupling entirely
  and is the real answer if this workload is permanent.

What I would do: dedicated analytics replica, `hot_standby_feedback = off`,
generous `max_standby_streaming_delay`, excluded from failover — and start
planning the warehouse, because 40-minute queries against an OLTP schema is a
signal about architecture, not about vacuum.

**7.3 Runbook.**

Check, in order:

1. `pg_stat_activity` — confirm the autovacuum worker on `payment_attempts` and
   confirm its query text says `(to prevent wraparound)`.
2. `SELECT age(relfrozenxid) FROM pg_class WHERE relname = 'payment_attempts'` —
   how urgent is it, really? Past 1 billion is serious; near 2 billion is an
   emergency.
3. `pg_stat_progress_vacuum` — which phase, how far through. This is the
   difference between "20 more minutes" and "6 more hours".
4. `pg_locks` — confirm the migration is what everything else is queued behind.

Do:

- **Cancel the migration**, not the vacuum. `pg_cancel_backend()` on the DDL
  releases the queue immediately, and every normal query resumes — a normal
  autovacuum holds only `SHARE UPDATE EXCLUSIVE` and blocks nothing you care
  about.
- Then let the anti-wraparound vacuum finish. On PostgreSQL 14+ check whether
  `vacuum_failsafe_age` has kicked in; if not and it is urgent, raising
  `vacuum_cost_limit` (or setting the delay to 0) for this table speeds it up
  substantially and can be changed live.
- Afterwards: find out **why** it reached the anti-wraparound threshold. That is
  the actual incident. It is nearly always a pinned horizon (§20 failures 1–5)
  or a table where autovacuum was disabled or starved.

Do **not**:

- **Kill the vacuum.** It will restart within a minute, having lost its progress,
  and the wraparound clock has not stopped. You have made it strictly worse.
- **Raise `autovacuum_freeze_max_age`** to make the situation go away. That is
  disabling the alarm, not the fire, and it shortens the time you have left.
- **Restart the database** hoping to clear it. Recovery does not freeze anything,
  and the anti-wraparound vacuum starts again immediately.

**7.4** No long-running transaction means the horizon is fine, so this is
**failure 6: cleanup throughput below garbage production**, not a horizon
problem. `n_dead_tup` falling overnight confirms it — vacuum wins when the write
rate drops, so it is not blocked, just outrun.

Ordered fixes:

1. **Lower that table's `autovacuum_vacuum_scale_factor`** to ~0.01 with a fixed
   threshold. Cheapest, safest, per-table, reversible. Small frequent vacuums
   instead of rare huge ones.
2. **Raise `vacuum_cost_limit`** (globally or per table). The default 200 is a
   speed limit set for hardware two decades old; vacuum is very often not stuck
   but throttled.
3. **Restore HOT.** Check `n_tup_hot_upd` against `n_tup_upd` in
   `pg_stat_user_tables`. If the HOT fraction is low: drop any index on a
   frequently-updated column, and set `fillfactor` to 80–90. This attacks the
   *production* of garbage rather than its removal, which is strictly better.
4. **Drop unused indexes** (`pg_stat_user_indexes`, `idx_scan = 0`). Every index
   dropped removes one full index scan from every future vacuum, permanently.
5. **Raise `autovacuum_max_workers`** — last, and only together with the cost
   limit, because the cost budget is shared among workers: more workers on the
   same budget simply means each goes slower.
6. **Structural:** if the row is updated on every request purely to record
   something like `last_seen_at`, consider whether that write belongs in the
   database at all, or in a cache flushed periodically. The cheapest dead tuple
   is the one never created.

---

## Rung 8 — Design

**8.1 `payment_attempts`.**

- **Schema:** range-partition by month (or week) on `created_at`, using
  declarative partitioning. Indexes become per-partition.
- **Retention:** the nightly `DELETE ... WHERE created_at < now() - 90 days`
  becomes `DROP TABLE payment_attempts_2026_05` (or `DETACH` then `DROP`). This
  returns the file to the filesystem **instantly**, creates **zero** dead tuples,
  generates almost no WAL, needs no vacuum, and does not require 2× free space.
  Every symptom in §1 is designed out rather than tuned around.
- **Monitor:** `n_dead_tup` on the *current* partition only (the old ones are
  read-only and static); `age(relfrozenxid)` across all partitions — note the old
  partitions still need to be frozen, and on PostgreSQL 13+ the
  insert-based autovacuum trigger handles that, while on older versions you need
  a scheduled `VACUUM (FREEZE)`; oldest `backend_xmin`; partition creation
  succeeding ahead of time.
- **New problems, honestly:** partition maintenance is now a job that can fail
  (a missing future partition means failing inserts — use `pg_partman` or a
  tested scheduled task); queries that do not filter on `created_at` now scan
  every partition, so the planner's pruning must be verified on your real
  queries; unique constraints must include the partition key; and cross-partition
  foreign keys have limitations. Partitioning is a real cost, paid for a real
  return.

**8.2 `sessions`, 10k updates/sec.**

| decision | mechanism it exploits |
|---|---|
| **No index on `last_seen_at`** — serve the dashboard from a replica, a materialised view, or a periodic snapshot table | HOT: an index on the updated column disables it for every update on the table (§16) |
| **Primary-key index only** | fewer indexes = fewer entries per non-HOT update *and* fewer index scans per vacuum (§13) |
| **`fillfactor = 80`** | reserves in-page space so HOT updates have somewhere to land (§16) |
| **`autovacuum_vacuum_scale_factor = 0.005`, `threshold = 5000`** | 0.2 on 40M rows means 8M dead tuples before anything happens (§18) |
| **`autovacuum_vacuum_cost_delay = 0` (or a much higher cost limit) for this table** | vacuum is throttled by default, and here it must win a race (§13c) |
| **`idle_in_transaction_session_timeout`** | protects the horizon from application bugs (§20.1) |
| **Consider moving the write out of PostgreSQL entirely** — session heartbeats in Redis, flushed periodically | the cheapest dead tuple is the one never created |

The decision I would be least confident about: **`fillfactor = 80`.** It costs
25% more pages immediately, and its benefit depends entirely on whether the
updates actually become HOT. The measurement that settles it:
`n_tup_hot_upd / n_tup_upd` from `pg_stat_user_tables` before and after, together
with table size and `n_dead_tup` trend. If the HOT fraction does not rise
materially, revert it — something else (a forgotten index, rows too wide for the
reserved space) is blocking HOT and the fillfactor is pure cost.

**8.3 Choosing the engine on the MVCC bill.**

The requirement — *2–4 hour consistent reads against the primary, never
cancelled* — is a requirement about **retention**, so read it as: *"I need the
system whose retention is bounded by the reader, not by a setting."*

- **PostgreSQL-style (versions in the heap).** The reader is **guaranteed to
  complete**; there is no `snapshot too old` on modern versions, so this maps
  onto the requirement exactly. The bill: 2–4 hours of every table's garbage is
  retained, every table, whether or not the query touches it — plus the
  visibility-map and index-only-scan side effects. Failure when the assumption
  breaks: **disk fills; the database stops accepting writes.** Loud, and
  recoverable by adding disk.
- **InnoDB/Oracle-style (versions in undo).** The current table stays compact, so
  the OLTP side is unaffected by the long reader — genuinely attractive here. But
  the long reader is reconstructing old versions by walking undo chains that grow
  for the whole four hours, so **the analytics query itself gets progressively
  slower**, and on Oracle it can be **killed outright by `ORA-01555`** if undo is
  recycled. That directly contradicts the stated requirement. On InnoDB you would
  be watching `History list length` climb for four hours, and purge lag has its
  own cliff.

**On the MVCC bill alone, PostgreSQL fits the stated requirement better**,
because the requirement is "never cancel the reader" and PostgreSQL is the camp
that never cancels the reader.

What to measure before committing: the primary's **write rate during the
analytics window** (that, times four hours, *is* the retention bill in bytes —
if it is 400 GB the answer changes); how much of the workload the analytics
query actually touches; whether the consistent view genuinely must be *the
primary* rather than a replica or a warehouse; and, if the answer is yes, whether
partitioning can keep the churn away from the tables being read.

**8.4 MVCC with no background cleanup.**

You must give up **unbounded retention** — that is forced, because the *only*
alternative to cleaning up later is not accumulating in the first place, or
bounding how much can accumulate.

Three coherent designs:

1. **Clean up inline.** Every reader that walks past a provably-dead version
   removes it on the spot. PostgreSQL already does a version of this
   (opportunistic HOT pruning, and hint bits). *Given up:* predictable read
   latency — reads now do write work and can dirty pages — and it only cleans
   what someone happens to read, so cold garbage lives forever.
2. **Bound retention by time and refuse late readers.** Keep versions for T
   seconds; a reader that outlives T is aborted. *Given up:* the guarantee that
   a reader always completes. This is Oracle's `ORA-01555` and CockroachDB's
   `gc.ttlseconds`, and it is the most widely shipped answer.
3. **Never delete anything; make history the product.** An append-only,
   immutable store where every version is addressable forever, with cold data
   tiered to cheap storage. *Given up:* bounded storage, and the ability to
   actually delete data — which in a world of retention regulation is a
   substantive, not theoretical, loss. **Datomic** is the well-known system built
   on this trade; append-only event logs and
   [event-sourcing](../event-sourcing/) are the same idea at the application
   layer.

The meta-answer, and the one worth keeping: **the cleanup process is not an
implementation wart you could design away with more cleverness.** It is where the
cost of "never destroy the past" is recognised. Remove the process and the cost
reappears as a refused reader, a slower read path, or an unbounded disk bill.
