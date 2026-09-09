# Solutions — isolation levels

Every measured number here came from running the code in this lab against H2
2.4.240 or the toy engine. Where something is reasoning or vendor documentation
rather than a measurement, it says so.

---

## Rung 1 — Explain

**1.1** They have fixed *half-done work*: if the second statement fails, the
first is undone, and once it commits it survives a crash. They have not fixed
anything about the other clerk, because "one unit" says nothing about who else
is running at the same time. Isolation is a separate promise with its own dial,
and the default setting of that dial permits exactly the bug they closed —
measured, roughly **60 of 320 increments survive** at read committed, every
transaction committing successfully.

**1.2** Serializable means: *however the work actually overlapped, the ending is
one that could have been reached by doing the transactions one at a time, in
some order.* It does not say which order, and it does not say they actually ran
one at a time.

Running them genuinely one at a time is not the answer because it means serving
one user at a time. The whole field exists to get the appearance without the
cost.

**1.3**
- **Isolation vs atomicity** — atomicity is about *your* transaction being
  all-or-nothing across a failure; isolation is about *other people's*
  transactions being invisible to yours.
- **Isolation vs linearizability** — linearizability is one operation on one
  object, across machines, with a real-time rule; isolation is many operations
  on many rows, on one machine, with no real-time rule at all.
- **Snapshot isolation vs serializable** — snapshot checks what you *wrote*;
  serializable also checks what you *read*. The gap between them is exactly
  write skew.
- **Optimistic locking vs first-committer-wins** — the same rule, enforced in
  different places: your application comparing a version column, versus the
  engine comparing version timestamps.

**1.4** Because the database can see every row you touched, but it cannot see
the **rule you were checking**. Lost update is two transactions writing *the
same row*, which the engine notices for free. Write skew is two transactions
writing *different rows* after both reading a shared condition — and that
condition lives in an `if` statement in your application, somewhere the database
has never looked. The only way to catch it is to record what was **read**, which
is the one thing snapshot isolation does not do.

**1.5** It is a feature, and the sentence is: *"The database noticed that two
requests would have corrupted each other, stopped one of them before any damage
was done, and told us to run it again — which we do automatically."* If you
cannot say the last clause truthfully, you have bought the isolation level and
thrown away what it bought you.

---

## Rung 2 — Identify

**2.1** (a) non-repeatable read · (b) lost update · (c) write skew ·
(d) read skew · (e) dirty read.

The one worth dwelling on is (c): it is the only one where the two transactions
never touch the same row, which is why it is the only one that survives snapshot
isolation.

**2.2** Clerk 1 reads the version of `dr alice` and `dr bob` valid at moment 3;
Clerk 2 reads the versions valid at moment 4 — in this scenario the same two
versions, because nothing has been committed in between.

The first-committer-wins check does not fire because **it only looks at the rows
you wrote**. Clerk 1 wrote `dr alice`; no version of `dr alice` appeared after
Clerk 1's snapshot. Clerk 2 wrote `dr bob`; same story. Each check passes
honestly. The information that would have condemned them — that each had *read*
the row the other was about to change — was never recorded.

**2.3**
- read committed versus snapshot: `MiniDb.readTs`, the single line
  `return t.level == Level.READ_COMMITTED ? clock : t.startTs;`
- first-committer-wins: the loop in `MiniDb.commit` guarded by
  `if (v.begin > t.startTs)`
- the only place a read is recorded: `MiniDb.noteReadWriteConflicts` (fed by the
  `t.readKeys.add(key)` and `t.readPreds.add(...)` lines in `get` and `scan`,
  both of which run **only** under `SERIALIZABLE_SSI`)
- who gets refused: `MiniDb.isPivot` — `return in && out;`

**2.4** In the H2 matrix, `safe(err)` appears in the `RR`, `SNAP` and `SER`
columns for all three lost-update rows — nine cells.

What the application must have for every one of them: **a retry loop around the
whole transaction**, keyed on SQLSTATE `40001` (and `40P01` for deadlocks).
Without it, `safe(err)` is not "safe" from the user's point of view — it is a
500. See [`src/Retry.java`](src/Retry.java).

---

## Rung 3 — Predict

**3.1** **All 320 survive, with zero retries** — and it is the fastest option
measured (26 ms, against 131 ms for the broken read-then-write version).

A single `UPDATE` takes the row lock and *then* reads the current value, so
there is no gap for anyone to slip into. The lost update was never really about
the isolation level; it was about the application holding a value across a gap
it did not own. **Check whether the read can move into the write before you
reach for the dial.**

**3.2** `ALLOWED` at all five levels — including the one called `SERIALIZABLE`.

What that means for vendor documentation: **a level name is a label, not a
specification.** H2's own pull request adding these levels says the
implementation "prevents all three read phenomena, but doesn't ensure
equivalence of sequential and concurrent execution of transactions." The
behaviour is documented; it is just not documented in the name. Run the table
against your own database.

**3.3** Measured: **282 retries on 1 hot row, 1 retry on 16.** Roughly two
orders of magnitude, for identical work at an identical isolation level. Wall
clock went from 247 ms to 17 ms.

**3.4** `ALLOWED · ALLOWED · ALLOWED · safe(err) · safe(err)`.

`SERIALIZABLE(SSI)` is the first to stop it. What it is doing that snapshot
cannot: it recorded the **question** each clerk asked ("is room 1 free on
Monday?"), and when the other clerk inserted a row, it checked that row against
every remembered question. Snapshot isolation only compares row versions — and
when both clerks looked, **there was no row to compare**. That is why row locks
are no help here either: you cannot lock a row that does not exist.

**3.5** The anomaly disappears, at every level.

That is the most important control on the page. Nothing about the database
changed — the two transactions simply stopped overlapping. **Concurrency is the
input; the isolation level only decides what to do about it.** The cheapest fix
for a contention problem is often to stop the two things being concurrent at
all.

---

## Rung 4 — Modify

**4.1** Exactly one cell changes: **write skew at `SERIALIZABLE(SSI)` goes from
`safe(err)` to `ALLOWED`.** (Double-booking too, if you count it — both write-skew
rows.) `SERIALIZABLE(SSI)` has become **snapshot isolation**.

That is the whole of SSI in one method. Everything else — snapshots,
first-committer-wins, version chains — is shared with the level below. The
difference between "prevents everything" and "prevents everything except the
dangerous one" is a single piece of bookkeeping about reads.

**4.2** Three cells change, all in the `READ COMMITTED` column: **non-repeatable
read, phantom read and read skew all go from `ALLOWED` to `safe`.** Read
committed has become snapshot isolation, because that ternary *was* the only
difference.

The one that does not move is **lost update**, still `ALLOWED` at read
committed. A frozen view does not help you: both transactions read 100 from
their snapshots and both write 110. What fixes lost update at the snapshot level
is not the snapshot — it is the *first-committer-wins check at commit*, and this
edit did not touch that. Two independent mechanisms, often confused because they
arrive together in the same level.

**4.3** Read skew is a property of the **reader**, not the writer. The transfer
is already atomic — it commits both updates together, and no reader ever sees
half of it. The problem is that the *reader* took two separate looks at the
world, and the atomic transfer landed between them. Making the writer more
atomic cannot help, because the writer was never the one that was split.

The two things that fix it: give the reader **one snapshot** for both statements
(raise its level), or make the reader ask **one question** instead of two
(`SELECT SUM(balance) FROM acct WHERE id IN (1,2)` — a single statement is a
single moment at every level).

**4.4** Unreliable: the distinction between `safe`, `safe(wait)` and
`safe(err)`, because it is inferred from how long a step took. At a 1 ms
timeout, ordinary statements get misreported as blocked.

Completely trustworthy: **`ALLOWED` versus everything else.** That verdict is
computed from the values the transactions were actually given and the final
state of the table, neither of which depends on a clock.

So: you can quote the `ALLOWED` cells from any machine, in any state. If you
want to claim "this level prevents it *by waiting*" rather than just "prevents
it", run it on a quiet machine and raise the timeout.

---

## Rung 5 — Implement

**5.1** The method is a lock acquisition plus an ordinary read:

```java
Integer getForUpdate(Tx t, String key) {
    check(t);
    lockExclusive(t, key);          // at EVERY level, not just 2PL
    if (t.writes.containsKey(key)) return t.writes.get(key);
    return versionAt(key, db.clock);  // the newest committed, not your snapshot
}
```

Two details carry the whole idea. The lock is taken **at every level** — that is
what "pessimistic locking" means, and it is why `SELECT … FOR UPDATE` works even
at read committed. And it reads the **newest committed** value rather than your
snapshot, because reading your stale snapshot under a lock would give you a
correctly-locked wrong number.

Expect `safe(wait)` at `READ_UNCOMMITTED` and `READ_COMMITTED`, and `safe(err)`
at snapshot and above — which is exactly the H2 row
`Lost update, with SELECT ... FOR UPDATE`: `safe(wait) safe(wait) safe(err)
safe(err) safe(err)`. Two independent implementations, same answer.

**5.2** At commit, before stamping the writes, scan the rows this transaction is
adding and reject if another **committed** row already matches the predicate.

When a constraint beats an isolation level: whenever the rule can be expressed
about the *data* rather than about the *decision*. A constraint is checked on
every path into the table — including the migration script, the admin console
and the colleague's psql session — costs nothing at read committed, and cannot
be forgotten by a future caller. An isolation level protects only the code paths
that remember to ask for it.

**5.3** Expected shape: backoff **increases** wall-clock time at 1 hot row (you
are deliberately sleeping) but **reduces total retries**, because retrying
instantly into the same collision is what produced 282 attempts. At 16 rows it
changes almost nothing, because there was barely any contention to back off
from.

The lesson worth writing down: backoff trades latency for throughput, and it is
only worth it where contention is real. Measure before adding it.

---

## Rung 6 — Debug

**6.1** Nothing changes because **no version can ever have `begin == startTs`**.
Both values come from the same `clock++` counter, so every timestamp in the
engine is unique: a version's `begin` and a transaction's `startTs` are two
different draws from the same sequence and can never collide. The `>=` branch
is unreachable.

Could a test catch it? Not without breaking the invariant that makes it
unreachable — you would have to hand-construct a version and a transaction
sharing a timestamp, which the public API does not let you do. So the honest
answer is: **the bug is invisible because a stronger invariant holds elsewhere**,
and the right response is to write that invariant down as a comment next to the
counter, not to write a test for an impossible state.

This is worth internalising, because the reverse case is what kills you: a
"harmless" change becomes a real bug the day someone makes timestamps
non-unique — for example by taking them from a wall clock.

**6.2** The doctors table ends with **2 on call instead of 1**, and both
transactions are refused. Failing tests: `ssi_leaves_exactly_one_doctor_on_call`
and `ssi_refuses_exactly_one_transaction`.

It is a **waste, not a safety bug**: no incorrect state was ever committed. Once
Clerk 1 has been thrown out, its arrows describe a transaction that never
happened, so Clerk 2 is no longer part of any cycle and could safely have
committed. Counting the dead transaction's arrows refuses it for nothing.

In production this is the difference between one retry and two under
contention — and because each retry re-enters the same contention, the cost is
worse than linear. Real SSI implementations spend considerable effort on exactly
this: refusing as few transactions as possible while still refusing enough.

**6.3** **Dirty write** — writing over a change nobody has committed. Failing
tests: `dirty-write` at `READ_COMMITTED`, `SNAPSHOT` and `SERIALIZABLE_SSI`.

It is the one anomaly every standard forbids at every level because it breaks
something more basic than isolation: it breaks **rollback**. If Clerk 2 can
build on Clerk 1's uncommitted value, then Clerk 1's `ROLLBACK` cannot restore
the world — the undo would have to reach into a transaction that has already
committed. Atomicity itself stops being well-defined. Everything else on the
ladder is a matter of degree; this one is a matter of the model working at all.

**6.4** Something like:

> Nothing is broken — `40001` is what serializable *is*. It means the database
> caught two transactions that would have corrupted each other and stopped one
> before any damage was done. The fix is a retry loop around the whole
> transaction, not around the failing statement, and not a lower isolation
> level.
>
> Before changing anything, measure two things. First, **which rows** the
> failures cluster on: in our own measurements, the same workload spread over 1
> row versus 16 rows went from 282 retries to 1 — with no change to the
> isolation level. Contention is a data-access-pattern problem wearing an
> isolation costume. Second, **the retry rate per transaction type**: if one
> endpoint is responsible for all of it, fix that endpoint.
>
> And check the cheaper fixes first: can the read move into the write
> (`SET n = n + 1`)? Can the rule become a `UNIQUE` constraint? Both are free
> and neither needs serializable at all.

---

## Rung 7 — Handle failure

**7.1** Two transactions, both under snapshot isolation, transferring out of the
same account with an overdraft rule:

```
T1 read balance → 100          T2 read balance → 100
T1 checks 100 >= 80, OK        T2 checks 100 >= 80, OK
T1 UPDATE balance = 20
                               T2 UPDATE balance = 20   -> refused, 40001
```

Retrying only the failed `UPDATE` re-runs `balance = 20`, which succeeds this
time — and the account has paid out 160 from a balance of 100. **The decision
`100 >= 80` was made against data that is no longer true, and re-running the
statement carries that stale decision forward.**

The general rule: **a serialization failure invalidates everything your
transaction read, not just the statement that failed.** The retry must go back to
the reads, which means re-running the whole transaction — which in turn means
your transaction body must be a pure function of the data it reads, with no
side effects outside the database. That last clause is what makes retry loops
hard in real applications: the email you sent halfway through does not roll back.

**7.2** In order:

1. **Reduce the contention.** Find the hot row. Shard the counter, batch the
   updates, or move the read into the write. This is nearly always the real fix
   and it costs a code change, not throughput.
2. **Lower the level for this transaction and protect it differently** — a
   `UNIQUE` constraint, a conditional `UPDATE … WHERE status = 'free'`, or
   `SELECT … FOR UPDATE` on one row. Costs some correctness surface, which you
   must be able to name.
3. **Shed or queue the load.** If a hundred requests genuinely need the same row,
   they are going to take turns whatever you do; a queue makes the waiting
   explicit and fair instead of turning it into retries and timeouts. Costs
   latency and a component.

Raising the budget is wrong because retries *are* the contention: each attempt
re-enters the same collision, so more attempts make the collision worse. You are
adding load to a system that is failing from load.

**7.3** Lock the two accounts in a **fixed order** — always the lower id first —
regardless of the direction of the transfer. Then no two transactions can hold
what the other one wants, so the cycle cannot form.

Measured (`Experiments.deadlock`, 60 rounds of two opposing transfers):

| lock order | retries | gave up |
|---|---|---|
| whatever the transfer needs | 57 | 0 |
| always lowest id first | **0** | 0 |

Same isolation level, same work. **Deadlocks are a lock-ordering bug, not an
isolation-level problem**, and this is the fix that costs no throughput.

**7.4** The report holds an open snapshot for 40 minutes. Every version of every
row that anything overwrites during those 40 minutes must be kept, because your
report might still read it. Cleanup cannot advance past the oldest open
transaction, so 40 minutes of the whole database's writes accumulate as dead
versions — and they stay accumulated after your report finishes, until cleanup
catches up. The table grows while the row count does not, and every scan reads
the bloat.

The two settings: **`idle_in_transaction_session_timeout`** (kill sessions
holding a snapshot open while doing nothing) and **autovacuum's aggressiveness**
(`autovacuum_vacuum_cost_limit`, `autovacuum_naptime`). The structural fix is to
run long reports against a replica or a separate analytical copy so their
snapshots never pin the primary's cleanup — which is the point at which an
isolation-level decision becomes an architecture decision.

---

## Rung 8 — Design

One defensible position, with the reasoning exposed.

### 8.1 — A payments ledger

| Job | Level and mechanism | Why |
|---|---|---|
| balance check + debit | read committed + **conditional update**: `UPDATE acct SET balance = balance - :amt WHERE id = :id AND balance >= :amt`, then check rows-affected | one statement, one row, no gap. The rule is *inside* the write, so there is nothing to skew. No retries, no locks held across a round trip |
| idempotency-key check | read committed + **`UNIQUE` constraint** on the key, insert-first | the constraint decides the race, not the isolation level. A duplicate insert fails with a key violation you can catch, at every level, forever |
| monthly statement | `REPEATABLE READ`, read-only, **on a replica** | it needs one consistent moment across thousands of rows; on a replica so its snapshot cannot pin the primary's cleanup (7.4) |
| at most one active subscription | **serializable + retry**, or a partial unique index on `(customer_id) WHERE status = 'active'` | this is textbook write skew: two requests read "no active subscription", then insert *different rows*. Prefer the index — it is free and cannot be forgotten |
| promo code, first 1,000 redemptions | serializable + retry, or a counter row updated with `UPDATE … SET used = used + 1 WHERE used < 1000` | the conditional update turns a cross-row rule into a single-row one. Expect contention on the counter near the limit; shard it if the promo is large |
| fraud feature lookup | read committed, or no transaction at all | it is a model input, already approximate. Paying for consistency here buys nothing and costs latency on the hot path |

**Which would I make constraints instead?** The idempotency key, the single
active subscription, and any uniqueness rule. The reason is not performance —
it is **reach**. An isolation level protects the code paths that remember to ask
for it; a constraint protects the table. Six months from now, the data migration
and the support engineer's manual `INSERT` will both go through the constraint
and neither will go through your `@Transactional` annotation.

### 8.2 — PostgreSQL and MySQL/InnoDB

*(Vendor behaviour, from documentation — not measured in this lab.)*

1. **`REPEATABLE READ` does not mean the same thing.** On Postgres it is
   snapshot isolation throughout. On InnoDB, plain `SELECT` reads a snapshot but
   **`SELECT … FOR UPDATE`, `UPDATE` and `DELETE` read the latest committed
   row.** So a read-modify-write behaves differently on the two engines at the
   same named level. *What I would do:* never rely on `REPEATABLE READ` for a
   read-modify-write on either engine — use the conditional update, which is
   identical on both.
2. **`SERIALIZABLE` is a different mechanism.** Postgres uses SSI: it never
   blocks readers, and it fails at commit with `40001`. InnoDB turns every plain
   `SELECT` into a locking read: it blocks, and fails with lock-wait timeouts
   and deadlocks. *What I would do:* make the retry loop catch **both** families
   of error — serialization failure and deadlock — because which one you get
   depends on the engine, not on your code.
3. **Phantom prevention comes from different places.** Postgres gets it from the
   snapshot; InnoDB gets it from gap and next-key locks on *locking* reads,
   which also means InnoDB can deadlock on ranges where Postgres would not.
   *What I would do:* prefer constraints for uniqueness rules so neither
   mechanism is load-bearing.

The general lesson: **write the application against anomalies, not against level
names.** "This transaction must not lose an update" ports; "this transaction
uses REPEATABLE READ" does not.

### 8.3 — Not an isolation problem

**A customer-facing balance that looks briefly stale after a payment.** The
symptom is identical to a read skew or a non-repeatable read complaint — "I paid
and the balance is wrong" — and raising the isolation level will not fix a
single one of them, because the read is coming from a **replica** that is
seconds behind, or from a **cache**. Isolation is a one-node property; staleness
across nodes is the axis the [linearizability](../26-linearizability/) lab covers,
and no isolation level touches it.

What I would do instead: route the read that follows a write to the primary for
a short window (or pass the write's log position and wait for the replica to
reach it), and show an "as of" timestamp in the UI. What I give up: some of the
read-replica offload I bought the replica for, and a little latency on exactly
the requests that matter most.

The reason this belongs in the exercises: the two axes have almost identical
symptoms and completely different fixes, and reaching for the isolation dial
when the problem is replication is a very common and very expensive mistake.

### 8.4 — Proving what you have

1. **Run the matrix.** `java -cp ../lib/h2.jar:<driver>.jar -Ddb.url=… src/Matrix.java`
   against a scratch schema on a copy of the real database, at the real version.
   Ten anomalies, five levels, one command.
2. **Check the defaults you actually get** — not just the server default but
   what your connection pool, ORM and framework set. `SHOW transaction_isolation`
   from inside a real application transaction, not from psql.
3. **Find the write skews.** Grep the codebase for the shape: a `SELECT` whose
   result feeds an `if`, followed by an `UPDATE`/`INSERT` of a *different* row.
   Every one of those is a candidate, and no level below serializable protects
   any of them.
4. **Check for the retry loop.** If any code path uses a level above read
   committed and there is no retry on `40001`, that path is already broken and
   nobody has noticed yet, because it only fails under load.
5. **Measure contention** on the rows those paths touch, so you know what
   raising a level would cost before you raise it.

**In the runbook:** the matrix output with the database version next to it; the
list of transactions that run above read committed and where their retry loop
lives; the invariants currently protected by application code rather than
constraints (the write-skew candidates); and the alert thresholds for
serialization-failure rate, deadlock rate, and oldest-open-transaction age.

Re-run step 1 on every major database upgrade. **These behaviours change between
versions, and the level names do not.**
